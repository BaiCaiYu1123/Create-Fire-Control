package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoAircraftCompat.WeaponType;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Registry-only bomb integration; native procedures own spawning, inertia and consumption. */
public final class MianbaoBombCompat {
    public record Rack(WeaponType type, int ammo, String procedure, double releaseSpeed) {}
    private static final Map<ResourceLocation, Rack> RACKS = racks();
    private static final Map<String, Optional<Method>> FIRE_METHODS = new ConcurrentHashMap<>();
    record Launch(Level level, BlockPos pos, BindingRef binding, WeaponType type, CompoundTag settings) {}
    private static final ThreadLocal<Launch> LAUNCH = new ThreadLocal<>();
    static @Nullable Launch currentLaunch() { return LAUNCH.get(); }

    private static final String[] ENTITY_PATHS = {"bombtanshe", "medium_bombtanshe", "clusterbombtanshe", "earthpenetratorbombtanshe",
            "fire_bombtanshe", "highresistancebombtanshe", "jdam_1tanshe", "jdam_2_tanshe", "nuclearbombtanshe",
            "white_phosphorusbombtanshe", "gasbombtanshe", "glidebombtanshe", "littlebomb"};

    public static @Nullable WeaponType entityWeapon(ResourceLocation entity) {
        if (!entity.getNamespace().equals(MianbaoAircraftCompat.MOD_ID)) return null;
        if (entity.getPath().equals("whitephosphorusbombtanshe_2")) return WeaponType.WHITE_PHOSPHORUS_BOMB;
        for (int i = 0; i < ENTITY_PATHS.length; i++) if (ENTITY_PATHS[i].equals(entity.getPath())) return WeaponType.values()[WeaponType.BOMB.ordinal() + i];
        return null;
    }

    public static ResourceLocation entityType(WeaponType type) {
        if (!isBomb(type)) throw new IllegalArgumentException("Not a bomb: " + type);
        return id(ENTITY_PATHS[type.ordinal() - WeaponType.BOMB.ordinal()]);
    }

    private static Map<ResourceLocation, Rack> racks() {
        Map<ResourceLocation, Rack> result = new HashMap<>();
        add(result, "bombrack", WeaponType.BOMB, "Bombrackgo", 0.0);
        add(result, "medium_bombrack", WeaponType.MEDIUM_BOMB, "MediumBombrackgo", 0.0);
        add(result, "cluster_bombrack", WeaponType.CLUSTER_BOMB, "ClusterBombrackgo", 0.0);
        add(result, "earthpenetratorbombrack", WeaponType.EARTH_PENETRATOR_BOMB, "EarthpenetratorBombrackgo", 0.0);
        add(result, "fire_bombrack", WeaponType.FIRE_BOMB, "FireBombrackgo", 0.0);
        add(result, "highresistancebombrack", WeaponType.RETARDED_BOMB, "Highresistancebombrackgo", 0.0);
        add(result, "jdam_1rack", WeaponType.LASER_BOMB, "Jdam1rackgo", 0.1F);
        add(result, "jdam_2rack", WeaponType.JDAM_BOMB, "Jdam2rackgo", 0.1F);
        add(result, "nuclearbombrack", WeaponType.NUCLEAR_BOMB, "Nuclear_bomb_rack_drop", 0.0);
        add(result, "whitephosphorusbombrack", WeaponType.WHITE_PHOSPHORUS_BOMB, "Whitephosphorusbombgo", 0.1F);
        add(result, "gasbombrack", WeaponType.GAS_BOMB, "Gasbombgo", 0.1F);
        add(result, "glidebombrack", WeaponType.GLIDE_BOMB, "Glide_bomb_go", 0.1F);
        add(result, "littilebombrack", WeaponType.SMALL_BOMB, "Littilebombgo", 0.1F);
        for (int count = 1; count <= 3; count++) {
            result.put(id("mixedmediumbombrack_" + count), new Rack(WeaponType.MEDIUM_BOMB, count, "Mixedmediumbombrackfire", 0.1F));
            result.put(id("mixedjdamrack_" + count), new Rack(WeaponType.LASER_BOMB, count, "Mixedjdamrackfire", 0.1F));
            result.put(id("mixedglidebomb_" + count), new Rack(WeaponType.GLIDE_BOMB, count, "Mixed_glide_bomb_rack_go", 0.1F));
        }
        return Map.copyOf(result);
    }

    private static void add(Map<ResourceLocation, Rack> result, String path, WeaponType type, String procedure, double speed) {
        result.put(id(path), new Rack(type, 1, procedure, speed));
    }

    public static Set<ResourceLocation> rackIds() {
        var result = new java.util.HashSet<>(RACKS.keySet());
        result.add(id("mixedbombrack"));
        return Set.copyOf(result);
    }

    public static boolean isBomb(@Nullable WeaponType type) {
        if (type == null) return false;
        return switch (type) {
            case BOMB, MEDIUM_BOMB, CLUSTER_BOMB, EARTH_PENETRATOR_BOMB, FIRE_BOMB,
                    RETARDED_BOMB, LASER_BOMB, JDAM_BOMB, NUCLEAR_BOMB,
                    WHITE_PHOSPHORUS_BOMB, GAS_BOMB, GLIDE_BOMB, SMALL_BOMB -> true;
            default -> false;
        };
    }

    public static @Nullable WeaponType weaponAt(ResourceLocation id) {
        Rack rack = RACKS.get(id);
        return rack == null ? null : rack.type;
    }

    public static int ammoAt(ResourceLocation id) {
        Rack rack = RACKS.get(id);
        return rack == null ? 0 : rack.ammo;
    }

    public static @Nullable Rack rack(ResourceLocation id) { return RACKS.get(id); }

    /** Some native racks (notably the small bomb rack) have no block entity. */
    static @Nullable Level rackLevel(Level level, BindingRef ref) {
        if (level == null || ref == null || !level.hasChunkAt(ref.pos()) || !ref.isSameTarget(level)) return null;
        if (ref.subLevelId() == null) return level;
        var body = SubLevelContainer.getContainer(level).getSubLevel(ref.subLevelId());
        return body == null || body.isRemoved() ? null : body.getLevel();
    }

    public static Vec3 localReleasePoint(BlockPos pos, BlockState state) {
        return new Vec3(pos.getX() + 0.5, pos.getY() - 2.0, pos.getZ() + 0.5)
                .add(Vec3.atLowerCornerOf(facing(state).getNormal()).scale(0.6));
    }

    static Direction facing(BlockState state) {
        return state.hasProperty(BlockStateProperties.FACING) ? state.getValue(BlockStateProperties.FACING)
                : state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                : state.hasProperty(BlockStateProperties.AXIS) ? Direction.fromAxisAndDirection(state.getValue(BlockStateProperties.AXIS), Direction.AxisDirection.POSITIVE)
                : Direction.NORTH;
    }

    public static @Nullable Vec3 releasePoint(Level level, BindingRef ref) {
        Level local = rackLevel(level, ref);
        return local == null ? null : Sable.HELPER.projectOutOfSubLevel(local,
                localReleasePoint(ref.pos(), local.getBlockState(ref.pos())));
    }

    public static Vec3 releaseVelocity(Level level, BindingRef ref) {
        Level local = rackLevel(level, ref);
        if (local == null) return Vec3.ZERO;
        BlockState state = local.getBlockState(ref.pos());
        Rack rack = RACKS.get(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        if (rack == null) return Vec3.ZERO;
        Vec3 point = localReleasePoint(ref.pos(), state);
        Vec3 worldPoint = Sable.HELPER.projectOutOfSubLevel(local, point);
        Vec3 forward = Sable.HELPER.projectOutOfSubLevel(local,
                point.add(Vec3.atLowerCornerOf(facing(state).getNormal()))).subtract(worldPoint).normalize();
        return forward.scale(rack.releaseSpeed).add(MianbaoAircraftCompat.carrierVelocityAtPoint(level, ref, point));
    }

    /** Only this rack's synchronous call may bypass its native redstone condition. */
    public static boolean isCommanded(Level level, BlockPos pos) {
        Launch launch = LAUNCH.get();
        return launch != null && launch.level == level && launch.pos.equals(pos);
    }

    public static boolean fireOne(Level level, BindingRef ref, WeaponType expected) {
        Level local = rackLevel(level, ref);
        if (local == null || local.isClientSide()) return false;
        Rack rack = RACKS.get(BuiltInRegistries.BLOCK.getKey(local.getBlockState(ref.pos()).getBlock()));
        if (rack == null || rack.type != expected) return false;
        if (!MianbaoBombProjectileHandler.hasLaunchCapacity(expected)) return false;
        Optional<Method> method = FIRE_METHODS.computeIfAbsent(rack.procedure, MianbaoBombCompat::findFireMethod);
        if (method.isEmpty()) return false;
        BlockEntity before = local.getBlockEntity(ref.pos());
        CompoundTag settings = before == null ? new CompoundTag() : before.getPersistentData().copy();
        Launch previous = LAUNCH.get();
        LAUNCH.set(new Launch(local, ref.pos(), ref, expected, settings));
        try (FireControlMissileCommandGuard.Scope ignored = FireControlMissileCommandGuard.enter()) {
            method.get().invoke(null, local, (double) ref.pos().getX(), (double) ref.pos().getY(), (double) ref.pos().getZ());
            int remaining = ammoAt(BuiltInRegistries.BLOCK.getKey(local.getBlockState(ref.pos()).getBlock()));
            // Three-round racks replace their block entity on every release.
            BlockEntity after = local.getBlockEntity(ref.pos());
            if (remaining > 0 && after != null) {
                for (String key : new String[]{"坐标模式", "target_x", "target_y", "target_z", "深度"}) {
                    if (settings.contains(key)) after.getPersistentData().put(key, settings.get(key).copy());
                }
                after.setChanged();
            }
            return remaining == rack.ammo - 1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            StabilizedTurret.LOGGER.error("Unable to release bomb {} at {}", expected, ref.pos(), ex);
            return false;
        } finally {
            if (previous == null) LAUNCH.remove(); else LAUNCH.set(previous);
        }
    }

    private static Optional<Method> findFireMethod(String procedure) {
        try {
            return Optional.of(Class.forName("net.mcreator.myfirstmod.procedures." + procedure + "Procedure")
                    .getMethod("execute", net.minecraft.world.level.LevelAccessor.class, double.class, double.class, double.class));
        } catch (ReflectiveOperationException | LinkageError ignored) { return Optional.empty(); }
    }

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(MianbaoAircraftCompat.MOD_ID, path); }
    private MianbaoBombCompat() {}
}
