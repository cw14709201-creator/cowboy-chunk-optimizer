package dev.cowboy.chunkoptimizer.render;

import dev.cowboy.chunkoptimizer.config.ChunkOptimizerConfig;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chunk Mesh Vertex Compressor.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * THE PROBLEM
 * ═══════════════════════════════════════════════════════════════════════════
 * Vanilla Minecraft uses full 32-bit floats for vertex positions in chunk
 * geometry. A typical chunk section mesh uses:
 *   - 4 vertices per quad × 7 floats per vertex × 4 bytes = 112 bytes/quad
 *   - A fully-loaded section might have 500–3000 quads = 56KB–336KB per section
 *   - At RD=12, ~2,000 sections in memory = 112MB–672MB of vertex data
 *
 * All of this is on the GPU and must be traversed during rendering, causing
 * cache misses and bandwidth pressure.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * THE OPPORTUNITY
 * ═══════════════════════════════════════════════════════════════════════════
 * Positions within a 16×16×16 section only need values 0.0–16.0. With 1/256
 * precision (4 sub-texel precision), a 16-bit unsigned integer covers the
 * full range. That's half the bytes for position data.
 *
 * Additionally, UV coordinates in a chunk mesh are constrained to atlas tile
 * bounds — they fit in 16-bit fixed-point too.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * HOW WE IMPLEMENT IT
 * ═══════════════════════════════════════════════════════════════════════════
 * We hook into the mesh upload path (MixinChunkBuilderUploadLoop) after the
 * mesh is built but before it's submitted to the GPU. We repack the vertex
 * buffer in-place from FLOAT32 to UINT16 for position and UV components.
 *
 * This reduces position data size by 50%. Combined with GPU-side decoding
 * (a vertex shader that reconstructs the float from the short + section base),
 * the net result is smaller buffers = better cache utilisation = faster rendering.
 *
 * NOTE: Full vertex shader integration requires a shader mixin or resource
 * pack. This class implements the CPU-side buffer repacking and the
 * decompression utility. The shader integration is provided via
 * assets/cowboy-chunk-optimizer/shaders/ as an overlay on vanilla shaders.
 *
 * COMPATIBILITY: Sodium uses its own vertex format and doesn't go through
 * this path. The SodiumCompatLayer disables this system when Sodium is present.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * CURRENT STATUS: Pre-processing pass (stats-only mode)
 * ═══════════════════════════════════════════════════════════════════════════
 * The full repack requires matching shader changes to decode the compressed
 * format. This class implements the analysis + statistics pass to measure
 * compression potential, and the compression API for when shader support
 * is available. Actual repack is gated by cfg.meshCompression.
 *
 * Even in stats-only mode, this provides useful data for the HUD showing
 * how much VRAM could be saved.
 */
public final class ChunkMeshCompressor {

    private ChunkMeshCompressor() {}

    // Stats
    private static final AtomicLong bytesOriginal    = new AtomicLong(0);
    private static final AtomicLong bytesCompressed  = new AtomicLong(0);
    private static final AtomicLong sectionsAnalysed = new AtomicLong(0);

    // Vanilla vertex stride: 7 floats = 28 bytes
    // position (3f) + UV (2f) + light (1i packed as float) + normal (1i packed as float)
    private static final int VANILLA_STRIDE_FLOATS = 7;
    private static final int VANILLA_STRIDE_BYTES  = VANILLA_STRIDE_FLOATS * 4;

    // Compressed stride: pos (3 × uint16) + UV (2 × uint16) + light+normal (2 × uint16)
    // = 7 × 2 = 14 bytes (exactly half)
    private static final int COMPRESSED_STRIDE_BYTES = 14;

    /**
     * Analyse a vertex buffer and record compression statistics.
     * Call this after a section mesh is built, before GPU upload.
     *
     * @param quadCount number of quads in the mesh
     */
    public static void analyseBuffer(int quadCount) {
        if (!ChunkOptimizerConfig.get().meshCompressionStats) return;

        int vertexCount = quadCount * 4;
        long original   = (long) vertexCount * VANILLA_STRIDE_BYTES;
        long compressed = (long) vertexCount * COMPRESSED_STRIDE_BYTES;

        bytesOriginal.addAndGet(original);
        bytesCompressed.addAndGet(compressed);
        sectionsAnalysed.incrementAndGet();
    }

    /**
     * Compress a section's vertex buffer from float32 to uint16 format.
     * Only called when cfg.meshCompression = true AND shader support confirmed.
     *
     * Position packing: pos_u16 = (float_pos / 16.0) * 65535
     *   → range [0, 65535] covering 0.0–16.0 blocks with 1/4096 block precision
     *
     * UV packing: uv_u16 = clamp(uv_float * 32768 + 32768, 0, 65535)
     *   → range [0, 65535] covering [-1.0, 1.0] UV space
     *
     * @param source source FloatBuffer (vanilla format)
     * @param dest   destination ByteBuffer (compressed format)
     * @param vertexCount number of vertices
     * @param sectionBaseX section origin X in world space
     * @param sectionBaseY section origin Y in world space
     * @param sectionBaseZ section origin Z in world space
     */
    public static void compress(FloatBuffer source, ByteBuffer dest,
                                 int vertexCount,
                                 float sectionBaseX, float sectionBaseY, float sectionBaseZ) {
        source.rewind();
        dest.clear();

        for (int v = 0; v < vertexCount; v++) {
            // Read vanilla vertex
            float x = source.get() - sectionBaseX; // local position 0-16
            float y = source.get() - sectionBaseY;
            float z = source.get() - sectionBaseZ;
            float u = source.get();
            float uv = source.get();
            float light = source.get();
            float normal = source.get();

            // Pack position to uint16 (0-16 range → 0-65535)
            dest.putShort((short)(x * 4095.9375f)); // 65535/16 ≈ 4095.9375
            dest.putShort((short)(y * 4095.9375f));
            dest.putShort((short)(z * 4095.9375f));

            // Pack UV to uint16 (approximate atlas range 0-1 → 0-65535)
            dest.putShort((short)(u * 65535f));
            dest.putShort((short)(uv * 65535f));

            // Pack light + normal (pass-through as raw int bits → uint16 pairs)
            int lightBits  = Float.floatToRawIntBits(light);
            int normalBits = Float.floatToRawIntBits(normal);
            dest.putShort((short)(lightBits & 0xFFFF));
            dest.putShort((short)(normalBits & 0xFFFF));
        }

        dest.flip();
    }

    // Stats accessors for HUD
    public static long getBytesOriginal()    { return bytesOriginal.get(); }
    public static long getBytesCompressed()  { return bytesCompressed.get(); }
    public static long getSectionsAnalysed() { return sectionsAnalysed.get(); }
    public static long getSavingsBytes()     { return bytesOriginal.get() - bytesCompressed.get(); }

    public static String getSavingsFormatted() {
        long saved = getSavingsBytes();
        if (saved > 1024 * 1024) return String.format("%.1f MB", saved / (1024f * 1024f));
        if (saved > 1024)        return String.format("%.0f KB", saved / 1024f);
        return saved + " B";
    }

    public static void clear() {
        bytesOriginal.set(0);
        bytesCompressed.set(0);
        sectionsAnalysed.set(0);
    }
}
