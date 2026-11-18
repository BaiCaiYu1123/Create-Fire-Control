package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlock;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayConsoleBlockEntity;
import com.hooya.stabilizedturret.network.BindingSessionPayload;
import com.simibubi.create.AllItems;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class AutoDefenseDisplayBindingHandler {
    private static final long BINDING_TIMEOUT_TICKS = 20L * 60L * 10L;
    private static final Map<UUID, PendingBinding> PENDING = new HashMap<>();

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void rightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof ServerPlayer player)
                || !AllItems.WRENCH.isIn(event.getItemStack())) return;
        Level level=event.getLevel(); PendingBinding pending=PENDING.get(player.getUUID());
        ConnectedDisplayBlockEntity clickedDisplay=ConnectedDisplayBlock.findController(level,event.getPos());
        if (clickedDisplay!=null) {
            StabilizerBindingHandler.cancelBinding(player,false);
            BindingRef ref=BindingRef.capture(level,clickedDisplay.getBlockPos());
            if (pending != null && ref.equals(pending.source())) cancel(player); else {
                if(pending!=null)pending.lease().close();
                PENDING.put(player.getUUID(),new PendingBinding(
                        ref, level.getGameTime() + BINDING_TIMEOUT_TICKS,new BindingSourceLease(player.serverLevel(),ref)));
                sendCandidates(player,clickedDisplay);
                player.displayClientMessage(Component.translatable("message.create_fire_control.auto_defense.select_module"),true);
            }
            consume(event); return;
        }
        if (pending==null) return;
        if (pending.expiresAt() < level.getGameTime()
                || !(pending.source().resolve(level) instanceof ConnectedDisplayBlockEntity display)) { cancel(player); return; }
        if (level.getBlockEntity(event.getPos())
                instanceof ConnectedDisplayConsoleBlockEntity console) {
            consume(event);
            console.bind(display);
            player.displayClientMessage(Component.translatable(
                    "message.create_fire_control.display_console.bound"), true);
            cancel(player);
            return;
        }
        BindingRef clickedRef = BindingRef.capture(level, event.getPos());
        // A second wrench click on an already bound mount is its normal CBC
        // assembly interaction, not another binding request. End the binding
        // session and let the original block receive that click.
        if (display.isBound(clickedRef)) {
            cancel(player);
            return;
        }
        consume(event);
        BindingSlot slot=MianbaoBallisticMissileCompat.isBindingTarget(level.getBlockState(event.getPos()))
                ? BindingSlot.BALLISTIC_MISSILE_LAUNCHER
                : MianbaoCruiseMissileCompat.isBindingTarget(level.getBlockState(event.getPos()))
                ? BindingSlot.CRUISE_MISSILE_LAUNCHER
                : StabilizerBindingHandler.detectBindingSlot(level,event.getPos());
        if (slot==BindingSlot.BEARING && level.getBlockState(event.getPos()).getBlock() instanceof SwivelBearingBlock block) {
            slot=block.getRotationAxis(level.getBlockState(event.getPos()))== Direction.Axis.Y?BindingSlot.YAW:BindingSlot.PITCH;
        }
        if (slot==null || !display.bind(slot,clickedRef)) {
            player.displayClientMessage(Component.translatable("message.create_fire_control.binding.invalid_auto"),true); return;
        }
        player.displayClientMessage(Component.translatable("message.create_fire_control.binding.success",slot.displayName()),true);
        sendCandidates(player,display);
        PENDING.put(player.getUUID(),new PendingBinding(pending.source(),level.getGameTime()+BINDING_TIMEOUT_TICKS,pending.lease()));
    }
    @SubscribeEvent public static void tick(PlayerTickEvent.Post event){if(event.getEntity() instanceof ServerPlayer p){PendingBinding pending=PENDING.get(p.getUUID());if(pending!=null
            &&(pending.expiresAt()<p.level().getGameTime()||!pending.source().isInDimension(p.level())
            ||!AllItems.WRENCH.isIn(p.getMainHandItem())&&!AllItems.WRENCH.isIn(p.getOffhandItem()))){cancel(p);return;}
            if(pending!=null&&p.tickCount%40==0){if(pending.source().resolve(p.level()) instanceof ConnectedDisplayBlockEntity display)sendCandidates(p,display);else cancel(p);}}}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){var pending=PENDING.remove(event.getEntity().getUUID());if(pending!=null)pending.lease().close();}
    static void cancel(ServerPlayer p){var pending=PENDING.remove(p.getUUID());if(pending==null)return;pending.lease().close();PacketDistributor.sendToPlayer(p,BindingSessionPayload.inactive());}
    @SubscribeEvent public static void stop(net.neoforged.neoforge.event.server.ServerStoppingEvent event){PENDING.values().forEach(pending->pending.lease().close());PENDING.clear();}
    private static void consume(PlayerInteractEvent.RightClickBlock e){e.setCanceled(true);e.setCancellationResult(InteractionResult.SUCCESS);}

    private static void sendCandidates(ServerPlayer player,ConnectedDisplayBlockEntity display) {
        BindingSessionPayload.send(player,bindingPayload(display,Sable.HELPER.projectOutOfSubLevel(player.level(),player.position())));
    }
    private static BindingSessionPayload bindingPayload(ConnectedDisplayBlockEntity display,Vec3 observer) {
        Level level=display.getLevel();
        if(level==null)return new BindingSessionPayload(true,java.util.List.of());
        Set<Target> bound=new HashSet<>();
        display.getDevices().forEach(device->bound.add(new Target(device.ref().pos(),device.ref().subLevelId())));
        LinkedHashSet<BindingSessionPayload.Candidate> candidates=new LinkedHashSet<>();
        for(Target target:bound)candidates.add(new BindingSessionPayload.Candidate(target.pos(),target.subLevelId(),true));
        BindingCandidateCollector.collect(level,display.getBlockPos(),observer,(pos,body)->{
            if(level.getBlockEntity(pos) instanceof ConnectedDisplayConsoleBlockEntity console) {
                candidates.add(new BindingSessionPayload.Candidate(pos,body,console.resolveDisplay()==display));return;
            }
            BindingSlot slot=MianbaoBallisticMissileCompat.isBindingTarget(level.getBlockState(pos))?BindingSlot.BALLISTIC_MISSILE_LAUNCHER
                    :MianbaoCruiseMissileCompat.isBindingTarget(level.getBlockState(pos))?BindingSlot.CRUISE_MISSILE_LAUNCHER
                    :StabilizerBindingHandler.detectBindingSlot(level,pos);
            if(supported(slot))candidates.add(new BindingSessionPayload.Candidate(pos,body,bound.contains(new Target(pos,body))));
        });
        return new BindingSessionPayload(true,java.util.List.copyOf(candidates));
    }
    private static boolean supported(BindingSlot slot) {
        return slot == BindingSlot.BEARING || slot == BindingSlot.RADAR_BEARING
                || slot == BindingSlot.CANNON_MOUNT || slot == BindingSlot.AA_MISSILE_LAUNCHER
                || slot == BindingSlot.CRUISE_MISSILE_LAUNCHER
                || slot == BindingSlot.BALLISTIC_MISSILE_LAUNCHER
                || slot == BindingSlot.FORWARD_INDICATOR
                || slot == BindingSlot.IFF_TRANSPONDER;
    }

    private record Target(BlockPos pos, UUID subLevelId) { Target { pos=pos.immutable(); } }
    private record PendingBinding(BindingRef source, long expiresAt,BindingSourceLease lease) {}
    private AutoDefenseDisplayBindingHandler(){}
}
