package com.jhp.client;

import com.jhp.DLSSmc;
import com.jhp.client.dlss.DlssRenderState;
import com.jhp.client.dlss.DlssResolution;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class DLSSmcClient implements ClientModInitializer {

	private static KeyMapping cycleScaleKey;

	@Override
	public void onInitializeClient() {
		// The Vulkan device handle is captured lazily by VulkanDeviceMixin once the
		// renderpearl Vulkan backend creates it (Video Settings -> Graphics ->
		// "Prefer Vulkan (Experimental)").
		DLSSmc.LOGGER.info(
				"[DLSSmc] client init - awaiting Vulkan device capture (deviceReady={})",
				DlssRenderState.isDeviceReady());

		// F8 cycles the internal render scale (native / Quality 0.667 / Performance 0.5)
		// so resolution decoupling can be demoed and tuned live.
		cycleScaleKey = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.dlssmc.cycle_scale", GLFW.GLFW_KEY_F8, KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (cycleScaleKey.consumeClick()) {
				DlssResolution.cycleScale();
			}
		});
	}
}
