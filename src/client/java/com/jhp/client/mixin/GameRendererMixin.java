package com.jhp.client.mixin;

import com.jhp.client.dlss.DlssJitter;
import com.jhp.client.dlss.DlssDebug;
import com.jhp.client.dlss.DlssMotion;
import com.jhp.client.dlss.DlssResolution;
import com.jhp.client.dlss.DlssTerrainVelocity;
import com.jhp.client.dlss.DlssVelocity;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phase 1 rendering hooks on {@link GameRenderer}:
 * <ul>
 *   <li><b>Jitter (P1-4):</b> {@code @ModifyArg} on the world projection upload
 *       ({@code getBuffer(Matrix4f)}). The HUD path ({@code getBuffer(Projection)}) is
 *       left unjittered.</li>
 *   <li><b>Resolution decoupling (P1-5):</b> swap {@code mainRenderTarget} to a low-res
 *       offscreen target for the duration of {@code renderLevel}, then blit-upscale back
 *       into the native target before the HUD renders. Because {@code LevelRenderer}
 *       resolves its target via {@code gameRenderer.mainRenderTarget()}, this redirects
 *       the entire world render graph to the internal resolution.</li>
 *   <li><b>Motion vectors (P1-7):</b> slice-1 fullscreen camera velocity + slice-2
 *       terrain velocity prepass at {@code renderLevel} RETURN, plus the /dlssmc mv
 *       debug overlay composited onto the native target.</li>
 * </ul>
 * The jitter's NDC scale reads {@code mainRenderTarget.width/height}, which is the
 * swapped (internal) target during {@code renderLevel}, so jitter auto-scales to the
 * internal render resolution.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Mutable
    @Shadow @Final
    private RenderTarget mainRenderTarget;

    @Unique
    private RenderTarget dlssmc$savedTarget;

    @Shadow @Final
    private GameRenderState gameRenderState;

    @Inject(method = "renderLevel", at = @At("HEAD"), require = 1)
    private void dlssmc$onRenderLevelHead(CallbackInfo ci) {
        DlssJitter.beginLevelFrame();
        DlssMotion.capture(this.gameRenderState.levelRenderState.cameraRenderState);
        if (DlssResolution.enabled()) {
            RenderTarget real = this.mainRenderTarget;
            TextureTarget level = DlssResolution.ensureLevelTarget(real.width, real.height);
            this.dlssmc$savedTarget = real;
            this.mainRenderTarget = level; // world render graph now targets the low-res buffer
        }
    }

    @Inject(method = "renderLevel", at = @At("RETURN"), require = 1)
    private void dlssmc$onRenderLevelReturn(CallbackInfo ci) {
        DlssJitter.endLevelFrame();
        // P1-7: fill the velocity target at the internal (DLSS input) resolution.
        // Slice 1 (fullscreen camera-only) first as fallback for sky/entities/
        // translucents, then slice 2 overwrites terrain pixels with exact geometry
        // velocity (depth-tested against the level target's depth buffer).
        // During decoupling mainRenderTarget is still the low-res level target here.
        if (DlssVelocity.enabled && this.mainRenderTarget != null) {
            DlssVelocity.render(this.mainRenderTarget.width, this.mainRenderTarget.height);
            DlssTerrainVelocity.renderPrepass(DlssVelocity.velocityTarget(), this.mainRenderTarget);
        }
        if (this.dlssmc$savedTarget != null) {
            RenderTarget real = this.dlssmc$savedTarget;
            TextureTarget level = DlssResolution.levelTarget();
            // Upscale the low-res world into the native target (NEAREST; DLSS replaces this in Phase 2).
            level.blitAndBlendToTexture(real.getColorTextureView(), real.getDepthTextureView());
            if (DlssDebug.showDepth) {
                DlssDebug.blitDepth(level, real); // grayscale depth over the color image (debug)
            }
            this.mainRenderTarget = real;
            this.dlssmc$savedTarget = null;
        }
        // Debug overlay (/dlssmc mv): alpha-blend the velocity tint over the finished
        // NATIVE image (after restore). No scene sampling -> no ghosting; also works
        // without resolution decoupling.
        if (DlssVelocity.enabled && DlssVelocity.showDebug) {
            DlssVelocity.blitDebug(this.mainRenderTarget);
        }
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
