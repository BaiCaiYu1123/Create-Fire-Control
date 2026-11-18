package com.hooya.stabilizedturret.compat.touhoumaid;

import com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockPart;
import com.github.tartaricacid.touhoulittlemaid.api.animation.ICustomAnimation;
import com.github.tartaricacid.touhoulittlemaid.api.animation.IModelRenderer;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoBone;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.AnimatedGeoModel;
import com.hooya.stabilizedturret.client.MaidLoadingClientState;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.HashMap;
import org.joml.Quaternionf;
import org.joml.Vector3d;

/** Four-stage open, retrieve, ram and close arm motion for both maid model pipelines. */
@OnlyIn(Dist.CLIENT)
final class MaidCannonLoadingAnimation implements ICustomAnimation<Mob> {
    private static final float[] TIMES = {0.0F, 0.15F, 0.30F, 0.55F, 0.75F, 1.0F};
    // TLM's maid arm pivot uses positive X for reaching in front of the body.
    // Negative values put the hand behind the shoulder on models such as Wine Fox.
    private static final float[] RIGHT_X = {0.0F, 72.0F, 38.0F, 82.0F, 55.0F, 0.0F};
    private static final float[] LEFT_X = {0.0F, 12.0F, 68.0F, 78.0F, 12.0F, 0.0F};

    @Override
    public void setupRotations(
            Mob maid,
            PoseStack poseStack,
            float ageInTicks,
            float rotationYaw,
            float partialTicks
    ) {
        applySubLevelTilt(maid, poseStack, partialTicks);
    }

    @Override
    public void setupGeckoRotations(
            Mob maid,
            PoseStack poseStack,
            float ageInTicks,
            float rotationYaw,
            float partialTicks
    ) {
        applySubLevelTilt(maid, poseStack, partialTicks);
    }

    @Override
    public void setRotationAngles(
            Mob maid,
            HashMap<String, ? extends IModelRenderer> models,
            float limbSwing,
            float limbSwingAmount,
            float ageInTicks,
            float netHeadYaw,
            float headPitch
    ) {
        float progress = MaidLoadingClientState.progress(maid.getUUID());
        if (progress < 0.0F) return;
        float rightX = sample(RIGHT_X, progress);
        float leftX = sample(LEFT_X, progress);
        BedrockPart right = ICustomAnimation.getPartOrNull(models, "armRight");
        BedrockPart left = ICustomAnimation.getPartOrNull(models, "armLeft");
        if (right != null) {
            right.xRot = right.getInitRotX() + rightX;
            right.zRot = right.getInitRotZ() - 8.0F * pulse(progress);
        }
        if (left != null) {
            left.xRot = left.getInitRotX() + leftX;
            left.zRot = left.getInitRotZ() + 8.0F * pulse(progress);
        }
    }

    @Override
    public void setGeckoRotationAngles(
            Mob maid,
            AnimatedGeoModel model,
            float limbSwing,
            float limbSwingAmount,
            float ageInTicks,
            float netHeadYaw,
            float headPitch
    ) {
        float progress = MaidLoadingClientState.progress(maid.getUUID());
        if (progress < 0.0F) return;
        applyGecko(model.rightArm(), sample(RIGHT_X, progress), -8.0F * pulse(progress));
        applyGecko(model.leftArm(), sample(LEFT_X, progress), 8.0F * pulse(progress));
    }

    private static void applyGecko(AnimatedGeoBone arm, float xDegrees, float zDegrees) {
        if (arm == null) return;
        arm.addRotationX(xDegrees * Mth.DEG_TO_RAD);
        arm.addRotationZ(zDegrees * Mth.DEG_TO_RAD);
    }

    private static float sample(float[] values, float progress) {
        float clamped = Mth.clamp(progress, 0.0F, 1.0F);
        for (int i = 1; i < TIMES.length; i++) {
            if (clamped <= TIMES[i]) {
                float local = (clamped - TIMES[i - 1]) / (TIMES[i] - TIMES[i - 1]);
                local = local * local * (3.0F - 2.0F * local);
                return Mth.lerp(local, values[i - 1], values[i]);
            }
        }
        return values[values.length - 1];
    }

    private static float pulse(float progress) {
        return Mth.sin(Mth.clamp(progress, 0.0F, 1.0F) * Mth.PI);
    }

    private static void applySubLevelTilt(Mob mob, PoseStack poseStack, float partialTicks) {
        if (!(mob instanceof EntityMaid maid) || maid.getTask() == null
                || !TaskCannonLoading.UID.equals(maid.getTask().getUid())) return;
        SubLevel tracking = Sable.HELPER.getTrackingSubLevel(maid);
        if (tracking == null || tracking.isRemoved()) tracking = Sable.HELPER.getContaining(maid);
        if (!(tracking instanceof ClientSubLevel clientSubLevel)) return;

        Vector3d up = clientSubLevel.renderPose(partialTicks).orientation()
                .transform(new Vector3d(0.0, 1.0, 0.0));
        if (!Double.isFinite(up.x) || !Double.isFinite(up.y) || !Double.isFinite(up.z)
                || up.lengthSquared() < 1.0E-8) return;
        up.normalize();
        poseStack.mulPose(new Quaternionf().rotationTo(
                0.0F, 1.0F, 0.0F, (float) up.x, (float) up.y, (float) up.z));
    }
}
