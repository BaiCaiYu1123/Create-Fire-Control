package com.hooya.stabilizedturret.registry;

import com.hooya.stabilizedturret.content.diesel.Ztz99aDieselEngineBlock;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlock;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlock;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayConsoleBlock;
import com.hooya.stabilizedturret.content.iff.IffTransponderBlock;
import com.hooya.stabilizedturret.content.loader.Type10LoaderBlock;
import com.hooya.stabilizedturret.content.loader.AzLoaderBlock;
import com.hooya.stabilizedturret.content.loader.MzLoaderBlock;
import com.hooya.stabilizedturret.content.missile.Df26BallisticMissileBlock;
import com.hooya.stabilizedturret.content.turbine.GasTurbineBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks REGISTER = DeferredRegister.createBlocks(StabilizedTurret.MOD_ID);

    public static final DeferredBlock<com.hooya.stabilizedturret.content.remote.WirelessLecternControllerBlock> WIRELESS_LECTERN_CONTROLLER =
            REGISTER.register("wireless_lectern_controller", () -> new com.hooya.stabilizedturret.content.remote.WirelessLecternControllerBlock(
                    BlockBehaviour.Properties.ofFullCopy(net.minecraft.world.level.block.Blocks.LECTERN).noOcclusion()));

    public static final DeferredBlock<com.hooya.stabilizedturret.content.remote.WirelessSignalProcessorBlock> WIRELESS_SIGNAL_PROCESSOR =
            REGISTER.register("wireless_signal_processor", () -> new com.hooya.stabilizedturret.content.remote.WirelessSignalProcessorBlock(
                    BlockBehaviour.Properties.of().noOcclusion().strength(3.5F).sound(SoundType.METAL)));

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

    public static final DeferredBlock<ConnectedDisplayConsoleBlock> CONNECTED_DISPLAY_CONSOLE = REGISTER.register(
            "connected_display_console",
            () -> new ConnectedDisplayConsoleBlock(BlockBehaviour.Properties.of()
                    .noOcclusion()
                    .strength(3.0F)
                    .sound(SoundType.METAL))
    );

    public static final DeferredBlock<IffTransponderBlock> IFF_TRANSPONDER = REGISTER.register(
            "iff_transponder",
            () -> new IffTransponderBlock(BlockBehaviour.Properties.of()
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

    public static final DeferredBlock<Ztz99aDieselEngineBlock> ZTZ99A_DIESEL_ENGINE = REGISTER.register(
            "ztz99a_diesel_engine", () -> new Ztz99aDieselEngineBlock(BlockBehaviour.Properties.of()
                    .noOcclusion().strength(5.0F).sound(SoundType.METAL).pushReaction(PushReaction.BLOCK)));

    public static final DeferredBlock<com.hooya.stabilizedturret.content.tiger.TigerIEngineBlock> TIGER_I_ENGINE = REGISTER.register(
            "tiger_i_engine", () -> new com.hooya.stabilizedturret.content.tiger.TigerIEngineBlock(BlockBehaviour.Properties.of()
                    .noOcclusion().strength(5.0F).sound(SoundType.METAL).pushReaction(PushReaction.BLOCK)));

    public static final DeferredBlock<MzLoaderBlock> MZ_LOADER = REGISTER.register(
            "mz_loader",
            () -> new MzLoaderBlock(BlockBehaviour.Properties.of()
                    .noOcclusion()
                    .strength(4.0F)
                    .sound(SoundType.METAL))
    );

    public static final DeferredBlock<Type10LoaderBlock> TYPE_10_LOADER = REGISTER.register(
            "type_10_loader", () -> new Type10LoaderBlock(BlockBehaviour.Properties.of()
                    .noOcclusion().strength(4.0F).sound(SoundType.METAL)));

    public static final DeferredBlock<AzLoaderBlock> AZ_LOADER = REGISTER.register(
            "az_loader",
            () -> new AzLoaderBlock(BlockBehaviour.Properties.of()
                    .noOcclusion()
                    .strength(4.0F)
                    .sound(SoundType.METAL))
    );

    public static final DeferredBlock<Df26BallisticMissileBlock> DF_26_BALLISTIC_MISSILE =
            REGISTER.register(
                    "df_26_ballistic_missile",
                    () -> new Df26BallisticMissileBlock(BlockBehaviour.Properties.of()
                            .noOcclusion()
                            .noLootTable()
                            .strength(3.0F, 15.0F)
                            .sound(SoundType.METAL)
                            .pushReaction(PushReaction.BLOCK))
            );

    private ModBlocks() {
    }
}
