package dev.cowboy.chunkoptimizer.mixin;

import dev.cowboy.chunkoptimizer.CowboyChunkOptimizerClient;
import net.minecraft.client.option.GameOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Post-options-load sanity checks.
 *
 * Two warnings that many players have never seen explained:
 *
 *  1. simulationDistance > renderDistance:
 *     The server ticks chunks the client can't even see. Pure CPU waste
 *     on both client and server sides. The fix: lower simDistance.
 *
 *  2. Very high render distance (>20) with low RAM allocation:
 *     The JVM default heap is 256M. A 24-chunk render distance with
 *     standard mods needs ~1.5G. CCO logs an actionable warning.
 *
 * These are warnings only — we never change the user's settings.
 */
@Mixin(GameOptions.class)
public abstract class MixinGameOptions {

    @Inject(method = "load", at = @At("RETURN"))
    private void cco_postLoad(CallbackInfo ci) {
        GameOptions self = (GameOptions)(Object) this;

        int renderDist = self.getViewDistance().getValue();
        int simDist    = self.getSimulationDistance().getValue();
        long maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);

        if (simDist > renderDist) {
            CowboyChunkOptimizerClient.LOGGER.warn(
                "[CCO] ⚠ simulationDistance ({}) > renderDistance ({}). " +
                "The server is ticking {} extra chunk columns that are never visible. " +
                "Set simulationDistance = {} in Options > Video Settings for a free perf gain.",
                simDist, renderDist, (simDist - renderDist) * 4, renderDist
            );
        }

        if (renderDist > 20 && maxHeapMb < 2048) {
            CowboyChunkOptimizerClient.LOGGER.warn(
                "[CCO] ⚠ Render distance is {} chunks but your JVM max heap is only {}MB. " +
                "You may experience lag spikes from GC pauses. " +
                "Consider adding -Xmx3G to your JVM arguments.",
                renderDist, maxHeapMb
            );
        }

        if (renderDist > 16) {
            CowboyChunkOptimizerClient.LOGGER.info(
                "[CCO] Render distance: {} chunks. " +
                "CCO is active — upload budget and scheduling will compensate for the higher load.",
                renderDist
            );
        }
    }
}
