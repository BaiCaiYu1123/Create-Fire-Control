package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBehavior;

import java.lang.reflect.Field;
import java.util.List;

/** Optional live-add-on checks. Uses registered guns, real ammunition and live property resolvers. */
final class CannonAddonBallisticsChecks {
    private static final String MIL = "com.cainiao1053.cbcmoreshells.";
    private static int fixtureIndex;

    static void check(ServerLevel level) throws Exception {
        military(level, false);
        military(level, true);
        heavy(level, false);
        heavy(level, true);
        autocannonRounds(level);
        twinAndMedium(level);
        enhancedShells(level);
        StabilizedTurret.LOGGER.info("PASS: cannon add-on profiles: Military Supplement single/dual, CBC AT heavy/twin, Modern Warfare medium/rotary, Autogun and enhanced shells");
    }

    private static void military(ServerLevel level, boolean single) throws Exception {
        var gun = fixture(level, MIL + "cannon_control.contraption.MountedDualCannonContraption",
                single ? "cbcmoreshells:steel_single_cannon_quickfiring_breech" : "cbcmoreshells:steel_dual_cannon_quickfiring_breech",
                single ? "cbcmoreshells:steel_single_cannon_barrel" : "cbcmoreshells:steel_dual_cannon_barrel",
                Class.forName(MIL + "index.CBCMSDualCannonMaterials").getField(single ? "SINGLE_STEEL" : "STEEL").get(null));
        var round = block("cbcmoreshells:normal_ap_shot");
        var info = new StructureBlockInfo(BlockPos.ZERO,
                round.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.SOUTH), null);
        var behavior = (BigCannonBehavior) call(gun.breech, "cannonBehavior");
        behavior.loadBlock(info);
        var before = behavior.block();
        var profile = profile(level, gun);
        require(profile.projectile().equals("cbcmoreshells:normal_ap_shot"), "actual military shell identity");
        var registry = Class.forName(MIL + "munitions.dual_cannon.shaolib.CBCMSDualCannonMunitionRegistry");
        var entry = staticCall(registry, "of", round);
        var properties = staticCall(registry, "properties", entry);
        var launch = call(properties, "dualCannon");
        var physics = call(properties, "ballistics");
        close(((Number) call(launch, "initialVelocity")).doubleValue(), profile.muzzleSpeed(), "Shaolib live initial speed");
        close(((Number) call(physics, "gravity")).doubleValue(), profile.gravity(), "Shaolib live gravity");
        close(((Number) call(physics, "drag")).doubleValue(), profile.drag(), "Shaolib live drag");
        // Advance the real Shaolib motion model, independently of our solver.
        Object type = call(entry, "projectileType");
        Object instance = staticCall(Class.forName("com.verr1.shaolib.api.projectile.ShaolibProjectiles"),
                "spawnInstance", level, type, profile.muzzle(), profile.currentForward().scale(profile.muzzleSpeed()));
        Object motion = call(type, "motionModel");
        Vec3 expectedPosition = profile.muzzle(), expectedVelocity = profile.currentForward().scale(profile.muzzleSpeed());
        for (int tick = 0; tick < 12; tick++) {
            var step = CannonProjectileFlight.step(expectedPosition, expectedVelocity, profile.gravity(), profile.drag(), profile.quadraticDrag());
            call(motion, "integrate", instance, 1.0);
            require(((Vec3) call(instance, "position")).distanceTo(step.position()) < 1e-6,
                    "actual Shaolib integrator matches the predicted projectile position");
            expectedPosition = step.position(); expectedVelocity = step.velocity();
        }
        require(behavior.block().equals(before), "reading military ammo cannot consume or replace the loaded block");
        assertIntercept(profile);
        // Buffs are applied by the actual spawn path; the cue must use the same lifetime.
        set(gun.contraption, "commandLifetimeModifier", 1.4F);
        set(gun.contraption, "equipmentLifetimeModifier", 1.2F);
        advance(level);
        var buffed = profile(level, gun);
        int base = ((Number) call(launch, "baseLifetime")).intValue();
        var material = get(gun.contraption, "cannonMaterial");
        int added = ((Number) call(call(material, "properties"), "addedLifetime")).intValue();
        int expected = ((Number) call(call(entry, "launchProfile"), "resolveLifetimeTicks", base, added, 1.4F, 1.2F)).intValue();
        require(buffed.maxFlightTicks() == expected, "command/equipment/material lifetime matches spawnRound");
        require(profile.carrierVelocity().equals(Vec3.ZERO), "Shaolib firing does not invent carrier inheritance");
    }

    private static void heavy(ServerLevel level, boolean quick) throws Exception {
        var gun = fixture(level, "com.dsvv.cbcat.cannon.heavy_autocannon.contraption.MountedHeavyAutocannonContraption",
                "cbc_at:steel_heavy_autocannon_" + (quick ? "qfbreech" : "breech"),
                "cbc_at:steel_heavy_autocannon_barrel", null);
        var round = item("cbc_at:ha_ap_item");
        ItemStack magazine = null;
        if (quick) call(gun.breech, "setCartridge", round);
        else {
            // The dedicated heavy box is not a vanilla AutocannonAmmoContainerItem.
            Class<?> box = Class.forName("com.dsvv.cbcat.cannon.heavy_autocannon.munitions.box.HeavyAutocannonAmmoContainerItem");
            for (var candidate : BuiltInRegistries.ITEM) {
                if (box.isInstance(candidate)) { magazine = new ItemStack(candidate); break; }
            }
            require(magazine != null, "dedicated heavy magazine is registered");
            var component = Class.forName("rbasamoyai.createbigcannons.index.CBCDataComponents").getField("AMMO").get(null);
            @SuppressWarnings("unchecked")
            var contents = (net.minecraft.core.component.DataComponentType<ItemContainerContents>) component;
            magazine.set(contents, ItemContainerContents.fromItems(List.of(round.copyWithCount(7))));
            require(!((ItemStack) staticCall(box, "pollItemFromContainer", magazine.copy())).isEmpty(),
                    "real heavy box can select the prepared round");
            set(gun.breech, "magazine", magazine);
        }
        ItemStack before = magazine == null ? round.copy() : magazine.copy();
        var profile = profile(level, gun);
        require(!profile.projectile().contains("fallback"), "heavy breech uses exact ammo, not fallback");
        assertIntercept(profile);
        require(ItemStack.matches(before, magazine == null ? (ItemStack) get(gun.breech, "cartridge") : magazine),
                "heavy ammo inspection cannot consume rounds");
    }

    private static void autocannonRounds(ServerLevel level) throws Exception {
        var gun = fixture(level, MountedAutocannonContraption.class.getName(),
                "createbigcannons:steel_autocannon_breech", "createbigcannons:steel_autocannon_barrel", null);
        var queue = (java.util.Collection<ItemStack>) call(gun.breech, "getInputBuffer");
        for (String roundId : List.of("cbc_at:apds_item", "cbcaddon:apfsds_autocannon_round")) {
            ItemStack cartridge = item("createbigcannons:autocannon_cartridge");
            var projectileComponent = (net.minecraft.core.component.DataComponentType<ItemContainerContents>)
                    Class.forName("rbasamoyai.createbigcannons.index.CBCDataComponents").getField("PROJECTILE").get(null);
            cartridge.set(projectileComponent, ItemContainerContents.fromItems(List.of(item(roundId))));
            queue.clear(); queue.add(cartridge); advance(level);
            var profile = profile(level, gun);
            require(!profile.projectile().contains("fallback"), "addon round resolves exact projectile: " + roundId);
            assertIntercept(profile);
            if (roundId.startsWith("cbcaddon")) {
                double regular = profile.muzzleSpeed();
                var boosted = item("cbcaddon:high_velocity_autocannon_cartridge");
                boosted.set(projectileComponent, ItemContainerContents.fromItems(List.of(item(roundId))));
                queue.clear(); queue.add(boosted); advance(level);
                var highSpeed = profile(level, gun);
                close(regular * 2, highSpeed.muzzleSpeed(), "Autogun first-tick speed doubling");
                Entity fired = (Entity) call(boosted.getItem(), "getAutocannonProjectile", boosted, level);
                fired.setPos(highSpeed.muzzle().add(0, 10, 0));
                call(fired, "setLifetime", highSpeed.maxFlightTicks());
                fired.setDeltaMovement(new Vec3(0, 0, regular));
                var expected = CannonProjectileFlight.step(fired.position(), new Vec3(0, 0, regular * 2),
                        highSpeed.gravity(), highSpeed.drag(), highSpeed.quadraticDrag());
                fired.tick();
                require(fired.getDeltaMovement().distanceTo(expected.velocity()) < 1e-6,
                        "real Autogun first tick confirms the speed boost used by lead");
            }
        }
    }

    private static void twinAndMedium(ServerLevel level) throws Exception {
        var twin = fixture(level, "com.dsvv.cbcat.cannon.twin_autocannon.contraption.MountedTwinAutocannonContraption",
                "cbc_at:steel_twin_autocannon_breech", "cbc_at:steel_twin_autocannon_barrel", null);
        var queue = (java.util.Collection<ItemStack>) call(twin.breech, "getInputBuffer");
        var round = item("createbigcannons:autocannon_cartridge");
        var component = (net.minecraft.core.component.DataComponentType<ItemContainerContents>)
                Class.forName("rbasamoyai.createbigcannons.index.CBCDataComponents").getField("PROJECTILE").get(null);
        round.set(component, ItemContainerContents.fromItems(List.of(item("createbigcannons:ap_autocannon_round"))));
        queue.add(round);
        assertIntercept(profile(level, twin));
        var medium = fixture(level, "riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMediumcannonContraption",
                "cbcmodernwarfare:steel_mediumcannon_breech", "cbcmodernwarfare:steel_mediumcannon_barrel",
                Class.forName("riftyboi.cbcmodernwarfare.index.CBCModernWarfareMediumcannonMaterials").getField("STEEL").get(null));
        Class<?> ammo = Class.forName("riftyboi.cbcmodernwarfare.munitions.medium_cannon.MediumcannonRoundItem");
        ItemStack mediumRound = ItemStack.EMPTY;
        for (var candidate : BuiltInRegistries.ITEM) if (ammo.isInstance(candidate)) {
            mediumRound = new ItemStack(candidate); break;
        }
        require(!mediumRound.isEmpty(), "real medium cannon round registered");
        call(medium.breech, "setInputBuffer", mediumRound);
        var mediumProfile = profile(level, medium);
        require(!mediumProfile.projectile().contains("fallback"), "medium cannon resolves exact ammunition");
        assertIntercept(mediumProfile);
    }

    private static void enhancedShells(ServerLevel level) throws Exception {
        var gun = fixture(level, "rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption",
                "createbigcannons:steel_sliding_breech", "createbigcannons:steel_cannon_barrel",
                Class.forName("rbasamoyai.createbigcannons.index.CBCBigCannonMaterials").getField("STEEL").get(null));
        // Three actual powder charges plus an enhanced shell inside the barrel.
        for (int i = 0; i < 3; i++) {
            var behavior = (BigCannonBehavior) call(gun.contraption.presentBlockEntities.get(new BlockPos(0, 0, i)), "cannonBehavior");
            behavior.loadBlock(new StructureBlockInfo(new BlockPos(0, 0, i),
                    block("createbigcannons:powder_charge").defaultBlockState(), null));
        }
        var behavior = (BigCannonBehavior) call(gun.contraption.presentBlockEntities.get(new BlockPos(0, 0, 3)), "cannonBehavior");
        for (String id : List.of("cbc_enhanced_shells:ap_shot_mk1", "cbc_enhanced_shells:ap_shell_mk2")) {
            behavior.loadBlock(new StructureBlockInfo(new BlockPos(0, 0, 3),
                    block(id).defaultBlockState().setValue(BlockStateProperties.FACING, Direction.SOUTH), null));
            advance(level);
            var profile = profile(level, gun);
            require(profile.projectile().equals(id), "enhanced shell retains actual projectile properties");
            require(profile.propellantCount() == 3 && profile.muzzleSpeed() > 0,
                    "enhanced shell reads actual CBC propellant charge");
            assertIntercept(profile);
        }
    }

    private static CannonBallisticsCompat.ShotProfile profile(ServerLevel level, Fixture gun) {
        var profile = CannonBallisticsCompat.withCurrentFrame(CannonBallisticsCompat.analyze(level, gun.binding), level, gun.binding);
        require(profile != null, "live profile for " + gun.contraption.getClass().getSimpleName());
        StabilizedTurret.LOGGER.info("ADDON BALLISTICS {}", profile);
        return profile;
    }

    private static void assertIntercept(CannonBallisticsCompat.ShotProfile profile) {
        Vec3 target = profile.muzzle().add(8, 0, 80), velocity = new Vec3(.25, 0, -.05);
        var solution = CannonLeadSolver.solve(profile, target, velocity);
        require(solution != null, "loaded addon gun can solve a reachable moving target");
        require(CannonLeadSolver.position(profile, solution.launchDirection(), solution.flightTicks())
                        .distanceTo(target.add(velocity.scale(solution.flightTicks()))) < .026,
                "addon profile intercepts actual moving target");
    }

    private static Fixture fixture(ServerLevel level, String className, String breechId, String barrelId, Object material) throws Exception {
        BlockPos pos = new BlockPos(30 + fixtureIndex++ * 4, 180, 0);
        level.setBlockAndUpdate(pos, block("createbigcannons:cannon_mount").defaultBlockState());
        var mount = (CannonMountBlockEntity) level.getBlockEntity(pos);
        var contraption = (AbstractMountedCannonContraption) Class.forName(className).getConstructor().newInstance();
        set(contraption, "initialOrientation", Direction.SOUTH);
        set(contraption, "startPos", BlockPos.ZERO);
        BlockEntity breech = null;
        for (int z = 0; z < 5; z++) {
            BlockPos local = new BlockPos(0, 0, z);
            Block part = block(z == 0 ? breechId : barrelId);
            var state = part.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.SOUTH);
            var be = ((EntityBlock) part).newBlockEntity(local, state); be.setLevel(level);
            contraption.getBlocks().put(local, new StructureBlockInfo(local, state, null));
            contraption.presentBlockEntities.put(local, be);
            if (z == 0) {
                breech = be;
                if (material == null) material = call(part, "getAutocannonMaterial");
            }
        }
        set(contraption, "cannonMaterial", material);
        contraption.bounds = new AABB(0, 0, 0, 1, 1, 5);
        var entity = PitchOrientedContraptionEntity.create(level, contraption, Direction.SOUTH, mount);
        entity.moveTo(Vec3.atCenterOf(pos).add(0, 1, 0)); mount.attach(entity);
        return new Fixture(contraption, breech, BindingRef.capture(level, pos));
    }

    private static Block block(String id) {
        var value = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        require(!value.defaultBlockState().isAir(), "registered test block " + id); return value;
    }

    private static ItemStack item(String id) {
        var stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)));
        require(!stack.isEmpty(), "registered test item " + id); return stack;
    }

    private static void advance(ServerLevel level) { level.getServer().getWorldData().overworldData().setGameTime(level.getGameTime() + 1); }
    private static Field field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
    private static void set(Object target, String name, Object value) throws Exception { field(target, name).set(target, value); }
    private static Object get(Object target, String name) throws Exception { return field(target, name).get(target); }
    private static Object call(Object target, String name, Object... args) throws Exception { return invoke(target.getClass(), target, name, args); }
    private static Object staticCall(Class<?> type, String name, Object... args) throws Exception { return invoke(type, null, name, args); }
    private static Object invoke(Class<?> type, Object target, String name, Object[] args) throws Exception {
        for (var method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            try { method.setAccessible(true); return method.invoke(target, args); }
            catch (IllegalArgumentException ignored) {}
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
    private static void close(double expected, double actual, String label) { require(Math.abs(expected - actual) < 1e-6, label + ": " + expected + " != " + actual); }
    private static void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
    private record Fixture(AbstractMountedCannonContraption contraption, BlockEntity breech, BindingRef binding) {}
}
