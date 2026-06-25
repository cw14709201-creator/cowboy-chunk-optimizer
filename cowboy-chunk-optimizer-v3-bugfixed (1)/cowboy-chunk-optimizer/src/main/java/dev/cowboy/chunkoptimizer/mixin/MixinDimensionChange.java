package dev.cowboy.chunkoptimizer.mixin;

import dev.cowboy.chunkoptimizer.config.DimensionProfileManager;
import dev.cowboy.chunkoptimizer.throttle.TranslucentSortThrottle;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks world join and dimension change packets to apply dimension profiles
 * and clear translucent sort caches.
 *
 * 1.21.1 yarn mappings:
 *   GameJoinS2CPacket#commonPlayerSpawnInfo() -> CommonPlayerSpawnInfo
 *   PlayerRespawnS2CPacket#commonPlayerSpawnInfo() -> CommonPlayerSpawnInfo
 *   CommonPlayerSpawnInfo#dimension() -> RegistryKey<World>
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class MixinDimensionChange {

    @Inject(method = "onGameJoin", at = @At("RETURN"))
    private void cco_onGameJoin(GameJoinS2CPacket packet, CallbackInfo ci) {
        try {
            RegistryKey<World> dim = packet.commonPlayerSpawnInfo().dimension();
            if (dim != null) {
                DimensionProfileManager.onDimensionChanged(dim);
            }
        } catch (Exception e) {
            dev.cowboy.chunkoptimizer.CowboyChunkOptimizerClient.LOGGER.warn(
                "[CCO] Failed to read dimension from GameJoin packet: {}", e.getMessage());
        }
        // Always clear sort cache on world join — coords changed
        TranslucentSortThrottle.clear();
    }

    @Inject(method = "onPlayerRespawn", at = @At("RETURN"))
    private void cco_onRespawn(PlayerRespawnS2CPacket packet, CallbackInfo ci) {
        try {
            RegistryKey<World> dim = packet.commonPlayerSpawnInfo().dimension();
            if (dim != null) {
                DimensionProfileManager.onDimensionChanged(dim);
                dev.cowboy.chunkoptimizer.CowboyChunkOptimizerClient.LOGGER.info(
                    "[CCO] Dimension change: {}", dim.getValue());
            }
        } catch (Exception e) {
            dev.cowboy.chunkoptimizer.CowboyChunkOptimizerClient.LOGGER.warn(
                "[CCO] Failed to read dimension from Respawn packet: {}", e.getMessage());
        }
        TranslucentSortThrottle.clear();
    }
}
