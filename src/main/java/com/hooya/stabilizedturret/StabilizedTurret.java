package com.hooya.stabilizedturret;

import com.hooya.stabilizedturret.content.bearing.BearingStabilizer;
import com.hooya.stabilizedturret.network.ModNetworking;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import com.hooya.stabilizedturret.registry.ModArmorUpgrades;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import com.hooya.stabilizedturret.registry.ModCreativeTabs;
import com.hooya.stabilizedturret.registry.ModItems;
import com.hooya.stabilizedturret.registry.RegistryMigration;
import com.hooya.stabilizedturret.registry.ModSounds;
import com.hooya.stabilizedturret.registry.ModContraptionTypes;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(StabilizedTurret.MOD_ID)
public final class StabilizedTurret {
    public static final String MOD_ID = "create_fire_control";
    public static final Logger LOGGER = LogUtils.getLogger();

    public StabilizedTurret(IEventBus modBus, ModContainer modContainer) {
        RegistryMigration.addLegacyAliases(ModBlocks.REGISTER);
        RegistryMigration.addLegacyAliases(ModItems.REGISTER);
        RegistryMigration.addLegacyAliases(ModBlockEntities.REGISTER);
        RegistryMigration.addLegacyAliases(ModSounds.REGISTER);
        ModBlocks.REGISTER.register(modBus);
        ModItems.REGISTER.register(modBus);
        ModArmorUpgrades.register(modBus);
        ModBlockEntities.REGISTER.register(modBus);
        ModSounds.REGISTER.register(modBus);
        com.hooya.stabilizedturret.registry.PolyArmorMenus.REGISTER.register(modBus);
        com.caca.copypoly.PolyMenu.bindMultiEditor(com.hooya.stabilizedturret.registry.PolyArmorMenus::openGroup);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            com.hooya.stabilizedturret.client.FireControlConfigMenu.register(modContainer);
            com.hooya.stabilizedturret.client.BearingHeadClientModels.register(modBus);
            com.hooya.stabilizedturret.client.RadarKeyHandler.register(modBus);
            com.hooya.stabilizedturret.client.remote.WirelessControllerClient.register(modBus);
            if (ModList.get().isLoaded("mianbaos_modernwarfare")) {
                com.hooya.stabilizedturret.content.missile.client
                        .BallisticMissileWingRenderer.register(modBus);
            }
        }
        if (ModList.get().isLoaded("cbcmodernwarfare")) {
            ModCbcContent.register(modBus);
        }
        if (ModList.get().isLoaded("createbigcannons")
                && ModList.get().isLoaded("mianbaos_modernwarfare")) {
            RegistryMigration.addLegacyAliases(ModContraptionTypes.REGISTER);
            ModContraptionTypes.REGISTER.register(modBus);
        }
        RegistryMigration.addLegacyAliases(ModCreativeTabs.REGISTER);
        ModCreativeTabs.REGISTER.register(modBus);
        modBus.addListener(ModNetworking::register);
        modBus.addListener(this::commonSetup);
        modContainer.registerConfig(ModConfig.Type.CLIENT,
                com.hooya.stabilizedturret.client.ClientScopeConfig.SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT,
                com.hooya.stabilizedturret.client.ClientVisualConfig.SPEC,
                com.hooya.stabilizedturret.client.ClientVisualConfig.FILE);
        modContainer.registerConfig(ModConfig.Type.CLIENT,
                com.hooya.stabilizedturret.client.TigerISoundConfig.SPEC,
                "create_fire_control-tiger-i-sound.toml");
        modContainer.registerConfig(ModConfig.Type.CLIENT,
                com.hooya.stabilizedturret.client.Ztz99aDieselSoundConfig.SPEC,
                "create_fire_control-ztz99a-sound.toml");
        com.hooya.stabilizedturret.config.FireControlSettings.register(modBus, modContainer);
        com.hooya.stabilizedturret.content.missile.client.ClientMissileConfig.register(modContainer);
        NeoForge.EVENT_BUS.addListener(BearingStabilizer::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(BearingStabilizer::onServerStopping);
        NeoForge.EVENT_BUS.addListener(
                com.hooya.stabilizedturret.content.controller.CannonProjectileInheritanceHandler::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,
                com.hooya.stabilizedturret.content.controller.CannonProjectileInheritanceHandler::onEntityTickPre);
        NeoForge.EVENT_BUS.addListener(
                com.hooya.stabilizedturret.content.controller.CannonProjectileInheritanceHandler::onEntityLeave);
        if (ModList.get().isLoaded("touhou_little_maid")) {
            NeoForge.EVENT_BUS.addListener(
                    com.hooya.stabilizedturret.compat.touhoumaid.MaidLoadingSableCollisionProtection::onIncomingDamage);
            NeoForge.EVENT_BUS.addListener(
                    com.hooya.stabilizedturret.compat.touhoumaid.MaidLoadingSeatPersistence::onServerStopping);
        }
        SableEventPlatform.INSTANCE.onPhysicsTick(BearingStabilizer::maintainPhysicalProperties);
        SableEventPlatform.INSTANCE.onSubLevelContainerReady(BearingStabilizer::onSubLevelContainerReady);
        SableEventPlatform.INSTANCE.onPostPhysicsTick((physicsSystem,timeStep) ->
                com.hooya.stabilizedturret.content.bearing.LegacyNestedBearingConstraintFollower
                        .enforceWorldLocks(physicsSystem));
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        if (ModList.get().isLoaded("sable_schematic_api")) {
            event.enqueueWork(com.hooya.stabilizedturret.compat.schematic.FireControlBlueprintCompat::register);
        }
        if (ModList.get().isLoaded("cbcmodernwarfare") && ModList.get().isLoaded("yuushya_modelling")) {
            event.enqueueWork(com.hooya.stabilizedturret.compat.yuushya.YuushyaCannonCompat::initialize);
        }
        if (ModList.get().isLoaded("createbigcannons")
                && ModList.get().isLoaded("mianbaos_modernwarfare")) {
            event.enqueueWork(
                    com.hooya.stabilizedturret.compat.mianbao.MianbaoCannonCompat::registerMovementBehaviours);
        }
    }
}
