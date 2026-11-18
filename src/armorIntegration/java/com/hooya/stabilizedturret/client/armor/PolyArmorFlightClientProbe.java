package com.hooya.stabilizedturret.client.armor;

import com.caca.copypoly.*;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.armor.PolyArmorAccess;
import com.hooya.stabilizedturret.content.cannon.munition.StabilizedApfsdsProjectile;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import com.mojang.logging.LogUtils;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** A client may predict impacts before the server's carved mesh arrives. Never shipped. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT)
public final class PolyArmorFlightClientProbe {
    private static final BlockPos P=new BlockPos(0,101,0),SECOND=P.south(3),REAR=P.south(8);
    private static int stage,ticks,total;private static volatile int id=-1,age,idleTicks;private static volatile boolean launched,serverPassed,rearHit;private static volatile String serverState="unseen";
    private static volatile StabilizedApfsdsProjectile flight;private static boolean clientPassed;
    @SubscribeEvent public static void server(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        var shot=flight;if(shot==null||!Boolean.getBoolean("create_fire_control.armorFlightSmoke"))return;idleTicks=shot.tickCount;if(!launched)return;
        serverState="pos="+shot.position()+" velocity="+shot.getDeltaMovement()+" grounded="+shot.isInGround()+" ticks="+shot.tickCount+" removed="+shot.isRemoved()+" mass="+shot.getProjectileMass();
        serverPassed|=shot.getZ()>SECOND.getZ()+1&&!shot.isInGround();rearHit|=shot.level().getBlockState(REAR).isAir();
        if(++age>14){shot.discard();flight=null;}
    }
    @SubscribeEvent public static void client(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("create_fire_control.armorFlightSmoke")||stage==999)return;var mc=Minecraft.getInstance();
        try{tick(mc);}catch(Throwable failure){LogUtils.getLogger().error("ARMOR_FLIGHT_CLIENT_FAIL stage="+stage,failure);stage=999;mc.stop();}
    }
    private static void tick(Minecraft mc){
        ticks++;if(++total>4000)throw new IllegalStateException("Flight probe timeout at "+stage);
        if(stage==0&&(mc.screen instanceof TitleScreen||mc.screen instanceof AccessibilityOnboardingScreen)&&mc.getOverlay()==null){
            mc.options.renderDistance().set(2);mc.options.simulationDistance().set(5);mc.options.pauseOnLostFocus=false;
            var settings=new LevelSettings("Armor flight regression",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT);
            // Flat terrain keeps unrelated noise-generation futures out of fixture shutdown.
            mc.createWorldOpenFlows().createFreshLevel("armor-flight-"+System.currentTimeMillis(),settings,new WorldOptions(1234,false,false),
                    registries->registries.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),mc.screen);next();return;
        }
        if(stage==1&&ticks>80&&mc.level!=null&&mc.player!=null&&mc.screen==null){
            mc.getSingleplayerServer().execute(()->{var level=mc.getSingleplayerServer().overworld();
                for(int x=-2;x<=2;x++)for(int z=-5;z<=40;z++)for(int y=100;y<=103;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
                for(var pos:java.util.List.of(P,SECOND)){level.setBlockAndUpdate(pos,Copypoly.POLY.get().defaultBlockState());((PolyBlockEntity)level.getBlockEntity(pos)).setMaterial(Blocks.SANDSTONE.defaultBlockState());}
                level.setBlockAndUpdate(REAR,Blocks.GLASS.defaultBlockState());level.setBlockAndUpdate(P.south(20),Blocks.BEDROCK.defaultBlockState());
                var player=mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();player.getAbilities().flying=true;player.onUpdateAbilities();player.connection.teleport(.5,101.5-player.getEyeHeight(),-4,0,0);
                var shot=ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get().create(level);shot.setPos(.5,101.5,-2);shot.setNoGravity(true);level.addFreshEntity(shot);id=shot.getId();flight=shot;
            });next();return;
        }
        if(stage==2&&ticks>40&&idleTicks>=20&&mc.level.getBlockEntity(P) instanceof PolyArmorAccess&&mc.level.getBlockEntity(SECOND) instanceof PolyArmorAccess&&mc.level.getEntity(id) instanceof StabilizedApfsdsProjectile shot){
            check(!((PolyArmorAccess)mc.level.getBlockEntity(P)).fireControl$armorDamage().damaged(),"Fixture received carved mesh before prediction");
            // Deliberately predict ahead of the server to reproduce delayed block-entity updates.
            shot.setPos(.5,101.5,-2);shot.setDeltaMovement(0,0,4);shot.tick();shot.tick();
            check(!shot.isInGround()&&shot.getZ()>SECOND.getZ()+1,"Predicted penetration embedded client projectile: pos="+shot.position()+" grounded="+shot.isInGround()+" mass="+shot.getProjectileMass());
            mc.getSingleplayerServer().execute(()->{flight.setDeltaMovement(0,0,4);flight.hasImpulse=true;launched=true;});next();return;
        }
        if(stage==3){
            if(mc.level.getEntity(id) instanceof StabilizedApfsdsProjectile shot){
                if(shot.getZ()>SECOND.getZ()+1&&shot.getZ()<19&&!shot.isInGround())clientPassed=true;
                check(!(shot.isInGround()&&shot.getZ()<SECOND.getZ()+1),"Client left a frozen dart at the armor after server penetration");
            }
            if(age>=12){check(serverPassed&&rearHit&&clientPassed,"Flight evidence incomplete: server="+serverPassed+" rear="+rearHit+" client="+clientPassed+" "+serverState);mc.getSingleplayerServer().execute(()->{if(flight!=null)flight.discard();});next();return;}
        }
        if(stage==4&&ticks>100){LogUtils.getLogger().info("ARMOR_FLIGHT_CLIENT_PASS: delayed-mesh client prediction, two armor layers, continued server/client flight and rear target hit");stage=999;mc.stop();}
    }
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private static void next(){stage++;ticks=0;}
}
