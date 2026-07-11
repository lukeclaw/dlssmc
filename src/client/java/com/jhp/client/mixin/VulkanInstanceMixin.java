package com.jhp.client.mixin;

import com.jhp.DLSSmc;
import com.jhp.client.dlss.SlBridge;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Phase 2 (P2-2) — route Mojang's {@code vkCreateInstance} through NVIDIA Streamline's
 * interposer proxy so SL adds the instance extensions DLSS needs and registers the
 * instance internally (manual-hooking guide §4.2: the creation proxies are "optional but
 * make integrations much easier").
 *
 * <p>Call site (decompiled 26.3-snapshot-3): {@code VulkanInstance.<init>} →
 * {@code VK12.vkCreateInstance(instanceInfo, null, pInstance)}. The proxy takes the same
 * native structs, so we pass LWJGL's raw addresses straight through. SL returns the
 * <em>native</em> VkInstance handle (Vulkan dispatch stays with vulkan-1.dll except the
 * few hooks in sl_hooks.h), so Mojang's {@code new VkInstance(...)} wrap is untouched.</p>
 *
 * <p>slInit happens lazily here — guaranteed before instance creation regardless of
 * loader init order. If Streamline is unavailable the vanilla call runs unchanged.</p>
 */
@Mixin(VulkanInstance.class)
public abstract class VulkanInstanceMixin {

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VK12;vkCreateInstance(Lorg/lwjgl/vulkan/VkInstanceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"),
            require = 1)
    private int dlssmc$createInstanceViaStreamline(
            VkInstanceCreateInfo createInfo, VkAllocationCallbacks allocator, PointerBuffer pInstance) {
        if (SlBridge.ensureInit()) {
            try {
                int result = SlBridge.vkCreateInstance(createInfo.address(), MemoryUtil.memAddress(pInstance));
                DLSSmc.LOGGER.info("[DLSSmc] vkCreateInstance via Streamline proxy -> VkResult={} instance=0x{}",
                        result, Long.toHexString(pInstance.get(0)));
                return result;
            } catch (Throwable t) {
                DLSSmc.LOGGER.error("[DLSSmc] Streamline vkCreateInstance proxy threw; falling back to vanilla", t);
            }
        }
        return VK12.vkCreateInstance(createInfo, allocator, pInstance);
    }
}
