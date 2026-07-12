package com.jhp.client.mixin;

import com.jhp.DLSSmc;
import com.jhp.client.dlss.SlBridge;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Phase 3 (FG-3) — route Mojang's swapchain entry points through Streamline's interposer.
 *
 * <p>Frame Generation injects its generated frames at present time via a replacement
 * swapchain, so SL must own the swapchain lifecycle. All five KHRSwapchain calls that
 * renderpearl makes live in one class ({@link VulkanGpuSurface}, FG-1 recon): create +
 * get-images ({@code configure}), destroy ({@code destroySwapchain}), acquire
 * ({@code acquireNextTexture}) and present ({@code present}).</p>
 *
 * <p><b>All-or-nothing</b> (P2-2 crash lesson): every redirect is gated on
 * {@link SlBridge#isInstanceProxied()} so the swapchain routing tracks the same proxied
 * chain as instance/device creation. If any call throws we
 * {@link SlBridge#markChainBroken} and fall back to vanilla for the rest of the session —
 * a partially-routed swapchain leaves SL's internal maps inconsistent.</p>
 *
 * <p>FG-3 lands this with FG still OFF: with DLSS-G not activated SL forwards each call to
 * the real driver, so behaviour must be identical to vanilla (resize/F11 included) and
 * DLSS-SR must stay live. Enabling FG is FG-5.</p>
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {

    @Redirect(
            method = "configure(Lcom/mojang/renderpearl/api/device/GpuSurface$Configuration;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"),
            require = 1)
    private int dlssmc$createSwapchainViaStreamline(
            VkDevice device, VkSwapchainCreateInfoKHR pCreateInfo,
            VkAllocationCallbacks pAllocator, LongBuffer pSwapchain) {
        if (SlBridge.isInstanceProxied()) {
            try {
                return SlBridge.vkCreateSwapchainKHR(device.address(), pCreateInfo.address(),
                        pAllocator == null ? 0L : pAllocator.address(), MemoryUtil.memAddress(pSwapchain));
            } catch (Throwable t) {
                DLSSmc.LOGGER.error("[DLSSmc] Streamline vkCreateSwapchainKHR threw; falling back to vanilla", t);
                SlBridge.markChainBroken("vkCreateSwapchainKHR threw");
            }
        }
        return KHRSwapchain.vkCreateSwapchainKHR(device, pCreateInfo, pAllocator, pSwapchain);
    }

    /** Redirects BOTH get-images calls in configure() (count query with null pImages, then fetch). */
    @Redirect(
            method = "configure(Lcom/mojang/renderpearl/api/device/GpuSurface$Configuration;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkGetSwapchainImagesKHR(Lorg/lwjgl/vulkan/VkDevice;JLjava/nio/IntBuffer;Ljava/nio/LongBuffer;)I"),
            require = 2)
    private int dlssmc$getSwapchainImagesViaStreamline(
            VkDevice device, long swapchain, IntBuffer pCount, LongBuffer pImages) {
        if (SlBridge.isInstanceProxied()) {
            try {
                return SlBridge.vkGetSwapchainImagesKHR(device.address(), swapchain,
                        MemoryUtil.memAddress(pCount), pImages == null ? 0L : MemoryUtil.memAddress(pImages));
            } catch (Throwable t) {
                DLSSmc.LOGGER.error("[DLSSmc] Streamline vkGetSwapchainImagesKHR threw; falling back to vanilla", t);
                SlBridge.markChainBroken("vkGetSwapchainImagesKHR threw");
            }
        }
        return KHRSwapchain.vkGetSwapchainImagesKHR(device, swapchain, pCount, pImages);
    }

    @Redirect(
            method = "acquireNextTexture()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkAcquireNextImageKHR(Lorg/lwjgl/vulkan/VkDevice;JJJJLjava/nio/IntBuffer;)I"),
            require = 1)
    private int dlssmc$acquireNextImageViaStreamline(
            VkDevice device, long swapchain, long timeout, long semaphore, long fence, IntBuffer pImageIndex) {
        if (SlBridge.isInstanceProxied()) {
            try {
                return SlBridge.vkAcquireNextImageKHR(device.address(), swapchain, timeout, semaphore, fence,
                        MemoryUtil.memAddress(pImageIndex));
            } catch (Throwable t) {
                DLSSmc.LOGGER.error("[DLSSmc] Streamline vkAcquireNextImageKHR threw; falling back to vanilla", t);
                SlBridge.markChainBroken("vkAcquireNextImageKHR threw");
            }
        }
        return KHRSwapchain.vkAcquireNextImageKHR(device, swapchain, timeout, semaphore, fence, pImageIndex);
    }

    @Redirect(
            method = "present()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"),
            require = 1)
    private int dlssmc$queuePresentViaStreamline(VkQueue queue, VkPresentInfoKHR pPresentInfo) {
        // FG-4: bracket the real present submission with PCL markers (shared frame token).
        SlBridge.mark(SlBridge.PCL_RENDER_SUBMIT_END);
        SlBridge.mark(SlBridge.PCL_PRESENT_START);
        try {
            if (SlBridge.isInstanceProxied()) {
                try {
                    return SlBridge.vkQueuePresentKHR(queue.address(), pPresentInfo.address());
                } catch (Throwable t) {
                    DLSSmc.LOGGER.error("[DLSSmc] Streamline vkQueuePresentKHR threw; falling back to vanilla", t);
                    SlBridge.markChainBroken("vkQueuePresentKHR threw");
                }
            }
            return KHRSwapchain.vkQueuePresentKHR(queue, pPresentInfo);
        } finally {
            SlBridge.mark(SlBridge.PCL_PRESENT_END);
        }
    }

    @Redirect(
            method = "destroySwapchain()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkDestroySwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"),
            require = 1)
    private void dlssmc$destroySwapchainViaStreamline(
            VkDevice device, long swapchain, VkAllocationCallbacks pAllocator) {
        if (SlBridge.isInstanceProxied()) {
            try {
                SlBridge.vkDestroySwapchainKHR(device.address(), swapchain,
                        pAllocator == null ? 0L : pAllocator.address());
                return;
            } catch (Throwable t) {
                DLSSmc.LOGGER.error("[DLSSmc] Streamline vkDestroySwapchainKHR threw; falling back to vanilla", t);
                SlBridge.markChainBroken("vkDestroySwapchainKHR threw");
            }
        }
        KHRSwapchain.vkDestroySwapchainKHR(device, swapchain, pAllocator);
    }
}
