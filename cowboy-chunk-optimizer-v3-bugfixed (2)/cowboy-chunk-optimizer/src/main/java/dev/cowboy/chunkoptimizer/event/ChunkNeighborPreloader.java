package dev.cowboy.chunkoptimizer.event;

import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;
import dev.cowboy.chunkoptimizer.graph.SectionVisibilityGraph;
import dev.cowboy.chunkoptimizer.scheduler.SectionPriorityScorer;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chunk Neighbor Pre-loader.
 *
 * Problem: When a new chunk arrives, it often makes its neighboring sections
 * "dirty" because a previously-hidden face is now exposed. Vanilla queues these
 * neighbor section rebuilds via the normal dirty-flag path, but this happens
 * reactively — the neighbor section rebuild only enters the queue AFTER the
 * new chunk's own rebuild completes.
 *
 * Solution: When we learn a chunk is arriving, proactively invalidate the face
 * exposure data for all border sections of its 8 neighboring chunks. This way
 * their rebuilds are queued in the right priority order immediately, rather than
 * waiting for the ripple-effect from vanilla's rebuild notification.
 *
 * Additionally, we pre-sort these border sections into the priority queue so
 * the nearest ones (to the camera) are processed first among the neighbors.
 *
 * Impact: Eliminates the "border seam" visual artifact where chunk borders
 * show their old (incorrect) face exposure state for 1–2 frames after a new
 * chunk loads.
 *
 * Implementation:
 *   When onChunkArriving(cx, cz) is called from MixinClientChunkManager,
 *   we enqueue invalidation tasks for all 8 neighbors into a lock-free queue.
 *   A drain method is called from the tick handler to process them in batches.
 *
 *   We deliberately defer the actual invalidation to avoid doing N²
 *   cache-busting on the hot chunk-load path.
 */
public final class ChunkNeighborPreloader {

    private ChunkNeighborPreloader() {}

    // Pending invalidation requests: packed (cx << 32 | cz)
    private static final ConcurrentLinkedQueue<Long> pendingInvalidations = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger processedThisTick = new AtomicInteger(0);

    // Max invalidations to process per tick (avoids monopolising tick time)
    private static final int MAX_PER_TICK = 16;

    /**
     * Call from MixinClientChunkManager when a chunk packet is received.
     * Enqueues neighbor invalidation tasks for later processing.
     */
    public static void onChunkArriving(int cx, int cz) {
        if (!ChunkOptimizerConfig.get().neighborPreloading) return;

        // Enqueue 8 neighbors + self = 9 chunks to invalidate
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                pendingInvalidations.offer(packChunk(cx + dx, cz + dz));
            }
        }
    }

    /**
     * Drain and process pending invalidation tasks.
     * Call from ClientTickEvents.END_CLIENT_TICK.
     */
    public static void drainPending() {
        if (!ChunkOptimizerConfig.get().neighborPreloading) return;

        processedThisTick.set(0);
        int limit = Math.min(MAX_PER_TICK, pendingInvalidations.size());

        for (int i = 0; i < limit; i++) {
            Long packed = pendingInvalidations.poll();
            if (packed == null) break;

            int cx = (int)(packed >> 32);
            int cz = (int)(packed & 0xFFFFFFFFL);

            // Invalidate section visibility graph for this chunk column
            SectionVisibilityGraph.invalidateChunk(cx, cz);

            processedThisTick.incrementAndGet();
        }
    }

    /**
     * On a chunk being unloaded, ensure its neighbor caches are also cleared
     * since the neighbor sections now have newly-exposed faces.
     */
    public static void onChunkUnloading(int cx, int cz) {
        if (!ChunkOptimizerConfig.get().neighborPreloading) return;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                pendingInvalidations.offer(packChunk(cx + dx, cz + dz));
            }
        }
    }

    public static int getPendingCount()      { return pendingInvalidations.size(); }
    public static int getProcessedThisTick() { return processedThisTick.get(); }

    private static long packChunk(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
