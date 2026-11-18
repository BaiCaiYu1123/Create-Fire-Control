package com.hooya.stabilizedturret.content.cannon.munition;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import com.mojang.authlib.GameProfile;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.joml.Quaterniond;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.config.CBCConfigs;
import rbasamoyai.ritchiesprojectilelib.RPLTags;
import rbasamoyai.ritchiesprojectilelib.network.neoforge.NeoForgePacket;
import riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMediumcannonContraption;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.breech.MediumcannonBreechBlockEntity;
import riftyboi.cbcmodernwarfare.index.CBCModernWarfareMediumcannonMaterials;
import riftyboi.cbcmodernwarfare.munitions.medium_cannon.MediumcannonCartridgeItem;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Real CBC/Sable server checks; no fixtures are included in the shipped JAR. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class AnnihilationIntegration {
    private static final List<String> report = new ArrayList<>();
    private static AnnihilationProjectile launched, remote;
    private static final TicketType<UUID> FIXTURE_TICKET = TicketType.create("annihilation_fixture", UUID::compareTo);
    private static final UUID FIXTURE_TICKET_ID = UUID.randomUUID();
    private static ServerLevel level;
    private static long launchedAt;
    private static boolean finished;

    @SubscribeEvent
    public static void capture(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof AnnihilationProjectile shot) launched = shot;
    }

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        try {
            level = event.getServer().overworld();
            require(ModCbcContent.ANNIHILATION_MEDIUM_CANNON_PROJECTILE.get().is(RPLTags.PRECISE_MOTION),
                    "precise motion tag missing");
            var cartridge = ModCbcContent.ANNIHILATION_MEDIUM_CANNON_CARTRIDGE.get().getDefaultInstance();
            require(MediumcannonCartridgeItem.getProjectileStack(cartridge).is(
                    ModCbcContent.ANNIHILATION_MEDIUM_CANNON_ROUND.get()), "default cartridge lacks round");
            com.hooya.stabilizedturret.content.loader.PierceEverythingLoaderChecks.check(level);
            report.add("MZ/AZ/Type 10: mixed typed cartridges, correct real chamber insertions, before/after insertion reload, legacy saves: PASS");
            checkCannon(cartridge);
            checkBlocks();
            checkBoss();
            checkSable();
            // Load terrain without an entity-ticking ticket or nearby player.
            level.getChunkSource().addRegionTicket(FIXTURE_TICKET, new ChunkPos(512, 512), 0, FIXTURE_TICKET_ID);
            level.getChunk(512, 512);
            remote = shot(new Vec3(8192.5, 200, 8192.5), new Vec3(12, 0, 0));
            require(level.addFreshEntity(remote), "remote shot spawn failed");
            require(!level.isPositionEntityTicking(remote.blockPosition()), "remote fixture unexpectedly ticks");
            require(!remote.getType().canSerialize(), "unloaded shells must never be persisted as orphans");
            launchedAt = level.getGameTime();
            report.add("Remote shell: no nearby player and no entity-ticking ticket");
        } catch (Throwable failure) { finish(failure); }
    }

    private static void checkCannon(ItemStack cartridge) throws Exception {
        Fixture fixture = cannon(new BlockPos(0, 220, 0));
        boolean oldFailure = CBCConfigs.server().failure.disableAllFailure.get();
        CBCConfigs.server().failure.disableAllFailure.set(true);
        try {
            fixture.breech.setInputBuffer(cartridge);
            launched = null;
            fixture.contraption.fireShot(level, fixture.entity);
            require(launched != null, "actual CBC medium cannon failed to fire annihilation cartridge");
            require(launched.getItem().is(ModCbcContent.APFSDS_MEDIUM_CANNON_ROUND.get()), "flight model changed");
            var ordinary = ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get().create(level);
            ordinary.shoot(0, 0, 1, 6, 0);
            var comparison = shot(Vec3.ZERO, Vec3.ZERO);
            comparison.shoot(0, 0, 1, 6, 0);
            require(ordinary.getDeltaMovement().equals(comparison.getDeltaMovement()), "APFSDS muzzle speeds differ");
            Vec3 start = launched.position(), velocity = launched.getDeltaMovement();
            launched.tick();
            require(launched.position().distanceTo(start.add(velocity.scale(.995))) < 1e-8, "flight has gravity or wrong drag");
            require(launched.getDeltaMovement().distanceTo(velocity.scale(.99)) < 1e-8, "APFSDS drag changed");
            List<Packet<?>> packets = new ArrayList<>();
            new ServerEntity(level, launched, 1, true, packets::add).sendChanges();
            require(packets.stream().anyMatch(p -> p instanceof ClientboundCustomPayloadPacket custom
                    && custom.payload() instanceof NeoForgePacket), "precise motion sync not emitted");
            require(packets.stream().noneMatch(p -> p instanceof ClientboundSetEntityMotionPacket),
                    "high speed motion was clamped");
            launched.discard();
            report.add("Actual CBC fireShot, cartridge NBT, APFSDS muzzle speed/drag, zero gravity, precise sync: PASS");
        } finally { CBCConfigs.server().failure.disableAllFailure.set(oldFailure); }
    }

    private static void checkBlocks() {
        // More than CBC's 100 impact iterations, in one tick, including indestructible blocks.
        for (int x = 20; x < 180; x++) level.setBlockAndUpdate(new BlockPos(x, 220, 10),
                (x % 2 == 0 ? Blocks.BEDROCK : Blocks.BARRIER).defaultBlockState());
        BlockPos chestPos = new BlockPos(180, 220, 10);
        level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) level.getBlockEntity(chestPos)).setItem(0, new ItemStack(Items.DIAMOND, 64));
        var shot = shot(new Vec3(19.5, 220.5, 10.5), new Vec3(163, 0, 0));
        shot.tick();
        for (int x = 20; x <= 180; x++) require(level.getBlockState(new BlockPos(x, 220, 10)).isAir(),
                "uncapped deletion stopped at " + x);
        require(level.getEntitiesOfClass(ItemEntity.class, new AABB(18, 219, 9, 183, 223, 12)).isEmpty(),
                "deleted inventory dropped items");
        require(!shot.isRemoved() && !shot.isInGround(), "penetration consumed the projectile");
        shot.discard();
        report.add("161 consecutive bedrock/barrier/chest deletions in one tick, no inventory drops: PASS");
    }

    private static void checkBoss() {
        DamageGolem ordinary = new DamageGolem(level);
        ordinary.setPos(5, 220, 21);
        ordinary.setNoAi(true);
        require(level.addFreshEntity(ordinary), "ordinary victim spawn failed");
        var regularShot = shot(new Vec3(0, 221, 21), new Vec3(12, 0, 0));
        regularShot.tick();
        require(ordinary.damageCalls == 1 && ordinary.getHealth() <= 0, "normal victim was not killed by one damage call");
        require(!ordinary.isRemoved() && level.getEntity(ordinary.getUUID()) == ordinary,
                "normal victim lost its standard death animation");
        require(!AnnihilationEntityRemoval.get(level).contains(ordinary.getUUID()), "normal kill received a deletion tombstone");
        regularShot.setPos(0, 221, 21);
        regularShot.tick();
        require(ordinary.damageCalls == 1 && !ordinary.isRemoved(), "dying corpse was damaged again or force-deleted");
        regularShot.discard();
        report.add("Ordinary mob: exactly one damage call, normal lethal damage/death animation, no forced deletion or tombstone: PASS");
        var deathLocked = new DeathLockedGolem(level);
        deathLocked.setPos(5, 220, 29);
        deathLocked.setNoAi(true);
        require(level.addFreshEntity(deathLocked), "death-locked victim spawn failed");
        var deathShot = shot(new Vec3(0, 221, 29), new Vec3(12, 0, 0));
        deathShot.tick();
        require(deathLocked.isRemoved() && AnnihilationEntityRemoval.get(level).contains(deathLocked.getUUID()),
                "zero-health boss cancelling die() must be deleted");
        deathShot.discard();
        report.add("Boss allowing damage to zero health but rejecting die(): forced removal: PASS");
        LockedGolem boss = new LockedGolem(level);
        boss.setPos(5, 220, 25);
        boss.setNoAi(true);
        require(level.addFreshEntity(boss), "locked boss spawn failed");
        var shot = shot(new Vec3(0, 221, 25), new Vec3(12, 0, 0));
        shot.tick();
        require(boss.damageSeen == AnnihilationProjectile.DAMAGE, "damage is not 2.1 billion");
        require(boss.isRemoved() && level.getEntity(boss.getUUID()) == null,
                "locked boss survived direct world removal");
        UUID erasedUuid = boss.getUUID();
        var revived = new LockedGolem(level);
        revived.setUUID(erasedUuid);
        revived.setPos(5, 220, 25);
        require(!level.addFreshEntity(revived), "erased boss rejoined with the same UUID");
        CompoundTag saved = AnnihilationEntityRemoval.get(level).save(new CompoundTag(), level.registryAccess());
        require(!saved.getList("Erased", 11).isEmpty(), "tombstones were not serialized");
        shot.discard();
        report.add("Boss rejecting hurt/setHealth/remove/projectile targeting: removed; same UUID revival denied; tombstones saved: PASS");
    }

    private static void checkSable() throws Exception {
        BlockPos anchor = new BlockPos(20, 240, 45);
        level.setBlockAndUpdate(anchor, Blocks.IRON_BLOCK.defaultBlockState());
        var assembly = SimAssemblyHelper.assembleFromSingleBlock(level, anchor.below(), anchor, false, false);
        require(assembly != null, "Sable assembly failed");
        ServerSubLevel body = (ServerSubLevel) assembly.subLevel();
        BlockPos local = anchor.offset(assembly.offset());
        // Keep an off-ray support so the test can examine the body after penetration.
        level.setBlockAndUpdate(local.above(), Blocks.IRON_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(local, Blocks.BEDROCK.defaultBlockState());
        level.setBlockAndUpdate(local.east(), Blocks.BARRIER.defaultBlockState());
        body.logicalPose().orientation().set(new Quaterniond().rotationYXZ(.7, -.3, .4));
        body.updateBoundingBox();
        body.updateLastPose();
        body.forceUpdateGlobalBounds();
        Vec3 from = body.logicalPose().transformPosition(Vec3.atCenterOf(local.west(2)));
        Vec3 to = body.logicalPose().transformPosition(Vec3.atCenterOf(local.east(3)));
        StabilizedTurret.LOGGER.info("ANNIHILATION SABLE fixture: local={} from={} to={} bounds={} worldLoaded={} plotLoaded={}",
                local, from, to, body.boundingBox(), level.hasChunkAt(BlockPos.containing(from)), level.hasChunkAt(local));
        level.getChunk(BlockPos.containing(from));
        var shot = shot(from, to.subtract(from));
        shot.tick();
        StabilizedTurret.LOGGER.info("ANNIHILATION SABLE result: shot={} ticks={} states={},{} localFrom={}",
                shot.position(), shot.tickCount, level.getBlockState(local), level.getBlockState(local.east()),
                body.logicalPose().transformPositionInverse(from));
        require(level.getBlockState(local).isAir() && level.getBlockState(local.east()).isAir(),
                "rotated Sable structure blocks survived");
        require(level.getBlockState(local.above()).is(Blocks.IRON_BLOCK), "off-ray Sable block was deleted");
        shot.discard();
        report.add("Rotated real Sable structure: bedrock/barrier deleted in plot coordinates, off-ray block retained: PASS");
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (remote == null || finished) return;
        try {
            level.resetEmptyTime();
            long elapsed = level.getGameTime() - launchedAt;
            if (elapsed < AnnihilationProjectile.LIFETIME_TICKS) {
                require(!remote.isRemoved(), "remote shot expired early");
                return;
            }
            require(remote.tickCount == 0, "fixture must reproduce a shell that never entity-ticks");
            require(remote.isRemoved() && level.getEntity(remote.getUUID()) == null,
                    "global lifetime did not clear the non-ticking shell at 200 ticks");
            report.add("Remote shell expired at server tick 200 with entity tickCount=0: PASS");
            finish(null);
        } catch (Throwable failure) { finish(failure); }
    }

    private static AnnihilationProjectile shot(Vec3 position, Vec3 velocity) {
        var shot = ModCbcContent.ANNIHILATION_MEDIUM_CANNON_PROJECTILE.get().create(level);
        shot.setPos(position);
        shot.setDeltaMovement(velocity);
        return shot;
    }

    private static void finish(Throwable failure) {
        finished = true;
        if (failure != null) StabilizedTurret.LOGGER.error("ANNIHILATION CHECK FAILED", failure);
        try {
            Files.writeString(Path.of("annihilation-integration-result.txt"), failure == null ? "PASS" : "FAIL: " + failure);
            Files.writeString(Path.of("annihilation-integration-details.txt"), String.join("\n", report));
        } catch (Exception error) { throw new RuntimeException(error); }
        finally {
            if (level != null) {
                level.getChunkSource().removeRegionTicket(FIXTURE_TICKET, new ChunkPos(512, 512), 0, FIXTURE_TICKET_ID);
                level.getServer().halt(false);
            }
        }
    }

    private static Fixture cannon(BlockPos mountPos) throws Exception {
        var mountBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("createbigcannons:cannon_mount"));
        level.setBlockAndUpdate(mountPos, mountBlock.defaultBlockState());
        var mount = (CannonMountBlockEntity) level.getBlockEntity(mountPos);
        var contraption = new MountedMediumcannonContraption();
        field(AbstractMountedCannonContraption.class, contraption, "initialOrientation", Direction.SOUTH);
        field(AbstractMountedCannonContraption.class, contraption, "startPos", BlockPos.ZERO);
        field(MountedMediumcannonContraption.class, contraption, "cannonMaterial", CBCModernWarfareMediumcannonMaterials.STEEL);
        MediumcannonBreechBlockEntity breech = null;
        for (int z = 0; z <= 3; z++) {
            BlockPos local = new BlockPos(0, 0, z);
            var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(z == 0
                    ? "cbcmodernwarfare:steel_mediumcannon_breech" : "cbcmodernwarfare:steel_mediumcannon_barrel"));
            var state = block.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.SOUTH);
            var be = ((EntityBlock) block).newBlockEntity(local, state);
            be.setLevel(level);
            contraption.getBlocks().put(local, new StructureBlockInfo(local, state, null));
            contraption.presentBlockEntities.put(local, be);
            if (z == 0) breech = (MediumcannonBreechBlockEntity) be;
        }
        contraption.bounds = new AABB(0, 0, 0, 1, 1, 4);
        var entity = PitchOrientedContraptionEntity.create(level, contraption, Direction.SOUTH, mount);
        entity.moveTo(Vec3.atCenterOf(mountPos).add(0, 1, 0));
        mount.attach(entity);
        return new Fixture(contraption, entity, breech);
    }

    private static void field(Class<?> owner, Object target, String name, Object value) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Fixture(MountedMediumcannonContraption contraption, PitchOrientedContraptionEntity entity,
                           MediumcannonBreechBlockEntity breech) {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** Simulates the hooks used by health-locked/invulnerable mod bosses. */
    private static final class LockedGolem extends IronGolem {
        private float damageSeen;
        LockedGolem(ServerLevel level) { super(EntityType.IRON_GOLEM, level); }
        @Override public boolean hurt(DamageSource source, float damage) { damageSeen = damage; return false; }
        @Override public void setHealth(float health) {}
        @Override public void remove(Entity.RemovalReason reason) {}
        @Override public boolean canBeHitByProjectile() { return false; }
    }

    private static final class DamageGolem extends IronGolem {
        private int damageCalls;
        DamageGolem(ServerLevel level) { super(EntityType.IRON_GOLEM, level); }
        @Override public boolean hurt(DamageSource source, float damage) {
            damageCalls++;
            return super.hurt(source, damage);
        }
    }

    private static final class DeathLockedGolem extends IronGolem {
        DeathLockedGolem(ServerLevel level) { super(EntityType.IRON_GOLEM, level); }
        @Override public void die(DamageSource source) {}
    }
}
