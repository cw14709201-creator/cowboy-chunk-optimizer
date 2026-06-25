package dev.cowboy.chunkoptimizer.mixin;

import dev.cowboy.chunkoptimizer.CowboyChunkOptimizerClient;
import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;
import dev.cowboy.chunkoptimizer.event.ChunkNeighborPreloader;
import dev.cowboy.chunkoptimizer.graph.SectionPaletteHasher;
import dev.cowboy.chunkoptimizer.graph.SectionVisibilityGraph;
import dev.cowboy.chunkoptimizer.render.ChunkFadeSystem;
import net.minecraft.client.world.ClientChunkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ClientChunkManager hooks:
 *
 *  1. loadChunkFromPacket() — fires when server sends new chunk data.
 *     → Triggers neighbor preloader (invalidate border section caches)
 *     → Triggers SectionVisibilityGraph invalidation for this column
 *     → Registers fade for all sections in the new chunk
 *
 *  2. unload() — fires when a chunk is unloaded (too far away).
 *     → Clears fade entries for all sections in the chunk
 *     → Invalidates graph/hash caches for the column
 *     → Triggers neighbor preloader for border sections
 *
 * The section Y range for 1.21.1 default world height:
 *   Bottom section: -4 (y = -64)
 *   Top section:    19 (y = 320)
 *   Total: 24 sections per chunk column
 */
@Mixin(ClientChunkManager.class)
public abstract class MixinClientChunkManager {

    private static volatile boolean cco_firstChunkSeen = false;

    @Inject(method = "loadChunkFromPacket", at = @At("RETURN"))
    private void cco_onChunkLoad(int x, int z, CallbackInfo ci) {
        // On first chunk received after connect, treat as world load
        if (!cco_firstChunkSeen) {
            cco_firstChunkSeen = true;
            CowboyChunkOptimizerClient.onWorldLoad();
        }

        // Invalidate section caches for this chunk column
        SectionVisibilityGraph.invalidateChunk(x, z);
        SectionPaletteHasher.invalidateChunk(x, z);

        // Pre-warm neighbor border sections
        ChunkNeighborPreloader.onChunkArriving(x, z);

        // Register all sections for fade-in
        if (ChunkOptimizerConfig.get().enableFade) {
            for (int sy = -4; sy <= 19; sy++) {
                ChunkFadeSystem.onSectionReady(x, sy, z);
            }
        }
    }

    @Inject(method = "unload", at = @At("HEAD"))
    private void cco_onChunkUnload(int x, int z, CallbackInfo ci) {
        // Clear fade entries
        ChunkFadeSystem.onChunkUnloaded(x, z);

        // Clear graph and hash caches
        SectionVisibilityGraph.invalidateChunk(x, z);
        SectionPaletteHasher.invalidateChunk(x, z);

        // Notify neighbor preloader (neighbors now have exposed faces)
        ChunkNeighborPreloader.onChunkUnloading(x, z);
    }

    // Reset on world disconnect
    @Inject(method = "close", at = @At("HEAD"))
    private void cco_onClose(CallbackInfo ci) {
        cco_firstChunkSeen = false;
    }
}
