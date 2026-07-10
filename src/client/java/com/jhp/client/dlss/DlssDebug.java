package com.jhp.client.dlss;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Debug render passes for validating the custom-shader GPU plumbing that the
 * motion-vector pass (P1-7) needs: building a {@link RenderPipeline} from a custom
 * {@code dlssmc}-namespace shader, compiling it via {@code getCompiledPipeline}, and
 * running a full-screen pass that binds a sampler and draws.
 *
 * <p>{@link #showDepth} visualizes the scene depth as grayscale — if it renders, the
 * pipeline/shader/sampler path works (and it doubles as the P1-10 depth sanity check).</p>
 */
public final class DlssDebug {
    private DlssDebug() {}

    /** Temporary: overwrite the color image with a grayscale depth view for verification. */
    public static volatile boolean showDepth = true;

    private static RenderPipeline pipeline;

    private static RenderPipeline pipeline() {
        if (pipeline == null) {
            pipeline = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath("dlssmc", "pipeline/depth_debug"))
                    .withVertexShader("core/screenquad")
                    .withFragmentShader(Identifier.fromNamespaceAndPath("dlssmc", "core/dlss_depth_debug"))
                    .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
                    .withColorTargetState(ColorTargetState.DEFAULT)
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withCull(false)
                    .build();
        }
        return pipeline;
    }

    /** Blit the depth of {@code source} as grayscale into {@code dest}'s color. */
    public static void blitDepth(RenderTarget source, RenderTarget dest) {
        GpuTextureView depthView = source.getDepthTextureView();
        GpuTextureView outColor = dest.getColorTextureView();
        if (depthView == null || outColor == null) {
            return;
        }
        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "DLSS depth debug", outColor, Optional.empty(), null, OptionalDouble.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline()));
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", depthView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
    }
}
