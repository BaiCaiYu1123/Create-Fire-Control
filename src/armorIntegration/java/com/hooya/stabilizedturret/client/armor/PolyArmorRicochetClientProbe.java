package com.hooya.stabilizedturret.client.armor;

import com.caca.copypoly.*;
import com.caca.copypoly.geometry.Polyhedron;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.armor.*;
import com.hooya.stabilizedturret.content.cannon.munition.StabilizedApfsdsProjectile;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
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
import rbasamoyai.createbigcannons.config.CBCConfigs;
import java.util.*;

/** Predicts and tracks real ricochets on an original and a baked edited ramp. Never shipped. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT)
public final class PolyArmorRicochetClientProbe {
    private static final Vec3 NORMAL=new Vec3(0,1,-1).normalize(),DIRECTION=new Vec3(0,1,1.1).normalize();
    private static final List<StabilizedApfsdsProjectile> SHOTS=new ArrayList<>();
    private static volatile List<Integer> ids=List.of();private static volatile boolean launched,serverPassed;private static volatile int age;
    private static final Set<Integer> CLIENT_PASSED=new HashSet<>();private static int stage,ticks,total;
    @SubscribeEvent public static void server(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        if(!Boolean.getBoolean("create_fire_control.armorRicochetSmoke")||!launched)return;
        if(++age==1)serverPassed=SHOTS.stream().allMatch(p->!p.isInGround()&&!p.isRemoved()&&p.getDeltaMovement().dot(NORMAL)>0);
        if(age==4)serverPassed&=SHOTS.stream().allMatch(p->!p.isInGround()&&!p.isRemoved()&&p.getDeltaMovement().dot(NORMAL)>0);
        if(age==9)SHOTS.forEach(StabilizedApfsdsProjectile::discard);
    }
    @SubscribeEvent public static void client(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("create_fire_control.armorRicochetSmoke")||stage==999)return;
        var mc=Minecraft.getInstance();try{tick(mc);}catch(Throwable failure){LogUtils.getLogger().error("ARMOR_RICOCHET_CLIENT_FAIL stage="+stage,failure);stage=999;mc.stop();}
    }
    private static void tick(Minecraft mc){
        ticks++;if(++total>3000)throw new IllegalStateException("Ricochet probe timed out at "+stage);
        if(stage==0&&(mc.screen instanceof TitleScreen||mc.screen instanceof AccessibilityOnboardingScreen)&&mc.getOverlay()==null){
            mc.options.renderDistance().set(2);mc.options.simulationDistance().set(5);mc.options.pauseOnLostFocus=false;
            var settings=new LevelSettings("Armor ricochet regression",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("armor-ricochet-"+System.currentTimeMillis(),settings,new WorldOptions(1234,false,false),
                    r->r.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),mc.screen);next();return;
        }
        if(stage==1&&ticks>80&&mc.level!=null&&mc.player!=null&&mc.screen==null){
            mc.getSingleplayerServer().execute(()->{
                var level=mc.getSingleplayerServer().overworld();CBCConfigs.server().munitions.projectilesCanBounce.set(true);CBCConfigs.server().munitions.baseProjectileBounceChance.set(1.0);
                var ramp=Polyhedron.of(Polyhedron.parse("0,0,0;16,0,0;0,0,16;16,0,16;0,16,16;16,16,16"));
                for(int x:new int[]{0,3}){
                    var pos=new BlockPos(x,101,0);level.setBlockAndUpdate(pos,Copypoly.POLY.get().defaultBlockState());var be=(PolyBlockEntity)level.getBlockEntity(pos);be.setPoly(ramp);be.setMaterial(Blocks.ANCIENT_DEBRIS.defaultBlockState());
                    if(x==3){var access=(PolyArmorAccess)be;var damage=access.fireControl$armorDamage();damage.add(ArmorHole.along(new Vec3(.15,0,.85),new Vec3(0,1,0)));damage.install(damage.editable(ramp));access.fireControl$armorChanged();}
                    var p=ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get().create(level);p.setPos(Vec3.atLowerCornerOf(pos).add(.5,.5,.5).subtract(DIRECTION.scale(.3)));p.setNoGravity(true);p.setProjectileMass(900);level.addFreshEntity(p);SHOTS.add(p);
                }
                ids=SHOTS.stream().map(StabilizedApfsdsProjectile::getId).toList();var player=mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();player.getAbilities().flying=true;player.onUpdateAbilities();player.connection.teleport(1.5,103,-4,0,0);
            });next();return;
        }
        if(stage==2&&ticks>50&&ids.size()==2&&ids.stream().allMatch(id->mc.level.getEntity(id) instanceof StabilizedApfsdsProjectile)&&mc.level.getBlockEntity(new BlockPos(3,101,0)) instanceof PolyArmorAccess armor&&armor.fireControl$armorDamage().damaged()){
            for(int id:ids){
                var p=(StabilizedApfsdsProjectile)mc.level.getEntity(id);p.setDeltaMovement(DIRECTION.scale(8));
                var velocity=p.getDeltaMovement().add(((com.hooya.stabilizedturret.mixin.PolyArmorProjectileAccessor)p).fireControl$forces(p.position(),p.getDeltaMovement()));var expected=velocity.subtract(NORMAL.scale(NORMAL.dot(velocity)*1.7F));p.tick();
                var saved=new CompoundTag();p.saveWithoutId(saved);var next=saved.getList("NextMotion",Tag.TAG_DOUBLE);var actual=new Vec3(next.getDouble(0),next.getDouble(1),next.getDouble(2));
                check(next.size()==3&&actual.distanceTo(expected)<1e-6&&!p.isInGround(),"Client prediction did not reflect off the true slope: "+actual+" expected="+expected);
                check(p.getDeltaMovement().distanceTo(expected)<1e-6,"Client kept the incoming velocity for another tick");
            }
            mc.getSingleplayerServer().execute(()->{SHOTS.forEach(p->{p.setDeltaMovement(DIRECTION.scale(8));p.hasImpulse=true;});launched=true;});next();return;
        }
        if(stage==3){
            for(int id:ids)if(mc.level.getEntity(id) instanceof StabilizedApfsdsProjectile p&&!p.isInGround()&&!p.isRemoved()&&p.getDeltaMovement().dot(NORMAL)>0)CLIENT_PASSED.add(id);
            if(age>=7){check(serverPassed&&CLIENT_PASSED.size()==2,"Ricochet flight diverged: server="+serverPassed+" client="+CLIENT_PASSED);next();return;}
        }
        if(stage==4&&ticks>60){LogUtils.getLogger().info("ARMOR_RICOCHET_CLIENT_PASS: exact local prediction and continuing server/client ricochet on original and edited ramps");stage=999;mc.stop();}
    }
    private static void next(){stage++;ticks=0;}
    private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
