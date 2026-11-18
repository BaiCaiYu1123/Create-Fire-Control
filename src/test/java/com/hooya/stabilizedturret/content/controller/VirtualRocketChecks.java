package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.network.RocketSalvoPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.fml.loading.LoadingModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class VirtualRocketChecks {
    public static void main(String[] args) {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        checkSalvoTrajectories();
        checkSavedBlockCollision();
        checkNetworkBatch();
        checkGuidance();
        checkModernRockets();
        checkExplosionBudget();
        System.out.println("PASS: 192 entity-free flights, client prediction, saved terrain/negative sections, first-tick wall, batched removal, laser steering, shared explosion budget");
    }

    private static void checkSalvoTrajectories() {
        long start = System.nanoTime();
        for (int index = 0; index < 192; index++) {
            double drop = index % 2 == 0 ? 0.025 : 0.03;
            double drag = index % 3 == 0 ? (double) 0.6F : (double) 0.99F;
            Vec3 origin = new Vec3(index, 300, -index);
            Vec3 initial = new Vec3(0.4 + index * 0.01, 1.2, 10.0);
            VirtualRocketFlight flight = new VirtualRocketFlight(origin, initial, 0, drag, drop);
            for (int tick = 0; tick < 300; tick++) {
                Vec3 midpoint = MianbaoRocketFlight.positionAt(origin, initial, 0, tick + 0.5, drag, drop);
                var step = flight.advance(null);
                near(step.from().lerp(step.to(), 0.5), midpoint, "fractional client/server trajectory");
                near(flight.position(), MianbaoRocketFlight.positionAt(origin, initial, 0,
                        tick + 1, drag, drop), "server flight matches launch-only client prediction");
                if (tick == 120) {
                    near(MianbaoRocketFlight.positionAt(flight.position(), flight.velocity(), flight.age(),
                            4.5, drag, drop), MianbaoRocketFlight.positionAt(origin, initial, 0,
                            tick + 5.5, drag, drop), "midflight correction remains continuous");
                }
            }
        }
        System.out.printf("192 rockets x 300 ticks mathematical check: %.1f ms (includes assertions; excludes world/render work)%n",
                (System.nanoTime() - start) / 1e6);
    }

    private static void checkSavedBlockCollision() {
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) -1);
        CompoundTag state = new CompoundTag();
        state.putString("Name", "minecraft:stone");
        ListTag palette = new ListTag(); palette.add(state);
        CompoundTag blocks = new CompoundTag(); blocks.put("palette", palette);
        section.put("block_states", blocks);
        ListTag sections = new ListTag(); sections.add(section);
        CompoundTag chunk = new CompoundTag();
        chunk.putString("Status", "minecraft:full"); chunk.put("sections", sections);
        var snapshot = RocketTerrainCache.decode(chunk);
        require(snapshot != null && snapshot.getBlockState(new BlockPos(-17, -1, -17)).is(Blocks.STONE),
                "disk snapshot preserves negative chunk/section coordinates");
        BlockGetter view = new BlockGetter() {
            @Override public BlockState getBlockState(BlockPos pos) {
                return pos.getX() >= 3 ? snapshot.getBlockState(pos) : Blocks.AIR.defaultBlockState();
            }
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public int getHeight() { return 384; }
            @Override public int getMinBuildHeight() { return -64; }
        };
        VirtualRocketFlight flight = new VirtualRocketFlight(new Vec3(0.5, -0.5, 0.5),
                new Vec3(10, 0, 0), 0, 0.99F, 0.025);
        var step = flight.advance(null);
        var hit = view.clip(new ClipContext(step.from(), step.to(), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, CollisionContext.empty()));
        require(hit.getType() != HitResult.Type.MISS && Math.abs(hit.getLocation().x - 3.0) < 1e-8,
                "near wall hits during first tick instead of global 100-block safety blindness");
        chunk.putString("Status", "minecraft:empty");
        require(RocketTerrainCache.decode(chunk) == null, "unfinished terrain is unknown, never invented as air");
        blocks.put("palette", new ListTag());
        chunk.putString("Status", "minecraft:full");
        require(RocketTerrainCache.decode(chunk) == null, "corrupt palette is rejected");
        require(RocketTerrainCache.decodeSaved(Optional.of(chunk)) == null,
                "damaged saved data must not become clear transit space");
        require(RocketTerrainCache.decodeSaved(Optional.empty()).getBlockState(new BlockPos(0, 0, 0)).isAir(),
                "un-generated transit does not invent a collision or block later known-target checks");
    }

    private static void checkNetworkBatch() {
        List<RocketSalvoPayload.State> states = new ArrayList<>();
        List<UUID> removed = new ArrayList<>();
        for (int i = 0; i < RocketSalvoPayload.MAX_ROCKETS; i++) {
            UUID id = new UUID(0, i);
            states.add(new RocketSalvoPayload.State(id, ResourceLocation.parse("mianbaos_modernwarfare:rocketstanshewu"),
                    new Vec3(i, 20, -i), new Vec3(0.5, 1, 10), i, 0.99F, 0.025));
            removed.add(new UUID(1, i));
        }
        var original = new RocketSalvoPayload(ResourceLocation.parse("minecraft:overworld"), 400, states, removed);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        RocketSalvoPayload.STREAM_CODEC.encode(buffer, original);
        int bytes = buffer.readableBytes();
        require(original.equals(RocketSalvoPayload.STREAM_CODEC.decode(buffer)) && buffer.readableBytes() == 0,
                "full salvo and explicit impact removals round trip");
        require(bytes < 32767, "maximum salvo fits one small payload");
        buffer.release();
    }

    private static void checkGuidance() {
        Vec3 velocity = new Vec3(0, 0, 9);
        VirtualRocketFlight flight = new VirtualRocketFlight(Vec3.ZERO, velocity, 0, 0.99F, 0.03);
        flight.advance(new Vec3(50, 0, 100));
        near(flight.velocity(), velocity.scale((double) 0.99F).add(0, -0.05, 0),
                "native laser correction has one-tick cache delay");
        flight.advance(new Vec3(50, 0, 100));
        require(flight.velocity().x > 0, "laser rocket retains native cached steering");
    }

    private static void checkExplosionBudget() {
        var budget = new MianbaoDeferredDetonation.RocketWorkBudget();
        budget.reset();
        int allowed = 0;
        for (int shot = 0; shot < 192; shot++) for (int ray = 0; ray < 100; ray++) {
            if (budget.ray()) allowed++;
        }
        require(allowed <= 384, "salvos share a global ray budget");
        allowed = 0;
        for (int block = 0; block < 10000; block++) if (budget.block()) allowed++;
        require(allowed <= 256, "salvos share a global block budget");
    }

    private static void checkModernRockets() {
        Vec3 origin = new Vec3(3, 200, -4), initial = new Vec3(0, 0, 10);
        VirtualRocketFlight he = new VirtualRocketFlight(origin, initial, 0, 0.99F, 0.025, 0.0, false);
        for (int tick = 1; tick <= 300; tick++) {
            he.advance(null);
            double cachedTicks = tick - 1;
            Vec3 expected = origin.add(0, -0.025 * cachedTicks * (cachedTicks - 1) / 2,
                    10 * (1 + (double) 0.99F * cachedTicks));
            near(he.position(), expected, "2.7 HE retains zero first-tick gravity");
            near(MianbaoRocketFlight.positionAt(origin, initial, 0, tick, 0.99F, 0.025, 0.0),
                    expected, "2.7 HE client trajectory");
        }
        VirtualRocketFlight guided = new VirtualRocketFlight(origin, new Vec3(0, 0, 6), 0, 0.99F, 0, 0, true);
        for (int tick = 1; tick <= 10; tick++) {
            guided.advance(new Vec3(100, 200, 100));
            near(guided.velocity(), new Vec3(0, 0, 6 * Math.pow((double) 0.99F, tick)),
                    "2.7 sight-guided launch delay and drag");
        }
        guided.advance(new Vec3(100, 200, 100));
        require(guided.velocity().x > 0 && guided.velocity().y == 0, "2.7 sight guidance lost or gained gravity");
        VirtualRocketFlight aligned = new VirtualRocketFlight(origin, new Vec3(0, 0, 6), 10, 0.99F, 0, 0, true);
        aligned.advance(new Vec3(3, 200, 100));
        near(aligned.velocity(), new Vec3(0, 0, 6), "2.7 guided motor restores native speed");
    }

    private static void near(Vec3 a, Vec3 b, String message) { require(a.distanceTo(b) < 1e-7, message); }
    private static void require(boolean test, String message) { if (!test) throw new AssertionError(message); }
    private VirtualRocketChecks() {}
}
