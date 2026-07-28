package com.hooya.stabilizedturret.client.compat;

import com.hooya.stabilizedturret.registry.ModCbcContent;
import com.yuushya.modelling.client.NeoItemBlockModel;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ModelEvent;

public final class YuushyaCannonClient {
    public static void register(IEventBus modBus) {
        modBus.addListener(YuushyaCannonClient::wrapBarrelModels);
    }

    private static void wrapBarrelModels(ModelEvent.ModifyBakingResult event) {
        Block barrel = ModCbcContent.YUUSHYA_MEDIUM_CANNON_BARREL.get();
        for (BlockState state : barrel.getStateDefinition().getPossibleStates()) {
            ModelResourceLocation location = BlockModelShaper.stateToModelLocation(state);
            BakedModel original = event.getModels().get(location);
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            event.getModels().put(location, new NeoItemBlockModel(facing, original));
        }
    }

    private YuushyaCannonClient() {
    }
}
