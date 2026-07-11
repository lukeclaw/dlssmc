package com.jhp.client;

import com.jhp.DLSSmc;
import com.jhp.client.dlss.DlssRenderState;
import com.jhp.client.dlss.DlssResolution;
import com.jhp.client.dlss.DlssVelocity;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;

public class DLSSmcClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		// The Vulkan device handle is captured lazily by VulkanDeviceMixin once the
		// renderpearl Vulkan backend creates it (Video Settings -> Graphics ->
		// "Prefer Vulkan (Experimental)").
		DLSSmc.LOGGER.info(
				"[DLSSmc] client init - awaiting Vulkan device capture (deviceReady={})",
				DlssRenderState.isDeviceReady());

		// Debug/settings toggles. A chat command avoids the key-mapping API package
		// churn in this snapshot (see the old F8 TODO); a real Video Settings entry can
		// come later if the prototype ships.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommandManager.literal("dlssmc")
						.then(ClientCommandManager.literal("mv").executes(ctx -> {
							DlssVelocity.showDebug = !DlssVelocity.showDebug;
							ctx.getSource().sendFeedback(Component.literal(
									"[DLSSmc] motion-vector overlay: " + (DlssVelocity.showDebug ? "ON" : "OFF")
									+ (DlssVelocity.showDebug && !DlssResolution.enabled()
											? " (needs render scale < 1.0 to display)" : "")));
							return 1;
						}))
						.then(ClientCommandManager.literal("scale").executes(ctx -> {
							DlssResolution.cycleScale();
							ctx.getSource().sendFeedback(Component.literal(
									"[DLSSmc] render scale: " + DlssResolution.scale));
							return 1;
						}))));
	}
}
