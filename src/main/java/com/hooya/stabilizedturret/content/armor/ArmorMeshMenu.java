package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.Copypoly;
import com.caca.copypoly.PolyBlockEntity;
import com.caca.copypoly.PolyMenu;
import com.hooya.stabilizedturret.network.ArmorMeshResultPayload;
import com.hooya.stabilizedturret.registry.PolyArmorMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ArmorMeshMenu extends AbstractContainerMenu {
    public static final int CLEAR_HOLES_BUTTON = 0;
    public final BlockPos pos;
    public final int revision;
    private final Level level;
    private final BlockEntity target;
    private EditableArmorMesh snapshot;
    private String result;
    public ArmorMeshMenu(int id,Inventory inventory,RegistryFriendlyByteBuf buffer){this(id,inventory,buffer.readBlockPos(),buffer.readVarInt());}
    public ArmorMeshMenu(int id,Inventory inventory,BlockPos pos,int revision){super(PolyArmorMenus.EDITOR.get(),id);this.pos=pos.immutable();this.revision=revision;level=inventory.player.level();target=level.getBlockEntity(pos);}
    public EditableArmorMesh snapshot(){return snapshot;}
    public void snapshot(EditableArmorMesh value){snapshot=value;}
    public void result(String value){result=value;}
    public String takeResult(){var value=result;result=null;return value;}
    @Override public boolean stillValid(Player player){return player.isAlive()&&player.mayBuild()&&player.level()==level&&level.getBlockState(pos).is(Copypoly.POLY.get())
            &&(level.isClientSide||target!=null&&!target.isRemoved()&&level.getBlockEntity(pos)==target)&&player.canInteractWithBlock(pos,16-player.blockInteractionRange());}
    @Override
    public boolean clickMenuButton(Player player, int button) {
        if (!(player instanceof ServerPlayer server)) return false;
        if (button == PolyMenu.MULTI_EDIT_BUTTON) {
            try {
                if (player.containerMenu != this || !stillValid(player)) throw new IllegalArgumentException("permission");
                ArmorGroupMenu.open(server,pos,revision);
            } catch (IllegalArgumentException rejected) {
                PacketDistributor.sendToPlayer(server,new ArmorMeshResultPayload(containerId,rejected.getMessage()));
            }
            return true;
        }
        if (button != CLEAR_HOLES_BUTTON) return false;
        PacketDistributor.sendToPlayer(server, new ArmorMeshResultPayload(containerId, clearHoles(server)));
        return true;
    }

    private String clearHoles(ServerPlayer player) {
        if (player.containerMenu != this || !stillValid(player) || !level.mayInteract(player, pos)) {
            return "permission";
        }
        if (!(target instanceof PolyBlockEntity be) || !(be instanceof PolyArmorAccess armor)
                || revision != be.revision() || armor.fireControl$armorDamage().holes().isEmpty()) {
            return "changed";
        }
        if (!be.tryBeginEdit()) return "rate";

        armor.fireControl$armorDamage().clearHoles();
        armor.fireControl$armorChanged();
        return "";
    }

    @Override public ItemStack quickMoveStack(Player player,int slot){return ItemStack.EMPTY;}
}
