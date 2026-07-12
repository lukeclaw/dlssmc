package com.jhp.client.mixin;

import com.jhp.client.dlss.DlssEvaluator;
import com.jhp.client.dlss.DlssResolution;
import com.jhp.client.dlss.DlssTargetAccess;
import com.jhp.client.dlss.DlssVelocity;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Velocity + DLSS hooks on {@link LevelRenderer}.
 *
 * <p>A single depth-sampled velocity pass runs at {@code render} RETURN (world is
 * fully drawn, depth buffer is still intact). The DLSS evaluate follows immediately
 * after, using the same depth + freshly written velocity.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Inject(method = "render", at = @At("RETURN"), require = 1)
    private void dlssmc$velocityPasses(CallbackInfo ci) {
        if (!DlssVelocity.enabled) {
            return;
        }
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (target == null) {
            return;
        }
        GpuTextureView depthView = target.getDepthTextureView();
        DlssVelocity.render(target.width, target.height, depthView);

        // P2-3: DLSS evaluate — records upscale of the low-res level target into the
        // native target HERE (depth still intact; velocity targets just written). On
        // success the main target is restored EARLY so hand+HUD render at native res
        // on top; the legacy NEAREST blit in GameRendererMixin is skipped automatically.
        if (Minecraft.getInstance().gameRenderer instanceof DlssTargetAccess access
                && access.dlssmc$savedNativeTarget() != null) {
            if (DlssEvaluator.evaluate(DlssResolution.levelTarget(), access.dlssmc$savedNativeTarget())) {
                access.dlssmc$restoreNativeTarget();
            }
        }
    }
}
