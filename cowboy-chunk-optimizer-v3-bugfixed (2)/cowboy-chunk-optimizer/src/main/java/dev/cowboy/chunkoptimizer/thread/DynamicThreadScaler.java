package dev.cowboy.chunkoptimizer.thread;

import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dynamic thread pool scaler for the chunk mesh builder pool.
 *
 * Problem: Vanilla creates a fixed thread pool at startup. This is fine for
 * steady-state, but terrible for bursts (world load, F3+A, high-speed travel)
 * where you want extra threads to drain the queue fast, and for idle periods
 * where spare worker threads waste OS scheduler time.
 *
 * Solution: Periodically check the pending queue depth and scale the pool
 * between a minimum and maximum worker count:
 *
 *   pendingChunks < LOW_WATER   → scale toward minWorkers (release idle threads)
 *   pendingChunks > HIGH_WATER  → scale toward maxWorkers (bring in help)
 *   otherwise                   → hold at current count
 *
 * Scale-up is aggressive (immediate), scale-down is gentle (debounced by
 * SCALE_DOWN_DELAY_TICKS to avoid thrashing during bursty loads).
 *
 * This class wraps a ThreadPoolExecutor and exposes adjustCorePoolSize().
 * The actual pool is created by CowboyChunkOptimizerClient and passed here.
 */
public final class DynamicThreadScaler {

    private DynamicThreadScaler() {}

    private static volatile ThreadPoolExecutor pool;
    private static final AtomicInteger currentWorkers = new AtomicInteger(0);
    private static final AtomicLong    scaleDownCooldown = new AtomicLong(0);
    private static volatile long       tickCount = 0;

    private static final int LOW_WATER           = 4;
    private static final int HIGH_WATER          = 16;
    private static final int SCALE_DOWN_DELAY    = 40; // ticks before scaling down

    // Stats
    private static final AtomicInteger scaleUpEvents   = new AtomicInteger(0);
    private static final AtomicInteger scaleDownEvents = new AtomicInteger(0);

    /**
     * Register the executor pool to manage.
     * Must be called once on initialisation from the client thread.
     */
    public static void register(ThreadPoolExecutor executor) {
        pool = executor;
        currentWorkers.set(executor.getCorePoolSize());
    }

    /**
     * Call from ClientTickEvents.END_CLIENT_TICK.
     *
     * @param pendingChunks current pending rebuild queue depth
     */
    public static void tick(int pendingChunks) {
        if (!ChunkOptimizerConfig.get().dynamicThreadScaling) return;
        if (pool == null) return;

        tickCount++;
        ChunkOptimizerConfig cfg = ChunkOptimizerConfig.get();

        int min = resolveMinWorkers(cfg);
        int max = resolveMaxWorkers(cfg);
        int cur = currentWorkers.get();

        // ── Scale up ───────────────────────────────────────────────────────
        if (pendingChunks > HIGH_WATER && cur < max) {
            int newCount = Math.min(max, cur + 1);
            applyWorkerCount(newCount);
            scaleDownCooldown.set(tickCount + SCALE_DOWN_DELAY);
            scaleUpEvents.incrementAndGet();
        }

        // ── Scale down ─────────────────────────────────────────────────────
        else if (pendingChunks < LOW_WATER && cur > min) {
            if (tickCount >= scaleDownCooldown.get()) {
                int newCount = Math.max(min, cur - 1);
                applyWorkerCount(newCount);
                scaleDownEvents.incrementAndGet();
            }
        }
        // ── Hold ───────────────────────────────────────────────────────────
        else {
            scaleDownCooldown.set(Math.max(scaleDownCooldown.get(),
                tickCount + (pendingChunks > LOW_WATER ? SCALE_DOWN_DELAY / 2 : 0)));
        }
    }

    private static void applyWorkerCount(int n) {
        if (n == currentWorkers.get()) return;
        pool.setCorePoolSize(n);
        pool.setMaximumPoolSize(Math.max(n, pool.getMaximumPoolSize()));
        currentWorkers.set(n);
    }

    private static int resolveMinWorkers(ChunkOptimizerConfig cfg) {
        int override = cfg.workerThreads;
        if (override > 0) return Math.max(1, override / 2);
        int cpus = Runtime.getRuntime().availableProcessors();
        return Math.max(1, cpus / 4);
    }

    private static int resolveMaxWorkers(ChunkOptimizerConfig cfg) {
        int override = cfg.workerThreads;
        if (override > 0) return override;
        int cpus = Runtime.getRuntime().availableProcessors();
        // Leave 2 CPUs free: main thread + render thread
        return Math.max(1, cpus - 2);
    }

    public static int getCurrentWorkers() { return currentWorkers.get(); }
    public static int getScaleUpEvents()  { return scaleUpEvents.get(); }
    public static int getScaleDownEvents(){ return scaleDownEvents.get(); }
}
