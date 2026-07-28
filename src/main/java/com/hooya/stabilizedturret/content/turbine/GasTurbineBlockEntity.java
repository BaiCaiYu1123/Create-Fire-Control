package com.hooya.stabilizedturret.content.turbine;

import com.hooya.stabilizedturret.client.GasTurbineSoundManager;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.content.controller.DriveByWireCompat;
import com.hooya.stabilizedturret.content.controller.RemoteControllerCompat;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class GasTurbineBlockEntity extends GeneratingKineticBlockEntity {
    public static final int INPUT_FORWARD = 0;
    public static final int INPUT_REVERSE = 1;
    public static final int INPUT_LEFT = 2;
    public static final int INPUT_RIGHT = 3;
    public static final int INPUT_ENGINE_TOGGLE = 4;
    public static final int INPUT_COUNT = 5;

    public static final float MAX_SPEED = 1024.0F;
    public static final float DEFAULT_FORWARD_SPEED = 256.0F;
    public static final float DEFAULT_REVERSE_SPEED = 256.0F;
    public static final float DEFAULT_TURN_SPEED = 128.0F;
    public static final float DEFAULT_ACCELERATION = 128.0F;
    public static final float DEFAULT_SOUND_VOLUME = 100.0F;
    public static final float MAX_SOUND_VOLUME = 100.0F;
    public static final float STRESS_CAPACITY = 64.0F;

    private static final int KINETIC_UPDATE_INTERVAL = 2;
    private static final float EPSILON = 0.001F;

    private final String[] advancedRemoteKeys = new String[INPUT_COUNT];
    private @Nullable BindingRef advancedHub;
    private float forwardSpeed = DEFAULT_FORWARD_SPEED;
    private float reverseSpeed = DEFAULT_REVERSE_SPEED;
    private float turnSpeed = DEFAULT_TURN_SPEED;
    private float acceleration = DEFAULT_ACCELERATION;
    private float soundVolume = DEFAULT_SOUND_VOLUME;
    private boolean engineEnabled;
    private boolean engineTogglePressed;
    private float targetLeftSpeed;
    private float targetRightSpeed;
    private float currentLeftSpeed;
    private float currentRightSpeed;
    private float appliedLeftSpeed;
    private float appliedRightSpeed;
    private int kineticUpdateTicks;

    public GasTurbineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GAS_TURBINE.get(), pos, state);
        Arrays.fill(advancedRemoteKeys, "");
    }

    @Override
    public void initialize() {
        super.initialize();
        if (!hasSource()) updateGeneratedRotation();
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null) return;
        if (level.isClientSide) {
            if (FMLEnvironment.dist == Dist.CLIENT) GasTurbineSoundManager.tick(this);
            return;
        }
        if (!(getBlockState().getBlock() instanceof GasTurbineBlock)
                || getBlockState().getValue(GasTurbineBlock.PART) != GasTurbinePart.NEGATIVE) {
            return;
        }

        updateTargetsFromRemote();
        if (!engineEnabled) {
            currentLeftSpeed = 0;
            currentRightSpeed = 0;
            applyKineticSpeeds(0, 0);
            return;
        }
        float step = acceleration / 20.0F;
        currentLeftSpeed = moveTowards(currentLeftSpeed, targetLeftSpeed, step);
        currentRightSpeed = moveTowards(currentRightSpeed, targetRightSpeed, step);

        kineticUpdateTicks++;
        boolean reachedTarget = nearlyEqual(currentLeftSpeed, targetLeftSpeed)
                && nearlyEqual(currentRightSpeed, targetRightSpeed);
        if (kineticUpdateTicks >= KINETIC_UPDATE_INTERVAL || reachedTarget) {
            kineticUpdateTicks = 0;
            applyKineticSpeeds(currentLeftSpeed, currentRightSpeed);
        }
    }

    private void updateTargetsFromRemote() {
        boolean forward = false;
        boolean reverse = false;
        boolean turnLeft = false;
        boolean turnRight = false;
        boolean engineToggle = false;
        if (hasValidAdvancedHub() && DriveByWireCompat.isAvailable()) {
            Map<?, ?> signals = DriveByWireCompat.signals(level, advancedHub.pos());
            forward = isSignalPressed(signals, INPUT_FORWARD);
            reverse = isSignalPressed(signals, INPUT_REVERSE);
            turnLeft = isSignalPressed(signals, INPUT_LEFT);
            turnRight = isSignalPressed(signals, INPUT_RIGHT);
            engineToggle = isSignalPressed(signals, INPUT_ENGINE_TOGGLE);
        }

        boolean stateChanged = false;
        if (engineToggle && !engineTogglePressed) {
            engineEnabled = !engineEnabled;
            stateChanged = true;
        }
        engineTogglePressed = engineToggle;

        int driveDirection = (forward ? 1 : 0) - (reverse ? 1 : 0);
        float leftTarget = 0;
        float rightTarget = 0;
        if (engineEnabled && driveDirection > 0) {
            leftTarget = forwardSpeed;
            rightTarget = forwardSpeed;
            if (turnLeft != turnRight) {
                if (turnLeft) leftTarget = 0;
                else rightTarget = 0;
            }
        } else if (engineEnabled && driveDirection < 0) {
            leftTarget = -reverseSpeed;
            rightTarget = -reverseSpeed;
            if (turnLeft != turnRight) {
                // Reversing swaps the inside track required for the same
                // vehicle-relative steering direction.
                if (turnLeft) rightTarget = 0;
                else leftTarget = 0;
            }
        } else if (engineEnabled && turnLeft != turnRight) {
            if (turnLeft) {
                leftTarget = -turnSpeed;
                rightTarget = turnSpeed;
            } else {
                leftTarget = turnSpeed;
                rightTarget = -turnSpeed;
            }
        }

        if (stateChanged
                || !nearlyEqual(targetLeftSpeed, leftTarget)
                || !nearlyEqual(targetRightSpeed, rightTarget)) {
            targetLeftSpeed = leftTarget;
            targetRightSpeed = rightTarget;
            setChanged();
            sendData();
        }
    }

    private boolean isSignalPressed(Map<?, ?> signals, int action) {
        String key = advancedRemoteKeys[action];
        return !key.isEmpty() && DriveByWireCompat.signal(signals, key) > 0;
    }

    private void applyKineticSpeeds(float leftSpeed, float rightSpeed) {
        if (nearlyEqual(appliedLeftSpeed, leftSpeed)
                && nearlyEqual(appliedRightSpeed, rightSpeed)) return;
        float oldBase = generatedMagnitude(appliedLeftSpeed, appliedRightSpeed);
        float newBase = generatedMagnitude(leftSpeed, rightSpeed);
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
        lastCapacityProvided = STRESS_CAPACITY;
        return STRESS_CAPACITY;
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
                level, hub.pos(), RemoteControllerCompat.Type.ADVANCED)) return false;
        advancedHub = hub;
        setChanged();
        sendData();
        return true;
    }

    public boolean hasValidAdvancedHub() {
        return level != null
                && advancedHub != null
                && advancedHub.isSameTarget(level)
                && RemoteControllerCompat.isTerminal(
                level, advancedHub.pos(), RemoteControllerCompat.Type.ADVANCED);
    }

    public @Nullable BindingRef getAdvancedHub() {
        return advancedHub;
    }

    public List<String> getAdvancedRemoteKeys() {
        return List.of(advancedRemoteKeys.clone());
    }

    public boolean applySettings(
            List<String> keys,
            float requestedForwardSpeed,
            float requestedReverseSpeed,
            float requestedTurnSpeed,
            float requestedAcceleration,
            float requestedSoundVolume
    ) {
        if (keys.size() != INPUT_COUNT
                || !Float.isFinite(requestedForwardSpeed)
                || !Float.isFinite(requestedReverseSpeed)
                || !Float.isFinite(requestedTurnSpeed)
                || !Float.isFinite(requestedAcceleration)
                || !Float.isFinite(requestedSoundVolume)) return false;
        boolean hubValid = hasValidAdvancedHub();
        for (String key : keys) {
            if (key == null || (!key.isEmpty() && (!hubValid
                    || !DriveByWireCompat.accepts(RemoteControllerCompat.Type.ADVANCED, key)))) {
                return false;
            }
        }
        for (int index = 0; index < INPUT_COUNT; index++) advancedRemoteKeys[index] = keys.get(index);
        forwardSpeed = Mth.clamp(requestedForwardSpeed, 0, MAX_SPEED);
        reverseSpeed = Mth.clamp(requestedReverseSpeed, 0, MAX_SPEED);
        turnSpeed = Mth.clamp(requestedTurnSpeed, 0, MAX_SPEED);
        acceleration = Mth.clamp(requestedAcceleration, 1, MAX_SPEED);
        soundVolume = Mth.clamp(requestedSoundVolume, 0, MAX_SOUND_VOLUME);
        setChanged();
        sendData();
        return true;
    }

    public float getForwardSpeed() { return forwardSpeed; }
    public float getReverseSpeed() { return reverseSpeed; }
    public float getTurnSpeed() { return turnSpeed; }
    public float getAcceleration() { return acceleration; }
    public float getSoundVolume() { return soundVolume; }
    public boolean isEngineEnabled() { return engineEnabled; }
    public float getTargetLeftSpeed() { return targetLeftSpeed; }
    public float getTargetRightSpeed() { return targetRightSpeed; }
    public float getCurrentLeftSpeed() { return appliedLeftSpeed; }
    public float getCurrentRightSpeed() { return appliedRightSpeed; }
    public float getCurrentMagnitude() {
        return generatedMagnitude(appliedLeftSpeed, appliedRightSpeed);
    }
    public float getTargetMagnitude() {
        return generatedMagnitude(targetLeftSpeed, targetRightSpeed);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putFloat("ForwardSpeed", forwardSpeed);
        tag.putFloat("ReverseSpeed", reverseSpeed);
        tag.putFloat("TurnSpeed", turnSpeed);
        tag.putFloat("Acceleration", acceleration);
        tag.putFloat("SoundVolume", soundVolume);
        tag.putBoolean("EngineEnabled", engineEnabled);
        for (int index = 0; index < INPUT_COUNT; index++) {
            tag.putString("AdvancedRemoteKey_" + index, advancedRemoteKeys[index]);
        }
        if (advancedHub != null) tag.put("AdvancedHub", advancedHub.save());
        if (clientPacket) {
            tag.putFloat("TargetLeftSpeed", targetLeftSpeed);
            tag.putFloat("TargetRightSpeed", targetRightSpeed);
            tag.putFloat("CurrentLeftSpeed", appliedLeftSpeed);
            tag.putFloat("CurrentRightSpeed", appliedRightSpeed);
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        float legacyDriveSpeed = readClamped(
                tag, "DriveSpeed", DEFAULT_FORWARD_SPEED, 0, MAX_SPEED);
        forwardSpeed = readClamped(
                tag, "ForwardSpeed", legacyDriveSpeed, 0, MAX_SPEED);
        reverseSpeed = readClamped(
                tag, "ReverseSpeed", legacyDriveSpeed, 0, MAX_SPEED);
        turnSpeed = readClamped(tag, "TurnSpeed", DEFAULT_TURN_SPEED, 0, MAX_SPEED);
        acceleration = readClamped(tag, "Acceleration", DEFAULT_ACCELERATION, 1, MAX_SPEED);
        soundVolume = readClamped(
                tag, "SoundVolume", DEFAULT_SOUND_VOLUME, 0, MAX_SOUND_VOLUME);
        engineEnabled = tag.getBoolean("EngineEnabled");
        engineTogglePressed = false;
        for (int index = 0; index < INPUT_COUNT; index++) {
            String key = tag.getString("AdvancedRemoteKey_" + index);
            advancedRemoteKeys[index] = DriveByWireCompat.accepts(
                    RemoteControllerCompat.Type.ADVANCED, key) ? key : "";
        }
        advancedHub = BindingRef.load(tag, "AdvancedHub");
        if (clientPacket) {
            targetLeftSpeed = tag.getFloat("TargetLeftSpeed");
            targetRightSpeed = tag.getFloat("TargetRightSpeed");
            currentLeftSpeed = tag.getFloat("CurrentLeftSpeed");
            currentRightSpeed = tag.getFloat("CurrentRightSpeed");
            appliedLeftSpeed = currentLeftSpeed;
            appliedRightSpeed = currentRightSpeed;
        } else {
            targetLeftSpeed = 0;
            targetRightSpeed = 0;
            currentLeftSpeed = 0;
            currentRightSpeed = 0;
            appliedLeftSpeed = 0;
            appliedRightSpeed = 0;
        }
    }

    private static float readClamped(
            CompoundTag tag, String key, float defaultValue, float minimum, float maximum) {
        if (!tag.contains(key)) return defaultValue;
        float value = tag.getFloat(key);
        return Float.isFinite(value) ? Mth.clamp(value, minimum, maximum) : defaultValue;
    }

    private static float moveTowards(float current, float target, float amount) {
        if (current < target) return Math.min(current + amount, target);
        if (current > target) return Math.max(current - amount, target);
        return target;
    }

    private static float generatedMagnitude(float left, float right) {
        return Math.max(Math.abs(left), Math.abs(right));
    }

    private static boolean nearlyEqual(float first, float second) {
        return Math.abs(first - second) <= EPSILON;
    }
}
