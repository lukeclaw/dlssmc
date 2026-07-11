package com.jhp.client.mixin;

import com.jhp.client.dlss.DlssTerrainVelocity;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the per-frame {@link ChunkSectionsToRender} (Mojang's fully prepared chunk
 * draw data) at the top of the main solid pass, so the DLSS terrain velocity prepass
 * can replay the exact same draws into the velocity target afterwards (P1-7 slice 2).
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
}
