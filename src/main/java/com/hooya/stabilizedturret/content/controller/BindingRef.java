package com.hooya.stabilizedturret.content.controller;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.Objects;
import java.util.UUID;

/**
 * Persistent reference to one particular bound block. Besides its plot UUID
 * and position, the block id and live block-entity instance are checked so a
 * replacement at the same coordinates cannot inherit an old binding.
 */
public final class BindingRef {
    private final BlockPos pos;
    private final @Nullable UUID subLevelId;
    private @Nullable ResourceLocation blockId;
    private final boolean expectsBlockEntity;
    private @Nullable BlockEntity observedBlockEntity;

    private BindingRef(
            BlockPos pos,
            @Nullable UUID subLevelId,
            @Nullable ResourceLocation blockId,
            boolean expectsBlockEntity,
            @Nullable BlockEntity observedBlockEntity
    ) {
        this.pos = pos.immutable();
        this.subLevelId = subLevelId;
        this.blockId = blockId;
        this.expectsBlockEntity = expectsBlockEntity;
        this.observedBlockEntity = observedBlockEntity;
    }

    public static BindingRef capture(Level level, BlockPos pos) {
        SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return new BindingRef(
                pos,
                subLevel == null ? null : subLevel.getUniqueId(),
                BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()),
                blockEntity != null,
                blockEntity
        );
    }

    public BlockPos pos() {
        return pos;
    }

    public @Nullable UUID subLevelId() {
        return subLevelId;
    }

    public boolean isValidIn(Level level) {
        SubLevel current = Sable.HELPER.getContaining(level, pos);
        if (subLevelId == null) {
            return current == null;
        }
        return current != null && !current.isRemoved() && subLevelId.equals(current.getUniqueId());
    }

    /**
     * Returns false once the original target has been broken or replaced.
     * A null observed instance after world loading is adopted once; afterwards
     * object identity catches same-block replacements at the same position.
     */
    public boolean isSameTarget(Level level) {
        if (!isValidIn(level)) {
            return false;
        }
        if (!level.hasChunkAt(pos)) {
            return true;
        }
        ResourceLocation currentId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        if (blockId == null) {
            blockId = currentId;
        }
        boolean adjustableLauncherTransition =
                MianbaoMissileCompat.isSameAdjustableLauncherVariant(blockId, currentId)
                        || MianbaoAircraftCompat.isSameRackVariant(blockId, currentId);
        if (!blockId.equals(currentId) && !adjustableLauncherTransition) {
            return false;
        }
        if (!expectsBlockEntity) {
            return !level.getBlockState(pos).isAir();
        }
        BlockEntity current = level.getBlockEntity(pos);
        if (current == null || current.isRemoved()) {
            return false;
        }
        if (observedBlockEntity == null) {
            observedBlockEntity = current;
            return true;
        }
        if (adjustableLauncherTransition) {
            // Mianbao swaps the adjustable launcher block and block-entity
            // instance when a drill changes its height. Preserve this one
            // intentional device transition without relaxing other bindings.
            blockId = currentId;
            observedBlockEntity = current;
        }
        return observedBlockEntity == current;
    }

    public @Nullable BlockEntity resolve(Level level) {
        return isSameTarget(level) ? level.getBlockEntity(pos) : null;
    }

    public boolean matches(Level level, BlockPos otherPos) {
        if (!pos.equals(otherPos)) {
            return false;
        }
        SubLevel other = Sable.HELPER.getContaining(level, otherPos);
        return subLevelId == null ? other == null : other != null && subLevelId.equals(other.getUniqueId());
    }

    public boolean matchesEntityPosition(Level level, Entity entity) {
        if (entity == null || !isSameTarget(level)) return false;
        if (subLevelId == null) {
            return entity.level() == level && entity.blockPosition().equals(pos);
        }
        SubLevel subLevel = SubLevelContainer.getContainer(level).getSubLevel(subLevelId);
        if (subLevel == null || subLevel.isRemoved()) {
            return false;
        }

        // A Sable seat entity may live directly in the physical sublevel or
        // be mirrored into the parent world. In both cases require the exact
        // bound block coordinate; a radius check grants every nearby cushion
        // the operator seat's permissions.
        if (entity.level() == subLevel.getLevel()) {
            return entity.blockPosition().equals(pos);
        }
        Vector3d local = new Vector3d(entity.getX(), entity.getY(), entity.getZ());
        subLevel.logicalPose().transformPositionInverse(local);
        return BlockPos.containing(local.x, local.y, local.z).equals(pos);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Pos", pos.asLong());
        if (blockId != null) {
            tag.putString("Block", blockId.toString());
        }
        tag.putBoolean("HasBlockEntity", expectsBlockEntity);
        if (subLevelId != null) {
            tag.putUUID("SubLevel", subLevelId);
        }
        return tag;
    }

    public static @Nullable BindingRef load(CompoundTag owner, String key) {
        if (!owner.contains(key)) {
            return null;
        }
        return loadTag(owner.getCompound(key));
    }

    public static BindingRef loadTag(CompoundTag tag) {
        UUID subLevel = tag.hasUUID("SubLevel") ? tag.getUUID("SubLevel") : null;
        ResourceLocation blockId = tag.contains("Block")
                ? ResourceLocation.tryParse(tag.getString("Block"))
                : null;
        return new BindingRef(
                BlockPos.of(tag.getLong("Pos")),
                subLevel,
                blockId,
                tag.getBoolean("HasBlockEntity"),
                null
        );
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof BindingRef other)) {
            return false;
        }
        return pos.equals(other.pos) && Objects.equals(subLevelId, other.subLevelId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pos, subLevelId);
    }
}
