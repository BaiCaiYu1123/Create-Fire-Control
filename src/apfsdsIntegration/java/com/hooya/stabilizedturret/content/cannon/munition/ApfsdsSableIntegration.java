package com.hooya.stabilizedturret.content.cannon.munition;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.mojang.authlib.GameProfile;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.network.bundle.PacketAndPayloadAcceptor;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.config.CBCConfigs;
import rbasamoyai.ritchiesprojectilelib.RPLTags;
import rbasamoyai.ritchiesprojectilelib.network.ClientboundPreciseMotionSyncPacket;
import rbasamoyai.ritchiesprojectilelib.network.neoforge.NeoForgePacket;
import riftyboi.cbcmodernwarfare.cannon_control.contraption.MountedMediumcannonContraption;
import riftyboi.cbcmodernwarfare.cannons.medium_cannon.breech.MediumcannonBreechBlockEntity;
import riftyboi.cbcmodernwarfare.index.CBCModernWarfareMediumcannonMaterials;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Exercises the transformed cannon, Sable launch handoff and real ServerEntity packet emission. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class ApfsdsSableIntegration {
    private static StabilizedApfsdsProjectile launched;

    @SubscribeEvent
    public static void capture(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof StabilizedApfsdsProjectile projectile) {
            launched = projectile;
        }
    }

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        Throwable failure = null;
        try {
            check(event.getServer().overworld());
        } catch (Throwable error) {
            failure = error;
            StabilizedTurret.LOGGER.error("SABLE APFSDS CHECK FAILED", error);
        }
        try {
            Files.writeString(Path.of("apfsds-integration-result.txt"), failure == null ? "PASS" : "FAIL: " + failure);
        } catch (Exception error) {
            throw new RuntimeException(error);
        } finally {
            event.getServer().halt(false);
        }
    }

    private static void check(ServerLevel level) throws Exception {
        require(ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get().is(RPLTags.PRECISE_MOTION),
                "The live APFSDS EntityType must have the precise-motion tag");
        BlockPos anchor = new BlockPos(0, 220, 0);
        level.setBlockAndUpdate(anchor, Blocks.IRON_BLOCK.defaultBlockState());
        var assembly = SimAssemblyHelper.assembleFromSingleBlock(level, anchor.below(), anchor, false, false);
        require(assembly != null, "A real Sable structure must assemble");
        ServerSubLevel body = (ServerSubLevel) assembly.subLevel();
        Vector3d bodyOrigin = new Vector3d(body.logicalPose().position());
        Fixture cannon = cannon(level, anchor.offset(assembly.offset()).above(2));
        require(Sable.HELPER.getContaining(cannon.entity) == body, "The cannon must fire from the Sable plot");
        boolean oldFailure = CBCConfigs.server().failure.disableAllFailure.get();
        CBCConfigs.server().failure.disableAllFailure.set(true);
        int shots = 0, invalidLaunchOrientation = 0, competingPositionPackets = 0, clampedSpawnVelocities = 0, precisePackets = 0;
        double maxFlightError = 0, maxVelocityError = 0;
        try {
            for (int yaw : new int[]{0, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330}) {
                for (int pitch : new int[]{-45, -15, 0, 15, 45}) {
                    for (int roll : new int[]{0, 25}) {
                        for (int moving : new int[]{0, 1}) {
                            body.logicalPose().position().set(bodyOrigin);
                            body.logicalPose().orientation().set(new Quaterniond().rotationYXZ(
                                    Math.toRadians(yaw), Math.toRadians(pitch), Math.toRadians(roll)));
                            body.latestLinearVelocity.set(moving == 0 ? 0 : 8, moving == 0 ? 0 : .5, moving == 0 ? 0 : -3);
                            body.latestAngularVelocity.set(0, moving == 0 ? 0 : .4, 0);
                            body.updateLastPose();
                            cannon.breech.setInputBuffer(ModCbcContent.APFSDS_MEDIUM_CANNON_CARTRIDGE.get().getDefaultInstance());
                            launched = null;
                            cannon.contraption.fireShot(level, cannon.entity);
                            require(launched != null, "The actual mounted medium cannon must launch APFSDS");
                            var projectile = launched;
                            Vec3 velocity = projectile.getDeltaMovement();
                            require(Sable.HELPER.getContaining(projectile) == null, "Launch position must leave the hidden plot");
                            Vec3 orientation = projectile.getOrientation();
                            if (orientation.lengthSqr() < 1e-8 || orientation.normalize().dot(velocity.normalize()) < .999999) {
                                invalidLaunchOrientation++;
                            }
                            require(velocity.length() > 3.9, "The fixture must exercise high-speed ammunition");
                            if (spawnMotionWasClamped(level, projectile, velocity)) clampedSpawnVelocities++;
                            List<Packet<?>> packets = new ArrayList<>();
                            var tracker = new ServerEntity(level, projectile, 1, true, packets::add);
                            checkPairing(level, tracker, projectile);
                            for (int tick = 0; tick < 3; tick++) {
                                if (moving != 0) {
                                    body.logicalPose().position().add(.4, .025, -.15);
                                    body.logicalPose().orientation().rotateY(.02);
                                    body.updateLastPose();
                                }
                                Vec3 from = projectile.position(), before = projectile.getDeltaMovement();
                                Vec3 acceleration = before.scale(-.01).add(0, -.03, 0);
                                projectile.tick();
                                maxFlightError = Math.max(maxFlightError,
                                        projectile.position().distanceTo(from.add(before).add(acceleration.scale(.5))));
                                maxVelocityError = Math.max(maxVelocityError,
                                        projectile.getDeltaMovement().distanceTo(before.add(acceleration)));
                                tracker.sendChanges();
                                for (Packet<?> packet : packets) {
                                    if (packet instanceof ClientboundCustomPayloadPacket custom
                                            && custom.payload() instanceof NeoForgePacket rpl
                                            && rpl.pkt() instanceof ClientboundPreciseMotionSyncPacket precise) {
                                        require(new Vec3(precise.x(), precise.y(), precise.z()).distanceTo(projectile.position()) < 1e-12,
                                                "Tracker must publish the world position before Sable changes its frame");
                                        require(new Vec3(precise.dx(), precise.dy(), precise.dz()).distanceTo(projectile.getDeltaMovement()) < 1e-12,
                                                "Tracker must publish the full current world velocity");
                                        precisePackets++;
                                    }
                                }
                                competingPositionPackets += (int) packets.stream().filter(packet ->
                                        packet instanceof ClientboundMoveEntityPacket || packet instanceof ClientboundTeleportEntityPacket
                                                || packet instanceof ClientboundSetEntityMotionPacket).count();
                                packets.clear();
                            }
                            projectile.discard();
                            shots++;
                        }
                    }
                }
            }
        } finally {
            CBCConfigs.server().failure.disableAllFailure.set(oldFailure);
        }
        String report = String.format("%d real Sable shots; invalid launch orientations=%d; competing vanilla packets=%d; clamped spawn velocities=%d; precise packets=%d; flight error=%.9f; velocity error=%.9f",
                shots, invalidLaunchOrientation, competingPositionPackets, clampedSpawnVelocities, precisePackets, maxFlightError, maxVelocityError);
        Files.writeString(Path.of("apfsds-sable-details.txt"), report);
        StabilizedTurret.LOGGER.info("SABLE APFSDS CHECK: {}", report);
        require(maxFlightError < 1e-6 && maxVelocityError < 1e-6, "Real Sable shots must follow free world-space flight: " + report);
        require(invalidLaunchOrientation == 0 && competingPositionPackets == 0 && clampedSpawnVelocities == 0,
                "Launch/spawn/tracking must share one world-space motion source: " + report);
        require(precisePackets == shots * 3, "Each actual tracking tick must send its precise motion packet: " + report);
        checkOtherAndGroundedEntities(level);
    }

    private static void checkPairing(ServerLevel level, ServerEntity tracker, StabilizedApfsdsProjectile projectile) {
        var observer = FakePlayerFactory.get(level, new GameProfile(new UUID(7, 37), "apfsds_check"));
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        tracker.sendPairingData(observer, new PacketAndPayloadAcceptor<>(packets::add));
        require(packets.stream().noneMatch(packet -> packet instanceof ClientboundSetEntityMotionPacket),
                "A new tracking client must not receive a clamped pairing velocity");
        Packet<?> last = packets.getLast();
        require(last instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof NeoForgePacket rpl
                        && rpl.pkt() instanceof ClientboundPreciseMotionSyncPacket precise
                        && new Vec3(precise.dx(), precise.dy(), precise.dz()).equals(projectile.getDeltaMovement()),
                "The spawn bundle must end with the full world motion after entity creation");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            NeoForgePacket payload = (NeoForgePacket) ((ClientboundCustomPayloadPacket) last).payload();
            NeoForgePacket.STREAM_CODEC.encode(buffer, payload);
            require(payload.equals(NeoForgePacket.STREAM_CODEC.decode(buffer)),
                    "The emitted RPL payload must round-trip through its registered network codec");
        } finally {
            buffer.release();
        }
    }

    private static void checkOtherAndGroundedEntities(ServerLevel level) {
        var snowball = EntityType.SNOWBALL.create(level);
        snowball.setPos(0, 220, 0);
        snowball.setDeltaMovement(.5, 0, .2);
        List<Packet<?>> ordinary = new ArrayList<>();
        var tracker = new ServerEntity(level, snowball, 1, true, ordinary::add);
        tracker.sendChanges();
        snowball.setPos(.5, 220, .2);
        snowball.setDeltaMovement(.6, 0, .3);
        tracker.sendChanges();
        require(ordinary.stream().anyMatch(packet -> packet instanceof ClientboundSetEntityMotionPacket),
                "Other entity types must retain vanilla synchronization");
        var grounded = ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get().create(level);
        grounded.setPos(0, 220, 0);
        grounded.setInGround(true);
        grounded.setOrientation(new Vec3(.3, .4, .5));
        grounded.setDeltaMovement(Vec3.ZERO);
        require(grounded.getOrientation().distanceTo(new Vec3(.3, .4, .5)) < 1e-7,
                "Grounded shots must retain CBC's stored contact orientation");
        grounded.lerpMotion(.2, .1, -.3);
        require(grounded.getDeltaMovement().equals(new Vec3(.2, .1, -.3)),
                "Grounded shots must retain the existing client motion updates");
        List<Packet<?>> groundPackets = new ArrayList<>();
        new ServerEntity(level, grounded, 1, true, groundPackets::add).sendChanges();
        require(groundPackets.stream().noneMatch(packet -> packet instanceof ClientboundCustomPayloadPacket custom
                        && custom.payload() instanceof NeoForgePacket),
                "Grounded shots must retain the existing Sable attachment synchronization");
    }

    private static boolean spawnMotionWasClamped(ServerLevel level, StabilizedApfsdsProjectile projectile, Vec3 velocity) {
        var copy = ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get().create(level);
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            projectile.baseWriteSpawnData(buf);
            copy.baseReadSpawnData(buf);
        } finally {
            buf.release();
        }
        require(copy.getDeltaMovement().distanceTo(velocity) < 1e-12, "CBC complex spawn data must preserve world velocity");
        var vanilla = new ClientboundSetEntityMotionPacket(projectile.getId(), velocity);
        copy.lerpMotion(vanilla.getXa(), vanilla.getYa(), vanilla.getZa());
        return copy.getDeltaMovement().distanceTo(velocity) > 1e-12;
    }

    private static Fixture cannon(ServerLevel level, BlockPos mountPos) throws Exception {
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Fixture(MountedMediumcannonContraption contraption, PitchOrientedContraptionEntity entity,
                           MediumcannonBreechBlockEntity breech) {}
}
