package dev.cowboy.chunkoptimizer.util;

import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;

/**
 * Adaptive GPU upload budget controller.
 *
 * Each frame, computes the effective upload budget in nanoseconds based on:
 *   1. Player velocity  — fast player → tighten budget (protect FPS)
 *                        still player → loosen budget (load fast)
 *   2. Frame time pressure — if last frame took longer than targetFrameTime,
 *      reduce budget to give the frame more room.
 *   3. Pending chunk count — if there are many chunks waiting, modestly
 *      increase budget to clear the backlog faster.
 *
 * The controller is intentionally conservative: it biases toward smooth
 * frames rather than fast loads, which is the right tradeoff for a client
 * optimisation mod.
 */
public final class AdaptiveBudgetController {

    private AdaptiveBudgetController() {}

    // Rolling last-frame time, updated each frame from WorldRenderer
    private static volatile long lastFrameNanos = 16_000_000L; // assume 60fps start
    private static volatile long lastFrameStartNanos = 0L;

    // Current effective budget (cached, recomputed per frame)
    private static volatile long currentBudgetNanos = 5_000_000L;

    // Exponential smoothing factor for frame time
    private static final float EMA_ALPHA = 0.15f;
    private static float smoothedFrameMs = 16f;

    /**
     * Call at the start of each frame render to snapshot the frame timing.
     */
    public static void onFrameStart() {
        long now = System.nanoTime();
        if (lastFrameStartNanos != 0L) {
            lastFrameNanos = now - lastFrameStartNanos;
            // EMA smooth
            float frameMs = lastFrameNanos / 1_000_000f;
            smoothedFrameMs = EMA_ALPHA * frameMs + (1 - EMA_ALPHA) * smoothedFrameMs;
        }
        lastFrameStartNanos = now;
    }

    /**
     * Compute this frame's effective upload budget and cache it.
     * Call once per frame before any upload attempts.
     *
     * @param pendingChunks number of sections waiting to be uploaded
     */
    public static void compute(int pendingChunks) {
        ChunkOptimizerConfig cfg = ChunkOptimizerConfig.get();

        if (!cfg.adaptiveBudget) {
            currentBudgetNanos = (long) cfg.uploadBudgetMs * 1_000_000L;
            return;
        }

        float targetMs   = cfg.targetFrameTimeMs;
        float minBudget  = cfg.adaptiveBudgetMin;
        float maxBudget  = cfg.adaptiveBudgetMax;
        float baseBudget = cfg.uploadBudgetMs;

        // 1. Velocity factor: fast → compress budget toward min
        float speedFactor;
        if (VelocityTracker.isVeryFast()) {
            speedFactor = 0.0f; // absolute minimum, player is flying
        } else if (VelocityTracker.isFast()) {
            speedFactor = 0.25f;
        } else if (VelocityTracker.isStationary()) {
            speedFactor = 1.0f; // full expansion toward max
        } else {
            // Linear interpolation: 0.5–5 b/s maps to 0.25–0.75
            float speed = (float) VelocityTracker.getSmoothedSpeed();
            speedFactor = 1.0f - (speed / 5.0f) * 0.5f;
        }

        // 2. Frame pressure factor: if we're over target, tighten
        float framePressure = smoothedFrameMs / targetMs; // >1 = over budget
        float pressureFactor = framePressure > 1.1f
            ? Math.max(0.2f, 1.0f - (framePressure - 1.0f)) // reduce proportionally
            : 1.0f;

        // 3. Backlog bonus: many pending chunks → small budget increase
        //    Never exceed maxBudget regardless
        float backlogBonus = pendingChunks > 20 ? 1.15f : 1.0f;

        // Combine: lerp between min and max by speedFactor, then apply pressure
        float budget = (minBudget + (maxBudget - minBudget) * speedFactor)
                       * pressureFactor
                       * backlogBonus;

        // Hard floor at min, hard cap at max
        budget = Math.max(minBudget, Math.min(maxBudget, budget));

        currentBudgetNanos = (long)(budget * 1_000_000L);
    }

    /** Get the deadline (absolute nanotime) by which uploads must stop. */
    public static long getDeadlineNanos() {
        return System.nanoTime() + currentBudgetNanos;
    }

    /** Get the current budget in ms (for HUD display). */
    public static float getCurrentBudgetMs() {
        return currentBudgetNanos / 1_000_000f;
    }

    /** Get smoothed frame time for HUD display. */
    public static float getSmoothedFrameMs() {
        return smoothedFrameMs;
    }
}
