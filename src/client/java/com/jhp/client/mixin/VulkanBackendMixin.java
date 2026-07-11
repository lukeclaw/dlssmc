package com.jhp.client.mixin;

import com.jhp.DLSSmc;
import com.jhp.client.dlss.SlBridge;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.nio.IntBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Phase 2 (P2-1 + P2-2, collapsed) — route Mojang's {@code vkCreateDevice} through
 * Streamline's interposer proxy. SL rewrites the {@code VkDeviceCreateInfo} to add the
 * device extensions, features and extra command queues DLSS requires (this is why P2-1,
 * "inject DLSS-required extensions/features", collapses into this redirect), creates the
 * device, and registers it with SL — no {@code slSetVulkanInfo} needed (guide §5.0).
 *
 * <p>Call site (decompiled 26.3-snapshot-3): private static
 * {@code VulkanBackend.createDevice(FeatureSet, VulkanPhysicalDevice)} →
 * {@code VK12.vkCreateDevice(physicalDevice.vkPhysicalDevice(), deviceCreateInfo, null, pointer)}.
 * The returned handle is the native VkDevice, so Mojang's {@code new VkDevice(...)} wrap
 * (which parses capabilities from the app-side createInfo) is unaffected; SL-added
 * extensions are used only by SL's own dispatch.</p>
 */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {

    /**
     * CRITICAL for the proxied chain (root cause of the 2026-07-11 pc=0 crash): SL's
     * vkEnumeratePhysicalDevices hook populates its instanceDeviceMap
     * (wrapper.cpp:982). SL's vkCreateDevice reads that map after creating the device
     * (wrapper.cpp:940) to re-derive the owning instance; if the enumeration bypassed
     * SL the map is empty, SL rebuilds its dispatch from a null instance, and plugin
     * init calls a null function pointer. Both call sites (count query + fetch) in
     * findPhysicalDevice are redirected by this one handler.
     */
    @Redirect(
            method = "findPhysicalDevice(Lcom/mojang/renderpearl/backend/vulkan/VulkanInstance;Ljava/util/Set;Ljava/util/Set;)Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VK12;vkEnumeratePhysicalDevices(Lorg/lwjgl/vulkan/VkInstance;Ljava/nio/IntBuffer;Lorg/lwjgl/PointerBuffer;)I"),
            require = 1)
    private static int dlssmc$enumeratePhysicalDevicesViaStreamline(
            VkInstance instance, IntBuffer pCount, PointerBuffer pPhysicalDevices) {
        if (SlBridge.isInstanceProxied()) {
            try {
                return SlBridge.vkEnumeratePhysicalDevices(
                        instance.address(), MemoryUtil.memAddress(pCount),
                        pPhysicalDevices == null ? 0L : MemoryUtil.memAddress(pPhysicalDevices));
            } catch (Throwable t) {
                DLSSmc.LOGGER.error("[DLSSmc] Streamline vkEnumeratePhysicalDevices threw; falling back to vanilla", t);
                SlBridge.markChainBroken("vkEnumeratePhysicalDevices threw");
            }
        }
        return VK12.vkEnumeratePhysicalDevices(instance, pCount, pPhysicalDevices);
    }

    @Redirect(
            method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"),
            require = 1)
    private static int dlssmc$createDeviceViaStreamline(
            VkPhysicalDevice physicalDevice, VkDeviceCreateInfo createInfo,
            VkAllocationCallbacks allocator, PointerBuffer pDevice) {
        if (SlBridge.isInstanceProxied()) {
            try {
                int result = SlBridge.vkCreateDevice(
                        physicalDevice.address(), createInfo.address(), MemoryUtil.memAddress(pDevice));
                DLSSmc.LOGGER.info("[DLSSmc] vkCreateDevice via Streamline proxy -> VkResult={} device=0x{}",
                        result, Long.toHexString(pDevice.get(0)));
                if (result == 0) { // VK_SUCCESS — now ask SL if DLSS runs on this adapter (V-5 evidence)
                    SlBridge.logDlssSupport(physicalDevice.address());
                }
                return result;
            } catch (Throwable t) {
                DLSSmc.LOGGER.error("[DLSSmc] Streamline vkCreateDevice proxy threw; falling back to vanilla", t);
                SlBridge.markChainBroken("vkCreateDevice proxy threw");
            }
        }
        return VK12.vkCreateDevice(physicalDevice, createInfo, allocator, pDevice);
    }
}
