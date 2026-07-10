package com.jhp.client;

import com.jhp.DLSSmc;
import com.jhp.client.dlss.DlssRenderState;
import net.fabricmc.api.ClientModInitializer;

public class DLSSmcClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Phase 1: the Vulkan device handle is captured lazily by VulkanDeviceMixin once
		// the renderpearl Vulkan backend creates it. This only happens when the Vulkan
		// backend is active (Video Settings -> Graphics -> "Prefer Vulkan (Experimental)").
		DLSSmc.LOGGER.info(
				"[DLSSmc] client init - awaiting Vulkan device capture (deviceReady={})",
				DlssRenderState.isDeviceReady());
	}
}
