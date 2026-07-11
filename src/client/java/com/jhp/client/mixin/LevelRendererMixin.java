package com.jhp.client.mixin;

import com.jhp.client.dlss.DlssTerrainVelocity;
import com.jhp.client.dlss.DlssVelocity;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Motion-vector hooks on {@link LevelRenderer} (P1-7 slice 2):
 * <ul>
 *   <li>Capture the per-frame {@link ChunkSectionsToRender} (Mojang's fully prepared
 *       chunk draw data) + the main pass's uniform state at the top of the solid pass,
 *       so the terrain velocity prepass can replay the exact same draws.</li>
 *   <li>Run BOTH velocity passes (fullscreen camera fallback, then the terrain
 *       prepass) at {@code render} RETURN — the world (incl. translucents) is fully
 *       drawn, and CRITICALLY the depth buffer is still intact: GameRenderer clears
 *       it to far (reverse-Z 0.0) right after {@code render} returns, before
 *       {@code renderItemInHand}. Running at renderLevel RETURN (the old hook point)
 *       depth-tested against that CLEARED buffer, so every prepass fragment passed and
 *       draw order let far terrain overwrite near terrain — the Gate-D "background
 *       leaks through blocks" artifact.</li>
 * </ul>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Inject(method = "executeSolid", at = @At("HEAD"), require = 1)
    private void dlssmc$captureChunkDraws(ChunkSectionsToRender draws,
                                          FeatureRenderDispatcher.PreparedFrame preparedFrame,
                                          RenderPass pass,
                                          CallbackInfo ci) {
        DlssTerrainVelocity.captureFrame(draws);
    }

    @Inject(method = "render", at = @At("RETURN"), require = 1)
    private void dlssmc$velocityPasses(CallbackInfo ci) {
        if (!DlssVelocity.enabled) {
            return;
        }
        // During resolution decoupling this is the swapped low-res level target —
        // exactly the surface whose depth the prepass needs.
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (target == null) {
            return;
        }
        DlssVelocity.render(target.width, target.height);
        DlssTerrainVelocity.renderPrepass(DlssVelocity.velocityTarget(), target);
    }
}
