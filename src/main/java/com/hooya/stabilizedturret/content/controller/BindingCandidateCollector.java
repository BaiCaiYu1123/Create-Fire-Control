package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.compat.schematic.SableSchematicGroupCompat;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.BiConsumer;

/** Loaded sections only: no rotated bounding-volume cutoff and no distance from the source. */
final class BindingCandidateCollector {
    static void collect(Level level,BlockPos sourcePos,Vec3 observer,BiConsumer<BlockPos,UUID> visitor) {
        if(!(level instanceof ServerLevel server))return;
        int cx=BlockPos.containing(observer).getX()>>4,cz=BlockPos.containing(observer).getZ()>>4;
        for(int x=cx-4;x<=cx+4;x++)for(int z=cz-4;z<=cz+4;z++) {
            LevelChunk chunk=server.getChunkSource().getChunkNow(x,z);
            if(chunk!=null)scan(chunk,null,visitor);
        }
        SubLevel source=Sable.HELPER.getContaining(level,sourcePos);
        Set<UUID> related=source==null?Set.of():SableSchematicGroupCompat.connectedCameraGroup(server,source.getUniqueId());
        for(SubLevel sub:SubLevelContainer.getContainer(level).getAllSubLevels()) {
            if(sub.isRemoved())continue;
            if(sub!=source&&!related.contains(sub.getUniqueId())) {
                var b=sub.boundingBox();
                if(b.minX()>b.maxX()||b.minY()>b.maxY()||b.minZ()>b.maxZ())continue;
                double x=Math.clamp(observer.x,b.minX(),b.maxX()),y=Math.clamp(observer.y,b.minY(),b.maxY()),z=Math.clamp(observer.z,b.minZ(),b.maxZ());
                if(observer.distanceToSqr(x,y,z)>128*128)continue;
            }
            for(var holder:sub.getPlot().getLoadedChunks())scan(holder.getChunk(),sub.getUniqueId(),visitor);
        }
    }
    private static void scan(LevelChunk chunk,UUID body,BiConsumer<BlockPos,UUID> visitor) {
        for(BlockPos pos:chunk.getBlockEntitiesPos())visitor.accept(pos,body);
        var sections=chunk.getSections();var cursor=new BlockPos.MutableBlockPos();
        for(int s=0;s<sections.length;s++) {
            var section=sections[s];
            if(section==null||section.hasOnlyAir()||!section.maybeHas(BindingCandidateCollector::nonEntityCandidate))continue;
            int by=chunk.getSectionYFromSectionIndex(s)<<4,bx=chunk.getPos().getMinBlockX(),bz=chunk.getPos().getMinBlockZ();
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                BlockState state=section.getBlockState(x,y,z);
                if(nonEntityCandidate(state))visitor.accept(cursor.set(bx+x,by+y,bz+z).immutable(),body);
            }
        }
    }
    private static boolean nonEntityCandidate(BlockState state) {
        if(state.isAir()||state.hasBlockEntity())return false;
        if(state.getBlock() instanceof SeatBlock)return true;
        var id=BuiltInRegistries.BLOCK.getKey(state.getBlock());String ns=id.getNamespace();
        // Conservative palette filter; the caller applies the exact device classifier.
        return ns.equals("interiors")||ns.equals("create_flight_control")||ns.equals("createbigcannons")
                ||ns.equals("mianbaos_modernwarfare")||ns.equals("drivebywire");
    }
    private BindingCandidateCollector() {}
}
