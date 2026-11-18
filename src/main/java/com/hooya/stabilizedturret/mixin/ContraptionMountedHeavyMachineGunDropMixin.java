package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.compat.mianbao.MountedHeavyMachineGunCarrier;
import com.hooya.stabilizedturret.compat.mianbao.MountedHeavyMachineGunCompat;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.StructureTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Returns virtual attachments as items when CBC disassembles the cannon. */
@Mixin(Contraption.class)
public abstract class ContraptionMountedHeavyMachineGunDropMixin {
    @Inject(method = "addBlocksToWorld", at = @At("TAIL"))
    private void createFireControl$dropMountedHeavyMachineGuns(
            Level level, StructureTransform transform, CallbackInfo ci
    ) {
        if (level.isClientSide
                || !((Object) this instanceof MountedHeavyMachineGunCarrier carrier)
                || !carrier.createFireControl$hasMountedHeavyMachineGun()) {
            return;
        }
        Item item = BuiltInRegistries.ITEM.get(MountedHeavyMachineGunCompat.HEAVY_MACHINE_GUN);
        for (MountedHeavyMachineGunCarrier.Side side
                : MountedHeavyMachineGunCarrier.Side.values()) {
            MountedHeavyMachineGunCarrier.Attachment attachment =
                    carrier.createFireControl$getMountedHeavyMachineGun(side);
            if (attachment == null) continue;
            BlockPos dropPos = transform.apply(attachment.anchor());
            level.addFreshEntity(new ItemEntity(
                    level,
                    dropPos.getX() + 0.5D,
                    dropPos.getY() + 0.75D,
                    dropPos.getZ() + 0.5D,
                    new ItemStack(item)));
            carrier.createFireControl$setMountedHeavyMachineGun(side, null);
        }
    }
}
