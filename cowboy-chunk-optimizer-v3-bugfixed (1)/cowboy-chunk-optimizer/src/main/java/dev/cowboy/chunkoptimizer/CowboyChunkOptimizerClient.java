package dev.cowboy.chunkoptimizer;

import dev.cowboy.chunkoptimizer.async.AsyncChunkDataCopier;
import dev.cowboy.chunkoptimizer.compat.SodiumCompatLayer;
import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;
import dev.cowboy.chunkoptimizer.config.DimensionProfileManager;
import dev.cowboy.chunkoptimizer.event.ChunkNeighborPreloader;
import dev.cowboy.chunkoptimizer.event.TeleportBurstHandler;
import dev.cowboy.chunkoptimizer.graph.SectionPaletteHasher;
import dev.cowboy.chunkoptimizer.graph.SectionVisibilityGraph;
import dev.cowboy.chunkoptimizer.hud.ChunkOptimizerHud;
import dev.cowboy.chunkoptimizer.memory.MeshPool;
import dev.cowboy.chunkoptimizer.profiler.PerformanceProfiler;
import dev.cowboy.chunkoptimizer.render.BlockEntityCuller;
import dev.cowboy.chunkoptimizer.render.ChunkFadeSystem;
import dev.cowboy.chunkoptimizer.render.ChunkMeshCompressor;
import dev.cowboy.chunkoptimizer.render.ChunkSectionFrustumCache;
import dev.cowboy.chunkoptimizer.render.RenderSectionCuller;
import dev.cowboy.chunkoptimizer.thread.DynamicThreadScaler;
import dev.cowboy.chunkoptimizer.throttle.FpsAutoTuner;
import dev.cowboy.chunkoptimizer.throttle.LightUpdateThrottle;
import dev.cowboy.chunkoptimizer.throttle.TranslucentSortThrottle;
import dev.cowboy.chunkoptimizer.util.AdaptiveBudgetController;
import dev.cowboy.chunkoptimizer.util.MainThreadStallGuard;
import dev.cowboy.chunkoptimizer.util.StartupBenchmark;
import dev.cowboy.chunkoptimizer.util.VelocityTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CowboyChunkOptimizerClient implements ClientModInitializer {

    public static final String MOD_ID  = "cowboy-chunk-optimizer";
    public static final Logger LOGGER  = LoggerFactory.getLogger("cowboy-chunk-optimizer");
    public static final String VERSION = "3.0.0";

    private static KeyBinding hudToggleKey;
    public  static volatile int  lastQueueDepth = 0;
    private static long tickIndex = 0;

    @Override
    public void onInitializeClient() {
        LOGGER.info("╔══════════════════════════════════════════╗");
        LOGGER.info("║  Cowboy's Chunk Optimizer v{}           ║", VERSION);
        LOGGER.info("╚══════════════════════════════════════════╝");

        ChunkOptimizerConfig.load();
        SodiumCompatLayer.logStatus();
        AsyncChunkDataCopier.init();

        hudToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.cowboy-chunk-optimizer.toggleHud",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            "category.cowboy.mods"
        ));

        HudRenderCallback.EVENT.register(new ChunkOptimizerHud());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            tickIndex++;

            double px = client.player.getX();
            double py = client.player.getY();
            double pz = client.player.getZ();

            // Core scheduling systems
            VelocityTracker.tick(px, py, pz);
            LightUpdateThrottle.tick();
            TeleportBurstHandler.tick(px, py, pz, lastQueueDepth);
            ChunkNeighborPreloader.drainPending();
            DynamicThreadScaler.tick(lastQueueDepth);
            MeshPool.tick();
            AdaptiveBudgetController.compute(lastQueueDepth);

            // FPS & render systems
            FpsAutoTuner.tick(tickIndex);
            RenderSectionCuller.onTick();

            // Periodic cleanup — staggered to spread tick cost
            if (tickIndex % 10 == 0)  ChunkFadeSystem.cleanup();
            if (tickIndex % 60 == 0)  BlockEntityCuller.resetStats();  // reset per-minute
            if (tickIndex % 200 == 0) LightUpdateThrottle.getCacheSize(); // passive cleanup

            // HUD toggle
            if (hudToggleKey.wasPressed()) {
                ChunkOptimizerConfig.get().showHud = !ChunkOptimizerConfig.get().showHud;
                ChunkOptimizerConfig.save();
                LOGGER.info("[CCO] HUD {}", ChunkOptimizerConfig.get().showHud ? "enabled" : "disabled");
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            LOGGER.info("[CCO] Shutting down — restoring settings, clearing caches.");
            FpsAutoTuner.restore();
            DimensionProfileManager.reset();
            AsyncChunkDataCopier.shutdown();
            ChunkFadeSystem.clear();
            SectionVisibilityGraph.clear();
            SectionPaletteHasher.clear();
            MeshPool.clear();
            LightUpdateThrottle.clear();
            TranslucentSortThrottle.clear();
            RenderSectionCuller.clear();
            ChunkMeshCompressor.clear();
            MainThreadStallGuard.reset();
            PerformanceProfiler.reset();
        });

        LOGGER.info("[CCO] v{} ready — {} optimisation systems active.", VERSION, countActiveSystems());
    }

    /** Called from MixinWorldRenderer on F3+A, /reload, dimension change. */
    public static void onWorldRendererReload() {
        TeleportBurstHandler.onWorldRendererReload();
        SectionVisibilityGraph.clear();
        SectionPaletteHasher.clear();
        LightUpdateThrottle.clear();
        AsyncChunkDataCopier.clear();
        RenderSectionCuller.clear();
        TranslucentSortThrottle.clear();
        LOGGER.info("[CCO] WorldRenderer reloaded — caches cleared, burst activated.");
    }

    /** Called from MixinClientChunkManager on new world connection. */
    public static void onWorldLoad() {
        TeleportBurstHandler.onWorldLoad();
        SectionVisibilityGraph.clear();
        SectionPaletteHasher.clear();
        MeshPool.clear();
        LightUpdateThrottle.clear();
        AsyncChunkDataCopier.clear();
        RenderSectionCuller.clear();
        TranslucentSortThrottle.clear();
        ChunkMeshCompressor.clear();
        MainThreadStallGuard.reset();
        FpsAutoTuner.reset();
        StartupBenchmark.reset();
        LOGGER.info("[CCO] World loaded — all caches reset.");
    }

    private static int countActiveSystems() {
        ChunkOptimizerConfig c = ChunkOptimizerConfig.get();
        int n = 0;
        if (c.adaptiveBudget)               n++;
        if (c.nearestFirstScheduling)        n++;
        if (c.frustumPriorityScheduling)     n++;
        if (c.predictiveScheduling)          n++;
        if (c.earlyOcclusionCull)            n++;
        if (c.paletteHashDedup)              n++;
        if (c.neighborPreloading)            n++;
        if (c.dynamicThreadScaling)          n++;
        if (c.enableMeshPool)                n++;
        if (c.enableFade)                    n++;
        if (c.burstMode)                     n++;
        if (c.lightUpdateThrottle)           n++;
        if (c.enableLOD)                     n++;
        if (c.fpsAutoTune)                   n++;
        if (c.asyncDataCopy)                 n++;
        if (c.enableRenderFeathering)        n++;
        if (c.renderBackfaceCull)            n++;
        if (c.renderLodSkip)                 n++;
        if (c.renderStaticSkip)              n++;
        if (c.translucentSortThrottle)       n++;
        if (c.blockEntityCulling)            n++;
        if (c.dimensionProfiles)             n++;
        if (c.runStartupBenchmark)           n++;
        return n;
    }
}
