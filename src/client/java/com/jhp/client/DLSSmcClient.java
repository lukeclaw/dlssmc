package com.jhp.client;

import com.jhp.DLSSmc;
import com.jhp.client.dlss.DlssRenderState;
import com.jhp.client.dlss.DlssResolution;
import com.jhp.client.dlss.DlssEvaluator;
import com.jhp.client.dlss.DlssVelocity;
import com.jhp.client.dlss.SlBridge;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
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

		// Debug/settings toggles. This Fabric API build has no ClientCommandManager
		// sugar class, so the brigadier literals are built directly. A real Video
		// Settings entry can come later if the prototype ships.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("dlssmc")
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("mv").executes(ctx -> {
							DlssVelocity.showDebug = !DlssVelocity.showDebug;
							ctx.getSource().sendFeedback(Component.literal(
									"[DLSSmc] motion-vector overlay: " + (DlssVelocity.showDebug ? "ON" : "OFF")
									+ (DlssVelocity.showDebug && !DlssResolution.enabled()
											? " (needs render scale < 1.0 to display)" : "")));
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("dlss").executes(ctx -> {
							DlssEvaluator.enabled = !DlssEvaluator.enabled;
							if (DlssEvaluator.enabled) {
								DlssEvaluator.reset(); // clear a previous 'broken' latch and retry
							}
							ctx.getSource().sendFeedback(Component.literal(
									"[DLSSmc] DLSS evaluate: " + DlssEvaluator.status()));
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("sl").executes(ctx -> {
							ctx.getSource().sendFeedback(Component.literal(
									"[DLSSmc] Streamline: " + SlBridge.status()));
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("fg").executes(ctx -> {
							int r = SlBridge.setFrameGeneration(!SlBridge.isFrameGenEnabled());
							ctx.getSource().sendFeedback(Component.literal(
									"[DLSSmc] Frame Gen: " + (SlBridge.isFrameGenEnabled() ? "ON" : "OFF")
									+ " (slDLSSGSetOptions=" + r + "); " + SlBridge.frameGenState()));
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("scale").executes(ctx -> {
							DlssResolution.cycleScale();
							ctx.getSource().sendFeedback(Component.literal(
									"[DLSSmc] render scale: " + DlssResolution.scale));
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("mode").executes(ctx -> {
							// Cycle auto(-1) -> MaxPerformance -> Balanced -> MaxQuality -> DLAA -> auto.
							DlssEvaluator.modeOverride = switch (DlssEvaluator.modeOverride) {
								case -1 -> 1; case 1 -> 2; case 2 -> 3; case 3 -> 6; default -> -1;
							};
							ctx.getSource().sendFeedback(Component.literal(
									"[DLSSmc] DLSS mode override: " + DlssEvaluator.modeName(DlssEvaluator.modeOverride)));
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("preset").executes(ctx -> {
							// MV-Q: cycle DLSS preset default -> K -> L -> M to A/B fast-motion stability live.
							DlssEvaluator.presetOverride = switch (DlssEvaluator.presetOverride) {
								case 0 -> 11; case 11 -> 12; case 12 -> 13; default -> 0;
							};
							ctx.getSource().sendFeedback(Component.literal(
								"[DLSSmc] DLSS preset override: " + DlssEvaluator.presetName(DlssEvaluator.presetOverride)
								+ " (K/L transformer = most motion-stable; applies next frame)"));
							return 1;
						}))
						// P2-5 live tuning knobs: flip a sign, watch the artifacts.
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("mvx").executes(ctx -> {
							DlssEvaluator.mvSignX = -DlssEvaluator.mvSignX;
							ctx.getSource().sendFeedback(Component.literal("[DLSSmc] " + DlssEvaluator.knobs()));
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("mvy").executes(ctx -> {
							DlssEvaluator.mvSignY = -DlssEvaluator.mvSignY;
							ctx.getSource().sendFeedback(Component.literal("[DLSSmc] " + DlssEvaluator.knobs()));
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("jx").executes(ctx -> {
							DlssEvaluator.jitterSignX = -DlssEvaluator.jitterSignX;
							ctx.getSource().sendFeedback(Component.literal("[DLSSmc] " + DlssEvaluator.knobs()));
							return 1;
						}))
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("jy").executes(ctx -> {
							DlssEvaluator.jitterSignY = -DlssEvaluator.jitterSignY;
							ctx.getSource().sendFeedback(Component.literal("[DLSSmc] " + DlssEvaluator.knobs()));
							return 1;
						}))));
	}
}
