package com.jhp.client.dlss;

import org.joml.Matrix4f;

/**
 * Sub-pixel projection jitter for DLSS (Phase 1, tracker task P1-4).
 *
 * <p>Produces a Halton(2,3) low-discrepancy jitter sequence and applies it to the
 * level projection matrix. DLSS also consumes the current jitter offset (in pixels) as
 * a per-frame input, so both the applied matrix offset and the raw pixel offset are
 * exposed here for Phase 2.</p>
 *
 * <p>Scope is controlled by an "active" window: {@code GameRendererMixin} opens it
 * around the level (world) render so the HUD/UI projection is left unjittered.</p>
 *
 * <p><b>Correctness note:</b> the exact sign/space of the jitter (Y-up vs Vulkan
 * Y-down, NDC range) needs empirical render/look/adjust iteration on a real device
 * (see the brief). The sign flags below exist for that tuning — the defaults are a
 * starting point, not a final answer.</p>
 */
public final class DlssJitter {
    private DlssJitter() {}

    /** Jitter phase count. DLSS suggests ~8 * (outputRes/renderRes)^2; tune later. */
    private static final int PHASE_COUNT = 16;

    // Sign conventions to flip during empirical tuning.
    private static final float SIGN_X = 1.0f;
    private static final float SIGN_Y = 1.0f; // try -1 under Vulkan Y-flip

    private static int phase = 0;
    private static volatile boolean active = false;
    private static volatile boolean resetRequested = false;

    // Current sub-pixel offset in pixel units, range [-0.5, 0.5].
    private static volatile float offsetX = 0f;
    private static volatile float offsetY = 0f;

    // Internal 3D render resolution used to convert the pixel offset into NDC.
    // TODO(P1-5): feed the real decoupled internal render size; placeholder for now.
    private static volatile int renderWidth = 1920;
    private static volatile int renderHeight = 1080;

    /** Start of a level frame: advance the sequence and open the jitter window. */
    public static void beginLevelFrame() {
        phase = (phase + 1) % PHASE_COUNT;
        // Halton is defined on [0,1); center to [-0.5, 0.5) so the mean offset is ~0.
        offsetX = halton(phase + 1, 2) - 0.5f;
        offsetY = halton(phase + 1, 3) - 0.5f;
        active = true;
    }

    /** End of the level frame: close the jitter window. */
    public static void endLevelFrame() {
        active = false;
    }

    public static boolean isActive() {
        return active;
    }

    public static void setRenderResolution(int width, int height) {
        if (width > 0 && height > 0) {
            renderWidth = width;
            renderHeight = height;
        }
    }

    /** Apply the current jitter to a projection matrix in place; returns it. */
    public static Matrix4f applyTo(Matrix4f projection) {
        float ndcX = SIGN_X * 2.0f * offsetX / renderWidth;
        float ndcY = SIGN_Y * 2.0f * offsetY / renderHeight;
        // Column-major (JOML): shift clip-space x,y via the z-column terms.
        projection.m20(projection.m20() + ndcX);
        projection.m21(projection.m21() + ndcY);
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
