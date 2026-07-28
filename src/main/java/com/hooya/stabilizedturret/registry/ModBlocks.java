package com.hooya.stabilizedturret.registry;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlock;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlock;
import com.hooya.stabilizedturret.content.turbine.GasTurbineBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks REGISTER = DeferredRegister.createBlocks(StabilizedTurret.MOD_ID);

    public static final DeferredBlock<StabilizerControllerBlock> STABILIZER_CONTROLLER = REGISTER.register(
            "stabilizer_controller",
            () -> new StabilizerControllerBlock(BlockBehaviour.Properties.of()
                    .noOcclusion()
                    .strength(3.5F)
                    .sound(SoundType.METAL))
    );

    public static final DeferredBlock<ConnectedDisplayBlock> CONNECTED_DISPLAY = REGISTER.register(
            "connected_display",
            () -> new ConnectedDisplayBlock(BlockBehaviour.Properties.of()
                    .noOcclusion()
                    .strength(3.0F)
                    .sound(SoundType.METAL))
    );

    public static final DeferredBlock<GasTurbineBlock> GAS_TURBINE = REGISTER.register(
            "gas_turbine",
            () -> new GasTurbineBlock(BlockBehaviour.Properties.of()
                    .noOcclusion()
                    .strength(5.0F)
                    .sound(SoundType.METAL)
                    .pushReaction(PushReaction.BLOCK))
    );

    private ModBlocks() {
    }
}
