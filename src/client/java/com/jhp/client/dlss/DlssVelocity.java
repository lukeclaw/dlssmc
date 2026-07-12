package com.jhp.client.dlss;

import com.jhp.DLSSmc;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
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
 * Motion-vector velocity target (P1-7, depth-sampled approach).
 *
 * <p>Owns the RG16-float velocity {@link TextureTarget} DLSS consumes, plus a
 * full-screen pass that fills it with per-pixel velocity by sampling the
 * <b>level depth buffer</b>. The reprojection chain — un-project current NDC
 * (using the actual depth from the buffer), shift by the camera delta,
 * re-project with the previous view-proj — is folded into a single matrix
 * ({@link DlssMotion#reprojectionMatrix()}); perspective division's invariance
 * under homogeneous scaling makes this exact even without knowing clipW. This
 * single pass replaces the old two-pass approach (camera-only fallback + terrain
 * geometry prepass), eliminating the expensive opaque-geometry replay.</p>
 */
public final class DlssVelocity {
    private DlssVelocity() {}

    /** Master switch for the velocity pass. */
    public static volatile boolean enabled = true;
    /** Overlay the velocity target on screen (toggled by the /dlssmc mv client command). */
    public static volatile boolean showDebug = false;

    private static TextureTarget velocityTarget;
    private static RenderPipeline velocityPipeline;
    private static RenderPipeline debugPipeline;
    private static ProjectionMatrixBuffer reprojectionBuffer;
    private static boolean loggedOnce;

    /** The RG16F velocity target at the internal (DLSS input) resolution, or null. */
    public static TextureTarget velocityTarget() {
        return velocityTarget;
    }

    /** Run the depth-sampled velocity full-screen pass at {@code width}x{@code height}. */
    public static void render(int width, int height, GpuTextureView depthView) {
        if (depthView == null) {
            return;
        }
        TextureTarget target = ensureTarget(width, height);
        if (reprojectionBuffer == null) {
            reprojectionBuffer = new ProjectionMatrixBuffer("dlss-reprojection");
        }
        GpuBufferSlice reprojection = reprojectionBuffer.getBuffer(DlssMotion.reprojectionMatrix());
        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipeline(velocityPipeline());
        if (!loggedOnce) {
            loggedOnce = true;
            DLSSmc.LOGGER.info("[DLSSmc] depth velocity pass: {}x{} compiled={}", width, height, compiled);
        }
        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "DLSS velocity", target.getColorTextureView(),
                        Optional.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(compiled);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Projection", reprojection);
            pass.bindTexture("InSampler", depthView,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
    }

    /**
     * Draw the velocity tint translucently OVER the finished image in {@code dest}
     * (alpha-blended). Deliberately does NOT sample/re-draw the scene: the first
     * version reconstructed the screen from the half-res level texture, which softened
     * the image and ghosted mismatched scene content through opaque blocks (Gate D
     * 2026-07-10). Blending against the real backbuffer makes ghosting impossible and
     * works with or without resolution decoupling.
     */
    public static void blitDebug(RenderTarget dest) {
        if (velocityTarget == null || dest == null) {
            return;
        }
        GpuTextureView velocity = velocityTarget.getColorTextureView();
        GpuTextureView out = dest.getColorTextureView();
        if (velocity == null || out == null) {
            return;
        }
        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipeline(debugPipeline());
        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "DLSS velocity debug", out,
                        Optional.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(compiled);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", velocity, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
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
                    .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
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
                    .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
                    // Alpha-blend the tint over the finished frame (see blitDebug).
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withCull(false)
                    .build();
        }
        return debugPipeline;
    }
}
