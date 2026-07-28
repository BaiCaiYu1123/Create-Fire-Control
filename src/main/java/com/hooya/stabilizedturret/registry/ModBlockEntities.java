package com.hooya.stabilizedturret.registry;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> REGISTER = DeferredRegister.create(
            Registries.BLOCK_ENTITY_TYPE,
            StabilizedTurret.MOD_ID
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StabilizerControllerBlockEntity>> STABILIZER_CONTROLLER =
            REGISTER.register("stabilizer_controller", () -> BlockEntityType.Builder.of(
                    StabilizerControllerBlockEntity::new,
                    ModBlocks.STABILIZER_CONTROLLER.get()
            ).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ConnectedDisplayBlockEntity>> CONNECTED_DISPLAY =
            REGISTER.register("connected_display", () -> BlockEntityType.Builder.of(
                    ConnectedDisplayBlockEntity::new,
                    ModBlocks.CONNECTED_DISPLAY.get()
            ).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GasTurbineBlockEntity>> GAS_TURBINE =
            REGISTER.register("gas_turbine", () -> BlockEntityType.Builder.of(
                    GasTurbineBlockEntity::new,
                    ModBlocks.GAS_TURBINE.get()
            ).build(null));

    private ModBlockEntities() {
    }
}
