package com.jhp.client.mixin;

import com.jhp.client.dlss.DlssRenderState;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phase 1 — capture the raw {@link VkDevice} and graphics {@link VkQueue} that Mojang's
 * {@code renderpearl} Vulkan backend creates, so DLSS / NVIDIA Streamline (Phase 2) can
 * hook Minecraft's own device via manual hooking.
 *
 * <p>Signatures confirmed against {@code javap} of {@code 26.3-snapshot-3}
 * (see {@code docs/javap_dump.txt}):</p>
 * <pre>
 *   com.mojang.renderpearl.backend.vulkan.VulkanDevice
 *     private final VkDevice vkDevice;
 *     private final VulkanQueue graphicsQueue;      // record(VkQueue vkQueue, int queueFamilyIndex)
 *     public VulkanDevice(VulkanInstance, VulkanPhysicalDevice, FeatureSet, VkDevice, long, CheckpointExtension)  // sole ctor
 * </pre>
 *
 * <p>{@code require = 0} until the runtime log (Gate C) confirms the injection fires;
 * then this can be tightened to {@code require = 1}.</p>
 */
@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceMixin {

    @Shadow @Final private VkDevice vkDevice;
    @Shadow @Final private VulkanQueue graphicsQueue;

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void dlssmc$onDeviceCreated(CallbackInfo ci) {
        VkQueue gq = this.graphicsQueue != null ? this.graphicsQueue.vkQueue() : null;
        int family = this.graphicsQueue != null ? this.graphicsQueue.queueFamilyIndex() : -1;
        DlssRenderState.captureDevice(this.vkDevice, gq, family);
    }
}
