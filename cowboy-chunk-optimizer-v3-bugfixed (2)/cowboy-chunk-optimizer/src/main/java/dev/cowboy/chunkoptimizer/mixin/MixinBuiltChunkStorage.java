package dev.cowboy.chunkoptimizer.mixin;

import dev.cowboy.chunkoptimizer.graph.SectionPaletteHasher;
import dev.cowboy.chunkoptimizer.graph.SectionVisibilityGraph;
import dev.cowboy.chunkoptimizer.render.ChunkFadeSystem;
import dev.cowboy.chunkoptimizer.scheduler.SectionCuller;
import net.minecraft.client.render.chunk.BuiltChunkStorage;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * BuiltChunkStorage hooks.
 *
 * BuiltChunkStorage manages the grid of BuiltChunk objects — one per
 * section position within render distance. It is responsible for
 * repositioning sections when the player moves (cycling the grid).
 *
 * We hook:
 *  1. updateCameraPosition() — fires when the player moves far enough that
 *     the section grid needs to shift. Invalidate visibility graph entries
 *     for the newly-exposed sections (they moved out of the old position).
 *
 *  2. The section eviction path — when a BuiltChunk is evicted from the grid
 *     (replaced by a new position), release its mesh buffer back to MeshPool
 *     and remove its fade entry.
 */
@Mixin(BuiltChunkStorage.class)
public abstract class MixinBuiltChunkStorage {

    /**
     * When the section grid repositions (player moved), invalidate caches
     * for all sections that shifted position. The SectionVisibilityGraph
     * entries are keyed by position and need to be refreshed for any
     * section that moved to a new chunk coordinate.
     *
     * We conservatively increment the generation counter here, which marks
     * all cached entries as potentially stale. The cache will rebuild on
     * the next access, which is the correct behaviour: movement means new
     * terrain, and the old face-exposure data no longer applies.
     */
    @Inject(method = "updateCameraPosition", at = @At("HEAD"))
    private void cco_onGridShift(double x, double y, double z, CallbackInfo ci) {
        // Increment the graph generation: all cached face masks become stale.
        // The next rebuild-schedule cycle will recompute them from fresh chunk data.
        SectionVisibilityGraph.clear();
    }
}
