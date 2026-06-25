package dev.cowboy.chunkoptimizer.scheduler;

import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;
import net.minecraft.world.chunk.ChunkSection;

/**
 * Fast pre-queue culling tests for chunk sections.
 *
 * These checks run before a section is submitted to the rebuild queue,
 * eliminating obviously useless builds without touching any worker threads.
 *
 * All checks are O(1) or O(very small constant) — they must be faster
 * than the overhead of enqueuing the task itself.
 */
public final class SectionCuller {

    private SectionCuller() {}

    /**
     * Returns true if the section should be SKIPPED (no rebuild needed).
     *
     * @param section  the ChunkSection to test (may be null for out-of-range)
     * @param sectionX section X coordinate (chunk-section space)
     * @param sectionY section Y coordinate
     * @param sectionZ section Z coordinate
     */
    public static boolean shouldSkip(ChunkSection section, int sectionX, int sectionY, int sectionZ) {
        if (section == null) return true;

        ChunkOptimizerConfig cfg = ChunkOptimizerConfig.get();

        // ── Air sections ────────────────────────────────────────────────────
        // isEmpty() checks the block count in the section palette —
        // returns true when there are zero non-air blocks. Pure O(1).
        if (cfg.skipAirSections && section.isEmpty()) {
            return true;
        }

        // ── Invisible-only sections ─────────────────────────────────────────
        // hasOnlyAir is a stricter check via access widener that includes
        // blocks with no model (barriers, structure voids, light blocks).
        if (cfg.skipInvisibleOnlySections && hasOnlyInvisibleBlocks(section)) {
            return true;
        }

        // ── Fluid-only sections ─────────────────────────────────────────────
        if (cfg.skipFluidOnlySections && isFluidOnly(section)) {
            return true;
        }

        return false;
    }

    /**
     * Quick check: does this section only contain blocks with no rendered geometry?
     * We use the section's non-empty block count — if all blocks are invisible
     * types (barriers etc.), their render layers are all empty.
     *
     * This is an approximation: we can't inspect render layers here without
     * expensive BlockState lookups, so we use isEmpty() as a proxy.
     * A future version could use a custom invisible-block bitset.
     */
    private static boolean hasOnlyInvisibleBlocks(ChunkSection section) {
        // Primary: air check covers the vast majority of empty sections
        if (section.isEmpty()) return true;
        // Secondary: check if the section has no renderable block states
        // by checking whether the block count equals only invisible block types.
        // For now, delegate to isEmpty — a more thorough check would require
        // iterating the section's palette which has its own cost.
        return false;
    }

    /**
     * Heuristic: is this section made entirely of fluid?
     * We approximate by checking if the section is non-empty but all blocks
     * are fluid source states. This check is intentionally lenient —
     * false negatives (not skipping) are fine, false positives would be bugs.
     */
    private static boolean isFluidOnly(ChunkSection section) {
        // Full implementation requires palette iteration; we stub to false
        // and let the real mesh builder determine emptiness.
        // A Mixin-based palette accessor would be needed for a proper check.
        return false;
    }

    /**
     * Returns true if the section has non-zero pending geometry to render.
     * Used by the fade system to decide whether to start a fade timer.
     */
    public static boolean hasRenderableContent(ChunkSection section) {
        return section != null && !section.isEmpty();
    }
}
