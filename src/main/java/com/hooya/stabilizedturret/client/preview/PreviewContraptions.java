package com.hooya.stabilizedturret.client.preview;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import java.util.*;

/** Assembled Create/CBC machinery is an entity and is no longer present in plot block snapshots. */
final class PreviewContraptions implements AutoCloseable {
    record Entry(AbstractContraptionEntity entity,UUID body,boolean plotCoordinates,
                 List<PreviewModelBuffers.Layer> layers,boolean cannon) {
        Matrix4f model(Map<UUID,Matrix4f> bodies,float pt) {
            var mc=Minecraft.getInstance();var bodyPose=bodies.get(body);
            if(mc.level==null||bodyPose==null)return null;
            SubLevel sub=SubLevelContainer.getContainer(mc.level).getSubLevel(body);
            if(sub==null||sub.isRemoved()||entity.isRemoved())return null;
            Vec3 pos=new Vec3(Mth.lerp(pt,entity.xOld,entity.getX()),Mth.lerp(pt,entity.yOld,entity.getY()),Mth.lerp(pt,entity.zOld,entity.getZ()));
            if(!plotCoordinates)pos=sub.logicalPose().transformPositionInverse(pos);
            BlockPos origin=sub.getPlot().getCenterBlock();
            return new Matrix4f(bodyPose).translate((float)(pos.x-origin.getX()),(float)(pos.y-origin.getY()),(float)(pos.z-origin.getZ()));
        }
    }
    private final Map<UUID,Entry> entries=new LinkedHashMap<>();
    private final Set<String> failed=new HashSet<>();
    private record Candidate(AbstractContraptionEntity entity,UUID body,boolean plot) {}
    private List<Candidate> pending=List.of();
    private int ticks,cursor,nextScan;
    Collection<Entry> entries(){return entries.values();}
    void tick(Set<UUID> allowed) {
        ticks++;
        Minecraft mc=Minecraft.getInstance();if(mc.level==null)return;
        if(cursor>=pending.size()&&ticks>=nextScan)discover(mc,allowed);
        long deadline=System.nanoTime()+2_000_000;
        for(int steps=0;cursor<pending.size()&&steps<2;steps++) {
            Candidate candidate=pending.get(cursor++);
            if(allowed.contains(candidate.body)&&!candidate.entity.isRemoved())capture(mc,candidate);
            if(System.nanoTime()>=deadline)break;
        }
    }
    private void discover(Minecraft mc,Set<UUID> allowed) {
        Set<UUID> keep=new HashSet<>();List<Candidate> candidates=new ArrayList<>();
        for(var entity:mc.level.entitiesForRendering()) {
            if(!(entity instanceof AbstractContraptionEntity contraption)||entity.isRemoved()||contraption.getContraption()==null)continue;
            SubLevel containing=Sable.HELPER.getContaining(entity),sub=containing;
            boolean plot=sub!=null;
            if(sub==null)sub=Sable.HELPER.getTrackingOrVehicleSubLevel(entity);
            // CBC/Create can project the entity into world coordinates while keeping its anchor in the plot.
            if(sub==null&&contraption.getContraption().anchor!=null)
                sub=Sable.HELPER.getContaining(mc.level,contraption.getContraption().anchor);
            if(sub==null&&contraption instanceof rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity cannon
                    &&cannon.getController() instanceof net.minecraft.world.level.block.entity.BlockEntity controller)
                sub=Sable.HELPER.getContaining(controller);
            if(sub==null||!allowed.contains(sub.getUniqueId()))continue;
            keep.add(entity.getUUID());
            candidates.add(new Candidate(contraption,sub.getUniqueId(),plot));
        }
        pending=candidates;cursor=0;nextScan=ticks+10;
        var iterator=entries.entrySet().iterator();
        while(iterator.hasNext()){var entry=iterator.next();if(!keep.contains(entry.getKey())){release(entry.getValue().layers);iterator.remove();}}
    }
    private void capture(Minecraft mc,Candidate candidate) {
            var contraption=candidate.entity;
            if(contraption.getContraption()==null)return;
            List<PreviewModelBuffers.Layer> layers=null;
            var client=contraption.getContraption().getOrCreateClientContraptionLazy();
            BitSet visibility=(BitSet)client.shouldRenderBlockEntities.clone();
            try(PreviewExternalModels external=new PreviewExternalModels();PreviewModelBuffers buffers=new PreviewModelBuffers()) {
                client.shouldRenderBlockEntities.set(0,client.renderedBlockEntityView.size());
                for(int i=0;i<client.renderedBlockEntityView.size();i++)
                    if(LittleTilesPreviewBridge.handles(client.renderedBlockEntityView.get(i)))client.shouldRenderBlockEntities.clear(i);
                var renderer=mc.getEntityRenderDispatcher().getRenderer(contraption);
                renderer.render(contraption,contraption.getYRot(),1,new PoseStack(),buffers,LightTexture.FULL_BRIGHT);
                PoseStack tilesPose=new PoseStack();contraption.applyLocalTransforms(tilesPose,1);
                for(var be:client.renderedBlockEntityView)if(LittleTilesPreviewBridge.handles(be)) {
                    tilesPose.pushPose();tilesPose.translate(be.getBlockPos().getX(),be.getBlockPos().getY(),be.getBlockPos().getZ());
                    try{LittleTilesPreviewBridge.render(be,tilesPose,buffers);}finally{tilesPose.popPose();}
                }
                layers=buffers.upload();
            } catch(RuntimeException failure) {
                if(failed.add(contraption.getType().toString()))StabilizedTurret.LOGGER.warn("Cannot capture assembled preview entity {}",contraption.getType(),failure);
                return;
            } finally {client.shouldRenderBlockEntities.clear();client.shouldRenderBlockEntities.or(visibility);}
            boolean cannon=contraption.getContraption() instanceof rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
            Entry old=entries.put(contraption.getUUID(),new Entry(contraption,candidate.body,candidate.plot,layers,cannon));
            if(old!=null)release(old.layers);
    }
    private static void release(List<PreviewModelBuffers.Layer> layers){if(layers!=null)layers.forEach(PreviewModelBuffers.Layer::close);}
    @Override public void close(){entries.values().forEach(e->release(e.layers));entries.clear();pending=List.of();cursor=ticks=nextScan=0;failed.clear();}
}
