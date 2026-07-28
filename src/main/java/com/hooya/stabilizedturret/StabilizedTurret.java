package com.hooya.stabilizedturret;

import com.hooya.stabilizedturret.content.bearing.BearingStabilizer;
import com.hooya.stabilizedturret.content.bearing.NestedBearingConstraintFollower;
import com.hooya.stabilizedturret.network.ModNetworking;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import com.hooya.stabilizedturret.registry.ModBlocks;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import com.hooya.stabilizedturret.registry.ModCreativeTabs;
import com.hooya.stabilizedturret.registry.ModItems;
import com.hooya.stabilizedturret.registry.ModSounds;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(StabilizedTurret.MOD_ID)
public final class StabilizedTurret {
    public static final String MOD_ID = "stabilized_turret";
    public static final Logger LOGGER = LogUtils.getLogger();

    public StabilizedTurret(IEventBus modBus, ModContainer modContainer) {
        ModBlocks.REGISTER.register(modBus);
        ModItems.REGISTER.register(modBus);
        ModBlockEntities.REGISTER.register(modBus);
        ModSounds.REGISTER.register(modBus);
        if (ModList.get().isLoaded("cbcmodernwarfare")) {
            ModCbcContent.register(modBus);
        }
        ModCreativeTabs.REGISTER.register(modBus);
        modBus.addListener(ModNetworking::register);
        modBus.addListener(this::commonSetup);
        modContainer.registerConfig(ModConfig.Type.CLIENT,
                com.hooya.stabilizedturret.client.ClientScopeConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(BearingStabilizer::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(
                com.hooya.stabilizedturret.content.controller.CannonProjectileInheritanceHandler::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,
                com.hooya.stabilizedturret.content.controller.CannonProjectileInheritanceHandler::onEntityTickPre);
        NeoForge.EVENT_BUS.addListener(
                com.hooya.stabilizedturret.content.controller.CannonProjectileInheritanceHandler::onEntityLeave);
        SableEventPlatform.INSTANCE.onPhysicsTick(BearingStabilizer::maintainPhysicalProperties);
        SableEventPlatform.INSTANCE.onPostPhysicsTick((physicsSystem, timeStep) ->
                NestedBearingConstraintFollower.enforceWorldLocks(physicsSystem));
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        if (ModList.get().isLoaded("sable_schematic_api")) {
            event.enqueueWork(com.hooya.stabilizedturret.compat.schematic.FireControlBlueprintCompat::register);
        }
        if (ModList.get().isLoaded("cbcmodernwarfare") && ModList.get().isLoaded("yuushya_modelling")) {
            event.enqueueWork(com.hooya.stabilizedturret.compat.yuushya.YuushyaCannonCompat::initialize);
        }
    }
}
