package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.network.RemoteWeaponStationAimPayload;
import dev.ryanhcode.sable.Sable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client ownership bridge for Mianbao's native weapon-station sessions. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class MianbaoRemoteWeaponStationClientCompat {
    private static boolean active;
    private static boolean optical;
    private static boolean onSableStructure;
    private static Direction stationBaseFacing = Direction.NORTH;
    private static float worldViewYaw;
    private static float worldViewPitch;

    public static void accept(
            boolean nextActive,
            boolean nextOptical,
            BlockPos stationPos,
            int baseFacing,
            float stationYaw,
            float stationPitch
    ) {
        active = nextActive;
        optical = nextActive && nextOptical;
        Minecraft minecraft = Minecraft.getInstance();
        onSableStructure = active && minecraft.level != null
                && containingStructure(minecraft, stationPos);
        if (active) {
            Direction facing = Direction.from2DDataValue(baseFacing);
            stationBaseFacing = facing;
            if (!optical && minecraft.player != null) {
                // Direct right-click keeps the exact world-space view the
                // player had before mounting instead of snapping to the gun.
                captureWorldView(minecraft.player.getLookAngle());
            } else {
                worldViewYaw = Mth.wrapDegrees(
                        baseYaw(facing) + stationYaw + 180.0F);
                worldViewPitch = Mth.clamp(-stationPitch, -90.0F, 90.0F);
            }
            if (onSableStructure && minecraft.player != null) {
                applyView(minecraft.player);
            }
        }
    }

    /** Both native control modes need an independent mouse view on Sable craft. */
    public static boolean isControlActive() {
        // Client-side Sable containment can briefly be unavailable while a
        // structure render plot is rebuilt. Own the mouse for the complete
        // native session; the server independently verifies Sable membership.
        return active;
    }

    /** Called before vanilla can feed mouse movement into the ridden entity. */
    public static boolean turn(
            LocalPlayer player, double yawInput, double pitchInput
    ) {
        if (!isControlActive() || player == null) return false;
        worldViewYaw = Mth.wrapDegrees(
                worldViewYaw + (float) yawInput * 0.15F);
        worldViewPitch = Mth.clamp(
                worldViewPitch + (float) pitchInput * 0.15F,
                -90.0F, 90.0F);
        applyView(player);
        if (player.getVehicle() != null) {
            player.getVehicle().onPassengerTurned(player);
        }
        return true;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            active = false;
            optical = false;
            onSableStructure = false;
            return;
        }
        if (isControlActive()) {
            // Vehicle synchronization may rewrite the LocalPlayer rotation
            // during the tick. Restore the independent mouse view last.
            applyView(minecraft.player);
            PacketDistributor.sendToServer(new RemoteWeaponStationAimPayload(
                    worldViewYaw, worldViewPitch));
        }
    }

    private static boolean containingStructure(
            Minecraft minecraft, BlockPos stationPos
    ) {
        if (minecraft.level == null || stationPos == null) return false;
        try {
            var containing = Sable.HELPER.getContaining(
                    minecraft.level, stationPos);
            return containing != null && !containing.isRemoved();
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static void applyView(LocalPlayer player) {
        player.setYRot(worldViewYaw);
        player.setXRot(worldViewPitch);
        player.setYHeadRot(worldViewYaw);
        player.yBodyRot = worldViewYaw;
        player.yRotO = worldViewYaw;
        player.xRotO = worldViewPitch;
        player.yHeadRotO = worldViewYaw;
        player.yBodyRotO = worldViewYaw;
    }

    private static void captureWorldView(Vec3 direction) {
        if (direction == null || direction.lengthSqr() < 1.0e-8
                || !Double.isFinite(direction.x)
                || !Double.isFinite(direction.y)
                || !Double.isFinite(direction.z)) {
            return;
        }
        Vec3 normalized = direction.normalize();
        worldViewYaw = Mth.wrapDegrees((float) Math.toDegrees(
                Math.atan2(-normalized.x, normalized.z)));
        worldViewPitch = Mth.clamp((float) -Math.toDegrees(Math.asin(
                Mth.clamp(normalized.y, -1.0, 1.0))), -90.0F, 90.0F);
    }

    private static float baseYaw(Direction facing) {
        return switch (facing) {
            case EAST -> 90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 270.0F;
            default -> 0.0F;
        };
    }

    private MianbaoRemoteWeaponStationClientCompat() {
    }
}
