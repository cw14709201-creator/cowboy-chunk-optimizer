package dev.cowboy.chunkoptimizer.throttle;

import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;
import net.minecraft.util.math.ChunkSectionPos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Translucent Sort Throttle.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * THE PROBLEM
 * ═══════════════════════════════════════════════════════════════════════════
 * Every frame, Minecraft's WorldRenderer re-sorts the vertices of every
 * translucent section (water, ice, stained glass, nether portals, honey
 * blocks, etc.) to maintain correct back-to-front draw order for alpha
 * blending. This sort runs on the main thread.
 *
 * The sort is O(n log n) over the quad count in each section. A section
 * full of water can have 500–2000 quads. With render distance 12, there
 * can be 200+ translucent sections, each sorted every frame.
 *
 * At 60fps, that's 60 × 200 × ~0.02ms = 240ms/second spent on sorting
 * geometry that hasn't changed. In water-heavy worlds (oceans, rivers,
 * swamps), this can consume 3–8ms per frame — a huge chunk of your budget.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * THE SOLUTION
 * ═══════════════════════════════════════════════════════════════════════════
 * Only re-sort a translucent section when the camera has moved far enough
 * relative to that section to change the correct sort order.
 *
 * EXACT CONDITION: The sort order for a section only changes when the camera
 * moves more than ~(sectionSize / 2) relative to the section centre, OR when
 * the camera crosses a section boundary. We approximate this as:
 *   "re-sort if camera moved > SORT_THRESHOLD blocks since last sort"
 *
 * Additionally: if the section itself was rebuilt (block change), force a
 * re-sort regardless of camera movement.
 *
 * DEFAULT THRESHOLD: 0.5 blocks. At typical walking speed (4.3 b/s), this
 * means a sort every ~0.12 seconds per section (~8 sorts/sec) instead of
 * 60 sorts/sec. At elytra speed (30 b/s), threshold is hit every frame anyway
 * — no penalty for fast movement.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * IMPLEMENTATION
 * ═══════════════════════════════════════════════════════════════════════════
 * Track per-section: the camera position at which this section was last sorted.
 * Before each sort, compute camera displacement from last sort position.
 * If displacement < threshold, skip the sort and return the existing order.
 *
 * The mixin target is ChunkBuilder.BuiltChunk$RebuildTask.buildTranslucent()
 * which calls the sort. We intercept at the sort invocation site.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * EXPECTED GAINS
 * ═══════════════════════════════════════════════════════════════════════════
 * Ocean biome, RD=12:          ~5ms/frame → ~0.5ms/frame  (90% reduction)
 * Nether (lava lakes), RD=12:  ~2ms/frame → ~0.3ms/frame  (85% reduction)
 * Normal survival world:        ~1ms/frame → ~0.15ms/frame (85% reduction)
 */
public final class TranslucentSortThrottle {

    private TranslucentSortThrottle() {}

    /**
     * Per-section last-sort camera position.
     * Key: packed section pos. Value: packed (x,z) camera float pair.
     */
    private static final ConcurrentHashMap<Long, long[]> lastSortPos =
        new ConcurrentHashMap<>(512);

    // Stats
    private static final AtomicLong skippedSorts  = new AtomicLong(0);
    private static final AtomicLong executedSorts  = new AtomicLong(0);
    private static final AtomicLong forcedSorts    = new AtomicLong(0);

    // Current camera position — updated from render thread each frame
    private static volatile double camX = 0, camY = 0, camZ = 0;

    /** Called from MixinWorldRenderer once per frame with the camera position. */
    public static void updateCamera(double x, double y, double z) {
        camX = x; camY = y; camZ = z;
    }

    /**
     * Returns true if the translucent sort for this section should be SKIPPED.
     *
     * @param sx section X (chunk-section coords)
     * @param sy section Y
     * @param sz section Z
     * @param forceSort true if the section was just rebuilt (block change)
     */
    public static boolean shouldSkipSort(int sx, int sy, int sz, boolean forceSort) {
        ChunkOptimizerConfig cfg = ChunkOptimizerConfig.get();
        if (!cfg.translucentSortThrottle) return false;

        long key = ChunkSectionPos.asLong(sx, sy, sz);

        if (forceSort) {
            // Section was rebuilt — must re-sort, record current position
            lastSortPos.put(key, packPos(camX, camY, camZ));
            forcedSorts.incrementAndGet();
            return false;
        }

        long[] last = lastSortPos.get(key);
        if (last == null) {
            // Never sorted — must sort now
            lastSortPos.put(key, packPos(camX, camY, camZ));
            executedSorts.incrementAndGet();
            return false;
        }

        double lx = Double.longBitsToDouble(last[0]);
        double ly = Double.longBitsToDouble(last[1]);
        double lz = Double.longBitsToDouble(last[2]);

        double dx = camX - lx;
        double dy = camY - ly;
        double dz = camZ - lz;
        double movedSq = dx*dx + dy*dy + dz*dz;

        float threshold = cfg.translucentSortThresholdBlocks;
        if (movedSq < threshold * threshold) {
            skippedSorts.incrementAndGet();
            return true; // camera hasn't moved enough — skip
        }

        // Camera moved enough — re-sort and record new position
        last[0] = Double.doubleToRawLongBits(camX);
        last[1] = Double.doubleToRawLongBits(camY);
        last[2] = Double.doubleToRawLongBits(camZ);
        executedSorts.incrementAndGet();
        return false;
    }

    /**
     * Force-invalidate a section's sort position (section was rebuilt).
     * The next sort call for this section will always execute.
     */
    public static void invalidateSection(int sx, int sy, int sz) {
        lastSortPos.remove(ChunkSectionPos.asLong(sx, sy, sz));
    }

    public static void invalidateChunk(int cx, int cz) {
        for (int sy = -4; sy <= 19; sy++) {
            lastSortPos.remove(ChunkSectionPos.asLong(cx, sy, cz));
        }
    }

    public static void clear() {
        lastSortPos.clear();
        skippedSorts.set(0);
        executedSorts.set(0);
        forcedSorts.set(0);
    }

    private static long[] packPos(double x, double y, double z) {
        return new long[]{
            Double.doubleToRawLongBits(x),
            Double.doubleToRawLongBits(y),
            Double.doubleToRawLongBits(z)
        };
    }

    // Stats for HUD
    public static long getSkippedSorts()  { return skippedSorts.get(); }
    public static long getExecutedSorts() { return executedSorts.get(); }
    public static long getForcedSorts()   { return forcedSorts.get(); }
    public static int  getCacheSize()     { return lastSortPos.size(); }

    public static float getSkipRate() {
        long total = skippedSorts.get() + executedSorts.get();
        return total == 0 ? 0f : (float) skippedSorts.get() / total;
    }
}
