package com.hooya.stabilizedturret.client.remote;

import com.hooya.stabilizedturret.content.remote.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.core.Direction;
import java.util.Set;

public final class WirelessLecternControllerRenderer implements BlockEntityRenderer<WirelessLecternControllerBlockEntity> {
    public WirelessLecternControllerRenderer(BlockEntityRendererProvider.Context context) {}
    @Override public void render(WirelessLecternControllerBlockEntity mounted,float partial,PoseStack pose,
                                 MultiBufferSource buffer,int light,int overlay) {
        if(mounted.controller().isEmpty()||mounted.getLevel()==null)return;
        pose.pushPose();
        transform(pose,mounted.getBlockState().getValue(WirelessLecternControllerBlock.FACING));
        double time=mounted.animationTime(mounted.getLevel().getGameTime()+partial,KeyboardControllerModel.animationLength());
        KeyboardControllerModel.renderAt(pose,buffer,light,overlay,time,mounted.inUse()&&time==0,
                mounted.inUse()?mounted.visualPressed():Set.of(),ControllerSettings.color(ControllerSettings.data(mounted.controller())));
        pose.popPose();
    }
    static void transform(PoseStack pose,Direction facing) {
        pose.translate(0.5,1.055,0.5);
        // Blockstate Y rotations turn clockwise; PoseStack's positive Y turns the other way.
        pose.mulPose(Axis.YP.rotationDegrees(180-facing.toYRot()));
        pose.translate(0,-0.085,-0.13);
        pose.mulPose(Axis.XP.rotationDegrees(-22.5F));
        pose.mulPose(Axis.YP.rotationDegrees(90));
        // At full scale the keyboard's 16-pixel width meets both lectern edges.
        pose.translate(-0.5,-0.5,-0.5);
    }
}
