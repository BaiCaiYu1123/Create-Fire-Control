package com.hooya.stabilizedturret.mixin;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import rbasamoyai.createbigcannons.cannon_control.ControlPitchContraption;

/**
 * Compatibility shim for CBC Advanced Technology (cbc_at).
 *
 * <p>CBC 5.11.7 only declares the three-argument
 * {@code onRecoil(Vec3 recoilForce, Vec3 recoilPoint, AbstractContraptionEntity cannon)}
 * on {@link ControlPitchContraption}, but cbc_at 0.1.4c's
 * {@code MountedHeavyAutocannonContraption.fireShot} calls the legacy
 * two-argument {@code onRecoil(Vec3, AbstractContraptionEntity)}. Without this
 * bridge the call throws {@link NoSuchMethodError} at runtime.</p>
 *
 * <p>This mixin is only loaded when cbc_at is present, so unaffected
 * installations retain CBC's original surface.</p>
 */
@Mixin(value = ControlPitchContraption.class, remap = false)
public interface ControlPitchContraptionCompatMixin {

    @Unique
    default void onRecoil(Vec3 recoilForce, AbstractContraptionEntity cannon) {
        ((ControlPitchContraption) (Object) this)
                .onRecoil(recoilForce, cannon.position(), cannon);
    }
}
