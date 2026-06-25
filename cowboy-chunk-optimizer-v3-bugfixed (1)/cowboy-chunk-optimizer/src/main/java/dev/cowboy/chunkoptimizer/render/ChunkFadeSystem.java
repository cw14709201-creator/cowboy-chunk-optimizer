package dev.cowboy.chunkoptimizer.render;

import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;
import dev.cowboy.chunkoptimizer.scheduler.SectionPriorityScorer;
import net.minecraft.util.math.ChunkSectionPos;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-section fade-in alpha for newly built chunk sections.
 *
 * Design goals:
 *  - Zero-allocation on the hot path (read path during rendering)
 *  - Thread-safe: written from render thread, read during chunk draw calls
 *  - Configurable curve: linear / smoothstep / ease-in / ease-out-quad
 *  - Skips nearby sections (below fadeNearbyChunkThreshold) for immediacy
 *  - Auto-evicts fully-faded entries to prevent map growth
 */
public final class ChunkFadeSystem {

    private ChunkFadeSystem() {}

    // Long key: ChunkSectionPos.asLong(x, y, z) → build timestamp ms
    private static final ConcurrentHashMap<Long, Long> buildTimes = new ConcurrentHashMap<>(1024);

    /**
     * Register a section as newly built. Starts the fade timer.
     * Skip if the section is too close to the player (looks wrong up close).
     */
    public static void onSectionReady(int sx, int sy, int sz) {
        ChunkOptimizerConfig cfg = ChunkOptimizerConfig.get();
        if (!cfg.enableFade) return;

        // Skip very nearby sections — they should appear instantly
        int threshold = cfg.fadeNearbyChunkThreshold;
        if (threshold > 0) {
            int camCX = (int)(SectionPriorityScorer.getCamX()) >> 4;
            int camCZ = (int)(SectionPriorityScorer.getCamZ()) >> 4;
            int chunkDist = Math.max(Math.abs(sx - camCX), Math.abs(sz - camCZ));
            if (chunkDist <= threshold) return;
        }

        buildTimes.put(ChunkSectionPos.asLong(sx, sy, sz), System.currentTimeMillis());
    }

    /**
     * Get the current alpha (0.0–1.0) for this section.
     * Returns 1.0 when fade is disabled, section is unknown, or fully faded.
     */
    public static float getAlpha(int sx, int sy, int sz) {
        ChunkOptimizerConfig cfg = ChunkOptimizerConfig.get();
        if (!cfg.enableFade) return 1.0f;

        long key = ChunkSectionPos.asLong(sx, sy, sz);
        Long buildTime = buildTimes.get(key);
        if (buildTime == null) return 1.0f;

        long elapsed = System.currentTimeMillis() - buildTime;
        int duration = cfg.fadeDurationMs;
        if (elapsed >= duration) {
            buildTimes.remove(key);
            return 1.0f;
        }

        float t = (float) elapsed / duration; // 0..1
        return applyCurve(t, cfg.fadeCurveMode);
    }

    /**
     * Apply the configured alpha curve to t ∈ [0,1].
     *
     *  Mode 0 — Linear:       t
     *  Mode 1 — Smoothstep:   3t²−2t³  (default)
     *  Mode 2 — Ease-in quad: t²
     *  Mode 3 — Ease-out quad: 1-(1-t)²  → fast start, gentle finish
     */
    private static float applyCurve(float t, int mode) {
        return switch (mode) {
            case 0 -> t;
            case 1 -> t * t * (3f - 2f * t);      // smoothstep
            case 2 -> t * t;                        // ease-in
            case 3 -> 1f - (1f - t) * (1f - t);    // ease-out
            default -> t * t * (3f - 2f * t);
        };
    }

    /** True if this section still has an active fade timer. */
    public static boolean isFading(int sx, int sy, int sz) {
        return buildTimes.containsKey(ChunkSectionPos.asLong(sx, sy, sz));
    }

    /** Remove a section's fade entry when it is unloaded from the world. */
    public static void onSectionUnloaded(int sx, int sy, int sz) {
        buildTimes.remove(ChunkSectionPos.asLong(sx, sy, sz));
    }

    /** Unload all sections for an entire chunk column (called on chunk eviction). */
    public static void onChunkUnloaded(int cx, int cz) {
        // Sections span bottom to top — cover all 1.21.1 section Y coords
        for (int sy = -4; sy <= 20; sy++) {
            buildTimes.remove(ChunkSectionPos.asLong(cx, sy, cz));
        }
    }


    /** Clear all fade tracking (world unload, F3+A). */
    public static void clear() {
        buildTimes.clear();
    }

    /** Number of sections currently fading in (for HUD). */
    public static int getFadingCount() {
        return buildTimes.size();
    }

    /**
     * Periodic cleanup: evict entries whose fade has definitely completed
     * but weren't removed by getAlpha() (e.g. off-screen sections).
     * Call from a tick handler, not every frame.
     */
    public static void cleanup() {
        if (!ChunkOptimizerConfig.get().enableFade) {
            buildTimes.clear();
            return;
        }
        long now = System.currentTimeMillis();
        int duration = ChunkOptimizerConfig.get().fadeDurationMs;
        buildTimes.entrySet().removeIf(e -> (now - e.getValue()) >= duration + 200);
    }
}
