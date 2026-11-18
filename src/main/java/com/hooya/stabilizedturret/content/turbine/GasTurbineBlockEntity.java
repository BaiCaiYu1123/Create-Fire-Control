package com.hooya.stabilizedturret.content.turbine;

import com.hooya.stabilizedturret.client.GasTurbineSoundManager;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.DriveByWireCompat;
import com.hooya.stabilizedturret.content.remote.ControllerInput;
import com.hooya.stabilizedturret.content.remote.ControllerSessions;
import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.hooya.stabilizedturret.content.controller.FireControlInputRouter;
import com.hooya.stabilizedturret.network.EngineSettingsCodec;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Map;

public class GasTurbineBlockEntity extends GeneratingKineticBlockEntity {
    public static final float MAX_SPEED = 1024.0F;
    public static final float DEFAULT_SOUND_VOLUME = 150.0F;
    public static final float MAX_SOUND_VOLUME = 300.0F;
    private static final int KINETIC_UPDATE_INTERVAL = 2;
    private static final float EPSILON = 0.001F;

    private EngineControlSettings controlSettings = EngineControlSettings.defaults();
    private final EngineBehaviorController behaviorController = new EngineBehaviorController();
    private final EngineSoundRpmController soundRpmController = new EngineSoundRpmController();
    private float soundRpmTarget;
    private int soundSyncTicks;
    private boolean soundRpmInitialized;
    private @Nullable BindingRef advancedHub;
    private RemoteControllerCompat.Type terminalType=RemoteControllerCompat.Type.WIRELESS;
    private final EngineRedstoneInputs redstoneInputs = new EngineRedstoneInputs(this);
    private static final java.util.Set<GasTurbineBlockEntity> LOADED=java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    @Override public void onLoad(){super.onLoad();redstoneInputs.invalidate();synchronized(LOADED){LOADED.add(this);}}
    @Override public void remove(){redstoneInputs.close();synchronized(LOADED){LOADED.remove(this);}super.remove();}
    @Override public void invalidate(){redstoneInputs.close();super.invalidate();}
    public RemoteControllerCompat.Type getTerminalType(){return terminalType;}
    public static java.util.Set<String> channelsForTerminal(net.minecraft.world.level.Level world,BlockPos pos) {
        java.util.Set<String> channels=new java.util.HashSet<>();
        synchronized(LOADED) {
            for(var engine:java.util.Set.copyOf(LOADED)) {
                if(engine.isRemoved()||engine.level!=world||!engine.isControlPart()
                        ||engine.terminalType!=RemoteControllerCompat.Type.WIRELESS||!engine.hasValidAdvancedHub()
                        ||!engine.advancedHub.pos().equals(pos))continue;
                channels.addAll(com.hooya.stabilizedturret.content.remote.ControllerInput.chordInputs(engine.controlSettings.engineToggleKey()));
                for(var behavior:engine.controlSettings.behaviors())for(String key:behavior.keys())
                    channels.addAll(com.hooya.stabilizedturret.content.remote.ControllerInput.chordInputs(key));
            }
        }
        return java.util.Set.copyOf(channels);
    }
    private boolean engineEnabled;
    private boolean engineTogglePressed;
    private float targetLeftSpeed;
    private float targetRightSpeed;
    private float currentLeftSpeed;
    private float currentRightSpeed;
    private float appliedLeftSpeed;
    private float appliedRightSpeed;
    private int kineticUpdateTicks;
    private int lastBehaviorIndex;

    public GasTurbineBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.GAS_TURBINE.get(), pos, state);
    }
    protected GasTurbineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }
    protected boolean isControlPart() {
        return getBlockState().getBlock() instanceof GasTurbineBlock
                && getBlockState().getValue(GasTurbineBlock.PART) == GasTurbinePart.NEGATIVE;
    }
    protected void tickClientSound() { GasTurbineSoundManager.tick(this); }
    @Override public void initialize() {
        super.initialize();
        if (!hasSource()) updateGeneratedRotation();
    }
    @Override public void tick() {
        super.tick();
        if (level == null) return;
        if (level.isClientSide) {
            soundRpmController.tickClient(controlSettings, engineEnabled, 1.0F / 20);
            if (FMLEnvironment.dist == Dist.CLIENT) tickClientSound();
            return;
        }
        if (!isControlPart()) return;
        updateTargetsFromRemote();
        float previousSoundRpm = soundRpmController.rpm();
        soundRpmController.tick(controlSettings, engineEnabled, soundRpmTarget, 1.0F / 20);
        if (!nearlyEqual(previousSoundRpm, soundRpmController.rpm())) {
            setChanged();
            // Also sync when a behavior has zero output or the transmission is already settled.
            if (++soundSyncTicks >= KINETIC_UPDATE_INTERVAL || nearlyEqual(soundRpmController.rpm(), soundRpmTarget)) {
                soundSyncTicks = 0;
                sendData();
            }
        }
        if (!engineEnabled) {
            behaviorController.completeTransition();
            currentLeftSpeed = currentRightSpeed = 0;
            applyKineticSpeeds(0, 0);
            return;
        }
        var behavior = behaviorController.motionBehavior();
        currentLeftSpeed = EngineBehaviorController.advance(currentLeftSpeed, targetLeftSpeed,
                behavior.leftAcceleration(), behavior.leftDeceleration(), 1.0F / 20);
        currentRightSpeed = EngineBehaviorController.advance(currentRightSpeed, targetRightSpeed,
                behavior.rightAcceleration(), behavior.rightDeceleration(), 1.0F / 20);
        kineticUpdateTicks++;
        boolean reachedTarget = nearlyEqual(currentLeftSpeed, targetLeftSpeed)
                && nearlyEqual(currentRightSpeed, targetRightSpeed);
        if (reachedTarget) behaviorController.completeTransition();
        if (kineticUpdateTicks >= KINETIC_UPDATE_INTERVAL || reachedTarget) {
            kineticUpdateTicks = 0;
            applyKineticSpeeds(currentLeftSpeed, currentRightSpeed);
        }
    }
    private void updateTargetsFromRemote() {
        redstoneInputs.tick();
        boolean connected = hasValidAdvancedHub();
        Map<?, ?> signals = connected ? FireControlInputRouter.signals(level, advancedHub.pos(), terminalType) : Map.of();
        java.util.function.Predicate<String> pressed = key -> connected && isSignalPressed(signals, key);
        boolean toggle = pressed.test(controlSettings.engineToggleKey()) || redstoneInputs.powered(0);
        boolean changed = false;
        if (toggle && !engineTogglePressed) {
            engineEnabled = !engineEnabled;
            changed = true;
        }
        engineTogglePressed = toggle;
        int selected = behaviorController.select(controlSettings.behaviors(), pressed, index -> redstoneInputs.powered(index + 1));
        if (selected >= 0 && selected != lastBehaviorIndex) {
            lastBehaviorIndex = selected;
            changed = true;
        }
        var behavior = behaviorController.lastBehavior();
        float left = engineEnabled && selected >= 0 ? behavior.leftSpeed() : 0;
        float right = engineEnabled && selected >= 0 ? behavior.rightSpeed() : 0;
        float audioTarget = EngineSoundRpmController.target(controlSettings, behavior, engineEnabled && selected >= 0);
        if (changed || !nearlyEqual(targetLeftSpeed, left) || !nearlyEqual(targetRightSpeed, right)
                || !nearlyEqual(soundRpmTarget, audioTarget)) {
            targetLeftSpeed = left;
            targetRightSpeed = right;
            soundRpmTarget = audioTarget;
            setChanged();
            sendData();
        }
    }
    private boolean isSignalPressed(Map<?, ?> signals, String key) {
        return !key.isEmpty() && ControllerInput.signal(signals, key) > 0;
    }

    protected void applyKineticSpeeds(float leftSpeed, float rightSpeed) {
        // This block is always a generator. During rapid differential-output
        // rebuilds, Create can momentarily mark it as driven by a neighbouring
        // shaft. GeneratingKineticBlockEntity then refuses to stop at 0 RPM
        // and may destroy the block on the next reversal. Clear only this
        // turbine's transient source before applying its authoritative speed.
        boolean clearedTransientSource = hasSource();
        if (clearedTransientSource) {
            detachKinetics();
            removeSource();
        }
        boolean speedsUnchanged = nearlyEqual(appliedLeftSpeed, leftSpeed)
                && nearlyEqual(appliedRightSpeed, rightSpeed);
        float oldBase = generatedMagnitude(appliedLeftSpeed, appliedRightSpeed);
        float newBase = generatedMagnitude(leftSpeed, rightSpeed);
        if (speedsUnchanged) {
            if (clearedTransientSource && newBase > EPSILON) {
                updateGeneratedRotation();
            }
            return;
        }
        appliedLeftSpeed = leftSpeed;
        appliedRightSpeed = rightSpeed;
        if (!nearlyEqual(oldBase, newBase)) {
            updateGeneratedRotation();
        } else if (newBase > EPSILON) {
            detachKinetics();
            attachKinetics();
        }
        setChanged();
        sendData();
    }

    @Override
    public float getGeneratedSpeed() {
        if (!(getBlockState().getBlock() instanceof GasTurbineBlock)
                || getBlockState().getValue(GasTurbineBlock.PART) != GasTurbinePart.NEGATIVE) {
            return 0;
        }
        float magnitude = generatedMagnitude(appliedLeftSpeed, appliedRightSpeed);
        return magnitude <= EPSILON ? 0 : convertToDirection(
                magnitude, GasTurbineBlock.counterClockwiseOutput(getBlockState()));
    }

    @Override
    public float calculateAddedStressCapacity() {
        lastCapacityProvided = (float) com.simibubi.create.api.stress.BlockStressValues.getCapacity(
                com.simibubi.create.AllBlocks.CREATIVE_MOTOR.get());
        return lastCapacityProvided;
    }

    public float getOutputModifier(Direction direction) {
        float magnitude = generatedMagnitude(appliedLeftSpeed, appliedRightSpeed);
        if (magnitude <= EPSILON) return 0;
        Direction left = GasTurbineBlock.counterClockwiseOutput(getBlockState());
        if (direction == left) return appliedLeftSpeed / magnitude;
        if (direction == left.getOpposite()) return appliedRightSpeed / magnitude;
        return 0;
    }

    @Override
    public float propagateRotationTo(
            KineticBlockEntity target,
            BlockState sourceState,
            BlockState targetState,
            BlockPos offset,
            boolean connectedViaAxes,
            boolean connectedViaCogs
    ) {
        if (!connectedViaAxes) return 0;
        return getOutputModifier(Direction.getNearest(offset.getX(), offset.getY(), offset.getZ()));
    }

    public boolean bindAdvancedHub(BindingRef hub) {
        if (level == null || !RemoteControllerCompat.isTerminal(
                level, hub.pos(), RemoteControllerCompat.Type.ADVANCED)
                && !RemoteControllerCompat.isTerminal(level,hub.pos(),RemoteControllerCompat.Type.WIRELESS)) return false;
        BindingRef previousHub = advancedHub;
        terminalType=RemoteControllerCompat.isTerminal(level,hub.pos(),RemoteControllerCompat.Type.WIRELESS)?RemoteControllerCompat.Type.WIRELESS:RemoteControllerCompat.Type.ADVANCED;
        advancedHub = hub;
        controlSettings = compatibleSettings(controlSettings);
        engineTogglePressed = false;
        behaviorController.reset(controlSettings.behaviors().getFirst());
        lastBehaviorIndex = 0;
        if (!level.isClientSide) synchronized (LOADED) { LOADED.add(this); }
        if (previousHub != null) ControllerSessions.mappingsChanged(level, previousHub.pos());
        ControllerSessions.mappingsChanged(level, hub.pos());
        setChanged();
        sendData();
        return true;
    }

    public boolean hasValidAdvancedHub() {
        getAdvancedHub();
        return level != null
                && advancedHub != null
                && level.hasChunkAt(advancedHub.pos())
                && advancedHub.isSameTarget(level)
                && RemoteControllerCompat.isTerminal(
                level, advancedHub.pos(), terminalType);
    }

    public @Nullable BindingRef getAdvancedHub() {
        if (level != null && !level.isClientSide && advancedHub != null
                && terminalType == RemoteControllerCompat.Type.WIRELESS) {
            BindingRef refreshed = advancedHub.refreshWirelessTerminal(level);
            if (refreshed != advancedHub) {
                advancedHub = refreshed;
                setChanged();
                sendData();
            }
        }
        return advancedHub;
    }
    /** Functional terminal detach; control settings remain available for later rebinding. */
    public void unbindAdvancedHub() {
        BindingRef previousHub = advancedHub;
        advancedHub=null;engineTogglePressed=false;setChanged();sendData();
        if (previousHub != null) ControllerSessions.mappingsChanged(level, previousHub.pos());
    }

    public EngineControlSettings getControlSettings() { return controlSettings; }
    public boolean applySettings(EngineControlSettings settings) {
        if (!validKey(settings.engineToggleKey())) return false;
        for (var behavior : settings.behaviors()) {
            if (behavior.keys().stream().anyMatch(key -> !validKey(key))) return false;
        }
        controlSettings = settings;
        redstoneInputs.invalidate();
        lastBehaviorIndex = 0;
        behaviorController.reset(settings.behaviors().getFirst());
        if (advancedHub != null) ControllerSessions.mappingsChanged(level, advancedHub.pos());
        setChanged();
        sendData();
        return true;
    }
    private boolean validKey(String key) {
        return key.isEmpty() || (terminalType == RemoteControllerCompat.Type.WIRELESS
                ? !ControllerInput.chordInputs(key).isEmpty() : DriveByWireCompat.accepts(terminalType, key));
    }
    private EngineControlSettings compatibleSettings(EngineControlSettings settings) {
        return new EngineControlSettings(settings.soundVolume(), validKey(settings.engineToggleKey()) ? settings.engineToggleKey() : "",
                settings.behaviors().stream().map(b -> b.keys().stream().allMatch(this::validKey) ? b
                        : new EngineDriveBehavior(List.of(), b.leftSpeed(), b.leftAcceleration(), b.leftDeceleration(),
                        b.rightSpeed(), b.rightAcceleration(), b.rightDeceleration(), b.enteringRates(), b.leavingRates(), b.redstoneChannel())).toList(),
                settings.soundRpmEnabled(), settings.soundRpmAcceleration(), settings.soundRpmDeceleration(), settings.soundMaxRpm(), settings.toggleChannel());
    }
    public float getSoundVolume() { return controlSettings.soundVolume(); }
    public float getSimulatedSoundRpm() { return soundRpmController.rpm(); }
    public float getSoundRpmTarget() { return soundRpmTarget; }
    public float getSoundRpmMagnitude() {
        return controlSettings.soundRpmEnabled() ? Math.abs(soundRpmController.rpm()) : getCurrentMagnitude();
    }
    public float getSoundMaxRpm() {
        return controlSettings.soundRpmEnabled() ? controlSettings.soundMaxRpm() : getConfiguredMaxSpeed();
    }
    public float getSoundRpmAcceleration() {
        return controlSettings.soundRpmEnabled() ? controlSettings.soundRpmAcceleration() : getAcceleration();
    }
    public float getSoundRpmDeceleration() {
        return controlSettings.soundRpmDeceleration();
    }
    public float getAcceleration() {
        var behavior = controlSettings.behaviors().get(lastBehaviorIndex);
        return Math.max(behavior.leftAcceleration(), behavior.rightAcceleration());
    }
    public boolean isEngineEnabled() { return engineEnabled; }
    public float getTargetLeftSpeed() { return targetLeftSpeed; }
    public float getTargetRightSpeed() { return targetRightSpeed; }
    public float getCurrentLeftSpeed() { return appliedLeftSpeed; }
    public float getCurrentRightSpeed() { return appliedRightSpeed; }
    public float getCurrentMagnitude() { return generatedMagnitude(appliedLeftSpeed, appliedRightSpeed); }
    public float getTargetMagnitude() { return generatedMagnitude(targetLeftSpeed, targetRightSpeed); }
    public float getConfiguredMaxSpeed() {
        float maximum = 0;
        for (var behavior : controlSettings.behaviors()) maximum = Math.max(maximum, behavior.maximumMagnitude());
        return maximum;
    }
    /** Uses Create's synchronized stress/capacity totals for the connected kinetic network. */
    public float calculateEngineLoad() {
        if (!Float.isFinite(capacity) || !Float.isFinite(stress) || capacity <= EPSILON || stress <= 0) return 0;
        return Mth.clamp(stress / capacity, 0, 1);
    }
    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        EngineSettingsCodec.save(tag, controlSettings, registries);
        tag.putBoolean("EngineEnabled", engineEnabled);
        tag.putFloat("SimulatedSoundRpm", soundRpmController.rpm());
        if (advancedHub != null) tag.put("AdvancedHub", advancedHub.save());
        tag.putString("ControlTerminalType",terminalType.name());
        if (clientPacket) {
            tag.putInt("LastBehavior", lastBehaviorIndex);
            tag.putFloat("SoundRpmTarget", soundRpmTarget);
            tag.putFloat("TargetLeftSpeed", targetLeftSpeed);
            tag.putFloat("TargetRightSpeed", targetRightSpeed);
            tag.putFloat("CurrentLeftSpeed", appliedLeftSpeed);
            tag.putFloat("CurrentRightSpeed", appliedRightSpeed);
        }
    }
    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        var loaded = EngineSettingsCodec.load(tag, registries);
        // Keep legacy advanced-controller profiles; new/unbound engines capture native keyboard inputs.
        boolean nativeProfile = "WIRELESS".equals(tag.getString("ControlTerminalType"))
                || !tag.contains("AdvancedHub") && (loaded.engineToggleKey().isEmpty()
                || !ControllerInput.chordInputs(loaded.engineToggleKey()).isEmpty())
                && loaded.behaviors().stream().flatMap(behavior -> behavior.keys().stream())
                .allMatch(key -> !ControllerInput.chordInputs(key).isEmpty());
        terminalType = nativeProfile ? RemoteControllerCompat.Type.WIRELESS : RemoteControllerCompat.Type.ADVANCED;
        controlSettings = compatibleSettings(loaded);
        if (!clientPacket) redstoneInputs.invalidate();
        behaviorController.reset(controlSettings.behaviors().getFirst());
        lastBehaviorIndex = Mth.clamp(tag.getInt("LastBehavior"), 0, controlSettings.behaviors().size() - 1);
        engineEnabled = tag.getBoolean("EngineEnabled");
        engineTogglePressed = false;
        advancedHub = BindingRef.load(tag, "AdvancedHub");
        float synchronizedRpm = tag.getFloat("SimulatedSoundRpm");
        // A held rising target must not turn into a falling audible RPM when the
        // server lags behind the client. Only the first observation restores state;
        // subsequent snapshots are followed continuously by the client simulation.
        if (clientPacket) soundRpmController.synchronize(synchronizedRpm, !soundRpmInitialized);
        else soundRpmController.restore(synchronizedRpm);
        soundRpmInitialized = true;
        soundSyncTicks = 0;
        if (clientPacket) {
            float target = tag.getFloat("SoundRpmTarget");
            soundRpmTarget = Float.isFinite(target) ? Mth.clamp(target, -1024, 1024) : 0;
            targetLeftSpeed = tag.getFloat("TargetLeftSpeed");
            targetRightSpeed = tag.getFloat("TargetRightSpeed");
            currentLeftSpeed = appliedLeftSpeed = tag.getFloat("CurrentLeftSpeed");
            currentRightSpeed = appliedRightSpeed = tag.getFloat("CurrentRightSpeed");
        } else {
            soundRpmTarget = 0;
            targetLeftSpeed = targetRightSpeed = currentLeftSpeed = currentRightSpeed = appliedLeftSpeed = appliedRightSpeed = 0;
        }
    }
    private static float generatedMagnitude(float left, float right) { return Math.max(Math.abs(left), Math.abs(right)); }
    private static boolean nearlyEqual(float first, float second) { return Math.abs(first - second) <= EPSILON; }
}
