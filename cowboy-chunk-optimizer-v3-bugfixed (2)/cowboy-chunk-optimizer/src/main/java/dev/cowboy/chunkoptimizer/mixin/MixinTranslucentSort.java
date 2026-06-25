package dev.cowboy.chunkoptimizer.mixin;

import dev.cowboy.chunkoptimizer.throttle.TranslucentSortThrottle;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Translucent vertex sort throttle.
 *
 * TARGET: ChunkBuilder$BuiltChunk#scheduleSort()
 *
 * In vanilla 1.21.1, WorldRenderer calls scheduleSort() on every visible
 * translucent BuiltChunk every frame. scheduleSort() submits a sort task
 * to the worker pool, which re-sorts the translucent vertex buffer by
 * distance from the camera and re-uploads it to the GPU.
 *
 * We inject at HEAD of scheduleSort() with cancellable=true. Before the
 * sort is submitted, we ask TranslucentSortThrottle whether the camera
 * has moved far enough to warrant a new sort. If not, we cancel — the
 * existing sorted buffer remains on the GPU unchanged, which is correct
 * because the optimal sort order hasn't changed.
 *
 * CORRECTNESS: The sort is only valid for a specific camera position. If
 * we skip it, the previously-sorted order is used. For alpha blending
 * correctness, the order should be back-to-front from the camera. With a
 * 0.5-block threshold, the order is at most 0.5 blocks "stale" — visually
 * imperceptible at any normal viewing distance.
 *
 * ACCESSING SECTION COORDS:
 * BuiltChunk has an `origin` BlockPos field (the block position of the
 * section's (0,0,0) corner). We convert this to section coordinates via
 * ChunkSectionPos.getSectionCoord().
 *
 * The field is accessed via the @Shadow accessor exposed by the access widener.
 */
@Mixin(ChunkBuilder.BuiltChunk.class)
public abstract class MixinTranslucentSort {

    @Shadow
    private BlockPos origin;

    @Inject(
        method = "scheduleSort",
        at = @At("HEAD"),
        cancellable = true
    )
    private void cco_throttleSort(CallbackInfo ci) {
        if (origin == null) return;

        int sx = ChunkSectionPos.getSectionCoord(origin.getX());
        int sy = ChunkSectionPos.getSectionCoord(origin.getY());
        int sz = ChunkSectionPos.getSectionCoord(origin.getZ());

        if (TranslucentSortThrottle.shouldSkipSort(sx, sy, sz, false)) {
            ci.cancel();
        }
    }
}
