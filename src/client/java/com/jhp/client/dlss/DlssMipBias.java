package com.jhp.client.dlss;

import com.jhp.DLSSmc;

/**
 * Negative texture mip/LOD bias for DLSS (MV-Q detail pass, 2026-07-12).
 *
 * <p>The world renders at renderRes &lt; displayRes; without a sampler LOD bias the
 * texture units pick mip levels as if the <i>render</i> resolution were final, so
 * surfaces go blurry one mip step (at scale 0.5) sooner than they should for the
 * display resolution — DLSS then faithfully upscales an already-smooth input
 * ("smooth distant stone" symptom). The DLSS programming guide prescribes biasing
 * world-texture samplers by {@code log2(renderRes/displayRes)} (= -1 at scale 0.5),
 * optionally up to an extra -1 for sharper reconstruction.</p>
 *
 * <p>Scope: applied ONLY to the terrain sampler ({@code LevelRenderer.chunkLayerSampler}).
 * {@code LevelRendererMixin} arms this class for the duration of {@code render}, and
 * {@code VulkanGpuSamplerMixin} substitutes renderpearl's hardcoded
 * {@code mipLodBias(0.0F)} while armed. The only {@code createSampler} call vanilla
 * makes inside {@code render()} is the chunk-layer sampler (verified against
 * 26.3-snapshot-3 decompile: LevelRenderer L399 is the sole non-SamplerCache creation),
 * so arming the whole method scopes exactly to it. SamplerCache's 32 shared samplers
 * are created at init (unarmed) and stay unbiased — deliberately, since the atlas
 * mip-generation pass samples through a cache sampler and must not be biased.</p>
 *
 * <p>Live A/B: changing {@link #extraBias} (K panel / {@code /dlssmc bias}) calls
 * {@link #requestSamplerReset()}; {@code LevelRendererMixin} then sets vanilla's
 * {@code LevelRenderState.shouldResetChunkLayerSampler}, which makes LevelRenderer
 * close + recreate the sampler with the new bias on that same frame.</p>
 */
public final class DlssMipBias {
    private DlssMipBias() {}

    /** Extra user bias on top of the auto log2(scale) term. 0 = DLSS-spec default. */
    public static volatile float extraBias = 0f;

    /** Armed while LevelRenderer.render is on the stack (render thread only). */
    private static volatile boolean armed = false;

    private static volatile boolean samplerResetRequested = false;
    private static volatile float lastLogged = Float.NaN;

    /** The DLSS-spec term: log2(renderRes/displayRes) = log2(scale); 0 at native res. */
    public static float autoBias() {
        return DlssResolution.enabled()
                ? (float) (Math.log(DlssResolution.scale) / Math.log(2.0))
                : 0f;
    }

    /** autoBias + extraBias, clamped to a sane range. */
    public static float effectiveBias() {
        float b = autoBias() + extraBias;
        return Math.max(-4f, Math.min(2f, b));
    }

    /** Cycle the extra-bias knob: 0 → -0.5 → -1 → -1.5 → +1 → +0.5 → 0. */
    public static void cycleExtra() {
        float e = extraBias;
        if (near(e, 0f)) extraBias = -0.5f;
        else if (near(e, -0.5f)) extraBias = -1f;
        else if (near(e, -1f)) extraBias = -1.5f;
        else if (near(e, -1.5f)) extraBias = 1f;
        else if (near(e, 1f)) extraBias = 0.5f;
        else extraBias = 0f;
        requestSamplerReset();
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.01f;
    }

    public static void arm() { armed = true; }
    public static void disarm() { armed = false; }
    public static boolean isArmed() { return armed; }

    /** Ask LevelRendererMixin to trigger vanilla's chunk-layer sampler rebuild. */
    public static void requestSamplerReset() { samplerResetRequested = true; }

    public static boolean consumeSamplerReset() {
        boolean r = samplerResetRequested;
        samplerResetRequested = false;
        return r;
    }

    /** One log line per distinct bias so Gate-C runs can confirm the value applied. */
    public static void logApplied(float bias) {
        if (bias != lastLogged) {
            lastLogged = bias;
            DLSSmc.LOGGER.info("[DLSSmc] terrain sampler created with mipLodBias={} (auto={}, extra={})",
                    bias, autoBias(), extraBias);
        }
    }

    public static String status() {
        return String.format("Mip Bias: %.1f (auto %.1f, extra %+.1f)", effectiveBias(), autoBias(), extraBias);
    }
}
