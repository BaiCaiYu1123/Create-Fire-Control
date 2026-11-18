package com.hooya.stabilizedturret.content.diesel;

import com.hooya.stabilizedturret.client.Ztz99aDieselSoundManager;
import com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Shares the behavior controller with M1A2 and feeds two separate ordinary Create source cells. */
public final class Ztz99aDieselEngineBlockEntity extends GasTurbineBlockEntity {
    private static final java.util.Set<Ztz99aDieselEngineBlockEntity> LOADED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private float outputSpeed;
    private float modelAnimationTicks, previousModelAnimationTicks;
    private boolean compactLayoutReady;
    private int layoutCleanupTicks;

    public Ztz99aDieselEngineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ZTZ99A_DIESEL_ENGINE.get(), pos, state);
    }
    @Override public void initialize() {
        super.initialize();
        if (level != null && !level.isClientSide && isControlPart()) {
            synchronized (LOADED) { LOADED.add(this); }
            compactLayoutReady = ((Ztz99aDieselEngineBlock)getBlockState().getBlock()).removeLegacyModelCells(
                    level, worldPosition, getBlockState().getValue(Ztz99aDieselEngineBlock.FACING));
        }
    }
    @Override public void remove() {
        synchronized (LOADED) { LOADED.remove(this); }
        super.remove();
    }
    public static @Nullable Ztz99aDieselEngineBlockEntity findLoadedForSettings(
            net.minecraft.server.level.ServerPlayer player, BlockPos pos, @Nullable java.util.UUID subLevelId) {
        Ztz99aDieselEngineBlockEntity match = null;
        synchronized (LOADED) {
            for (var engine : java.util.Set.copyOf(LOADED)) {
                if (engine.isRemoved() || engine.level == null || engine.level.getServer() != player.getServer()
                        || !engine.level.dimension().equals(player.level().dimension()) || !engine.worldPosition.equals(pos)) continue;
                var containing = dev.ryanhcode.sable.Sable.HELPER.getContaining(engine);
                if (!java.util.Objects.equals(subLevelId, containing == null ? null : containing.getUniqueId())) continue;
                if (match != null && match != engine) return null;
                match = engine;
            }
        }
        return match;
    }
    @Override public boolean isControlPart() {
        return getBlockState().getValue(Ztz99aDieselEngineBlock.PART).isController();
    }
    public @Nullable Ztz99aDieselEngineBlockEntity controller() {
        if (isControlPart()) return this;
        if (level == null) return null;
        BlockPos pos = Ztz99aDieselEngineBlock.controllerPos(worldPosition, getBlockState());
        if (!level.hasChunkAt(pos)) return null;
        return level.getBlockEntity(pos) instanceof Ztz99aDieselEngineBlockEntity engine
                && engine.isControlPart() && engine.getBlockState().getValue(Ztz99aDieselEngineBlock.FACING)
                == getBlockState().getValue(Ztz99aDieselEngineBlock.FACING) ? engine : null;
    }
    @Override protected void tickClientSound() {
        if (isControlPart()) {
            previousModelAnimationTicks = modelAnimationTicks;
            if (isEngineEnabled()) modelAnimationTicks += 1;
            float cycle = Ztz99aDieselModelAnimation.LENGTH_SECONDS * 20;
            if (modelAnimationTicks >= cycle) {
                modelAnimationTicks -= cycle;
                previousModelAnimationTicks -= cycle;
            }
            Ztz99aDieselSoundManager.tick(this);
        }
    }
    public float getModelAnimationSeconds(float partialTicks) {
        return (previousModelAnimationTicks + (modelAnimationTicks - previousModelAnimationTicks) * partialTicks) / 20;
    }
    @Override public void tick() {
        super.tick();
        if (level != null && !level.isClientSide && isControlPart() && !compactLayoutReady && ++layoutCleanupTicks >= 20) {
            layoutCleanupTicks = 0;
            compactLayoutReady = ((Ztz99aDieselEngineBlock)getBlockState().getBlock()).removeLegacyModelCells(
                    level, worldPosition, getBlockState().getValue(Ztz99aDieselEngineBlock.FACING));
        }
        if (level != null && !level.isClientSide && !isControlPart()) refreshOutput();
    }
    @Override protected void applyKineticSpeeds(float left, float right) {
        super.applyKineticSpeeds(left, right);
        if (level == null) return;
        for (var part : Ztz99aDieselEnginePart.values()) {
            if (!part.isOutput()) continue;
            BlockPos pos = worldPosition.offset(Ztz99aDieselEngineBlock.offset(part,
                    getBlockState().getValue(Ztz99aDieselEngineBlock.FACING)));
            if (level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof Ztz99aDieselEngineBlockEntity port)
                port.refreshOutput();
        }
    }
    private void refreshOutput() {
        var control = controller();
        float requested = control == null ? 0 : getBlockState().getValue(Ztz99aDieselEngineBlock.PART).x < 0
                ? control.getCurrentLeftSpeed() : control.getCurrentRightSpeed();
        boolean hadSource = hasSource();
        if (hadSource) { detachKinetics(); removeSource(); }
        if (Math.abs(requested - outputSpeed) < 0.001F && !hadSource) return;
        outputSpeed = requested;
        updateGeneratedRotation();
        setChanged();
        sendData();
    }
    @Override public float getGeneratedSpeed() {
        // Neither port exposes an inward shaft, so there is no internal kinetic edge.
        if (isControlPart()) return 0;
        Direction left = getBlockState().getValue(Ztz99aDieselEngineBlock.FACING).getCounterClockWise();
        return convertToDirection(outputSpeed, left);
    }
    @Override public float getOutputModifier(Direction direction) {
        return direction == Ztz99aDieselEngineBlock.outputDirection(getBlockState()) ? 1 : 0;
    }
    @Override public float propagateRotationTo(KineticBlockEntity target, BlockState sourceState,
            BlockState targetState, BlockPos offset, boolean axes, boolean cogs) {
        return axes ? getOutputModifier(Direction.getNearest(offset.getX(), offset.getY(), offset.getZ())) : 0;
    }
    @Override public float calculateEngineLoad() {
        if (!isControlPart()) return super.calculateEngineLoad();
        float load = 0;
        if (level == null) return load;
        for (var part : Ztz99aDieselEnginePart.values()) {
            if (!part.isOutput()) continue;
            BlockPos pos = worldPosition.offset(Ztz99aDieselEngineBlock.offset(part,
                    getBlockState().getValue(Ztz99aDieselEngineBlock.FACING)));
            if (level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof Ztz99aDieselEngineBlockEntity port)
                load = Math.max(load, port.calculateEngineLoad());
        }
        return load;
    }
    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean packet) {
        super.write(tag, registries, packet);
        if (packet) tag.putFloat("OutputSpeed", outputSpeed);
    }
    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean packet) {
        super.read(tag, registries, packet);
        outputSpeed = packet ? tag.getFloat("OutputSpeed") : 0;
    }
}
