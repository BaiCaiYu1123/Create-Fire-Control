package com.hooya.stabilizedturret.content.loader;

import com.hooya.stabilizedturret.client.MzLoaderClientSound;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.network.MzLoaderAnimationPayload;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import net.neoforged.neoforge.network.PacketDistributor;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.UUID;

public class MzLoaderBlockEntity extends BlockEntity
        implements GeoBlockEntity, BlockEntitySubLevelActor, CannonAutoLoader {
    public static final String CONTROLLER = "loader";
    public static final String ANIM_LOAD = "MZ2";
    public static final int MAX_LOADED = 24;
    /** MZ2 is 7.08s long -> 142 ticks. */
    public static final int ANIM_TICKS = 142;
    /** The cartridge reaches the breech at 4.39s, rounded to the nearest game tick. */
    public static final int INSERT_TICKS = 88;
    /** Open the breech two seconds after the loader animation starts. */
    public static final int OPEN_BREECH_TICKS = 40;
    /** Close the breech six seconds after the loader animation starts. */
    public static final int CLOSE_BREECH_TICKS = 120;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private boolean wasPowered;
    /** True after the initial live redstone state has been sampled on the server. */
    private boolean powerStateInitialized;
    /**
     * Occupancy bitmask of the 24 rack slots, in Blockbench group order (bit 0 = first group).
     * Loading fills the first empty slot; a completed loading animation clears the front-most occupied slot.
     */
    private int slots;
    private final LoaderAmmunition ammunition = new LoaderAmmunition();
    /**
     * Players may fill the rack one round at a time until its first loading cycle starts.
     * It stays locked against topping-up until the rack is completely empty again.
     */
    private boolean loadingLocked;
    /** Prevents projectile, block-event and explosion callbacks from detonating the same rack twice. */
    private boolean detonating;
    /** Game tick until which the loading animation is busy; redstone is ignored before this. */
    private long playUntilTick;
    /** Server-only: a round is being loaded and must be consumed when the animation finishes. */
    private boolean consumePending;
    /** Persisted reference to the CBC/CBC Neo Warfare mount controlled by this loader. */
    private @Nullable BindingRef cannonMountBinding;
    /** Server-only automatic cycle state, kept separately from the legacy redstone animation. */
    private boolean automaticOpenPending;
    private long automaticOpenAtTick;
    private boolean automaticLoadPending;
    private long automaticLoadAtTick;
    private boolean automaticClosePending;
    private long automaticCloseAtTick;
    private @Nullable UUID automaticCannonId;
    /** True only when this cycle changed a closed breech to open. */
    private boolean automaticBreechOpenedByLoader;
    /** Number of completed loading animations; drives the rack drum's resting angle (15 degrees per shot). */
    private int wheelIndex;
    /** Client-only: whether the animation is currently being driven by the predicate. */
    private boolean animating;
    /** Client-only: name of the per-rack animation variant currently playing. */
    private String currentAnimName = ANIM_LOAD;
    /** Client-only: game tick when the current loading animation started (for the wheel swing). */
    private long animStartTick;
    /** Server-only: prevents an overdue saved cycle from executing all keyframes in one tick. */
    private long automaticPhaseProcessedTick = Long.MIN_VALUE;
    /**
     * Client-only: the server will consume one round when the current animation ends, and we have
     * not yet received its sync update. Cleared by any incoming block-entity update packet.
     */
    private boolean consumePendingClient;

    public MzLoaderBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MZ_LOADER.get(), pos, state);
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (!level.isClientSide) MzLoaderAutomation.track(this);
    }

    public int getSlots() {
        return slots;
    }

    public int getSlotCount() {
        return Integer.bitCount(slots);
    }

    @Override
    public int getApfsdsCount() {
        int remaining = ammunition.standardCount(slots);
        // The visual rack keeps the inserted round until the automatic animation ends.
        boolean transferAwaitingAnimationEnd = consumePending && !automaticLoadPending
                && automaticLoadAtTick == playUntilTick - ANIM_TICKS + INSERT_TICKS;
        if (transferAwaitingAnimationEnd && LoaderAmmunition.isStandard(getNextCartridge())) {
            return remaining - 1;
        }
        return remaining;
    }

    @Override
    public ItemStack getNextCartridge() {
        return ammunition.cartridge(Integer.lowestOneBit(slots));
    }

    @Override
    public boolean beginDetonation() {
        if (detonating || slots == 0) return false;
        detonating = true;
        return true;
    }

    @Override
    public void cancelDetonation() {
        detonating = false;
    }

    public @Nullable BindingRef getCannonMountBinding() {
        return cannonMountBinding;
    }

    public void bindCannonMount(BindingRef binding) {
        cannonMountBinding = binding;
        sync();
    }

    /** Starts the authoritative animation/cooldown after a successful cannon shot. */
    public boolean startAutomaticCycle(UUID cannonId) {
        return startAutomaticCycle(cannonId, OPEN_BREECH_TICKS);
    }

    /** Starts one non-overlapping automatic cycle with the requested breech-opening delay. */
    public boolean startAutomaticCycle(UUID cannonId, int openDelayTicks) {
        if (level == null || level.isClientSide || cannonId == null
                || level.getGameTime() < playUntilTick || isBusy() || slots == 0
                || automaticOpenPending || automaticLoadPending
                || automaticClosePending || automaticCannonId != null) {
            return false;
        }
        long now = level.getGameTime();
        playUntilTick = now + ANIM_TICKS;
        automaticOpenAtTick = now + Math.max(0, openDelayTicks);
        automaticLoadAtTick = now + INSERT_TICKS;
        automaticCloseAtTick = now + CLOSE_BREECH_TICKS;
        automaticCannonId = cannonId;
        automaticOpenPending = true;
        automaticLoadPending = true;
        automaticClosePending = false;
        automaticBreechOpenedByLoader = false;
        consumePending = false;
        loadingLocked = true;
        sync();
        syncAnimationStart();
        return true;
    }

    @Override
    public ResourceLocation requiredBreechId() {
        return ResourceLocation.fromNamespaceAndPath("create_fire_control", "mz_cannon_breech");
    }

    /**
     * Inserts one round while the rack is in its initial filling state. The first
     * loading cycle locks further insertion until every loaded round has been used.
     */
    public boolean insertRound() {
        return insertRound(LoaderAmmunition.standardCartridge());
    }

    public boolean insertRound(ItemStack cartridge) {
        if (!LoaderAmmunition.isSupported(cartridge)) return false;
        if (loadingLocked || getSlotCount() >= MAX_LOADED) return false;
        int emptySlot = Integer.numberOfTrailingZeros(~slots & ((1 << MAX_LOADED) - 1));
        slots |= 1 << emptySlot;
        ammunition.insert(1 << emptySlot, cartridge);
        sync();
        return true;
    }

    /** Server-side only: consume the front-most occupied round (one completed loading animation). */
    public void consumeRound() {
        if (slots == 0) {
            return;
        }
        slots &= slots - 1;
        ammunition.retain(slots);
        if (slots == 0) {
            // An empty loader returns to exactly the same state as a newly placed loader.
            wheelIndex = 0;
            loadingLocked = false;
        } else {
            // The rack drum advances one step (15 degrees) after every completed loading cycle.
            wheelIndex = (wheelIndex + 1) % MAX_LOADED;
        }
        sync();
    }

    public int getWheelIndex() {
        return wheelIndex;
    }

    /**
     * Client-side: current wheel swing in degrees. The rack drum rests at wheelIndex * 15 degrees
     * and swings an extra 15 degrees during the first 1.52s of the loading animation (same ramp as
     * the original keyframes), so the next rack arrives at the loading position.
     */
    public float getWheelSwingDegrees() {
        if (level == null || level.getGameTime() >= playUntilTick) {
            return 0.0f;
        }
        float ticks = (float) (level.getGameTime() - animStartTick);
        float rampTicks = 1.52f * 20.0f;
        return 15.0f * Math.min(1.0f, ticks / rampTicks);
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            // Sable plots use their own tracking-player set. Vanilla chunk tracking does not
            // reliably forward block-entity update packets for a moving/nested plot.
            SubLevel containing = Sable.HELPER.getContaining(this);
            if (containing instanceof ServerSubLevel subLevel) {
                ClientboundBlockEntityDataPacket packet = getUpdatePacket();
                for (UUID playerId : subLevel.getTrackingPlayers()) {
                    if (subLevel.getLevel().getPlayerByUUID(playerId) instanceof ServerPlayer player) {
                        player.connection.send(packet);
                    }
                }
            }
        }
    }

    /**
     * Sable plot chunks live outside the normal client view range, so their vanilla block-entity
     * packets can be discarded during rapid tracking changes. Send the small animation timeline
     * through the mod channel as well; authoritative loading still remains entirely server-side.
     */
    private void syncAnimationStart() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        SubLevel containing = Sable.HELPER.getContaining(this);
        UUID subLevelId = containing == null ? null : containing.getUniqueId();
        MzLoaderAnimationPayload payload = new MzLoaderAnimationPayload(
                worldPosition, subLevelId, playUntilTick, slots, wheelIndex, consumePending);
        if (containing instanceof ServerSubLevel subLevel) {
            for (UUID playerId : subLevel.getTrackingPlayers()) {
                if (subLevel.getLevel().getPlayerByUUID(playerId) instanceof ServerPlayer player) {
                    sendAnimationIfSupported(player, payload);
                }
            }
        } else {
            for (ServerPlayer player : serverLevel.getChunkSource().chunkMap
                    .getPlayers(new ChunkPos(worldPosition), false)) {
                sendAnimationIfSupported(player, payload);
            }
        }
    }

    private static void sendAnimationIfSupported(
            ServerPlayer player, MzLoaderAnimationPayload payload
    ) {
        if (player.connection.hasChannel(MzLoaderAnimationPayload.TYPE)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    public boolean isBusy() {
        return level != null && level.getGameTime() < playUntilTick;
    }

    /** Server tick: start the loading animation on a redstone rising edge (only when idle). */
    public static void serverTick(Level level, BlockPos pos, BlockState state, MzLoaderBlockEntity be) {
        if (level.isClientSide || Sable.HELPER.getContaining(be) != null) return;
        be.serverTick();
    }

    @Override
    public void sable$tick(ServerSubLevel subLevel) {
        if (!subLevel.getLevel().isClientSide) serverTick();
    }

    private void serverTick() {
        if (level == null) return;
        BlockPos pos = worldPosition;
        BlockState state = getBlockState();
        long now = level.getGameTime();
        MzLoaderAutomation.track(this);
        if (cannonMountBinding != null
                && level.hasChunkAt(cannonMountBinding.pos())
                && !cannonMountBinding.isSameTarget(level)) {
            closeOwnedAutomaticBreech(automaticCannonId);
            cannonMountBinding = null;
            automaticOpenPending = false;
            automaticLoadPending = false;
            automaticClosePending = false;
            automaticCannonId = null;
            sync();
        }
        // Finish an expired inventory animation before an empty breech is allowed
        // to claim the next automatic cycle.
        if (consumePending && now >= playUntilTick) {
            consumePending = false;
            consumeRound();
        }
        if (automaticOpenPending && now >= automaticOpenAtTick) {
            if (automaticCannonId != null) {
                boolean available = MzLoaderAutomation.isCannonAvailable(
                        this, automaticCannonId);
                if (available) {
                    automaticOpenPending = false;
                    automaticBreechOpenedByLoader = MzLoaderAutomation.openBreechIfClosed(
                            this, automaticCannonId);
                }
            } else {
                automaticOpenPending = false;
            }
            setChanged();
            if (!automaticOpenPending && now >= automaticLoadAtTick) {
                automaticPhaseProcessedTick = now;
            }
        }
        if (automaticLoadPending && now >= automaticLoadAtTick
                && !automaticOpenPending && automaticPhaseProcessedTick != now) {
            UUID cannonId = automaticCannonId;
            boolean available = cannonId != null
                    && MzLoaderAutomation.isCannonAvailable(this, cannonId);
            if (!available) {
                // A mount entity can be published a few ticks after this block entity
                // on a chunk/world reload. Keep both the cartridge and breech ownership
                // pending until the exact same cannon can be resolved again.
                automaticPhaseProcessedTick = now;
            } else if (MzLoaderAutomation.insertCartridge(this, cannonId)) {
                automaticLoadPending = false;
                automaticClosePending = true;
                // Keep the animation's rack index untouched while MZ2 is still
                // playing. Inventory/wheel advancement happens at its natural end.
                consumePending = true;
                sync();
            } else {
                automaticLoadPending = false;
                automaticClosePending = automaticBreechOpenedByLoader;
                automaticCloseAtTick = now;
                if (!automaticClosePending) automaticCannonId = null;
                sync();
            }
            if (available) automaticPhaseProcessedTick = now;
        }
        if (automaticClosePending && now >= automaticCloseAtTick
                && automaticPhaseProcessedTick != now) {
            UUID cannonId = automaticCannonId;
            if (closeOwnedAutomaticBreech(cannonId)) {
                automaticClosePending = false;
                automaticCannonId = null;
                sync();
            }
            automaticPhaseProcessedTick = now;
        }
        // Recover/finish an existing persisted cycle before inspecting an empty
        // chamber for a new one. This ordering preserves breech ownership across
        // unloads even when every saved keyframe is already overdue.
        MzLoaderAutomation.tickBoundLoader(this);
        boolean powered = level.hasNeighborSignal(pos);
        boolean powerStateChanged = !powerStateInitialized || powered != wasPowered;
        if (powerStateInitialized && powered && !wasPowered) {
            // No rounds left in the rack -> the loading animation cannot be started.
            if (now >= playUntilTick && getSlotCount() > 0) {
                playUntilTick = now + ANIM_TICKS;
                consumePending = true;
                loadingLocked = true;
                setChanged();
                level.sendBlockUpdated(pos, state, state, 3);
                syncAnimationStart();
            }
        }
        wasPowered = powered;
        powerStateInitialized = true;
        if (powerStateChanged) setChanged();
    }

    private boolean closeOwnedAutomaticBreech(@Nullable UUID cannonId) {
        if (automaticBreechOpenedByLoader && cannonId != null) {
            if (!MzLoaderAutomation.closeBreechIfOpen(this, cannonId)) return false;
        }
        automaticBreechOpenedByLoader = false;
        return true;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Slots", slots);
        ammunition.save(tag);
        tag.putInt("WheelIndex", wheelIndex);
        tag.putBoolean("LoadingLocked", loadingLocked);
        tag.putLong("PlayUntilTick", playUntilTick);
        tag.putBoolean("WasPowered", wasPowered);
        tag.putBoolean("PowerStateInitialized", powerStateInitialized);
        tag.putBoolean("ConsumePending", consumePending);
        if (cannonMountBinding != null) {
            tag.put("CannonMountBinding", cannonMountBinding.save());
        }
        tag.putBoolean("AutomaticOpenPending", automaticOpenPending);
        tag.putLong("AutomaticOpenAtTick", automaticOpenAtTick);
        tag.putBoolean("AutomaticLoadPending", automaticLoadPending);
        tag.putLong("AutomaticLoadAtTick", automaticLoadAtTick);
        tag.putBoolean("AutomaticClosePending", automaticClosePending);
        tag.putLong("AutomaticCloseAtTick", automaticCloseAtTick);
        if (automaticCannonId != null) {
            tag.putUUID("AutomaticCannon", automaticCannonId);
        }
        tag.putBoolean("AutomaticBreechOpenedByLoader", automaticBreechOpenedByLoader);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        slots = tag.getInt("Slots") & ((1 << MAX_LOADED) - 1);
        ammunition.load(tag, slots);
        wheelIndex = tag.getInt("WheelIndex") % MAX_LOADED;
        loadingLocked = tag.getBoolean("LoadingLocked");
        playUntilTick = readPlayUntilTick(tag);
        wasPowered = tag.getBoolean("WasPowered");
        powerStateInitialized = tag.getBoolean("PowerStateInitialized")
                || tag.contains("WasPowered");
        consumePending = tag.getBoolean("ConsumePending");
        cannonMountBinding = BindingRef.load(tag, "CannonMountBinding");
        automaticOpenPending = tag.getBoolean("AutomaticOpenPending");
        automaticOpenAtTick = tag.getLong("AutomaticOpenAtTick");
        automaticLoadPending = tag.getBoolean("AutomaticLoadPending");
        automaticLoadAtTick = tag.getLong("AutomaticLoadAtTick");
        automaticClosePending = tag.getBoolean("AutomaticClosePending");
        automaticCloseAtTick = tag.getLong("AutomaticCloseAtTick");
        automaticCannonId = tag.hasUUID("AutomaticCannon")
                ? tag.getUUID("AutomaticCannon") : null;
        automaticBreechOpenedByLoader = tag.getBoolean("AutomaticBreechOpenedByLoader");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider registries) {
        long previousPlayUntilTick = playUntilTick;
        super.onDataPacket(net, pkt, registries);
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            long syncedPlayUntilTick = tag.getLong("PlayUntilTick");
            boolean startsNewCycle = syncedPlayUntilTick > previousPlayUntilTick;
            slots = tag.getInt("Slots") & ((1 << MAX_LOADED) - 1);
            wheelIndex = tag.getInt("WheelIndex") % MAX_LOADED;
            playUntilTick = syncedPlayUntilTick;
            // Predict removal only after the server has actually inserted the cartridge.
            // This avoids a one-frame shell flash when the animation ends before its final sync arrives.
            consumePendingClient = tag.getBoolean("ConsumePending");
            // A new cycle can start on the same client tick on which the previous one expires.
            // Make the predicate start it afresh instead of continuing the old controller state.
            if (startsNewCycle) animating = false;
        }
    }

    /** Applies the redundant reliable start packet without restarting an already playing cycle. */
    public void acceptAnimationSync(long untilTick, int syncedSlots, int syncedWheelIndex,
                                    boolean syncedConsumePending) {
        if (level == null || !level.isClientSide || untilTick <= level.getGameTime()) return;
        // A normal block-entity update for this cycle may already be newer than this redundant
        // start packet. In that case do not roll inventory/consume state back to the start frame.
        if (untilTick <= playUntilTick) return;
        slots = syncedSlots & ((1 << MAX_LOADED) - 1);
        wheelIndex = Math.floorMod(syncedWheelIndex, MAX_LOADED);
        consumePendingClient = syncedConsumePending;
        playUntilTick = untilTick;
        // This also handles two cycles that meet at a tick boundary without replaying duplicate
        // block-entity/custom packets from the same cycle.
        animating = false;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, CONTROLLER, 0, this::predicate)
                // Play the loading sound as a positional block sound (mono source: distance + left/right pan).
                .setSoundKeyframeHandler(event ->
                        MzLoaderClientSound.play(event.getAnimatable())));
    }

    /**
     * Drives the MZ2 animation only while the server-side cooldown is active.
     * A finished animation returns to the bind pose; the next redstone pulse can start it again.
     */
    private <E extends GeoBlockEntity> PlayState predicate(AnimationState<E> event) {
        if (level != null && level.getGameTime() < playUntilTick) {
            if (!animating) {
                // Each shot lifts the rack that the drum has advanced to: rack (wheelIndex + 1).
                currentAnimName = "MZ2_" + (getWheelIndex() + 1);
                animStartTick = level.getGameTime();
                consumePendingClient = consumePending;
                event.getController().forceAnimationReset();
                animating = true;
            }
            event.getController().setAnimation(RawAnimation.begin().thenPlay(currentAnimName));
            return PlayState.CONTINUE;
        }
        if (animating) {
            animating = false;
            // The server consumes the lifted round exactly when the animation cooldown ends.
            // Mirror consumeRound() locally right away (before the sync packet arrives) so the
            // consumed round does not flash back into its rack and the drum does not snap back
            // one step for a frame.
            if (level != null && level.isClientSide && consumePendingClient) {
                slots &= slots - 1;
                wheelIndex = slots == 0 ? 0 : (wheelIndex + 1) % MAX_LOADED;
                consumePendingClient = false;
            }
            consumePendingClient = false;
        }
        return PlayState.STOP;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    static long readPlayUntilTick(CompoundTag tag) {
        // getLong accepts the legacy integer tag and the current long tag.
        return tag == null ? 0L : tag.getLong("PlayUntilTick");
    }
}
