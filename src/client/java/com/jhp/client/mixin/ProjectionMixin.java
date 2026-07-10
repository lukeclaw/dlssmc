package com.jhp.client.mixin;

import com.jhp.client.dlss.DlssJitter;
import net.minecraft.client.renderer.Projection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the current DLSS sub-pixel jitter to the matrix returned by
 * {@code Projection.getMatrix()}, but only while the level-render jitter window is open
 * (see {@code GameRendererMixin}), so the HUD/UI projection stays unjittered.
 * (Tracker task P1-4.)
 *
 * <p>A jittered <em>copy</em> is returned so the Projection's cached matrix is not
 * mutated.</p>
 *
 * <p><b>TODO(genSources):</b> confirm {@code getMatrix()}'s exact name/descriptor. The
 * first-person/held-item pass also runs inside {@code renderLevel}; if it should not be
 * jittered, add a discriminator here later.</p>
 */
@Mixin(Projection.class)
public abstract class ProjectionMixin {

    @Inject(method = "getMatrix", at = @At("RETURN"), require = 0, cancellable = true)
    private void dlssmc$jitterProjection(CallbackInfoReturnable<Matrix4f> cir) {
        if (!DlssJitter.isActive()) {
            return;
        }
        Matrix4f current = cir.getReturnValue();
        if (current == null) {
            return;
        }
        cir.setReturnValue(DlssJitter.applyTo(new Matrix4f(current)));
    }
}
