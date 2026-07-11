package com.jhp.client.dlss;

import com.jhp.DLSSmc;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.resources.Identifier;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Motion-vector velocity target (P1-7, Approach B — slice 1: camera-only).
 *
 * <p>Owns the RG16-float velocity {@link TextureTarget} DLSS will consume, plus a
 * full-screen pass that fills it with <b>camera-only</b> velocity. The whole
 * reprojection chain — un-project current NDC (at an assumed mid depth, matching
 * {@code DlssMotion.debugSanityCheck}), shift by the camera delta, re-project with the
 * previous view-proj — is folded into a <b>single matrix</b> on the CPU
 * ({@link DlssMotion#reprojectionMatrix()}); perspective division's invariance under
 * homogeneous scaling makes this exact. One mat4 means the pass reuses Mojang's own
 * {@link ProjectionMatrixBuffer} (std140 upload) and the stock
 * {@code BindGroupLayouts.PROJECTION} layout — no custom UBO plumbing. The shader block
 * mirrors vanilla's {@code Projection / ProjMat} naming.</p>
 *
 * <p><b>Accuracy note:</b> with a fixed assumed depth this is exact for camera
 * <i>rotation</i> (depth-independent) and approximate for camera <i>translation</i>.
 * Slice 2 (MRT velocity written during the geometry pass — see
 * {@code docs/IMPLEMENTATION_GUIDE.md} §3) replaces the assumed depth with real geometry
 * and adds per-object motion. This slice exists to stand up the target, the pass, and
 * the sign/space conventions so slice 2 only has to swap the source of truth.</p>
 */
public final class DlssVelocity {
    private DlssVelocity() {}

    /** Master switch for the velocity pass. */
    public static volatile boolean enabled = true;
    /** Overlay the velocity target on screen (toggled by the /dlssmc mv client command). */
    public static volatile boolean showDebug = false;

    /** Assumed NDC depth for un-projection; MUST match DlssMotion.debugSanityCheck (0.5). */
    public static final float ASSUMED_DEPTH = 0.5f;

    private static TextureTarget velocityTarget;
    private static RenderPipeline velocityPipeline;
    private static RenderPipeline debugPipeline;
    private static ProjectionMatrixBuffer reprojectionBuffer;
    private static boolean loggedOnce;

    /** The RG16F velocity target at the internal (DLSS input) resolution, or null. */
    public static TextureTarget velocityTarget() {
        return velocityTarget;
    }

    /** Run the camera-velocity full-screen pass at {@code width}x{@code height}. */
    public static void render(int width, int height) {
        TextureTarget target = ensureTarget(width, height);
        if (reprojectionBuffer == null) {
            reprojectionBuffer = new ProjectionMatrixBuffer("dlss-reprojection");
        }
        GpuBufferSlice reprojection = reprojectionBuffer.getBuffer(DlssMotion.reprojectionMatrix());
        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipeline(velocityPipeline());
        if (!loggedOnce) {
            loggedOnce = true;
            DLSSmc.LOGGER.info("[DLSSmc] velocity pass: {}x{} compiled={}", width, height, compiled);
        }
        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "DLSS velocity", target.getColorTextureView(),
                        Optional.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(compiled);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Projection", reprojection);
            pass.draw(3, 1, 0, 0);
        }
    }

    /**
     * Composite the velocity field translucently over the scene into {@code dest}.
     * {@code sceneSource} is the low-res level target (sampled for the scene under the
     * overlay — we cannot sample {@code dest} while rendering into it). Skipped when
     * resolution decoupling is off (no separate scene texture to composite from).
     */
    public static void blitDebug(RenderTarget sceneSource, RenderTarget dest) {
        if (velocityTarget == null || sceneSource == null || dest == null) {
            return;
        }
        GpuTextureView scene = sceneSource.getColorTextureView();
        GpuTextureView velocity = velocityTarget.getColorTextureView();
        GpuTextureView out = dest.getColorTextureView();
        if (scene == null || velocity == null || out == null) {
            return;
        }
        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipeline(debugPipeline());
        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "DLSS velocity debug", out,
                        Optional.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(compiled);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("Sampler0", scene, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.bindTexture("Sampler1", velocity, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
    }

    private static TextureTarget ensureTarget(int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        if (velocityTarget == null) {
            // Depth attachment unused by the fullscreen pass; kept for ctor simplicity.
            velocityTarget = new TextureTarget("dlss-velocity", w, h, GpuFormat.RG16_FLOAT, GpuFormat.D32_FLOAT);
        } else if (velocityTarget.width != w || velocityTarget.height != h) {
            velocityTarget.resize(w, h);
        }
        return velocityTarget;
    }

    private static RenderPipeline velocityPipeline() {
        if (velocityPipeline == null) {
            velocityPipeline = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath("dlssmc", "pipeline/velocity"))
                    .withVertexShader("core/screenquad")
                    .withFragmentShader(Identifier.fromNamespaceAndPath("dlssmc", "core/dlss_velocity"))
                    .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                    // Format must match the RG16F attachment (Gate-C crash confirmed
                    // DEFAULT is RGBA8). No blending; write all channels.
                    .withColorTargetState(new ColorTargetState(
                            Optional.empty(), GpuFormat.RG16_FLOAT, ColorTargetState.WRITE_ALL))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withCull(false)
                    .build();
        }
        return velocityPipeline;
    }

    private static RenderPipeline debugPipeline() {
        if (debugPipeline == null) {
            debugPipeline = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath("dlssmc", "pipeline/velocity_debug"))
                    .withVertexShader("core/screenquad")
                    .withFragmentShader(Identifier.fromNamespaceAndPath("dlssmc", "core/dlss_velocity_debug"))
                    .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withCull(false)
                    .build();
        }
        return debugPipeline;
    }
}
