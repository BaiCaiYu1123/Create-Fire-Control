package com.hooya.stabilizedturret.client.compat;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Custom renderers may draw directly or use global buffers; they cannot be captured in a mesh. */
record YuushyaItemRenderPlan(List<BakedModel> cached, List<BakedModel> live) {
    static YuushyaItemRenderPlan resolve(BakedModel base, ItemStack stack) {
        // Like ItemRenderer, check the root before asking for ordinary quad render passes.
        if (base.isCustomRenderer()) {
            return new YuushyaItemRenderPlan(List.of(), List.of(base));
        }
        List<BakedModel> cached = new ArrayList<>();
        List<BakedModel> live = new ArrayList<>();
        for (BakedModel pass : base.getRenderPasses(stack, true)) {
            (pass.isCustomRenderer() ? live : cached).add(pass);
        }
        return new YuushyaItemRenderPlan(List.copyOf(cached), List.copyOf(live));
    }
}
