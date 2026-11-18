package com.hooya.stabilizedturret.client.armor;

import com.caca.copypoly.*;
import com.caca.copypoly.geometry.Polyhedron;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.armor.*;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import com.mojang.logging.LogUtils;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
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

/** Real client, real projectile, network synchronization and rendered-hole checks. Never shipped. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT)
public final class PolyArmorClientProbe {
    private static final BlockPos P=new BlockPos(0,101,0),Q=new BlockPos(2,101,0);
    private static int stage,ticks,total;
    private static double originalVolume;
    private static Vec3 editedControl;
    private static EditableArmorMesh savedMesh;
    private static volatile net.minecraft.world.entity.Entity flight;
    private static volatile int flightStamp;
    @SubscribeEvent public static void serverTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        var shot=flight;if(shot==null||!Boolean.getBoolean("create_fire_control.armorClientSmoke"))return;
        if(shot.tickCount>3||shot.level().getBlockEntity(P) instanceof PolyArmorAccess armor&&armor.fireControl$armorDamage().stamp()!=flightStamp){
            // Stop this fixture's dart immediately after the real impact instead of generating remote terrain.
            shot.discard();flight=null;
        }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("create_fire_control.armorClientSmoke")||stage==999)return;
        var mc=Minecraft.getInstance();try{run(mc);}catch(Throwable error){stage=999;LogUtils.getLogger().error("ARMOR_CLIENT_FAIL stage="+stage,error);mc.stop();}
    }
    private static void run(Minecraft mc){
        ticks++;if(++total>6000)throw new IllegalStateException("Armor client probe timeout at "+stage);
        if(stage==0&&(mc.screen instanceof TitleScreen||mc.screen instanceof AccessibilityOnboardingScreen)&&mc.getOverlay()==null){
            if(!mc.getLanguageManager().getSelected().equals("zh_cn")){mc.options.languageCode="zh_cn";mc.getLanguageManager().setSelected("zh_cn");mc.reloadResourcePacks();return;}
            mc.options.guiScale().set(2);mc.options.renderDistance().set(5);mc.options.simulationDistance().set(5);mc.options.pauseOnLostFocus=false;mc.resizeDisplay();
            var settings=new LevelSettings("Polyhedral armor integration",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("poly-armor-"+System.currentTimeMillis(),settings,new WorldOptions(1234,false,false),WorldPresets::createNormalWorldDimensions,mc.screen);next();return;
        }
        if(stage==1&&mc.level!=null&&mc.player!=null&&mc.screen==null){
            mc.getSingleplayerServer().execute(()->{
                var server=mc.getSingleplayerServer();var level=server.overworld();
                for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++)for(int y=99;y<=106;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(P,Copypoly.POLY.get().defaultBlockState());((PolyBlockEntity)level.getBlockEntity(P)).setMaterial(Blocks.IRON_BLOCK.defaultBlockState());
                level.setBlockAndUpdate(Q,Copypoly.POLY.get().defaultBlockState());((PolyBlockEntity)level.getBlockEntity(Q)).setMaterial(Blocks.IRON_BLOCK.defaultBlockState());
                var angled=(PolyArmorAccess)level.getBlockEntity(Q);angled.fireControl$armorDamage().add(ArmorHole.along(new Vec3(.5,.5,.5),new Vec3(.6,0,1)));angled.fireControl$armorChanged();
                var shot=ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get().create(level);shot.setPos(.5,101.5,-2);shot.setDeltaMovement(0,0,20);shot.setNoGravity(true);shot.setProjectileMass(200);flightStamp=((PolyArmorAccess)level.getBlockEntity(P)).fireControl$armorDamage().stamp();flight=shot;level.addFreshEntity(shot);
                level.setDayTime(6000);camera(mc,Vec3.atLowerCornerOf(P).add(.5,.5,-2.5),Vec3.atCenterOf(P));
            });next();return;
        }
        if(stage==2&&ticks>70&&mc.level.getBlockEntity(P) instanceof PolyArmorAccess armor&&armor.fireControl$armorDamage().damaged()){
            verifyMesh(mc,P);verifyMesh(mc,Q);next();return;
        }
        if(stage==3&&ticks>25){shot(mc,"armor-normal-real-projectile.png");
            mc.getSingleplayerServer().execute(()->camera(mc,Vec3.atCenterOf(Q).subtract(new Vec3(.6,0,1).normalize().scale(2.8)),Vec3.atCenterOf(Q)));next();return;}
        if(stage==4&&ticks>25){shot(mc,"armor-angled-real-hole.png");
            mc.getSingleplayerServer().execute(()->{var be=(PolyBlockEntity)mc.getSingleplayerServer().overworld().getBlockEntity(P);be.setPoly(Polyhedron.of(Polyhedron.CUBE_POINTS.stream().map(p->new Polyhedron.Point(p.x(),p.y(),p.z()/4)).toList()));camera(mc,Vec3.atLowerCornerOf(P).add(.5,.5,-2.5),Vec3.atCenterOf(P));});next();return;}
        if(stage==5&&ticks>25){verifyMesh(mc,P);shot(mc,"armor-hole-after-reshape.png");
            mc.getSingleplayerServer().execute(()->{var server=mc.getSingleplayerServer();Copypoly.POLY.get().openEditor(server.overworld(),P,server.getPlayerList().getPlayers().getFirst());});next();return;}
        if(stage==6&&ticks>25&&mc.screen instanceof ArmorMeshScreen screen&&screen.getMenu().snapshot()!=null){
            verifyMesh(mc,P);shot(mc,"armor-hole-world-editor.png");var mesh=screen.getMenu().snapshot();originalVolume=mesh.mesh().volume();
            check(mesh.vertices().size()==16,"One simple hole exposed extra tessellation controls: "+mesh.vertices().size());check(mesh.kinds().stream().filter(k->k==0).count()==8&&mesh.kinds().stream().filter(k->k==2).count()==8,"Shot lost original/generation vertex types");
            check(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(P).getBlock()).equals(Copypoly.ARMOR_ID),"Client still uses the old block ID");
            // Use the upper rim: the rear corner projects over the vertical rim in this camera view.
            var edge=mesh.edges().stream().filter(e->{var a=mesh.vertices().get(e.a());var b=mesh.vertices().get(e.b());return java.lang.Math.abs(a.y-.5625)<1e-7&&java.lang.Math.abs(b.y-.5625)<1e-7&&a.z<1e-7&&b.z<1e-7;})
                    .max(Comparator.comparingDouble(e->mesh.vertices().get(e.a()).distanceToSqr(mesh.vertices().get(e.b())))).orElseThrow();
            var center=mesh.vertices().get(edge.a()).lerp(mesh.vertices().get(edge.b()),.5);var projected=screen.view.project(center);check(projected!=null,"Hole edge is not visible in the editor");
            screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_2,0,0);screen.mouseClicked(projected.x,projected.y,0);screen.mouseReleased(projected.x,projected.y,0);
            var x=screen.children().stream().filter(net.minecraft.client.gui.components.EditBox.class::isInstance).map(net.minecraft.client.gui.components.EditBox.class::cast).skip(1).findFirst().orElseThrow();
            var yField=screen.children().stream().filter(net.minecraft.client.gui.components.EditBox.class::isInstance).map(net.minecraft.client.gui.components.EditBox.class::cast).skip(1).findFirst().orElseThrow();
            check(java.lang.Math.abs(Double.parseDouble(yField.getValue())-center.y*16)<1e-6,"Generated edge click selected a neighboring point instead of both endpoints");
            check(!x.getValue().isEmpty(),"Clicking a generated edge did not select its editable vertices");x.setValue(Double.toString(Double.parseDouble(x.getValue())+1));
            check(!button(screen,"screen.create_fire_control.armor.clear_holes").active,"Clearing would discard pending edits");
            double changed=previewVolume(mc);check(changed<originalVolume,"Coordinate field did not update the actual mesh preview");
            click(screen,"screen.create_fire_control.armor.undo");check(java.lang.Math.abs(previewVolume(mc)-originalVolume)<1e-8,"Undo did not restore generated hole geometry");
            click(screen,"screen.create_fire_control.armor.redo");check(java.lang.Math.abs(previewVolume(mc)-changed)<1e-8,"Redo did not restore the edited edge");
            Vec3 shifted=center.add(0,1.0/16,0),handle=screen.view.project(shifted.add(0,.4,0)),movedHandle=screen.view.project(shifted.add(0,.4+1.0/16,0));
            check(handle!=null&&movedHandle!=null,"Y handle not visible");screen.mouseClicked(handle.x,handle.y,0);screen.mouseDragged(movedHandle.x,movedHandle.y,0,movedHandle.x-handle.x,movedHandle.y-handle.y);
            var dragPreview=previewMesh(mc);
            screen.mouseDragged(movedHandle.x,movedHandle.y,0,0,0);
            check(previewMesh(mc)==dragPreview,"Duplicate drag rebuilt the same armor preview");
            screen.mouseReleased(movedHandle.x,movedHandle.y,0);
            check(previewVolume(mc)<changed-1e-6,"Dragging the generated edge's Y arrow did not move its vertices");
            click(screen,"screen.create_fire_control.armor.undo");check(java.lang.Math.abs(previewVolume(mc)-changed)<1e-8,"Undo did not restore a dragged hole edge");
            click(screen,"screen.create_fire_control.armor.split");check(java.lang.Math.abs(previewVolume(mc)-changed)<1e-8,"Edge subdivision changed the solid");check(previewVertices(mc)==17,"Edge subdivision did not add one control");click(screen,"screen.create_fire_control.armor.undo");check(previewVertices(mc)==16,"Undo did not restore the compact control mesh");
            editedControl=mesh.vertices().get(edge.a()).add(0,1.0/16,0);stage=60;ticks=0;return;
        }
        if(stage==60&&ticks>15&&mc.screen instanceof ArmorMeshScreen screen){screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_1,0,0);var point=screen.view.project(editedControl);check(point!=null,"Edited corner is not visible");screen.mouseClicked(point.x,point.y,0);screen.mouseReleased(point.x,point.y,0);stage=61;ticks=0;return;}
        if(stage==61&&ticks>15&&mc.screen instanceof ArmorMeshScreen screen){shot(mc,"armor-hole-edited-preview.png");click(screen,"screen.create_fire_control.armor.apply");stage=7;ticks=0;return;}
        if(stage==7&&ticks>20&&mc.screen==null){
            verifyMesh(mc,P);var damage=((PolyArmorAccess)mc.level.getBlockEntity(P)).fireControl$armorDamage();check(damage.holes().isEmpty(),"UI did not bake editable hole geometry");
            check(damage.volume(((PolyBlockEntity)mc.level.getBlockEntity(P)).poly())<originalVolume-1e-5,"UI edit did not widen the real opening");
            mc.getSingleplayerServer().execute(()->{var server=mc.getSingleplayerServer();Copypoly.POLY.get().openEditor(server.overworld(),P,server.getPlayerList().getPlayers().getFirst());});next();return;
        }
        if(stage==8&&ticks>20&&mc.screen instanceof ArmorMeshScreen screen&&screen.getMenu().snapshot()!=null){
            check(screen.getMenu().snapshot().vertices().stream().anyMatch(p->java.lang.Math.abs(p.y-.625)<1e-7),"Reopened editor lost the saved hole-edge coordinate");
            savedMesh=screen.getMenu().snapshot();
            shot(mc,"armor-hole-reopened-after-edit.png");screen.onClose();
            mc.getSingleplayerServer().execute(()->{var level=mc.getSingleplayerServer().overworld();var shot=ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get().create(level);shot.setPos(.2,101.2,-2);shot.setDeltaMovement(0,0,20);shot.setNoGravity(true);shot.setProjectileMass(200);flightStamp=((PolyArmorAccess)level.getBlockEntity(P)).fireControl$armorDamage().stamp();flight=shot;level.addFreshEntity(shot);});next();return;
        }
        if(stage==9&&ticks>30&&mc.level.getBlockEntity(P) instanceof PolyArmorAccess armor&&!armor.fireControl$armorDamage().holes().isEmpty()){
            verifyMesh(mc,P);armor.fireControl$armorDamage().editable(((PolyBlockEntity)mc.level.getBlockEntity(P)).poly()).validate();
            mc.getSingleplayerServer().execute(()->{
                var server=mc.getSingleplayerServer();var level=server.overworld();
                ((PolyArmorAccess)level.getBlockEntity(P)).fireControl$setArmorLevel(7);
                Copypoly.POLY.get().openEditor(level,P,server.getPlayerList().getPlayers().getFirst());
            });stage=90;ticks=0;return;
        }
        if(stage==90&&ticks>20&&mc.screen instanceof ArmorMeshScreen screen&&screen.getMenu().snapshot()!=null){
            var clear=button(screen,"screen.create_fire_control.armor.clear_holes");
            check(clear.active,"Clear button did not enable on recorded holes");
            shot(mc,"armor-clear-holes-button.png");
            check(screen.mouseClicked(clear.getX()+clear.getWidth()/2.0,clear.getY()+clear.getHeight()/2.0,0),
                    "Clear button could not be clicked");
            stage=91;ticks=0;return;
        }
        if(stage==91&&ticks>20&&mc.screen==null&&mc.level.getBlockEntity(P) instanceof PolyBlockEntity be){
            var armor=(PolyArmorAccess)be;var damage=armor.fireControl$armorDamage();
            check(damage.holes().isEmpty(),"Bullet holes remained after the server reply");
            check(damage.editable(be.poly()).equals(savedMesh),"Clearing lost saved custom mesh controls, faces or connections");
            check(armor.fireControl$armorLevel()==7&&be.getMaterial().is(Blocks.IRON_BLOCK),
                    "Clearing changed the copied material or armor level");
            var center=new Vec3(.2,.2,0);
            check(damage.mesh(be.poly()).clip(center.add(0,0,-1),center.add(0,0,1))!=null,
                    "The cleared shot still passes through the mesh");
            check(!((PolyArmorAccess)mc.level.getBlockEntity(Q)).fireControl$armorDamage().holes().isEmpty(),
                    "Clearing an armor block removed the neighboring block's damage");
            mc.getSingleplayerServer().execute(()->{var level=mc.getSingleplayerServer().overworld();var pos=P.east(4);level.setBlockAndUpdate(pos,Copypoly.POLY.get().defaultBlockState());camera(mc,Vec3.atLowerCornerOf(pos).add(.5,.5,-2.5),Vec3.atCenterOf(pos));});next();return;
        }
        if(stage==92){stage=10;ticks=0;return;}
        if(stage==10&&ticks>20&&mc.screen==null){mc.getSingleplayerServer().execute(()->{var server=mc.getSingleplayerServer();Copypoly.POLY.get().openEditor(server.overworld(),P.east(4),server.getPlayerList().getPlayers().getFirst());});next();return;}
        if(stage==11&&ticks>20&&mc.screen instanceof com.caca.copypoly.client.PolyScreen screen){
            shot(mc,"armor-brass-undamaged-world.png");click(screen,"screen.copypoly.table");next();return;
        }
        if(stage==12&&ticks>20&&mc.screen instanceof com.caca.copypoly.client.PolyScreen screen){
            shot(mc,"armor-brass-undamaged-table.png");click(screen,"screen.copypoly.apply");next();return;
        }
        if(stage==13&&ticks>20&&mc.screen==null){
            LogUtils.getLogger().info("ARMOR_CLIENT_PASS: actual APFSDS impacts, editable holes, real clear button and server sync preserving custom mesh, protection level, material and adjacent damage");stage=999;mc.stop();
        }
    }
    private static void verifyMesh(Minecraft mc,BlockPos pos){
        var be=(PolyBlockEntity)mc.level.getBlockEntity(pos);var data=be.getModelData();var damage=((PolyArmorAccess)be).fireControl$armorDamage();
        check(data.has(PolyArmorAccess.MESH),"Client BE lost damage model data");
        var model=mc.getBlockRenderer().getBlockModel(be.getBlockState());
        var quads=model.getQuads(be.getBlockState(),null,RandomSource.create(42),data,null);check(!quads.isEmpty(),"Damaged armor has no rendered material");
        var faces=new ArrayList<ArmorMesh.Face>();for(var quad:quads){int[] raw=quad.getVertices();int stride=raw.length/4;var points=new ArrayList<Vec3>();for(int i=0;i<3;i++)points.add(new Vec3(Float.intBitsToFloat(raw[i*stride]),Float.intBitsToFloat(raw[i*stride+1]),Float.intBitsToFloat(raw[i*stride+2])));if(points.get(1).subtract(points.getFirst()).cross(points.get(2).subtract(points.getFirst())).lengthSqr()>1e-16)faces.add(new ArmorMesh.Face(points));check(!quad.getSprite().contents().name().getPath().equals("missingno"),"Bore wall has missing texture");}
        var rendered=new ArmorMesh(faces);var bore=damage.holes().isEmpty()?ArmorHole.along(new Vec3(.5,.5,0),new Vec3(0,0,1)):damage.holes().getFirst();var direction=bore.direction();
        check(rendered.clip(bore.center().subtract(direction.scale(2)),bore.center().add(direction.scale(2)))==null,"Rendered material filled the real bullet hole");
        check(java.lang.Math.abs(rendered.volume()-damage.volume(be.poly()))<1e-5,"Rendered solid differs from server damage solid");
        var baked=model.getModelData(mc.level,pos,be.getBlockState(),data);check(baked.has(PolyArmorAccess.MESH),"Chunk baking lost bullet holes");
    }
    private static void camera(Minecraft mc,Vec3 eye,Vec3 target){
        var player=mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();var direction=target.subtract(eye);
        float yaw=(float)java.lang.Math.toDegrees(java.lang.Math.atan2(-direction.x,direction.z));float pitch=(float)-java.lang.Math.toDegrees(java.lang.Math.atan2(direction.y,direction.horizontalDistance()));
        player.getAbilities().flying=true;player.onUpdateAbilities();player.connection.teleport(eye.x,eye.y-player.getEyeHeight(),eye.z,yaw,pitch);
    }
    private static void shot(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),m->{});}
    private static ArmorMesh previewMesh(Minecraft mc){var be=(PolyBlockEntity)mc.level.getBlockEntity(P);return ((PolyArmorAccess)be).fireControl$armorDamage().renderMesh(be.poly());}
    private static double previewVolume(Minecraft mc){return previewMesh(mc).volume();}
    private static long previewVertices(Minecraft mc){var be=(PolyBlockEntity)mc.level.getBlockEntity(P);return ((PolyArmorAccess)be).fireControl$armorDamage().renderMesh(be.poly()).faces().stream().flatMap(f->f.vertices().stream()).distinct().count();}
    private static net.minecraft.client.gui.components.Button button(Screen screen,String key){var message=net.minecraft.network.chat.Component.translatable(key).getString();return screen.children().stream().filter(net.minecraft.client.gui.components.Button.class::isInstance).map(net.minecraft.client.gui.components.Button.class::cast).filter(b->b.getMessage().getString().equals(message)).findFirst().orElseThrow();}
    private static void click(Screen screen,String key){button(screen,key).onPress();}
    private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private static void next(){stage++;ticks=0;}
}
