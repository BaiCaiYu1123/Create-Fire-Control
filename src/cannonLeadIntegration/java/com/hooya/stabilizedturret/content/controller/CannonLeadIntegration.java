package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.hooya.stabilizedturret.network.TargetLockTelemetry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedRotarycannonContraption;
import riftyboi.cbcmodernwarfare.cannons.rotarycannon.breech.AbstractRotarycannonBreechBlockEntity;
import riftyboi.cbcmodernwarfare.index.CBCModernWarfareRotarycannonMaterials;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Set;

/** Real CBC material, creative magazine, controller and lead solver; no user world is loaded. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class CannonLeadIntegration {
    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        String result;
        try {
            OpticalLockPerformanceChecks.check(event.getServer().overworld());
            check(event.getServer().overworld());
            if (Boolean.getBoolean("create_fire_control.cannonAddonChecks"))
                CannonAddonBallisticsChecks.check(event.getServer().overworld());
            result = "PASS: non-loading optical visibility, physical occlusion, live rotary cannon, creative tracer magazine and optical/ground radar lead";
        } catch (Throwable error) {
            result = "FAIL: " + error;
            StabilizedTurret.LOGGER.error("CANNON LEAD CHECK FAILED", error);
        }
        try { Files.writeString(Path.of("cannon-lead-integration-result.txt"), result); }
        catch (Exception error) { throw new RuntimeException(error); }
        finally { event.getServer().halt(false); }
    }

    private static void check(ServerLevel level) throws Exception {
        BlockPos pos = new BlockPos(0, 180, 0);
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("createbigcannons:cannon_mount"));
        level.setBlockAndUpdate(pos, block.defaultBlockState());
        var mount = (CannonMountBlockEntity) level.getBlockEntity(pos);
        var contraption = new MountedRotarycannonContraption();
        field(AbstractMountedCannonContraption.class, contraption, "initialOrientation", Direction.SOUTH);
        field(AbstractMountedCannonContraption.class, contraption, "startPos", BlockPos.ZERO);
        field(MountedRotarycannonContraption.class, contraption, "cannonMaterial", CBCModernWarfareRotarycannonMaterials.STEEL);
        AbstractRotarycannonBreechBlockEntity breech = null;
        for (int z = 0; z <= 4; z++) {
            BlockPos local = new BlockPos(0, 0, z);
            var part = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("cbcmodernwarfare:steel_"
                    + (z == 0 ? "rotarycannon_breech" : z == 1 ? "rotarycannon_bearing" : "rotarycannon_barrel")));
            var state = part.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.SOUTH);
            var be = ((EntityBlock) part).newBlockEntity(local, state);
            be.setLevel(level);
            contraption.getBlocks().put(local, new StructureBlockInfo(local, state, null));
            contraption.presentBlockEntities.put(local, be);
            if (z == 0) breech = (AbstractRotarycannonBreechBlockEntity) be;
        }
        contraption.bounds = new AABB(0, 0, 0, 1, 1, 5);
        var entity = PitchOrientedContraptionEntity.create(level, contraption, Direction.SOUTH, mount);
        entity.moveTo(Vec3.atCenterOf(pos).add(0, 1, 0));
        mount.attach(entity);
        // The actual setup uses a creative magazine with only the tracer lane populated.
        var magazine = ItemStack.parse(level.registryAccess(), TagParser.parseTag("""
                {id:"createbigcannons:creative_autocannon_ammo_container",count:1,components:{
                  "createbigcannons:current_index":1,"createbigcannons:tracer_spacing":1,
                  "createbigcannons:tracers":[{slot:0,item:{id:"createbigcannons:autocannon_cartridge",count:1,components:{
                    "createbigcannons:projectile":[{slot:0,item:{id:"createbigcannons:flak_autocannon_round",count:1,
                      components:{"createbigcannons:autocannon_tracer":1}}}]}}}]}}
                """)).orElseThrow();
        breech.setMagazine(magazine);
        ItemStack magazineBefore = magazine.copy();
        BindingRef ref = BindingRef.capture(level, pos);
        var profile = CannonBallisticsCompat.withCurrentFrame(CannonBallisticsCompat.analyze(level, ref), level, ref);
        StabilizedTurret.LOGGER.info("LEAD CHECK profile={} muzzle={}", profile, CannonBallisticsCompat.getCurrentMuzzlePosition(level, ref));
        require(profile != null, "assembled loaded rotary cannon supplies a profile");
        require(profile.projectile().contains("flak_autocannon"), "profile must read the real magazine round, not generic fallback: " + profile.projectile());
        require(ItemStack.matches(magazineBefore, magazine), "ballistics must not consume a round or advance the magazine");
        UUID targetId = UUID.randomUUID();
        Vec3[] targetPoint = {new Vec3(30, 180, 200)};
        Vec3 velocity = new Vec3(.4, 0, 0);
        boolean[] opticalActive = {true};
        FireControlWeaponInputs.GroundWeapon[] selected = {null};
        var controller = new StabilizerControllerBlockEntity(pos.east(8), ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState()) {
            @Override public boolean hasOpticalLock() { return opticalActive[0]; }
            @Override public Vec3 getOpticalLockTarget() { return targetPoint[0]; }
            @Override public UUID getOpticalLockTargetId() { return targetId; }
            @Override public TargetLockTelemetry getOpticalLockTelemetry() {
                return new TargetLockTelemetry(velocity, 200, 0, 0);
            }
            @Override public boolean isAircraftRadarMode() { return false; }
            @Override public boolean isRadarActive() { return true; }
            @Override public UUID getRadarLockedSubLevelId() { return targetId; }
            @Override public RadarContact getRadarContact(UUID id) {
                return new RadarContact(targetId, targetPoint[0], velocity,
                        new AABB(targetPoint[0].subtract(1, 1, 1), targetPoint[0].add(1, 1, 1)), Set.of(targetId), false);
            }
            @Override public Vec3 getLockTargetVelocity(UUID id, Vec3 point) { return velocity; }
            @Override long getRadarContactSampleTick() { return level.getGameTime(); }
            @Override public FireControlWeaponInputs.GroundWeapon getSelectedGroundWeapon() { return selected[0]; }
        };
        controller.setLevel(level);
        controller.bind(BindingSlot.CANNON_MOUNT, ref);
        require(controller.getPrimaryLockLeadCannon() != null, "controller finds assembled cannon");
        var optical = CannonLockLeadTracker.snapshot(controller).optical();
        StabilizedTurret.LOGGER.info("LEAD CHECK opticalTarget={} optical={} directSolution={}",
                controller.getOpticalLockTarget(), optical,
                CannonLeadSolver.solve(profile, new Vec3(30, 180, 200), new Vec3(.4, 0, 0)));
        require(optical != null && optical.aimPoint() != null, "optical lock sends a finite lead point");
        require(CannonLockLeadTracker.snapshotForView(controller, null).optical() != null,
                "remote optics without a seated controller still receive optical lead");
        require(CannonLockLeadTracker.snapshotForView(null, controller).optical() == null,
                "an inactive view cannot show a lock from a different controller");
        require(CannonLockLeadTracker.snapshotForView(controller, controller).radar().aimPoint() != null,
                "complete ground radar snapshot sends lead point");
        Vec3 firstLead = optical.aimPoint();
        for (int tick = 0; tick < 100; tick++) {
            level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime() + 1);
            targetPoint[0] = targetPoint[0].add(velocity);
            var live = CannonLockLeadTracker.snapshotForView(controller, controller);
            require(live.optical().aimPoint() != null && live.radar().aimPoint() != null,
                    "both cues remain available throughout sustained magazine-fed tracking");
        }
        require(firstLead.distanceTo(CannonLockLeadTracker.snapshot(controller).optical().aimPoint()) > 30,
                "cue is recomputed continuously instead of retaining the first solution");
        require(ItemStack.matches(magazineBefore, magazine), "sustained tracking cannot mutate the real magazine");
        opticalActive[0] = false;
        selected[0] = FireControlWeaponInputs.GroundWeapon.AIR_DEFENSE;
        level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime() + 1);
        var aa = CannonLockLeadTracker.snapshot(controller);
        require(aa.radar() != null && aa.radar().aimPoint() != null && aa.radar().cameraTracking(),
                "ordinary AA selection retains assembled-cannon lead and camera tracking");
        selected[0] = FireControlWeaponInputs.GroundWeapon.HIGH_SPEED_AIR_DEFENSE;
        level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime() + 1);
        var fastAa = CannonLockLeadTracker.snapshot(controller).radar();
        require(fastAa != null && fastAa.aimPoint() != null && !fastAa.cameraTracking(),
                "high-speed AA retains cannon lead but must not enable ordinary AA camera tracking");
    }

    private static void field(Class<?> owner, Object target, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
