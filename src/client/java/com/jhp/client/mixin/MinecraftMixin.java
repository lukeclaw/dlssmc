package com.jhp.client.mixin;

import com.jhp.client.dlss.SlBridge;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phase 3 (FG-4) — Reflex/PCL per-frame markers around Minecraft's frame loop.
 *
 * <p>Frame Generation refuses to run without a coherent set of PCL latency markers whose
 * frame index matches the Constants token EXACTLY (guide: "common constants cannot be found
 * for frame N"). {@link SlBridge#frameBegin()} mints ONE shared token at the top of each
 * frame; every marker + the DLSS constants/evaluate reuse it.</p>
 *
 * <p>Placement (FG-1 recon): {@code runTick} is the per-frame driver, {@code renderFrame}
 * does the scene render + present. eSimulationStart + Reflex sleep at runTick HEAD;
 * eSimulationEnd + eRenderSubmitStart at renderFrame HEAD; the remaining three
 * (eRenderSubmitEnd, ePresentStart/End) bracket the real present in
 * {@code VulkanGpuSurfaceMixin}. All are no-ops until SL is active.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "runTick(Z)V", at = @At("HEAD"))
    private void dlssmc$frameBegin(boolean advanceGameTime, CallbackInfo ci) {
        SlBridge.frameBegin();
    }

    @Inject(method = "renderFrame(Z)V", at = @At("HEAD"))
    private void dlssmc$simEndRenderBegin(boolean advanceGameTime, CallbackInfo ci) {
        SlBridge.mark(SlBridge.PCL_SIMULATION_END);
        SlBridge.mark(SlBridge.PCL_RENDER_SUBMIT_START);
    }
}
