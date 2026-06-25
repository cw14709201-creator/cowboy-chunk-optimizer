package dev.cowboy.chunkoptimizer.mixin;

import dev.cowboy.chunkoptimizer.event.TeleportBurstHandler;
import dev.cowboy.chunkoptimizer.profiler.PerformanceProfiler;
import dev.cowboy.chunkoptimizer.render.ChunkUploadLoopInjector;
import dev.cowboy.chunkoptimizer.util.AdaptiveBudgetController;
import dev.cowboy.chunkoptimizer.util.MainThreadStallGuard;
import net.minecraft.client.render.chunk.ChunkBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.BlockingQueue;

/**
 * THE MOST CRITICAL MIXIN IN THE MOD.
 *
 * This is the proper implementation of the upload loop budget enforcer.
 * The problem with the HEAD/RETURN approach in MixinChunkBuilder:
 *
 *   boolean upload() {
 *       BuiltChunk.Task task;
 *       while ((task = uploadQueue.poll()) != null) {  // ← loop runs to completion
 *           task.upload();
 *       }
 *       return true;
 *   }
 *
 * Setting a flag at HEAD does nothing unless something checks it
 * BETWEEN loop iterations. The @Redirect on uploadQueue.poll() is the
 * correct approach: we intercept the poll() call itself and return null
 * (which terminates the while-loop) when the deadline has passed.
 * The task is never dequeued, so it remains in the queue for next frame.
 *
 * Phase lifecycle (coordinated with MixinChunkBuilder):
 *   HEAD inject  → beginUploadPhase() — set deadline
 *   @Redirect poll() → return null when over budget (ends loop)
 *   RETURN inject → endUploadPhase() — record phase stats
 */
@Mixin(ChunkBuilder.class)
public abstract class MixinChunkBuilderUploadLoop {

    /**
     * Called at the start of upload() — establishes the frame deadline.
     */
    @Inject(method = "upload", at = @At("HEAD"))
    private void cco_uploadHead(CallbackInfoReturnable<Boolean> cir) {
        float budgetMs     = AdaptiveBudgetController.getCurrentBudgetMs();
        float burstMult    = TeleportBurstHandler.getBudgetMultiplier();
        float penaltyMult  = MainThreadStallGuard.getPenaltyMultiplier();
        long  budgetNs     = (long)(budgetMs * burstMult * penaltyMult * 1_000_000f);

        ChunkUploadLoopInjector.beginUploadPhase(budgetNs);
        PerformanceProfiler.start(PerformanceProfiler.Slot.UPLOAD_PHASE);
    }

    /**
     * Called at the end of upload() — records phase stats.
     */
    @Inject(method = "upload", at = @At("RETURN"))
    private void cco_uploadTail(CallbackInfoReturnable<Boolean> cir) {
        PerformanceProfiler.stop(PerformanceProfiler.Slot.UPLOAD_PHASE,
            AdaptiveBudgetController.getCurrentBudgetMs());
        ChunkUploadLoopInjector.endUploadPhase();
        PerformanceProfiler.checkBudgetWarning();
    }

    /**
     * THE KEY INJECTION: @Redirect on the BlockingQueue.poll() inside upload().
     *
     * When ChunkBuilder.upload() calls uploadQueue.poll(), we intercept it.
     * - If the deadline has passed: return null → the while-loop terminates
     *   naturally. The task stays in the queue for the next frame.
     * - Otherwise: call poll() normally and return the real task.
     *
     * Target: the INVOKE on BlockingQueue.poll() inside the upload() method body.
     * This fires once per loop iteration, right before the task would be dequeued.
     *
     * Note on @Redirect vs @Inject: @Redirect replaces the entire method call.
     * We can't just cancel — we must either return the real result or null.
     * Returning null is equivalent to the queue being empty (loop termination).
     */
    @Redirect(
        method = "upload",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/concurrent/BlockingQueue;poll()Ljava/lang/Object;",
            ordinal = 0
        )
    )
    private Object cco_interceptQueuePoll(BlockingQueue<?> queue) {
        // Over budget: stop the loop by pretending the queue is empty
        if (ChunkUploadLoopInjector.isOverBudget()) {
            ChunkUploadLoopInjector.onSectionDeferred();
            return null;
        }
        // Under budget: actually poll the queue
        Object task = queue.poll();
        if (task != null) {
            // We'll check stall after this task executes (in RETURN of task.upload)
            // For now just note we started one
            MainThreadStallGuard.onSectionUploadStart();
        }
        return task;
    }
}
