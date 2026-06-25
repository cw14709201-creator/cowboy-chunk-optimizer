package dev.cowboy.chunkoptimizer.screen;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            try { return buildClothScreen(parent); }
            catch (Exception | NoClassDefFoundError e) { return new NoClothConfigScreen(parent); }
        };
    }

    private Screen buildClothScreen(Screen parent) {
        var builder = me.shedaniel.clothconfig.api.ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Text.literal("Cowboy's Chunk Optimizer v3"))
            .setSavingRunnable(ChunkOptimizerConfig::save);

        var cfg = ChunkOptimizerConfig.get();
        var eb  = builder.entryBuilder();

        // ── Upload Budget ──────────────────────────────────────────────────
        var budgetCat = builder.getOrCreateCategory(Text.literal("Upload Budget"));
        budgetCat.addEntry(eb.startIntSlider(Text.literal("Base Budget (ms)"), cfg.uploadBudgetMs, 1, 25)
            .setDefaultValue(5).setTooltip(Text.literal("Max ms/tick for GPU uploads. Lower = smoother FPS."))
            .setSaveConsumer(v -> cfg.uploadBudgetMs = v).build());
        budgetCat.addEntry(eb.startBooleanToggle(Text.literal("Adaptive Budget"), cfg.adaptiveBudget)
            .setDefaultValue(true).setTooltip(Text.literal("Auto-scale budget based on player speed."))
            .setSaveConsumer(v -> cfg.adaptiveBudget = v).build());
        budgetCat.addEntry(eb.startIntSlider(Text.literal("Adaptive Min (ms)"), cfg.adaptiveBudgetMin, 1, 10)
            .setDefaultValue(2).setSaveConsumer(v -> cfg.adaptiveBudgetMin = v).build());
        budgetCat.addEntry(eb.startIntSlider(Text.literal("Adaptive Max (ms)"), cfg.adaptiveBudgetMax, 5, 30)
            .setDefaultValue(14).setSaveConsumer(v -> cfg.adaptiveBudgetMax = v).build());
        budgetCat.addEntry(eb.startBooleanToggle(Text.literal("Burst Mode"), cfg.burstMode)
            .setDefaultValue(true).setTooltip(Text.literal("Expand budget after teleport/world join."))
            .setSaveConsumer(v -> cfg.burstMode = v).build());
        budgetCat.addEntry(eb.startFloatField(Text.literal("Burst Multiplier"), cfg.burstBudgetMultiplier)
            .setDefaultValue(3.0f).setSaveConsumer(v -> cfg.burstBudgetMultiplier = v).build());

        // ── Translucent Sort ───────────────────────────────────────────────
        var sortCat = builder.getOrCreateCategory(Text.literal("Translucent Sort"));
        sortCat.addEntry(eb.startBooleanToggle(Text.literal("Sort Throttle"), cfg.translucentSortThrottle)
            .setDefaultValue(true).setTooltip(Text.literal(
                "Skip re-sorting translucent geometry (water/glass) when camera hasn't moved enough. "
                + "Huge win in ocean/nether worlds — eliminates 80-90% of per-frame sorts."))
            .setSaveConsumer(v -> cfg.translucentSortThrottle = v).build());
        sortCat.addEntry(eb.startFloatField(Text.literal("Sort Threshold (blocks)"), cfg.translucentSortThresholdBlocks)
            .setDefaultValue(0.5f).setTooltip(Text.literal(
                "Camera must move this many blocks for a re-sort to fire. 0.5 = imperceptible at normal play."))
            .setSaveConsumer(v -> cfg.translucentSortThresholdBlocks = v).build());

        // ── Block Entity Culling ───────────────────────────────────────────
        var beCullCat = builder.getOrCreateCategory(Text.literal("Block Entity Culling"));
        beCullCat.addEntry(eb.startBooleanToggle(Text.literal("Block Entity Culling"), cfg.blockEntityCulling)
            .setDefaultValue(true).setTooltip(Text.literal(
                "Skip render calls for block entities that are too far, behind the player, or above/below. "
                + "Saves overhead in decorated bases and mob farms."))
            .setSaveConsumer(v -> cfg.blockEntityCulling = v).build());
        beCullCat.addEntry(eb.startIntSlider(Text.literal("Max Render Distance (blocks)"),
                cfg.blockEntityRenderDistance, 16, 256).setDefaultValue(64)
            .setSaveConsumer(v -> cfg.blockEntityRenderDistance = v).build());
        beCullCat.addEntry(eb.startBooleanToggle(Text.literal("Y-Extent Cull"), cfg.blockEntityYCull)
            .setDefaultValue(true).setSaveConsumer(v -> cfg.blockEntityYCull = v).build());
        beCullCat.addEntry(eb.startIntSlider(Text.literal("Y Cull Distance (blocks)"),
                cfg.blockEntityYCullBlocks, 16, 192).setDefaultValue(48)
            .setSaveConsumer(v -> cfg.blockEntityYCullBlocks = v).build());
        beCullCat.addEntry(eb.startBooleanToggle(Text.literal("Back-Face Cull"), cfg.blockEntityBackfaceCull)
            .setDefaultValue(true).setTooltip(Text.literal("Skip block entities clearly behind the player."))
            .setSaveConsumer(v -> cfg.blockEntityBackfaceCull = v).build());

        // ── Section Culling ────────────────────────────────────────────────
        var cullCat = builder.getOrCreateCategory(Text.literal("Section Culling"));
        cullCat.addEntry(eb.startBooleanToggle(Text.literal("Skip Air Sections"), cfg.skipAirSections)
            .setDefaultValue(true).setSaveConsumer(v -> cfg.skipAirSections = v).build());
        cullCat.addEntry(eb.startBooleanToggle(Text.literal("Early Occlusion Cull"), cfg.earlyOcclusionCull)
            .setDefaultValue(true).setTooltip(Text.literal("Skip fully-enclosed sections with no visible faces."))
            .setSaveConsumer(v -> cfg.earlyOcclusionCull = v).build());
        cullCat.addEntry(eb.startBooleanToggle(Text.literal("Palette Hash Dedup"), cfg.paletteHashDedup)
            .setDefaultValue(true).setTooltip(Text.literal("Skip rebuilds when block data is unchanged."))
            .setSaveConsumer(v -> cfg.paletteHashDedup = v).build());
        cullCat.addEntry(eb.startBooleanToggle(Text.literal("Light Update Throttle"), cfg.lightUpdateThrottle)
            .setDefaultValue(true).setTooltip(Text.literal(
                "Rate-limit rebuilds from light updates. Biggest single scheduling optimisation."))
            .setSaveConsumer(v -> cfg.lightUpdateThrottle = v).build());
        cullCat.addEntry(eb.startIntSlider(Text.literal("Light Throttle Ticks"), cfg.lightThrottleTicks, 1, 20)
            .setDefaultValue(4).setSaveConsumer(v -> cfg.lightThrottleTicks = v).build());

        // ── Render-Time Culling ────────────────────────────────────────────
        var renderCullCat = builder.getOrCreateCategory(Text.literal("Render-Time Culling"));
        renderCullCat.addEntry(eb.startBooleanToggle(Text.literal("Back-Face Direction Cull"), cfg.renderBackfaceCull)
            .setDefaultValue(true).setTooltip(Text.literal("Skip draw calls for sections >150° behind camera."))
            .setSaveConsumer(v -> cfg.renderBackfaceCull = v).build());
        renderCullCat.addEntry(eb.startBooleanToggle(Text.literal("Outer-Ring LOD Skip"), cfg.renderLodSkip)
            .setDefaultValue(true).setTooltip(Text.literal("Render outermost chunk ring every other frame."))
            .setSaveConsumer(v -> cfg.renderLodSkip = v).build());
        renderCullCat.addEntry(eb.startIntSlider(Text.literal("LOD Ring Width (chunks)"),
                cfg.renderLodSkipMarginChunks, 1, 4).setDefaultValue(2)
            .setSaveConsumer(v -> cfg.renderLodSkipMarginChunks = v).build());
        renderCullCat.addEntry(eb.startBooleanToggle(Text.literal("Static Section Skip"), cfg.renderStaticSkip)
            .setDefaultValue(true).setTooltip(Text.literal("Draw untouched sections every N frames instead of every frame."))
            .setSaveConsumer(v -> cfg.renderStaticSkip = v).build());
        renderCullCat.addEntry(eb.startIntSlider(Text.literal("Static Skip Age (ticks)"),
                cfg.renderStaticSkipAgeThresholdTicks, 20, 300).setDefaultValue(100)
            .setSaveConsumer(v -> cfg.renderStaticSkipAgeThresholdTicks = v).build());
        renderCullCat.addEntry(eb.startIntSlider(Text.literal("Static Skip Interval (frames)"),
                cfg.renderStaticSkipInterval, 2, 6).setDefaultValue(3)
            .setSaveConsumer(v -> cfg.renderStaticSkipInterval = v).build());
        renderCullCat.addEntry(eb.startBooleanToggle(Text.literal("Y-Axis Cull"), cfg.enableYCull)
            .setDefaultValue(true).setSaveConsumer(v -> cfg.enableYCull = v).build());
        renderCullCat.addEntry(eb.startIntSlider(Text.literal("Y Cull Distance (blocks)"),
                cfg.yCullDistanceBlocks, 32, 320).setDefaultValue(160)
            .setSaveConsumer(v -> cfg.yCullDistanceBlocks = v).build());

        // ── Layer LOD ──────────────────────────────────────────────────────
        var lodCat = builder.getOrCreateCategory(Text.literal("Layer LOD"));
        lodCat.addEntry(eb.startBooleanToggle(Text.literal("Enable Layer LOD"), cfg.enableLOD)
            .setDefaultValue(true).setTooltip(Text.literal(
                "Skip CUTOUT and TRANSLUCENT layers for distant sections. "
                + "Reduces draw calls by ~35-45% for the far tier."))
            .setSaveConsumer(v -> cfg.enableLOD = v).build());
        lodCat.addEntry(eb.startIntSlider(Text.literal("LOD Start Distance (chunks)"),
                cfg.lodNearDistance, 4, 20).setDefaultValue(8)
            .setTooltip(Text.literal("Sections beyond this skip translucent (water/glass)."))
            .setSaveConsumer(v -> cfg.lodNearDistance = v).build());
        lodCat.addEntry(eb.startIntSlider(Text.literal("Cutout LOD Distance (chunks)"),
                cfg.lodCutoutDistance, 4, 24).setDefaultValue(12)
            .setTooltip(Text.literal("Sections beyond this also skip cutout (leaves/fences)."))
            .setSaveConsumer(v -> cfg.lodCutoutDistance = v).build());
        lodCat.addEntry(eb.startBooleanToggle(Text.literal("Render Feathering"), cfg.enableRenderFeathering)
            .setDefaultValue(true).setTooltip(Text.literal("Render outer-ring sections every 2nd frame."))
            .setSaveConsumer(v -> cfg.enableRenderFeathering = v).build());

        // ── Scheduler ─────────────────────────────────────────────────────
        var schedulerCat = builder.getOrCreateCategory(Text.literal("Scheduler"));
        schedulerCat.addEntry(eb.startBooleanToggle(Text.literal("Nearest-First"), cfg.nearestFirstScheduling)
            .setDefaultValue(true).setSaveConsumer(v -> cfg.nearestFirstScheduling = v).build());
        schedulerCat.addEntry(eb.startBooleanToggle(Text.literal("Frustum Priority"), cfg.frustumPriorityScheduling)
            .setDefaultValue(true).setSaveConsumer(v -> cfg.frustumPriorityScheduling = v).build());
        schedulerCat.addEntry(eb.startBooleanToggle(Text.literal("Predictive Scheduling"), cfg.predictiveScheduling)
            .setDefaultValue(true).setSaveConsumer(v -> cfg.predictiveScheduling = v).build());
        schedulerCat.addEntry(eb.startBooleanToggle(Text.literal("Dynamic Thread Scaling"), cfg.dynamicThreadScaling)
            .setDefaultValue(true).setSaveConsumer(v -> cfg.dynamicThreadScaling = v).build());
        schedulerCat.addEntry(eb.startIntField(Text.literal("Worker Threads (0=auto)"), cfg.workerThreads)
            .setDefaultValue(0).setSaveConsumer(v -> cfg.workerThreads = v).build());

        // ── FPS Auto-Tuner ─────────────────────────────────────────────────
        var autoTuneCat = builder.getOrCreateCategory(Text.literal("FPS Auto-Tuner"));
        autoTuneCat.addEntry(eb.startBooleanToggle(Text.literal("FPS Auto-Tune"), cfg.fpsAutoTune)
            .setDefaultValue(false).setTooltip(Text.literal(
                "Dynamically reduce render distance to maintain target FPS. "
                + "The single most powerful way to maintain smooth gameplay."))
            .setSaveConsumer(v -> cfg.fpsAutoTune = v).build());
        autoTuneCat.addEntry(eb.startFloatField(Text.literal("Target FPS"), cfg.fpsAutoTuneTarget)
            .setDefaultValue(60f).setSaveConsumer(v -> cfg.fpsAutoTuneTarget = v).build());
        autoTuneCat.addEntry(eb.startIntSlider(Text.literal("Min Render Distance"),
                cfg.autoTuneMinRenderDistance, 2, 12).setDefaultValue(6)
            .setSaveConsumer(v -> cfg.autoTuneMinRenderDistance = v).build());

        // ── Dimension Profiles ─────────────────────────────────────────────
        var dimCat = builder.getOrCreateCategory(Text.literal("Dimension Profiles"));
        dimCat.addEntry(eb.startBooleanToggle(Text.literal("Dimension Profiles"), cfg.dimensionProfiles)
            .setDefaultValue(true).setTooltip(Text.literal(
                "Apply per-dimension config overrides. "
                + "Nether: tighter LOD, lower sort threshold (lava). "
                + "End: wider LOD, rare sorts (no water). "
                + "Overrides restore when you return to Overworld."))
            .setSaveConsumer(v -> cfg.dimensionProfiles = v).build());

        // ── Visual ─────────────────────────────────────────────────────────
        var visualCat = builder.getOrCreateCategory(Text.literal("Visual"));
        visualCat.addEntry(eb.startBooleanToggle(Text.literal("Chunk Fade-In"), cfg.enableFade)
            .setDefaultValue(true).setSaveConsumer(v -> cfg.enableFade = v).build());
        visualCat.addEntry(eb.startIntSlider(Text.literal("Fade Duration (ms)"), cfg.fadeDurationMs, 30, 500)
            .setDefaultValue(150).setSaveConsumer(v -> cfg.fadeDurationMs = v).build());

        // ── Debug / Advanced ───────────────────────────────────────────────
        var debugCat = builder.getOrCreateCategory(Text.literal("Debug"));
        debugCat.addEntry(eb.startBooleanToggle(Text.literal("Show HUD (H key)"), cfg.showHud)
            .setDefaultValue(false).setSaveConsumer(v -> cfg.showHud = v).build());
        debugCat.addEntry(eb.startBooleanToggle(Text.literal("HUD Timings"), cfg.hudShowTimings)
            .setDefaultValue(false).setSaveConsumer(v -> cfg.hudShowTimings = v).build());
        debugCat.addEntry(eb.startBooleanToggle(Text.literal("VRAM Compression Stats"), cfg.meshCompressionStats)
            .setDefaultValue(true).setTooltip(Text.literal("Track potential VRAM savings (stats only)."))
            .setSaveConsumer(v -> cfg.meshCompressionStats = v).build());
        debugCat.addEntry(eb.startBooleanToggle(Text.literal("Startup Benchmark"), cfg.runStartupBenchmark)
            .setDefaultValue(true).setTooltip(Text.literal(
                "Run a benchmark on next world join to auto-set budget and thread count. "
                + "Disables itself after running once."))
            .setSaveConsumer(v -> cfg.runStartupBenchmark = v).build());

        return builder.build();
    }
}
