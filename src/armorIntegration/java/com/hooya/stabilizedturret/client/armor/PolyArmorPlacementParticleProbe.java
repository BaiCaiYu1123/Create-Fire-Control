package com.hooya.stabilizedturret.client.armor;

import com.caca.copypoly.*;
import com.caca.copypoly.geometry.Polyhedron;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.armor.*;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.*;
import net.minecraft.world.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.lang.reflect.*;
import java.util.*;

/** Repeats BlockItem placement and the exact Effortless particle entry point. Never shipped. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT)
public final class PolyArmorPlacementParticleProbe {
    private static final BlockPos P=new BlockPos(0,101,0);
    private static int stage,ticks,total,iteration,skipped,emitted;private static Field pending;
    private static Object effortlessEngine,effortlessUp;private static Method effortlessCrack;private static Constructor<?> effortlessPos;
    @SubscribeEvent public static void client(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("create_fire_control.armorParticleSmoke")||stage==999)return;
        var mc=Minecraft.getInstance();try{tick(mc);}catch(Throwable failure){LogUtils.getLogger().error("ARMOR_PARTICLE_CLIENT_FAIL iteration="+iteration,failure);stage=999;mc.stop();}
    }
    private static void tick(Minecraft mc)throws Exception{
        ticks++;if(++total>3500)throw new IllegalStateException("Particle fixture timed out at "+stage);
        if(stage==0&&(mc.screen instanceof TitleScreen||mc.screen instanceof AccessibilityOnboardingScreen)&&mc.getOverlay()==null){
            mc.options.renderDistance().set(2);mc.options.simulationDistance().set(5);mc.options.pauseOnLostFocus=false;
            var settings=new LevelSettings("Armor placement particles",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("armor-particles-"+System.currentTimeMillis(),settings,new WorldOptions(1234,false,false),
                    r->r.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),mc.screen);next();return;
        }
        if(stage==1&&ticks>80&&mc.level!=null&&mc.player!=null&&mc.screen==null){
            mc.getSingleplayerServer().execute(()->{var level=mc.getSingleplayerServer().overworld();level.setBlockAndUpdate(P.below(),Blocks.STONE.defaultBlockState());var player=mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();player.getAbilities().flying=true;player.onUpdateAbilities();player.connection.teleport(3,103,-4,0,0);});
            pending=net.minecraft.client.particle.ParticleEngine.class.getDeclaredField("particlesToAdd");pending.setAccessible(true);
            if(ModList.get().isLoaded("effortless")){
                var engine=Class.forName("dev.huskuraft.effortless.neoforge.sound.MinecraftParticleEngine");var position=Class.forName("dev.huskuraft.effortless.api.core.BlockPosition");var direction=Class.forName("dev.huskuraft.effortless.api.core.Direction");
                effortlessEngine=engine.getConstructor(net.minecraft.client.particle.ParticleEngine.class).newInstance(mc.particleEngine);effortlessPos=position.getConstructor(int.class,int.class,int.class);effortlessUp=direction.getField("UP").get(null);effortlessCrack=engine.getMethod("crack",position,direction);
            }
            next();return;
        }
        if(stage==2&&ticks>40&&mc.level.getBlockState(P.below()).is(Blocks.STONE)){
            if(iteration<144){placeAndCrack(mc,iteration++%6);return;}
            // Ordinary blocks must still emit their native particle.
            mc.level.setBlockAndUpdate(P,Blocks.STONE.defaultBlockState());int before=count(mc);crack(mc);check(count(mc)==before+1,"Non-armor particle was suppressed");next();return;
        }
        if(stage==3&&ticks>40){LogUtils.getLogger().info("ARMOR_PARTICLE_CLIENT_PASS: 144 BlockItem placements, empty/open/fully-perforated/sub-grid shapes, intact/damaged/missing-BE controls; skipped={} emitted={} effortless={}",skipped,emitted,effortlessEngine!=null);stage=999;mc.stop();}
    }
    private static void placeAndCrack(Minecraft mc,int mode)throws Exception{
        mc.level.setBlockAndUpdate(P,Blocks.AIR.defaultBlockState());mc.player.setItemInHand(InteractionHand.MAIN_HAND,Copypoly.ITEM.get().getDefaultInstance());
        var hit=new BlockHitResult(Vec3.atLowerCornerOf(P).add(.5,0,.5),Direction.UP,P.below(),false);
        var result=mc.player.getMainHandItem().useOn(new UseOnContext(mc.player,InteractionHand.MAIN_HAND,hit));
        check(result.consumesAction()&&mc.level.getBlockState(P).is(Copypoly.POLY.get()),"Actual BlockItem placement failed");
        var be=(PolyBlockEntity)mc.level.getBlockEntity(P);check(be!=null,"Placement did not create the armor BE");var damage=((PolyArmorAccess)be).fireControl$armorDamage();
        if(mode==1)be.setPoly(Polyhedron.surface(Polyhedron.CUBE_POINTS,List.of()));
        if(mode==2)damage.add(ArmorHole.along(new Vec3(.5,.5,.5),new Vec3(0,0,1),2));
        if(mode==3)damage.install(EditableArmorMesh.from(ArmorMesh.of(Polyhedron.CUBE)).transform(p->new Vec3(.5+(p.x-.5)/2048,p.y,p.z),false));
        if(mode==4)damage.add(ArmorHole.along(new Vec3(.5,.5,0),new Vec3(0,0,1)));
        if(mode==5)mc.level.removeBlockEntity(P);
        boolean empty=mc.level.getBlockState(P).getShape(mc.level,P).isEmpty();check(empty==(mode>=1&&mode<=3),"Wrong fixture shape for mode "+mode);
        int before=count(mc);crack(mc);int added=count(mc)-before;
        check(added==(empty?0:1),"Particle result mismatch for shape mode "+mode+": "+added);
        if(empty)skipped++;else emitted++;
        if(mode==3)check(damage.volume(be.poly())>0,"Particle handling erased the thin edited solid");
    }
    private static void crack(Minecraft mc)throws Exception{
        if(effortlessEngine!=null)effortlessCrack.invoke(effortlessEngine,effortlessPos.newInstance(P.getX(),P.getY(),P.getZ()),effortlessUp);else mc.particleEngine.crack(P,Direction.UP);
    }
    private static int count(Minecraft mc)throws Exception{return ((Collection<?>)pending.get(mc.particleEngine)).size();}
    private static void next(){stage++;ticks=0;}
    private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
