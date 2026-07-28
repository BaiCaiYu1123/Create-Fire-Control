package com.hooya.stabilizedturret.mixin.client;

import com.hooya.stabilizedturret.compat.yuushya.YuushyaCannonCompat;
import com.hooya.stabilizedturret.network.SetYuushyaCannonTypePayload;
import com.yuushya.modelling.blockentity.itemblock.ItemBlockEntity;
import com.yuushya.modelling.gui.itemblock.ItemBlockScreen;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemBlockScreen.class)
public abstract class YuushyaItemBlockScreenMixin extends Screen {
    @Shadow
    @Final
    private ItemBlockEntity blockEntity;

    protected YuushyaItemBlockScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void stabilizedTurret$addCannonTypeSelector(CallbackInfo ci) {
        boolean barrel = YuushyaCannonCompat.isBarrel(blockEntity.getBlockState());
        int buttonWidth = 220;
        int x = Math.max(252, (width - buttonWidth) / 2);
        CycleButton<Boolean> selector = CycleButton.<Boolean>builder(value -> Component.translatable(
                        value
                                ? "gui.stabilized_turret.yuushya_cannon_type.medium_barrel"
                                : "gui.stabilized_turret.yuushya_cannon_type.none"
                ))
                .displayOnlyValue()
                .withValues(Boolean.FALSE, Boolean.TRUE)
                .withInitialValue(barrel)
                .withTooltip(value -> Tooltip.create(Component.translatable(
                        "gui.stabilized_turret.yuushya_cannon_type.tooltip"
                )))
                .create(x, 10, buttonWidth, 20, Component.empty(), (button, enabled) ->
                        PacketDistributor.sendToServer(new SetYuushyaCannonTypePayload(
                                blockEntity.getBlockPos(),
                                enabled
                        ))
                );
        addRenderableWidget(selector);
    }
}
