package dev.cowboy.chunkoptimizer.config;

import dev.cowboy.chunkoptimizer.CowboyChunkOptimizerClient;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/**
 * Dimension Profile Manager.
 *
 * Different dimensions have radically different terrain characteristics
 * that call for different optimisation settings:
 *
 * OVERWORLD:
 *   - Full sky: Y-cull above player useful
 *   - Water everywhere: translucent sort throttle very valuable
 *   - Dense cave layer: early occlusion cull very effective
 *   - Leaves/foliage: LOD layer skipping saves significantly
 *
 * NETHER:
 *   - No sky: ceiling is solid → Y-cull above useful, below less so
 *   - Lava oceans: huge translucent sort load, throttle critical
 *   - Very dense terrain: occlusion cull extremely effective
 *   - No leaves: LOD cutout layer saving less relevant
 *   - Default render distance: 16 → many fog-hidden sections
 *
 * END:
 *   - Almost entirely solid stone islands with void between
 *   - Very few translucent sections → translucent throttle less useful
 *   - Back-face cull very effective (mostly looking at one island at a time)
 *   - Few block entities
 *
 * HOW IT WORKS:
 * When the player changes dimension, this manager applies a config overlay —
 * temporary overrides to specific config values that revert when leaving
 * the dimension. The base config is preserved; overrides are layered on top.
 *
 * Overrides are opt-in per dimension (defaultOverride = false by default,
 * user enables per dimension in the config screen).
 */
public final class DimensionProfileManager {

    private DimensionProfileManager() {}

    private static RegistryKey<World> currentDimension = null;
    private static boolean overrideActive = false;

    // Saved base values to restore on dimension change
    private static int   savedUploadBudgetMs;
    private static int   savedLodNearDistance;
    private static int   savedLodCutoutDistance;
    private static float savedTranslucentSortThreshold;
    private static int   savedBlockEntityRenderDistance;
    private static int   savedYCullDistanceBlocks;

    /**
     * Called when the player changes dimensions (from ClientPlayNetworkHandler).
     * Applies dimension-specific config profile if enabled.
     *
     * @param dimension the new dimension key
     */
    public static void onDimensionChanged(RegistryKey<World> dimension) {
        if (currentDimension != null && currentDimension.equals(dimension)) return;

        // Restore previous overrides before applying new ones
        if (overrideActive) restoreBase();

        currentDimension = dimension;
        applyProfile(dimension);
    }

    private static void applyProfile(RegistryKey<World> dimension) {
        ChunkOptimizerConfig cfg = ChunkOptimizerConfig.get();
        if (!cfg.dimensionProfiles) return;

        // Save current values
        savedUploadBudgetMs            = cfg.uploadBudgetMs;
        savedLodNearDistance           = cfg.lodNearDistance;
        savedLodCutoutDistance         = cfg.lodCutoutDistance;
        savedTranslucentSortThreshold  = cfg.translucentSortThresholdBlocks;
        savedBlockEntityRenderDistance = cfg.blockEntityRenderDistance;
        savedYCullDistanceBlocks       = cfg.yCullDistanceBlocks;

        if (dimension.equals(World.NETHER)) {
            applyNetherProfile(cfg);
        } else if (dimension.equals(World.END)) {
            applyEndProfile(cfg);
        } else {
            // Overworld or unknown — use configured base (no override needed)
            overrideActive = false;
            return;
        }

        overrideActive = true;
        CowboyChunkOptimizerClient.LOGGER.info(
            "[CCO] Applied dimension profile for: {}", dimension.getValue());
    }

    private static void applyNetherProfile(ChunkOptimizerConfig cfg) {
        // Nether: dense fog at ~100 blocks, no sky, lava = lots of translucent
        cfg.translucentSortThresholdBlocks = 0.25f; // sort more often (lava moves visually)
        cfg.lodNearDistance    = 6;  // LOD kicks in sooner (fog hides it anyway)
        cfg.lodCutoutDistance  = 8;
        cfg.yCullDistanceBlocks = 100; // Nether is 128 blocks tall, tighter bound
        // Budget slightly higher — Nether geometry is dense, worker threads need time
        cfg.uploadBudgetMs = Math.min(cfg.uploadBudgetMs + 1, 8);
    }

    private static void applyEndProfile(ChunkOptimizerConfig cfg) {
        // End: mostly void + sparse islands, very few translucent
        cfg.translucentSortThresholdBlocks = 2.0f; // sort rarely (barely any water)
        cfg.lodNearDistance    = 10; // can push LOD out — terrain is sparse
        cfg.lodCutoutDistance  = 14;
        cfg.yCullDistanceBlocks = 48; // End is flat — tight Y cull
        cfg.blockEntityRenderDistance = 48; // End bases tend to be compact
    }

    private static void restoreBase() {
        ChunkOptimizerConfig cfg = ChunkOptimizerConfig.get();
        cfg.uploadBudgetMs                 = savedUploadBudgetMs;
        cfg.lodNearDistance                = savedLodNearDistance;
        cfg.lodCutoutDistance              = savedLodCutoutDistance;
        cfg.translucentSortThresholdBlocks = savedTranslucentSortThreshold;
        cfg.blockEntityRenderDistance      = savedBlockEntityRenderDistance;
        cfg.yCullDistanceBlocks            = savedYCullDistanceBlocks;
        overrideActive = false;
    }

    public static void reset() {
        if (overrideActive) restoreBase();
        currentDimension = null;
    }

    public static RegistryKey<World> getCurrentDimension() { return currentDimension; }
    public static boolean isOverrideActive() { return overrideActive; }

    public static String getCurrentDimensionShort() {
        if (currentDimension == null) return "?";
        String path = currentDimension.getValue().getPath();
        return switch (path) {
            case "overworld" -> "OW";
            case "the_nether" -> "NT";
            case "the_end"   -> "END";
            default          -> path.substring(0, Math.min(3, path.length())).toUpperCase();
        };
    }
}
