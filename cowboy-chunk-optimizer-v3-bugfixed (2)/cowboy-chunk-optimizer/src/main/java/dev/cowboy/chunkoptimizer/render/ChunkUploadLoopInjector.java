package dev.cowboy.chunkoptimizer.render;

import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;
import dev.cowboy.chunkoptimizer.util.MainThreadStallGuard;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Upload Loop Budget Enforcer.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * THE CRITICAL MISSING PIECE
 * ══════════════════════════════════════════════════════════════════════════
 *
 * The previous version injected at HEAD/RETURN of upload() and set a
 * deadline timestamp. But vanilla's upload() method looks like this:
 *
 *   boolean upload() {
 *       ChunkBuilder.BuiltChunk.Task task;
 *       while ((task = this.uploadQueue.poll()) != null) {
 *           task.upload();   // ← THIS is where GL calls happen
 *       }
 *       return true;
 *   }
 *
 * Setting a deadline at HEAD is useless unless something checks it
 * BETWEEN iterations. Our HEAD injection sets a timestamp but the loop
 * runs to completion regardless.
 *
 * The fix: inject at the top of the while-loop body (i.e. at the point
 * where a task has been polled but before task.upload() is called).
 * At that injection point, check the deadline and cancel the task if
 * expired, re-queuing it for the next frame.
 *
 * In Mixin terms: @Redirect on the Queue.poll() call inside upload().
 * When we "redirect" poll(), we can return null (ending the loop) when
 * the deadline has passed, without the actual task ever being dequeued.
 *
 * This class holds the shared deadline state. The actual @Redirect mixin
 * is in MixinChunkBuilderUploadLoop (separate class to keep mixin count
 * small and injection logic clean).
 *
 * ══════════════════════════════════════════════════════════════════════════
 * EXPECTED IMPACT
 * ══════════════════════════════════════════════════════════════════════════
 * Without this: vanilla uploads 15–30 sections per frame uncapped.
 *   Each section upload = 0.5–3ms of GL calls.
 *   30 sections × 2ms = 60ms → ~16fps during heavy chunk load.
 *
 * With this: we upload at most (budget / avg_ms_per_section) sections.
 *   5ms budget / 1.5ms avg = 3 sections/frame max.
 *   3 sections × 1.5ms = 4.5ms → 60fps maintained.
 *
 * The remaining sections upload the next frame. From the player's
 * perspective, chunks load ~3-4x slower in terms of appearance, but
 * the GAME NEVER DROPS FRAMES. This is the correct tradeoff.
 */
public final class ChunkUploadLoopInjector {

    private ChunkUploadLoopInjector() {}

    // Phase start time for accurate phase duration measurement
    private static volatile long phaseStartNs = 0L;

    // Deadline for current frame's upload phase (absolute nanotime)
    // Written once per frame at upload() HEAD, read inside loop
    public static volatile long frameDeadlineNs = Long.MAX_VALUE;

    // Per-frame counters
    private static final AtomicInteger sectionsThisFrame   = new AtomicInteger(0);
    private static final AtomicInteger sectionsDeferred    = new AtomicInteger(0);
    private static final AtomicLong    totalSectionsEver   = new AtomicLong(0);
    private static final AtomicLong    totalDeferredEver   = new AtomicLong(0);

    /**
     * Call at the start of upload() (from MixinChunkBuilder HEAD inject).
     * Establishes the deadline for this upload phase.
     *
     * @param budgetNs nanoseconds available for upload this frame
     */
    public static void beginUploadPhase(long budgetNs) {
        phaseStartNs = System.nanoTime();
        frameDeadlineNs = phaseStartNs + budgetNs;
        sectionsThisFrame.set(0);
        sectionsDeferred.set(0);
        MainThreadStallGuard.onSectionUploadStart();
    }

    /**
     * Call after each section upload completes (task.upload() returned).
     * Checks for single-section stall and updates stats.
     *
     * @return true if we should stop (deadline passed OR stall detected)
     */
    public static boolean onSectionUploaded() {
        sectionsThisFrame.incrementAndGet();
        totalSectionsEver.incrementAndGet();

        boolean stallYield = MainThreadStallGuard.onSectionUploadEnd();
        boolean deadlinePassed = System.nanoTime() >= frameDeadlineNs;

        return stallYield || deadlinePassed;
    }

    /**
     * Called when a section upload is DEFERRED (deadline passed before upload).
     * The caller should re-queue the task for the next frame.
     */
    public static void onSectionDeferred() {
        sectionsDeferred.incrementAndGet();
        totalDeferredEver.incrementAndGet();
    }

    /**
     * Call at the end of upload() (from MixinChunkBuilder RETURN inject).
     * Records phase timing for stall guard.
     */
    public static void endUploadPhase() {
        float phaseMs = phaseStartNs > 0
            ? (System.nanoTime() - phaseStartNs) / 1_000_000f
            : 0f;
        MainThreadStallGuard.onUploadPhaseEnd(Math.max(0, phaseMs));
        frameDeadlineNs = Long.MAX_VALUE;
        phaseStartNs = 0L;
    }

    /**
     * Quick deadline check — called from the redirected Queue.poll().
     * Returns true if we should stop the upload loop NOW.
     */
    public static boolean isOverBudget() {
        return System.nanoTime() >= frameDeadlineNs;
    }

    // Stats accessors for HUD
    public static int  getSectionsThisFrame()  { return sectionsThisFrame.get(); }
    public static int  getSectionsDeferred()   { return sectionsDeferred.get(); }
    public static long getTotalSections()      { return totalSectionsEver.get(); }
    public static long getTotalDeferred()      { return totalDeferredEver.get(); }
    public static float getDeferralRate() {
        long total = totalSectionsEver.get() + totalDeferredEver.get();
        return total == 0 ? 0f : (float)totalDeferredEver.get() / total;
    }
}
