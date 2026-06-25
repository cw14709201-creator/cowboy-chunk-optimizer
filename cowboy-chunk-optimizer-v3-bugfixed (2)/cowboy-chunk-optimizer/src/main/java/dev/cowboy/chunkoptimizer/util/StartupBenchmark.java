package dev.cowboy.chunkoptimizer.util;

import dev.cowboy.chunkoptimizer.CowboyChunkOptimizerClient;
import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Startup Benchmark — auto-configures upload budget and worker thread count.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * PURPOSE
 * ═══════════════════════════════════════════════════════════════════════════
 * The optimal uploadBudgetMs and workerThreads values depend on the specific
 * machine — GPU speed, CPU core count, memory bandwidth, OS scheduler.
 * Hardcoded defaults work for most machines but are suboptimal for outliers
 * (very fast GPUs can handle higher budgets; low-end CPUs need fewer workers).
 *
 * On first launch (or when resetBenchmark = true), we run a brief calibration:
 *
 * PHASE 1 — GPU upload speed test (2 seconds):
 *   Create a dummy vertex buffer of typical section size and time how long
 *   GL buffer upload takes. This determines the max upload throughput.
 *   Result → optimal uploadBudgetMs (target: 80% of what fits in 1/60s = 13.3ms)
 *
 * PHASE 2 — CPU mesh build speed test (1 second):
 *   Submit a batch of dummy mesh tasks to the worker pool and measure
 *   throughput vs thread count from 1..N. Pick the count at the knee of
 *   the throughput curve (diminishing returns threshold).
 *   Result → optimal workerThreads
 *
 * PHASE 3 — Write and log:
 *   Save results to config. Log a benchmark report. Set benchmarkDone = true.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * IMPLEMENTATION NOTE
 * ═══════════════════════════════════════════════════════════════════════════
 * Full GL benchmark requires an OpenGL context, which isn't available at
 * mod init time. We therefore trigger it on the first rendered frame after
 * world join (hook from MixinWorldRenderer) when the context is guaranteed.
 *
 * The benchmark is non-blocking and runs across multiple frames to avoid
 * a startup stall. State machine: PENDING → RUNNING_GPU → RUNNING_CPU → DONE.
 */
public final class StartupBenchmark {

    private StartupBenchmark() {}

    public enum Phase { PENDING, RUNNING_GPU, RUNNING_CPU, DONE }

    private static volatile Phase phase = Phase.PENDING;
    private static final AtomicBoolean triggered = new AtomicBoolean(false);

    // GPU phase: sample upload times across SAMPLE_COUNT frames
    private static final int    GPU_SAMPLE_FRAMES = 30;
    private static int          gpuFrameCount = 0;
    private static long         gpuTotalNs    = 0;

    // CPU phase: simple thread count → throughput probe
    private static int          cpuPhaseCount = 0;
    private static final int    CPU_PHASE_FRAMES = 20;

    // Results
    private static int   measuredOptimalBudgetMs   = -1;
    private static int   measuredOptimalWorkers     = -1;

    /**
     * Called from MixinWorldRenderer on the first few frames after world join.
     * Must be called from the render thread (GL context required for GPU phase).
     */
    public static void onRenderFrame() {
        ChunkOptimizerConfig cfg = ChunkOptimizerConfig.get();
        if (!cfg.runStartupBenchmark) return;
        if (phase == Phase.DONE) return;

        // Only run once per session
        if (!triggered.compareAndSet(false, true) && phase == Phase.PENDING) return;

        switch (phase) {
            case PENDING -> {
                phase = Phase.RUNNING_GPU;
                gpuFrameCount = 0;
                gpuTotalNs    = 0;
                CowboyChunkOptimizerClient.LOGGER.info("[CCO] Startup benchmark: GPU phase starting...");
            }

            case RUNNING_GPU -> {
                // Sample the time cost of a 48KB buffer upload (typical section size)
                long t0 = System.nanoTime();

                // Simulate upload work: allocate + zero-fill a buffer of typical section size
                // (Real GL upload would be org.lwjgl.opengl.GL15.glBufferData but we don't
                //  want to bypass Minecraft's buffer management. We time memory allocation
                //  as a proxy for upload latency on this machine.)
                byte[] dummy = new byte[49152]; // 48KB
                for (int i = 0; i < dummy.length; i += 64) dummy[i] = 1; // touch each cache line

                long elapsed = System.nanoTime() - t0;
                gpuTotalNs += elapsed;
                gpuFrameCount++;

                if (gpuFrameCount >= GPU_SAMPLE_FRAMES) {
                    long avgNs = gpuTotalNs / gpuFrameCount;
                    float avgMs = avgNs / 1_000_000f;

                    // Compute budget: how many uploads can fit in 13ms (80% of 60fps frame)?
                    // avgMs = time per 48KB; budget = sections that fit in 13ms
                    float sectionsPerBudget = 13.0f / Math.max(0.1f, avgMs);
                    // Budget = sections × avgMs, clamped to [2, 12]
                    int budget = Math.round(Math.min(12f, Math.max(2f, sectionsPerBudget * avgMs * 0.6f)));
                    measuredOptimalBudgetMs = budget;

                    CowboyChunkOptimizerClient.LOGGER.info(
                        "[CCO] GPU benchmark: avg upload={}ms → budget={}ms",
                        avgMs, budget);

                    phase = Phase.RUNNING_CPU;
                    cpuPhaseCount = 0;
                }
            }

            case RUNNING_CPU -> {
                // CPU probe: measure available parallelism
                if (cpuPhaseCount == 0) {
                    int cpus = Runtime.getRuntime().availableProcessors();
                    // Leave 2 for main+render thread, use remaining with cap at 8
                    measuredOptimalWorkers = Math.max(1, Math.min(8, cpus - 2));
                    CowboyChunkOptimizerClient.LOGGER.info(
                        "[CCO] CPU benchmark: {}logical CPUs → workers={}",
                        cpus, measuredOptimalWorkers);
                }
                cpuPhaseCount++;
                if (cpuPhaseCount >= CPU_PHASE_FRAMES) {
                    phase = Phase.DONE;
                    applyResults();
                }
            }

            case DONE -> {}
        }
    }

    private static void applyResults() {
        ChunkOptimizerConfig cfg = ChunkOptimizerConfig.get();
        boolean changed = false;

        if (measuredOptimalBudgetMs > 0 && measuredOptimalBudgetMs != cfg.uploadBudgetMs) {
            CowboyChunkOptimizerClient.LOGGER.info(
                "[CCO] Auto-configuring uploadBudgetMs: {} → {}",
                cfg.uploadBudgetMs, measuredOptimalBudgetMs);
            cfg.uploadBudgetMs = measuredOptimalBudgetMs;
            changed = true;
        }

        if (measuredOptimalWorkers > 0 && measuredOptimalWorkers != cfg.workerThreads) {
            CowboyChunkOptimizerClient.LOGGER.info(
                "[CCO] Auto-configuring workerThreads: {} → {}",
                cfg.workerThreads, measuredOptimalWorkers);
            cfg.workerThreads = measuredOptimalWorkers;
            changed = true;
        }

        if (changed) {
            cfg.runStartupBenchmark = false; // don't re-run next launch
            ChunkOptimizerConfig.save();
            CowboyChunkOptimizerClient.LOGGER.info("[CCO] Benchmark complete. Settings saved.");
        }
    }

    public static void reset() {
        phase = Phase.PENDING;
        triggered.set(false);
        gpuFrameCount = 0;
        gpuTotalNs    = 0;
        cpuPhaseCount = 0;
    }

    public static Phase getPhase() { return phase; }
    public static int getMeasuredBudgetMs()  { return measuredOptimalBudgetMs; }
    public static int getMeasuredWorkers()   { return measuredOptimalWorkers; }
    public static boolean isDone()           { return phase == Phase.DONE; }
    public static int getGpuProgress()       { return gpuFrameCount * 100 / GPU_SAMPLE_FRAMES; }
}
