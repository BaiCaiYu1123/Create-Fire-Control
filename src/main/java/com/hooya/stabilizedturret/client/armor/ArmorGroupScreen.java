package com.hooya.stabilizedturret.client.armor;

import com.caca.copypoly.PolyBlockEntity;
import com.caca.copypoly.api.PolyShape;
import com.caca.copypoly.client.PolyScreen;
import com.caca.copypoly.geometry.Polyhedron;
import com.hooya.stabilizedturret.content.armor.ArmorGroupMenu;
import com.hooya.stabilizedturret.content.armor.ArmorMesh;
import com.hooya.stabilizedturret.content.armor.PolyArmorAccess;
import com.hooya.stabilizedturret.network.ApplyArmorGroupPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The shared editor presents one surface and temporarily hides the old per-cell models. */
public final class ArmorGroupScreen extends PolyScreen {
    private static final Polyhedron HIDDEN_POLY = Polyhedron.surface(List.of(),List.of());
    private static final ArmorMesh HIDDEN_ARMOR = new ArmorMesh(List.of());
    private final ArmorGroupMenu menu;
    private final ArmorGroupPreview materials;
    private final Set<PolyBlockEntity> hidden = new LinkedHashSet<>();
    private final Set<BlockPos> synchronizedMembers = new LinkedHashSet<>();

    public ArmorGroupScreen(ArmorGroupMenu menu, Inventory inventory, Component title) {
        super(menu,inventory,title);
        this.menu = menu;
        this.materials = new ArmorGroupPreview(menu);
    }

    @Override protected BlockPos editOrigin() { return menu.origin(); }
    @Override protected int editingBlockCount() { return menu.members().size(); }
    @Override protected boolean canRepairGroup() { return menu.hasHoles(); }
    @Override protected void requestGroupRepair() {
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId,ArmorGroupMenu.CLEAR_HOLES_BUTTON);
    }

    @Override protected boolean targetChanged() {
        if (minecraft.level == null) return true;
        for (var member : menu.members()) {
            if (!(minecraft.level.getBlockEntity(member.pos()) instanceof PolyBlockEntity be)) return true;
            int revisionDelta = be.revision() - member.revision();
            if (revisionDelta == 0) synchronizedMembers.add(member.pos());
            // The new menu can precede queued block updates, especially on Sable plots.
            else if (revisionDelta > 0 || synchronizedMembers.contains(member.pos())) return true;
        }
        return false;
    }

    @Override protected List<MaterialPreview> previewMaterials(Polyhedron draft) {
        return materials.table(minecraft,draft);
    }

    @Override protected void renderWorldDraft(Polyhedron draft,Matrix4f view,Matrix4d transform,Vec3 camera) {
        materials.renderWorld(minecraft,draft,view,transform,camera);
    }

    @Override protected void setWorldPreview(Polyhedron draft) {
        if (draft == null) {
            for (var be : hidden) {
                ((PolyArmorAccess)be).fireControl$armorDamage().setClientPreview(null);
                be.setClientPreview(null);
            }
            hidden.clear();
        } else if (minecraft.level != null) {
            for (var member : menu.members()) {
                if (minecraft.level.getBlockEntity(member.pos()) instanceof PolyBlockEntity be && hidden.add(be)) {
                    ((PolyArmorAccess)be).fireControl$armorDamage().setClientPreview(HIDDEN_ARMOR);
                    be.setClientPreview(HIDDEN_POLY);
                }
            }
        }
    }

    @Override protected void submit(PolyShape shape) {
        PacketDistributor.sendToServer(new ApplyArmorGroupPayload(menu.containerId,shape.packed()));
    }
}
