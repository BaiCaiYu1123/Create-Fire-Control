package com.hooya.stabilizedturret.registry;

import com.hooya.stabilizedturret.content.diesel.Ztz99aDieselEngineBlockEntity;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.StabilizerControllerBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayConsoleBlockEntity;
import com.hooya.stabilizedturret.content.iff.IffTransponderBlockEntity;
import com.hooya.stabilizedturret.content.loader.Type10LoaderBlockEntity;
import com.hooya.stabilizedturret.content.loader.AzLoaderBlockEntity;
import com.hooya.stabilizedturret.content.loader.MzLoaderBlockEntity;
import com.hooya.stabilizedturret.content.missile.Df26BallisticMissileBlockEntity;
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

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.hooya.stabilizedturret.content.remote.WirelessSignalProcessorBlockEntity>> WIRELESS_SIGNAL_PROCESSOR =
            REGISTER.register("wireless_signal_processor", () -> BlockEntityType.Builder.of(
                    com.hooya.stabilizedturret.content.remote.WirelessSignalProcessorBlockEntity::new,
                    ModBlocks.WIRELESS_SIGNAL_PROCESSOR.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.hooya.stabilizedturret.content.remote.WirelessLecternControllerBlockEntity>> WIRELESS_LECTERN_CONTROLLER =
            REGISTER.register("wireless_lectern_controller", () -> BlockEntityType.Builder.of(
                    com.hooya.stabilizedturret.content.remote.WirelessLecternControllerBlockEntity::new,
                    ModBlocks.WIRELESS_LECTERN_CONTROLLER.get()).build(null));

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

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ConnectedDisplayConsoleBlockEntity>> CONNECTED_DISPLAY_CONSOLE =
            REGISTER.register("connected_display_console", () -> BlockEntityType.Builder.of(
                    ConnectedDisplayConsoleBlockEntity::new,
                    ModBlocks.CONNECTED_DISPLAY_CONSOLE.get()
            ).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<IffTransponderBlockEntity>> IFF_TRANSPONDER =
            REGISTER.register("iff_transponder", () -> BlockEntityType.Builder.of(
                    IffTransponderBlockEntity::new,
                    ModBlocks.IFF_TRANSPONDER.get()
            ).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GasTurbineBlockEntity>> GAS_TURBINE =
            REGISTER.register("gas_turbine", () -> BlockEntityType.Builder.of(
                    GasTurbineBlockEntity::new,
                    ModBlocks.GAS_TURBINE.get()
            ).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Ztz99aDieselEngineBlockEntity>> ZTZ99A_DIESEL_ENGINE =
            REGISTER.register("ztz99a_diesel_engine", () -> BlockEntityType.Builder.of(
                    Ztz99aDieselEngineBlockEntity::new, ModBlocks.ZTZ99A_DIESEL_ENGINE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.hooya.stabilizedturret.content.tiger.TigerIEngineBlockEntity>> TIGER_I_ENGINE =
            REGISTER.register("tiger_i_engine", () -> BlockEntityType.Builder.of(
                    com.hooya.stabilizedturret.content.tiger.TigerIEngineBlockEntity::new, ModBlocks.TIGER_I_ENGINE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MzLoaderBlockEntity>> MZ_LOADER =
            REGISTER.register("mz_loader", () -> BlockEntityType.Builder.of(
                    MzLoaderBlockEntity::new,
                    ModBlocks.MZ_LOADER.get()
            ).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Type10LoaderBlockEntity>> TYPE_10_LOADER =
            REGISTER.register("type_10_loader", () -> BlockEntityType.Builder.of(
                    Type10LoaderBlockEntity::new, ModBlocks.TYPE_10_LOADER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AzLoaderBlockEntity>> AZ_LOADER =
            REGISTER.register("az_loader", () -> BlockEntityType.Builder.of(
                    AzLoaderBlockEntity::new,
                    ModBlocks.AZ_LOADER.get()
            ).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Df26BallisticMissileBlockEntity>>
            DF_26_BALLISTIC_MISSILE = REGISTER.register(
                    "df_26_ballistic_missile",
                    () -> BlockEntityType.Builder.of(
                            Df26BallisticMissileBlockEntity::new,
                            ModBlocks.DF_26_BALLISTIC_MISSILE.get()
                    ).build(null)
            );

    private ModBlockEntities() {
    }
}
