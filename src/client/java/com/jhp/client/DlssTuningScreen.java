package com.jhp.client;

import com.jhp.client.dlss.DlssEvaluator;
import com.jhp.client.dlss.DlssMipBias;
import com.jhp.client.dlss.DlssResolution;
import com.jhp.client.dlss.DlssVelocity;
import com.jhp.client.dlss.SlBridge;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * MV-Q tuning panel (keybind K, see DLSSmcClient). Replaces the scattered {@code /dlssmc}
 * commands with a single in-game screen so DLSS/FG/MV knobs can be A/B'd quickly.
 *
 * <p>Every control is a plain {@link Button} whose label shows the current value; clicking
 * advances the value and refreshes the label. This deliberately avoids overriding
 * {@code render()} (whose GuiGraphics parameter type churns across snapshots) — the stable
 * widget + layout API is all that's needed. Non-pausing ({@link #isPauseScreen()} = false)
 * so the world keeps animating while you tune; the /dlssmc commands still work unchanged.</p>
 */
public class DlssTuningScreen extends Screen {

    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

    public DlssTuningScreen() {
        super(Component.literal("DLSSmc Tuning"));
    }

    @Override
    protected void init() {
        this.layout.addTitleHeader(Component.literal("DLSSmc Tuning"), this.font);
        LinearLayout col = LinearLayout.vertical().spacing(4);

        col.addChild(cycle(() -> "DLSS: " + (DlssEvaluator.enabled ? "ON" : "OFF"), () -> {
            DlssEvaluator.enabled = !DlssEvaluator.enabled;
            if (DlssEvaluator.enabled) {
                DlssEvaluator.reset();
            }
        }));
        col.addChild(cycle(() -> "Mode: " + DlssEvaluator.modeName(DlssEvaluator.modeOverride), () ->
                DlssEvaluator.modeOverride = switch (DlssEvaluator.modeOverride) {
                    case -1 -> 1;
                    case 1 -> 2;
                    case 2 -> 3;
                    case 3 -> 6;
                    default -> -1;
                }));
        col.addChild(cycle(() -> "Preset: " + DlssEvaluator.presetName(DlssEvaluator.presetOverride), () ->
                DlssEvaluator.presetOverride = switch (DlssEvaluator.presetOverride) {
                    case 0 -> 11;
                    case 11 -> 12;
                    case 12 -> 13;
                    default -> 0;
                }));
        col.addChild(cycle(() -> "Render Scale: " + DlssResolution.scale, DlssResolution::cycleScale));
        col.addChild(cycle(DlssMipBias::status, DlssMipBias::cycleExtra));
        col.addChild(cycle(() -> "Frame Gen: " + (SlBridge.isFrameGenEnabled() ? "ON" : "OFF"), () ->
                SlBridge.setFrameGeneration(!SlBridge.isFrameGenEnabled())));
        col.addChild(cycle(() -> "MV Overlay: " + (DlssVelocity.showDebug ? "ON" : "OFF"), () ->
                DlssVelocity.showDebug = !DlssVelocity.showDebug));
        col.addChild(cycle(() -> "MV Sign X: " + sign(DlssEvaluator.mvSignX), () ->
                DlssEvaluator.mvSignX = -DlssEvaluator.mvSignX));
        col.addChild(cycle(() -> "MV Sign Y: " + sign(DlssEvaluator.mvSignY), () ->
                DlssEvaluator.mvSignY = -DlssEvaluator.mvSignY));
        col.addChild(cycle(() -> "Jitter Sign X: " + sign(DlssEvaluator.jitterSignX), () ->
                DlssEvaluator.jitterSignX = -DlssEvaluator.jitterSignX));
        col.addChild(cycle(() -> "Jitter Sign Y: " + sign(DlssEvaluator.jitterSignY), () ->
                DlssEvaluator.jitterSignY = -DlssEvaluator.jitterSignY));
        col.addChild(Button.builder(Component.literal("Print SL / FG status to chat"), b -> printStatus())
                .size(220, 20).build());

        this.layout.addToContents(col);
        this.layout.addToFooter(Button.builder(Component.literal("Done"), b -> this.onClose()).size(220, 20).build());
        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    /** A button whose label reflects live state; clicking runs {@code action} then refreshes the label. */
    private Button cycle(Supplier<String> label, Runnable action) {
        return Button.builder(Component.literal(label.get()), b -> {
            action.run();
            b.setMessage(Component.literal(label.get()));
        }).size(220, 20).build();
    }

    private static String sign(float v) {
        return v >= 0 ? "+" : "-";
    }

    private void printStatus() {
        String msg = "[DLSSmc] " + SlBridge.status() + " | FG: " + SlBridge.frameGenState();
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.sendSystemMessage(Component.literal(msg));
        }
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
