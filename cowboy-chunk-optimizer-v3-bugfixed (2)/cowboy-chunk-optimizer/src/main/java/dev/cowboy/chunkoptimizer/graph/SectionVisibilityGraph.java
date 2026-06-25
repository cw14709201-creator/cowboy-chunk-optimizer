package dev.cowboy.chunkoptimizer.graph;

import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Section Visibility Graph — a simplified version of Minecraft's existing
 * occlusion graph that runs BEFORE section rebuild scheduling.
 *
 * Goal: determine whether a section even needs to be rebuilt yet, based on
 * whether any of its 6 neighboring sections are air (i.e. the section could
 * possibly have visible faces). Fully surrounded sections (cave interior, deep
 * underground, ocean floor) are culled from the rebuild queue entirely until
 * one of their neighbors changes.
 *
 * This is distinct from Minecraft's existing cave culling (which runs per-frame
 * during rendering). Our culling fires during REBUILD SCHEDULING — so we save
 * worker thread time building meshes that will never be rendered anyway.
 *
 * Implementation:
 *   For each section coordinate (sx, sy, sz), we store a 6-bit "exposed face"
 *   bitmask: bit N = face N has a neighboring section that is empty/air.
 *   If the bitmask is 0 (fully enclosed), the section's rebuild is deferred.
 *
 *   The bitmask is updated when neighboring sections change (block updates,
 *   chunk loads). We hook into ClientChunkManager to invalidate entries when
 *   chunks arrive.
 *
 * Bit layout for face mask:
 *   bit 0 = +X neighbor is air
 *   bit 1 = -X neighbor is air
 *   bit 2 = +Y neighbor is air
 *   bit 3 = -Y neighbor is air
 *   bit 4 = +Z neighbor is air
 *   bit 5 = -Z neighbor is air
 *
 * Cache strategy: LRU eviction via a simple generation counter. Entries older
 * than STALE_GENERATIONS are considered invalid and recomputed on next access.
 */
public final class SectionVisibilityGraph {

    private SectionVisibilityGraph() {}

    // Key: ChunkSectionPos.asLong(sx, sy, sz)
    // Value: 6-bit face mask | (generation << 8)
    private static final ConcurrentHashMap<Long, Long> faceMaskCache = new ConcurrentHashMap<>(4096);

    private static final AtomicLong generation = new AtomicLong(0);
    private static final long STALE_GENERATIONS = 5;

    // Cache stats
    private static final AtomicLong cacheHits   = new AtomicLong(0);
    private static final AtomicLong cacheMisses  = new AtomicLong(0);
    private static final AtomicLong culledTotal  = new AtomicLong(0);

    // Face index constants
    public static final int FACE_POS_X = 0;
    public static final int FACE_NEG_X = 1;
    public static final int FACE_POS_Y = 2;
    public static final int FACE_NEG_Y = 3;
    public static final int FACE_POS_Z = 4;
    public static final int FACE_NEG_Z = 5;

    /**
     * Store face exposure data for a section.
     * Called when a section's neighbors are analysed (from MixinClientChunkManager).
     *
     * @param sx section X
     * @param sy section Y
     * @param sz section Z
     * @param faceMask 6-bit bitmask of exposed faces
     */
    public static void setFaceMask(int sx, int sy, int sz, int faceMask) {
        long key = packKey(sx, sy, sz);
        long gen = generation.get();
        faceMaskCache.put(key, (long) faceMask | (gen << 8));
    }

    /**
     * Check whether a section should be culled from the rebuild queue.
     *
     * Returns true (cull it) when:
     *   - earlyOcclusionCull is enabled, AND
     *   - the section has no exposed faces (fully surrounded by solid sections)
     *
     * Returns false (let it rebuild) when:
     *   - earlyOcclusionCull is disabled
     *   - the section has at least one exposed face
     *   - no face data is cached (conservative: allow rebuild)
     *   - the cached data is stale
     */
    public static boolean shouldCull(int sx, int sy, int sz) {
        if (!ChunkOptimizerConfig.get().earlyOcclusionCull) return false;

        long key  = packKey(sx, sy, sz);
        Long entry = faceMaskCache.get(key);

        if (entry == null) {
            cacheMisses.incrementAndGet();
            return false; // no data → conservative: allow rebuild
        }

        long entryGen = entry >> 8;
        long curGen   = generation.get();

        if (curGen - entryGen > STALE_GENERATIONS) {
            cacheMisses.incrementAndGet();
            faceMaskCache.remove(key); // evict stale
            return false;
        }

        cacheHits.incrementAndGet();
        int faceMask = (int)(entry & 0xFF);

        if (faceMask == 0) {
            // Fully enclosed — no face is exposed to air
            culledTotal.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * Invalidate cached face data for an entire chunk column.
     * Call when a chunk is loaded or unloaded.
     */
    public static void invalidateChunk(int cx, int cz) {
        generation.incrementAndGet();
        // Remove stale entries for this column proactively
        for (int sy = -4; sy <= 20; sy++) {
            faceMaskCache.remove(packKey(cx, sy, cz));
        }
    }

    /**
     * Invalidate a specific section and its 6 face-neighbors.
     * Call when a block update changes a section's content.
     */
    public static void invalidateSection(int sx, int sy, int sz) {
        faceMaskCache.remove(packKey(sx, sy, sz));
        faceMaskCache.remove(packKey(sx + 1, sy, sz));
        faceMaskCache.remove(packKey(sx - 1, sy, sz));
        faceMaskCache.remove(packKey(sx, sy + 1, sz));
        faceMaskCache.remove(packKey(sx, sy - 1, sz));
        faceMaskCache.remove(packKey(sx, sy, sz + 1));
        faceMaskCache.remove(packKey(sx, sy, sz - 1));
    }

    /** Clear the entire cache (world unload, F3+A). */
    public static void clear() {
        faceMaskCache.clear();
        generation.incrementAndGet();
    }

    // ── Stats ─────────────────────────────────────────────────────────────

    public static long getCacheHits()  { return cacheHits.get(); }
    public static long getCacheMisses(){ return cacheMisses.get(); }
    public static long getCulledTotal(){ return culledTotal.get(); }
    public static int  getCacheSize()  { return faceMaskCache.size(); }

    // ── Packing ────────────────────────────────────────────────────────────
    // Encode (sx, sy, sz) into a compact long key.
    // X/Z range: ±4096 sections (64k blocks). Y range: -64 to +384 (24 bits)
    // Encoding: sx[24b] | sz[20b] | sy[8b] | padding
    private static long packKey(int sx, int sy, int sz) {
        return ((long)(sx & 0xFFFFFF) << 28) | ((long)(sz & 0xFFFFF) << 8) | (sy & 0xFF);
    }
}
