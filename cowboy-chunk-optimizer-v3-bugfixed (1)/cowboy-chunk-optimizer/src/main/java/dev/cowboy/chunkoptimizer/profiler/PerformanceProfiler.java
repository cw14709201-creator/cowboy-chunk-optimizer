package dev.cowboy.chunkoptimizer.profiler;

import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight performance profiler for CCO systems.
 *
 * Tracks rolling average timing for each major subsystem so the HUD
 * can show meaningful per-system breakdowns and budget warnings can
 * fire when the upload phase consistently overruns.
 *
 * Uses exponential moving averages (EMA) — no allocation on hot path.
 * Thread-safe per-slot via volatile writes (each slot is only written
 * from one thread).
 */
public final class PerformanceProfiler {

    private PerformanceProfiler() {}

    public enum Slot {
        UPLOAD_PHASE,
        CULL_CHECK,
        PRIORITY_SORT,
        FADE_UPDATE,
        TOTAL_FRAME
    }

    private static final int N = Slot.values().length;
    private static final float[] emaMs = new float[N];
    private static final long[] startNs = new long[N];
    private static final AtomicLong[] overrunCount = new AtomicLong[N];

    static {
        for (int i = 0; i < N; i++) overrunCount[i] = new AtomicLong(0);
    }

    /** Start timing a slot. Should be called from the same thread that calls stop(). */
    public static void start(Slot slot) {
        startNs[slot.ordinal()] = System.nanoTime();
    }

    /**
     * Stop timing and update EMA for the slot.
     * @param warnThresholdMs if > 0, increment overrun counter when this threshold is exceeded
     */
    public static void stop(Slot slot, float warnThresholdMs) {
        int i = slot.ordinal();
        long elapsed = System.nanoTime() - startNs[i];
        float ms = elapsed / 1_000_000f;
        // EMA with α=0.1
        emaMs[i] = 0.1f * ms + 0.9f * emaMs[i];
        if (warnThresholdMs > 0 && ms > warnThresholdMs) {
            overrunCount[i].incrementAndGet();
        }
    }

    public static void stop(Slot slot) { stop(slot, -1f); }

    /** Get smoothed average time in ms for a slot. */
    public static float getAvgMs(Slot slot) { return emaMs[slot.ordinal()]; }

    /** Get total overrun count for a slot. */
    public static long getOverruns(Slot slot) { return overrunCount[slot.ordinal()].get(); }

    /** Log a budget warning if upload phase consistently overruns. */
    public static void checkBudgetWarning() {
        ChunkOptimizerConfig cfg = ChunkOptimizerConfig.get();
        if (!cfg.logBudgetWarnings) return;

        long overruns = getOverruns(Slot.UPLOAD_PHASE);
        if (overruns > 0 && overruns % 100 == 0) {
            dev.cowboy.chunkoptimizer.CowboyChunkOptimizerClient.LOGGER.warn(
                "[CCO] Upload phase has exceeded budget {} times. " +
                "Consider raising uploadBudgetMax or lowering render distance. " +
                "Avg upload time: {:.2f}ms",
                overruns, getAvgMs(Slot.UPLOAD_PHASE)
            );
        }
    }

    public static void reset() {
        for (int i = 0; i < N; i++) {
            emaMs[i] = 0;
            overrunCount[i].set(0);
        }
    }
}
