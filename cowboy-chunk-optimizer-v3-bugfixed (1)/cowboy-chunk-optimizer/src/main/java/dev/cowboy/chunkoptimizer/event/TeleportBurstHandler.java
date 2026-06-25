package dev.cowboy.chunkoptimizer.event;

import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;
import dev.cowboy.chunkoptimizer.render.ChunkFadeSystem;
import dev.cowboy.chunkoptimizer.memory.MeshPool;

/**
 * Detects world joins, /tp commands, and dimension changes, then temporarily
 * expands the upload budget to clear the initial burst of pending chunks fast.
 *
 * Problem: When you join a world or teleport, hundreds of sections hit the
 * build queue simultaneously. With a strict 5ms budget the world takes 10+
 * seconds to fully load around the player. Better to allow a 2–3 second
 * "burst window" with a higher budget so the world loads quickly, then
 * return to the smooth-frame-preserving budget.
 *
 * Burst detection heuristics:
 *  1. Position delta > TELEPORT_THRESHOLD blocks in a single tick
 *  2. WorldRenderer.reload() call (F3+A or /reload)
 *  3. Dimension change (world reference changes)
 *
 * State machine:
 *  IDLE → BURST (on detection) → COOLING (queue draining) → IDLE
 */
public final class TeleportBurstHandler {

    private TeleportBurstHandler() {}

    public enum State { IDLE, BURST, COOLING }

    private static volatile State state = State.IDLE;

    // Burst starts at burstBudgetMs, decays toward normal over burstDurationTicks
    private static volatile long burstEndTick = 0;
    private static volatile long currentTick   = 0;

    // Position snapshot for teleport detection
    private static volatile double lastX = Double.NaN;
    private static volatile double lastZ = Double.NaN;

    private static final double TELEPORT_THRESHOLD   = 40.0; // blocks/tick = 800 b/s
    private static final int    BURST_DURATION_TICKS = 120;  // 6 seconds at 20tps
    private static final int    COOL_DURATION_TICKS  =  60;  // 3 seconds cooldown

    /**
     * Call from ClientTickEvents.END_CLIENT_TICK each tick.
     * @param px player X, py player Y, pz player Z
     * @param pendingBuilds current length of the rebuild queue
     */
    public static void tick(double px, double py, double pz, int pendingBuilds) {
        currentTick++;

        // ── Teleport detection ──────────────────────────────────────────────
        if (!Double.isNaN(lastX)) {
            double dx = px - lastX;
            double dz = pz - lastZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > TELEPORT_THRESHOLD) {
                triggerBurst("teleport (dist=" + (int)dist + " blocks)");
            }
        }
        lastX = px;
        lastZ = pz;

        // ── State transitions ───────────────────────────────────────────────
        if (state == State.BURST && currentTick > burstEndTick) {
            state = State.COOLING;
        }
        if (state == State.COOLING && pendingBuilds <= 4) {
            state = State.IDLE;
        }
    }

    /**
     * Triggered by WorldRenderer reload (F3+A, /reload, dimension change).
     */
    public static void onWorldRendererReload() {
        triggerBurst("WorldRenderer reload");
        ChunkFadeSystem.clear();
        MeshPool.clear();
    }

    /**
     * Triggered on world join / server switch.
     */
    public static void onWorldLoad() {
        triggerBurst("world load");
        lastX = Double.NaN;
        lastZ = Double.NaN;
        ChunkFadeSystem.clear();
        MeshPool.clear();
    }

    private static void triggerBurst(String reason) {
        if (state == State.BURST) return; // already in burst
        state = State.BURST;
        burstEndTick = currentTick + BURST_DURATION_TICKS;
        dev.cowboy.chunkoptimizer.CowboyChunkOptimizerClient.LOGGER.info(
            "[CCO] Burst mode activated ({}). Budget expanded for {} ticks.",
            reason, BURST_DURATION_TICKS
        );
    }

    /**
     * Get the effective upload budget multiplier for the current state.
     * AdaptiveBudgetController multiplies its computed budget by this.
     *
     * IDLE    → 1.0  (normal operation)
     * BURST   → 3.0  (load the world fast)
     * COOLING → 1.5  (step down gently)
     */
    public static float getBudgetMultiplier() {
        return switch (state) {
            case BURST   -> ChunkOptimizerConfig.get().burstBudgetMultiplier;
            case COOLING -> Math.max(1.0f, ChunkOptimizerConfig.get().burstBudgetMultiplier * 0.5f);
            default      -> 1.0f;
        };
    }

    public static State getState() { return state; }
    public static long  getCurrentTick() { return currentTick; }

    /**
     * How many ticks remain in burst mode (0 if not in burst).
     */
    public static long getBurstTicksRemaining() {
        if (state != State.BURST) return 0;
        return Math.max(0, burstEndTick - currentTick);
    }
}
