package com.hooya.stabilizedturret.client.armor;

import com.caca.copypoly.*;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientVisualConfig;
import com.hooya.stabilizedturret.content.armor.*;
import com.hooya.stabilizedturret.registry.PolyArmorMenus;
import com.mojang.logging.LogUtils;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
import org.lwjgl.glfw.GLFW;
import java.lang.reflect.*;
import java.util.*;

/** Three selection modes, real world depth, persistent preference and compact layout. Never shipped. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT)
public final class ArmorSelectionClientProbe {
    private static final BlockPos POS=new BlockPos(0,101,0);
    private static int stage,ticks,total;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        if(!Boolean.getBoolean("create_fire_control.armorSelectionSmoke") || stage==999)return;
        var mc=Minecraft.getInstance();try{run(mc);}catch(Throwable ex){stage=999;LogUtils.getLogger().error("ARMOR_SELECTION_FAIL",ex);mc.stop();}
    }
    private static void run(Minecraft mc) throws Exception {
        ticks++;if(++total>5000)throw new IllegalStateException("Selection probe timed out at "+stage);
        if(stage==0&&(mc.screen instanceof TitleScreen||mc.screen instanceof AccessibilityOnboardingScreen)&&mc.getOverlay()==null) {
            mc.options.guiScale().set(2);mc.options.renderDistance().set(4);mc.options.simulationDistance().set(5);mc.options.pauseOnLostFocus=false;mc.resizeDisplay();
            var settings=new LevelSettings("Armor selection and depth",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("armor-selection-"+System.currentTimeMillis(),settings,new WorldOptions(1234,false,false),WorldPresets::createNormalWorldDimensions,mc.screen);next();return;
        }
        if(stage==1&&mc.level!=null&&mc.player!=null&&mc.screen==null) {
            mc.getSingleplayerServer().execute(()->{
                var server=mc.getSingleplayerServer();var level=server.overworld();
                for(int x=-3;x<=3;x++)for(int z=-4;z<=3;z++)for(int y=100;y<=105;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(POS,Copypoly.POLY.get().defaultBlockState());var be=(PolyBlockEntity)level.getBlockEntity(POS);be.setMaterial(Blocks.IRON_BLOCK.defaultBlockState());
                var damage=(PolyArmorAccess)be;damage.fireControl$armorDamage().add(ArmorHole.along(new Vec3(.5,.5,0),new Vec3(0,0,1)));damage.fireControl$armorChanged();
                var player=server.getPlayerList().getPlayers().getFirst();player.getAbilities().flying=true;player.onUpdateAbilities();player.connection.teleport(.5,101.5-player.getEyeHeight(),-2.5,0,0);level.setDayTime(6000);
            });next();return;
        }
        if(stage==2&&ticks>70){open(mc);next();return;}
        if(stage==3&&ticks>30&&mc.screen instanceof ArmorMeshScreen s&&s.getMenu().snapshot()!=null) {
            check((int)field(s,"panelWidth")<=176,"Sidebar is still wide");
            check((int)field(s,"panelHeight")<260,"Empty coordinate row still reserves space");
            check(field(s,"mode")==ArmorEditorSelection.Mode.VERTEX,"Default mode is not vertex");
            if(!(boolean)field(s,"xray"))click(s,"xray_off");
            var mesh=doc(s);int hidden=hiddenVertex(s);check(hidden>=0,"No self-occluded point was detected from world depth");
            var edge=mesh.edges().stream().filter(e->mesh.vertices().get(e.a()).z<1e-8&&mesh.vertices().get(e.b()).z<1e-8).findFirst().orElseThrow();
            var p=s.view.project(mesh.vertices().get(edge.a()).lerp(mesh.vertices().get(edge.b()),.5));tap(s,p);
            check(selection(s).isEmpty(),"Vertex mode selected an edge");
            s.keyPressed(GLFW.GLFW_KEY_2,0,0);tap(s,p);check(edges(s).size()==1&&selection(s).size()==2,"Edge mode did not select only an edge and its movable endpoints");
            marquee(s);check(edges(s).size()==mesh.edges().size(),"Edge marquee did not replace selection with all visible and hidden edges");
            check(faces(s).isEmpty(),"Edge marquee selected faces");
            s.keyPressed(GLFW.GLFW_KEY_3,0,0);tap(s,s.view.project(new Vec3(.2,.2,0)));check(!faces(s).isEmpty()&&edges(s).isEmpty(),"Face click did not select a surface patch");
            var before=doc(s);s.keyPressed(GLFW.GLFW_KEY_DELETE,0,0);check(doc(s).vertices().equals(before.vertices())&&doc(s).triangles().size()<before.triangles().size(),"Face Del deleted points or left the face");
            s.keyPressed(GLFW.GLFW_KEY_Z,0,GLFW.GLFW_MOD_CONTROL);check(doc(s).equals(before)&&!faces(s).isEmpty(),"Face undo lost mode or selection");
            marquee(s);check(faces(s).size()==mesh.triangles().size(),"Face marquee missed hidden surface triangles");
            next();return;
        }
        if(stage==4&&ticks>10&&mc.screen instanceof ArmorMeshScreen s){shot(mc,"armor-face-mode-compact.png");s.keyPressed(GLFW.GLFW_KEY_1,0,0);next();return;}
        if(stage==5&&ticks>10&&mc.screen instanceof ArmorMeshScreen s){
            shot(mc,"armor-xray-on-hidden-translucent.png");click(s,"xray_on");check(!ClientVisualConfig.armorEditorXray(),"X-ray preference was not saved");
            marquee(s);check(selection(s).size()==doc(s).vertices().size(),"X-ray off incorrectly excluded hidden points from marquee");
            emptyMarquee(s);check(selection(s).isEmpty(),"Empty replacement marquee retained the old selection");
            int hidden=hiddenVertex(s);check(hidden>=0,"No hidden point available");tap(s,s.view.project(doc(s).vertices().get(hidden)));check(!selection(s).contains(hidden),"X-ray off allowed clicking through the block");
            s.keyPressed(GLFW.GLFW_KEY_2,0,0);marquee(s);check(edges(s).size()==doc(s).edges().size(),"X-ray off excluded hidden edges from marquee");
            s.keyPressed(GLFW.GLFW_KEY_3,0,0);marquee(s);check(faces(s).size()==doc(s).triangles().size(),"X-ray off excluded hidden faces from marquee");
            s.keyPressed(GLFW.GLFW_KEY_1,0,0);next();return;
        }
        if(stage==6&&ticks>10&&mc.screen instanceof ArmorMeshScreen s){shot(mc,"armor-xray-off-visible-only.png");s.onClose();next();return;}
        if(stage==7&&ticks>10&&mc.screen==null){open(mc);next();return;}
        if(stage==8&&ticks>25&&mc.screen instanceof ArmorMeshScreen s&&s.getMenu().snapshot()!=null){
            check(!(boolean)field(s,"xray"),"Reopening reset the x-ray switch");
            mc.getSingleplayerServer().execute(()->{for(int x=-1;x<=1;x++)for(int y=100;y<=103;y++)mc.getSingleplayerServer().overworld().setBlockAndUpdate(new BlockPos(x,y,-1),Blocks.STONE.defaultBlockState());});next();return;
        }
        if(stage==9&&ticks>25&&mc.screen instanceof ArmorMeshScreen s){
            check(doc(s).vertices().stream().map(s.view::project).filter(Objects::nonNull).noneMatch(s.view::visible),"A real foreground block did not occlude the editor");
            s.keyPressed(GLFW.GLFW_KEY_3,0,0);tap(s,s.view.project(new Vec3(.2,.2,0)));check(faces(s).isEmpty(),"Face picker selected through a foreground block");
            marquee(s);check(faces(s).size()==doc(s).triangles().size(),"Foreground block incorrectly blocked face marquee");
            s.keyPressed(GLFW.GLFW_KEY_1,0,0);next();return;
        }
        if(stage==10&&ticks>10&&mc.screen instanceof ArmorMeshScreen s){shot(mc,"armor-foreground-off.png");click(s,"xray_off");next();return;}
        if(stage==11&&ticks>10&&mc.screen instanceof ArmorMeshScreen s){
            shot(mc,"armor-foreground-xray-translucent.png");s.onClose();
            LogUtils.getLogger().info("ARMOR_SELECTION_PASS: three isolated modes, point/edge/face replacement and hidden marquee, face Del/undo, real scene occlusion, translucent xray, saved/reopened preference, compact layout");stage=999;mc.stop();
        }
    }
    private static int hiddenVertex(ArmorMeshScreen s) throws Exception{for(int i=0;i<doc(s).vertices().size();i++){var p=s.view.project(doc(s).vertices().get(i));if(p!=null&&!s.view.visible(p))return i;}return -1;}
    private static void marquee(ArmorMeshScreen s) throws Exception {
        var points=doc(s).vertices().stream().map(s.view::project).filter(Objects::nonNull).toList();
        double x0=points.stream().mapToDouble(p->p.x).min().orElseThrow()-20,y0=points.stream().mapToDouble(p->p.y).min().orElseThrow()-20;
        double x1=points.stream().mapToDouble(p->p.x).max().orElseThrow()+20,y1=points.stream().mapToDouble(p->p.y).max().orElseThrow()+20;
        s.mouseClicked(x0,y0,0);s.mouseDragged(x1,y1,0,x1-x0,y1-y0);s.mouseReleased(x1,y1,0);
    }
    private static void emptyMarquee(ArmorMeshScreen s){s.mouseClicked(s.width-60,60,0);s.mouseDragged(s.width-25,90,0,35,30);s.mouseReleased(s.width-25,90,0);}
    private static void tap(ArmorMeshScreen s,Vec3 p){check(p!=null,"Missing projected element");s.mouseClicked(p.x,p.y,0);s.mouseReleased(p.x,p.y,0);}
    private static EditableArmorMesh doc(ArmorMeshScreen s) throws Exception{return (EditableArmorMesh)field(s,"document");}
    private static Set<?> selection(ArmorMeshScreen s) throws Exception{return (Set<?>)field(s,"selection");}
    private static Set<?> edges(ArmorMeshScreen s) throws Exception{return (Set<?>)field(s,"selectedEdges");}
    private static Set<?> faces(ArmorMeshScreen s) throws Exception{return (Set<?>)field(s,"selectedFaces");}
    private static Object field(Object o,String name) throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void click(ArmorMeshScreen s,String key){s.children().stream().filter(c->c instanceof Button b&&b.getMessage().equals(Component.translatable("screen.create_fire_control.armor."+key))).map(Button.class::cast).findFirst().orElseThrow().onPress();}
    private static void open(Minecraft mc){mc.getSingleplayerServer().execute(()->{var server=mc.getSingleplayerServer();var level=server.overworld();PolyArmorMenus.open(server.getPlayerList().getPlayers().getFirst(),POS,(PolyBlockEntity)level.getBlockEntity(POS));});}
    private static void shot(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),message->{});}
    private static void next(){stage++;ticks=0;}
    private static void check(boolean valid,String message){if(!valid)throw new IllegalStateException(message);}
}
