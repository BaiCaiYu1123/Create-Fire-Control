package com.hooya.stabilizedturret.compat.touhoumaid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.hooya.stabilizedturret.network.MaidLoadingAnimationPayload;
import com.hooya.stabilizedturret.registry.ModSounds;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

final class MaidLoadingAnimationSync {
    private static final double TRACKING_RANGE_SQR = 96.0 * 96.0;
    private static final double BREECH_SOUND_RANGE_SQR = 16.0 * 16.0;
    private static final double VOICE_RANGE_SQR = 32.0 * 32.0;

    static void start(EntityMaid maid) {
        send(maid, true);
    }

    static void stop(EntityMaid maid) {
        send(maid, false);
    }

    static void playBreechSound(EntityMaid maid, Vec3 worldPosition, boolean opening) {
        ServerLevel root = rootLevel(maid);
        if (root == null) return;
        SoundEvent sound = opening ? SoundEvents.IRON_TRAPDOOR_OPEN
                : SoundEvents.IRON_TRAPDOOR_CLOSE;
        sendPositionalSound(root, worldPosition, sound, SoundSource.BLOCKS,
                1.0F, 1.5F, BREECH_SOUND_RANGE_SQR);
    }

    static void playCompletionVoice(EntityMaid maid) {
        ServerLevel root = rootLevel(maid);
        if (root == null) return;
        Vec3 worldPosition = Sable.HELPER.projectOutOfSubLevel(
                maid.level(), maid.position());
        sendPositionalSound(root, worldPosition, ModSounds.MAID_LOADING_COMPLETE.get(),
                SoundSource.VOICE, 1.0F, 1.0F, VOICE_RANGE_SQR);
    }

    private static void sendPositionalSound(
            ServerLevel root,
            Vec3 worldPosition,
            SoundEvent sound,
            SoundSource source,
            float volume,
            float pitch,
            double rangeSqr
    ) {
        ClientboundSoundPacket packet = new ClientboundSoundPacket(
                BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
                source,
                worldPosition.x,
                worldPosition.y,
                worldPosition.z,
                volume,
                pitch,
                root.random.nextLong());
        for (ServerPlayer player : root.getServer().getPlayerList().getPlayers()) {
            if (!player.level().dimension().equals(root.dimension())) continue;
            Vec3 playerWorld = Sable.HELPER.projectOutOfSubLevel(
                    player.level(), player.position());
            if (playerWorld.distanceToSqr(worldPosition) <= rangeSqr) {
                player.connection.send(packet);
            }
        }
    }

    private static void send(EntityMaid maid, boolean active) {
        ServerLevel root = rootLevel(maid);
        if (root == null) return;
        Vec3 maidWorld = Sable.HELPER.projectOutOfSubLevel(maid.level(), maid.position());
        MaidLoadingAnimationPayload payload = new MaidLoadingAnimationPayload(
                maid.getUUID(), active, MaidCannonLoadingBehavior.TOTAL_CYCLE_TICKS);
        for (ServerPlayer player : root.getServer().getPlayerList().getPlayers()) {
            if (!player.level().dimension().equals(root.dimension())) continue;
            Vec3 playerWorld = Sable.HELPER.projectOutOfSubLevel(player.level(), player.position());
            if (playerWorld.distanceToSqr(maidWorld) <= TRACKING_RANGE_SQR
                    && player.connection.hasChannel(MaidLoadingAnimationPayload.TYPE)) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    private static ServerLevel rootLevel(EntityMaid maid) {
        return SubLevelContainer.getContainer(maid.level()).getLevel() instanceof ServerLevel root
                ? root : null;
    }

    private MaidLoadingAnimationSync() {
    }
}
