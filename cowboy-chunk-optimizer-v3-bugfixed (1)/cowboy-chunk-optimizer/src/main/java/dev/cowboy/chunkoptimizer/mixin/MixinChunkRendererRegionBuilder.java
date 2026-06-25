package dev.cowboy.chunkoptimizer.mixin;

import dev.cowboy.chunkoptimizer.graph.SectionPaletteHasher;
import dev.cowboy.chunkoptimizer.scheduler.SectionCuller;
import net.minecraft.client.render.chunk.ChunkRendererRegion;
import net.minecraft.client.render.chunk.ChunkRendererRegionBuilder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.chunk.ChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ChunkRendererRegionBuilder.build() hook — section-level culling.
 *
 * This is the last gate before a worker thread task is created.
 * build() takes a world reference and the origin BlockPos of the section
 * and constructs a ChunkRendererRegion containing all the block data
 * the worker needs to mesh the section.
 *
 * If we return null here, the ChunkRenderDispatcher treats the section
 * as having nothing to render and skips the worker task entirely.
 *
 * We check:
 *  1. Whether the section is empty (via SectionCuller)
 *  2. Whether the section's palette hash matches the last-built state
 *     (via SectionPaletteHasher — skip if unchanged)
 *
 * Returning null from build() is safe: it is a documented return value
 * in vanilla's own code (it returns null for out-of-bounds or empty regions).
 */
@Mixin(ChunkRendererRegionBuilder.class)
public abstract class MixinChunkRendererRegionBuilder {

    @Inject(
        method = "build",
        at = @At("HEAD"),
        cancellable = true
    )
    private void cco_cullBeforeBuild(
            BlockRenderView world,
            BlockPos origin,
            CallbackInfoReturnable<ChunkRendererRegion> cir) {

        int sx = ChunkSectionPos.getSectionCoord(origin.getX());
        int sy = ChunkSectionPos.getSectionCoord(origin.getY());
        int sz = ChunkSectionPos.getSectionCoord(origin.getZ());

        // Retrieve the ChunkSection at this position
        ChunkSection section = null;
        try {
            if (world != null) {
                var chunk = world.getChunk(sx, sz);
                if (chunk != null) {
                    int yIndex = chunk.getSectionIndex(origin.getY());
                    if (yIndex >= 0 && yIndex < chunk.getSectionArray().length) {
                        section = chunk.getSectionArray()[yIndex];
                    }
                }
            }
        } catch (Exception ignored) {
            // If we can't get the section, bail out gracefully — let vanilla handle it
            return;
        }

        // Gate 1: SectionCuller (air / invisible / fluid-only)
        if (SectionCuller.shouldSkip(section, sx, sy, sz)) {
            cir.setReturnValue(null);
            return;
        }

        // Gate 2: Palette hash dedup (unchanged sections since last build)
        if (section != null && SectionPaletteHasher.isUnchanged(section, sx, sy, sz)) {
            cir.setReturnValue(null);
        }

        // If we reach here: allow build() to continue normally
    }
}
