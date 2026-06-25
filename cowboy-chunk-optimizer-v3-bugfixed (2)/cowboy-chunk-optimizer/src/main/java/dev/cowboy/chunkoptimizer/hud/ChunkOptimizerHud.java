package dev.cowboy.chunkoptimizer.hud;

import dev.cowboy.chunkoptimizer.async.AsyncChunkDataCopier;
import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;
import dev.cowboy.chunkoptimizer.config.DimensionProfileManager;
import dev.cowboy.chunkoptimizer.event.TeleportBurstHandler;
import dev.cowboy.chunkoptimizer.graph.SectionPaletteHasher;
import dev.cowboy.chunkoptimizer.graph.SectionVisibilityGraph;
import dev.cowboy.chunkoptimizer.memory.MeshPool;
import dev.cowboy.chunkoptimizer.profiler.PerformanceProfiler;
import dev.cowboy.chunkoptimizer.render.BlockEntityCuller;
import dev.cowboy.chunkoptimizer.render.ChunkFadeSystem;
import dev.cowboy.chunkoptimizer.render.ChunkMeshCompressor;
import dev.cowboy.chunkoptimizer.render.RenderSectionCuller;
import dev.cowboy.chunkoptimizer.scheduler.SectionPriorityScorer;
import dev.cowboy.chunkoptimizer.thread.DynamicThreadScaler;
import dev.cowboy.chunkoptimizer.throttle.FpsAutoTuner;
import dev.cowboy.chunkoptimizer.throttle.LightUpdateThrottle;
import dev.cowboy.chunkoptimizer.throttle.TranslucentSortThrottle;
import dev.cowboy.chunkoptimizer.util.AdaptiveBudgetController;
import dev.cowboy.chunkoptimizer.util.MainThreadStallGuard;
import dev.cowboy.chunkoptimizer.util.StartupBenchmark;
import dev.cowboy.chunkoptimizer.util.VelocityTracker;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/**
 * Cowboy's Chunk Optimizer — Live Stats HUD (v3 final)
 *
 * Press H to toggle. Hidden when F3 is open, HUD is off, or a screen is open.
 *
 * Rows:
 *  1  Header: version, dimension, burst state, FPS auto-tuner state
 *  2  FPS rolling average + budget bar + sparkline
 *  3  Light throttle: skipped count + skip rate (the big win indicator)
 *  4  Translucent sort: skipped sorts + skip rate
 *  5  Section culling: graph + hash + sched totals
 *  6  Render-time culling: back-face + LOD + static skipped
 *  7  Block entity culling: culled/tested this minute
 *  8  LOD status + feathering + Y-cull
 *  9  Workers + queue depth + scale events
 * 10  Mesh pool + async copy
 * 11  Velocity + predicted position
 * 12  VRAM compression savings (stats mode)
 * 13  Per-system timings (optional, hudShowTimings)
 * 14  Benchmark progress (if running)
 */
public final class ChunkOptimizerHud implements HudRenderCallback {

    private static final int BG     = 0xB0000000;
    private static final int GOLD   = 0xFFFFD700;
    private static final int GREY   = 0xFFAAAAAA;
    private static final int WHITE  = 0xFFFFFFFF;
    private static final int RED    = 0xFFFF5555;
    private static final int ORANGE = 0xFFFF9900;
    private static final int GREEN  = 0xFF55FF55;
    private static final int CYAN   = 0xFF55FFFF;
    private static final int PURPLE = 0xFFCC88FF;

    private final FrameTimeRingBuffer budgetSparkline = new FrameTimeRingBuffer(80);
    private final FrameTimeRingBuffer fpsSparkline    = new FrameTimeRingBuffer(80);

    @Override
    public void onHudRender(DrawContext ctx, RenderTickCounter tick) {
        ChunkOptimizerConfig cfg = ChunkOptimizerConfig.get();
        if (!cfg.showHud) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getDebugHud().shouldShowDebugHud()) return;
        if (mc.options.hudHidden) return;
        if (mc.currentScreen != null) return;

        TextRenderer f = mc.textRenderer;
        int lh = f.fontHeight + 2;
        int x = 4, y = 4;

        // Collect live values
        float budgetMs   = AdaptiveBudgetController.getCurrentBudgetMs();
        float fpsRolling = FpsAutoTuner.getRollingFps();
        var   burst      = TeleportBurstHandler.getState();
        boolean isBurst  = burst == TeleportBurstHandler.State.BURST;
        boolean isCool   = burst == TeleportBurstHandler.State.COOLING;
        int  workers     = DynamicThreadScaler.getCurrentWorkers();
        long throttled   = LightUpdateThrottle.getThrottledCount();
        long hashSkipped = SectionPaletteHasher.getSkippedBuilds();
        long graphCulled = SectionVisibilityGraph.getCulledTotal();
        int  fading      = ChunkFadeSystem.getFadingCount();
        int  stalls      = (int) MainThreadStallGuard.getStallEvents();
        int  penalty     = MainThreadStallGuard.getPenaltyFrames();
        double speed     = VelocityTracker.getSmoothedSpeed();
        float sortSkip   = TranslucentSortThrottle.getSkipRate();
        long beCulled    = BlockEntityCuller.getTotalCulled();
        long beTested    = BlockEntityCuller.getTotalTested();

        budgetSparkline.push(budgetMs);
        fpsSparkline.push(fpsRolling);

        int W = 260;
        boolean showBenchmark = !StartupBenchmark.isDone() && cfg.runStartupBenchmark;
        int extraLines = (cfg.hudShowTimings ? 1 : 0) + (showBenchmark ? 1 : 0);
        int lines = 13 + extraLines;
        ctx.fill(x - 2, y - 2, x + W + 2, y + lines * lh + 2, BG);

        // ── Row 1: Header ─────────────────────────────────────────────────
        String dim      = DimensionProfileManager.getCurrentDimensionShort();
        String dimTag   = dim != null ? " §8[" + dim + (DimensionProfileManager.isOverrideActive() ? "*" : "") + "]" : "";
        String burstTag = isBurst ? String.format("  §c[BURST %.1fs]", TeleportBurstHandler.getBurstTicksRemaining() / 20f)
                        : isCool  ? "  §e[COOLING]" : "";
        String rdTag    = FpsAutoTuner.isActive()
            ? (FpsAutoTuner.isReduced()
               ? String.format("  §c[RD:%d↓]", FpsAutoTuner.getCurrentRenderDist())
               : String.format("  §a[RD:%d✓]", FpsAutoTuner.getCurrentRenderDist()))
            : "";
        ctx.drawText(f, "§6★ CCO v" + dev.cowboy.chunkoptimizer.CowboyChunkOptimizerClient.VERSION
            + dimTag + burstTag + rdTag, x, y, WHITE, false);
        y += lh;

        // ── Row 2: FPS + budget ───────────────────────────────────────────
        int fpsColor = fpsRolling < cfg.fpsAutoTuneTarget * 0.85f ? RED
                     : fpsRolling < cfg.fpsAutoTuneTarget ? ORANGE : GREEN;
        ctx.drawText(f, String.format("§7FPS §f%.0f  §7Budget §f%.1f/%.0fms",
            fpsRolling, budgetMs, (float)cfg.adaptiveBudgetMax), x, y, fpsColor, false);
        drawSparkline(ctx, x + 165, y, 90, lh - 1, fpsSparkline, fpsColor);
        y += lh;

        // ── Row 3: Light throttle ─────────────────────────────────────────
        long ltAllowed = LightUpdateThrottle.getAllowedCount();
        long ltTotal   = throttled + ltAllowed;
        int  ltPct     = ltTotal > 0 ? (int)(throttled * 100 / ltTotal) : 0;
        ctx.drawText(f, String.format("§7Light §f%d §7throttled §8(§f%d%%§8 skipped)  §7cache:%d",
            throttled, ltPct, LightUpdateThrottle.getCacheSize()), x, y,
            ltPct > 50 ? GREEN : ltPct > 20 ? CYAN : WHITE, false);
        y += lh;

        // ── Row 4: Translucent sort throttle ─────────────────────────────
        long sortSkipped  = TranslucentSortThrottle.getSkippedSorts();
        long sortExecuted = TranslucentSortThrottle.getExecutedSorts();
        int  sortPct      = (int)(sortSkip * 100);
        ctx.drawText(f, String.format("§7TSort §f%d §7skipped §8(§f%d%%§8)  §7exec:%d  §7cache:%d",
            sortSkipped, sortPct, sortExecuted, TranslucentSortThrottle.getCacheSize()),
            x, y, sortPct > 70 ? GREEN : WHITE, false);
        y += lh;

        // ── Row 5: Sched culling ──────────────────────────────────────────
        ctx.drawText(f, String.format("§7SchedCull §fgraph:%d  hash:%d  §7eliminated",
            graphCulled, hashSkipped), x, y, WHITE, false);
        y += lh;

        // ── Row 6: Render-time culling ────────────────────────────────────
        long renderSkip = RenderSectionCuller.getTotalSkipped();
        ctx.drawText(f, String.format("§7RenderCull §f%d  §7(back:%d  lod:%d  static:%d)",
            renderSkip,
            RenderSectionCuller.getBackfaceCulled(),
            RenderSectionCuller.getLodSkipped(),
            RenderSectionCuller.getStaticSkipped()), x, y, WHITE, false);
        y += lh;

        // ── Row 7: Block entity culling ───────────────────────────────────
        int bePct = beTested > 0 ? (int)(beCulled * 100 / beTested) : 0;
        ctx.drawText(f, String.format("§7BlockEnt §f%d §7culled §8(§f%d%%§8 of %d tested)  §7dist:%d+back+y",
            beCulled, bePct, beTested, BlockEntityCuller.getCulledDistance()),
            x, y, bePct > 30 ? GREEN : WHITE, false);
        y += lh;

        // ── Row 8: LOD + feathering ───────────────────────────────────────
        String lodStr = cfg.enableLOD
            ? String.format("§7LOD §aON  §7trans>%dc  cut>%dc", cfg.lodNearDistance, cfg.lodCutoutDistance)
            : "§7LOD §cOFF";
        String featherStr = cfg.enableRenderFeathering ? "  §7feather:§aON" : "";
        String yCullStr   = cfg.enableYCull ? String.format("  §7ycull:%db", cfg.yCullDistanceBlocks) : "";
        ctx.drawText(f, lodStr + featherStr + yCullStr, x, y, WHITE, false);
        y += lh;

        // ── Row 9: Workers + queue ────────────────────────────────────────
        ctx.drawText(f, String.format("§7Workers §f%d  §7queue:§f%d  §7↑%d↓%d  §7stalls:§f%d%s",
            workers,
            dev.cowboy.chunkoptimizer.CowboyChunkOptimizerClient.lastQueueDepth,
            DynamicThreadScaler.getScaleUpEvents(),
            DynamicThreadScaler.getScaleDownEvents(),
            stalls,
            penalty > 0 ? " §cPEN:" + penalty + "f§r" : ""),
            x, y, stalls > 0 ? ORANGE : WHITE, false);
        y += lh;

        // ── Row 10: Mesh pool + async copy ────────────────────────────────
        ctx.drawText(f, String.format("§7Pool §f%d/%d  §7recycled:%d  §7pre-copy:%d  §7fade:%d",
            MeshPool.getPoolSize(), cfg.meshPoolMaxSize,
            MeshPool.getTotalRecycled(),
            AsyncChunkDataCopier.getStagedCount(),
            fading), x, y, WHITE, false);
        y += lh;

        // ── Row 11: Velocity + prediction ────────────────────────────────
        String spdColor = speed > 15 ? "§c" : speed > 5 ? "§e" : "§a";
        ctx.drawText(f, String.format("§7Speed %s%.1f§r§7b/s  pred=(%.0f,%.0f,%.0f)",
            spdColor, speed,
            SectionPriorityScorer.getPredX(),
            SectionPriorityScorer.getPredY(),
            SectionPriorityScorer.getPredZ()), x, y, WHITE, false);
        y += lh;

        // ── Row 12: Budget sparkline + VRAM savings ───────────────────────
        ctx.drawText(f, "§7Budget:", x, y, GREY, false);
        drawSparkline(ctx, x + 55, y, 110, lh - 1, budgetSparkline, 0xFF00CCFF);
        if (cfg.meshCompressionStats && ChunkMeshCompressor.getSectionsAnalysed() > 0) {
            ctx.drawText(f, "§7VRAM save:§a " + ChunkMeshCompressor.getSavingsFormatted(),
                x + 175, y, WHITE, false);
        }
        y += lh;

        // ── Row 13 (optional): Per-system timings ─────────────────────────
        if (cfg.hudShowTimings) {
            float uploadMs = PerformanceProfiler.getAvgMs(PerformanceProfiler.Slot.UPLOAD_PHASE);
            float cullMs   = PerformanceProfiler.getAvgMs(PerformanceProfiler.Slot.CULL_CHECK);
            float fadeMs   = PerformanceProfiler.getAvgMs(PerformanceProfiler.Slot.FADE_UPDATE);
            ctx.drawText(f, String.format("§7Times §fupload:%.2f  cull:%.3f  fade:%.3fms",
                uploadMs, cullMs, fadeMs), x, y, GREY, false);
            y += lh;
        }

        // ── Row 14 (conditional): Benchmark progress ──────────────────────
        if (showBenchmark) {
            String phase = switch (StartupBenchmark.getPhase()) {
                case RUNNING_GPU -> String.format("§eGPU phase %d%%", StartupBenchmark.getGpuProgress());
                case RUNNING_CPU -> "§eCPU phase...";
                default -> "§7pending";
            };
            ctx.drawText(f, "§7Benchmark: " + phase + "  §7(auto-configuring)", x, y, PURPLE, false);
        }
    }

    private static void drawSparkline(DrawContext ctx, int x, int y, int w, int h,
                                       FrameTimeRingBuffer buf, int color) {
        if (buf.size() == 0) return;
        float maxVal = Math.max(0.001f, buf.max() * 1.1f);
        int n = Math.min(buf.size(), w);
        for (int i = 0; i < n; i++) {
            float val  = buf.get(buf.size() - n + i);
            int   barH = Math.max(1, (int)(val / maxVal * h));
            int   bx   = x + (int)((float) i / n * w);
            int   bw   = Math.max(1, w / n);
            ctx.fill(bx, y + h - barH, bx + bw, y + h, color);
        }
    }
}
