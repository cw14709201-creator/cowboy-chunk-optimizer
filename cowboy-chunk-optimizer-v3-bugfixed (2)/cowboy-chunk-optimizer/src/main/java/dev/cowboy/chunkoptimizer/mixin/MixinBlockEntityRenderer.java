package dev.cowboy.chunkoptimizer.mixin;

import dev.cowboy.chunkoptimizer.render.BlockEntityCuller;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block entity render dispatch culling.
 *
 * TARGET: BlockEntityRenderDispatcher#render(BlockEntity, float, MatrixStack,
 *         VertexConsumerProvider, int, int)
 *
 * In 1.21.1 the full signature is:
 *   render(E entity, float tickDelta, MatrixStack matrices,
 *          VertexConsumerProvider vertexConsumers, int light, int overlay)
 *
 * The light and overlay ints are passed down from WorldRenderer and required
 * for correct block entity shading. We do not use them, just forward them.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class MixinBlockEntityRenderer {

    @Inject(
        method = "render(Lnet/minecraft/block/entity/BlockEntity;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;II)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private <E extends BlockEntity> void cco_cullBlockEntity(
            E blockEntity,
            float tickDelta,
            MatrixStack matrices,
            VertexConsumerProvider vertexConsumers,
            int light,
            int overlay,
            CallbackInfo ci) {

        BlockPos pos = blockEntity.getPos();
        if (BlockEntityCuller.shouldCull(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
            ci.cancel();
        }
    }
}
