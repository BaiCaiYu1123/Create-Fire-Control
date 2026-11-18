package com.hooya.stabilizedturret.content.tiger;

import com.hooya.stabilizedturret.client.TigerISoundManager;
import com.hooya.stabilizedturret.content.turbine.GasTurbineBlockEntity;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.TorquePropagator;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Shared controls, two independent kinetic nodes at the same block centre. */
public final class TigerIEngineBlockEntity extends GasTurbineBlockEntity {
    private static final java.util.Set<TigerIEngineBlockEntity> LOADED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private Port leftPort,rightPort;
    private Direction portsFacing;
    private boolean portsActive=true;
    private float leftVisualSpeed,rightVisualSpeed,clientLoad;
    public TigerIEngineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TIGER_I_ENGINE.get(), pos, state);
    }
    @Override public void initialize() {
        super.initialize();
        if (level != null && !level.isClientSide && isControlPart()) {
            synchronized (LOADED) { LOADED.add(this); }
            TigerIEngineBlock.clearLegacyOutputs(level,worldPosition,getBlockState().getValue(TigerIEngineBlock.FACING));
            ensurePorts();
        }
    }
    @Override public void remove() {
        portsActive=false;
        removePorts();
        synchronized (LOADED) { LOADED.remove(this); }
        if (level != null && level.isClientSide) TigerISoundManager.remove(this);
        super.remove();
    }
    public static @Nullable TigerIEngineBlockEntity findLoadedForSettings(
            net.minecraft.server.level.ServerPlayer player, BlockPos pos, @Nullable java.util.UUID subLevelId) {
        TigerIEngineBlockEntity match = null;
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
        return getBlockState().getValue(TigerIEngineBlock.PART).isController();
    }
    public @Nullable TigerIEngineBlockEntity controller() {
        if (isControlPart()) return this;
        if (level == null) return null;
        BlockPos pos = TigerIEngineBlock.controllerPos(worldPosition, getBlockState());
        if (!level.hasChunkAt(pos)) return null;
        return level.getBlockEntity(pos) instanceof TigerIEngineBlockEntity engine
                && engine.isControlPart() && engine.getBlockState().getValue(TigerIEngineBlock.FACING)
                == getBlockState().getValue(TigerIEngineBlock.FACING) ? engine : null;
    }
    @Override protected void tickClientSound() {
        if (isControlPart()) TigerISoundManager.tick(this);
    }
    @Override public void tick() {
        super.tick();
        if (level==null || level.isClientSide || !isControlPart()) return;
        ensurePorts();
        leftPort.tick();rightPort.tick();
        float left=leftPort.getSpeed(),right=rightPort.getSpeed(),load=calculateEngineLoad();
        if (left!=leftVisualSpeed || right!=rightVisualSpeed || Math.abs(load-clientLoad)>.0001F) {
            leftVisualSpeed=left;rightVisualSpeed=right;clientLoad=load;sendData();
        }
    }
    @Override protected void applyKineticSpeeds(float left, float right) {
        super.applyKineticSpeeds(left, right);
        if (level==null || level.isClientSide || !isControlPart()) return;
        ensurePorts();
        leftPort.applyRequested(left);rightPort.applyRequested(right);
    }
    private void ensurePorts() {
        Direction facing=getBlockState().getValue(TigerIEngineBlock.FACING);
        if (leftPort!=null && portsFacing==facing) return;
        removePorts();portsFacing=facing;
        leftPort=new Port(this,facing.getCounterClockWise());rightPort=new Port(this,facing.getClockWise());
        leftPort.setLevel(level);rightPort.setLevel(level);
        leftPort.initialize();rightPort.initialize();
    }
    private void removePorts() {
        if(leftPort!=null)leftPort.remove();
        if(rightPort!=null)rightPort.remove();
        leftPort=rightPort=null;
    }
    public @Nullable Port port(Direction output) {
        if(!portsActive || level==null || level.isClientSide || !isControlPart()) return null;
        if(output.getAxis()!=getBlockState().getValue(TigerIEngineBlock.FACING).getClockWise().getAxis())return null;
        ensurePorts();return output==portsFacing.getCounterClockWise()?leftPort:rightPort;
    }
    public static net.minecraft.world.level.block.entity.BlockEntity resolvePortSource(
            net.minecraft.world.level.Level level,BlockPos source,BlockPos neighbour) {
        var block=level.getBlockEntity(source);
        if(block instanceof TigerIEngineBlockEntity engine && engine.isControlPart()) {
            BlockPos diff=neighbour.subtract(source);
            if(diff.distManhattan(BlockPos.ZERO)==1)
                return engine.port(Direction.getNearest(diff.getX(),diff.getY(),diff.getZ()));
        }
        return block;
    }
    public float halfShaftSpeed(Direction output) {
        boolean left=output==getBlockState().getValue(TigerIEngineBlock.FACING).getCounterClockWise();
        if(level!=null && level.isClientSide)return left?leftVisualSpeed:rightVisualSpeed;
        Port port=port(output);return port==null?0:port.getSpeed();
    }
    @Override public float getGeneratedSpeed() {
        return 0; // The controller is never a common kinetic source connecting the two sides.
    }
    @Override public float getOutputModifier(Direction direction) {
        return 0;
    }
    @Override public float propagateRotationTo(KineticBlockEntity target, BlockState sourceState,
            BlockState targetState, BlockPos offset, boolean axes, boolean cogs) {
        return axes ? getOutputModifier(Direction.getNearest(offset.getX(), offset.getY(), offset.getZ())) : 0;
    }
    @Override public float calculateEngineLoad() {
        if(level==null)return 0;
        if(level.isClientSide)return clientLoad;
        return leftPort==null?0:Math.max(leftPort.load(),rightPort.load());
    }
    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean packet) {
        super.write(tag, registries, packet);
        if (packet) {
            tag.putFloat("LeftPortSpeed",leftVisualSpeed);tag.putFloat("RightPortSpeed",rightVisualSpeed);
            tag.putFloat("PortLoad",clientLoad);
        }
    }
    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean packet) {
        super.read(tag, registries, packet);
        if(packet) {
            leftVisualSpeed=tag.getFloat("LeftPortSpeed");rightVisualSpeed=tag.getFloat("RightPortSpeed");
            clientLoad=tag.getFloat("PortLoad");
        }
    }
    /** Network node, owned/ticked by the engine; never a separate placed or saved block. */
    public static final class Port extends GeneratingKineticBlockEntity {
        private static final java.util.concurrent.atomic.AtomicLong IDS=new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
        private final TigerIEngineBlockEntity owner;
        public final Direction output;
        private final long id;
        private float requested;
        Port(TigerIEngineBlockEntity owner,Direction output) {
            super(ModBlockEntities.TIGER_I_ENGINE.get(),owner.getBlockPos(),owner.getBlockState());
            this.owner=owner;this.output=output;
            long candidate;
            do {candidate=IDS.incrementAndGet();}
            while(com.hooya.stabilizedturret.mixin.TigerITorqueAccessor.tigerI$networks()
                    .getOrDefault(owner.getLevel(),java.util.Map.of()).containsKey(candidate));
            id=candidate;
        }
        void applyRequested(float value) {
            boolean transientSource=hasSource();
            if(transientSource){detachKinetics();removeSource();}
            if(Math.abs(value-requested)<.001F && !transientSource)return;
            requested=value;updateGeneratedRotation();
        }
        @Override public float getGeneratedSpeed() {
            return owner.portsActive?convertToDirection(requested,owner.portsFacing.getCounterClockWise()):0;
        }
        @Override public Long createNetworkId(){return id;}
        @Override public float calculateAddedStressCapacity() {
            return lastCapacityProvided=(float)com.simibubi.create.api.stress.BlockStressValues.getCapacity(
                    com.simibubi.create.AllBlocks.CREATIVE_MOTOR.get());
        }
        public float load(){return capacity>0?net.minecraft.util.Mth.clamp(stress/capacity,0,1):0;}
        public boolean isLivePort() {
            return owner.portsActive && !owner.isRemoved()
                    && owner.getLevel().getBlockEntity(owner.getBlockPos()) == owner
                    && (owner.leftPort == this || owner.rightPort == this);
        }
        @Override public void sendData(){}
        @Override public void setChanged(){}
        @Override public int getFlickerScore(){return Math.min(super.getFlickerScore(),120);}
    }
}
