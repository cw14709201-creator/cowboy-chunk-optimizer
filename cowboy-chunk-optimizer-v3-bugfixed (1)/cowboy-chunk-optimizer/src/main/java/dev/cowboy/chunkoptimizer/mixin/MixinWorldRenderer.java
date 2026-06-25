package dev.cowboy.chunkoptimizer.mixin;

import dev.cowboy.chunkoptimizer.CowboyChunkOptimizerClient;
import dev.cowboy.chunkoptimizer.render.ChunkFadeSystem;
import dev.cowboy.chunkoptimizer.render.RenderSectionCuller;
import dev.cowboy.chunkoptimizer.scheduler.SectionPriorityScorer;
import dev.cowboy.chunkoptimizer.throttle.TranslucentSortThrottle;
import dev.cowboy.chunkoptimizer.render.ChunkSectionFrustumCache;
import dev.cowboy.chunkoptimizer.util.AdaptiveBudgetController;
import dev.cowboy.chunkoptimizer.util.StartupBenchmark;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class MixinWorldRenderer {

    @Inject(method = "render", at = @At("HEAD"))
    private void cco_onRenderStart(CallbackInfo ci) {
        AdaptiveBudgetController.onFrameStart();
        ChunkSectionFrustumCache.newFrame();
        // Trigger startup benchmark (runs for first ~50 frames after world join)
        StartupBenchmark.onRenderFrame();
    }

    @Inject(method = "setupFrustum", at = @At("RETURN"))
    private void cco_onFrustumSetup(Camera camera, CallbackInfo ci) {
        Vec3d pos = camera.getPos();
        double yawRad   = Math.toRadians(camera.getYaw());
        double pitchRad = Math.toRadians(camera.getPitch());
        double fwdX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double fwdY = -Math.sin(pitchRad);
        double fwdZ =  Math.cos(yawRad) * Math.cos(pitchRad);

        SectionPriorityScorer.updateCamera(
            pos.x, pos.y, pos.z, camera.getYaw(), camera.getPitch());
        RenderSectionCuller.updateCamera(pos.x, pos.y, pos.z, fwdX, fwdY, fwdZ);
        TranslucentSortThrottle.updateCamera(pos.x, pos.y, pos.z);
    }

    @Inject(method = "reload", at = @At("HEAD"))
    private void cco_onReload(CallbackInfo ci) {
        CowboyChunkOptimizerClient.onWorldRendererReload();
        RenderSectionCuller.clear();
        TranslucentSortThrottle.clear();
    }

    private static int cco_cleanupCounter = 0;

    @Inject(method = "render", at = @At("RETURN"))
    private void cco_onRenderEnd(CallbackInfo ci) {
        if (++cco_cleanupCounter >= 20) {
            cco_cleanupCounter = 0;
            ChunkFadeSystem.cleanup();
        }
    }
}
