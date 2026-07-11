package com.jhp.client;

import com.jhp.DLSSmc;
import com.jhp.client.dlss.DlssRenderState;

import net.fabricmc.api.ClientModInitializer;

public class DLSSmcClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		// The Vulkan device handle is captured lazily by VulkanDeviceMixin once the
		// renderpearl Vulkan backend creates it (Video Settings -> Graphics ->
		// "Prefer Vulkan (Experimental)").
		DLSSmc.LOGGER.info(
				"[DLSSmc] client init - awaiting Vulkan device capture (deviceReady={})",
				DlssRenderState.isDeviceReady());

		// Render scale is controlled by DlssResolution.scale (default 0.5).
		// TODO(keybind): the Fabric key-mapping API package changed in this version
		// (fabric-key-mapping-api-v1). To wire F8 -> DlssResolution.cycleScale(), find the
		// current KeyBindingHelper package in the resolved fabric-api and register a
		// KeyMapping("key.dlssmc.cycle_scale", GLFW_KEY_F8, KeyMapping.Category.MISC),
		// polling it in a ClientTickEvents.END_CLIENT_TICK handler.
	}
}
