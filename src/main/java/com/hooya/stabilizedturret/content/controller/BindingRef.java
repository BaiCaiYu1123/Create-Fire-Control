package com.hooya.stabilizedturret.content.controller;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import com.hooya.stabilizedturret.content.remote.WirelessSignalProcessorBlockEntity;
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
    public static final String WIRELESS_PROCESSOR_ID_TAG = "WirelessProcessorIdentity";
    private final BlockPos pos;
    private final @Nullable UUID subLevelId;
    private @Nullable ResourceLocation dimensionId;
    private @Nullable ResourceLocation blockId;
    private final boolean expectsBlockEntity;
    private @Nullable BlockEntity observedBlockEntity;
    private final @Nullable UUID wirelessProcessorId;

    private BindingRef(
            BlockPos pos,
            @Nullable UUID subLevelId,
            @Nullable ResourceLocation dimensionId,
            @Nullable ResourceLocation blockId,
            boolean expectsBlockEntity,
            @Nullable BlockEntity observedBlockEntity,
            @Nullable UUID wirelessProcessorId
    ) {
        this.pos = pos.immutable();
        this.subLevelId = subLevelId;
        this.dimensionId = dimensionId;
        this.blockId = blockId;
        this.expectsBlockEntity = expectsBlockEntity;
        this.observedBlockEntity = observedBlockEntity;
        this.wirelessProcessorId = wirelessProcessorId;
    }

    public static BindingRef capture(Level level, BlockPos pos) {
        SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return new BindingRef(
                pos,
                subLevel == null ? null : subLevel.getUniqueId(),
                level.dimension().location(),
                BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()),
                blockEntity != null,
                blockEntity,
                blockEntity instanceof WirelessSignalProcessorBlockEntity processor ? processor.identity() : null
        );
    }

    public BlockPos pos() {
        return pos;
    }

    public @Nullable UUID subLevelId() {
        return subLevelId;
    }

    public boolean hasWirelessProcessorIdentity() {
        return wirelessProcessorId != null;
    }

    /** Returns a new reference after a terminal moves; coordinates used as map keys stay immutable. */
    public BindingRef refreshWirelessTerminal(Level level) {
        if (level == null || level.isClientSide || !isInDimension(level) || !expectsBlockEntity) return this;
        if (wirelessProcessorId == null) {
            // Upgrade an old, still-valid connection without guessing a missing target.
            return resolve(level) instanceof WirelessSignalProcessorBlockEntity ? capture(level, pos) : this;
        }
        if (level.hasChunkAt(pos)
                && level.getBlockEntity(pos) instanceof WirelessSignalProcessorBlockEntity processor
                && !processor.isRemoved() && wirelessProcessorId.equals(processor.identity())) {
            return isValidIn(level) && observedBlockEntity == processor ? this : capture(level, pos);
        }
        var moved = WirelessSignalProcessorBlockEntity.findLoaded(level, wirelessProcessorId);
        return moved == null ? this : capture(level, moved.getBlockPos());
    }

    public boolean isValidIn(Level level) {
        if (dimensionId != null && !dimensionId.equals(level.dimension().location())) {
            return false;
        }
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
                MianbaoLauncherIdMigration.isRenamedLauncher(blockId, currentId)
                        || MianbaoMissileCompat.isSameAdjustableLauncherVariant(blockId, currentId)
                        || MianbaoAircraftCompat.isSameRackVariant(blockId, currentId)
                        || MianbaoRocketPodCompat.isSamePodVariant(blockId, currentId);
        if (!blockId.equals(currentId) && !adjustableLauncherTransition) {
            return false;
        }
        if (!expectsBlockEntity) {
            return !level.getBlockState(pos).isAir();
        }
        BlockEntity current = level.getBlockEntity(pos);
        if (current == null && MianbaoAircraftCompat.isSameRackVariant(blockId, currentId)
                && !level.getBlockState(pos).hasBlockEntity()) {
            blockId = currentId;
            observedBlockEntity = null;
            return true;
        }
        if (current == null || current.isRemoved()) {
            return false;
        }
        if (wirelessProcessorId != null && (!(current instanceof WirelessSignalProcessorBlockEntity processor)
                || !wirelessProcessorId.equals(processor.identity()))) {
            return false;
        }
        if (observedBlockEntity == null) {
            observedBlockEntity = current;
            if (adjustableLauncherTransition) blockId = currentId;
            return true;
        }
        if (adjustableLauncherTransition) {
            // Mianbao swaps several loaded launchers to another block (and in
            // some families another block-entity instance) as ammunition or
            // adjustable height changes. Preserve only those known families.
            blockId = currentId;
            observedBlockEntity = current;
        }
        return observedBlockEntity == current;
    }

    /**
     * Resolves a Mianbao anti-air launcher after Sable/Synaxis has rebuilt its
     * block entity during welding. This deliberately keeps the normal binding
     * rules strict: only the exact saved dimension, plot, position and launcher
     * block id may refresh the observed Java instance.
     */
    public @Nullable BlockEntity resolveRebuiltAirDefenseLauncher(Level level) {
        if (level == null || !level.hasChunkAt(pos) || !isValidIn(level)
                || blockId == null || !MianbaoAirDefenseCompat.isLauncherId(blockId)) {
            return null;
        }
        ResourceLocation currentId = BuiltInRegistries.BLOCK.getKey(
                level.getBlockState(pos).getBlock());
        if (!blockId.equals(currentId)
                && !MianbaoLauncherIdMigration.isRenamedLauncher(blockId, currentId)) return null;
        BlockEntity current = level.getBlockEntity(pos);
        if (current == null || current.isRemoved()
                || !MianbaoAirDefenseCompat.isLauncher(current.getBlockState())) {
            return null;
        }
        observedBlockEntity = current;
        blockId = currentId;
        return current;
    }

    public @Nullable BlockEntity resolve(Level level) {
        // getBlockEntity may synchronously obtain a FULL chunk.  A persistent
        // binding is allowed to survive an unload, but resolving it must never
        // be the operation that loads that chunk.
        return level.hasChunkAt(pos) && isSameTarget(level) ? level.getBlockEntity(pos) : null;
    }

    public boolean isInDimension(Level level) {
        return dimensionId == null || dimensionId.equals(level.dimension().location());
    }

    /** World-space centre used by authoritative binding-distance checks. */
    public @Nullable net.minecraft.world.phys.Vec3 worldCenter(Level level) {
        if (!isInDimension(level) || !level.hasChunkAt(pos) || !isValidIn(level)) return null;
        if (dimensionId == null) dimensionId = level.dimension().location();
        if (subLevelId == null) return net.minecraft.world.phys.Vec3.atCenterOf(pos);
        SubLevel subLevel = SubLevelContainer.getContainer(level).getSubLevel(subLevelId);
        if (subLevel == null || subLevel.isRemoved()) return null;
        Vector3d world = subLevel.logicalPose().transformPosition(new Vector3d(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
        if (!Double.isFinite(world.x) || !Double.isFinite(world.y) || !Double.isFinite(world.z)) {
            return null;
        }
        return new net.minecraft.world.phys.Vec3(world.x, world.y, world.z);
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
        if (wirelessProcessorId != null) tag.putUUID(WIRELESS_PROCESSOR_ID_TAG, wirelessProcessorId);
        if (subLevelId != null) {
            tag.putUUID("SubLevel", subLevelId);
        }
        if (dimensionId != null) {
            tag.putString("Dimension", dimensionId.toString());
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
        ResourceLocation dimensionId = tag.contains("Dimension", CompoundTag.TAG_STRING)
                ? ResourceLocation.tryParse(tag.getString("Dimension"))
                : null;
        return new BindingRef(
                BlockPos.of(tag.getLong("Pos")),
                subLevel,
                dimensionId,
                blockId,
                tag.getBoolean("HasBlockEntity"),
                null,
                tag.hasUUID(WIRELESS_PROCESSOR_ID_TAG) ? tag.getUUID(WIRELESS_PROCESSOR_ID_TAG) : null
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
        return pos.equals(other.pos)
                && Objects.equals(subLevelId, other.subLevelId)
                && Objects.equals(dimensionId, other.dimensionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pos, subLevelId, dimensionId);
    }
}
