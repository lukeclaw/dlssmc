package com.jhp.client.mixin;

import com.jhp.client.dlss.DlssJitter;
import net.minecraft.client.renderer.Projection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the current DLSS sub-pixel jitter to the matrix returned by
 * {@code Projection.getMatrix(Matrix4f)} — but only while the level-render jitter
 * window is open (see {@code GameRendererMixin}), so the HUD/UI projection stays
 * unjittered. (Tracker task P1-4.)
 *
 * <p>The returned matrix is the caller's destination copy (not the Projection's cached
 * matrix), so jittering it in place is safe and covers callers that read either the
 * return value or their own dest reference. Uses the Projection's own
 * {@code width()}/{@code height()} for the NDC scale — confirmed public accessors.</p>
 */
@Mixin(Projection.class)
public abstract class ProjectionMixin {

    @Shadow public abstract float width();

    @Shadow public abstract float height();

    @Inject(method = "getMatrix", at = @At("RETURN"), require = 0)
    private void dlssmc$jitterProjection(CallbackInfoReturnable<Matrix4f> cir) {
        if (!DlssJitter.isActive()) {
            return;
        }
        Matrix4f matrix = cir.getReturnValue();
        if (matrix != null) {
            DlssJitter.applyTo(matrix, this.width(), this.height());
        }
    }
}
