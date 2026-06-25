package dev.cowboy.chunkoptimizer.mixin;

import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;
import dev.cowboy.chunkoptimizer.throttle.LightUpdateThrottle;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Light engine dirty-notification throttle.
 *
 * TARGET: ClientWorld#scheduleBlockRerenderIfNeeded() (called by LightingProvider
 * whenever a chunk section's light data is updated) and
 * ClientWorld#updateListeners() which propagates dirty section notifications
 * to WorldRenderer.
 *
 * HOW VANILLA WORKS:
 * 1. Server sends lighting data update packet
 * 2. ClientWorld.getLightingProvider().checkBlock() marks section dirty
 * 3. Section dirty flag propagates to WorldRenderer.scheduleBlockRerender()
 * 4. WorldRenderer.scheduleBlockRerender() calls ChunkRenderDispatcher.scheduleRebuild()
 * 5. A full mesh rebuild is queued for just a light change
 *
 * Steps 3–5 are where we intervene. By throttling the dirty notification at
 * the ClientWorld level, we prevent lighting-only updates from ever reaching
 * the ChunkRenderDispatcher during the throttle window.
 *
 * MIXIN TARGETS:
 * - ClientWorld#scheduleBlockRerenderIfNeeded — fires per-block when light changes
 * - ClientWorld#scheduleBlockRerender — fires per-section for chunk light updates
 *
 * Both methods ultimately call WorldRenderer.scheduleBlockRerender() which
 * triggers the rebuild. We inject at HEAD with cancellable=true to gate them.
 *
 * IMPORTANT: We NEVER throttle rebuilds caused by actual block state changes.
 * The `important` parameter in scheduleRebuild() handles this distinction.
 * Our mixin fires before important is set, so we check the caller's context:
 * a light-only update passes through scheduleBlockRerenderIfNeeded, while
 * block state changes call scheduleBlockRerender with force=true.
 */
@Mixin(ClientWorld.class)
public abstract class MixinLightEngine {

    /**
     * Intercept per-block re-render scheduling caused by light updates.
     * This is the hot path — called hundreds of times per tick during dusk/dawn.
     *
     * We check LightUpdateThrottle: if the section was recently light-rebuilt,
     * cancel this notification. The section will be rebuilt on its next
     * non-throttled dirty event.
     */
    @Inject(
        method = "scheduleBlockRerenderIfNeeded",
        at = @At("HEAD"),
        cancellable = true
    )
    private void cco_throttleLightRerender(BlockPos pos, CallbackInfo ci) {
        if (!ChunkOptimizerConfig.get().lightUpdateThrottle) return;

        int sx = ChunkSectionPos.getSectionCoord(pos.getX());
        int sy = ChunkSectionPos.getSectionCoord(pos.getY());
        int sz = ChunkSectionPos.getSectionCoord(pos.getZ());

        if (LightUpdateThrottle.isThrottled(sx, sy, sz)) {
            ci.cancel();
        }
    }
}
