package dev.cowboy.chunkoptimizer.util;

import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;

/**
 * Tracks the client player's velocity over a rolling window of ticks.
 *
 * Used by:
 *  - AdaptiveBudgetController  (decide upload budget based on speed)
 *  - PredictiveScheduler       (estimate future player position)
 *
 * Thread-safe for reads; only written from the client tick thread.
 */
public final class VelocityTracker {

    private VelocityTracker() {}

    private static final int MAX_WINDOW = 20;
    private static final double[] velX = new double[MAX_WINDOW];
    private static final double[] velY = new double[MAX_WINDOW];
    private static final double[] velZ = new double[MAX_WINDOW];

    private static volatile int writeHead = 0;
    private static volatile int count = 0;

    // Last known position — updated each tick
    private static volatile double lastX = 0, lastY = 0, lastZ = 0;
    // Current tick velocity (blocks/tick, 1 tick = 0.05s)
    private static volatile double curVX = 0, curVY = 0, curVZ = 0;
    // Smoothed speed (blocks/second)
    private static volatile double smoothSpeed = 0;

    /**
     * Call from ClientTickEvents.END_CLIENT_TICK with the player's
     * current position each tick. Updates all internal state.
     */
    public static void tick(double px, double py, double pz) {
        if (!ChunkOptimizerConfig.get().velocityTracking) return;

        int window = Math.min(MAX_WINDOW, ChunkOptimizerConfig.get().velocitySampleTicks);

        curVX = px - lastX;
        curVY = py - lastY;
        curVZ = pz - lastZ;

        lastX = px;
        lastY = py;
        lastZ = pz;

        int idx = writeHead % MAX_WINDOW;
        velX[idx] = curVX;
        velY[idx] = curVY;
        velZ[idx] = curVZ;
        writeHead++;
        count = Math.min(count + 1, MAX_WINDOW);

        // Compute smoothed speed from window
        int n = Math.min(count, window);
        double sx = 0, sy = 0, sz = 0;
        for (int i = 0; i < n; i++) {
            int k = ((writeHead - 1 - i) + MAX_WINDOW) % MAX_WINDOW;
            sx += velX[k];
            sy += velY[k];
            sz += velZ[k];
        }
        sx /= n; sy /= n; sz /= n;
        // Convert blocks/tick to blocks/second
        smoothSpeed = Math.sqrt(sx*sx + sy*sy + sz*sz) * 20.0;
    }

    /** Smoothed speed in blocks/second. */
    public static double getSmoothedSpeed() { return smoothSpeed; }

    /** Raw velocity this tick, in blocks/tick. */
    public static Vec3d getCurrentVelocity() { return new Vec3d(curVX, curVY, curVZ); }

    /**
     * Predict player position N seconds in the future, using the current
     * smoothed velocity. The Y component is clamped (player can't fly
     * straight up in survival) for better horizontal prediction.
     */
    public static Vec3d predictPosition(double px, double py, double pz, float seconds) {
        // Convert ticks/tick velocity to blocks/second
        double pvx = curVX * 20.0;
        double pvy = curVY * 20.0;
        double pvz = curVZ * 20.0;
        return new Vec3d(
            px + pvx * seconds,
            py + pvy * seconds,
            pz + pvz * seconds
        );
    }

    /** Whether the player is effectively stationary (< 0.5 b/s). */
    public static boolean isStationary() { return smoothSpeed < 0.5; }

    /** Whether the player is sprinting / moving fast (> 5 b/s). */
    public static boolean isFast() { return smoothSpeed > 5.0; }

    /** Whether the player is moving very fast (elytra / speed pots, > 15 b/s). */
    public static boolean isVeryFast() { return smoothSpeed > 15.0; }
}
