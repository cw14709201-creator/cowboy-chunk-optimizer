package dev.cowboy.chunkoptimizer.config;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;

/**
 * Central configuration for Cowboy's Chunk Optimizer v2.
 *
 * All fields are public for Cloth Config screen access.
 * JSON-persisted, hot-reload supported via the in-game keybind.
 *
 * Sections:
 *  A) Upload Budget & Adaptive Throttling
 *  B) Scheduler & Prioritisation
 *  C) Section Culling
 *  D) Mesh Memory Pool
 *  E) Chunk Fade Rendering
 *  F) Worker Thread Tuning
 *  G) Velocity Prediction
 *  H) Debug & Profiling
 */
public class ChunkOptimizerConfig {

    private static final Logger LOG = LoggerFactory.getLogger("cowboy-chunk-optimizer/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final Path CONFIG_FILE =
            FabricLoader.getInstance().getConfigDir().resolve("cowboy-chunk-optimizer.json");

    private static ChunkOptimizerConfig INSTANCE = new ChunkOptimizerConfig();

    // ═══════════════════════════════════════════════════════════════════════
    // A) UPLOAD BUDGET & ADAPTIVE THROTTLING
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Maximum milliseconds the main thread may spend uploading built chunk
     * meshes to the GPU per frame. This is the single highest-impact setting.
     *
     * Vanilla: unlimited (causes 20–40ms spikes when chunks load)
     * Default: 5ms — smooth at 60fps with fast chunk turnaround
     * Aggressive: 2–3ms — ultra smooth, chunks take slightly longer to appear
     * Fast load: 8–12ms — prioritises load speed over frame smoothness
     *
     * Range: 1–25
     */
    public int uploadBudgetMs = 5;

    /**
     * Adaptive budget: automatically raise uploadBudgetMs when the player
     * is stationary or moving slowly (no FPS impact), and lower it when
     * moving fast (prioritise smooth rendering).
     *
     * Velocity thresholds are defined in AdaptiveBudgetController.
     */
    public boolean adaptiveBudget = true;

    /**
     * Minimum budget used when adaptiveBudget scales down (high velocity).
     * Should be lower than uploadBudgetMs. Range: 1–10
     */
    public int adaptiveBudgetMin = 2;

    /**
     * Maximum budget used when adaptiveBudget scales up (player is still).
     * Range: uploadBudgetMs–25
     */
    public int adaptiveBudgetMax = 14;

    /**
     * Target frame time in ms for adaptive throttling reference.
     * 16 = 60fps, 8 = 120fps, 4 = 240fps. The adaptive controller uses
     * this to decide when the current frame is "over budget" overall.
     */
    public int targetFrameTimeMs = 16;

    // ═══════════════════════════════════════════════════════════════════════
    // B) SCHEDULER & PRIORITISATION
    // ═══════════════════════════════════════════════════════════════════════

    /** Max concurrent rebuild tasks in the queue. Range: 8–128. */
    public int maxRebuildQueue = 48;

    /**
     * Sort the rebuild queue so closest sections are always processed first.
     * This is the most important scheduling optimisation — without it, a
     * section 200 blocks away may be built before one 10 blocks in front.
     */
    public boolean nearestFirstScheduling = true;

    /**
     * In-frustum sections receive a priority multiplier making them build
     * significantly faster than sections behind the camera.
     * Multiplier: frustumPriorityBoost (lower = more aggressive boost).
     */
    public boolean frustumPriorityScheduling = true;

    /**
     * Priority score multiplier for in-frustum sections.
     * 0.4 = frustum sections treated as if 2.5x closer.
     * Range: 0.1–1.0. Default: 0.45
     */
    public float frustumPriorityBoost = 0.45f;

    /**
     * Velocity-predictive scheduling: estimate where the player will be in
     * ~1 second and pre-boost priority of sections in that direction.
     * Requires velocityTracking = true.
     */
    public boolean predictiveScheduling = true;

    /**
     * Look-ahead distance in seconds for predictive scheduling.
     * 0.8 = pre-prioritise sections ~0.8s of travel ahead of the player.
     * Range: 0.2–3.0
     */
    public float predictiveLookaheadSeconds = 0.8f;

    /**
     * When prioritising predictive chunks, this is the cone half-angle
     * (degrees) around the predicted travel direction within which sections
     * receive the predictive boost. Wider = more sections boosted.
     * Range: 15–90. Default: 35
     */
    public float predictiveConeAngleDeg = 35f;

    // ═══════════════════════════════════════════════════════════════════════
    // C) SECTION CULLING (skip pointless builds entirely)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Skip rebuild for sections that are entirely air. These have no visible
     * geometry and would produce a zero-polygon mesh anyway.
     */
    public boolean skipAirSections = true;

    /**
     * Skip rebuild for sections that only contain invisible blocks
     * (barriers, structure blocks, light sources, etc.) with no renderable
     * faces. Saves background thread time with no visual impact.
     */
    public boolean skipInvisibleOnlySections = true;

    /**
     * Skip rebuild for sections that are entirely submerged in water/lava
     * with no non-fluid blocks. Their mesh contains only fluid faces.
     * Set false if you use resource packs that render fluid blocks specially.
     */
    public boolean skipFluidOnlySections = false;

    /**
     * Extended occlusion pre-test: before queuing a section for rebuild,
     * do a fast check of whether any face of the section is exposed to air
     * on any of its 6 sides. Fully buried sections (surrounded by solid)
     * are culled immediately without touching worker threads.
     */
    public boolean earlyOcclusionCull = true;

    // ═══════════════════════════════════════════════════════════════════════
    // D) MESH MEMORY POOL
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Pool and reuse vertex buffer objects across section rebuilds instead
     * of allocating new native memory each time. Dramatically reduces GC
     * pressure on the render thread and GPU driver call overhead.
     */
    public boolean enableMeshPool = true;

    /**
     * Maximum number of pooled VertexBuffer objects to keep alive.
     * Each entry holds ~1–4 MB of VRAM depending on section complexity.
     * Range: 64–1024. Default: 256
     */
    public int meshPoolMaxSize = 256;

    /**
     * Aggressively evict mesh pool entries when they haven't been used for
     * this many ticks. Reduces VRAM usage at the cost of occasional
     * re-allocation when sections come back into range.
     * 0 = never evict (keep all pooled entries). Range: 0–600
     */
    public int meshPoolEvictAfterTicks = 120;

    // ═══════════════════════════════════════════════════════════════════════
    // E) CHUNK FADE RENDERING
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Fade new chunk sections in over a short duration instead of hard pop-in.
     * Uses a smoothstep curve for natural easing.
     */
    public boolean enableFade = true;

    /** Fade duration in milliseconds. Range: 30–800. Default: 150 */
    public int fadeDurationMs = 150;

    /**
     * Fade curve style:
     *  0 = linear        (constant opacity gain, looks robotic)
     *  1 = smoothstep    (starts fast, decelerates — default, natural)
     *  2 = ease-in       (starts slow, ends fast — "blooms" into view)
     *  3 = ease-out-quad (fast start, slow end — snappy feel)
     */
    public int fadeCurveMode = 1;

    /**
     * Only apply fade to sections that are more than this many chunks away.
     * Nearby sections (< threshold) appear instantly to avoid the player
     * seeing terrain phase in right at their feet.
     * 0 = fade everything. Range: 0–6. Default: 2
     */
    public int fadeNearbyChunkThreshold = 2;

    // ═══════════════════════════════════════════════════════════════════════
    // F) WORKER THREAD TUNING
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Number of background threads for chunk mesh building.
     * 0 = auto-compute: max(1, physicalCores - 2).
     * Recommended: leave at 0 unless you know your CPU topology.
     * Range: 0–32
     */
    public int workerThreads = 0;

    /**
     * Raise chunk worker threads above NORM_PRIORITY in the OS scheduler.
     * Helps on systems where the JVM is competing with other processes.
     * Note: can cause input/audio jitter if the system is under heavy load.
     */
    public boolean highPriorityWorkers = false;

    /**
     * Pin each worker thread to its own CPU core using thread affinity hints
     * (best-effort; no-op on unsupported JVMs). Prevents threads migrating
     * between cores during mesh builds, improving cache locality.
     */
    public boolean workerCoreAffinity = false;

    // ═══════════════════════════════════════════════════════════════════════
    // G) VELOCITY TRACKING
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Track player velocity across ticks. Required for adaptive budget and
     * predictive scheduling. Negligible CPU cost.
     */
    public boolean velocityTracking = true;

    /**
     * Velocity sample window in ticks. Smooths out jitter from player
     * physics (jumping, knockback). Range: 1–20. Default: 6
     */
    public int velocitySampleTicks = 6;

    // ═══════════════════════════════════════════════════════════════════════
    // H) DEBUG & PROFILING
    // ═══════════════════════════════════════════════════════════════════════

    /** Show live chunk stats in top-left corner (toggleable with keybind). */
    public boolean showHud = false;

    /** Include per-system timing breakdown in HUD. */
    public boolean hudShowTimings = false;

    /** Log performance warnings to console when frame budget is consistently exceeded. */
    public boolean logBudgetWarnings = true;

    /** Warn if average budget overage exceeds this many ms. Range: 1–20. Default: 5 */
    public int budgetWarnThresholdMs = 5;

    // ═══════════════════════════════════════════════════════════════════════


    // ═══════════════════════════════════════════════════════════════════════
    // I) BURST MODE — fast-loading after teleport / world join
    // ═══════════════════════════════════════════════════════════════════════

    /** Expand upload budget temporarily after teleport/world-load for fast chunk appearance. */
    public boolean burstMode = true;

    /** Budget multiplier during burst (3.0 = 3x normal budget for ~6s). Range: 1.5–5.0 */
    public float burstBudgetMultiplier = 3.0f;

    // ═══════════════════════════════════════════════════════════════════════
    // J) PALETTE HASH DEDUPLICATION
    // ═══════════════════════════════════════════════════════════════════════

    /** Skip rebuild for sections whose blocks haven't changed since the last build.
     *  Catches spurious dirty flags from light updates and server sync. */
    public boolean paletteHashDedup = true;

    // ═══════════════════════════════════════════════════════════════════════
    // K) NEIGHBOR PRELOADING
    // ═══════════════════════════════════════════════════════════════════════

    /** When a new chunk arrives, proactively invalidate visibility caches for
     *  all 8 neighboring chunk borders to prevent seam artifacts. */
    public boolean neighborPreloading = true;

    // ═══════════════════════════════════════════════════════════════════════
    // L) DYNAMIC THREAD SCALING
    // ═══════════════════════════════════════════════════════════════════════

    /** Dynamically scale worker thread count based on pending queue depth.
     *  More threads during load bursts, fewer during idle. */
    public boolean dynamicThreadScaling = true;


    // ═══════════════════════════════════════════════════════════════════════
    // N) LIGHT UPDATE THROTTLE — eliminates spurious light-driven rebuilds
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Throttle section rebuilds caused by light updates (day/night, torches).
     * Light changes do not change geometry — vanilla rebuilds are wasteful.
     * This is one of the highest-impact optimizations in heavy light scenes.
     */
    public boolean lightUpdateThrottle = true;

    /**
     * Minimum ticks between light-driven rebuilds for the same section.
     * Default 4 = max 5 light rebuilds/second per section (vs vanilla's ~20).
     * Range: 1–20.
     */
    public int lightThrottleTicks = 4;

    // ═══════════════════════════════════════════════════════════════════════
    // O) LOD (Level of Detail) — skip expensive render layers at distance
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Enable LOD layer skipping for distant sections.
     * Sections beyond lodNearDistance skip CUTOUT and TRANSLUCENT render layers.
     * These are the most expensive layers and barely visible at distance.
     */
    public boolean enableLOD = true;

    /**
     * Chunk distance beyond which LOD kicks in (skip translucent).
     * Default 8: sections 8+ chunks away skip water/glass renders.
     */
    public int lodNearDistance = 8;

    /**
     * Chunk distance beyond which cutout (leaves, fences) is also skipped.
     * Default 12. Must be >= lodNearDistance.
     */
    public int lodCutoutDistance = 12;

    // ═══════════════════════════════════════════════════════════════════════
    // P) FPS AUTO-TUNER — dynamically adjusts render distance for target FPS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Automatically reduce render distance when FPS drops below fpsAutoTuneTarget,
     * and restore it when FPS recovers. The single most powerful way to maintain
     * smooth gameplay — finds your GPU's optimal render distance in real time.
     */
    public boolean fpsAutoTune = false; // opt-in: changes render distance

    /** Target FPS for the auto-tuner. Default: 60. */
    public float fpsAutoTuneTarget = 60.0f;

    /** Minimum render distance the auto-tuner will go to. Default: 6. */
    public int autoTuneMinRenderDistance = 6;

    // ═══════════════════════════════════════════════════════════════════════
    // Q) RENDER FEATHERING — skip outermost ring sections every 2nd frame
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Render the outermost ring of sections every 2nd frame instead of every frame.
     * These sections contribute ~2% of visible pixels but ~15% of draw calls.
     * The flicker is imperceptible at normal framerates (60+fps).
     */
    public boolean enableRenderFeathering = true;

    // ═══════════════════════════════════════════════════════════════════════
    // R) Y-AXIS FRUSTUM CULL — skip sections far above/below the player
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Skip rendering sections that are more than yCullDistanceBlocks above
     * or below the camera. Sky and deep bedrock sections are never visible.
     */
    public boolean enableYCull = true;

    /** Distance in blocks. Default 160 (10 sections). Range: 32–320. */
    public int yCullDistanceBlocks = 160;

    // ═══════════════════════════════════════════════════════════════════════
    // S) ASYNC DATA COPY — pre-warm chunk data before worker tasks start
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Pre-copy section block data into staging buffers on a background thread
     * before worker tasks request it. Reduces cache misses in the worker copy phase.
     */
    public boolean asyncDataCopy = true;


    // ═══════════════════════════════════════════════════════════════════════
    // T) RENDER-TIME SECTION CULLING (RenderSectionCuller)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Skip draw calls for sections that are more than ~150° behind the camera
     * using a fast dot-product test. Cheaper than a full AABB frustum test.
     * Eliminates ~30-40% of back-hemisphere sections from each frame's draw list.
     */
    public boolean renderBackfaceCull = true;

    /**
     * Skip draw calls for the outermost ring of sections every other frame.
     * These sections are near the fog edge and barely contribute to the image.
     * Halves their draw-call overhead with no perceptible visual difference at 60+ fps.
     */
    public boolean renderLodSkip = true;

    /**
     * How many chunks from the edge of render distance the LOD skip kicks in.
     * Default 2: the outermost 2-chunk ring is feathered. Range: 1–4.
     */
    public int renderLodSkipMarginChunks = 2;

    /**
     * Skip draw calls for sections that haven't been rebuilt for a long time.
     * Static geometry (untouched terrain) only needs its draw call every N frames
     * since the GPU vertex data doesn't change between frames.
     */
    public boolean renderStaticSkip = true;

    /**
     * How many ticks a section must be untouched before static-skip activates.
     * Default 100 ticks (5 seconds). Range: 20–600.
     */
    public int renderStaticSkipAgeThresholdTicks = 100;

    /**
     * Draw static sections every N frames instead of every frame.
     * Default 3: draw every 3rd frame (saves ~66% draw call overhead for static terrain).
     * Range: 2–6. Higher = more savings, higher flicker risk at low FPS.
     */
    public int renderStaticSkipInterval = 3;


    // ═══════════════════════════════════════════════════════════════════════
    // U) TRANSLUCENT SORT THROTTLE
    // ═══════════════════════════════════════════════════════════════════════

    /** Throttle per-frame translucent vertex re-sorts to when camera moves enough.
     *  Biggest win in water-heavy worlds (ocean, rivers, nether lava). */
    public boolean translucentSortThrottle = true;

    /** Camera must move this many blocks before a section's sort re-runs.
     *  Default 0.5. Lower = more accurate, higher = more savings. Range: 0.1–4.0 */
    public float translucentSortThresholdBlocks = 0.5f;

    // ═══════════════════════════════════════════════════════════════════════
    // V) BLOCK ENTITY CULLING
    // ═══════════════════════════════════════════════════════════════════════

    /** Cull block entity render calls for entities that are too far away,
     *  behind the player, or far above/below. Saves render overhead in
     *  decorated bases, mob farms, and village-heavy areas. */
    public boolean blockEntityCulling = true;

    /** Max distance in blocks to render block entities. Default 64. Range: 16–256. */
    public int blockEntityRenderDistance = 64;

    /** Skip block entities more than this many blocks above/below the player. */
    public boolean blockEntityYCull = true;

    /** Y distance threshold for block entity Y-cull. Default 48. Range: 16–192. */
    public int blockEntityYCullBlocks = 48;

    /** Skip block entities clearly behind the player (dot < -0.85). */
    public boolean blockEntityBackfaceCull = true;

    // ═══════════════════════════════════════════════════════════════════════
    // W) MESH COMPRESSION STATS
    // ═══════════════════════════════════════════════════════════════════════

    /** Track potential VRAM savings from vertex compression (stats only — no
     *  actual compression without matching shader support). */
    public boolean meshCompressionStats = true;

    // ═══════════════════════════════════════════════════════════════════════
    // X) DIMENSION PROFILES
    // ═══════════════════════════════════════════════════════════════════════

    /** Apply per-dimension config overrides (Nether/End have different optimal
     *  settings for LOD, sort throttle, Y-cull, etc.). */
    public boolean dimensionProfiles = true;

    // ═══════════════════════════════════════════════════════════════════════
    // Y) STARTUP BENCHMARK
    // ═══════════════════════════════════════════════════════════════════════

    /** Run a startup benchmark on first launch to auto-configure upload budget
     *  and worker thread count for this specific machine. Runs once then disables. */
    public boolean runStartupBenchmark = true;

    public static ChunkOptimizerConfig get() { return INSTANCE; }

    public static void load() {
        if (Files.exists(CONFIG_FILE)) {
            try (Reader r = Files.newBufferedReader(CONFIG_FILE)) {
                ChunkOptimizerConfig loaded = GSON.fromJson(r, ChunkOptimizerConfig.class);
                if (loaded != null) {
                    INSTANCE = loaded;
                    sanitise(INSTANCE);
                    LOG.info("[CCO] Config loaded.");
                    return;
                }
            } catch (Exception e) {
                LOG.warn("[CCO] Config read failed — using defaults. ({})", e.getMessage());
            }
        }
        save();
        LOG.info("[CCO] Default config written to {}", CONFIG_FILE);
    }

    public static void save() {
        try (Writer w = Files.newBufferedWriter(CONFIG_FILE)) {
            GSON.toJson(INSTANCE, w);
        } catch (IOException e) {
            LOG.error("[CCO] Could not save config.", e);
        }
    }

    /** Clamp all values to safe ranges. */
    private static void sanitise(ChunkOptimizerConfig c) {
        c.uploadBudgetMs        = clamp(c.uploadBudgetMs, 1, 25);
        c.adaptiveBudgetMin     = clamp(c.adaptiveBudgetMin, 1, 10);
        c.adaptiveBudgetMax     = clamp(c.adaptiveBudgetMax, c.uploadBudgetMs, 25);
        c.targetFrameTimeMs     = clamp(c.targetFrameTimeMs, 4, 33);
        c.maxRebuildQueue       = clamp(c.maxRebuildQueue, 8, 128);
        c.frustumPriorityBoost  = clampF(c.frustumPriorityBoost, 0.1f, 1.0f);
        c.predictiveLookaheadSeconds = clampF(c.predictiveLookaheadSeconds, 0.2f, 3.0f);
        c.predictiveConeAngleDeg     = clampF(c.predictiveConeAngleDeg, 15f, 90f);
        c.meshPoolMaxSize       = clamp(c.meshPoolMaxSize, 64, 1024);
        c.meshPoolEvictAfterTicks = clamp(c.meshPoolEvictAfterTicks, 0, 600);
        c.fadeDurationMs        = clamp(c.fadeDurationMs, 30, 800);
        c.fadeCurveMode         = clamp(c.fadeCurveMode, 0, 3);
        c.fadeNearbyChunkThreshold = clamp(c.fadeNearbyChunkThreshold, 0, 6);
        c.workerThreads         = clamp(c.workerThreads, 0, 32);
        c.velocitySampleTicks   = clamp(c.velocitySampleTicks, 1, 20);
        c.budgetWarnThresholdMs = clamp(c.budgetWarnThresholdMs, 1, 20);
        c.lightThrottleTicks    = clamp(c.lightThrottleTicks, 1, 20);
        c.lodNearDistance       = clamp(c.lodNearDistance, 2, 16);
        c.lodCutoutDistance     = clamp(c.lodCutoutDistance, c.lodNearDistance, 24);
        c.fpsAutoTuneTarget     = clampF(c.fpsAutoTuneTarget, 20f, 240f);
        c.autoTuneMinRenderDistance = clamp(c.autoTuneMinRenderDistance, 2, 12);
        c.yCullDistanceBlocks   = clamp(c.yCullDistanceBlocks, 32, 320);
        c.renderLodSkipMarginChunks       = clamp(c.renderLodSkipMarginChunks, 1, 4);
        c.renderStaticSkipAgeThresholdTicks = clamp(c.renderStaticSkipAgeThresholdTicks, 20, 600);
        c.renderStaticSkipInterval          = clamp(c.renderStaticSkipInterval, 2, 6);
        c.translucentSortThresholdBlocks  = clampF(c.translucentSortThresholdBlocks, 0.1f, 4.0f);
        c.blockEntityRenderDistance       = clamp(c.blockEntityRenderDistance, 16, 256);
        c.blockEntityYCullBlocks          = clamp(c.blockEntityYCullBlocks, 16, 192);
        c.burstBudgetMultiplier = clampF(c.burstBudgetMultiplier, 1.5f, 5.0f);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float clampF(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}

    // NOTE: These fields are appended additions to the config class.
    // They must be placed inside the class body. The file is regenerated below.
