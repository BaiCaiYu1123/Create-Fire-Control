package com.hooya.stabilizedturret.compat.mianbao;

import com.hooya.stabilizedturret.content.controller.ControllerTracker;
import com.hooya.stabilizedturret.network.MountedHeavyMachineGunSyncPayload;
import com.simibubi.create.AllItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.CBCCompatTransformers;
import rbasamoyai.createbigcannons.cannon_control.ControlPitchContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Installation, spacing, firing and synchronization for cannon-mounted HMGs. */
public final class MountedHeavyMachineGunCompat {
    public static final ResourceLocation HEAVY_MACHINE_GUN = id("heavymachinegun");
    private static final Map<PitchOrientedContraptionEntity, FireState> FIRE_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static boolean handleInteraction(
            PitchOrientedContraptionEntity entity,
            Player player,
            BlockPos localPos,
            Direction clickedFace,
            InteractionHand hand
    ) {
        if (!(entity.getContraption() instanceof AbstractMountedCannonContraption cannon)
                || !(cannon instanceof MountedHeavyMachineGunCarrier carrier)) {
            return false;
        }
        ItemStack held = player.getItemInHand(hand);

        if (held.isEmpty() && player.isShiftKeyDown()) {
            MountedHeavyMachineGunCarrier.Side side = removalSide(
                    cannon, carrier, localPos, clickedFace);
            if (side == null) return false;
            if (player.level().isClientSide) return true;
            carrier.createFireControl$setMountedHeavyMachineGun(side, null);
            if (!player.getAbilities().instabuild) {
                ItemStack removed = new ItemStack(
                        BuiltInRegistries.ITEM.get(HEAVY_MACHINE_GUN));
                if (!player.getInventory().add(removed)) player.drop(removed, false);
            }
            sync(entity, carrier);
            player.displayClientMessage(Component.translatable(
                    "message.create_fire_control.mounted_hmg.removed"), true);
            return true;
        }

        if (isHeavyMachineGun(held)) {
            MountedHeavyMachineGunCarrier.Side side = sideFor(cannon, clickedFace);
            if (side == null) return false;
            if (player.level().isClientSide) return true;
            if (carrier.createFireControl$getMountedHeavyMachineGun(side) != null) {
                player.displayClientMessage(Component.translatable(
                        "message.create_fire_control.mounted_hmg.side_occupied"), true);
                return true;
            }
            carrier.createFireControl$setMountedHeavyMachineGun(
                    side, new MountedHeavyMachineGunCarrier.Attachment(localPos, 0));
            if (!player.getAbilities().instabuild) held.shrink(1);
            sync(entity, carrier);
            player.displayClientMessage(Component.translatable(
                    "message.create_fire_control.mounted_hmg.installed"), true);
            return true;
        }

        if (!AllItems.WRENCH.isIn(held)
                || !carrier.createFireControl$hasMountedHeavyMachineGun()) {
            return false;
        }
        if (player.level().isClientSide) return true;
        int delta = player.isShiftKeyDown() ? 1 : -1;
        boolean changed = false;
        for (MountedHeavyMachineGunCarrier.Side side
                : MountedHeavyMachineGunCarrier.Side.values()) {
            MountedHeavyMachineGunCarrier.Attachment attachment =
                    carrier.createFireControl$getMountedHeavyMachineGun(side);
            if (attachment == null) continue;
            int spacing = Mth.clamp(attachment.spacingPixels() + delta, -16, 0);
            if (spacing == attachment.spacingPixels()) continue;
            carrier.createFireControl$setMountedHeavyMachineGun(
                    side, attachment.withSpacing(spacing));
            changed = true;
        }
        if (changed) {
            sync(entity, carrier);
            int spacing = currentSpacing(carrier);
            player.displayClientMessage(Component.translatable(
                    "message.create_fire_control.mounted_hmg.spacing", spacing), true);
        }
        return true;
    }

    /** Polls the common CBC controller state, covering regular and fixed mounts. */
    public static void tickFromCannonMount(PitchOrientedContraptionEntity entity) {
        if (!hasAttachments(entity)) return;
        tickFromCannonMount(entity, isBaseActivated(entity));
    }

    /** Direct controller pulse, used by carriage and compatible custom mounts. */
    public static void tickFromCannonMount(
            @Nullable PitchOrientedContraptionEntity entity, boolean trigger
    ) {
        if (entity == null) return;
        if (!(entity.level() instanceof ServerLevel level)
                || !hasAttachments(entity)
                || ControllerTracker.isMountedMachineGunFireControlBound(entity)) {
            return;
        }
        tickFire(level, entity, trigger);
    }

    /** Held fire-control input, sampled once per server tick. */
    public static void tickFireControl(
            PitchOrientedContraptionEntity entity, boolean trigger
    ) {
        if (!(entity.level() instanceof ServerLevel level) || !hasAttachments(entity)) return;
        tickFire(level, entity, trigger);
    }

    public static boolean hasAttachments(@Nullable Entity entity) {
        return entity instanceof PitchOrientedContraptionEntity cannonEntity
                && cannonEntity.getContraption() instanceof MountedHeavyMachineGunCarrier carrier
                && carrier.createFireControl$hasMountedHeavyMachineGun();
    }

    public static void applyClientSync(
            PitchOrientedContraptionEntity entity,
            @Nullable MountedHeavyMachineGunCarrier.Attachment left,
            @Nullable MountedHeavyMachineGunCarrier.Attachment right
    ) {
        if (!(entity.getContraption() instanceof MountedHeavyMachineGunCarrier carrier)) return;
        carrier.createFireControl$setMountedHeavyMachineGun(
                MountedHeavyMachineGunCarrier.Side.LEFT, left);
        carrier.createFireControl$setMountedHeavyMachineGun(
                MountedHeavyMachineGunCarrier.Side.RIGHT, right);
    }

    public static @Nullable Direction lateralDirection(
            AbstractMountedCannonContraption cannon,
            MountedHeavyMachineGunCarrier.Side side
    ) {
        Direction forward = cannon.initialOrientation();
        if (forward == null || !forward.getAxis().isHorizontal()) return null;
        return side == MountedHeavyMachineGunCarrier.Side.LEFT
                ? forward.getCounterClockWise() : forward.getClockWise();
    }

    public static Vec3 localGunCenter(
            AbstractMountedCannonContraption cannon,
            MountedHeavyMachineGunCarrier.Side side,
            MountedHeavyMachineGunCarrier.Attachment attachment
    ) {
        Direction lateral = lateralDirection(cannon, side);
        if (lateral == null) return Vec3.atCenterOf(attachment.anchor());
        double distance = 1.0D + attachment.spacingPixels() / 16.0D;
        return Vec3.atCenterOf(attachment.anchor()).add(
                lateral.getStepX() * distance,
                lateral.getStepY() * distance,
                lateral.getStepZ() * distance);
    }

    private static void tickFire(
            ServerLevel level,
            PitchOrientedContraptionEntity entity,
            boolean trigger
    ) {
        FireState state = FIRE_STATES.computeIfAbsent(entity, ignored -> new FireState());
        long now = level.getGameTime();
        if (!trigger) return;
        if (state.lastFireTick != Long.MIN_VALUE
                && now - state.lastFireTick < MianbaoNativeGunfire.mountedFireInterval()) return;
        if (!(entity.getContraption() instanceof AbstractMountedCannonContraption cannon)
                || !(cannon instanceof MountedHeavyMachineGunCarrier carrier)) return;

        boolean fired = false;
        for (MountedHeavyMachineGunCarrier.Side side
                : MountedHeavyMachineGunCarrier.Side.values()) {
            MountedHeavyMachineGunCarrier.Attachment attachment =
                    carrier.createFireControl$getMountedHeavyMachineGun(side);
            if (attachment != null) fired |= fireOne(level, entity, cannon, side, attachment);
        }
        if (fired) state.lastFireTick = now;
    }

    private static boolean fireOne(
            ServerLevel level,
            PitchOrientedContraptionEntity entity,
            AbstractMountedCannonContraption cannon,
            MountedHeavyMachineGunCarrier.Side side,
            MountedHeavyMachineGunCarrier.Attachment attachment
    ) {
        Direction forwardDirection = cannon.initialOrientation();
        if (forwardDirection == null) return false;
        Vec3 localCenter = localGunCenter(cannon, side, attachment);
        Vec3 localMuzzle = localCenter.add(
                forwardDirection.getStepX() * 0.95D,
                forwardDirection.getStepY() * 0.95D + 0.42D,
                forwardDirection.getStepZ() * 0.95D);
        Vec3 muzzle = entity.toGlobalVector(localMuzzle, 0.0F);
        Vec3 forwardPoint = entity.toGlobalVector(
                localCenter.add(
                        forwardDirection.getStepX(),
                        forwardDirection.getStepY() + 0.01D,
                        forwardDirection.getStepZ()), 0.0F);
        Vec3 center = entity.toGlobalVector(localCenter, 0.0F);
        Vec3 direction = forwardPoint.subtract(center);
        if (direction.lengthSqr() < 1.0e-8D) return false;

        // Create's global vector can still be in a Sable plot. Native arrows and
        // their new entity-anchored tracers must spawn in root-world coordinates.
        muzzle = CBCCompatTransformers.transformVec3(level, muzzle, entity.position());
        direction = CBCCompatTransformers.transformLocationNormal(
                level, entity.blockPosition(), direction);
        ServerLevel root = level.getServer().getLevel(level.dimension());
        if (root == null) return false;
        BlockState gun = BuiltInRegistries.BLOCK.get(HEAVY_MACHINE_GUN).defaultBlockState();
        for (Property<?> property : gun.getProperties()) {
            if (property instanceof net.minecraft.world.level.block.state.properties.DirectionProperty facing
                    && "facing".equals(property.getName())
                    && facing.getPossibleValues().contains(forwardDirection)) {
                gun = gun.setValue(facing, forwardDirection);
                break;
            }
        }
        return MianbaoNativeGunfire.fireMounted(root, gun, muzzle, direction);
    }

    private static @Nullable MountedHeavyMachineGunCarrier.Side sideFor(
            AbstractMountedCannonContraption cannon, Direction clickedFace
    ) {
        if (clickedFace == lateralDirection(cannon, MountedHeavyMachineGunCarrier.Side.LEFT)) {
            return MountedHeavyMachineGunCarrier.Side.LEFT;
        }
        if (clickedFace == lateralDirection(cannon, MountedHeavyMachineGunCarrier.Side.RIGHT)) {
            return MountedHeavyMachineGunCarrier.Side.RIGHT;
        }
        return null;
    }

    private static @Nullable MountedHeavyMachineGunCarrier.Side removalSide(
            AbstractMountedCannonContraption cannon,
            MountedHeavyMachineGunCarrier carrier,
            BlockPos clickedPos,
            Direction clickedFace
    ) {
        MountedHeavyMachineGunCarrier.Side faceSide = sideFor(cannon, clickedFace);
        if (faceSide != null) {
            return carrier.createFireControl$getMountedHeavyMachineGun(faceSide) == null
                    ? null : faceSide;
        }

        MountedHeavyMachineGunCarrier.Side match = null;
        for (MountedHeavyMachineGunCarrier.Side side
                : MountedHeavyMachineGunCarrier.Side.values()) {
            MountedHeavyMachineGunCarrier.Attachment attachment =
                    carrier.createFireControl$getMountedHeavyMachineGun(side);
            if (attachment == null || !attachment.anchor().equals(clickedPos)) continue;
            if (match != null) return null;
            match = side;
        }
        if (match != null) return match;

        for (MountedHeavyMachineGunCarrier.Side side
                : MountedHeavyMachineGunCarrier.Side.values()) {
            if (carrier.createFireControl$getMountedHeavyMachineGun(side) == null) continue;
            if (match != null) return null;
            match = side;
        }
        return match;
    }

    private static boolean isBaseActivated(PitchOrientedContraptionEntity entity) {
        ControlPitchContraption controller = entity.getController();
        if (controller == null) return false;
        BlockState state = controller.getControllerState();
        if (state == null) return false;
        for (Property<?> property : state.getProperties()) {
            if (property instanceof BooleanProperty booleanProperty
                    && "fire_powered".equals(property.getName())) {
                return state.getValue(booleanProperty);
            }
        }
        return false;
    }

    private static boolean isHeavyMachineGun(ItemStack stack) {
        return !stack.isEmpty() && HEAVY_MACHINE_GUN.equals(
                BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private static int currentSpacing(MountedHeavyMachineGunCarrier carrier) {
        for (MountedHeavyMachineGunCarrier.Side side
                : MountedHeavyMachineGunCarrier.Side.values()) {
            MountedHeavyMachineGunCarrier.Attachment attachment =
                    carrier.createFireControl$getMountedHeavyMachineGun(side);
            if (attachment != null) return attachment.spacingPixels();
        }
        return 0;
    }

    private static void sync(
            PitchOrientedContraptionEntity entity,
            MountedHeavyMachineGunCarrier carrier
    ) {
        PacketDistributor.sendToPlayersTrackingEntity(entity,
                MountedHeavyMachineGunSyncPayload.from(entity, carrier));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mianbaos_modernwarfare", path);
    }

    private static final class FireState {
        long lastFireTick = Long.MIN_VALUE;
    }

    private MountedHeavyMachineGunCompat() {
    }
}
