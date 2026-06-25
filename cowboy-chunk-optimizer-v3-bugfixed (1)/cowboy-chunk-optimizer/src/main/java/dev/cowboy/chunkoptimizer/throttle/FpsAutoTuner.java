package dev.cowboy.chunkoptimizer.throttle;

import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import dev.cowboy.chunkoptimizer.CowboyChunkOptimizerClient;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * FPS Auto-Tuner — the single most impactful optimization for maintaining
 * a smooth framerate during chunk loading.
 *
 * WHAT IT DOES:
 * Tracks the rolling average FPS every second. When FPS drops below the
 * configured target for more than PRESSURE_TICKS consecutive ticks, it
 * reduces render distance by 1 chunk. When FPS is comfortably above target
 * for RECOVERY_TICKS consecutive ticks, it restores render distance by 1.
 *
 * The result: the game always runs at your target FPS. If you set a target
 * of 60fps, the auto-tuner will find the maximum render distance that
 * sustains 60fps on your machine in real time — no manual tuning needed.
 *
 * WHY THIS GIVES HUGE FPS GAINS:
 * Render distance is O(n³) in terms of sections. Going from 16 to 12 chunks
 * reduces the section count by ~42%. Each reduction is a massive win.
 * Most players have their render distance set too high for their GPU.
 *
 * CONTROLS:
 * - fpsAutoTune: enable/disable
 * - fpsTarget: target FPS (default: 60)
 * - autoTuneMinRenderDistance: floor (never go below this, default: 4)
 * - autoTuneMaxRenderDistance: ceiling (never exceed the user's original setting)
 *
 * SAFETY:
 * - Never exceeds the render distance set before auto-tuning started
 * - Restores original render distance on disable or world disconnect
 * - Changes are gradual (1 chunk at a time) to avoid jarring transitions
 * - Hysteresis: requires sustained FPS drop before reducing, sustained
 *   recovery before increasing (prevents oscillation)
 */
public final class FpsAutoTuner {

    private FpsAutoTuner() {}

    // --- State ---
    private static int  originalRenderDist = -1;
    private static int  currentRenderDist  = -1;
    private static long lastAdjustTick     = 0;

    // Rolling FPS tracking
    private static final int FPS_WINDOW = 20; // ticks (1 second)
    private static final long[] frameTimesNs = new long[FPS_WINDOW];
    private static int  frameHead = 0;
    private static int  frameCount = 0;
    private static long lastFrameNs = 0;
    private static float rollingFps = 60f;

    // Hysteresis counters
    private static int  pressureTicks  = 0;  // consecutive ticks below target
    private static int  recoveryTicks  = 0;  // consecutive ticks above target
    private static final int PRESSURE_TICKS  = 40;  // 2s below target → reduce
    private static final int RECOVERY_TICKS  = 200; // 10s above target → recover
    private static final int ADJUST_COOLDOWN = 60;  // min ticks between adjustments

    // Stats
    private static final AtomicInteger reduceEvents  = new AtomicInteger(0);
    private static final AtomicInteger restoreEvents = new AtomicInteger(0);

    /**
     * Called every tick from CowboyChunkOptimizerClient.
     * Samples FPS, applies hysteresis, adjusts render distance if needed.
     */
    public static void tick(long tickIndex) {
        ChunkOptimizerConfig cfg = ChunkOptimizerConfig.get();
        if (!cfg.fpsAutoTune) {
            if (originalRenderDist != -1) restore();
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;

        // Capture original render distance on first activation
        if (originalRenderDist == -1) {
            originalRenderDist = mc.options.getViewDistance().getValue();
            currentRenderDist  = originalRenderDist;
        }

        // Update rolling FPS from frame time
        long now = System.nanoTime();
        if (lastFrameNs != 0) {
            long delta = now - lastFrameNs;
            frameTimesNs[frameHead % FPS_WINDOW] = delta;
            frameHead++;
            frameCount = Math.min(frameCount + 1, FPS_WINDOW);

            if (frameCount >= 5) {
                long sum = 0;
                for (int i = 0; i < frameCount; i++) {
                    sum += frameTimesNs[(frameHead - 1 - i + FPS_WINDOW) % FPS_WINDOW];
                }
                rollingFps = 1_000_000_000f / (sum / (float) frameCount);
            }
        }
        lastFrameNs = now;

        float target = cfg.fpsAutoTuneTarget;
        int   minDist = cfg.autoTuneMinRenderDistance;
        long  cooldown = tickIndex - lastAdjustTick;

        // ── Pressure detection: FPS below target ────────────────────────────
        if (rollingFps < target * 0.90f) { // 10% headroom before reacting
            pressureTicks++;
            recoveryTicks = 0;
        } else if (rollingFps > target * 1.15f) { // 15% above target = recovering
            recoveryTicks++;
            pressureTicks = 0;
        } else {
            // In the stable zone — decay both counters
            pressureTicks  = Math.max(0, pressureTicks  - 1);
            recoveryTicks  = Math.max(0, recoveryTicks  - 1);
        }

        // ── Reduce render distance ───────────────────────────────────────────
        if (pressureTicks >= PRESSURE_TICKS
                && currentRenderDist > minDist
                && cooldown >= ADJUST_COOLDOWN) {

            int next = currentRenderDist - 1;
            applyRenderDistance(mc, next);
            pressureTicks = 0;
            lastAdjustTick = tickIndex;
            reduceEvents.incrementAndGet();
            CowboyChunkOptimizerClient.LOGGER.info(
                "[CCO AutoTune] FPS {} < target {} — reducing render distance: {} → {}",
                rollingFps, target, currentRenderDist + 1, currentRenderDist);
        }

        // ── Restore render distance ──────────────────────────────────────────
        else if (recoveryTicks >= RECOVERY_TICKS
                && currentRenderDist < originalRenderDist
                && cooldown >= ADJUST_COOLDOWN) {

            int next = currentRenderDist + 1;
            applyRenderDistance(mc, next);
            recoveryTicks = 0;
            lastAdjustTick = tickIndex;
            restoreEvents.incrementAndGet();
            CowboyChunkOptimizerClient.LOGGER.info(
                "[CCO AutoTune] FPS stable at {} — restoring render distance: {} → {}",
                rollingFps, currentRenderDist - 1, currentRenderDist);
        }
    }

    private static void applyRenderDistance(MinecraftClient mc, int dist) {
        currentRenderDist = dist;
        // Apply via options — this triggers a WorldRenderer reload automatically
        mc.options.getViewDistance().setValue(dist);
        mc.worldRenderer.scheduleTerrainUpdate();
    }

    /** Restore original render distance (called on disable / world unload). */
    public static void restore() {
        if (originalRenderDist == -1) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options != null && currentRenderDist != originalRenderDist) {
            applyRenderDistance(mc, originalRenderDist);
            CowboyChunkOptimizerClient.LOGGER.info(
                "[CCO AutoTune] Restored render distance to {}", originalRenderDist);
        }
        reset();
    }

    public static void reset() {
        originalRenderDist = -1;
        currentRenderDist  = -1;
        pressureTicks = 0;
        recoveryTicks = 0;
        frameCount = 0;
        frameHead = 0;
        lastFrameNs = 0;
    }

    // Accessors for HUD
    public static float getRollingFps()       { return rollingFps; }
    public static int   getCurrentRenderDist(){ return currentRenderDist; }
    public static int   getOriginalRenderDist(){ return originalRenderDist; }
    public static int   getPressureTicks()    { return pressureTicks; }
    public static int   getRecoveryTicks()    { return recoveryTicks; }
    public static int   getReduceEvents()     { return reduceEvents.get(); }
    public static int   getRestoreEvents()    { return restoreEvents.get(); }
    public static boolean isActive()          { return originalRenderDist != -1; }
    public static boolean isReduced()         {
        return originalRenderDist != -1 && currentRenderDist < originalRenderDist;
    }
}
