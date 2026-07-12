package com.jhp.client.dlss;

import com.jhp.DLSSmc;
import org.joml.Matrix4f;

/**
 * Sub-pixel projection jitter for DLSS (Phase 1, tracker task P1-4).
 *
 * <p>Produces a Halton(2,3) low-discrepancy jitter sequence and applies it to the level
 * projection matrix. DLSS also consumes the current jitter offset (in pixels) as a
 * per-frame input, so the raw pixel offset is exposed here for Phase 2.</p>
 *
 * <p>Scope is controlled by an "active" window: {@code GameRendererMixin} opens it
 * around the level render so the HUD/UI projection is left unjittered. The NDC scale of
 * the offset is computed from the {@code Projection}'s own width/height (see
 * {@code ProjectionMixin}), which stays correct after resolution decoupling (P1-5).</p>
 *
 * <p><b>Correctness note:</b> the exact sign/space of the jitter (Y-up vs Vulkan
 * Y-down, NDC range) needs empirical render/look/adjust iteration on a real device
 * (see the brief). {@link #SIGN_X}/{@link #SIGN_Y} exist for that tuning.</p>
 */
public final class DlssJitter {
    private DlssJitter() {}

    /**
     * Jitter phase count per the DLSS programming guide: 8 * (outputRes/renderRes)^2
     * = 8 / scale^2 — 32 at scale 0.5, ~18 at 0.667, 8 at native/DLAA. Was a fixed 16
     * (MV-Q fix 2026-07-12): too few phases at 0.5 starves DLSS of the sub-pixel
     * samples it needs to reconstruct fine/distant detail.
     */
    private static int phaseCount() {
        float s = DlssResolution.scale;
        if (s <= 0f || s >= 0.999f) {
            return 8;
        }
        return Math.max(8, Math.min(64, Math.round(8f / (s * s))));
    }

    // Sign conventions to flip during empirical tuning.
    private static final float SIGN_X = 1.0f;
    private static final float SIGN_Y = 1.0f; // try -1 under Vulkan Y-flip

    private static int phase = 0;
    private static volatile boolean active = false;
    private static volatile boolean resetRequested = false;

    // Current sub-pixel offset in pixel units, range [-0.5, 0.5].
    private static volatile float offsetX = 0f;
    private static volatile float offsetY = 0f;

    // One-shot diagnostics so a runtime log can confirm each mixin actually fires (S2).
    private static volatile boolean loggedScope = false;
    private static volatile boolean loggedApply = false;

    /** Start of a level frame: advance the sequence and open the jitter window. */
    public static void beginLevelFrame() {
        phase = (phase + 1) % phaseCount();
        // Halton is defined on [0,1); center to [-0.5, 0.5) so the mean offset is ~0.
        offsetX = halton(phase + 1, 2) - 0.5f;
        offsetY = halton(phase + 1, 3) - 0.5f;
        active = true;
        if (!loggedScope) {
            loggedScope = true;
            DLSSmc.LOGGER.info("[DLSSmc] jitter scope active (GameRendererMixin.renderLevel hooked); phase={} offsetPx=({}, {})",
                    phase, offsetX, offsetY);
        }
    }

    /** End of the level frame: close the jitter window. */
    public static void endLevelFrame() {
        active = false;
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * Apply the current jitter to {@code projection} in place, using the given render
     * resolution to convert the pixel offset into NDC. Returns the same matrix.
     */
    public static Matrix4f applyTo(Matrix4f projection, float width, float height) {
        if (width <= 0f || height <= 0f) {
            return projection;
        }
        float ndcX = SIGN_X * 2.0f * offsetX / width;
        float ndcY = SIGN_Y * 2.0f * offsetY / height;
        // Column-major (JOML): shift clip-space x,y via the z-column terms.
        projection.m20(projection.m20() + ndcX);
        projection.m21(projection.m21() + ndcY);
        if (!loggedApply) {
            loggedApply = true;
            DLSSmc.LOGGER.info("[DLSSmc] jitter applied to WORLD projection; dims={}x{} offsetPx=({}, {}) ndc=({}, {})",
                    width, height, offsetX, offsetY, ndcX, ndcY);
        }
        return projection;
    }

    // --- Phase 2 inputs -----------------------------------------------------

    /** Current jitter X offset in pixels, range [-0.5, 0.5] (DLSS input). */
    public static float pixelOffsetX() { return offsetX; }

    /** Current jitter Y offset in pixels, range [-0.5, 0.5] (DLSS input). */
    public static float pixelOffsetY() { return offsetY; }

    public static int phase() { return phase; }

    /** Request a DLSS history reset (camera cut: teleport/portal/respawn) — see P2-4. */
    public static void requestReset() { resetRequested = true; }

    /** Consume the reset flag (Phase 2 reads this when tagging the frame). */
    public static boolean consumeReset() {
        boolean r = resetRequested;
        resetRequested = false;
        return r;
    }

    /** Halton low-discrepancy value for index i (1-based) and prime base. */
    private static float halton(int i, int base) {
        float f = 1.0f;
        float r = 0.0f;
        while (i > 0) {
            f /= base;
            r += f * (i % base);
            i /= base;
        }
        return r;
    }
}
