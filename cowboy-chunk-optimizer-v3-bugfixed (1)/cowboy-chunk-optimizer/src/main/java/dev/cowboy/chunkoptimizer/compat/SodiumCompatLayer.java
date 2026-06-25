package dev.cowboy.chunkoptimizer.compat;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Sodium Compatibility Layer.
 *
 * Sodium replaces most of Minecraft's chunk rendering pipeline, including:
 *  - ChunkBuilder (replaced by SodiumChunkBuilder / TaskDispatcher)
 *  - WorldRenderer chunk sections (replaced by RenderSection)
 *  - BuiltChunkStorage (replaced by RenderSectionManager)
 *
 * When Sodium is present, several of our mixins target classes that have
 * been replaced or heavily modified. This layer:
 *
 *  1. Detects Sodium at startup
 *  2. Disables CCO systems that conflict with or duplicate Sodium's own
 *     implementations (Sodium already has good thread management)
 *  3. Keeps systems that complement Sodium (burst detection, fade, HUD,
 *     velocity tracking, teleport detection)
 *
 * The detection is done via FabricLoader at startup — no runtime reflection.
 *
 * Systems disabled when Sodium is present:
 *  - MixinChunkBuilder (Sodium replaces it entirely)
 *  - MixinChunkRenderDispatcher (Sodium uses a different dispatcher)
 *  - MixinBuiltChunkStorage (Sodium has RenderSectionManager)
 *  - MeshPool (Sodium manages its own buffer pools)
 *
 * Systems kept alongside Sodium:
 *  - TeleportBurstHandler ✓
 *  - AdaptiveBudgetController (reports budget info to HUD) ✓
 *  - VelocityTracker ✓
 *  - SectionVisibilityGraph ✓
 *  - ChunkFadeSystem (render-level, works with any renderer) — partial
 *  - HUD overlay ✓
 *  - Performance profiler ✓
 */
public final class SodiumCompatLayer {

    private SodiumCompatLayer() {}

    private static final boolean SODIUM_PRESENT = checkSodium();
    private static final boolean IRIS_PRESENT   = checkIris();
    private static final boolean EMBEDDIUM_PRESENT = checkEmbeddium();

    private static boolean checkSodium() {
        return FabricLoader.getInstance().isModLoaded("sodium");
    }

    private static boolean checkIris() {
        return FabricLoader.getInstance().isModLoaded("iris");
    }

    private static boolean checkEmbeddium() {
        return FabricLoader.getInstance().isModLoaded("embeddium");
    }

    /** True if Sodium or an equivalent renderer replacement is present. */
    public static boolean isSodiumPresent()    { return SODIUM_PRESENT || EMBEDDIUM_PRESENT; }
    public static boolean isIrisPresent()      { return IRIS_PRESENT; }
    public static boolean isEmbeddiumPresent() { return EMBEDDIUM_PRESENT; }

    /**
     * Should CCO's chunk builder mixin be applied?
     * Disabled when Sodium is present since it replaces the target class.
     */
    public static boolean applyChunkBuilderMixin()    { return !isSodiumPresent(); }
    public static boolean applyMeshPool()              { return !isSodiumPresent(); }
    public static boolean applyDispatcherMixin()       { return !isSodiumPresent(); }
    public static boolean applyBuiltStorageMixin()     { return !isSodiumPresent(); }

    /**
     * Systems always active regardless of Sodium presence.
     */
    public static boolean applyBurstDetection()   { return true; }
    public static boolean applyVelocityTracking() { return true; }
    public static boolean applyFadeSystem()        { return !isSodiumPresent(); } // fade needs render hooks
    public static boolean applyHud()               { return true; }

    /**
     * Log compatibility status at startup.
     */
    public static void logStatus() {
        var log = dev.cowboy.chunkoptimizer.CowboyChunkOptimizerClient.LOGGER;
        if (isSodiumPresent()) {
            log.info("[CCO] Sodium detected — disabling conflicting mixins (builder, dispatcher, mesh pool).");
            log.info("[CCO] Velocity tracking, burst detection, and HUD remain active.");
        } else {
            log.info("[CCO] No Sodium detected — full pipeline active.");
        }
        if (isIrisPresent()) {
            log.info("[CCO] Iris detected — shader-aware chunk fade disabled (would conflict with shader alpha).");
        }
    }
}
