package com.hooya.stabilizedturret.compat.mianbao;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Registry-only access to Mianbao's blocks, keeping this project source-independent. */
public final class MianbaoCannonCompat {
    public static final String MOD_ID = "mianbaos_modernwarfare";

    public static final ResourceLocation AUTOCANNON_BREECH = id("autocannonbreech");
    public static final ResourceLocation AUTOCANNON_WIDE_BARREL = id("autocannonwidebarrel");
    public static final ResourceLocation AUTOCANNON_MID_BARREL = id("autocannonmidbarrel");
    public static final ResourceLocation AUTOCANNON_FINE_BARREL = id("autocannonfinebarrel");
    public static final ResourceLocation AUTOCANNON_MUZZLE_BRAKE = id("autocannonmuzzlebrake");
    public static final ResourceLocation ROTARY_CANNON_BREECH = id("rotarycannonbreech");
    public static final ResourceLocation ROTARY_CANNON_BARREL = id("rotarycannonbarrel");
    public static final ResourceLocation ROTARY_GUN_BARREL = id("rotarygunbarrel");

    private static final Set<ResourceLocation> AUTOCANNON_BARRELS = Set.of(
            AUTOCANNON_WIDE_BARREL,
            AUTOCANNON_MID_BARREL,
            AUTOCANNON_FINE_BARREL,
            AUTOCANNON_MUZZLE_BRAKE
    );
    private static final Set<ResourceLocation> ROTARY_BARRELS = Set.of(
            ROTARY_CANNON_BARREL,
            ROTARY_GUN_BARREL
    );
    private static final Map<ResourceLocation, Family> FAMILIES = Map.ofEntries(
            Map.entry(AUTOCANNON_BREECH, Family.AUTOCANNON),
            Map.entry(AUTOCANNON_WIDE_BARREL, Family.AUTOCANNON),
            Map.entry(AUTOCANNON_MID_BARREL, Family.AUTOCANNON),
            Map.entry(AUTOCANNON_FINE_BARREL, Family.AUTOCANNON),
            Map.entry(AUTOCANNON_MUZZLE_BRAKE, Family.AUTOCANNON),
            Map.entry(ROTARY_CANNON_BREECH, Family.ROTARY),
            Map.entry(ROTARY_CANNON_BARREL, Family.ROTARY),
            Map.entry(ROTARY_GUN_BARREL, Family.ROTARY)
    );
    private static boolean movementBehavioursRegistered;

    private MianbaoCannonCompat() {
    }

    public static synchronized void registerMovementBehaviours() {
        if (movementBehavioursRegistered) return;
        MianbaoRotaryBarrelMovementBehaviour behaviour = new MianbaoRotaryBarrelMovementBehaviour();
        Set<Block> registered = new HashSet<>();
        registerMovementBehaviour(AUTOCANNON_WIDE_BARREL, behaviour, registered);
        registerMovementBehaviour(AUTOCANNON_MID_BARREL, behaviour, registered);
        registerMovementBehaviour(AUTOCANNON_FINE_BARREL, behaviour, registered);
        registerMovementBehaviour(AUTOCANNON_MUZZLE_BRAKE, behaviour, registered);
        registerMovementBehaviour(ROTARY_CANNON_BARREL, behaviour, registered);
        registerMovementBehaviour(ROTARY_GUN_BARREL, behaviour, registered);
        movementBehavioursRegistered = true;
    }

    private static void registerMovementBehaviour(
            ResourceLocation blockId,
            MovementBehaviour behaviour,
            Set<Block> registered
    ) {
        // Registry#get returns the default block (minecraft:air) when an older
        // Mianbao version does not contain one of the newer cannon parts.
        // Never register a movement behaviour for that fallback, and avoid a
        // duplicate registration if aliases ever resolve to the same block.
        Block block = BuiltInRegistries.BLOCK.getOptional(blockId).orElse(null);
        if (block != null && registered.add(block)
                && MovementBehaviour.REGISTRY.get(block) == null) {
            MovementBehaviour.REGISTRY.register(block, behaviour);
        }
    }

    public static Family family(BlockState state) {
        return FAMILIES.get(id(state.getBlock()));
    }

    public static boolean isBreech(BlockState state, Family family) {
        ResourceLocation id = id(state.getBlock());
        return id.equals(family == Family.ROTARY ? ROTARY_CANNON_BREECH : AUTOCANNON_BREECH);
    }

    public static boolean isAllowedBarrel(BlockState state, Family family) {
        ResourceLocation id = id(state.getBlock());
        return (family == Family.ROTARY ? ROTARY_BARRELS : AUTOCANNON_BARRELS).contains(id);
    }

    public static boolean isRotaryBarrel(BlockState state) {
        return ROTARY_BARRELS.contains(id(state.getBlock()));
    }

    public static boolean isAutocannonBarrel(BlockState state) {
        return AUTOCANNON_BARRELS.contains(id(state.getBlock()));
    }

    public static boolean isAnyBarrel(BlockState state) {
        ResourceLocation id = id(state.getBlock());
        return AUTOCANNON_BARRELS.contains(id) || ROTARY_BARRELS.contains(id);
    }

    public static Direction facing(BlockState state) {
        return state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                : Direction.NORTH;
    }

    public static ResourceLocation id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public enum Family {
        AUTOCANNON(3, 400),
        ROTARY(1, 1200);

        private final int shotIntervalTicks;
        private final int roundsPerMinute;

        Family(int shotIntervalTicks, int roundsPerMinute) {
            this.shotIntervalTicks = shotIntervalTicks;
            this.roundsPerMinute = roundsPerMinute;
        }

        public int shotIntervalTicks() {
            return shotIntervalTicks;
        }

        public int roundsPerMinute() {
            return roundsPerMinute;
        }
    }
}
