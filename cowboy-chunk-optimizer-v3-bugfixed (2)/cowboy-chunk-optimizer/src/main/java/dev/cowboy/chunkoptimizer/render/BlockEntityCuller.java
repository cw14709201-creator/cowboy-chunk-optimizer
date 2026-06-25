package dev.cowboy.chunkoptimizer.render;

import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;
import dev.cowboy.chunkoptimizer.scheduler.SectionPriorityScorer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Block Entity Culler.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * THE PROBLEM
 * ═══════════════════════════════════════════════════════════════════════════
 * Block entities (chests, signs, banners, furnaces, enchanting tables,
 * beds, bells, beehives, shulker boxes, etc.) have separate render calls
 * that run every frame regardless of whether they're visible. Each block
 * entity renderer call involves:
 *   1. A Java method dispatch
 *   2. GL state setup (matrix push, uniform set)
 *   3. Model rendering
 *   4. GL state teardown (matrix pop)
 *
 * In a decorated base with 200 block entities, even if you're looking away
 * from half of them, all 200 get their render calls issued every frame.
 * Each call is cheap individually (~0.005ms) but 200 × 60fps = 6ms/s wasted.
 * In heavily-decorated worlds or mob farms, this can be 300–500 entities.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * THE SOLUTION — three-tier culling
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * TIER 1: Distance cull
 *   Block entities beyond blockEntityRenderDistance blocks from the camera
 *   are skipped entirely. Default: 64 blocks (4 chunks). Beyond this, block
 *   entity detail is not readable anyway.
 *
 * TIER 2: Back-hemisphere cull
 *   Block entities whose direction from camera has dot product < -0.7 with
 *   the camera forward vector are behind us. Skip them.
 *   This is O(1) — 3 multiplies + add + compare.
 *
 * TIER 3: Y-extent cull
 *   Block entities more than blockEntityYCullBlocks above or below the camera
 *   are likely not visible (deep underground or high in the sky).
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * MIXIN TARGET
 * ═══════════════════════════════════════════════════════════════════════════
 * WorldRenderer#renderBlockEntities() iterates blockEntitiesForRendering and
 * calls BlockEntityRendererFactories.get(be.getType()).render(...) for each.
 * We inject before each render call via @Redirect on the iterator's hasNext()
 * or more precisely via an @Inject into the render dispatch method with a
 * per-entity check.
 *
 * The actual mixin is MixinBlockEntityRenderer — this class holds the logic.
 */
public final class BlockEntityCuller {

    private BlockEntityCuller() {}

    // Stats
    private static final AtomicLong culledDistance  = new AtomicLong(0);
    private static final AtomicLong culledBackface   = new AtomicLong(0);
    private static final AtomicLong culledY          = new AtomicLong(0);
    private static final AtomicLong totalTested      = new AtomicLong(0);
    private static final AtomicLong totalRendered    = new AtomicLong(0);

    /**
     * Main cull decision. Returns true = SKIP this block entity's render call.
     *
     * @param bex block entity X (world coordinates)
     * @param bey block entity Y
     * @param bez block entity Z
     */
    public static boolean shouldCull(double bex, double bey, double bez) {
        ChunkOptimizerConfig cfg = ChunkOptimizerConfig.get();
        if (!cfg.blockEntityCulling) return false;

        totalTested.incrementAndGet();

        double camX = SectionPriorityScorer.getCamX();
        double camY = SectionPriorityScorer.getCamY();
        double camZ = SectionPriorityScorer.getCamZ();

        double dx = bex - camX;
        double dy = bey - camY;
        double dz = bez - camZ;

        // ── Tier 1: Distance cull ────────────────────────────────────────
        double distSq = dx*dx + dy*dy + dz*dz;
        double maxDist = cfg.blockEntityRenderDistance;
        if (distSq > maxDist * maxDist) {
            culledDistance.incrementAndGet();
            return true;
        }

        // ── Tier 2: Y-extent cull ────────────────────────────────────────
        if (cfg.blockEntityYCull) {
            if (Math.abs(dy) > cfg.blockEntityYCullBlocks) {
                culledY.incrementAndGet();
                return true;
            }
        }

        // ── Tier 3: Back-hemisphere dot-product cull ─────────────────────
        if (cfg.blockEntityBackfaceCull) {
            // Get camera forward vector (computed from yaw/pitch in SectionPriorityScorer)
            // We approximate using the stored camera position delta from last frame
            // For correctness, we use a conservative threshold (-0.85 = >148° behind)
            // so we only cull block entities that are clearly behind the player
            double dist = Math.sqrt(distSq);
            if (dist > 4.0) { // don't cull BEs in the same block as camera
                // Read forward vector from RenderSectionCuller which caches it
                // We use a simplified version: compare against camera velocity direction
                // stored in VelocityTracker. This is safe — block entities are static.
                double fwdX = dev.cowboy.chunkoptimizer.render.RenderSectionCuller.getFwdX();
                double fwdY = dev.cowboy.chunkoptimizer.render.RenderSectionCuller.getFwdY();
                double fwdZ = dev.cowboy.chunkoptimizer.render.RenderSectionCuller.getFwdZ();
                double dot  = (dx * fwdX + dy * fwdY + dz * fwdZ) / dist;
                if (dot < -0.85) {
                    culledBackface.incrementAndGet();
                    return true;
                }
            }
        }

        totalRendered.incrementAndGet();
        return false;
    }

    public static void resetStats() {
        culledDistance.set(0);
        culledBackface.set(0);
        culledY.set(0);
        totalTested.set(0);
        totalRendered.set(0);
    }

    public static long getCulledDistance() { return culledDistance.get(); }
    public static long getCulledBackface()  { return culledBackface.get(); }
    public static long getCulledY()         { return culledY.get(); }
    public static long getTotalTested()     { return totalTested.get(); }
    public static long getTotalRendered()   { return totalRendered.get(); }
    public static long getTotalCulled()     {
        return culledDistance.get() + culledBackface.get() + culledY.get();
    }
}
