package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.mojang.serialization.Codec;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import java.util.UUID;

/** Keeps only the chosen binding source alive until the wrench session ends. */
@net.neoforged.fml.common.EventBusSubscriber(modid=StabilizedTurret.MOD_ID)
public final class BindingSourceLease implements AutoCloseable {
    private static final TicketType<UUID> ROOT=TicketType.create("create_fire_control_binding",UUID::compareTo);
    private static final SubLevelLoadingTicketType<String> BODY=SubLevelLoadingTicketType.create(
            ResourceLocation.fromNamespaceAndPath(StabilizedTurret.MOD_ID,"binding_source"),Codec.STRING);
    private final UUID key=UUID.randomUUID();
    private final ServerLevel level;
    private final ServerSubLevelContainer container;
    private final ServerSubLevel body;
    private final ChunkPos chunk;
    private boolean closed;
    BindingSourceLease(ServerLevel level,BindingRef source) {
        this.level=level;
        container=(ServerSubLevelContainer)SubLevelContainer.getContainer(level);
        body=source.subLevelId()!=null&&container.getSubLevel(source.subLevelId()) instanceof ServerSubLevel sub?sub:null;
        chunk=body==null?new ChunkPos(source.pos()):null;
        if(body!=null)container.addForceLoadTicket(body,BODY,key.toString());
        else level.getChunkSource().addRegionTicket(ROOT,chunk,0,key,true);
    }
    @Override public void close() {
        if(closed)return;closed=true;
        if(body!=null)container.removeForceLoadTicket(body,BODY,key.toString());
        else level.getChunkSource().removeRegionTicket(ROOT,chunk,0,key,true);
    }
    @net.neoforged.bus.api.SubscribeEvent
    public static void clearInterruptedSessions(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        // Binding sessions are never persisted. Discard only our own leases after an interrupted game.
        for(ServerLevel level:event.getServer().getAllLevels()) {
            var container=(ServerSubLevelContainer)SubLevelContainer.getContainer(level);
            for(var entry:container.collectForceLoadTickets().entrySet())
                for(var ticket:java.util.Set.copyOf(entry.getValue()))
                    if(ticket.type().equals(BODY))container.removeForceLoadTicket(entry.getKey(),BODY,(String)ticket.key());
            for(var info:container.getAllTickets().values())info.tickets().removeIf(ticket->ticket.type().equals(BODY));
        }
    }
}
