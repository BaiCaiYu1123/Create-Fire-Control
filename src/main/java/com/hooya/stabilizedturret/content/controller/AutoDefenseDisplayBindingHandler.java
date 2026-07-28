package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlock;
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
    private static final Map<UUID, BindingRef> PENDING = new HashMap<>();

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void rightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof ServerPlayer player)
                || !AllItems.WRENCH.isIn(event.getItemStack())) return;
        Level level=event.getLevel(); BindingRef pending=PENDING.get(player.getUUID());
        ConnectedDisplayBlockEntity clickedDisplay=ConnectedDisplayBlock.findController(level,event.getPos());
        if (clickedDisplay!=null) {
            BindingRef ref=BindingRef.capture(level,clickedDisplay.getBlockPos());
            if (ref.equals(pending)) cancel(player); else {
                PENDING.put(player.getUUID(),ref);
                PacketDistributor.sendToPlayer(player,bindingPayload(clickedDisplay));
                player.displayClientMessage(Component.translatable("message.stabilized_turret.auto_defense.select_module"),true);
            }
            consume(event); return;
        }
        if (pending==null) return;
        if (!(pending.resolve(level) instanceof ConnectedDisplayBlockEntity display)) { cancel(player); return; }
        BindingRef clickedRef = BindingRef.capture(level, event.getPos());
        // A second wrench click on an already bound mount is its normal CBC
        // assembly interaction, not another binding request. End the binding
        // session and let the original block receive that click.
        if (display.isBound(clickedRef)) {
            cancel(player);
            return;
        }
        consume(event);
        BindingSlot slot=StabilizerBindingHandler.detectBindingSlot(level,event.getPos());
        if (slot==BindingSlot.BEARING && level.getBlockState(event.getPos()).getBlock() instanceof SwivelBearingBlock block) {
            slot=block.getRotationAxis(level.getBlockState(event.getPos()))== Direction.Axis.Y?BindingSlot.YAW:BindingSlot.PITCH;
        }
        if (slot==null || !display.bind(slot,clickedRef)) {
            player.displayClientMessage(Component.translatable("message.stabilized_turret.binding.invalid_auto"),true); return;
        }
        player.displayClientMessage(Component.translatable("message.stabilized_turret.binding.success",slot.displayName()),true);
        PacketDistributor.sendToPlayer(player,bindingPayload(display));
    }
    @SubscribeEvent public static void tick(PlayerTickEvent.Post event){if(event.getEntity() instanceof ServerPlayer p&&PENDING.containsKey(p.getUUID())
            &&!AllItems.WRENCH.isIn(p.getMainHandItem())&&!AllItems.WRENCH.isIn(p.getOffhandItem()))cancel(p);}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){PENDING.remove(event.getEntity().getUUID());}
    private static void cancel(ServerPlayer p){PENDING.remove(p.getUUID());PacketDistributor.sendToPlayer(p,BindingSessionPayload.inactive());}
    private static void consume(PlayerInteractEvent.RightClickBlock e){e.setCanceled(true);e.setCancellationResult(InteractionResult.SUCCESS);}

    private static BindingSessionPayload bindingPayload(ConnectedDisplayBlockEntity display) {
        Level level = display.getLevel();
        if (level == null) return new BindingSessionPayload(true, java.util.List.of());
        Set<Target> bound = new HashSet<>();
        display.getDevices().forEach(device -> bound.add(new Target(
                device.ref().pos(), device.ref().subLevelId())));
        LinkedHashSet<BindingSessionPayload.Candidate> candidates = new LinkedHashSet<>();
        Vec3 center = Sable.HELPER.projectOutOfSubLevel(
                level, Vec3.atCenterOf(display.getBlockPos()));
        BlockPos rootCenter = BlockPos.containing(center);
        scan(level, rootCenter.offset(-24, -24, -24), rootCenter.offset(24, 24, 24),
                center, null, bound, candidates, 180000);
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        for (SubLevel sub : container.getAllSubLevels()) {
            if (sub == null || sub.isRemoved() || candidates.size() >= 1024) continue;
            var worldBounds = sub.boundingBox();
            double x = Math.max(worldBounds.minX(), Math.min(worldBounds.maxX(), center.x));
            double y = Math.max(worldBounds.minY(), Math.min(worldBounds.maxY(), center.y));
            double z = Math.max(worldBounds.minZ(), Math.min(worldBounds.maxZ(), center.z));
            if (center.distanceToSqr(x, y, z) > 96.0 * 96.0) continue;
            org.joml.Vector3d localMin = new org.joml.Vector3d(
                    Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
            org.joml.Vector3d localMax = new org.joml.Vector3d(
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
            for (int corner = 0; corner < 8; corner++) {
                org.joml.Vector3d point = new org.joml.Vector3d(
                        (corner & 1) == 0 ? worldBounds.minX() : worldBounds.maxX(),
                        (corner & 2) == 0 ? worldBounds.minY() : worldBounds.maxY(),
                        (corner & 4) == 0 ? worldBounds.minZ() : worldBounds.maxZ());
                sub.logicalPose().transformPositionInverse(point);
                localMin.min(point); localMax.max(point);
            }
            scan(sub.getLevel(),
                    BlockPos.containing(Math.floor(localMin.x)-1,Math.floor(localMin.y)-1,Math.floor(localMin.z)-1),
                    BlockPos.containing(Math.ceil(localMax.x)+1,Math.ceil(localMax.y)+1,Math.ceil(localMax.z)+1),
                    center, sub.getUniqueId(), bound, candidates, 300000);
        }
        return new BindingSessionPayload(true, java.util.List.copyOf(candidates));
    }

    private static void scan(Level level, BlockPos min, BlockPos max, Vec3 center,
                             UUID subLevelId, Set<Target> bound,
                             Set<BindingSessionPayload.Candidate> candidates, int maxChecks) {
        long sx=(long)max.getX()-min.getX()+1, sy=(long)max.getY()-min.getY()+1,
                sz=(long)max.getZ()-min.getZ()+1;
        if(sx<=0||sy<=0||sz<=0||sx*sy*sz>maxChecks)return;
        BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();
        for(int y=min.getY();y<=max.getY()&&candidates.size()<1024;y++)
            for(int z=min.getZ();z<=max.getZ()&&candidates.size()<1024;z++)
                for(int x=min.getX();x<=max.getX()&&candidates.size()<1024;x++){
                    cursor.set(x,y,z);
                    BindingSlot slot=StabilizerBindingHandler.detectBindingSlot(level,cursor);
                    if(!supported(slot))continue;
                    Vec3 world=Sable.HELPER.projectOutOfSubLevel(level,Vec3.atCenterOf(cursor));
                    if(world.distanceToSqr(center)>96.0*96.0)continue;
                    BlockPos pos=cursor.immutable();
                    candidates.add(new BindingSessionPayload.Candidate(pos,subLevelId,
                            bound.contains(new Target(pos,subLevelId))));
                }
    }

    private static boolean supported(BindingSlot slot) {
        return slot == BindingSlot.BEARING || slot == BindingSlot.RADAR_BEARING
                || slot == BindingSlot.CANNON_MOUNT || slot == BindingSlot.AA_MISSILE_LAUNCHER;
    }

    private record Target(BlockPos pos, UUID subLevelId) { Target { pos=pos.immutable(); } }
    private AutoDefenseDisplayBindingHandler(){}
}
