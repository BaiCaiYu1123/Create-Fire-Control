package com.hooya.stabilizedturret.content.remote;

import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import java.util.List;

public final class WirelessControllerItem extends Item {
    public WirelessControllerItem(Properties properties) { super(properties); }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("remote.create_fire_control.item_hint").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("remote.create_fire_control.item_hint_config").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("remote.create_fire_control.item_hint_lectern").withStyle(ChatFormatting.GRAY));
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) ControllerSessions.use(serverPlayer, hand, null);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() instanceof ServerPlayer player)
            ControllerSessions.use(player, context.getHand(), context.getClickedPos());
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }
}
