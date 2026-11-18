package com.hooya.stabilizedturret.content.loader;

import com.hooya.stabilizedturret.client.AzLoaderClientSound;
import com.hooya.stabilizedturret.content.controller.BindingRef;
import com.hooya.stabilizedturret.network.AzLoaderAnimationPayload;
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
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Inventory and redstone state for the independent AZ loader. */
public class AzLoaderBlockEntity extends BlockEntity
        implements GeoBlockEntity, BlockEntitySubLevelActor, CannonAutoLoader {
    private static final int STATE_VERSION = 1;
    public static final String CONTROLLER = "loader";
    public static final String ANIM_LOAD = "AZ";
    public static final int MAX_LOADED = 24;
    /** The source animation is 6.68 seconds long. */
    public static final int ANIM_TICKS = 134;
    public static final int OPEN_BREECH_TICKS = 40;
    /** The cartridge disappears into the breech at 5.12 seconds. */
    public static final int INSERT_TICKS = 102;
    public static final int CLOSE_BREECH_TICKS = 120;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private boolean wasPowered;
    private boolean powerStateInitialized;
    /** Occupancy bits by Blockbench slot number (bit 0 = slot 1, bit 23 = slot 24). */
    private int slots;
    private final LoaderAmmunition ammunition = new LoaderAmmunition();
    /** Casing bits persist after their corresponding live rounds are consumed. */
    private int casingSlots;
    /** Prevents topping up after the first cycle until the carousel is empty again. */
    private boolean loadingLocked;
    private boolean detonating;
    private long playUntilTick;
    private boolean consumePending;
    /** Persistent 15-degree carousel step count. */
    private int wheelIndex;
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
    private String currentAnimName = ANIM_LOAD;
    private boolean consumePendingClient;

    public AzLoaderBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.AZ_LOADER.get(), pos, state);
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

    public int getCasingSlots() {
        return casingSlots;
    }

    public int getWheelIndex() {
        return wheelIndex;
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

    @Override
    public @Nullable BindingRef getCannonMountBinding() {
        return cannonMountBinding;
    }

    public void bindCannonMount(BindingRef binding) {
        cannonMountBinding = binding;
        sync();
    }

    @Override
    public boolean startAutomaticCycle(UUID cannonId) {
        return startAutomaticCycle(cannonId, OPEN_BREECH_TICKS);
    }

    @Override
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
        return ResourceLocation.fromNamespaceAndPath("create_fire_control", "az_cannon_breech");
    }

    public boolean isBusy() {
        return level != null && level.getGameTime() < playUntilTick;
    }

    public boolean insertRound() {
        return insertRound(LoaderAmmunition.standardCartridge());
    }

    public boolean insertRound(ItemStack cartridge) {
        if (!LoaderAmmunition.isSupported(cartridge)) return false;
        if (loadingLocked || getSlotCount() >= MAX_LOADED) return false;
        int emptySlot = firstEmptySlot(slots);
        slots = insertIntoFirstEmptySlot(slots);
        ammunition.insert(1 << emptySlot, cartridge);
        casingSlots |= 1 << emptySlot;
        sync();
        return true;
    }

    private void consumeRound() {
        if (slots == 0) return;
        slots = consumeFrontSlot(slots);
        ammunition.retain(slots);
        if (slots == 0) {
            wheelIndex = advanceWheelIndex(wheelIndex, slots);
            loadingLocked = false;
        } else {
            wheelIndex = advanceWheelIndex(wheelIndex, slots);
        }
        sync();
    }

    static int firstEmptySlot(int occupiedSlots) {
        int mask = (1 << MAX_LOADED) - 1;
        return Integer.numberOfTrailingZeros(~occupiedSlots & mask);
    }

    static int insertIntoFirstEmptySlot(int occupiedSlots) {
        return occupiedSlots | 1 << firstEmptySlot(occupiedSlots);
    }

    static int consumeFrontSlot(int occupiedSlots) {
        return occupiedSlots == 0 ? 0 : occupiedSlots & (occupiedSlots - 1);
    }

    static int advanceWheelIndex(int currentWheelIndex, int remainingSlots) {
        return remainingSlots == 0 ? 0 : (currentWheelIndex + 1) % MAX_LOADED;
    }

    static String animationNameForWheelIndex(int currentWheelIndex) {
        return "AZ_" + (Math.floorMod(currentWheelIndex, MAX_LOADED) + 1);
    }

    private void sync() {
        setChanged();
        if (level == null || level.isClientSide) return;
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
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

    private void syncAnimationStart() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        SubLevel containing = Sable.HELPER.getContaining(this);
        UUID subLevelId = containing == null ? null : containing.getUniqueId();
        AzLoaderAnimationPayload payload = new AzLoaderAnimationPayload(
                worldPosition, subLevelId, playUntilTick, slots, casingSlots,
                wheelIndex, consumePending);
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
            ServerPlayer player, AzLoaderAnimationPayload payload) {
        if (player.connection.hasChannel(AzLoaderAnimationPayload.TYPE)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  AzLoaderBlockEntity loader) {
        if (level.isClientSide || Sable.HELPER.getContaining(loader) != null) return;
        loader.serverTick();
    }

    @Override
    public void sable$tick(ServerSubLevel subLevel) {
        if (!subLevel.getLevel().isClientSide) serverTick();
    }

    private void serverTick() {
        if (level == null) return;
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
                automaticPhaseProcessedTick = now;
            } else if (MzLoaderAutomation.insertCartridge(this, cannonId)) {
                automaticLoadPending = false;
                automaticClosePending = true;
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

        MzLoaderAutomation.tickBoundLoader(this);

        boolean powered = level.hasNeighborSignal(worldPosition);
        boolean powerStateChanged = !powerStateInitialized || powered != wasPowered;
        if (powerStateInitialized && powered && !wasPowered
                && now >= playUntilTick && slots != 0) {
            playUntilTick = now + ANIM_TICKS;
            consumePending = true;
            loadingLocked = true;
            sync();
            syncAnimationStart();
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
        tag.putInt("AzStateVersion", STATE_VERSION);
        tag.putInt("Slots", slots);
        ammunition.save(tag);
        tag.putInt("CasingSlots", casingSlots);
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
        if (automaticCannonId != null) tag.putUUID("AutomaticCannon", automaticCannonId);
        tag.putBoolean("AutomaticBreechOpenedByLoader", automaticBreechOpenedByLoader);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.getInt("AzStateVersion") != STATE_VERSION) {
            slots = 0;
            ammunition.retain(0);
            casingSlots = 0;
            wheelIndex = 0;
            loadingLocked = false;
            playUntilTick = 0L;
            wasPowered = false;
            powerStateInitialized = false;
            consumePending = false;
            return;
        }
        int mask = (1 << MAX_LOADED) - 1;
        slots = tag.getInt("Slots") & mask;
        ammunition.load(tag, slots);
        casingSlots = tag.getInt("CasingSlots") & mask;
        wheelIndex = Math.floorMod(tag.getInt("WheelIndex"), MAX_LOADED);
        loadingLocked = tag.getBoolean("LoadingLocked");
        playUntilTick = tag.getLong("PlayUntilTick");
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
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet,
                             HolderLookup.Provider registries) {
        long previousPlayUntilTick = playUntilTick;
        super.onDataPacket(net, packet, registries);
        CompoundTag tag = packet.getTag();
        if (tag == null) return;
        long syncedPlayUntilTick = tag.getLong("PlayUntilTick");
        slots = tag.getInt("Slots") & ((1 << MAX_LOADED) - 1);
        casingSlots = tag.getInt("CasingSlots") & ((1 << MAX_LOADED) - 1);
        wheelIndex = Math.floorMod(tag.getInt("WheelIndex"), MAX_LOADED);
        playUntilTick = syncedPlayUntilTick;
        consumePendingClient = tag.getBoolean("ConsumePending");
        if (syncedPlayUntilTick > previousPlayUntilTick) animating = false;
    }

    public void acceptAnimationSync(long untilTick, int syncedSlots, int syncedCasingSlots,
                                    int syncedWheelIndex,
                                    boolean syncedConsumePending) {
        if (level == null || !level.isClientSide || untilTick <= level.getGameTime()
                || untilTick <= playUntilTick) {
            return;
        }
        slots = syncedSlots & ((1 << MAX_LOADED) - 1);
        casingSlots = syncedCasingSlots & ((1 << MAX_LOADED) - 1);
        wheelIndex = Math.floorMod(syncedWheelIndex, MAX_LOADED);
        consumePendingClient = syncedConsumePending;
        playUntilTick = untilTick;
        animating = false;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, CONTROLLER, 0, this::predicate)
                .setSoundKeyframeHandler(event ->
                        AzLoaderClientSound.play(event.getAnimatable())));
    }

    private <E extends GeoBlockEntity> PlayState predicate(AnimationState<E> event) {
        if (level != null && level.getGameTime() < playUntilTick) {
            if (!animating) {
                currentAnimName = animationNameForWheelIndex(wheelIndex);
                consumePendingClient = consumePending;
                event.getController().forceAnimationReset();
                animating = true;
            }
            event.getController().setAnimation(RawAnimation.begin().thenPlay(currentAnimName));
            return PlayState.CONTINUE;
        }
        if (animating) {
            animating = false;
            if (level != null && level.isClientSide && consumePendingClient && slots != 0) {
                slots = consumeFrontSlot(slots);
                wheelIndex = advanceWheelIndex(wheelIndex, slots);
            }
            consumePendingClient = false;
        }
        return PlayState.STOP;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
