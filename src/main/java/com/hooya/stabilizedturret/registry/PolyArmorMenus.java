package com.hooya.stabilizedturret.registry;

import com.caca.copypoly.PolyBlockEntity;
import com.caca.copypoly.PolyMenu;
import com.caca.copypoly.PolyPackets;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.armor.*;
import com.hooya.stabilizedturret.network.ArmorMeshResultPayload;
import com.hooya.stabilizedturret.network.ArmorMeshSnapshotPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.*;

public final class PolyArmorMenus {
    public static final DeferredRegister<MenuType<?>> REGISTER=DeferredRegister.create(Registries.MENU,StabilizedTurret.MOD_ID);
    public static final DeferredHolder<MenuType<?>,MenuType<ArmorMeshMenu>> EDITOR=REGISTER.register("polyhedral_armor_editor",()->IMenuTypeExtension.create(ArmorMeshMenu::new));
    public static final DeferredHolder<MenuType<?>,MenuType<ArmorGroupMenu>> GROUP_EDITOR =
        REGISTER.register("polyhedral_armor_group",() -> IMenuTypeExtension.create(ArmorGroupMenu::new));

    public static void openGroup(ServerPlayer player, PolyMenu menu) {
        try {
            if (!menu.stillValid(player)) throw new IllegalArgumentException("permission");
            ArmorGroupMenu.open(player,menu.pos,menu.revision);
        } catch (IllegalArgumentException rejected) {
            PacketDistributor.sendToPlayer(player,new PolyPackets.Result(menu.containerId,rejected.getMessage()));
        }
    }

    public static void open(ServerPlayer player,BlockPos pos,PolyBlockEntity be){
        var damage = ((PolyArmorAccess) be).fireControl$armorDamage();
        EditableArmorMesh snapshot = null;
        try {
            snapshot = damage.editable(be.poly(), be.document());
        } catch (IllegalArgumentException limit) {
            if (damage.holes().isEmpty()) {
                player.displayClientMessage(Component.translatable("screen.create_fire_control.armor.error.mesh_limit"), true);
                return;
            }
            // Clearing stored bores needs no editable triangulation.
        }
        player.openMenu(new SimpleMenuProvider((id,inventory,p)->new ArmorMeshMenu(id,inventory,pos,be.revision()),
                Component.translatable("screen.create_fire_control.armor.title")),buffer->{buffer.writeBlockPos(pos);buffer.writeVarInt(be.revision());});
        if (player.containerMenu instanceof ArmorMeshMenu menu) {
            if (snapshot == null) {
                PacketDistributor.sendToPlayer(player, new ArmorMeshResultPayload(menu.containerId, "mesh_limit"));
            } else {
                menu.snapshot(snapshot);
                PacketDistributor.sendToPlayer(player, new ArmorMeshSnapshotPayload(menu.containerId, pos, menu.revision, snapshot));
            }
        }
    }
    private PolyArmorMenus(){}
}
