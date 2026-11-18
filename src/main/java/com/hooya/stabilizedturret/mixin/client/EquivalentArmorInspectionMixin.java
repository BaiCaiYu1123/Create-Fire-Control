package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.content.armor.ArmorInspectionRay;
import com.hooya.stabilizedturret.content.armor.EquivalentArmor;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.block_armor_properties.BlockArmorInspectionToolItem;
import rbasamoyai.createbigcannons.block_armor_properties.BlockArmorPropertiesProvider;

import java.util.List;

@Mixin(value=BlockArmorInspectionToolItem.class,remap=false)
public abstract class EquivalentArmorInspectionMixin {
    @WrapOperation(method = "addBlockArmorInfo", at = @At(value = "INVOKE",
            target = "Lrbasamoyai/createbigcannons/block_armor_properties/BlockArmorPropertiesProvider;toughness(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Z)D"))
    private static double fireControl$inspectToughness(BlockArmorPropertiesProvider provider, Level level,
            BlockState state, BlockPos pos, boolean recurse, Operation<Double> original,
            @Share("armorTotals") LocalRef<ArmorInspectionRay.Totals> totals) {
        var mc = Minecraft.getInstance();
        var viewer = mc.getCameraEntity();
        if (!EquivalentArmor.enabled() || viewer == null || !(mc.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) {
            return original.call(provider, level, state, pos, recurse);
        }

        float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(true);
        var eye = Sable.HELPER.getEyePositionInterpolated(viewer, partialTick);
        var scan = ArmorInspectionRay.scan(level, hit, eye, body -> body instanceof ClientSubLevel client
                ? client.renderPose(partialTick) : body.logicalPose());
        totals.set(scan);
        return scan.toughness();
    }

    @WrapOperation(method = "addBlockArmorInfo", at = @At(value = "INVOKE",
            target = "Lrbasamoyai/createbigcannons/block_armor_properties/BlockArmorPropertiesProvider;hardness(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Z)D"))
    private static double fireControl$inspectHardness(BlockArmorPropertiesProvider provider, Level level,
            BlockState state, BlockPos pos, boolean recurse, Operation<Double> original,
            @Share("armorTotals") LocalRef<ArmorInspectionRay.Totals> totals) {
        var scan = totals.get();
        return scan == null ? original.call(provider, level, state, pos, recurse) : scan.hardness();
    }

    @ModifyExpressionValue(method = "addBlockArmorInfo", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;",
            ordinal = 0))
    private static MutableComponent fireControl$inspectionTitle(MutableComponent original,
            @Share("armorTotals") LocalRef<ArmorInspectionRay.Totals> totals) {
        return totals.get() == null ? original : Component.translatable("tooltip.create_fire_control.armor.equivalent_title");
    }

    @Inject(method = "addBlockArmorInfo", at = @At(value = "INVOKE",
            target = "Lnet/createmod/catnip/lang/LangBuilder;forGoggles(Ljava/util/List;I)V",
            ordinal = 1, shift = At.Shift.AFTER))
    private static void fireControl$inspectThickness(List<Component> tooltip, Level level, BlockPos pos,
            BlockState state, CallbackInfo ci, @Share("armorTotals") LocalRef<ArmorInspectionRay.Totals> totals) {
        var scan = totals.get();
        if (scan != null) {
            tooltip.add(Component.translatable("tooltip.create_fire_control.armor.equivalent_thickness",
                    scan.millimeters(), scan.blocks()));
        }
    }
}
