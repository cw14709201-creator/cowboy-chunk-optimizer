package dev.cowboy.chunkoptimizer.util;

import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Main Thread Stall Guard.
 *
 * Problem: Even with a time budget, the upload loop can stall the main thread
 * if a single section's GL calls take longer than expected (driver hiccup,
 * large section with many transparent blocks, shader compilation, etc.).
 *
 * Solution:
 *  1. Track wall time per individual upload step. If a SINGLE step exceeds
 *     SINGLE_STEP_WARN_MS, log a warning and force-yield after this step.
 *
 *  2. Track rolling average upload time per section. If the average climbs
 *     above STALL_THRESHOLD_MS, dynamically reduce maxRebuildQueue to reduce
 *     future upload volume.
 *
 *  3. Detect "frame death" — when the entire upload phase takes >FRAME_DEATH_MS.
 *     After a frame death event, halve the current budget for the next 30 frames.
 *
 * This guard is the last line of defence against the rare but severe case where
 * the budget calculation is correct but individual GL calls are pathological.
 */
public final class MainThreadStallGuard {

    private MainThreadStallGuard() {}

    // Per-step tracking (updated inside upload loop by MixinChunkBuilder)
    private static volatile long stepStartNs = 0;
    private static final AtomicLong totalUploadNs  = new AtomicLong(0);
    private static final AtomicLong uploadCount     = new AtomicLong(0);
    private static final AtomicLong stallEvents     = new AtomicLong(0);
    private static final AtomicLong frameDeathCount = new AtomicLong(0);

    // Frame-death penalty state
    private static volatile int penaltyFrames = 0;
    private static volatile float penaltyMultiplier = 1.0f;

    private static final float SINGLE_STEP_WARN_MS  =  8.0f;
    private static final float STALL_THRESHOLD_MS   =  3.0f; // avg per section
    private static final float FRAME_DEATH_MS       = 25.0f;

    // Dynamic queue cap (may be reduced below config max during stalls)
    private static volatile int dynamicQueueCap = Integer.MAX_VALUE;

    /**
     * Call at the start of uploading a single chunk section.
     */
    public static void onSectionUploadStart() {
        stepStartNs = System.nanoTime();
    }

    /**
     * Call at the end of uploading a single chunk section.
     * @return true if we should yield (stop uploading more this frame)
     */
    public static boolean onSectionUploadEnd() {
        if (stepStartNs == 0) return false;
        long elapsed = System.nanoTime() - stepStartNs;
        float ms = elapsed / 1_000_000f;

        totalUploadNs.addAndGet(elapsed);
        uploadCount.incrementAndGet();

        // Update rolling average queue cap
        long count = uploadCount.get();
        if (count > 10) {
            float avgMs = (totalUploadNs.get() / 1_000_000f) / count;
            if (avgMs > STALL_THRESHOLD_MS) {
                // Reduce dynamic cap to limit future upload volume
                dynamicQueueCap = Math.max(4, (int)(dynamicQueueCap * 0.9));
            } else if (dynamicQueueCap < ChunkOptimizerConfig.get().maxRebuildQueue) {
                // Recover toward configured max gradually
                dynamicQueueCap = Math.min(
                    ChunkOptimizerConfig.get().maxRebuildQueue,
                    dynamicQueueCap + 1
                );
            }
        }

        // Single-step stall
        if (ms > SINGLE_STEP_WARN_MS) {
            stallEvents.incrementAndGet();
            return true; // yield: stop uploading more after this expensive section
        }

        return false;
    }

    /**
     * Call at the end of the entire upload phase (after all sections for this frame).
     * @param totalPhaseMs total time spent in upload phase this frame
     */
    public static void onUploadPhaseEnd(float totalPhaseMs) {
        if (totalPhaseMs > FRAME_DEATH_MS) {
            frameDeathCount.incrementAndGet();
            penaltyFrames = 30;
            penaltyMultiplier = 0.5f;
            dev.cowboy.chunkoptimizer.CowboyChunkOptimizerClient.LOGGER.warn(
                "[CCO] Frame death detected: upload phase took {}ms (>{}ms threshold). " +
                "Reducing budget for 30 frames.",
                totalPhaseMs, FRAME_DEATH_MS
            );
        }

        // Tick penalty frames down
        if (penaltyFrames > 0) {
            penaltyFrames--;
            if (penaltyFrames == 0) {
                penaltyMultiplier = 1.0f;
            }
        }
    }

    /**
     * Get the penalty multiplier to apply to the upload budget.
     * Returns < 1.0 during frame-death recovery, 1.0 otherwise.
     */
    public static float getPenaltyMultiplier() {
        return penaltyMultiplier;
    }

    /**
     * Get the dynamic queue cap (may be lower than config max during stalls).
     */
    public static int getDynamicQueueCap() {
        return dynamicQueueCap == Integer.MAX_VALUE
            ? ChunkOptimizerConfig.get().maxRebuildQueue
            : dynamicQueueCap;
    }

    public static void reset() {
        dynamicQueueCap = Integer.MAX_VALUE;
        penaltyFrames = 0;
        penaltyMultiplier = 1.0f;
    }

    // Stats for HUD
    public static long   getStallEvents()     { return stallEvents.get(); }
    public static long   getFrameDeathCount() { return frameDeathCount.get(); }
    public static int    getPenaltyFrames()   { return penaltyFrames; }
    public static float  getAvgUploadMs()     {
        long count = uploadCount.get();
        if (count == 0) return 0f;
        return (totalUploadNs.get() / 1_000_000f) / count;
    }
}
