package com.jhp.client.mixin;

import com.jhp.client.dlss.DlssJitter;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scopes DLSS projection jitter to the level (world) render so the HUD/UI is left
 * unjittered. Opens the jitter "active" window at the start of {@code renderLevel} and
 * closes it at the end; {@code ProjectionMixin} applies the offset to the projection
 * matrix while the window is open. (Tracker task P1-4.)
 *
 * <p><b>TODO(genSources):</b> confirm the exact {@code renderLevel} descriptor. Also
 * feed the real internal 3D render resolution via
 * {@link DlssJitter#setRenderResolution(int, int)} once resolution decoupling (P1-5)
 * exists — a placeholder resolution is used until then.</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "renderLevel", at = @At("HEAD"), require = 1) // S2 confirmed
    private void dlssmc$beginJitter(CallbackInfo ci) {
        DlssJitter.beginLevelFrame();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"), require = 1) // S2 confirmed
    private void dlssmc$endJitter(CallbackInfo ci) {
        DlssJitter.endLevelFrame();
    }
}
