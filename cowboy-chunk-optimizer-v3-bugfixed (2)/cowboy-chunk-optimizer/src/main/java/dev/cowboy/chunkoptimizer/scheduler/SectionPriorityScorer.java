package dev.cowboy.chunkoptimizer.scheduler;

import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;
import dev.cowboy.chunkoptimizer.util.VelocityTracker;
import net.minecraft.util.math.Vec3d;

/**
 * Scores a chunk section position for rebuild priority.
 *
 * Lower score = higher priority (sections are sorted ascending).
 *
 * Scoring components:
 *  1. Distance²  — baseline, ensures nearby sections are always preferred
 *  2. Frustum    — sections in view get a configurable discount
 *  3. Predictive — sections in the predicted travel direction get boosted
 *  4. Y-axis     — sections near eye level get a small bonus over sky/bedrock
 *
 * All camera state is pushed here from the render thread each frame via
 * {@link #updateCamera}. Reads are lock-free (volatile).
 */
public final class SectionPriorityScorer {

    private SectionPriorityScorer() {}

    // Volatile camera state updated once per frame from render thread
    private static volatile double camX = 0, camY = 0, camZ = 0;
    private static volatile float  camYaw = 0, camPitch = 0;

    // Forward vector components (derived from yaw/pitch, updated with camera)
    private static volatile double fwdX = 0, fwdY = 0, fwdZ = 1;

    // Predicted future position (updated alongside camera)
    private static volatile double predX = 0, predY = 0, predZ = 0;

    /** Push camera state from the render thread. */
    public static void updateCamera(double x, double y, double z, float yaw, float pitch) {
        camX = x; camY = y; camZ = z;
        camYaw = yaw; camPitch = pitch;

        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        fwdX = -Math.sin(yr) * Math.cos(pr);
        fwdY = -Math.sin(pr);
        fwdZ =  Math.cos(yr) * Math.cos(pr);

        // Compute predicted position for predictive scheduling
        ChunkOptimizerConfig cfg = ChunkOptimizerConfig.get();
        if (cfg.velocityTracking && cfg.predictiveScheduling) {
            Vec3d pred = VelocityTracker.predictPosition(x, y, z, cfg.predictiveLookaheadSeconds);
            predX = pred.x; predY = pred.y; predZ = pred.z;
        } else {
            predX = x; predY = y; predZ = z;
        }
    }

    /**
     * Compute priority score for section at chunk-section coordinates (sx, sy, sz).
     * Block centre: (sx*16+8, sy*16+8, sz*16+8)
     */
    public static double score(int sx, int sy, int sz) {
        ChunkOptimizerConfig cfg = ChunkOptimizerConfig.get();

        // Block-space centre of the section
        double bx = sx * 16.0 + 8.0;
        double by = sy * 16.0 + 8.0;
        double bz = sz * 16.0 + 8.0;

        double dx = bx - camX;
        double dy = by - camY;
        double dz = bz - camZ;
        double distSq = dx*dx + dy*dy + dz*dz;

        double score = distSq;

        // ── Frustum priority ────────────────────────────────────────────────
        if (cfg.frustumPriorityScheduling) {
            double dist = Math.sqrt(distSq);
            if (dist > 0.001) {
                double dot = (dx * fwdX + dy * fwdY + dz * fwdZ) / dist;
                if (dot > 0.0) {
                    // Fully in-frustum (dot=1) → apply full boost
                    // Partially in-frustum (dot=0.5) → half boost
                    float boost = cfg.frustumPriorityBoost;
                    double factor = boost + (1.0 - boost) * (1.0 - dot);
                    score *= factor;
                }
            }
        }

        // ── Predictive scheduling ───────────────────────────────────────────
        if (cfg.predictiveScheduling && cfg.velocityTracking) {
            double pdx = bx - predX;
            double pdy = by - predY;
            double pdz = bz - predZ;
            double predDistSq = pdx*pdx + pdy*pdy + pdz*pdz;

            // Direction toward predicted position from current camera
            double futDX = predX - camX;
            double futDY = predY - camY;
            double futDZ = predZ - camZ;
            double futLen = Math.sqrt(futDX*futDX + futDY*futDY + futDZ*futDZ);

            if (futLen > 1.0) {
                // Angle between section direction and predicted travel direction
                futDX /= futLen; futDY /= futLen; futDZ /= futLen;
                double norm = Math.sqrt(distSq);
                double dot = norm > 0.001
                    ? (dx * futDX + dy * futDY + dz * futDZ) / norm
                    : 0;

                double coneRad = Math.toRadians(cfg.predictiveConeAngleDeg);
                double cosHalf = Math.cos(coneRad);
                if (dot > cosHalf) {
                    // Inside predictive cone: blend toward predicted distance
                    double t = (dot - cosHalf) / (1.0 - cosHalf); // 0..1
                    // Further inside cone = more weight on predicted distance
                    score = score * (1.0 - t * 0.5) + predDistSq * (t * 0.5);
                }
            }
        }

        // ── Y-level bonus: sections near eye height load first ──────────────
        // Sections at eye level (dy ~0) get a 10% boost; buried/sky sections
        // don't — they're less likely to be visible terrain.
        double eyeDist = Math.abs(by - camY);
        if (eyeDist < 32.0) {
            score *= (0.9 + 0.1 * (eyeDist / 32.0));
        }

        return score;
    }

    // Accessors for debug HUD
    public static double getCamX() { return camX; }
    public static double getCamY() { return camY; }
    public static double getCamZ() { return camZ; }
    public static double getPredX() { return predX; }
    public static double getPredY() { return predY; }
    public static double getPredZ() { return predZ; }
}
