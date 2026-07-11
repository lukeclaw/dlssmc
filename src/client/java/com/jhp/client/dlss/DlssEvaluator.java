package com.jhp.client.dlss;

import com.jhp.DLSSmc;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.LongBuffer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * P2-3 — the per-frame DLSS evaluate path. Replaces the P1-5 NEAREST upscale blit.
 *
 * <p><b>Frame flow</b> (recorded at {@code LevelRenderer.render} RETURN, right after the
 * velocity passes, while the level depth is still valid — the renderItemInHand depth
 * clear happens later):</p>
 * <ol>
 *   <li>{@code slGetNewFrameToken}</li>
 *   <li>{@code slSetConstants} — unjittered matrices from {@link DlssMotion}
 *       ({@code clipToPrevClip} is literally {@code DlssMotion.reprojectionMatrix()}),
 *       jitter from {@link DlssJitter}, REVERSE-Z ({@code depthInverted=true}),
 *       camera-motion-included MVs in UV space ({@code mvecScale} converts to pixels)</li>
 *   <li>{@code slSetTagForFrame} — depth = level D32 (GENERAL), MV = velocity RG16F,
 *       scaling input = level color, scaling output = our own STORAGE VkImage
 *       (renderpearl's usage enum has no STORAGE bit, so Mojang textures can't be a
 *       DLSS/UAV output — we create the output image raw via LWJGL)</li>
 *   <li>{@code slEvaluateFeature(kFeatureDLSS)} into a transient command buffer obtained
 *       from {@link VulkanCommandEncoder#allocateAndBeginTransientCommandBuffer()};
 *       {@code encoder.execute(cb)} closes Mojang's open buffer first, so ordering is
 *       world-render → evaluate → hand/HUD, exactly right</li>
 *   <li>{@code vkCmdCopyImage} output → native color (usage 15 = TRANSFER_DST ok; all
 *       renderpearl images live permanently in VK_IMAGE_LAYOUT_GENERAL)</li>
 * </ol>
 *
 * <p>On success the caller restores the main render target EARLY (before
 * renderItemInHand), so the hand+HUD render at native res on top of the DLSS output and
 * the level-depth clear becomes harmless. On any SL failure the evaluator marks itself
 * broken and the old NEAREST blit path takes over.</p>
 *
 * <p>ABI offsets follow the SlBridge javadoc conventions (BaseStructure header = 32 B).
 * Verified against SDK v2.12.0 headers 2026-07-11: Constants v2 (456 B), Resource v1
 * (112 B), ResourceTag v1 (64 B), ViewportHandle v1 (40 B), DLSSOptions v3 (88 B).</p>
 */
public final class DlssEvaluator {
    private DlssEvaluator() {}

    /** Master switch (/dlssmc dlss). */
    public static volatile boolean enabled = true;

    // ---- P2-5 runtime tuning knobs (/dlssmc mvx|mvy|jx|jy) ------------------------------
    // mvecScale is a NORMALIZER into [-1,1] (see fillConstants) — magnitude is unity;
    // only the SIGNS are tunable. The buffer stores (prevNdc - currNdc) * 0.5 in
    // GL-style NDC (y-UP); if the consumed space is image-style y-DOWN the y sign must
    // be negative (current default). Toggle live to A/B.
    public static volatile float mvSignX = 1f;
    public static volatile float mvSignY = -1f;
    /** Sign of the jitterOffset REPORTED to SL (applied matrix jitter unchanged). */
    public static volatile float jitterSignX = 1f;
    public static volatile float jitterSignY = 1f;

    /**
     * DLSS mode override (/dlssmc mode): -1 = auto by scale (<=0.55 -> MaxPerformance,
     * else MaxQuality). Exists to decouple the render-scale variable from the DLSS
     * model variable when diagnosing artifacts (sl_dlss.h DLSSMode values).
     */
    public static volatile int modeOverride = -1;

    public static String modeName(int m) {
        return switch (m) {
            case 0 -> "Off"; case 1 -> "MaxPerformance"; case 2 -> "Balanced";
            case 3 -> "MaxQuality"; case 4 -> "UltraPerformance"; case 5 -> "UltraQuality";
            case 6 -> "DLAA"; default -> "auto";
        };
    }

    /** Set true after an unrecoverable SL error; falls back to the NEAREST blit. */
    private static boolean broken = false;
    private static boolean optionsSent = false;
    private static int optW, optH, optMode = -1;
    private static String lastError = "none";
    private static long frames = 0;

    // ---- long-lived native structs (render thread only) --------------------------------
    private static final Arena A = Arena.global();
    private static final MemorySegment VIEWPORT = A.allocate(40, 8);
    private static final MemorySegment CONSTANTS = A.allocate(456, 8);
    private static final MemorySegment RESOURCES = A.allocate(4 * 112, 8);
    private static final MemorySegment TAGS = A.allocate(4 * 64, 8);
    private static final MemorySegment EVAL_INPUTS = A.allocate(ADDRESS);
    private static final MemorySegment DLSS_OPTIONS = A.allocate(88, 8);
    private static final float[] MAT_TMP = new float[16];
    private static boolean structsInitialized = false;

    // ---- our DLSS output image (STORAGE usage; recreated on resize) ---------------------
    private static long outImage, outMemory, outView;
    private static int outImgW, outImgH;
    private static boolean outNeedsInit; // pending UNDEFINED -> GENERAL transition

    private static final int VK_FORMAT_R8G8B8A8_UNORM = 37;
    private static final int VK_FORMAT_R16G16_SFLOAT = 83;
    private static final int VK_FORMAT_D32_SFLOAT = 126;
    private static final int VK_IMAGE_LAYOUT_GENERAL = 1;

    public static String status() {
        return (broken ? "BROKEN (" + lastError + ")" : enabled ? "on" : "off")
                + ", frames evaluated: " + frames;
    }

    /** P2-5 knob readout for the /dlssmc command feedback. */
    public static String knobs() {
        return "mvSign=(" + (int) mvSignX + "," + (int) mvSignY
                + ") jitterSign=(" + (int) jitterSignX + "," + (int) jitterSignY + ")";
    }

    /**
     * Called from LevelRendererMixin at render RETURN (velocity passes already recorded).
     * Returns true if the DLSS output now sits in the native target (caller restores the
     * main target early and skips the blit).
     */
    public static boolean evaluate(TextureTarget levelTarget, RenderTarget nativeTarget) {
        if (!enabled || broken || levelTarget == null || nativeTarget == null
                || !SlBridge.isInstanceProxied() || !DlssResolution.enabled()
                || DlssVelocity.velocityTarget() == null) {
            return false;
        }
        try {
            int inW = levelTarget.width, inH = levelTarget.height;
            int outWpx = nativeTarget.width, outHpx = nativeTarget.height;

            initStructsOnce();
            if (!sendOptionsIfNeeded(outWpx, outHpx)) {
                return false;
            }
            ensureOutputImage(outWpx, outHpx);

            long token = SlBridge.getNewFrameToken();
            if (token == 0L) {
                return fail("slGetNewFrameToken returned null");
            }

            fillConstants(inW, inH);
            int r = SlBridge.setConstants(CONSTANTS, token, VIEWPORT);
            if (r != SlBridge.SL_OK) {
                return fail("slSetConstants -> " + r);
            }

            VulkanCommandEncoder encoder = (VulkanCommandEncoder)
                    RenderSystem.getDevice().createCommandEncoder().backend();
            VkCommandBuffer cb = encoder.allocateAndBeginTransientCommandBuffer();

            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (outNeedsInit) {
                    transitionOutputToGeneral(cb, stack);
                    outNeedsInit = false;
                }
                globalBarrier(cb, stack); // world render + velocity writes -> DLSS reads

                fillResourcesAndTags(levelTarget, nativeTarget);
                r = SlBridge.setTagForFrame(token, VIEWPORT, TAGS, 4, cb.address());
                if (r != SlBridge.SL_OK) {
                    abandon(cb, encoder);
                    return fail("slSetTagForFrame -> " + r);
                }

                EVAL_INPUTS.set(ADDRESS, 0, VIEWPORT);
                r = SlBridge.evaluateDlss(token, EVAL_INPUTS, 1, cb.address());
                if (r != SlBridge.SL_OK) {
                    abandon(cb, encoder);
                    return fail("slEvaluateFeature -> " + r);
                }

                globalBarrier(cb, stack); // DLSS writes -> copy reads
                copyOutputToNative(cb, stack, nativeTarget, outWpx, outHpx);
                globalBarrier(cb, stack); // copy writes -> subsequent hand/HUD reads
            }

            VK12.vkEndCommandBuffer(cb);
            encoder.execute(cb);

            if (frames == 0) {
                DLSSmc.LOGGER.info("[DLSSmc] DLSS evaluate LIVE: {}x{} -> {}x{}", inW, inH, outWpx, outHpx);
            }
            frames++;
            return true;
        } catch (Throwable t) {
            broken = true;
            lastError = String.valueOf(t);
            DLSSmc.LOGGER.error("[DLSSmc] DLSS evaluate threw; falling back to NEAREST blit", t);
            return false;
        }
    }

    /** Allow retry after /dlssmc dlss toggle (e.g. after a fix or driver update). */
    public static void reset() {
        broken = false;
        optionsSent = false;
        optMode = -1;
        lastError = "none";
    }

    // -------------------------------------------------------------------------------------

    private static boolean fail(String why) {
        broken = true;
        lastError = why;
        DLSSmc.LOGGER.error("[DLSSmc] {} — DLSS disabled, NEAREST blit fallback active (sl_result.h)", why);
        return false;
    }

    /** End + submit an already-allocated cmd buffer so the frame stays consistent. */
    private static void abandon(VkCommandBuffer cb, VulkanCommandEncoder encoder) {
        VK12.vkEndCommandBuffer(cb);
        encoder.execute(cb);
    }

    private static void initStructsOnce() {
        if (structsInitialized) {
            return;
        }
        // ViewportHandle {171B6435-9B3C-4FC8-9994-FBE52569AAA4} v1, value=0 @32
        header(VIEWPORT, 0x171b6435, (short) 0x9b3c, (short) 0x4fc8,
                new byte[] { (byte) 0x99, (byte) 0x94, (byte) 0xfb, (byte) 0xe5, 0x25, 0x69, (byte) 0xaa, (byte) 0xa4 }, 1);
        VIEWPORT.set(JAVA_INT, 32, 0);
        // Constants {DCD35AD7-4E4A-4BAD-A90C-E0C49EB23AFE} v2
        header(CONSTANTS, 0xdcd35ad7, (short) 0x4e4a, (short) 0x4bad,
                new byte[] { (byte) 0xa9, 0x0c, (byte) 0xe0, (byte) 0xc4, (byte) 0x9e, (byte) 0xb2, 0x3a, (byte) 0xfe }, 2);
        // Resource {3A9D70CF-2418-4B72-8391-13F8721C7261} v1 (x4)
        for (int i = 0; i < 4; i++) {
            header(RESOURCES.asSlice(i * 112L, 112), 0x3a9d70cf, (short) 0x2418, (short) 0x4b72,
                    new byte[] { (byte) 0x83, (byte) 0x91, 0x13, (byte) 0xf8, 0x72, 0x1c, 0x72, 0x61 }, 1);
        }
        // ResourceTag {4C6A5AAD-B445-496C-87FF-1AF3845BE653} v1 (x4)
        for (int i = 0; i < 4; i++) {
            MemorySegment tag = TAGS.asSlice(i * 64L, 64);
            header(tag, 0x4c6a5aad, (short) 0xb445, (short) 0x496c,
                    new byte[] { (byte) 0x87, (byte) 0xff, 0x1a, (byte) 0xf3, (byte) 0x84, 0x5b, (byte) 0xe6, 0x53 }, 1);
            tag.set(ADDRESS, 32, RESOURCES.asSlice(i * 112L, 112)); // resource*
            tag.set(JAVA_INT, 44, 2); // lifecycle = eValidUntilEvaluate
            // extent @48..63 stays zeroed = whole resource
        }
        // DLSSOptions {6AC826E4-4C61-4101-A92D-638D421057B8} v3
        header(DLSS_OPTIONS, 0x6ac826e4, (short) 0x4c61, (short) 0x4101,
                new byte[] { (byte) 0xa9, 0x2d, 0x63, (byte) 0x8d, 0x42, 0x10, 0x57, (byte) 0xb8 }, 3);
        structsInitialized = true;
    }

    private static void header(MemorySegment s, int d1, short d2, short d3, byte[] d4, long version) {
        s.set(ADDRESS, 0, MemorySegment.NULL);
        s.set(JAVA_INT, 8, d1);
        s.set(JAVA_SHORT, 12, d2);
        s.set(JAVA_SHORT, 14, d3);
        MemorySegment.copy(d4, 0, s, JAVA_BYTE, 16, 8);
        s.set(JAVA_LONG, 24, version);
    }

    private static boolean sendOptionsIfNeeded(int outWpx, int outHpx) throws Throwable {
        int mode = modeOverride >= 0 ? modeOverride
                : DlssResolution.scale <= 0.55f ? 1 /*eMaxPerformance*/ : 3 /*eMaxQuality*/;
        if (optionsSent && optW == outWpx && optH == outHpx && optMode == mode) {
            return true;
        }
        MemorySegment o = DLSS_OPTIONS;
        o.set(JAVA_INT, 32, mode);                     // mode
        o.set(JAVA_INT, 36, outWpx);                   // outputWidth
        o.set(JAVA_INT, 40, outHpx);                   // outputHeight
        o.set(JAVA_FLOAT, 44, 0.0f);                   // sharpness (deprecated)
        o.set(JAVA_FLOAT, 48, 1.0f);                   // preExposure
        o.set(JAVA_FLOAT, 52, 1.0f);                   // exposureScale
        o.set(JAVA_BYTE, 56, (byte) 0);                // colorBuffersHDR = eFalse (LDR RGBA8)
        o.set(JAVA_BYTE, 57, (byte) 0);                // indicatorInvertAxisX
        o.set(JAVA_BYTE, 58, (byte) 0);                // indicatorInvertAxisY
        for (int off = 60; off <= 80; off += 4) {
            o.set(JAVA_INT, off, 0);                   // all presets = eDefault
        }
        o.set(JAVA_BYTE, 84, (byte) 1);                // useAutoExposure = eTrue (no exposure tag)
        o.set(JAVA_BYTE, 85, (byte) 0);                // alphaUpscalingEnabled
        int r = SlBridge.dlssSetOptions(VIEWPORT, o);
        if (r != SlBridge.SL_OK) {
            return fail("slDLSSSetOptions -> " + r);
        }
        DLSSmc.LOGGER.info("[DLSSmc] slDLSSSetOptions OK: mode={} output={}x{}",
                modeName(mode), outWpx, outHpx);
        optionsSent = true;
        optW = outWpx;
        optH = outHpx;
        optMode = mode;
        return true;
    }

    private static void fillConstants(int renderW, int renderH) {
        MemorySegment c = CONSTANTS;
        Matrix4f proj = DlssMotion.currentProjection();
        // JOML is column-major with y=M*x; SL float4x4 is row-major with the usual
        // NVIDIA y=x*M convention — writing JOML's raw (column-major) storage as SL rows
        // IS the required transpose. matrix.get(float[]) emits column-major.
        putMatrix(c, 32, proj);                                            // cameraViewToClip
        putMatrix(c, 96, new Matrix4f(proj).invert());                     // clipToCameraView
        putMatrix(c, 160, new Matrix4f());                                 // clipToLensClip = I
        putMatrix(c, 224, DlssMotion.reprojectionMatrix());                // clipToPrevClip
        putMatrix(c, 288, new Matrix4f(DlssMotion.reprojectionMatrix()).invert()); // prevClipToClip

        c.set(JAVA_FLOAT, 352, jitterSignX * DlssJitter.pixelOffsetX());   // jitterOffset
        c.set(JAVA_FLOAT, 356, jitterSignY * DlssJitter.pixelOffsetY());
        // mvecScale NORMALIZES the MV buffer into [-1,1] where 1.0 = full screen
        // (sl_consts.h:203; DLSS guide: pixel-space MVs -> {1/renderW, 1/renderH}).
        // Our buffer stores UV-space deltas (full screen = 1.0) — ALREADY normalized —
        // so the scale is unity. The original {renderW, renderH} (an inversion of the
        // convention) fed MVs ~10^3x too large: history rejected at speed (raw low-res
        // shimmer), wrong-history blends at low speed (deceleration noise swaths) —
        // M5 iteration-2 diagnosis, 2026-07-11. Signs remain live-tunable
        // (/dlssmc mvx|mvy); y defaults negative (GL NDC y-up vs image-space y-down).
        c.set(JAVA_FLOAT, 360, mvSignX);                                   // mvecScale.x
        c.set(JAVA_FLOAT, 364, mvSignY);                                   // mvecScale.y
        c.set(JAVA_FLOAT, 368, 0f);                                        // cameraPinholeOffset
        c.set(JAVA_FLOAT, 372, 0f);

        c.set(JAVA_FLOAT, 376, (float) DlssMotion.cameraX());              // cameraPos
        c.set(JAVA_FLOAT, 380, (float) DlssMotion.cameraY());
        c.set(JAVA_FLOAT, 384, (float) DlssMotion.cameraZ());
        Matrix4f vr = DlssMotion.currentViewRotation();
        // Camera basis in world space = rows of the world->view rotation.
        c.set(JAVA_FLOAT, 388, vr.m01()); c.set(JAVA_FLOAT, 392, vr.m11()); c.set(JAVA_FLOAT, 396, vr.m21()); // up = row1
        // (offsets: up @388, right @400, fwd @412 — see below)
        c.set(JAVA_FLOAT, 400, vr.m00()); c.set(JAVA_FLOAT, 404, vr.m10()); c.set(JAVA_FLOAT, 408, vr.m20()); // right = row0
        c.set(JAVA_FLOAT, 412, -vr.m02()); c.set(JAVA_FLOAT, 416, -vr.m12()); c.set(JAVA_FLOAT, 420, -vr.m22()); // fwd = -row2 (GL looks down -Z)

        float m00 = proj.m00(), m11 = proj.m11();
        float near = proj.m32();                                           // REVERSE-Z: ndc.z = near/dist
        c.set(JAVA_FLOAT, 424, near > 0 ? near : 0.05f);                   // cameraNear
        c.set(JAVA_FLOAT, 428, 10000f);                                    // cameraFar (heuristic only)
        c.set(JAVA_FLOAT, 432, (float) (2.0 * Math.atan(1.0 / m11)));      // cameraFOV (vertical, rad)
        c.set(JAVA_FLOAT, 436, m11 / m00);                                 // cameraAspectRatio
        c.set(JAVA_FLOAT, 440, 3.40282346638528859812e38f);                // motionVectorsInvalidValue (unused)

        c.set(JAVA_BYTE, 444, (byte) 1);                                   // depthInverted = eTrue (REVERSE-Z)
        c.set(JAVA_BYTE, 445, (byte) 1);                                   // cameraMotionIncluded = eTrue
        c.set(JAVA_BYTE, 446, (byte) 0);                                   // motionVectors3D = eFalse
        c.set(JAVA_BYTE, 447, (byte) (DlssJitter.consumeReset() ? 1 : 0)); // reset (P2-4 hook)
        c.set(JAVA_BYTE, 448, (byte) 0);                                   // orthographicProjection
        c.set(JAVA_BYTE, 449, (byte) 0);                                   // motionVectorsDilated
        c.set(JAVA_BYTE, 450, (byte) 0);                                   // motionVectorsJittered (MV pass uses unjittered matrices)
        c.set(JAVA_FLOAT, 452, 40.0f);                                     // minRelativeLinearDepthObjectSeparation
    }

    private static void putMatrix(MemorySegment c, long offset, Matrix4f m) {
        m.get(MAT_TMP); // column-major float[16] == SL row-major transpose (see fillConstants)
        MemorySegment.copy(MAT_TMP, 0, c, JAVA_FLOAT, offset, 16);
    }

    private static void fillResourcesAndTags(TextureTarget level, RenderTarget nativeTarget) {
        TextureTarget velocity = DlssVelocity.velocityTarget();
        // slot 0: depth (level D32) — kBufferTypeDepth = 0
        fillResource(0, (VulkanGpuTexture) level.getDepthTexture(),
                (VulkanGpuTextureView) level.getDepthTextureView(), VK_FORMAT_D32_SFLOAT,
                level.width, level.height);
        TAGS.set(JAVA_INT, 0 * 64 + 40, 0);
        // slot 1: motion vectors (velocity RG16F) — kBufferTypeMotionVectors = 1
        fillResource(1, (VulkanGpuTexture) velocity.getColorTexture(),
                (VulkanGpuTextureView) velocity.getColorTextureView(), VK_FORMAT_R16G16_SFLOAT,
                velocity.width, velocity.height);
        TAGS.set(JAVA_INT, 1 * 64 + 40, 1);
        // slot 2: scaling input color (level RGBA8) — kBufferTypeScalingInputColor = 3
        fillResource(2, (VulkanGpuTexture) level.getColorTexture(),
                (VulkanGpuTextureView) level.getColorTextureView(), VK_FORMAT_R8G8B8A8_UNORM,
                level.width, level.height);
        TAGS.set(JAVA_INT, 2 * 64 + 40, 3);
        // slot 3: scaling output color (our STORAGE image) — kBufferTypeScalingOutputColor = 4
        fillResourceRaw(3, outImage, outView, VK_FORMAT_R8G8B8A8_UNORM, outImgW, outImgH,
                VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT
                        | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        TAGS.set(JAVA_INT, 3 * 64 + 40, 4);
    }

    private static void fillResource(int slot, VulkanGpuTexture tex, VulkanGpuTextureView view,
            int vkFormat, int w, int h) {
        // renderpearl usage 15 = COPY_DST|COPY_SRC|TEXTURE_BINDING|RENDER_ATTACHMENT
        int vkUsage = VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                | VK12.VK_IMAGE_USAGE_SAMPLED_BIT
                | (vkFormat == VK_FORMAT_D32_SFLOAT
                        ? VK12.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
                        : VK12.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
        fillResourceRaw(slot, tex.vkImage(), view.vkImageView(), vkFormat, w, h, vkUsage);
    }

    private static void fillResourceRaw(int slot, long vkImage, long vkImageView, int vkFormat,
            int w, int h, int vkUsage) {
        MemorySegment r = RESOURCES.asSlice(slot * 112L, 112);
        r.set(JAVA_BYTE, 32, (byte) 0);                                   // type = eTex2d
        r.set(JAVA_LONG, 40, vkImage);                                    // native = VkImage
        r.set(JAVA_LONG, 48, 0L);                                         // memory (nullptr ok)
        r.set(JAVA_LONG, 56, vkImageView);                                // view = VkImageView
        r.set(JAVA_INT, 64, VK_IMAGE_LAYOUT_GENERAL);                     // state (renderpearl keeps GENERAL)
        r.set(JAVA_INT, 68, w);
        r.set(JAVA_INT, 72, h);
        r.set(JAVA_INT, 76, vkFormat);
        r.set(JAVA_INT, 80, 1);                                           // mipLevels
        r.set(JAVA_INT, 84, 1);                                           // arrayLayers
        r.set(JAVA_LONG, 88, 0L);                                         // gpuVirtualAddress
        r.set(JAVA_INT, 96, 0);                                           // VkImageCreateFlags
        r.set(JAVA_INT, 100, vkUsage);                                    // VkImageUsageFlags
        r.set(JAVA_INT, 104, 0);                                          // reserved
    }

    // ---- raw Vulkan: DLSS output image (STORAGE), barriers, copy -------------------------

    private static void ensureOutputImage(int w, int h) {
        if (outImage != 0 && outImgW == w && outImgH == h) {
            return;
        }
        VkDevice device = DlssRenderState.device();
        if (outImage != 0) {
            VK12.vkDeviceWaitIdle(device); // prototype-grade resize; rare event
            VK12.vkDestroyImageView(device, outView, null);
            VK12.vkDestroyImage(device, outImage, null);
            VK12.vkFreeMemory(device, outMemory, null);
            outImage = outView = outMemory = 0;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK12.VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK12.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK12.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT
                            | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
            ici.extent().set(w, h, 1);
            LongBuffer pImage = stack.callocLong(1);
            vkCheck(VK12.vkCreateImage(device, ici, null, pImage), "vkCreateImage(dlss-out)");
            long image = pImage.get(0);

            VkMemoryRequirements memReq = VkMemoryRequirements.calloc(stack);
            VK12.vkGetImageMemoryRequirements(device, image, memReq);
            VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), memProps);
            int typeIndex = -1;
            for (int i = 0; i < memProps.memoryTypeCount(); i++) {
                if ((memReq.memoryTypeBits() & (1 << i)) != 0
                        && (memProps.memoryTypes(i).propertyFlags() & VK12.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) {
                    typeIndex = i;
                    break;
                }
            }
            if (typeIndex < 0) {
                throw new IllegalStateException("no DEVICE_LOCAL memory type for DLSS output image");
            }
            VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(memReq.size())
                    .memoryTypeIndex(typeIndex);
            LongBuffer pMem = stack.callocLong(1);
            vkCheck(VK12.vkAllocateMemory(device, mai, null, pMem), "vkAllocateMemory(dlss-out)");
            vkCheck(VK12.vkBindImageMemory(device, image, pMem.get(0), 0), "vkBindImageMemory(dlss-out)");

            VkImageViewCreateInfo vci = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(image)
                    .viewType(VK12.VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM);
            vci.subresourceRange().set(VK12.VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1);
            LongBuffer pView = stack.callocLong(1);
            vkCheck(VK12.vkCreateImageView(device, vci, null, pView), "vkCreateImageView(dlss-out)");

            outImage = image;
            outMemory = pMem.get(0);
            outView = pView.get(0);
            outImgW = w;
            outImgH = h;
            outNeedsInit = true;
            DLSSmc.LOGGER.info("[DLSSmc] DLSS output image created: {}x{} RGBA8 STORAGE", w, h);
        }
    }

    private static void transitionOutputToGeneral(VkCommandBuffer cb, MemoryStack stack) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                .oldLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                .srcAccessMask(0)
                .dstAccessMask(VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT)
                .srcQueueFamilyIndex(-1)
                .dstQueueFamilyIndex(-1)
                .image(outImage);
        barrier.subresourceRange().set(VK12.VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1);
        VK12.vkCmdPipelineBarrier(cb, VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, barrier);
    }

    /** Global everything->everything barrier — matches renderpearl's own sync style. */
    private static void globalBarrier(VkCommandBuffer cb, MemoryStack stack) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK12.VK_ACCESS_MEMORY_WRITE_BIT)
                .dstAccessMask(VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT);
        VK12.vkCmdPipelineBarrier(cb, VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, barrier, null, null);
    }

    private static void copyOutputToNative(VkCommandBuffer cb, MemoryStack stack,
            RenderTarget nativeTarget, int w, int h) {
        long dst = ((VulkanGpuTexture) nativeTarget.getColorTexture()).vkImage();
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.srcSubresource().set(VK12.VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
        region.dstSubresource().set(VK12.VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
        region.extent().set(w, h, 1);
        VK12.vkCmdCopyImage(cb, outImage, VK_IMAGE_LAYOUT_GENERAL, dst, VK_IMAGE_LAYOUT_GENERAL, region);
    }

    private static void vkCheck(int result, String what) {
        if (result != 0) {
            throw new IllegalStateException(what + " failed: VkResult=" + result);
        }
    }
}
