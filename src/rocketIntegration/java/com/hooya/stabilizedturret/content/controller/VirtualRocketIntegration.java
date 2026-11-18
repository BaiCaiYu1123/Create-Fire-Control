package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;

/** Actual native firing, first-tick wall impact, damage and 192-flight no-load salvo. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class VirtualRocketIntegration {
    private static ServerLevel level;
    private static Pig victim;
    private static int tick, phase;
    private static boolean finished;
    private static final BlockPos WALL = new BlockPos(12, 200, 8);

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        if (Boolean.getBoolean("create_fire_control.countermeasureChecks")) return;
        level = event.getServer().overworld();
        try {
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) level.getChunk(x, z);
            verifyResidentImpactNeedsNoTicket();
            BlockPos controllerPos = new BlockPos(2, 200, 2), podPos = new BlockPos(4, 200, 8);
            level.setBlock(controllerPos, ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState(), 3);
            var pod = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mianbaos_modernwarfare:rocketpod_14"))
                    .defaultBlockState();
            require(!pod.isAir(), "native loaded rocket pod missing");
            if (pod.hasProperty(BlockStateProperties.FACING)) pod = pod.setValue(BlockStateProperties.FACING, Direction.EAST);
            else pod = pod.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
            level.setBlock(podPos, pod, 3);
            for (int y = 196; y <= 204; y++) for (int z = 4; z <= 12; z++) {
                level.setBlock(new BlockPos(12, y, z), Blocks.STONE.defaultBlockState(), 3);
            }
            StabilizerControllerBlockEntity controller = (StabilizerControllerBlockEntity) level.getBlockEntity(controllerPos);
            controller.bind(BindingSlot.AIRCRAFT_ROCKET_POD, BindingRef.capture(level, podPos));
            Field selected = StabilizerControllerBlockEntity.class.getDeclaredField("selectedAircraftWeapon");
            selected.setAccessible(true); selected.set(controller, MianbaoAircraftCompat.WeaponType.ROCKET_POD);
            ControllerTracker.add(controller);
            verifyModernPods(controller);
            victim = EntityType.PIG.create(level);
            victim.setNoAi(true); victim.setNoGravity(true); victim.setPos(12.5, 200, 11.5);
            level.addFreshEntity(victim);
            require(MianbaoRocketPodCompat.fireOne(level, BindingRef.capture(level, podPos)), "native procedure did not consume one round");
            require(MianbaoRocketPodProjectileHandler.activeRocketCount() == modernShots + 1, "native shot was not virtualized");
            require(noNativeRockets(), "native rocket entity entered world tracking");
            StabilizedTurret.LOGGER.info("VIRTUAL ROCKET CHECK: native fire consumed ammunition and created only mathematical flight");
        } catch (Throwable error) { finish(error); }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (level == null || finished) return;
        try {
            level.resetEmptyTime();
            tick++;
            require(tick < 600, "integration timed out");
            if (phase == 0) {
                if (tick < 2) return;
                require(MianbaoRocketPodProjectileHandler.activeRocketCount() == 0, "near wall contact did not remove rocket");
                require(victim.isDeadOrDying(), "impact did not apply explosion damage");
                if (!level.getBlockState(WALL).isAir()) return;
                StabilizedTurret.LOGGER.info("VIRTUAL ROCKET CHECK: PASS near wall exploded, nearby pig killed, stone crater destroyed");
                for (int i = 0; i < 192; i++) {
                    Entity rocket = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(
                            "mianbaos_modernwarfare:rocketstanshewu")).create(level);
                    rocket.setPos(8.5, 2000 + i * 0.01, 8.5);
                    rocket.setDeltaMovement(10, 0, 0);
                    rocket.getPersistentData().putBoolean(MianbaoRocketPodCompat.ADOPTED_TAG, true);
                    require(!level.addFreshEntity(rocket), "virtual salvo created native world entity");
                }
                require(MianbaoRocketPodProjectileHandler.activeRocketCount() == 192, "full salvo missing");
                require(level.getChunkSource().getChunkNow(128, 0) == null, "remote flight chunk unexpectedly preloaded");
                phase = 1; tick = 0;
            } else {
                require(noNativeRockets(), "salvo added ticking projectile entities");
                require(level.getChunkSource().getChunkNow(128, 0) == null, "salvo loaded remote flight terrain");
                if (tick < 310) return;
                require(MianbaoRocketPodProjectileHandler.activeRocketCount() == 0, "virtual flights did not expire");
                StabilizedTurret.LOGGER.info("VIRTUAL ROCKET CHECK: PASS 192 simultaneous flights, zero native rocket entities, remote chunks remain unloaded, clean expiry");
                finish(null);
            }
        } catch (Throwable error) { finish(error); }
    }

    private static boolean noNativeRockets() {
        for (Entity entity : level.getAllEntities()) if (MianbaoRocketPodCompat.isRocket(entity)) {
            StabilizedTurret.LOGGER.error("Unexpected native rocket {} at {}: {}",
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), entity.position(), entity.getPersistentData());
            return false;
        }
        return true;
    }

    private static int modernShots;

    private static void verifyModernPods(StabilizerControllerBlockEntity controller) {
        for (int type = 1; type <= 3; type++) {
            ResourceLocation id = ResourceLocation.parse("mianbaos_modernwarfare:medium_rocket_pod_type_" + type);
            if (!BuiltInRegistries.BLOCK.containsKey(id)) continue; // This suite also supports 2.6.
            BlockPos pos = new BlockPos(4, 200, 8);
            require(controller.unbindDevice(BindingSlot.AIRCRAFT_ROCKET_POD, 0, pos), "Fixture did not unbind replaced pod");
            // Use the controller's original launch position so each test shot hits the existing wall.
            var state = BuiltInRegistries.BLOCK.get(id).defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
            level.setBlock(pos, state, 3);
            require(MianbaoRocketPodCompat.isPod(state), "2.7 pod is not bindable: " + id);
            var be = level.getBlockEntity(pos);
            require(be instanceof Container, "2.7 pod inventory missing: " + id);
            Container inventory = (Container) be;
            require(inventory.getMaxStackSize() == new int[]{14, 36, 9}[type - 1], "2.7 native capacity changed");
            be.getPersistentData().putDouble("rockets", 90);
            BindingRef ref = BindingRef.capture(level, pos);
            controller.bind(BindingSlot.AIRCRAFT_ROCKET_POD, ref);
            require(MianbaoRocketPodCompat.ammoCount(level, ref) == 0, "2.7 pod revived stale NBT");
            for (String round : new String[]{"rockets", "rockets_visual_guided"}) {
                inventory.setItem(0, new ItemStack(BuiltInRegistries.ITEM.get(
                        ResourceLocation.parse("mianbaos_modernwarfare:" + round)), 1));
                require(MianbaoRocketPodCompat.ammoCount(level, ref) == 1, "2.7 ammunition not counted: " + round);
                require(MianbaoRocketPodCompat.muzzleSpeed(level, ref) == (round.equals("rockets") ? 10.0 : 6.0),
                        "2.7 launch speed mismatch");
                require(MianbaoRocketPodCompat.fireOne(level, ref), "2.7 native launch failed: " + id + "/" + round);
                require(MianbaoRocketPodCompat.ammoCount(level, ref) == 0 && ref.isSameTarget(level),
                        "2.7 consumption lost inventory/binding");
                require(!MianbaoRocketPodCompat.fireOne(level, ref), "2.7 empty launcher fired");
                modernShots++;
                require(MianbaoRocketPodProjectileHandler.activeRocketCount() == modernShots,
                        "2.7 projectile entity was not virtualized");
            }
            inventory.setItem(0, new ItemStack(Items.DIRT));
            require(MianbaoRocketPodCompat.ammoCount(level, ref) == 0
                    && !MianbaoRocketPodCompat.fireOne(level, ref), "2.7 invalid round fired");
        }
        // Restore the old variant to exercise retained 2.6 IDs in the same 2.7 runtime.
        require(controller.unbindDevice(BindingSlot.AIRCRAFT_ROCKET_POD, 0, new BlockPos(4, 200, 8)),
                "Fixture did not unbind modern pod");
        var legacy = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mianbaos_modernwarfare:rocketpod_14"))
                .defaultBlockState();
        legacy = legacy.hasProperty(BlockStateProperties.FACING)
                ? legacy.setValue(BlockStateProperties.FACING, Direction.EAST)
                : legacy.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        level.setBlock(new BlockPos(4, 200, 8), legacy, 3);
        controller.bind(BindingSlot.AIRCRAFT_ROCKET_POD, BindingRef.capture(level, new BlockPos(4, 200, 8)));
        StabilizedTurret.LOGGER.info("VIRTUAL ROCKET CHECK: PASS {} real 2.7 launches, three inventory pods, HE and sight-guided ammunition, empty/invalid/stale NBT and stable binding", modernShots);
    }

    private static void verifyResidentImpactNeedsNoTicket() throws ReflectiveOperationException {
        Class<?> workType = Class.forName(MianbaoDeferredDetonation.class.getName() + "$Work");
        var constructor = workType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object work = constructor.newInstance(level, java.util.UUID.randomUUID(),
                new net.minecraft.world.phys.Vec3(8.5, 200, 8.5),
                MianbaoDeferredDetonation.PROFILE_ROCKET, level.getGameTime());
        var issue = workType.getDeclaredMethod("issueNextTicket");
        issue.setAccessible(true);
        require(Boolean.FALSE.equals(issue.invoke(work)), "resident impact issued a redundant chunk ticket");
        var tickets = workType.getDeclaredField("ticketedChunks");
        tickets.setAccessible(true);
        require(((java.util.Set<?>) tickets.get(work)).isEmpty(), "resident crater retained tickets");
        StabilizedTurret.LOGGER.info("VIRTUAL ROCKET CHECK: PASS resident impact needs zero extra chunk tickets");
    }
    private static void require(boolean value, String text) { if (!value) throw new AssertionError(text); }
    private static void finish(Throwable failure) {
        finished = true;
        if (failure != null) StabilizedTurret.LOGGER.error("VIRTUAL ROCKET CHECK FAILED", failure);
        try { Files.writeString(Path.of("virtual-rocket-integration-result.txt"), failure == null ? "PASS" : "FAIL: " + failure); }
        catch (Exception error) { throw new RuntimeException(error); }
        finally { if (level != null) level.getServer().halt(false); }
    }
}
