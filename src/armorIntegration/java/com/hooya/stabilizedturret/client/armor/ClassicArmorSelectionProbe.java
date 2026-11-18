package com.hooya.stabilizedturret.client.armor;

import com.caca.copypoly.*;
import com.caca.copypoly.api.*;
import com.caca.copypoly.client.PolyScreen;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.client.ClientVisualConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.*;
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

/** Opens an undamaged block through its actual menu route; covers the formerly missed editor. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT)
public final class ClassicArmorSelectionProbe {
    private static final BlockPos POS=new BlockPos(0,101,0);
    private static int stage,ticks,total;
    private static PolyShape saved;
    private static long deadline;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(!Boolean.getBoolean("create_fire_control.classicArmorSelectionSmoke")||stage==999)return;
        var mc=Minecraft.getInstance();try{run(mc);}catch(Throwable error){LogUtils.getLogger().error("CLASSIC_ARMOR_SELECTION_FAIL stage="+stage,error);stage=999;mc.stop();}
    }
    private static void run(Minecraft mc) throws Exception {
        ticks++;if(++total>5000)throw new IllegalStateException("Classic editor probe timeout "+stage);
        if(stage==0&&(mc.screen instanceof TitleScreen||mc.screen instanceof AccessibilityOnboardingScreen)&&mc.getOverlay()==null){
            mc.options.guiScale().set(2);mc.options.renderDistance().set(4);mc.options.simulationDistance().set(5);mc.options.pauseOnLostFocus=false;mc.resizeDisplay();
            var settings=new LevelSettings("Undamaged armor editor",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("classic-armor-"+System.currentTimeMillis(),settings,new WorldOptions(1234,false,false),WorldPresets::createNormalWorldDimensions,mc.screen);next();return;
        }
        if(stage==1&&mc.level!=null&&mc.player!=null&&mc.screen==null){mc.getSingleplayerServer().execute(()->{
            var server=mc.getSingleplayerServer();var level=server.overworld();for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)for(int y=100;y<=105;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(POS,Copypoly.POLY.get().defaultBlockState());
            var player=server.getPlayerList().getPlayers().getFirst();player.getAbilities().flying=true;player.onUpdateAbilities();player.connection.teleport(2.8,101.7-player.getEyeHeight(),3.8,145,5);level.setDayTime(6000);
        });next();return;}
        if(stage==2&&ticks>60){open(mc);next();return;}
        if(stage==3&&ticks>25&&mc.screen instanceof PolyScreen s){
            check((int)field(s,"imageWidth")==176,"Undamaged editor is still wide");check((int)field(s,"imageHeight")<300,"Undamaged editor still has the empty coordinate area");
            button(s,"select_vertices");button(s,"select_edges");button(s,"select_faces");if(!(boolean)field(s,"xray"))button(s,"xray_off").onPress();
            var p=point(s,7);s.mouseClicked(p.x,p.y,0);check(!(boolean)call(s,"handlesVisible"),"Vertex press displayed arrows");s.mouseReleased(p.x,p.y,0);check((boolean)call(s,"handlesVisible"),"Vertex click did not display arrows");
            var a=point(s,3);var b=point(s,6);s.mouseClicked(a.x,a.y,0);s.mouseDragged(b.x,b.y,0,b.x-a.x,b.y-a.y);s.mouseReleased(b.x,b.y,0);check(doc(s).targets(3).contains(6),"Normal vertex drag no longer connects");
            saved=doc(s);check(doc(s).vertices().stream().map(v->projectUnchecked(s,new Vec3(v.position().x()/16,v.position().y()/16,v.position().z()/16))).anyMatch(projected->!visibleUnchecked(s,projected)),"Default copycat frame does not occlude its own back vertices");s.keyPressed(GLFW.GLFW_KEY_2,0,0);tap(s,point(s,3).lerp(point(s,6),.5));check(edges(s).contains(new PolyShape.Connection(3,6)),"Line mode cannot select drawn lines");
            s.keyPressed(GLFW.GLFW_KEY_DELETE,0,0);check(!doc(s).targets(3).contains(6)&&doc(s).vertices().size()==8,"Line Del deleted vertices or failed");undo(s);check(doc(s).equals(saved)&&!edges(s).isEmpty(),"Line undo did not restore mode/selection");
            box(s);check(edges(s).size()>=13&&faces(s).isEmpty(),"Line marquee did not select hidden and visible lines separately");s.keyPressed(GLFW.GLFW_KEY_3,0,0);tap(s,project(s,new Vec3(.3,1,.6)));check(!faces(s).isEmpty()&&edges(s).isEmpty(),"Face mode did not select a face");
            s.keyPressed(GLFW.GLFW_KEY_DELETE,0,0);check(doc(s).vertices().size()==8&&!(boolean)field(s,"valid"),"Face Del did not leave a repairable opening");button(s,"apply").onPress();check((boolean)field(s,"showOpenings"),"Missing-face hint disappeared");deadline=net.minecraft.Util.getMillis()+5200;next();return;
        }
        if(stage==4&&ticks>10&&mc.screen instanceof PolyScreen s){if(net.minecraft.Util.getMillis()<deadline){if(ticks==11)shot(mc,"classic-missing-face-red.png");return;}check(!(boolean)field(s,"showOpenings"),"Missing-face hint no longer expires");undo(s);check(doc(s).equals(saved),"Face undo failed");s.keyPressed(GLFW.GLFW_KEY_1,0,0);next();return;}
        if(stage==5&&ticks>10&&mc.screen instanceof PolyScreen s){
            shot(mc,"classic-world-three-modes-xray.png");var p=project(s,new Vec3(.4,1,.4));s.mouseClicked(p.x,p.y,1);s.mouseReleased(p.x,p.y,1);button(s,"add_custom").onPress();check(doc(s).vertices().size()==9&&doc(s).vertices().getLast().type()==VertexType.CUSTOM,"Right-click custom point creation was lost");undo(s);
            button(s,"xray_on").onPress();check(!ClientVisualConfig.armorEditorXray(),"Classic switch does not share saved preference");box(s);check(selected(s)==8,"Vertex box misses hidden points when x-ray is off");
            s.keyPressed(GLFW.GLFW_KEY_2,0,0);box(s);check(edges(s).size()>=13,"Hidden edges cannot be box-selected");s.keyPressed(GLFW.GLFW_KEY_3,0,0);box(s);check(faces(s).size()==doc(s).surfaceTriangles().size(),"Face box misses hidden faces");
            s.keyPressed(GLFW.GLFW_KEY_1,0,0);next();return;
        }
        if(stage==6&&ticks>10&&mc.screen instanceof PolyScreen s){shot(mc,"classic-world-xray-off.png");button(s,"table").onPress();next();return;}
        if(stage==7&&ticks>12&&mc.screen instanceof PolyScreen s){
            check(!(boolean)field(s,"worldMode"),"Preview mode failed");int visible=0,hidden=0;for(int i=0;i<8;i++){Vec3 p=point(s,i);if(visible(s,p))visible++;else hidden++;}check(visible>0&&hidden>0,"Preview has no independent mesh depth");
            s.keyPressed(GLFW.GLFW_KEY_2,0,0);box(s);check(edges(s).size()>=13,"Preview line box failed");s.keyPressed(GLFW.GLFW_KEY_3,0,0);box(s);check(faces(s).size()==doc(s).surfaceTriangles().size(),"Preview face box failed");s.keyPressed(GLFW.GLFW_KEY_1,0,0);next();return;
        }
        if(stage==8&&ticks>10&&mc.screen instanceof PolyScreen s){shot(mc,"classic-preview-three-modes.png");button(s,"world").onPress();mc.getSingleplayerServer().execute(()->{for(int x=-2;x<=3;x++)for(int y=100;y<=104;y++)mc.getSingleplayerServer().overworld().setBlockAndUpdate(new BlockPos(x,y,2),Blocks.STONE.defaultBlockState());});next();return;}
        if(stage==9&&ticks>25&&mc.screen instanceof PolyScreen s){check(doc(s).vertices().stream().map(v->projectUnchecked(s,new Vec3(v.position().x()/16,v.position().y()/16,v.position().z()/16))).filter(Objects::nonNull).noneMatch(p->visibleUnchecked(s,p)),"Classic world overlay sees through foreground blocks while off");box(s);check(selected(s)==8,"Foreground block prevents box selection");s.keyPressed(GLFW.GLFW_KEY_1,0,0);next();return;}
        if(stage==10&&ticks>10&&mc.screen instanceof PolyScreen s){shot(mc,"classic-foreground-hidden.png");button(s,"xray_off").onPress();next();return;}
        if(stage==11&&ticks>10&&mc.screen instanceof PolyScreen s){shot(mc,"classic-foreground-translucent.png");button(s,"xray_on").onPress();s.onClose();next();return;}
        if(stage==12&&ticks>10&&mc.screen==null){mc.getSingleplayerServer().execute(()->{for(int x=-2;x<=3;x++)for(int y=100;y<=104;y++)mc.getSingleplayerServer().overworld().setBlockAndUpdate(new BlockPos(x,y,2),Blocks.AIR.defaultBlockState());});open(mc);next();return;}
        if(stage==13&&ticks>20&&mc.screen instanceof PolyScreen s){check(!(boolean)field(s,"xray"),"Classic reopen reset the switch");button(s,"select_edges");shot(mc,"classic-reopened-controls.png");s.onClose();LogUtils.getLogger().info("CLASSIC_ARMOR_SELECTION_PASS: actual undamaged-block route; compact persistent three-mode world/preview editor; hidden point/line/face marquee; line and face Del/undo; independent preview depth; real world occlusion; translucent xray; vertex click/drag/add and five-second openings preserved");stage=999;mc.stop();}
    }
    private static void box(PolyScreen s) throws Exception {List<Vec3> points=new ArrayList<>();for(int i=0;i<doc(s).vertices().size();i++)points.add(point(s,i));points.removeIf(Objects::isNull);double x0=points.stream().mapToDouble(p->p.x).min().orElseThrow()-15,y0=points.stream().mapToDouble(p->p.y).min().orElseThrow()-15,x1=points.stream().mapToDouble(p->p.x).max().orElseThrow()+15,y1=points.stream().mapToDouble(p->p.y).max().orElseThrow()+15;Object marquee=field(s,"marquee");Method begin=marquee.getClass().getDeclaredMethod("begin",double.class,double.class);begin.setAccessible(true);begin.invoke(marquee,x0,y0);s.mouseDragged(x1,y1,0,x1-x0,y1-y0);s.mouseReleased(x1,y1,0);}
    private static void undo(PolyScreen s){s.keyPressed(GLFW.GLFW_KEY_Z,0,GLFW.GLFW_MOD_CONTROL);}
    private static void tap(PolyScreen s,Vec3 p){check(p!=null,"Element not projected");s.mouseClicked(p.x,p.y,0);s.mouseReleased(p.x,p.y,0);}
    private static Vec3 point(PolyScreen s,int i) throws Exception{var p=doc(s).vertices().get(i).position();return project(s,new Vec3(p.x()/16,p.y()/16,p.z()/16));}
    private static Vec3 project(PolyScreen s,Vec3 p) throws Exception{var m=PolyScreen.class.getDeclaredMethod("editProject",Vec3.class);m.setAccessible(true);return (Vec3)m.invoke(s,p);}
    private static Vec3 projectUnchecked(PolyScreen s,Vec3 p){try{return project(s,p);}catch(Exception e){throw new RuntimeException(e);}}
    private static boolean visible(PolyScreen s,Vec3 p) throws Exception {var o=field(s,"overlay");var m=o.getClass().getDeclaredMethod("visible",Vec3.class);m.setAccessible(true);return (boolean)m.invoke(o,p);}
    private static boolean visibleUnchecked(PolyScreen s,Vec3 p){try{return visible(s,p);}catch(Exception e){throw new RuntimeException(e);}}
    private static int selected(PolyScreen s) throws Exception{var gizmo=field(s,"gizmo");var m=gizmo.getClass().getDeclaredMethod("selectedCount");m.setAccessible(true);return (int)m.invoke(gizmo);}
    private static Set<?> edges(PolyScreen s) throws Exception{return (Set<?>)field(s,"selectedConnections");}
    private static Set<?> faces(PolyScreen s) throws Exception{return (Set<?>)field(s,"selectedFaces");}
    private static PolyShape doc(PolyScreen s) throws Exception{return (PolyShape)field(s,"document");}
    private static Object field(Object o,String key) throws Exception{var f=o.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(o);}
    private static Object call(PolyScreen s,String key) throws Exception{var m=PolyScreen.class.getDeclaredMethod(key);m.setAccessible(true);return m.invoke(s);}
    private static Button button(PolyScreen s,String key){return s.children().stream().filter(c->c instanceof Button b&&b.getMessage().equals(Component.translatable("screen.copypoly."+key))).map(Button.class::cast).findFirst().orElseThrow();}
    private static void open(Minecraft mc){mc.getSingleplayerServer().execute(()->{var server=mc.getSingleplayerServer();Copypoly.POLY.get().openEditor(server.overworld(),POS,server.getPlayerList().getPlayers().getFirst());});}
    private static void shot(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),m->{});}
    private static void next(){stage++;ticks=0;}
    private static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
}
