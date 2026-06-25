package dev.cowboy.chunkoptimizer.hud;

/**
 * Fixed-size ring buffer for storing per-frame timing values.
 * Used by the HUD overlay to draw sparkline graphs.
 *
 * Thread-safe for single-writer / single-reader (write from render thread,
 * read from HUD render callback on same thread).
 *
 * Storage is a primitive float[] for zero allocation on the hot path.
 */
public final class FrameTimeRingBuffer {

    private final float[] data;
    private final int     capacity;
    private int           writePos = 0;
    private int           count    = 0;

    public FrameTimeRingBuffer(int capacity) {
        this.capacity = capacity;
        this.data     = new float[capacity];
    }

    /** Append a value. Overwrites the oldest entry when full. */
    public void push(float value) {
        data[writePos] = value;
        writePos = (writePos + 1) % capacity;
        if (count < capacity) count++;
    }

    /** Get the value at logical index i (0 = oldest, count-1 = newest). */
    public float get(int i) {
        if (i < 0 || i >= count) return 0f;
        int realIdx = (writePos - count + i + capacity) % capacity;
        return data[realIdx];
    }

    /** Get the most recently pushed value. */
    public float latest() {
        if (count == 0) return 0f;
        return data[(writePos - 1 + capacity) % capacity];
    }

    /** Number of valid entries (up to capacity). */
    public int size() { return count; }

    /** Max value in the buffer (for sparkline scaling). */
    public float max() {
        float m = 0;
        for (int i = 0; i < count; i++) m = Math.max(m, get(i));
        return m;
    }

    /** Average of all valid entries. */
    public float average() {
        if (count == 0) return 0f;
        float sum = 0;
        for (int i = 0; i < count; i++) sum += get(i);
        return sum / count;
    }

    public void clear() {
        writePos = 0;
        count = 0;
    }
}
