package com.hooya.stabilizedturret.compat.mianbao;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.BindingSlot;
import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.content.controller.MianbaoRemoteWeaponStationCompat;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.registry.ModBlocks;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/** Opt-in real-server test of transformed native firing and full-speed tracer data. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class NativeGunfireIntegration {
    private static final String[] IDS = {"weaponcontrolsystem_1", "weaponcontrolsystem_2",
            "heavycannonweaponstation", "heavymachinegunstandlow", "heavymachinegunstandhigh"};
    private static final List<BindingRef> STATIONS = new ArrayList<>();
    private static final List<Entity> SHOTS = new ArrayList<>();
    private static final int[] COUNTS = new int[IDS.length];
    private static ServerLevel level;
    private static StabilizerControllerBlockEntity controller;
    private static int age;
    private static boolean finished;

    @SubscribeEvent
    public static void capture(EntityJoinLevelEvent event) {
        if (event.getLevel() == level && "autocannontanshe".equals(
                BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType()).getPath())) {
            SHOTS.add(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        level = event.getServer().overworld();
        try {
            BlockPos base = new BlockPos(0, 240, 0);
            // Track a controller without its automatic block tick: every trigger sample
            // below is explicit, so ordering is deterministic even at ServerTick.Post.
            controller = new StabilizerControllerBlockEntity(base,
                    ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
            controller.setLevel(level);
            ControllerTracker.add(controller);
            for (int index = 0; index < IDS.length; index++) {
                BlockPos pos = base.offset(4 + index * 4, 0, 0);
                level.setBlock(pos, BuiltInRegistries.BLOCK.get(id(IDS[index])).defaultBlockState(), 2);
                BindingRef ref = BindingRef.capture(level, pos);
                controller.bind(BindingSlot.REMOTE_WEAPON_STATION, ref);
                STATIONS.add(ref);
                require(ControllerTracker.isRemoteWeaponStationFireControlBound(level, pos), "fixture binding");
            }
            // Keep the heavy station on its missile selection: the gun key must still fire shells.
            Object heavy = level.getBlockEntity(STATIONS.get(2).pos());
            heavy.getClass().getMethod("loadAntiTankMissile").invoke(heavy);
            if (!(boolean) heavy.getClass().getMethod("isAntiTankMissileSelected").invoke(heavy)) {
                heavy.getClass().getMethod("cycleWeapon", int.class).invoke(heavy, 1);
            }
            require((boolean) heavy.getClass().getMethod("isAntiTankMissileSelected").invoke(heavy), "missile fixture");
            checkMounted();
            checkSableMounted();
        } catch (Throwable failure) { finish(failure); }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (level == null || finished) return;
        try {
            for (int index = 0; index < IDS.length; index++) {
                BindingRef ref = STATIONS.get(index);
                int before = SHOTS.size();
                // Also exercise false trigger release after the eight-tick burst.
                MianbaoRemoteWeaponStationCompat.tickFireControl(level, ref, age < 8);
                int added = SHOTS.size() - before;
                COUNTS[index] += added;
                if (added == 1) checkStation(SHOTS.get(before), index);
                require(added <= 1, "no duplicate shell");
                // Simulate another controller and native block tick in the same tick.
                MianbaoRemoteWeaponStationCompat.tickFireControl(level, ref, age < 8);
                Object station = level.getBlockEntity(ref.pos());
                Class<?> api = Class.forName("net.mcreator.myfirstmod.api.RemoteWeaponStationController");
                Class.forName("net.mcreator.myfirstmod.remote.RemoteWeaponStationFireService")
                        .getMethod("tick", api).invoke(null, station);
                require(SHOTS.size() == before + added, "duplicate paths must not refire");
            }
            if (++age == 9) {
                for (int index = 0; index < IDS.length; index++) {
                    require(COUNTS[index] == (index == 2 ? 2 : 3), "native cadence " + IDS[index]
                            + ": " + COUNTS[index]);
                }
                finish(null);
            }
        } catch (Throwable failure) { finish(failure); }
    }

    private static void checkStation(Entity shell, int index) throws Exception {
        boolean heavy = index == 2;
        require(shell.getPersistentData().getBoolean("MianbaoRemoteWeaponStationShell"), "native station tag");
        require(shell.getPersistentData().getBoolean("MianbaoHeavyCannonShell") == heavy, "native heavy tag");
        require(shell.getPersistentData().getBoolean("MianbaoDirectDamageWeaponStationShell") != heavy,
                "native direct/explosive behavior");
        require(!shell.getPersistentData().getBoolean(MianbaoRemoteWeaponStationCompat.REDUCED_AUTOCANNON_TAG),
                "no weakened compatibility ammunition");
        require(((AbstractArrow) shell).getBaseDamage() == (heavy ? 5.0 : 10.0), "native damage");
        require(Math.abs(shell.getDeltaMovement().length() - (heavy ? 10 : 8)) < 0.2, "native speed");
        require(shell.isNoGravity(), "native trajectory marker");
        checkTracer(shell);
    }

    private static void checkMounted() throws Exception {
        Vec3 muzzle = new Vec3(8.25, 245.75, 12.5);
        Vec3 direction = new Vec3(0.6, 0.7, -0.2).normalize();
        int before = SHOTS.size();
        require(MianbaoNativeGunfire.fireMounted(level, BuiltInRegistries.BLOCK.get(id("heavymachinegun"))
                .defaultBlockState(), muzzle, direction), "native mounted fire");
        require(SHOTS.size() == before + 1, "mounted shell count");
        Entity shell = SHOTS.get(before);
        require(shell.position().distanceToSqr(muzzle) < 1e-12, "mounted root muzzle");
        require(shell.getDeltaMovement().normalize().dot(direction) > 0.999, "mounted rotated direction");
        require(((AbstractArrow) shell).getBaseDamage() == 10.0, "native mounted damage");
        require(shell.getPersistentData().getBoolean("MianbaoDirectDamageWeaponStationShell"), "mounted native tag");
        require(MianbaoNativeGunfire.mountedFireInterval() == 3, "native mounted cadence");
        checkTracer(shell);
    }

    @SuppressWarnings("unchecked")
    private static void checkTracer(Entity shell) throws Exception {
        Class<?> arrow = Class.forName("net.mcreator.myfirstmod.entity.AutocannonProjectileArrow");
        double[] components = {shell.getDeltaMovement().x, shell.getDeltaMovement().y, shell.getDeltaMovement().z};
        String[] names = {"VELOCITY_X", "VELOCITY_Y", "VELOCITY_Z"};
        for (int i = 0; i < names.length; i++) {
            var field = arrow.getDeclaredField(names[i]);
            field.setAccessible(true);
            float synced = shell.getEntityData().get((EntityDataAccessor<Float>) field.get(null));
            require(Math.abs(synced - components[i]) < 1e-5, "full-speed native tracer " + names[i]);
        }
    }

    private static void checkSableMounted() throws Exception {
        SubLevel body = SubLevelContainer.getContainer(level).allocateNewSubLevel(new Pose3d());
        BlockPos plotCenter = body.getPlot().getCenterBlock();
        body.logicalPose().rotationPoint().set(plotCenter.getX(), plotCenter.getY(), plotCenter.getZ());
        body.logicalPose().position().set(40, 250, -20);
        body.logicalPose().orientation().rotationY(0.65).rotateZ(0.3);
        level.setBlock(plotCenter, Blocks.STONE.defaultBlockState(), 2);
        body.forceUpdateGlobalBounds();
        for (int spacing : new int[]{0, -8, -16}) {
            MountedAutocannonContraption contraption = new MountedAutocannonContraption();
            var orientation = AbstractMountedCannonContraption.class.getDeclaredField("initialOrientation");
            orientation.setAccessible(true);
            orientation.set(contraption, Direction.SOUTH);
            var start = AbstractMountedCannonContraption.class.getDeclaredField("startPos");
            start.setAccessible(true);
            start.set(contraption, BlockPos.ZERO);
            contraption.bounds = new AABB(0, 0, 0, 1, 1, 3);
            contraption.getBlocks().put(BlockPos.ZERO, new StructureBlockInfo(BlockPos.ZERO,
                    Blocks.STONE.defaultBlockState(), null));
            PitchOrientedContraptionEntity cannon = PitchOrientedContraptionEntity.create(
                    level, contraption, Direction.SOUTH, true);
            cannon.setPos(plotCenter.getX(), plotCenter.getY() + 2, plotCenter.getZ());
            cannon.xOld = cannon.xo = cannon.getX();
            cannon.yOld = cannon.yo = cannon.getY();
            cannon.zOld = cannon.zo = cannon.getZ();
            MountedHeavyMachineGunCarrier carrier = (MountedHeavyMachineGunCarrier) contraption;
            carrier.createFireControl$setMountedHeavyMachineGun(MountedHeavyMachineGunCarrier.Side.LEFT,
                    new MountedHeavyMachineGunCarrier.Attachment(BlockPos.ZERO, spacing));
            carrier.createFireControl$setMountedHeavyMachineGun(MountedHeavyMachineGunCarrier.Side.RIGHT,
                    new MountedHeavyMachineGunCarrier.Attachment(new BlockPos(0, 0, 2), spacing));
            int before = SHOTS.size();
            MountedHeavyMachineGunCompat.tickFireControl(cannon, true);
            require(SHOTS.size() == before + 2, "both assembled sides fire at spacing " + spacing);
            int index = before;
            for (MountedHeavyMachineGunCarrier.Side side : MountedHeavyMachineGunCarrier.Side.values()) {
                var attachment = carrier.createFireControl$getMountedHeavyMachineGun(side);
                Vec3 center = MountedHeavyMachineGunCompat.localGunCenter(contraption, side, attachment);
                Vec3 plotMuzzle = cannon.toGlobalVector(center.add(0, 0.42, 0.95), 0);
                Vector3d expected = body.logicalPose().transformPosition(
                        new Vector3d(plotMuzzle.x, plotMuzzle.y, plotMuzzle.z));
                Vec3 plotDirection = cannon.toGlobalVector(center.add(0, 0.01, 1), 0)
                        .subtract(cannon.toGlobalVector(center, 0));
                Vector3d expectedDirection = body.logicalPose().transformNormal(new Vector3d(
                        plotDirection.x, plotDirection.y, plotDirection.z)).normalize();
                Entity shell = SHOTS.get(index++);
                require(shell.position().distanceToSqr(new Vec3(expected.x, expected.y, expected.z)) < 1e-10,
                        "assembled Sable muzzle in root world " + side);
                require(shell.getDeltaMovement().normalize().dot(new Vec3(expectedDirection.x,
                        expectedDirection.y, expectedDirection.z)) > 0.999,
                        "assembled Sable direction " + side);
                checkTracer(shell);
            }
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", path);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void finish(Throwable failure) {
        finished = true;
        if (failure != null) StabilizedTurret.LOGGER.error("NATIVE GUNFIRE CHECK FAILED", failure);
        else StabilizedTurret.LOGGER.info("NATIVE GUNFIRE CHECK: PASS five stations, mounted pose, damage, cadence, tracer synchronization and missile isolation");
        try { Files.writeString(Path.of("gunfire-integration-result.txt"), failure == null ? "PASS" : "FAIL: " + failure); }
        catch (Exception error) { throw new RuntimeException(error); }
        finally {
            if (controller != null) ControllerTracker.remove(controller);
            for (Entity shell : SHOTS) shell.discard();
            level.getServer().halt(false);
        }
    }
}
