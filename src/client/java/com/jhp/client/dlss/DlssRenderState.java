package com.jhp.client.dlss;

import com.jhp.DLSSmc;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueue;

/**
 * Central holder for the raw Vulkan handles that DLSS / NVIDIA Streamline need.
 *
 * <p>Populated by mixins into Mojang's {@code renderpearl} Vulkan backend (see
 * {@code com.jhp.client.mixin.VulkanDeviceMixin}). Phase 2 will read these to hook
 * Minecraft's own Vulkan device via Streamline "manual hooking" (the app owns
 * {@code vkCreateDevice}, so Streamline does not need to create the device — see
 * {@code docs/SPIKE_FINDINGS.md}, Risk 2).</p>
 *
 * <p>All access is via LWJGL handle objects; the raw native pointers Streamline wants
 * are obtained with {@link VkDevice#address()} / {@link VkQueue#address()}.</p>
 */
public final class DlssRenderState {
    private DlssRenderState() {}

    private static volatile VkDevice device;
    private static volatile VkQueue graphicsQueue;
    private static volatile int graphicsQueueFamily = -1;

    /**
     * Record the Vulkan device (and, once wired, the graphics queue) captured from the
     * renderpearl backend. Safe to call with a null queue during early bring-up.
     */
    public static void captureDevice(VkDevice device, VkQueue graphicsQueue, int graphicsQueueFamily) {
        DlssRenderState.device = device;
        DlssRenderState.graphicsQueue = graphicsQueue;
        DlssRenderState.graphicsQueueFamily = graphicsQueueFamily;

        long deviceAddr = device != null ? device.address() : 0L;
        long queueAddr = graphicsQueue != null ? graphicsQueue.address() : 0L;
        DLSSmc.LOGGER.info(
                "[DLSSmc] Vulkan device captured: device=0x{} graphicsQueue=0x{} queueFamily={}",
                Long.toHexString(deviceAddr), Long.toHexString(queueAddr), graphicsQueueFamily);
    }

    public static VkDevice device() {
        return device;
    }

    public static VkQueue graphicsQueue() {
        return graphicsQueue;
    }

    public static int graphicsQueueFamily() {
        return graphicsQueueFamily;
    }

    /** True once the Vulkan device handle has been captured (success criterion S1). */
    public static boolean isDeviceReady() {
        return device != null;
    }
}
