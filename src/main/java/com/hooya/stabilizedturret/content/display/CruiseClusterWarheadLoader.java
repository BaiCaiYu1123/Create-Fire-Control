package com.hooya.stabilizedturret.content.display;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.MianbaoMissileCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Keeps the 24 native cluster bomblets ticking until their own impacts. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class CruiseClusterWarheadLoader {
    private static final ResourceLocation LITTLE_BOMB = ResourceLocation.fromNamespaceAndPath(
            MianbaoMissileCompat.MOD_ID, "littlebomb_2");
    private static final TicketType<UUID> TICKET = TicketType.create(
            "create_fire_control_cluster_warhead", UUID::compareTo);
    private static final Map<Entity, Tickets> TRACKED = new IdentityHashMap<>();

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (event.getLevel().isClientSide()
                || !LITTLE_BOMB.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))
                || !CruiseMissileGuidance.adoptClusterWarhead(entity)) return;
        Tickets tickets = new Tickets();
        TRACKED.put(entity, tickets);
        if (entity.level() instanceof ServerLevel level) {
            tickets.update(level, entity.getUUID(),
                    MianbaoMissileCompat.getWorldPosition(entity),
                    MianbaoMissileCompat.getWorldVelocity(entity));
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        Iterator<Map.Entry<Entity, Tickets>> iterator = TRACKED.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Entity, Tickets> entry = iterator.next();
            Entity warhead = entry.getKey();
            Tickets tickets = entry.getValue();
            if (warhead.isRemoved() || !warhead.isAlive()) {
                tickets.release();
                iterator.remove();
                continue;
            }
            ServerLevel root = MianbaoMissileCompat.getRootServerLevel(warhead);
            if (root == null) {
                tickets.release();
                iterator.remove();
                continue;
            }
            tickets.update(root, warhead.getUUID(),
                    MianbaoMissileCompat.getWorldPosition(warhead),
                    MianbaoMissileCompat.getWorldVelocity(warhead));
        }
    }

    private static final class Tickets {
        private ServerLevel level;
        private UUID owner;
        private Set<ChunkPos> chunks = Set.of();

        void update(ServerLevel nextLevel, UUID nextOwner, Vec3 position, Vec3 velocity) {
            Set<ChunkPos> wanted = new HashSet<>();
            Vec3 end = position.add(velocity);
            int samples = Math.max(1, (int) Math.ceil(position.distanceTo(end) / 8.0));
            for (int index = 0; index <= samples; index++) {
                wanted.add(new ChunkPos(BlockPos.containing(
                        position.lerp(end, (double) index / samples))));
            }
            if (level != nextLevel || !nextOwner.equals(owner)) release();
            level = nextLevel;
            owner = nextOwner;
            for (ChunkPos chunk : chunks) if (!wanted.contains(chunk)) {
                level.getChunkSource().removeRegionTicket(TICKET, chunk, 0, owner, true);
            }
            for (ChunkPos chunk : wanted) if (!chunks.contains(chunk)) {
                level.getChunkSource().addRegionTicket(TICKET, chunk, 0, owner, true);
            }
            chunks = Set.copyOf(wanted);
        }

        void release() {
            if (level != null && owner != null) {
                for (ChunkPos chunk : chunks) {
                    level.getChunkSource().removeRegionTicket(TICKET, chunk, 0, owner, true);
                }
            }
            level = null;
            owner = null;
            chunks = Set.of();
        }
    }

    @SubscribeEvent
    public static void onStop(ServerStoppingEvent event) {
        TRACKED.values().forEach(Tickets::release);
        TRACKED.clear();
    }

    private CruiseClusterWarheadLoader() {}
}
