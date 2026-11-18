package com.hooya.stabilizedturret.client.armor;

import com.caca.copypoly.*;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.cannon.munition.StabilizedApfsdsProjectile;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.*;

/** Real network regression for fast impacts, including impacts in the spawn tick. Never shipped. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT)
public final class ApfsdsStopClientProbe {
    private record Sample(int id,Vec3 position,boolean grounded,boolean attached,int age){}
    private static final List<StabilizedApfsdsProjectile> SHOTS=new ArrayList<>();
    private static volatile List<Sample> samples=List.of();
    private static int stage,ticks,total;
    @SubscribeEvent public static void server(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        if(!Boolean.getBoolean("create_fire_control.apfsdsStopSmoke"))return;
        samples=SHOTS.stream().map(p->new Sample(p.getId(),world(p),p.isInGround(),Sable.HELPER.getContaining(p)!=null,p.tickCount)).toList();
    }
    @SubscribeEvent public static void client(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("create_fire_control.apfsdsStopSmoke")||stage==999)return;
        var mc=Minecraft.getInstance();try{tick(mc);}catch(Throwable failure){stage=999;LogUtils.getLogger().error("APFSDS_STOP_CLIENT_FAIL",failure);mc.stop();}
    }
    private static void tick(Minecraft mc){
        ticks++;if(++total>3000)throw new IllegalStateException("Stop probe timed out at "+stage);
        if(stage==0&&(mc.screen instanceof TitleScreen||mc.screen instanceof AccessibilityOnboardingScreen)&&mc.getOverlay()==null){
            mc.options.renderDistance().set(2);mc.options.simulationDistance().set(5);mc.options.pauseOnLostFocus=false;
            var settings=new LevelSettings("APFSDS stop regression",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("apfsds-stop-"+System.currentTimeMillis(),settings,new WorldOptions(1234,false,false),
                    r->r.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),mc.screen);next();return;
        }
        if(stage==1&&ticks>80&&mc.level!=null&&mc.player!=null&&mc.screen==null){
            mc.getSingleplayerServer().execute(()->{
                var level=mc.getSingleplayerServer().overworld();
                for(int x=-2;x<=14;x++)for(int z=-4;z<=24;z++)for(int y=100;y<=104;y++)
                    level.setBlockAndUpdate(new BlockPos(x,y,z),(y==100||z==20?Blocks.BEDROCK:Blocks.AIR).defaultBlockState());
                for(int x:new int[]{3,9}){var p=new BlockPos(x,102,0);level.setBlockAndUpdate(p,Copypoly.POLY.get().defaultBlockState());((PolyBlockEntity)level.getBlockEntity(p)).setMaterial(Blocks.SANDSTONE.defaultBlockState());}
                var movingWall=new BlockPos(12,102,20);
                SubLevelAssemblyHelper.assembleBlocks(level,movingWall,List.of(movingWall,movingWall.below()),new BoundingBox3i(movingWall.below(),movingWall));
                var player=mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();player.getAbilities().flying=true;player.onUpdateAbilities();player.connection.teleport(6,103,-5,0,0);
                for(int x:new int[]{0,3,6,9,12})spawn(level,x,Vec3.ZERO);
            });next();return;
        }
        if(stage==2&&ticks>50&&samples.size()==5&&samples.stream().allMatch(s->mc.level.getEntity(s.id())!=null)){
            mc.getSingleplayerServer().execute(()->{
                for(int i=0;i<SHOTS.size();i++){var p=SHOTS.get(i);p.setDeltaMovement(0,i==2||i==3?-3:0,24);p.hasImpulse=true;}
                // These shots impact before the client has ticked their new entities twice.
                for(int x:new int[]{1,4,7,10})spawn(mc.getSingleplayerServer().overworld(),x,new Vec3(0,x>=7?-3:0,24));
            });next();return;
        }
        if(stage==3&&ticks>45&&samples.size()==9&&samples.stream().allMatch(s->s.age()>30)){
            for(var s:samples){
                check(s.grounded(),"Server did not stop fixture "+s);
                var p=mc.level.getEntity(s.id());check(p instanceof StabilizedApfsdsProjectile,"Client never received stopped projectile "+s);
                var dart=(StabilizedApfsdsProjectile)p;
                var clientPosition=world(dart);boolean attached=Sable.HELPER.getContaining(dart)!=null;
                LogUtils.getLogger().info("APFSDS_STOP_SAMPLE id={} server={} client={} grounded={}/{} attached={}/{} error={}",s.id(),s.position(),clientPosition,s.grounded(),dart.isInGround(),s.attached(),attached,s.position().distanceTo(clientPosition));
                check(dart.isInGround()&&s.position().distanceTo(clientPosition)<.05&&s.attached()==attached,"Stopped projectile floated away from its real contact: server="+s+" client="+clientPosition+" ground="+dart.isInGround());
            }
            var sableHit=samples.get(4).position();
            check(mc.level.getBlockState(new BlockPos(12,102,20)).isAir()&&sableHit.z>19.5&&sableHit.z<20.1,"Fixture did not hit the assembled Sable wall");
            LogUtils.getLogger().info("APFSDS_STOP_CLIENT_PASS: tracked/spawn-tick wall and floor impacts; real Sable wall impacts agree on both sides");stage=999;mc.stop();
        }
    }
    private static void spawn(net.minecraft.server.level.ServerLevel level,int x,Vec3 velocity){
        var p=ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get().create(level);p.setPos(x+.5,102.5,-2);p.setNoGravity(true);p.setDeltaMovement(velocity);level.addFreshEntity(p);SHOTS.add(p);
    }
    private static void next(){stage++;ticks=0;}
    private static Vec3 world(net.minecraft.world.entity.Entity entity){var body=Sable.HELPER.getContaining(entity);return body==null?entity.position():body.logicalPose().transformPosition(entity.position());}
    private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
