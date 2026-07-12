package com.jhp.client.dlss;

import com.jhp.DLSSmc;
import com.jhp.client.dlss.SlBridge;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Automated benchmark that cycles through DLSS configurations, measures FPS,
 * and writes results to {@code dlssmc_benchmark_<timestamp>.log}.
 *
 * <p>Frame counting is wired into {@code MinecraftMixin.renderFrame} HEAD.
 * Start with the {@code /dlssmc bench} command in a stable scene.
 */
public final class DlssBenchmark {
    private DlssBenchmark() {}

    private static final long WARMUP_NS = 3_000_000_000L;  // 3 s
    private static final long MEASURE_NS = 5_000_000_000L; // 5 s

    private record Step(String label, Runnable apply) {}

    private static final List<Step> STEPS = List.of(
        new Step("DLSS off, native 1.0", () -> {
            DlssEvaluator.enabled = false;
            DlssResolution.scale = 1.0f;
        }),
        new Step("DLSS off, scale 0.667", () -> {
            DlssEvaluator.enabled = false;
            DlssResolution.scale = 0.667f;
        }),
        new Step("DLSS on, scale 0.667, preset K (transformer)", () -> {
            DlssEvaluator.enabled = true;
            DlssEvaluator.reset();
            DlssResolution.scale = 0.667f;
            DlssEvaluator.presetOverride = 11;
            DlssEvaluator.modeOverride = -1;
        }),
        new Step("DLSS on, scale 0.667, preset default (CNN)", () -> {
            DlssEvaluator.enabled = true;
            DlssEvaluator.reset();
            DlssResolution.scale = 0.667f;
            DlssEvaluator.presetOverride = 0;
            DlssEvaluator.modeOverride = -1;
        }),
        new Step("DLSS on, scale 0.667, preset L", () -> {
            DlssEvaluator.enabled = true;
            DlssEvaluator.reset();
            DlssResolution.scale = 0.667f;
            DlssEvaluator.presetOverride = 12;
            DlssEvaluator.modeOverride = -1;
        }),
        new Step("DLSS on, scale 0.667, mod=MaxPerformance", () -> {
            DlssEvaluator.enabled = true;
            DlssEvaluator.reset();
            DlssResolution.scale = 0.667f;
            DlssEvaluator.presetOverride = 11;
            DlssEvaluator.modeOverride = 1;
        }),
        new Step("DLSS on, scale 0.667, mode=Balanced", () -> {
            DlssEvaluator.enabled = true;
            DlssEvaluator.reset();
            DlssResolution.scale = 0.667f;
            DlssEvaluator.presetOverride = 11;
            DlssEvaluator.modeOverride = 2;
        }),
        new Step("DLSS on, scale 0.667, mode=MaxQuality", () -> {
            DlssEvaluator.enabled = true;
            DlssEvaluator.reset();
            DlssResolution.scale = 0.667f;
            DlssEvaluator.presetOverride = 11;
            DlssEvaluator.modeOverride = 3;
        }),
        new Step("DLSS on, scale 0.333, preset K", () -> {
            DlssEvaluator.enabled = true;
            DlssEvaluator.reset();
            DlssResolution.scale = 0.333f;
            DlssEvaluator.presetOverride = 11;
            DlssEvaluator.modeOverride = -1;
        }),
        new Step("DLSS on, scale 0.5, preset K", () -> {
            DlssEvaluator.enabled = true;
            DlssEvaluator.reset();
            DlssResolution.scale = 0.5f;
            DlssEvaluator.presetOverride = 11;
            DlssEvaluator.modeOverride = -1;
        }),
        // FG variants
        new Step("DLSS on, scale 0.667, preset K, FG ON", () -> {
            DlssEvaluator.enabled = true;
            DlssEvaluator.reset();
            DlssResolution.scale = 0.667f;
            DlssEvaluator.presetOverride = 11;
            DlssEvaluator.modeOverride = -1;
            SlBridge.setFrameGeneration(true);
        }),
        new Step("DLSS on, scale 0.667, preset def, FG ON", () -> {
            DlssEvaluator.enabled = true;
            DlssEvaluator.reset();
            DlssResolution.scale = 0.667f;
            DlssEvaluator.presetOverride = 0;
            DlssEvaluator.modeOverride = -1;
            SlBridge.setFrameGeneration(true);
        }),
        new Step("DLSS on, scale 0.667, preset K, FG off (cleanup)", () -> {
            DlssEvaluator.enabled = true;
            DlssEvaluator.reset();
            DlssResolution.scale = 0.667f;
            DlssEvaluator.presetOverride = 11;
            DlssEvaluator.modeOverride = -1;
            SlBridge.setFrameGeneration(false);
        })
    );

    private static int stepIndex = -1;
    private static long phaseDeadline; // end of current warmup or measure phase
    private static long measureStart;
    private static int frameCount;
    private static PrintWriter log;
    private static boolean running;

    public static boolean isRunning() { return running; }

    /** Called once per rendered frame from MinecraftMixin. Records frame timing. */
    public static void onFrame() {
        if (!running) {
            return;
        }
        long now = System.nanoTime();

        if (phaseDeadline == 0) {
            // Warmup phase: waiting for deadline
            if (now >= measureStart) {
                // Warmup done, start measurement
                phaseDeadline = now + MEASURE_NS;
                measureStart = now;
                frameCount = 0;
            }
        } else if (now >= phaseDeadline) {
            // Measurement done
            double elapsed = (now - measureStart) / 1_000_000_000.0;
            double fps = frameCount / elapsed;
            Step step = STEPS.get(stepIndex);
            log.printf("%-50s %8.1f%n", step.label(), fps);
            log.flush();
            DLSSmc.LOGGER.info("[DLSSmc] bench: {} -> {:.1f} fps", step.label(), fps);
            advance();
        } else {
            frameCount++;
        }
    }

    /** Start the benchmark sequence. */
    public static void start() {
        if (running) {
            DLSSmc.LOGGER.warn("[DLSSmc] benchmark already running");
            return;
        }
        try {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Path dir = FabricLoader.getInstance().getGameDir();
            Path file = dir.resolve("dlssmc_benchmark_" + ts + ".log");
            log = new PrintWriter(file.toFile());
            log.println("DLSSmc Benchmark " + LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            log.println("Scene: current camera view (stand still for consistent results)");
            log.println(String.valueOf(new char[80]).replace('\0', '='));
            log.printf("%-50s %8s%n", "Config", "Avg FPS");
            log.println(String.valueOf(new char[80]).replace('\0', '-'));
            log.flush();
            DLSSmc.LOGGER.info("[DLSSmc] benchmark started -> {}", file);
        } catch (IOException e) {
            DLSSmc.LOGGER.error("[DLSSmc] failed to open benchmark log", e);
            return;
        }
        running = true;
        stepIndex = -1;
        advance();
    }

    /** Stop the benchmark early and close the log. */
    public static void stop() {
        if (!running) return;
        running = false;
        if (log != null) {
            log.println(String.valueOf(new char[80]).replace('\0', '='));
            log.close();
            log = null;
        }
        stepIndex = -1;
        DLSSmc.LOGGER.info("[DLSSmc] benchmark stopped");
    }

    private static void advance() {
        stepIndex++;
        if (stepIndex >= STEPS.size()) {
            stop();
            DLSSmc.LOGGER.info("[DLSSmc] benchmark complete");
            return;
        }
        Step step = STEPS.get(stepIndex);
        step.apply.run();
        // Warmup: 0 deadline means "not measuring yet"
        phaseDeadline = 0;
        measureStart = System.nanoTime() + WARMUP_NS;
        frameCount = 0;
        DLSSmc.LOGGER.info("[DLSSmc] bench step {}: {} (warming up {} s)",
                stepIndex + 1, step.label(), WARMUP_NS / 1_000_000_000);
    }
}
