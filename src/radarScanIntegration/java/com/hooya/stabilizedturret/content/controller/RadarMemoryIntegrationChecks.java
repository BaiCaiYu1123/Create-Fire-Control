package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.config.FireControlSettings;
import com.hooya.stabilizedturret.network.RadarSessionPayload;
import com.hooya.stabilizedturret.registry.ModBlocks;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Real production scans and payloads; ghosts must never become guidance/RWR contacts. */
public final class RadarMemoryIntegrationChecks {
    public static void check(ServerLevel level) throws Exception {
        long before = level.getGameTime(); boolean raycast = FireControlSettings.RADAR_RAYCAST.get();
        try {
            FireControlSettings.RADAR_RAYCAST.set(true);
            for (boolean aircraft : new boolean[]{true,false}) check(level, aircraft);
            if (Boolean.getBoolean("create_fire_control.radarMemoryMotionChecks")) {
                RadarMotionMatrixChecks.check(level);
                RadarLateralMotionIntegrationChecks.check(level);
                RadarLongRangeIntegrationChecks.check(level);
                RadarStabilityIntegrationChecks.check(level);
            }
        } finally { FireControlSettings.RADAR_RAYCAST.set(raycast); time(level,before); }
    }
    private static void check(ServerLevel level, boolean aircraft) throws Exception {
        Vec3 origin = new Vec3(320.5,200.75,aircraft?320.5:640.5), point=origin.add(48,0,0);
        for(int x=20;x<=24;x++) level.getChunk(x,BlockPos.containing(origin).getZ()>>4);
        var body=RadarVisibilityIntegrationChecks.body(level,point); UUID id=body.getUniqueId();
        var radar=new Computer(origin,aircraft,Set.of()); radar.setLevel(level);
        var receiver=new Computer(point,true,Set.of(id)); receiver.setLevel(level);
        var scan=StabilizerControllerBlockEntity.class.getDeclaredMethod("updateRadarScan"); scan.setAccessible(true);
        var lock=StabilizerControllerBlockEntity.class.getDeclaredMethod("setRadarLockedTarget",UUID.class); lock.setAccessible(true);
        BlockPos wall=BlockPos.containing(origin.add(8,0,0));
        ControllerTracker.add(radar);
        try {
            level.setBlockAndUpdate(wall,Blocks.AIR.defaultBlockState());
            next(level); scan.invoke(radar); lock.invoke(radar,id); long seen=level.getGameTime();
            require(radar.getRadarContact(id)!=null && warned(receiver),"confirmed radar contact illuminates RWR");
            level.setBlockAndUpdate(wall,Blocks.STONE.defaultBlockState()); next(level); scan.invoke(radar);
            require(radar.getRadarContacts().isEmpty() && radar.getRadarLockedSubLevelId()==null
                    && radar.getRadarLockedSubLevel()==null && radar.getAircraftGuidanceTargetId()==null,"occlusion removes every real target entry immediately after detection");
            require(id.equals(RadarLockMemory.displayLock(radar)),"display lock intent survives a temporary wall");
            var payload=RadarSessionPayload.from(radar);
            require(id.equals(payload.lockedTarget()) && payload.contacts().isEmpty(),"payload carries display identity without leaking hidden positions");
            require(!warned(receiver),"remembered frame is not RWR illumination");
            if(aircraft) require(id.equals(radar.getAircraftSelectedTargetId()),"selected aircraft identity survives temporary occlusion");
            next(level); scan.invoke(radar); require(id.equals(RadarLockMemory.displayLock(radar)),"repeated blocked scans do not erase intent early");
            level.setBlockAndUpdate(wall,Blocks.AIR.defaultBlockState()); next(level); scan.invoke(radar);
            require(id.equals(radar.getRadarLockedSubLevelId()) && warned(receiver),"real reacquisition restores same lock and RWR");
            seen=level.getGameTime(); level.setBlockAndUpdate(wall,Blocks.STONE.defaultBlockState()); next(level); scan.invoke(radar);
            int hold=aircraft?60:40;
            time(level,seen+hold-2); scan.invoke(radar);
            require(id.equals(RadarLockMemory.displayLock(radar)) && !warned(receiver),"memory lasts up to exact deadline without illumination");
            time(level,seen+hold); scan.invoke(radar);
            require(RadarLockMemory.displayLock(radar)==null,"memory expires at 3/2 seconds from last real detection");
            level.setBlockAndUpdate(wall,Blocks.AIR.defaultBlockState()); next(level); scan.invoke(radar);
            require(radar.getRadarContact(id)!=null && radar.getRadarLockedSubLevelId()==null,"expired intent does not silently relock");
            lock.invoke(radar,id); level.setBlockAndUpdate(wall,Blocks.STONE.defaultBlockState()); next(level); scan.invoke(radar);
            lock.invoke(radar,new Object[]{null}); level.setBlockAndUpdate(wall,Blocks.AIR.defaultBlockState()); next(level); scan.invoke(radar);
            require(radar.getRadarLockedSubLevelId()==null,"manual cancel prevents automatic restoration");
            lock.invoke(radar,id); radar.active=false; next(level); scan.invoke(radar);
            require(RadarLockMemory.displayLock(radar)==null && radar.getRadarContacts().isEmpty(),"radar off clears memory immediately");
            StabilizedTurret.LOGGER.info("RADAR MEMORY PASS: aircraft={} hold={} ticks, same-target recovery, exact expiry, manual cancel, power off, zero ghost contacts/positions/guidance/RWR",aircraft,hold);
        } finally {
            level.setBlockAndUpdate(wall,Blocks.AIR.defaultBlockState()); ControllerTracker.remove(radar); RadarLockMemory.clear(radar);
            SubLevelContainer.getContainer(level).removeSubLevel(body,SubLevelRemovalReason.REMOVED);
        }
    }
    private static boolean warned(Computer receiver){return RwrThreatCollector.collect(receiver).stream().anyMatch(t->t.type()==RwrThreat.Type.AAA||t.type()==RwrThreat.Type.RFL);}
    private static void time(ServerLevel level,long time){level.getServer().getWorldData().overworldData().setGameTime(time);}
    private static void next(ServerLevel level){time(level,level.getGameTime()+6);}
    private static void require(boolean b,String message){if(!b)throw new AssertionError(message);}
    private static final class Computer extends StabilizerControllerBlockEntity {
        final Vec3 origin; final boolean aircraft; final Set<UUID> own; boolean active=true;
        Computer(Vec3 origin,boolean aircraft,Set<UUID> own){super(BlockPos.containing(origin),ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());this.origin=origin;this.aircraft=aircraft;this.own=own;}
        @Override public boolean isRadarActive(){return active;}
        @Override public boolean isAircraftRadarMode(){return aircraft;}
        @Override public boolean isAircraftRadarActive(){return aircraft;}
        @Override public Vec3 getRadarOrigin(){return origin;}
        @Override public Vec3 getAircraftRadarForward(){return new Vec3(1,0,0);}
        @Override public Set<UUID> getOwnControlledSubLevelIds(){return own;}
    }
}
