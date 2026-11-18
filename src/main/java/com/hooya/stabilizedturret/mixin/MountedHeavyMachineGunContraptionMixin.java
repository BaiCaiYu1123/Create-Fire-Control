package com.hooya.stabilizedturret.mixin;

import com.hooya.stabilizedturret.compat.mianbao.MountedHeavyMachineGunCarrier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;

/** Stores side-mounted machine guns in the cannon's normal spawn/save NBT. */
@Mixin(AbstractMountedCannonContraption.class)
public abstract class MountedHeavyMachineGunContraptionMixin
        implements MountedHeavyMachineGunCarrier {
    @Unique private static final String CREATE_FIRE_CONTROL$TAG =
            "CreateFireControlMountedHeavyMachineGuns";
    @Unique private @Nullable Attachment createFireControl$leftMachineGun;
    @Unique private @Nullable Attachment createFireControl$rightMachineGun;

    @Override
    public @Nullable Attachment createFireControl$getMountedHeavyMachineGun(Side side) {
        return side == Side.LEFT
                ? createFireControl$leftMachineGun : createFireControl$rightMachineGun;
    }

    @Override
    public void createFireControl$setMountedHeavyMachineGun(
            Side side, @Nullable Attachment attachment
    ) {
        if (side == Side.LEFT) createFireControl$leftMachineGun = attachment;
        else createFireControl$rightMachineGun = attachment;
    }

    @Inject(method = "writeNBT", at = @At("RETURN"))
    private void createFireControl$writeMountedHeavyMachineGuns(
            HolderLookup.Provider registries,
            boolean spawnPacket,
            CallbackInfoReturnable<CompoundTag> cir
    ) {
        CompoundTag attachments = new CompoundTag();
        createFireControl$writeAttachment(
                attachments, "Left", createFireControl$leftMachineGun);
        createFireControl$writeAttachment(
                attachments, "Right", createFireControl$rightMachineGun);
        if (!attachments.isEmpty()) cir.getReturnValue().put(CREATE_FIRE_CONTROL$TAG, attachments);
    }

    @Inject(method = "readNBT", at = @At("TAIL"))
    private void createFireControl$readMountedHeavyMachineGuns(
            Level level,
            CompoundTag tag,
            boolean clientData,
            CallbackInfo ci
    ) {
        CompoundTag attachments = tag.getCompound(CREATE_FIRE_CONTROL$TAG);
        createFireControl$leftMachineGun = createFireControl$readAttachment(attachments, "Left");
        createFireControl$rightMachineGun = createFireControl$readAttachment(attachments, "Right");
    }

    @Unique
    private static void createFireControl$writeAttachment(
            CompoundTag owner, String key, @Nullable Attachment attachment
    ) {
        if (attachment == null) return;
        CompoundTag tag = new CompoundTag();
        tag.putLong("Anchor", attachment.anchor().asLong());
        tag.putInt("SpacingPixels", attachment.spacingPixels());
        owner.put(key, tag);
    }

    @Unique
    private static @Nullable Attachment createFireControl$readAttachment(
            CompoundTag owner, String key
    ) {
        if (!owner.contains(key, CompoundTag.TAG_COMPOUND)) return null;
        CompoundTag tag = owner.getCompound(key);
        return new Attachment(
                net.minecraft.core.BlockPos.of(tag.getLong("Anchor")),
                tag.getInt("SpacingPixels"));
    }
}
