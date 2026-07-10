package com.jhp.client.dlss;

import com.jhp.DLSSmc;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;

/**
 * Resolution decoupling (Phase 1, tracker task P1-5).
 *
 * <p>Owns a single offscreen {@link TextureTarget} that the world is rendered into at a
 * fraction of the native resolution. {@code GameRendererMixin} swaps
 * {@code GameRenderer.mainRenderTarget} to this target for the duration of
 * {@code renderLevel}, so the entire world render graph runs at the internal resolution,
 * then blit-upscales the result back into the real native target before the HUD draws.</p>
 *
 * <p>Formats match {@code MainTarget}: {@code RGBA8_UNORM} + {@code D32_FLOAT}. The blit
 * currently uses NEAREST (blocky) — DLSS will replace it in Phase 2.</p>
 */
public final class DlssResolution {
    private DlssResolution() {}

    /** Internal render scale as a fraction of native. 1.0 disables decoupling. */
    public static volatile float scale = 0.5f; // DLSS Quality~0.667, Performance~0.5

    /** Scales cycled by the F8 keybind: native (off), Quality, Performance. */
    private static final float[] SCALES = {1.0f, 0.667f, 0.5f};
    private static int scaleIndex = 2; // matches the 0.5f default

    private static TextureTarget levelTarget;
    private static boolean loggedOnce = false;

    /** Advance to the next render scale (F8). */
    public static void cycleScale() {
        scaleIndex = (scaleIndex + 1) % SCALES.length;
        scale = SCALES[scaleIndex];
        loggedOnce = false; // re-log the new internal resolution on the next frame
        DLSSmc.LOGGER.info("[DLSSmc] render scale -> {} ({})", scale, enabled() ? "decoupled" : "native/off");
    }

    /** True when the scale calls for a real downscale. */
    public static boolean enabled() {
        return scale > 0.0f && scale < 0.999f;
    }

    /** Ensure the offscreen level target exists at scale*native and return it. */
    public static TextureTarget ensureLevelTarget(int nativeWidth, int nativeHeight) {
        int w = Math.max(1, Math.round(nativeWidth * scale));
        int h = Math.max(1, Math.round(nativeHeight * scale));
        if (levelTarget == null) {
            levelTarget = new TextureTarget("dlss-level", w, h, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        } else if (levelTarget.width != w || levelTarget.height != h) {
            levelTarget.resize(w, h);
        }
        if (!loggedOnce) {
            loggedOnce = true;
            DLSSmc.LOGGER.info("[DLSSmc] resolution decoupled: native {}x{} -> internal {}x{} (scale={})",
                    nativeWidth, nativeHeight, w, h, scale);
        }
        return levelTarget;
    }

    public static TextureTarget levelTarget() {
        return levelTarget;
    }
}
