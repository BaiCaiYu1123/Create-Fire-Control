package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import dev.simulated_team.simulated.index.SimBlocks;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Makes the normal Simulated bearing-head model conditional on per-block-entity model data. */
public final class BearingHeadClientModels {
    public static final ModelProperty<Boolean> BEARING_HEAD_HIDDEN = new ModelProperty<>();

    public static void register(IEventBus modBus) {
        modBus.addListener(BearingHeadClientModels::wrapBearingHeadModels);
    }

    private static void wrapBearingHeadModels(ModelEvent.ModifyBakingResult event) {
        for (BlockState state : SimBlocks.SWIVEL_BEARING_LINK_BLOCK.get()
                .getStateDefinition().getPossibleStates()) {
            ModelResourceLocation location = BlockModelShaper.stateToModelLocation(state);
            BakedModel original = event.getModels().get(location);
            if (original != null) {
                event.getModels().put(location, new VisibilityModel(original));
            } else {
                StabilizedTurret.LOGGER.warn("Missing Simulated bearing-head model {}", location);
            }
        }
    }

    private static final class VisibilityModel extends BakedModelWrapper<BakedModel> {
        private VisibilityModel(BakedModel originalModel) {
            super(originalModel);
        }

        @Override
        public List<BakedQuad> getQuads(
                @Nullable BlockState state,
                @Nullable Direction side,
                RandomSource random
        ) {
            return originalModel.getQuads(state, side, random);
        }

        @Override
        public List<BakedQuad> getQuads(
                @Nullable BlockState state,
                @Nullable Direction side,
                RandomSource random,
                ModelData data,
                @Nullable RenderType renderType
        ) {
            return Boolean.TRUE.equals(data.get(BEARING_HEAD_HIDDEN))
                    ? List.of()
                    : originalModel.getQuads(state, side, random, data, renderType);
        }
    }

    private BearingHeadClientModels() {
    }
}
