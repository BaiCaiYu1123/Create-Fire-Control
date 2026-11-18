package com.hooya.stabilizedturret.compat.mianbao;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/** Persistent attachment data mixed into every CBC mounted cannon contraption. */
public interface MountedHeavyMachineGunCarrier {
    @Nullable Attachment createFireControl$getMountedHeavyMachineGun(Side side);

    void createFireControl$setMountedHeavyMachineGun(Side side, @Nullable Attachment attachment);

    default boolean createFireControl$hasMountedHeavyMachineGun() {
        return createFireControl$getMountedHeavyMachineGun(Side.LEFT) != null
                || createFireControl$getMountedHeavyMachineGun(Side.RIGHT) != null;
    }

    enum Side {
        LEFT,
        RIGHT
    }

    /** anchor is the cannon block that the player clicked; spacing is in model pixels. */
    record Attachment(BlockPos anchor, int spacingPixels) {
        public Attachment {
            anchor = anchor.immutable();
            spacingPixels = Math.max(-16, Math.min(0, spacingPixels));
        }

        public Attachment withSpacing(int pixels) {
            return new Attachment(anchor, pixels);
        }
    }
}
