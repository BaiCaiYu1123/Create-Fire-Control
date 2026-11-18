package com.hooya.stabilizedturret.mixin;
import com.caca.copypoly.geometry.Polyhedron;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(targets="com.caca.copypoly.compat.PolyRapierColliders$Entry",remap=false)
public interface PolyArmorRapierEntryAccessor {
    @Accessor("geometry") void fireControl$clearGeometry(Polyhedron geometry);
}
