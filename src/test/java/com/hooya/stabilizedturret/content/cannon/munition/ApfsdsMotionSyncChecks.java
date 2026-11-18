package com.hooya.stabilizedturret.content.cannon.munition;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import rbasamoyai.ritchiesprojectilelib.RPLTags;
import rbasamoyai.ritchiesprojectilelib.network.ClientboundPreciseMotionSyncPacket;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Loads the shipped tags through Minecraft and exercises the actual motion packet codecs. */
public final class ApfsdsMotionSyncChecks {
    public static void main(String[] args) {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        checkPreciseMotionTag(Path.of(args[0]), Path.of(args[1]));
        checkHighSpeedPackets();
    }

    private static void checkPreciseMotionTag(Path modResources, Path cbcResources) {
        TagLoader<ResourceLocation> loader = new TagLoader<>(Optional::of,
                Registries.tagsDirPath(Registries.ENTITY_TYPE));
        ResourceLocation projectile = ResourceLocation.parse("create_fire_control:apfsds_medium_shell");
        ResourceLocation cbcShot = ResourceLocation.parse("createbigcannons:shot");
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(pack("cbc", cbcResources), pack("fire_control", modResources)))) {
            Collection<ResourceLocation> precise = loader.loadAndBuild(resources)
                    .get(RPLTags.PRECISE_MOTION.location());
            require(precise != null && precise.contains(projectile),
                    "APFSDS must load into RPL's precise_motion tag through the current entity registry directory");
            require(precise.contains(cbcShot), "The APFSDS tag must preserve CBC's existing precise-motion projectiles");
        }
    }

    private static PackResources pack(String name, Path path) {
        var location = new PackLocationInfo(name, Component.literal(name), PackSource.DEFAULT, Optional.empty());
        return Files.isDirectory(path) ? new PathPackResources(location, path)
                : new FilePackResources(location, new FilePackResources.SharedZipFileAccess(path.toFile()), "");
    }

    private static void checkHighSpeedPackets() {
        int cases = 0;
        double worstVanillaAngle = 0;
        double worstVanillaSpeedLoss = 0;
        for (double speed : new double[]{6, 12, 24}) {
            for (int yaw = -180; yaw < 180; yaw += 15) {
                for (int pitch : new int[]{-89, -45, -15, -1, 0, 1, 15, 45, 89}) {
                    double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
                    Vec3 direction = new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
                    for (Vec3 carrier : new Vec3[]{Vec3.ZERO, new Vec3(.8, .2, -.4)}) {
                        Vec3 velocity = direction.scale(speed).add(carrier);
                        var original = new ClientboundPreciseMotionSyncPacket(37, -2048.25, 172.5, 4096.125,
                                velocity.x, velocity.y, velocity.z, yaw, pitch, false, 3);
                        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
                        try {
                            original.rootEncode(buffer);
                            var decoded = new ClientboundPreciseMotionSyncPacket(buffer);
                            require(original.equals(decoded), "Precise motion must preserve every position/velocity component and angle");
                            Vec3 received = new Vec3(decoded.dx(), decoded.dy(), decoded.dz());
                            require(received.equals(velocity), "High-speed oblique shots must retain their world launch vector");
                            require(buffer.readableBytes() == 0, "The complete precise-motion packet must be consumed");
                        } finally {
                            buffer.release();
                        }

                        // Reproduce the pre-fix channel's actual per-axis clamp, including its effect on trail direction.
                        var ordinary = new ClientboundSetEntityMotionPacket(37, velocity);
                        Vec3 clamped = new Vec3(ordinary.getXa(), ordinary.getYa(), ordinary.getZa());
                        double angle = Math.toDegrees(Math.acos(Math.clamp(velocity.normalize().dot(clamped.normalize()), -1, 1)));
                        worstVanillaAngle = Math.max(worstVanillaAngle, angle);
                        worstVanillaSpeedLoss = Math.max(worstVanillaSpeedLoss, velocity.length() - clamped.length());
                        cases++;
                    }
                }
            }
        }
        require(worstVanillaAngle > 25, "The original ordinary-motion channel must reproduce a large angular error");
        require(worstVanillaSpeedLoss > 15, "The original channel must reproduce severe high-speed truncation");
        System.out.printf("PASS: Minecraft loads the merged APFSDS precise-motion tag; %d packet round trips preserve high-speed oblique shots. Ordinary packets reproduced %.2f degrees of error and %.2f blocks/tick of speed loss.%n",
                cases, worstVanillaAngle, worstVanillaSpeedLoss);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private ApfsdsMotionSyncChecks() {}
}
