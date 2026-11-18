package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.loader.MzLoaderBlockEntity;
import com.hooya.stabilizedturret.network.MzLoaderAnimationPayload;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Applies loader starts independently of vanilla plot-chunk update visibility. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class MzLoaderClientSync {
    private static final int MAX_PENDING = 256;
    private static final Map<Key, MzLoaderAnimationPayload> PENDING = new LinkedHashMap<>();
    private static ClientLevel pendingLevel;

    public static void accept(MzLoaderAnimationPayload payload) {
        if (payload == null || payload.playUntilTick() < 0L) return;
        resetForLevel(Minecraft.getInstance().level);
        Key key = new Key(payload.pos().asLong(), payload.subLevelId());
        if (!apply(payload)) {
            PENDING.put(key, payload);
            while (PENDING.size() > MAX_PENDING) {
                PENDING.remove(PENDING.keySet().iterator().next());
            }
        } else {
            PENDING.remove(key);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        resetForLevel(level);
        if (level == null) {
            return;
        }
        PENDING.entrySet().removeIf(entry ->
                level.getGameTime() >= entry.getValue().playUntilTick()
                        || apply(entry.getValue()));
    }

    private static void resetForLevel(ClientLevel level) {
        if (pendingLevel == level) return;
        PENDING.clear();
        pendingLevel = level;
    }

    private static boolean apply(MzLoaderAnimationPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || level.getGameTime() >= payload.playUntilTick()) return false;
        SubLevel containing = Sable.HELPER.getContaining(level, payload.pos());
        if (payload.subLevelId() == null) {
            if (containing != null) return false;
        } else {
            SubLevel expected = SubLevelContainer.getContainer(level)
                    .getSubLevel(payload.subLevelId());
            if (expected == null || expected.isRemoved() || containing != expected) return false;
        }
        BlockEntity blockEntity = level.getBlockEntity(payload.pos());
        if (!(blockEntity instanceof MzLoaderBlockEntity loader)) return false;
        loader.acceptAnimationSync(payload.playUntilTick(), payload.slots(),
                payload.wheelIndex(), payload.consumePending());
        return true;
    }

    private record Key(long pos, UUID subLevelId) {
    }

    private MzLoaderClientSync() {
    }
}
