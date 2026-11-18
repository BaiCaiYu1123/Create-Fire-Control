package com.hooya.stabilizedturret.content.armor;

import net.neoforged.neoforge.client.model.data.ModelProperty;

public interface PolyArmorAccess {
    ModelProperty<ArmorMesh> MESH = new ModelProperty<>();
    ArmorDamage fireControl$armorDamage();
    void fireControl$armorChanged();
    int fireControl$armorLevel();
    void fireControl$setArmorLevel(int level);
}
