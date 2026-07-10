package com.jhp.client.mixin;

import com.jhp.client.dlss.DlssRenderState;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phase 1 — capture the raw {@link VkDevice} that Mojang's {@code renderpearl} Vulkan
 * backend creates, so DLSS / NVIDIA Streamline (Phase 2) can hook Minecraft's own
 * device via manual hooking.
 *
 * <p>Target identified by constant-pool inspection of {@code 26.3-snapshot-3}
 * (see {@code docs/SPIKE_FINDINGS.md}):</p>
 * <pre>
 *   com.mojang.renderpearl.backend.vulkan.VulkanDevice
 *     field: org.lwjgl.vulkan.VkDevice vkDevice
 *     ctor : (VulkanInstance, VulkanPhysicalDevice, FeatureSet, VkDevice, long, CheckpointExtension)
 * </pre>
 *
 * <p><b>TODO(genSources):</b> confirm the exact {@code <init>} descriptor and the
 * {@code vkDevice} field name against Loom {@code genSources} in the IDE. If
 * {@code VulkanDevice} declares more than one constructor, pin the descriptor in the
 * {@code method} selector below. Then extend this to also publish the graphics
 * {@code VkQueue} + queue family index (tracker task P1-3) — currently passed as
 * {@code null}/{@code -1}.</p>
 *
 * <p>{@code require = 0} keeps this a non-fatal scaffold: if the target can't be
 * resolved on an unverified snapshot the game still boots (it just won't capture),
 * rather than hard-crashing on load. Tighten to {@code require = 1} once verified.</p>
 */
@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceMixin {

    @Shadow
    @Final
    private VkDevice vkDevice;

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void dlssmc$onDeviceCreated(CallbackInfo ci) {
        // At TAIL the field is assigned; publish it for Phase 2 Streamline wiring.
        DlssRenderState.captureDevice(this.vkDevice, null, -1);
    }
}
