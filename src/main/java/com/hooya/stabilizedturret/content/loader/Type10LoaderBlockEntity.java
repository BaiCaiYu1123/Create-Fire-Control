package com.hooya.stabilizedturret.content.loader;

import com.hooya.stabilizedturret.client.Type10LoaderClientSound;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.network.Type10LoaderAnimationPayload;
import com.hooya.stabilizedturret.registry.ModBlockEntities;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.*;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.UUID;

/** Thirteen-round bustle rack sharing the MZ/AZ mount binding and loading bridge. */
public class Type10LoaderBlockEntity extends BlockEntity
        implements GeoBlockEntity, BlockEntitySubLevelActor, CannonAutoLoader {
    public static final int MAX_LOADED = 13;
    public static final int SLOT_MASK = (1 << MAX_LOADED) - 1;
    public static final String ANIMATION_NAME = "animation";
    private static final RawAnimation LOAD_ANIMATION = RawAnimation.begin().thenPlay(ANIMATION_NAME);
    public static final int ANIM_TICKS = 88;
    public static final int OPEN_BREECH_TICKS = 20;
    /** The source shell shrinks into the chamber at 2.64 seconds. */
    public static final int INSERT_TICKS = 53;
    /** Close after the rammer has returned at 3.56 seconds. */
    public static final int CLOSE_BREECH_TICKS = 72;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private int slots;
    private final LoaderAmmunition ammunition = new LoaderAmmunition();
    /** Legacy saved/network metadata; inventory slot numbers never select animation targets. */
    private int cycleSlot;
    private boolean loadingLocked;
    private boolean detonating;
    private boolean wasPowered;
    private boolean powerStateInitialized;
    private long playUntilTick;
    private boolean consumePending;
    private @Nullable BindingRef cannonMountBinding;
    private boolean automaticOpenPending;
    private long automaticOpenAtTick;
    private boolean automaticLoadPending;
    private long automaticLoadAtTick;
    private boolean automaticClosePending;
    private long automaticCloseAtTick;
    private @Nullable UUID automaticCannonId;
    private boolean automaticBreechOpenedByLoader;
    private long automaticPhaseProcessedTick = Long.MIN_VALUE;
    private boolean animating;

    public Type10LoaderBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TYPE_10_LOADER.get(), pos, state);
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (!level.isClientSide) MzLoaderAutomation.track(this);
    }

    public int getSlots() { return slots; }

    public boolean isAnimationPlaying() { return level != null && level.getGameTime() < playUntilTick; }

    @Override
    public int getSlotCount() { return Integer.bitCount(slots); }

    @Override
    public int getApfsdsCount() { return ammunition.standardCount(slots); }

    @Override
    public ItemStack getNextCartridge() {
        return ammunition.cartridge(Integer.highestOneBit(slots));
    }

    public static int insertIntoFirstEmptySlot(int occupied) {
        int empty = ~occupied & SLOT_MASK;
        return (occupied | Integer.lowestOneBit(empty)) & SLOT_MASK;
    }

    public static int consumeHighestSlot(int occupied) {
        occupied &= SLOT_MASK;
        return occupied & ~Integer.highestOneBit(occupied);
    }

    public static int highestSlotNumber(int occupied) {
        return Integer.SIZE - Integer.numberOfLeadingZeros(occupied & SLOT_MASK);
    }

    public boolean insertRound() {
        return insertRound(LoaderAmmunition.standardCartridge());
    }

    public boolean insertRound(ItemStack cartridge) {
        if (!LoaderAmmunition.isSupported(cartridge)) return false;
        if (loadingLocked || isBusy() || getSlotCount() >= MAX_LOADED) return false;
        int previousSlots = slots;
        slots = insertIntoFirstEmptySlot(slots);
        ammunition.insert(slots ^ previousSlots, cartridge);
        sync();
        return true;
    }

    private void consumeRound() {
        slots = consumeHighestSlot(slots);
        ammunition.retain(slots);
        if (slots == 0) loadingLocked = false;
        sync();
    }

    @Override
    public boolean beginDetonation() {
        if (detonating || slots == 0) return false;
        return detonating = true;
    }

    @Override
    public void cancelDetonation() { detonating = false; }

    @Override
    public @Nullable BindingRef getCannonMountBinding() { return cannonMountBinding; }

    @Override
    public void bindCannonMount(BindingRef binding) {
        cannonMountBinding = binding;
        sync();
    }

    @Override
    public ResourceLocation requiredBreechId() {
        return ResourceLocation.fromNamespaceAndPath("create_fire_control", "type_10_cannon_breech");
    }

    @Override
    public boolean isBusy() {
        return isAnimationPlaying() || consumePending || automaticOpenPending || automaticLoadPending
                || automaticClosePending || automaticCannonId != null;
    }

    @Override
    public boolean startAutomaticCycle(UUID cannonId) {
        return startAutomaticCycle(cannonId, OPEN_BREECH_TICKS);
    }

    @Override
    public boolean startAutomaticCycle(UUID cannonId, int openDelayTicks) {
        if (level == null || level.isClientSide || cannonId == null || isBusy() || slots == 0) {
            return false;
        }
        long now = level.getGameTime();
        cycleSlot = highestSlotNumber(slots);
        playUntilTick = now + ANIM_TICKS;
        loadingLocked = true;
        automaticOpenAtTick = now + Math.max(0, openDelayTicks);
        automaticLoadAtTick = now + INSERT_TICKS;
        automaticCloseAtTick = now + CLOSE_BREECH_TICKS;
        automaticCannonId = cannonId;
        automaticOpenPending = true;
        automaticLoadPending = true;
        automaticClosePending = false;
        automaticBreechOpenedByLoader = false;
        consumePending = false;
        sync();
        syncAnimationStart();
        return true;
    }

    private void sync() {
        setChanged();
        if (level == null || level.isClientSide) return;
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        if (Sable.HELPER.getContaining(this) instanceof ServerSubLevel subLevel) {
            for (UUID id : subLevel.getTrackingPlayers()) {
                if (subLevel.getLevel().getPlayerByUUID(id) instanceof ServerPlayer player) {
                    player.connection.send(getUpdatePacket());
                }
            }
        }
    }

    private void syncAnimationStart() {
        if (!(level instanceof ServerLevel server)) return;
        SubLevel containing = Sable.HELPER.getContaining(this);
        Type10LoaderAnimationPayload payload = new Type10LoaderAnimationPayload(
                worldPosition, containing == null ? null : containing.getUniqueId(),
                playUntilTick, slots, cycleSlot);
        if (containing instanceof ServerSubLevel subLevel) {
            for (UUID id : subLevel.getTrackingPlayers()) {
                if (subLevel.getLevel().getPlayerByUUID(id) instanceof ServerPlayer player) {
                    sendAnimation(player, payload);
                }
            }
        } else {
            for (ServerPlayer player : server.getChunkSource().chunkMap
                    .getPlayers(new ChunkPos(worldPosition), false)) sendAnimation(player, payload);
        }
    }

    private static void sendAnimation(ServerPlayer player, Type10LoaderAnimationPayload payload) {
        if (player.connection.hasChannel(Type10LoaderAnimationPayload.TYPE)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, Type10LoaderBlockEntity be) {
        if (!level.isClientSide && Sable.HELPER.getContaining(be) == null) be.serverTick();
    }

    @Override
    public void sable$tick(ServerSubLevel subLevel) {
        if (!subLevel.getLevel().isClientSide) serverTick();
    }

    private void serverTick() {
        if (level == null) return;
        long now = level.getGameTime();
        MzLoaderAutomation.track(this);
        if (cannonMountBinding != null && level.hasChunkAt(cannonMountBinding.pos())
                && !cannonMountBinding.isSameTarget(level)) {
            closeOwnedAutomaticBreech(automaticCannonId);
            cannonMountBinding = null;
            automaticOpenPending = automaticLoadPending = automaticClosePending = false;
            automaticCannonId = null;
            sync();
        }
        if (consumePending && now >= playUntilTick - ANIM_TICKS + INSERT_TICKS) {
            consumePending = false;
            consumeRound();
        }
        if (automaticOpenPending && now >= automaticOpenAtTick) {
            if (automaticCannonId != null && MzLoaderAutomation.isCannonAvailable(this, automaticCannonId)) {
                automaticOpenPending = false;
                automaticBreechOpenedByLoader = MzLoaderAutomation.openBreechIfClosed(this, automaticCannonId);
                if (now >= automaticLoadAtTick) automaticPhaseProcessedTick = now;
            }
            setChanged();
        }
        if (automaticLoadPending && now >= automaticLoadAtTick && !automaticOpenPending
                && automaticPhaseProcessedTick != now) {
            if (automaticCannonId != null && MzLoaderAutomation.isCannonAvailable(this, automaticCannonId)) {
                automaticLoadPending = false;
                if (MzLoaderAutomation.insertCartridge(this, automaticCannonId)) {
                    automaticClosePending = true;
                    // Consume on confirmed chamber insertion, exactly once, including after reload.
                    consumeRound();
                } else {
                    automaticClosePending = automaticBreechOpenedByLoader;
                    automaticCloseAtTick = now;
                    if (!automaticClosePending) automaticCannonId = null;
                    sync();
                }
            }
            automaticPhaseProcessedTick = now;
        }
        if (automaticClosePending && now >= automaticCloseAtTick && automaticPhaseProcessedTick != now) {
            if (closeOwnedAutomaticBreech(automaticCannonId)) {
                automaticClosePending = false;
                automaticCannonId = null;
                sync();
            }
            automaticPhaseProcessedTick = now;
        }
        MzLoaderAutomation.tickBoundLoader(this);
        boolean powered = level.hasNeighborSignal(worldPosition);
        boolean changed = !powerStateInitialized || powered != wasPowered;
        if (powerStateInitialized && powered && !wasPowered && !isBusy() && slots != 0) {
            cycleSlot = highestSlotNumber(slots);
            playUntilTick = now + ANIM_TICKS;
            consumePending = true;
            loadingLocked = true;
            sync();
            syncAnimationStart();
        }
        wasPowered = powered;
        powerStateInitialized = true;
        if (changed) setChanged();
    }

    private boolean closeOwnedAutomaticBreech(@Nullable UUID cannonId) {
        if (automaticBreechOpenedByLoader && cannonId != null
                && !MzLoaderAutomation.closeBreechIfOpen(this, cannonId)) return false;
        automaticBreechOpenedByLoader = false;
        return true;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Slots", slots);
        ammunition.save(tag);
        tag.putInt("CycleSlot", cycleSlot);
        tag.putBoolean("LoadingLocked", loadingLocked);
        tag.putLong("PlayUntilTick", playUntilTick);
        tag.putBoolean("WasPowered", wasPowered);
        tag.putBoolean("PowerStateInitialized", powerStateInitialized);
        tag.putBoolean("ConsumePending", consumePending);
        if (cannonMountBinding != null) tag.put("CannonMountBinding", cannonMountBinding.save());
        tag.putBoolean("AutomaticOpenPending", automaticOpenPending);
        tag.putLong("AutomaticOpenAtTick", automaticOpenAtTick);
        tag.putBoolean("AutomaticLoadPending", automaticLoadPending);
        tag.putLong("AutomaticLoadAtTick", automaticLoadAtTick);
        tag.putBoolean("AutomaticClosePending", automaticClosePending);
        tag.putLong("AutomaticCloseAtTick", automaticCloseAtTick);
        if (automaticCannonId != null) tag.putUUID("AutomaticCannon", automaticCannonId);
        tag.putBoolean("AutomaticBreechOpenedByLoader", automaticBreechOpenedByLoader);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        slots = tag.getInt("Slots") & SLOT_MASK;
        ammunition.load(tag, slots);
        cycleSlot = Math.clamp(tag.getInt("CycleSlot"), 0, MAX_LOADED);
        loadingLocked = slots != 0 && tag.getBoolean("LoadingLocked");
        playUntilTick = tag.getLong("PlayUntilTick");
        wasPowered = tag.getBoolean("WasPowered");
        powerStateInitialized = tag.getBoolean("PowerStateInitialized");
        consumePending = tag.getBoolean("ConsumePending");
        cannonMountBinding = BindingRef.load(tag, "CannonMountBinding");
        automaticOpenPending = tag.getBoolean("AutomaticOpenPending");
        automaticOpenAtTick = tag.getLong("AutomaticOpenAtTick");
        automaticLoadPending = tag.getBoolean("AutomaticLoadPending");
        automaticLoadAtTick = tag.getLong("AutomaticLoadAtTick");
        automaticClosePending = tag.getBoolean("AutomaticClosePending");
        automaticCloseAtTick = tag.getLong("AutomaticCloseAtTick");
        automaticCannonId = tag.hasUUID("AutomaticCannon") ? tag.getUUID("AutomaticCannon") : null;
        automaticBreechOpenedByLoader = tag.getBoolean("AutomaticBreechOpenedByLoader");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        long previous = playUntilTick;
        super.onDataPacket(net, packet, registries);
        if (playUntilTick > previous) animating = false;
    }

    public void acceptAnimationSync(long untilTick, int syncedSlots, int syncedCycleSlot) {
        if (level == null || !level.isClientSide || untilTick <= level.getGameTime()
                || untilTick <= playUntilTick) return;
        slots = syncedSlots & SLOT_MASK;
        cycleSlot = Math.clamp(syncedCycleSlot, 0, MAX_LOADED);
        playUntilTick = untilTick;
        animating = false;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "loader", 0, this::predicate)
                .setSoundKeyframeHandler(event -> Type10LoaderClientSound.play(event.getAnimatable())));
    }

    private <E extends GeoBlockEntity> PlayState predicate(AnimationState<E> event) {
        if (isAnimationPlaying()) {
            if (!animating) {
                event.getController().forceAnimationReset();
                animating = true;
            }
            event.getController().setAnimation(LOAD_ANIMATION);
            return PlayState.CONTINUE;
        }
        animating = false;
        return PlayState.STOP;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
