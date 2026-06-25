package dev.cowboy.chunkoptimizer.mixin;

import net.minecraft.client.render.chunk.ChunkBuilder;
import org.spongepowered.asm.mixin.Mixin;

/**
 * ChunkBuilder accessor mixin.
 *
 * NOTE: All upload() injection logic lives in MixinChunkBuilderUploadLoop.
 * This class exists only to hold static accessors that other mixins need
 * without creating a circular dependency. Keeping it as a @Mixin class
 * (vs plain class) is required so the @Unique fields are injected into
 * ChunkBuilder's class space and accessible from the inner-class mixin
 * MixinChunkBuilderUploadLoop.
 *
 * BUG FIXED: Previously this class ALSO had HEAD/RETURN injections into
 * upload(), causing double PerformanceProfiler.start(UPLOAD_PHASE) calls
 * and two conflicting deadline computations per frame. Removed.
 */
@Mixin(ChunkBuilder.class)
public abstract class MixinChunkBuilder {
    // Intentionally empty — logic in MixinChunkBuilderUploadLoop
}
