package com.hooya.stabilizedturret.compat.touhoumaid;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.api.entity.ai.IExtraMaidBrain;
import com.github.tartaricacid.touhoulittlemaid.client.animation.HardcodedAnimationManger;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.ExtraMaidBrainManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.mojang.datafixers.util.Pair;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/** Discovered by Touhou Little Maid only when that optional mod is installed. */
@LittleMaidExtension
public final class FireControlMaidExtension implements ILittleMaid {
    @Override
    public void addMaidTask(TaskManager manager) {
        manager.add(new TaskCannonLoading());
    }

    @Override
    public void addExtraMaidBrain(ExtraMaidBrainManager manager) {
        manager.addExtraMaidBrain(new IExtraMaidBrain() {
            @Override
            public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> getCoreBehaviors() {
                // Run after the stock follow/path goals so loading mode can erase movement
                // requests in every schedule activity, not only during WORK hours.
                return List.of(Pair.of(100, new MaidCannonLoadingBehavior()));
            }
        });
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public void addHardcodeAnimation(HardcodedAnimationManger manager) {
        manager.addMaidAnimation(new MaidCannonLoadingAnimation());
    }
}
