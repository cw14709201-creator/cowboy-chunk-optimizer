package dev.cowboy.chunkoptimizer.scheduler;

/**
 * Represents a pending chunk section rebuild task with a priority score.
 *
 * Implements Comparable so it can be used in a PriorityBlockingQueue.
 * Lower score = higher priority (processed first).
 */
public record RebuildTask(
    int sectionX,
    int sectionY,
    int sectionZ,
    boolean urgent,     // true = dirty from block update, bypasses queue cap
    double score        // computed by SectionPriorityScorer
) implements Comparable<RebuildTask> {

    @Override
    public int compareTo(RebuildTask other) {
        // Urgent tasks always first
        if (this.urgent && !other.urgent) return -1;
        if (!this.urgent && other.urgent) return 1;
        return Double.compare(this.score, other.score);
    }

    /** Factory: compute score from current camera state. */
    public static RebuildTask of(int sx, int sy, int sz, boolean urgent) {
        double s = SectionPriorityScorer.score(sx, sy, sz);
        // Urgent tasks get a score of 0 to ensure they sort first
        return new RebuildTask(sx, sy, sz, urgent, urgent ? 0.0 : s);
    }
}
