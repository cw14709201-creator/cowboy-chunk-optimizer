package dev.cowboy.chunkoptimizer.mixin;

import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;
import dev.cowboy.chunkoptimizer.graph.SectionVisibilityGraph;
import dev.cowboy.chunkoptimizer.lod.RenderDistanceLOD;
import dev.cowboy.chunkoptimizer.profiler.PerformanceProfiler;
import dev.cowboy.chunkoptimizer.scheduler.SectionPriorityScorer;
import dev.cowboy.chunkoptimizer.throttle.LightUpdateThrottle;
import dev.cowboy.chunkoptimizer.util.MainThreadStallGuard;
import net.minecraft.client.render.chunk.ChunkRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ChunkRenderDispatcher.scheduleRebuild() — full culling pipeline v3.
 *
 * Gate order (cheapest → most expensive):
 *  1. Dynamic queue cap                      — O(1) int compare
 *  2. LOD zone check (skip distant layers)  — O(1) distance compare
 *  3. Light update throttle                  — O(1) map lookup
 *  4. Visibility graph occlusion cull        — O(1) map lookup
 *  5. Priority scoring                        — O(1) trig + multiply
 *
 * 'important' rebuilds (block placement/break) skip gates 2–4 and always
 * proceed, since player actions must be reflected immediately.
 */
@Mixin(ChunkRenderDispatcher.class)
public abstract class MixinChunkRenderDispatcher {

    @Unique private int cco_queuedCount = 0;
    @Unique static final ThreadLocal<Double> cco_lastScore = ThreadLocal.withInitial(() -> 0.0);

    @Inject(method = "scheduleRebuild", at = @At("HEAD"), cancellable = true)
    private void cco_filterRebuild(int x, int y, int z, boolean important, CallbackInfo ci) {
        ChunkOptimizerConfig cfg = ChunkOptimizerConfig.get();
        PerformanceProfiler.start(PerformanceProfiler.Slot.CULL_CHECK);

        // ── Gate 1: Dynamic queue cap ──────────────────────────────────────
        if (!important && cco_queuedCount >= MainThreadStallGuard.getDynamicQueueCap()) {
            PerformanceProfiler.stop(PerformanceProfiler.Slot.CULL_CHECK);
            ci.cancel();
            return;
        }

        if (!important) {
            // ── Gate 2: Light update throttle ──────────────────────────────
            // This is the #1 rebuild eliminator — kills spurious light-driven rebuilds
            if (LightUpdateThrottle.isThrottled(x, y, z)) {
                PerformanceProfiler.stop(PerformanceProfiler.Slot.CULL_CHECK);
                ci.cancel();
                return;
            }

            // ── Gate 3: Visibility graph (fully enclosed sections) ─────────
            if (SectionVisibilityGraph.shouldCull(x, y, z)) {
                PerformanceProfiler.stop(PerformanceProfiler.Slot.CULL_CHECK);
                ci.cancel();
                return;
            }

            // ── Gate 4: LOD zone — don't rebuild expensive layers at distance
            // (solid-only sections in LOD zone still rebuild for their solid mesh)
            // This gate is informational: the actual layer skip happens at render time
            // via RenderDistanceLOD.shouldSkipLayer(). Here we just do priority boosting.
        } else {
            // Important rebuild: record in light throttle so immediate neighbors
            // don't get redundant light-triggered rebuilds in the same window
            LightUpdateThrottle.onImportantRebuild(x, y, z);
        }

        // ── Gate 5: Priority scoring ───────────────────────────────────────
        if (cfg.nearestFirstScheduling) {
            double score = important ? 0.0 : SectionPriorityScorer.score(x, y, z);
            cco_lastScore.set(score);
        }

        PerformanceProfiler.stop(PerformanceProfiler.Slot.CULL_CHECK);
    }

    @Inject(method = "scheduleRebuild", at = @At("RETURN"))
    private void cco_afterSchedule(int x, int y, int z, boolean important, CallbackInfo ci) {
        cco_queuedCount++;
    }

    public static double getLastScore() { return cco_lastScore.get(); }
}
