package com.jhp.client.mixin;

import com.jhp.client.dlss.DlssJitter;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DLSS projection jitter, applied to the MAIN world projection. (Tracker task P1-4.)
 *
 * <p>The world/terrain projection is uploaded in {@code renderLevel} via
 * {@code ProjectionMatrixBuffer.getBuffer(Matrix4f)} (line ~626 of GameRenderer). The
 * HUD/item-in-hand projection uses the {@code getBuffer(Projection)} overload and is
 * intentionally left unjittered. We therefore jitter the {@code Matrix4f} argument of
 * the world-projection upload with {@link ModifyArg} — this hits exactly the scene
 * projection DLSS cares about.</p>
 *
 * <p>The HEAD/RETURN hooks advance the Halton phase once per level frame and mark the
 * jitter window (kept for Phase 2 bookkeeping).</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Shadow @Final private RenderTarget mainRenderTarget;

    @Inject(method = "renderLevel", at = @At("HEAD"), require = 1)
    private void dlssmc$beginJitter(CallbackInfo ci) {
        DlssJitter.beginLevelFrame();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"), require = 1)
    private void dlssmc$endJitter(CallbackInfo ci) {
        DlssJitter.endLevelFrame();
    }

    @ModifyArg(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"),
        index = 0,
        require = 1)
    private Matrix4f dlssmc$jitterWorldProjection(Matrix4f projection) {
        int w = this.mainRenderTarget != null ? this.mainRenderTarget.width : 0;
        int h = this.mainRenderTarget != null ? this.mainRenderTarget.height : 0;
        return DlssJitter.applyTo(projection, w, h);
    }
}
