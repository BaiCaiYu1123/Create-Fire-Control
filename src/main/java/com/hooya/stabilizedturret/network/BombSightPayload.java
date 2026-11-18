package com.hooya.stabilizedturret.network;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientPayloadHandlers;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType;
import com.hooya.stabilizedturret.content.controller.MianbaoBombBallistics;
import com.hooya.stabilizedturret.content.controller.MianbaoBombCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Independent of radar power: every bound-seat operator receives their selected bomb. */
public record BombSightPayload(int selectedWeapon, int selectedAmmo, int totalAmmo,
                               @Nullable MianbaoBombBallistics.Flight flight, @Nullable Vec3 impact) implements CustomPacketPayload {
    public static final Type<BombSightPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID, "bomb_sight"));
    private static final int MAX_LASERS = 32;
    public static final StreamCodec<RegistryFriendlyByteBuf, BombSightPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarInt(payload.selectedWeapon);
                buffer.writeVarInt(payload.selectedAmmo);
                buffer.writeVarInt(payload.totalAmmo);
                buffer.writeBoolean(payload.flight != null);
                if (payload.flight != null) {
                    writeVec(buffer, payload.flight.origin());
                    writeVec(buffer, payload.flight.velocity());
                    buffer.writeBoolean(payload.flight.coordinates());
                    writeVec(buffer, payload.flight.target());
                    buffer.writeVarInt(Math.min(MAX_LASERS, payload.flight.lasers().size()));
                    for (int i = 0; i < Math.min(MAX_LASERS, payload.flight.lasers().size()); i++) writeVec(buffer, payload.flight.lasers().get(i));
                }
                buffer.writeBoolean(payload.impact != null);
                if (payload.impact != null) writeVec(buffer, payload.impact);
            }, buffer -> {
                int selected = buffer.readVarInt(), ammo = buffer.readVarInt(), total = buffer.readVarInt();
                MianbaoBombBallistics.Flight flight = null;
                if (buffer.readBoolean()) {
                    Vec3 origin = readVec(buffer), velocity = readVec(buffer);
                    boolean coordinates = buffer.readBoolean();
                    Vec3 target = readVec(buffer);
                    int count = buffer.readVarInt();
                    if (count < 0 || count > MAX_LASERS) throw new IllegalArgumentException("Invalid bomb laser count " + count);
                    List<Vec3> lasers = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) lasers.add(readVec(buffer));
                    if (selected >= 0 && selected < WeaponType.values().length && MianbaoBombCompat.isBomb(WeaponType.values()[selected])) {
                        flight = new MianbaoBombBallistics.Flight(WeaponType.values()[selected], origin, velocity, coordinates, target, lasers);
                    }
                }
                Vec3 impact = buffer.readBoolean() ? readVec(buffer) : null;
                return new BombSightPayload(selected, Math.max(0, ammo), Math.max(0, total), flight, impact);
            });

    public static BombSightPayload inactive() { return new BombSightPayload(-1, 0, 0, null, null); }

    public static BombSightPayload from(StabilizerControllerBlockEntity controller, Set<UUID> ignored) {
        WeaponType type = controller.getSelectedAircraftWeapon();
        int ammo = controller.getAircraftAmmoCount(type), total = 0;
        for (WeaponType candidate : WeaponType.values()) if (MianbaoBombCompat.isBomb(candidate)) total += controller.getAircraftAmmoCount(candidate);
        var level = controller.getLevel();
        var rack = controller.getNextAircraftBombRack();
        if (level == null || rack == null || !(SubLevelContainer.getContainer(level).getLevel() instanceof ServerLevel root)) {
            return new BombSightPayload(type.ordinal(), ammo, total, null, null);
        }
        Vec3 origin = MianbaoBombCompat.releasePoint(level, rack);
        Vec3 velocity = MianbaoBombCompat.releaseVelocity(level, rack);
        if (!MianbaoBombBallistics.finite(origin) || !MianbaoBombBallistics.finite(velocity)) return new BombSightPayload(type.ordinal(), ammo, total, null, null);
        var be = level.getBlockEntity(rack.pos());
        var data = be == null ? new net.minecraft.nbt.CompoundTag() : be.getPersistentData();
        Vec3 target = new Vec3(data.getDouble("target_x"), data.getDouble("target_y"), data.getDouble("target_z"));
        List<Vec3> lasers = new ArrayList<>();
        if (type == WeaponType.LASER_BOMB || type == WeaponType.JDAM_BOMB) {
            for (var entity : root.getAllEntities()) {
                if (entity.isAlive() && BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).equals(
                        ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", "laserpoint_4"))) lasers.add(entity.position());
            }
            lasers.sort(Comparator.comparingDouble(origin::distanceToSqr));
            if (lasers.size() > MAX_LASERS) lasers = new ArrayList<>(lasers.subList(0, MAX_LASERS));
        }
        var flight = new MianbaoBombBallistics.Flight(type, origin, velocity, data.getBoolean("坐标模式"), target, lasers);
        return new BombSightPayload(type.ordinal(), ammo, total, flight, MianbaoBombBallistics.predict(root, flight, ignored));
    }

    private static void writeVec(RegistryFriendlyByteBuf buffer, Vec3 point) { buffer.writeDouble(point.x); buffer.writeDouble(point.y); buffer.writeDouble(point.z); }
    private static Vec3 readVec(RegistryFriendlyByteBuf buffer) { return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(BombSightPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT) context.enqueueWork(() -> ClientPayloadHandlers.handleBombSight(payload));
    }
}
