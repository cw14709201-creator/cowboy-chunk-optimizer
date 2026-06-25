package dev.cowboy.chunkoptimizer.memory;

import dev.cowboy.chunkoptimizer.CowboyChunkOptimizerClient;
import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Object pool for chunk section GPU buffers.
 *
 * Problem: Vanilla allocates a new native VertexBuffer each time a chunk
 * section is rebuilt. With many chunks loading (e.g. after F3+A or world join),
 * this creates thousands of allocations in a short window — hammering the GC
 * and causing GPU driver allocation stalls.
 *
 * Solution: Instead of freeing buffers on eviction, return them to this pool.
 * When a new section needs a buffer, take one from the pool instead of
 * allocating from scratch.
 *
 * Note: VertexBuffer is a client-only class. This pool stores opaque
 * Object references to avoid hard compile-time dependency issues —
 * in practice the cast is always safe since we only store VertexBuffers.
 *
 * Thread model: pool is accessed only from the render thread (GL context
 * thread), so synchronisation is unnecessary. The pool is not thread-safe
 * intentionally — VertexBuffers must not be touched from worker threads.
 */
public final class MeshPool {

    private MeshPool() {}

    // Stored as Object to avoid direct compile dependency on client class
    private static final Deque<Object> pool = new ArrayDeque<>(256);

    // Statistics
    private static final AtomicInteger totalRecycled = new AtomicInteger(0);
    private static final AtomicInteger totalMisses    = new AtomicInteger(0);

    // Age tracking: tick counter at which each entry was returned to pool
    // Parallel deque — same index as pool entries (we use a simple array for age)
    private static final int[] returnTick = new int[1024];
    private static int ageHead = 0;
    private static int currentTick = 0;

    /**
     * Attempt to retrieve a recycled buffer from the pool.
     * Returns null if the pool is empty (caller must allocate a new one).
     */
    public static Object tryAcquire() {
        if (!ChunkOptimizerConfig.get().enableMeshPool) return null;
        Object buf = pool.pollFirst();
        if (buf == null) {
            totalMisses.incrementAndGet();
        }
        return buf;
    }

    /**
     * Return a buffer to the pool after the section it belongs to is evicted
     * or rebuilt. If the pool is full, the buffer is simply discarded.
     *
     * @param buffer the VertexBuffer (as Object) to return
     */
    public static void release(Object buffer) {
        if (!ChunkOptimizerConfig.get().enableMeshPool || buffer == null) return;

        int maxSize = ChunkOptimizerConfig.get().meshPoolMaxSize;
        if (pool.size() < maxSize) {
            pool.addLast(buffer);
            // Record return tick for age-based eviction
            if (ageHead < returnTick.length) {
                returnTick[ageHead++ % returnTick.length] = currentTick;
            }
            totalRecycled.incrementAndGet();
        }
        // If pool is full, let the buffer be GC'd — no point holding extras
    }

    /**
     * Age-based eviction: discard entries that have been sitting in the pool
     * longer than meshPoolEvictAfterTicks.
     * Call from tick handler, not render loop.
     */
    public static void tick() {
        currentTick++;
        int evictAfter = ChunkOptimizerConfig.get().meshPoolEvictAfterTicks;
        if (evictAfter <= 0) return;

        // Remove from front of deque while the front entry is too old
        while (!pool.isEmpty()) {
            int headAge = returnTick[(ageHead - pool.size() + returnTick.length) % returnTick.length];
            if (currentTick - headAge > evictAfter) {
                pool.pollFirst(); // discard old buffer — native memory will be freed by GC
            } else {
                break;
            }
        }
    }

    public static int getPoolSize() { return pool.size(); }
    public static int getTotalRecycled() { return totalRecycled.get(); }
    public static int getTotalMisses() { return totalMisses.get(); }

    /** Drain the pool entirely (e.g. on world disconnect). */
    public static void clear() {
        pool.clear();
        ageHead = 0;
        totalRecycled.set(0);
        totalMisses.set(0);
    }
}
