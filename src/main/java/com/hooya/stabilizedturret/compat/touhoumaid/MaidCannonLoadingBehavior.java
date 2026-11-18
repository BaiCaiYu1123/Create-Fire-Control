package com.hooya.stabilizedturret.compat.touhoumaid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.IChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.ImageChatBubbleData;
import com.google.common.collect.ImmutableMap;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.loader.MaidCannonLoading;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.joml.Vector3d;

import java.util.UUID;

/** One 80-tick loading state machine. Target scans are deliberately limited to once per second. */
final class MaidCannonLoadingBehavior extends Behavior<EntityMaid> {
    static final int SCAN_INTERVAL_TICKS = 20;
    static final int TAKE_AMMUNITION_TICK = 20;
    static final int INSERT_AMMUNITION_TICK = 40;
    static final int CLOSE_BREECH_TICK = 60;
    static final int TOTAL_CYCLE_TICKS = 80;

    private MaidCannonLoading.Target target;
    private static final ResourceLocation COMPLETION_EXPRESSION =
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,
                    "textures/chat_bubble/loading_complete.png");
    private static final int COMPLETION_EXPRESSION_TICKS = 2 * 20;
    private static final int COMPLETION_EXPRESSION_SIZE = 24;

    private ItemStack reservedAmmunition = ItemStack.EMPTY;
    private long cycleStartedAt = Long.MIN_VALUE;
    private long nextScanAt = Long.MIN_VALUE;
    private boolean inserted;
    private boolean closeCommanded;
    private UUID anchoredSubLevelId;
    private Vec3 anchoredLocalPosition;
    private Vec3 anchoredLocalForward;
    private MaidCannonLoading.SeatTarget loadingSeat;
    private long completionBubbleKey = -1L;

    MaidCannonLoadingBehavior() {
        super(ImmutableMap.of());
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        return isLoadingMode(maid);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return isLoadingMode(maid);
    }

    @Override
    protected boolean timedOut(long gameTime) {
        return false;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        enableHomeMode(maid);
        loadingSeat = MaidCannonLoading.rememberedSeat(maid)
                .or(() -> MaidCannonLoading.currentSeat(maid)).orElse(null);
        if (loadingSeat != null) loadingSeat.remember(maid);
        nextScanAt = gameTime + Math.floorMod(maid.getUUID().hashCode(), SCAN_INTERVAL_TICKS);
        holdPosition(maid);
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        holdPosition(maid);

        if (target == null) {
            if (gameTime < nextScanAt) return;
            nextScanAt = gameTime + SCAN_INTERVAL_TICKS;

            // Seat selection is intentionally one-shot for the lifetime of this task behavior.
            // Re-ranking after the maid sits changes the distance origin and can make two
            // otherwise equivalent seats alternate forever. Once seated, keep that exact seat.
            if (loadingSeat == null) {
                MaidCannonLoading.SeatTarget selectedSeat = MaidCannonLoading
                        .findSeat(maid.level(), maid.position(), maid).orElse(null);
                if (selectedSeat != null) {
                    clearPositionAnchor();
                    if (selectedSeat.sit(maid)) {
                        loadingSeat = selectedSeat;
                        loadingSeat.remember(maid);
                        updateMovingHomeCenter(maid,
                                BlockPos.containing(selectedSeat.worldCenter()));
                        return;
                    }
                }
            } else if (!loadingSeat.isCurrentSeat(maid) && loadingSeat.isValidFor(maid)) {
                // If Sable tracking temporarily drops the Create seat entity, only attempt to
                // restore the originally selected seat; never switch to another candidate.
                clearPositionAnchor();
                if (loadingSeat.sit(maid)) {
                    updateMovingHomeCenter(maid,
                            BlockPos.containing(loadingSeat.worldCenter()));
                    return;
                }
            }

            Vec3 maidWorld = worldPosition(maid);
            MaidCannonLoading.Target candidate = MaidCannonLoading
                    .find(maid.level(), maid.position()).orElse(null);
            if (candidate == null || !candidate.tryClaim(maid.getUUID(), gameTime)) return;
            if (!candidate.isReady(maidWorld)) {
                candidate.release(maid.getUUID());
                return;
            }
            boolean wasOpen = candidate.isBreechOpen();
            if (!candidate.openBreech()) {
                candidate.release(maid.getUUID());
                return;
            }
            removeCompletionExpression(maid);
            target = candidate;
            cycleStartedAt = gameTime;
            lookAtWorld(maid, candidate.breechWorldCenter());
            if (!wasOpen) {
                MaidLoadingAnimationSync.playBreechSound(
                        maid, candidate.breechWorldCenter(), true);
            }
            maid.swing(InteractionHand.MAIN_HAND);
            MaidLoadingAnimationSync.start(maid);
            return;
        }

        Vec3 maidWorld = worldPosition(maid);
        long elapsed = gameTime - cycleStartedAt;
        if (!target.isCannonAvailable(maidWorld, !inserted)) {
            cancelCycle(maid, gameTime);
            return;
        }

        if (elapsed < TAKE_AMMUNITION_TICK) {
            lookAtWorld(maid, target.breechWorldCenter());
        } else if (elapsed < INSERT_AMMUNITION_TICK) {
            lookAtWorld(maid, target.depotWorldCenter());
        } else {
            lookAtWorld(maid, target.breechWorldCenter());
        }

        if (elapsed >= TAKE_AMMUNITION_TICK && reservedAmmunition.isEmpty() && !inserted) {
            reservedAmmunition = target.takeAmmunition();
            if (reservedAmmunition.isEmpty()) {
                cancelCycle(maid, gameTime);
                return;
            }
            maid.swing(InteractionHand.MAIN_HAND);
        }

        if (elapsed >= INSERT_AMMUNITION_TICK && !reservedAmmunition.isEmpty() && !inserted) {
            if (target.insertAmmunition(reservedAmmunition)) {
                reservedAmmunition = ItemStack.EMPTY;
                inserted = true;
                maid.swing(InteractionHand.MAIN_HAND);
            }
        }

        if (elapsed >= CLOSE_BREECH_TICK && inserted && !closeCommanded) {
            closeCommanded = closeBreechWithSound(maid);
            if (closeCommanded) maid.swing(InteractionHand.MAIN_HAND);
        }

        if (elapsed >= TOTAL_CYCLE_TICKS) {
            if (inserted) {
                if (!closeCommanded) closeBreechWithSound(maid);
                finishCycle(maid, gameTime);
            } else {
                cancelCycle(maid, gameTime);
            }
        }
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        cancelCycle(maid, gameTime);
        removeCompletionExpression(maid);
        maid.getBrain().eraseMemory(MemoryModuleType.PATH);
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        maid.getNavigation().stop();
        if (loadingSeat != null && loadingSeat.isCurrentSeat(maid)) {
            maid.stopRiding();
        }
        loadingSeat = null;
        MaidCannonLoading.forgetRememberedSeat(maid);
    }

    private void cancelCycle(EntityMaid maid, long gameTime) {
        if (target != null) {
            closeBreechWithSound(maid);
            if (!reservedAmmunition.isEmpty() && !target.returnAmmunition(reservedAmmunition)) {
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(
                        maid.getAvailableInv(false), reservedAmmunition, false);
                if (!remainder.isEmpty()) maid.spawnAtLocation(remainder);
            }
            target.release(maid.getUUID());
        }
        MaidLoadingAnimationSync.stop(maid);
        clearState(gameTime);
    }

    private void finishCycle(EntityMaid maid, long gameTime) {
        if (target != null) target.release(maid.getUUID());
        completionBubbleKey = showCompletionExpression(maid);
        MaidLoadingAnimationSync.playCompletionVoice(maid);
        MaidLoadingAnimationSync.stop(maid);
        clearState(gameTime);
    }

    private void clearState(long gameTime) {
        target = null;
        reservedAmmunition = ItemStack.EMPTY;
        cycleStartedAt = Long.MIN_VALUE;
        inserted = false;
        closeCommanded = false;
        anchoredSubLevelId = null;
        anchoredLocalPosition = null;
        anchoredLocalForward = null;
        nextScanAt = gameTime + SCAN_INTERVAL_TICKS;
    }

    private boolean closeBreechWithSound(EntityMaid maid) {
        if (target == null) return false;
        boolean wasOpen = target.isBreechOpen();
        boolean accepted = target.closeBreech();
        if (accepted && wasOpen) {
            MaidLoadingAnimationSync.playBreechSound(
                    maid, target.breechWorldCenter(), false);
        }
        return accepted;
    }

    private void holdPosition(EntityMaid maid) {
        maid.getBrain().eraseMemory(MemoryModuleType.PATH);
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        maid.getNavigation().stop();
        maid.setSprinting(false);
        if (loadingSeat != null && loadingSeat.isCurrentSeat(maid)) {
            clearPositionAnchor();
            updateMovingHomeCenter(maid, BlockPos.containing(worldPosition(maid)));
            return;
        }
        maintainSableAnchor(maid);
        Vec3 movement = maid.getDeltaMovement();
        maid.setDeltaMovement(anchoredSubLevelId == null
                ? new Vec3(0.0, movement.y, 0.0) : Vec3.ZERO);
    }

    private void clearPositionAnchor() {
        anchoredSubLevelId = null;
        anchoredLocalPosition = null;
        anchoredLocalForward = null;
    }

    private static void enableHomeMode(EntityMaid maid) {
        if (maid.isHomeModeEnable()) return;
        maid.getSchedulePos().setHomeModeEnable(maid, maid.blockPosition());
        maid.setHomeModeEnable(true);
        maid.getSchedulePos().restrictTo(maid);
    }

    private void maintainSableAnchor(EntityMaid maid) {
        SubLevelContainer container = SubLevelContainer.getContainer(maid.level());
        SubLevel anchor = anchoredSubLevelId == null ? null
                : container.getSubLevel(anchoredSubLevelId);
        if (anchor == null || anchor.isRemoved()) {
            anchoredSubLevelId = null;
            anchoredLocalPosition = null;
            anchoredLocalForward = null;
            anchor = Sable.HELPER.getTrackingSubLevel(maid);
            boolean positionIsAlreadyLocal = false;
            if (anchor == null || anchor.isRemoved()) {
                anchor = Sable.HELPER.getContaining(maid);
                positionIsAlreadyLocal = anchor != null && !anchor.isRemoved();
            }
            if (anchor == null || anchor.isRemoved()) return;
            anchoredSubLevelId = anchor.getUniqueId();
            anchoredLocalPosition = positionIsAlreadyLocal
                    ? maid.position()
                    : anchor.logicalPose().transformPositionInverse(maid.position());
            Vec3 worldForward = Vec3.directionFromRotation(0.0F, maid.getYRot());
            anchoredLocalForward = (positionIsAlreadyLocal
                    ? worldForward
                    : anchor.logicalPose().transformNormalInverse(worldForward)).normalize();
        }

        if (anchoredLocalPosition == null) return;
        Vec3 worldPosition = anchor.logicalPose().transformPosition(anchoredLocalPosition);
        if (!Double.isFinite(worldPosition.x) || !Double.isFinite(worldPosition.y)
                || !Double.isFinite(worldPosition.z)) return;

        ((EntityMovementExtension) maid).sable$setTrackingSubLevel(anchor);
        maid.setPos(worldPosition);
        applyAnchoredRotation(maid, anchor);
        EntitySubLevelUtil.setOldPosNoMovement(maid);
        updateMovingHomeCenter(maid, BlockPos.containing(worldPosition));
    }

    private void applyAnchoredRotation(EntityMaid maid, SubLevel anchor) {
        if (anchoredLocalForward == null) return;
        Vec3 worldForward = anchor.logicalPose().transformNormal(anchoredLocalForward);
        if (worldForward.horizontalDistanceSqr() < 1.0E-8) return;
        float yaw = (float) (Math.atan2(worldForward.z, worldForward.x)
                * 180.0 / Math.PI) - 90.0F;
        maid.setYRot(yaw);
        maid.setYBodyRot(yaw);
        maid.setYHeadRot(yaw);
    }

    private static void updateMovingHomeCenter(EntityMaid maid, BlockPos worldPosition) {
        var schedule = maid.getSchedulePos();
        schedule.setWorkPos(worldPosition);
        schedule.setIdlePos(worldPosition);
        schedule.setSleepPos(worldPosition);
        schedule.setDimension(maid.level().dimension().location());
        schedule.setConfigured(false);
        schedule.restrictTo(maid);
    }

    private static long showCompletionExpression(EntityMaid maid) {
        var manager = maid.getChatBubbleManager();
        var collection = manager.getChatBubbleDataCollection();
        // Completion feedback is exclusive: remove any lower-priority routine/hurt bubbles
        // before adding it, and use the maximum priority so nothing can evict it early.
        for (long key : collection.keySet().toLongArray()) {
            var bubble = collection.get(key);
            if (bubble != null && bubble.priority() < Integer.MAX_VALUE) {
                collection.remove(key);
            }
        }
        return manager.addChatBubble(ImageChatBubbleData.create(
                COMPLETION_EXPRESSION_TICKS,
                IChatBubbleData.TYPE_2,
                COMPLETION_EXPRESSION,
                COMPLETION_EXPRESSION_SIZE,
                COMPLETION_EXPRESSION_SIZE,
                0,
                0,
                COMPLETION_EXPRESSION_SIZE,
                COMPLETION_EXPRESSION_SIZE,
                Integer.MAX_VALUE));
    }

    private void removeCompletionExpression(EntityMaid maid) {
        if (completionBubbleKey < 0L) return;
        maid.getChatBubbleManager().removeChatBubble(completionBubbleKey);
        completionBubbleKey = -1L;
    }

    private static Vec3 worldPosition(EntityMaid maid) {
        return Sable.HELPER.projectOutOfSubLevel(maid.level(), maid.position());
    }

    private static void lookAtWorld(EntityMaid maid, Vec3 worldTarget) {
        Vec3 localTarget = worldTarget;
        SubLevel containing = Sable.HELPER.getContaining(maid);
        if (containing != null && !containing.isRemoved()) {
            Vector3d local = containing.logicalPose().transformPositionInverse(
                    new Vector3d(worldTarget.x, worldTarget.y, worldTarget.z));
            localTarget = new Vec3(local.x, local.y, local.z);
        }
        maid.getLookControl().setLookAt(localTarget);
    }

    private static boolean isLoadingMode(EntityMaid maid) {
        return maid.getTask() != null && TaskCannonLoading.UID.equals(maid.getTask().getUid());
    }
}
