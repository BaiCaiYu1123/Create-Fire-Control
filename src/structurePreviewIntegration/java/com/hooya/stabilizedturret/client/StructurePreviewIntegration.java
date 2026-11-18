package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.bearing.*;
import com.hooya.stabilizedturret.content.controller.*;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.network.*;
import com.hooya.stabilizedturret.registry.ModBlocks;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.joml.*;
import java.lang.Math;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Production-network and actual OpenGL UI check in a disposable integrated-server world. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,value=Dist.CLIENT)
public final class StructurePreviewIntegration {
    private static int age, stage, delay;
    private static boolean launched, done;
    private static CompletableFuture<Void> job;
    private static volatile ServerSubLevel body;
    private static volatile StabilizerControllerBlockEntity controller;
    private static volatile ConnectedDisplayBlockEntity display;
    private static volatile StabilizerControllerBlockEntity largeController;
    private static volatile BlockPos rootController;
    private static final List<BindingRef> yaws=new ArrayList<>(), pitches=new ArrayList<>();
    private static StructurePreviewScreen preview;
    private static PreviewBearingSettings requested;
    private static volatile Vec3 initialPlateInParent;
    private static volatile int displayAssemblyTick;
    private static int closedScreenTicks,closedRendererTicks;

    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event){
        if(done)return;
        Minecraft mc=Minecraft.getInstance();
        try{
            if(++age>6000)throw new AssertionError("Preview fixture timed out at stage "+stage);
            if(!launched&&age>70&&mc.screen!=null&&mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")){mc.setScreen(new TitleScreen());return;}
            if(!launched&&age>70&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null){
                launched=true;
                mc.createWorldOpenFlows().createFreshLevel("StructurePreview-"+System.currentTimeMillis(),
                        new LevelSettings("Structure preview QA",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,
                                new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(1234,false,false),WorldPresets::createNormalWorldDimensions,mc.screen);
                return;
            }
            if(mc.level==null||mc.player==null)return;
            if(job!=null){if(!job.isDone())return;job.join();job=null;}
            // The root-world UI checks move the player away. Wait for real server ticks
            // after returning, rather than assuming client ticks imply a simulated joint.
            if(stage==14&&mc.getSingleplayerServer().getTickCount()-displayAssemblyTick<25)return;
            if(stage==30) {
                if(!(mc.screen instanceof StabilizerControllerScreen))return;
                Button entry=button(mc.screen,"entry");
                if(entry==null||!entry.visible||!entry.active)return;
            }
            if(stage==31&&mc.screen instanceof StructurePreviewScreen screen) {
                preview=screen;
                @SuppressWarnings("unchecked")var sections=(Map<?,com.hooya.stabilizedturret.client.preview.StructureHullCache.Section>)get(preview,"completed");
                if(age%40==0) {
                    capture(mc,"large-loading.png");
                    StabilizedTurret.LOGGER.info("Large preview progress: state={}, sections={}, sampled={}, blocks={}",get(preview,"state")!=null,
                            ((List<?>)get(preview,"queue")).size(),get(preview,"sampled"),sections.values().stream().mapToInt(s->s.blocks().size()).sum());
                    job=CompletableFuture.runAsync(()->{
                        var player=mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                        Vec3 p=dev.ryanhcode.sable.Sable.HELPER.projectOutOfSubLevel(largeController.getLevel(),largeController.getBlockPos().getCenter());
                        StabilizedTurret.LOGGER.info("Large preview access: player={}, controller={}, world={}, distance={}, loaded={}",
                                player.position(),largeController.getBlockPos(),p,player.position().distanceTo(p),player.serverLevel().isLoaded(BlockPos.containing(p)));
                        player.teleportTo(p.x,p.y+1,p.z);
                    },mc.getSingleplayerServer());
                }
                if(sections.values().stream().mapToInt(s->s.blocks().size()).sum()<PreviewLargeStructureFixture.BLOCKS
                        ||(int)get(preview,"sampled")<((List<?>)get(preview,"queue")).size())return;
            }
            if(++delay<25)return;delay=0;
            switch(stage++){
                case 0->{
                    mc.setScreen(null);mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);mc.getToasts().clear();
                    mc.options.guiScale().set(2);mc.options.pauseOnLostFocus=false;mc.resizeDisplay();
                    if(Boolean.getBoolean("createFireControl.previewLargeOnly")) {
                        stage=30;mc.options.renderDistance().set(10);mc.options.graphicsMode().set(GraphicsStatus.FANCY);
                        job=CompletableFuture.runAsync(()->{largeController=PreviewLargeStructureFixture.create(mc.getSingleplayerServer().overworld());open(mc,largeController);},mc.getSingleplayerServer());
                        break;
                    }
                    job=CompletableFuture.runAsync(()->{try{setup(mc);}catch(Exception e){throw new RuntimeException(e);}},mc.getSingleplayerServer());
                }
                case 1->job=CompletableFuture.runAsync(()->{
                    var level=controller.getLevel();Vector3d local=new Vector3d(body.getPlot().getCenterBlock().getX(),body.getPlot().getCenterBlock().getY(),body.getPlot().getCenterBlock().getZ());
                    Vector3d world=body.logicalPose().transformPosition(new Vector3d(local));
                    SubLevelPhysicsSystem.require((net.minecraft.server.level.ServerLevel)level).getPipeline().addConstraint(null,body,
                            new FixedConstraintConfiguration(world,local,new Quaterniond(body.logicalPose().orientation())));
                    controller=(StabilizerControllerBlockEntity)level.getBlockEntity(controller.getBlockPos());
                    display=(ConnectedDisplayBlockEntity)level.getBlockEntity(display.getBlockPos());
                    List<BlockPos> yawPositions=yaws.stream().map(BindingRef::pos).toList(),pitchPositions=pitches.stream().map(BindingRef::pos).toList();
                    yaws.clear();pitches.clear();
                    for(var pos:yawPositions){var ref=BindingRef.capture(level,pos);yaws.add(ref);controller.bind(BindingSlot.YAW,ref);display.bind(BindingSlot.YAW,ref);}
                    for(var pos:pitchPositions){var ref=BindingRef.capture(level,pos);pitches.add(ref);controller.bind(BindingSlot.PITCH,ref);display.bind(BindingSlot.PITCH,ref);}
                    StabilizedTurret.LOGGER.info("Preview fixture: controller={} devices={} refsValid={}",controller.getBlockPos(),controller.getBoundDevices(),
                            yaws.stream().allMatch(r->r.isSameTarget(level))&&pitches.stream().allMatch(r->r.isSameTarget(level)));
                    try{PreviewComplexModelsFixture.assemble(level,body.getPlot().getCenterBlock());}catch(Exception error){throw new RuntimeException(error);}
                    open(mc,controller);
                },mc.getSingleplayerServer());
                case 2->{
                    require(mc.screen instanceof StabilizerControllerScreen,"real controller payload opens the controller");
                    var initial=(OpenControllerPayload)get(mc.screen,"initial");
                    require(initial.controllerSubLevelId()!=null,"server identifies controller physical body: "+body.getUniqueId());
                    require(button(mc.screen,"entry")!=null,"small controller preview entry exists on a body");
                    require(button(mc.screen,"entry").visible,"body preview entry visible after streaming: "+initial.controllerSubLevelId()+" loaded="+
                            dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level).getAllSubLevels());capture(mc,"controller-entry.png");
                    clickEntry(mc.screen);
                }
                case 3->{
                    require(mc.screen instanceof StructurePreviewScreen,"controller entry opens preview");preview=(StructurePreviewScreen)mc.screen;
                    var state=(StructurePreviewStatePayload)get(preview,"state");require(state!=null&&state.devices().size()==4,"server snapshot contains all four bound axes: "+state);
                    require((int)get(preview,"sampled")>0,"loaded hull sections were sampled");capture(mc,"structure-overview.png");
                    @SuppressWarnings("unchecked") var meshes=(Map<Object,Object>)get(get(preview,"renderer"),"meshes");
                    boolean opaque=false,context=false;
                    for(var mesh:meshes.values()){opaque|=get(mesh,"opaque")!=null||get(mesh,"movingOpaque")!=null;context|=get(mesh,"context")!=null||get(mesh,"movingContext")!=null;}
                    require(opaque&&context,"chassis and fire-control equipment use separate translucent and opaque VBOs");
                    require(call(preview,"northArrow",new Class<?>[0])==null,"north indicator stays hidden without a selected yaw bearing");
                    verifyNativeModels(mc);
                    verifyComplexModels(mc);verifyOverlayPriority(mc);verifyPreviewInteractions();
                    rightClickAxis("YAW");require(((StructurePreviewStatePayload.Device)get(preview,"editing")).role().equals("YAW"),"right-click opens yaw editor");
                    verifyNorthDirection();
                }
                case 4->{
                    capture(mc,"yaw-editor.png");
                    @SuppressWarnings("unchecked") var fields=(List<EditBox>)get(preview,"numericFields");
                    fields.get(0).setValue("1.25");fields.get(3).setValue("37");fields.get(4).setValue("42");fields.get(5).setValue("68");
                    set(preview,"reversed",true);set(preview,"hidden",true);set(preview,"centerDirection",YawLimits.NORTH);
                    requested=new PreviewBearingSettings(90,89,37,true,true,new Vec3(1.25,0,0),new YawLimits(42,68,YawLimits.NORTH));
                    button(preview,"apply").onPress();
                }
                case 5->{
                    require(get(preview,"pendingKey")==null,"controller change was acknowledged over the network");
                    job=CompletableFuture.runAsync(()->{
                        var own=controller.getBearingSettings(BindingSlot.YAW,yaws.getFirst());
                        require(own.speed()==37&&own.reversed()&&own.headHidden()&&own.offset().x==1.25&&own.yawLimits().left()==42,"controller stores selected yaw override");
                        require(controller.getBearingSettings(BindingSlot.YAW,yaws.get(1)).speed()==controller.getYawSpeedDegreesPerSecond(),"other controller yaw keeps default speed");
                        require(!controller.getBearingSettings(BindingSlot.YAW,yaws.get(1)).reversed(),"other yaw keeps its own reverse flag");
                        require(display.getPreviewBearingSettings(BindingSlot.YAW,yaws.getFirst()).speed()==120,"other computer is unchanged");
                        var nbt=controller.saveWithoutMetadata(controller.getLevel().registryAccess());
                        var copy=new StabilizerControllerBlockEntity(controller.getBlockPos(),controller.getBlockState());copy.setLevel(controller.getLevel());copy.loadWithComponents(nbt,controller.getLevel().registryAccess());
                        require(copy.getBearingSettings(BindingSlot.YAW,yaws.getFirst()).speed()==37,"controller override survives save/load");
                        open(mc,display);
                    },mc.getSingleplayerServer());
                }
                case 6->{
                    require(mc.screen instanceof AutoDefenseDisplayScreen,"real display payload opens display settings");capture(mc,"display-entry.png");
                    require(button(mc.screen,"entry")!=null,"small display entry exists on a body");clickEntry(mc.screen);
                }
                case 7->{
                    preview=(StructurePreviewScreen)mc.screen;rightClickAxis("PITCH");
                    require(((StructurePreviewStatePayload.Device)get(preview,"editing")).role().equals("PITCH"),"right-click opens pitch editor");
                    require(call(preview,"northArrow",new Class<?>[0])==null,"pitch selection hides the yaw north indicator");
                    capture(mc,"pitch-editor.png");
                    @SuppressWarnings("unchecked") var fields=(List<EditBox>)get(preview,"numericFields");
                    fields.get(1).setValue("-0.75");fields.get(3).setValue("28");fields.get(4).setValue("13");fields.get(5).setValue("61");
                    set(preview,"reversed",true);set(preview,"hidden",true);button(preview,"apply").onPress();
                }
                case 8->{
                    require(get(preview,"pendingKey")==null,"display change acknowledged over the network");
                    job=CompletableFuture.runAsync(()->{
                        var s=display.getPreviewBearingSettings(BindingSlot.PITCH,pitches.getFirst());
                        require(s.speed()==28&&s.depression()==13&&s.elevation()==61&&s.offset().y==-.75&&s.reversed()&&s.headHidden(),"display stores all selected pitch values");
                        require(display.getPreviewBearingSettings(BindingSlot.PITCH,pitches.get(1)).speed()==90,"second display pitch stays at 90");
                        require(display.getPreviewBearingSettings(BindingSlot.YAW,yaws.getFirst()).speed()==120,"display yaw defaults unchanged");
                        require(controller.getBearingSettings(BindingSlot.PITCH,pitches.getFirst()).speed()==controller.getPitchSpeedDegreesPerSecond(),"controller pitch unchanged by display edit");
                        var tag=display.saveWithoutMetadata(display.getLevel().registryAccess());var copy=new ConnectedDisplayBlockEntity(display.getBlockPos(),display.getBlockState());
                        copy.setLevel(display.getLevel());copy.loadWithComponents(tag,display.getLevel().registryAccess());
                        require(copy.getPreviewBearingSettings(BindingSlot.PITCH,pitches.getFirst()).equals(s),"display full settings survive save/load");
                    },mc.getSingleplayerServer());
                }
                case 9->{
                    rightClickAxis("YAW");verifyNorthDirection();set(preview,"yaw",90d);set(preview,"pitch",90d);
                    mc.options.guiScale().set(3);mc.resizeDisplay();
                }
                case 10->{
                    preview=(StructurePreviewScreen)mc.screen;capture(mc,"compact-editor.png");
                    preview.mouseScrolled(mc.screen.width-20,100,0,-20);require((int)get(preview,"editorScroll")>0,"compact editor scrolls to lower settings");
                }
                case 11->{
                    capture(mc,"compact-editor-scrolled.png");
                    job=CompletableFuture.runAsync(()->{
                        var player=mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                        player.teleportTo(rootController.getX()+.5,rootController.getY()+1,rootController.getZ()+.5);
                        var level=mc.getSingleplayerServer().overworld();level.getBlockState(rootController).useWithoutItem(level,player,new BlockHitResult(rootController.getCenter(),Direction.UP,rootController,false));
                    },mc.getSingleplayerServer());
                }
                case 12->{
                    require(mc.screen instanceof StabilizerControllerScreen,"root-world controller opened");
                    require(button(mc.screen,"entry")==null,"root-world controller hides the preview entry");capture(mc,"root-controller-no-entry.png");
                    job=CompletableFuture.runAsync(()->{
                        var level=mc.getSingleplayerServer().overworld();BlockPos pos=rootController.offset(2,0,0);
                        level.setBlockAndUpdate(pos,ModBlocks.CONNECTED_DISPLAY.get().defaultBlockState());
                        open(mc,level.getBlockEntity(pos));
                    },mc.getSingleplayerServer());
                }
                case 13->{
                    require(mc.screen instanceof AutoDefenseDisplayScreen,"root-world display opened");
                    require(button(mc.screen,"entry")==null,"root-world display hides the preview entry");capture(mc,"root-display-no-entry.png");
                    job=CompletableFuture.runAsync(()->{
                        var ref=pitches.getFirst();var level=controller.getLevel();
                        open(mc,display);
                        require(controller.unbindDevice(BindingSlot.PITCH,0,ref.pos()),"detach controller from display physics fixture");
                        var bearing=(dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity)ref.resolve(level);
                        level.setBlockAndUpdate(ref.pos().east(),Blocks.STONE.defaultBlockState());
                        bearing.assemble();require(bearing.isAssembled(),"display pitch assembled a real child body");
                        initialPlateInParent=body.logicalPose().transformPositionInverse(dev.ryanhcode.sable.Sable.HELPER.projectOutOfSubLevel(level,bearing.getPlatePos().getCenter()));
                        displayAssemblyTick=mc.getSingleplayerServer().getTickCount();
                    },mc.getSingleplayerServer());
                }
                case 14->job=CompletableFuture.runAsync(()->{
                    var ref=pitches.getFirst();var bearing=(dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity)ref.resolve(controller.getLevel());
                    require(BearingStabilizer.ownsJoint(bearing),"configured display pitch uses the physical offset servo");
                    require(controller.getLevel().getBlockEntity(bearing.getPlatePos()) instanceof BearingHeadVisibility visibility
                            && visibility.stabilizedTurret$isBearingHeadHidden(),"configured pitch hides the actual bearing head");
                    Vec3 moved=body.logicalPose().transformPositionInverse(dev.ryanhcode.sable.Sable.HELPER.projectOutOfSubLevel(controller.getLevel(),bearing.getPlatePos().getCenter()));
                    require(moved.distanceTo(initialPlateInParent)>.2,"display per-device offset physically moves its child anchor");
                    open(mc,display);
                },mc.getSingleplayerServer());
                case 15->{capture(mc,"display-compact.png");clickEntry(mc.screen);}
                case 16->{
                    preview=(StructurePreviewScreen)mc.screen;var state=(StructurePreviewStatePayload)get(preview,"state");
                    require(state.bodies().size()>=2,"preview includes the articulated child body");
                    require(!state.opaqueBodies().contains(body.getUniqueId())&&!state.opaqueBodies().isEmpty(),"driven turret body opaque; its chassis remains translucent");
                    @SuppressWarnings("unchecked") var meshes=(Map<com.hooya.stabilizedturret.client.preview.StructureHullCache.SectionKey,Object>)get(get(preview,"renderer"),"meshes");
                    for(var entry:meshes.entrySet())if(state.opaqueBodies().contains(entry.getKey().body()))
                        require(get(entry.getValue(),"context")==null,"turret and barrel geometry never enter the translucent pass");
                    capture(mc,"articulated-structure.png");
                    mc.options.guiScale().set(4);mc.resizeDisplay();
                    job=CompletableFuture.runAsync(()->open(mc,display),mc.getSingleplayerServer());
                }
                case 17->{
                    require(mc.screen instanceof AutoDefenseDisplayScreen,"small display settings opened");capture(mc,"display-small.png");
                    require((int)call(mc.screen,"maxScroll",new Class<?>[0])>0,"short display viewport exposes scrolling");
                    mc.screen.mouseScrolled(mc.screen.width/2.0,mc.screen.height/2.0,0,-10);
                    require((int)get(mc.screen,"scrollOffset")>0,"small display scrolls to bottom settings");
                }
                case 18->{
                    capture(mc,"display-small-scrolled.png");
                    Button save=mc.screen.children().stream().filter(c->c instanceof Button b&&b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("gui.done").getString())).map(c->(Button)c).findFirst().orElseThrow();
                    require(save.visible&&save.getY()>=0&&save.getY()+save.getHeight()<=mc.screen.height,"save stays visible while the display content scrolls");
                    mc.screen.mouseClicked(save.getX()+save.getWidth()/2.0,save.getY()+save.getHeight()/2.0,0);
                }
                case 19->{
                    job=CompletableFuture.runAsync(()->{
                        var s=display.getPreviewBearingSettings(BindingSlot.PITCH,pitches.getFirst());
                        require(s.depression()==13&&s.elevation()==61&&s.speed()==28,"saving display defaults preserves the independently configured pitch limits");
                        open(mc,display);
                    },mc.getSingleplayerServer());
                }
                case 20->{clickEntry(mc.screen);}
                case 21->{
                    require(mc.screen instanceof StructurePreviewScreen,"small display header also opens preview after scrolling");
                    preview=(StructurePreviewScreen)mc.screen;job=mc.reloadResourcePacks();
                }
                case 22->{
                    require((int)get(preview,"sampled")>0,"resource reload rebuilds model snapshots");
                    capture(mc,"reloaded-models.png");mc.options.guiScale().set(2);mc.resizeDisplay();
                }
                case 23->{capture(mc,"drawer-open.png");click(preview,(Button)get(preview,"drawerToggle"));}
                case 24->{
                    require((boolean)get(preview,"sidebarCollapsed"),"drawer stays closed after rendering and receiving a snapshot");
                    capture(mc,"drawer-closed.png");click(preview,(Button)get(preview,"drawerToggle"));
                }
                case 25->{capture(mc,"drawer-reopened.png");click(preview,(Button)get(preview,"outlineToggle"));}
                case 26->{capture(mc,"outlines-hidden.png");click(preview,(Button)get(preview,"outlineToggle"));rightClickAxis("YAW");}
                case 27->{
                    require(get(preview,"contextButton")==null&&get(preview,"editing")!=null,"right-click device opens only its settings without transparency popup");
                    capture(mc,"device-settings-no-popup.png");openCannonMenu(false);
                    Button popup=(Button)get(preview,"contextButton");require(popup!=null,"right-click cannon geometry opens the compact transparency menu");
                    var move=net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove",long.class,double.class,double.class);move.setAccessible(true);
                    double scale=mc.getWindow().getGuiScale();
                    move.invoke(mc.mouseHandler,mc.getWindow().getWindow(),(popup.getX()+popup.getWidth()/2.0)*scale,(popup.getY()+popup.getHeight()/2.0)*scale);
                }
                case 28->{
                    Button popup=(Button)get(preview,"contextButton");double scale=mc.getWindow().getGuiScale();
                    require(popup!=null&&popup.isMouseOver(mc.mouseHandler.xpos()/scale,mc.mouseHandler.ypos()/scale),"actual GUI render has the transparency menu under the cursor");
                    require(!((Map<?,?>)get(preview,"previewTooltips")).containsKey(popup),"compact transparency menu has no extra hover explanation");
                    capture(mc,"compact-transparency-menu.png");preview.onClose();
                    closedScreenTicks=(int)get(preview,"ticks");closedRendererTicks=(int)get(get(preview,"renderer"),"refreshTicks");
                    verifyClosedPreview(mc);
                }
                case 29->{
                    verifyClosedPreview(mc);mc.setScreen(null);mc.options.renderDistance().set(10);
                    mc.options.graphicsMode().set(GraphicsStatus.FANCY);
                    job=CompletableFuture.runAsync(()->{largeController=PreviewLargeStructureFixture.create(mc.getSingleplayerServer().overworld());open(mc,largeController);},mc.getSingleplayerServer());
                }
                case 30->{require(mc.screen instanceof StabilizerControllerScreen,"large physical structure opens its real controller UI");clickEntry(mc.screen);}
                case 31->{
                    verifyLargeStructure(mc);capture(mc,"large-196608-blocks.png");
                    int meshes=((Map<?,?>)get(get(preview,"renderer"),"meshes")).size();
                    call(preview,"startSampling",new Class<?>[0]);
                    require((int)get(preview,"sampled")==0&&((Map<?,?>)get(get(preview,"renderer"),"meshes")).size()==meshes,
                            "resampling retains the complete previous geometry while a new generation is pending");
                    preview.onClose();closedScreenTicks=(int)get(preview,"ticks");closedRendererTicks=(int)get(get(preview,"renderer"),"refreshTicks");
                    verifyClosedPreview(mc);
                }
                case 32->{verifyClosedPreview(mc);finish(mc,null);}
                default->throw new AssertionError("Unexpected stage");
            }
        }catch(Throwable failure){finish(mc,failure);}
    }
    private static void setup(Minecraft mc) throws Exception {
        var server=mc.getSingleplayerServer();var level=server.overworld();level.setChunkForced(0,0,true);
        BlockPos base=new BlockPos(0,160,0);
        for(int x=-7;x<=7;x++)for(int z=-4;z<=4;z++)level.setBlockAndUpdate(base.offset(x,0,z),Blocks.IRON_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(base.above(),ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        level.setBlockAndUpdate(base.offset(2,1,1),Blocks.STONE_BRICKS.defaultBlockState());
        level.setBlockAndUpdate(base.offset(2,1,0),ModBlocks.CONNECTED_DISPLAY.get().defaultBlockState());
        level.setBlockAndUpdate(base.offset(-5,1,1),Blocks.OAK_STAIRS.defaultBlockState());
        level.setBlockAndUpdate(base.offset(-3,1,1),Blocks.STONE_SLAB.defaultBlockState());
        level.setBlockAndUpdate(base.offset(-1,1,2),Blocks.OAK_FENCE.defaultBlockState());
        level.setBlockAndUpdate(base.offset(4,1,1),Blocks.GLASS_PANE.defaultBlockState());
        level.setBlockAndUpdate(base.offset(6,1,1),Blocks.OAK_TRAPDOOR.defaultBlockState());
        var barrel=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("createbigcannons:cast_iron_cannon_barrel"));
        require(barrel!=Blocks.AIR,"cannon barrel fixture type exists");
        level.setBlockAndUpdate(base.offset(4,1,3),barrel.defaultBlockState());
        var bearing=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("simulated:swivel_bearing"));
        for(int i=0;i<4;i++){
            BlockPos pos=base.offset(-5+i*3,1,-2);level.setBlockAndUpdate(pos,bearing.defaultBlockState().setValue(SwivelBearingBlock.FACING,i<2?Direction.UP:Direction.EAST));
        }
        for(int x=-6;x<=6;x++)for(int y=1;y<=3;y++)if(x%3==0)level.setBlockAndUpdate(base.offset(x,y,3),Blocks.STONE_BRICKS.defaultBlockState());
        PreviewComplexModelsFixture.place(level,base);
        List<BlockPos> structure=new ArrayList<>();
        for(int x=-7;x<=7;x++)for(int y=0;y<=3;y++)for(int z=-4;z<=4;z++){
            BlockPos pos=base.offset(x,y,z);if(!level.getBlockState(pos).isAir())structure.add(pos);
        }
        body=dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(level,base,structure,
                dev.ryanhcode.sable.companion.math.BoundingBox3i.from(structure));require(body!=null,"physical carrier assembled");
        ((dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer)dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level))
                .addForceLoadTicket(body,dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,net.minecraft.util.Unit.INSTANCE);
        BlockPos plot=body.getPlot().getCenterBlock();
        controller=(StabilizerControllerBlockEntity)level.getBlockEntity(plot.above());
        display=(ConnectedDisplayBlockEntity)level.getBlockEntity(plot.offset(2,1,0));
        require(controller!=null&&display!=null,"assembled computer and supported display survived relocation");
        for(int i=0;i<4;i++)(i<2?yaws:pitches).add(BindingRef.capture(level,plot.offset(-5+i*3,1,-2)));
        rootController=new BlockPos(24,160,0);level.setBlockAndUpdate(rootController,ModBlocks.STABILIZER_CONTROLLER.get().defaultBlockState());
        var player=server.getPlayerList().getPlayers().getFirst();player.getAbilities().flying=true;player.getAbilities().mayfly=true;player.onUpdateAbilities();
    }
    private static void open(Minecraft mc,net.minecraft.world.level.block.entity.BlockEntity host){
        var player=mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
        Vec3 p=dev.ryanhcode.sable.Sable.HELPER.projectOutOfSubLevel(host.getLevel(),host.getBlockPos().getCenter());
        player.teleportTo(p.x,p.y+1,p.z);boolean shifted=player.isShiftKeyDown();
        player.setShiftKeyDown(host instanceof ConnectedDisplayBlockEntity);
        try{host.getBlockState().useWithoutItem(host.getLevel(),player,new BlockHitResult(host.getBlockPos().getCenter(),Direction.UP,host.getBlockPos(),false));}
        finally{player.setShiftKeyDown(shifted);}
    }
    private static void clickEntry(Screen screen){
        Button entry=button(screen,"entry");
        require(entry!=null&&entry.visible&&entry.active,"preview entry is available for mouse input");
        require(screen.mouseClicked(entry.getX()+entry.getWidth()/2.0,entry.getY()+entry.getHeight()/2.0,0),"header accepts actual preview-button mouse click");
        require(Minecraft.getInstance().screen instanceof StructurePreviewScreen,"mouse click enters structure preview");
    }
    private static void verifyPreviewInteractions()throws Exception {
        require((int)call(preview,"sidebarWidth",new Class<?>[0])<=148,"device list uses a narrow sidebar");
        int view=(int)call(preview,"viewWidth",new Class<?>[0]);
        Button toggle=(Button)get(preview,"drawerToggle");
        require(toggle.getWidth()==14&&toggle.getY()>29,"drawer uses only a small arrow on the panel edge");
        require(toggle.getX()==(int)call(preview,"sideX",new Class<?>[0])-14,"open drawer arrow is attached to the panel's left edge");
        click(preview,toggle);
        require((boolean)get(preview,"sidebarCollapsed")&&(int)call(preview,"sidebarWidth",new Class<?>[0])==0,"collapsing device list removes the floating panel");
        toggle=(Button)get(preview,"drawerToggle");
        require(toggle.getX()==preview.width-20&&preview.children().stream().noneMatch(c->c instanceof Button b&&b.getMessage().equals(StructurePreviewScreen.text("collapse"))),"closed drawer leaves only its arrow at the right edge; no header collapse button");
        click(preview,toggle);
        require(52+(int)call(preview,"viewHeight",new Class<?>[0])<(int)call(preview,"footerTop",new Class<?>[0]),"help, legend and status have a separate footer below the viewport");
        for(var child:preview.children())if(child instanceof Button)require(child.getClass().getSimpleName().equals("BrassButton"),"all preview controls use the fire-control brass widget");
        click(preview,(Button)get(preview,"outlineToggle"));
        require(!(boolean)get(preview,"showDeviceBoxes"),"toolbar button hides bound-device outlines");
        rightClickAxis("YAW");
        require(get(preview,"editing")!=null,"hiding the outline keeps right-click device selection available");
        set(preview,"editing",null);set(preview,"contextButton",null);call(preview,"rebuild",new Class<?>[0]);
        click(preview,(Button)get(preview,"outlineToggle"));
        require((boolean)get(preview,"showDeviceBoxes"),"toolbar button restores bound-device outlines");
        double yaw=(double)get(preview,"yaw"),pitch=(double)get(preview,"pitch"),panX=(double)get(preview,"panX"),panY=(double)get(preview,"panY");
        require(preview.mouseClicked(16,60,1),"empty preview area accepts right-button press");
        preview.mouseDragged(36,70,1,20,10);preview.mouseReleased(36,70,1);
        require((double)get(preview,"yaw")==yaw&&(double)get(preview,"pitch")==pitch&&(double)get(preview,"panX")==panX&&(double)get(preview,"panY")==panY,
                "right-button dragging never rotates or pans the camera");
        require(get(preview,"contextButton")==null,"right-button movement never opens the transparency popup");
        require(preview.mouseClicked(16,60,0)&&preview.mouseDragged(36,70,0,20,10),"left-button drag rotates the structure");preview.mouseReleased(36,70,0);
        require((double)get(preview,"yaw")!=yaw&&(double)get(preview,"pitch")!=pitch,"left-button camera rotation remains active");
        set(preview,"yaw",yaw);set(preview,"pitch",pitch);
    }
    private static void verifyOverlayPriority(Minecraft mc)throws Exception {
        var renderer=(com.hooya.stabilizedturret.client.preview.StructurePreviewRenderer)get(preview,"renderer");
        var outline=(com.mojang.blaze3d.vertex.VertexBuffer)get(renderer,"outline");
        require(outline!=null,"outline mesh exists");outline.bind();
        int bytes=org.lwjgl.opengl.GL15.glGetBufferParameteri(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER,org.lwjgl.opengl.GL15.GL_BUFFER_SIZE);
        var data=org.lwjgl.BufferUtils.createByteBuffer(bytes);org.lwjgl.opengl.GL15.glGetBufferSubData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER,0,data);
        for(int i=15;i<bytes;i+=com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR.getVertexSize())
            require((data.get(i)&255)>0&&(data.get(i)&255)<255,"both colored outline ribbons and borders have translucent GPU alpha");
        com.mojang.blaze3d.vertex.VertexBuffer.unbind();
        var graphics=new net.minecraft.client.gui.GuiGraphics(mc,mc.renderBuffers().bufferSource());
        @SuppressWarnings("unchecked")var bodies=(Map<UUID,Matrix4f>)get(preview,"bodyMatrices");
        int w=(int)call(preview,"viewWidth",new Class<?>[0]),h=(int)call(preview,"viewHeight",new Class<?>[0]);
        renderer.draw(graphics,8,52,w,h,(Matrix4f)call(preview,"camera",new Class<?>[0]),(Matrix4f)call(preview,"projection",new Class<?>[0]),bodies,List.of());
        double scale=mc.getWindow().getGuiScale();var depths=org.lwjgl.BufferUtils.createFloatBuffer(64*64);
        org.lwjgl.opengl.GL11.glReadPixels((int)((8+w/2.0)*scale)-32,mc.getWindow().getHeight()-(int)((52+h/2.0)*scale)-32,64,64,org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT,org.lwjgl.opengl.GL11.GL_FLOAT,depths);
        for(int i=0;i<depths.capacity();i++)require(depths.get(i)>.99999f,"3D preview depth is cleared before GUI overlays draw");
    }
    private static void click(Screen screen,Button button){require(button!=null,"button exists");screen.mouseClicked(button.getX()+button.getWidth()/2.0,button.getY()+button.getHeight()/2.0,0);screen.mouseReleased(button.getX()+button.getWidth()/2.0,button.getY()+button.getHeight()/2.0,0);}
    private static void verifyClosedPreview(Minecraft mc)throws Exception {
        require(mc.screen!=preview&&(boolean)get(preview,"closed"),"closed preview is detached from the active GUI");
        var renderer=get(preview,"renderer");
        require(((Map<?,?>)get(renderer,"meshes")).isEmpty()&&get(renderer,"outline")==null,"close immediately releases section VBOs and outline VBO");
        require(((Map<?,?>)get(get(renderer,"contraptions"),"entries")).isEmpty(),"close releases all captured assembled entity models");
        require(((List<?>)get(get(renderer,"contraptions"),"pending")).isEmpty()&&((List<?>)get(renderer,"movingSections")).isEmpty(),
                "close releases pending entity captures and animated-section refresh queues");
        var worlds=com.hooya.stabilizedturret.client.preview.StructureHullCache.class.getDeclaredField("WORLDS");worlds.setAccessible(true);
        require(((Map<?,?>)worlds.get(null)).isEmpty(),"no sampled section cache remains after close");
        require(((Map<?,?>)get(preview,"completed")).isEmpty()&&((List<?>)get(preview,"queue")).isEmpty()&&get(preview,"state")==null,"close clears retained snapshots and sampling queue");
        var graphics=new net.minecraft.client.gui.GuiGraphics(mc,mc.renderBuffers().bufferSource());
        for(int i=0;i<20;i++){preview.tick();preview.render(graphics,0,0,1);}
        require((int)get(preview,"ticks")==closedScreenTicks&&(int)get(renderer,"refreshTicks")==closedRendererTicks,"closed callbacks perform no ticking, scanning, refresh or draw work");
        require(!com.hooya.stabilizedturret.client.preview.StructurePreviewRenderer.capturingModels(),"normal world rendering never enters preview capture after close");
        require(call(preview,"northArrow",new Class<?>[0])==null,"closed preview never computes a north indicator");
    }
    private static void verifyLargeStructure(Minecraft mc)throws Exception {
        @SuppressWarnings("unchecked")var sections=(Map<?,com.hooya.stabilizedturret.client.preview.StructureHullCache.Section>)get(preview,"completed");
        int blocks=sections.values().stream().mapToInt(s->s.blocks().size()).sum();
        require(blocks==PreviewLargeStructureFixture.BLOCKS,"every block of the 196608-block structure is sampled, including beyond the old 96000 limit: "+blocks);
        var renderer=get(preview,"renderer");@SuppressWarnings("unchecked")var meshes=(Map<?,?>)get(renderer,"meshes");
        require(meshes.size()==sections.size(),"every sampled section is progressively published instead of discarded");
        long vertices=0;int previousBuffer=org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING);
        for(Object mesh:meshes.values())for(String channel:List.of("opaque","context","movingOpaque","movingContext")) {
            var layers=(List<?>)get(mesh,channel);if(layers==null)continue;
            for(Object layer:layers) {
                int count=(int)get(layer,"vertices");vertices+=count;
                var buffer=(com.mojang.blaze3d.vertex.VertexBuffer)get(layer,"buffer");
                org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER,(int)get(buffer,"vertexBufferId"));
                require(org.lwjgl.opengl.GL15.glGetBufferParameteri(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER,org.lwjgl.opengl.GL15.GL_BUFFER_SIZE)
                        ==count*com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize(),"large native geometry exists in the actual GPU buffers");
            }
        }
        org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER,previousBuffer);
        com.mojang.blaze3d.vertex.VertexBuffer.unbind();
        require(vertices>768_000,"large native model exceeds the old scene vertex cutoff: "+vertices);
        var min=sections.values().stream().flatMap(s->s.blocks().stream()).mapToInt(b->b.pos().getX()).min().orElseThrow();
        var max=sections.values().stream().flatMap(s->s.blocks().stream()).mapToInt(b->b.pos().getX()).max().orElseThrow();
        require(max-min+1==PreviewLargeStructureFixture.LENGTH,"both ends of the 256-block structure remain present");
        Files.writeString(Path.of("large-preview-result.txt"),"PASS: blocks="+blocks+", sections="+sections.size()+", GPU vertices="+vertices+", length="+(max-min+1)+"\n");
        StabilizedTurret.LOGGER.info("Large preview complete: blocks={}, sections={}, GPU vertices={}",blocks,sections.size(),vertices);
    }
    private static void verifyNorthDirection()throws Exception {
        double yaw=(double)get(preview,"yaw"),pitch=(double)get(preview,"pitch");
        boolean boxes=(boolean)get(preview,"showDeviceBoxes");int center=(int)get(preview,"centerDirection");
        @SuppressWarnings("unchecked")var matrices=(Map<UUID,Matrix4f>)get(preview,"bodyMatrices");
        var device=(StructurePreviewStatePayload.Device)get(preview,"editing");Matrix4f original=matrices.get(device.body());
        try {
            set(preview,"showDeviceBoxes",false);set(preview,"pitch",90d);
            float[][] expected={{0,-1},{-1,0},{0,1},{1,0}};
            for(int turn=0;turn<4;turn++) {
                set(preview,"yaw",turn*90d);Object arrow=call(preview,"northArrow",new Class<?>[0]);
                require(arrow!=null,"selected yaw keeps its north indicator when device boxes are hidden");
                require(Math.abs((float)get(arrow,"dx")-expected[turn][0])<.001&&Math.abs((float)get(arrow,"dy")-expected[turn][1])<.001,
                        "north follows the four cardinal preview-camera rotations");
            }
            set(preview,"yaw",0d);set(preview,"centerDirection",(center+1)%4);
            Object arrow=call(preview,"northArrow",new Class<?>[0]);
            require((float)get(arrow,"dy")<-.999,"changing the limit center does not move the north reference");
            matrices.put(device.body(),new Matrix4f(original).rotateY((float)(Math.PI/2)));
            arrow=call(preview,"northArrow",new Class<?>[0]);
            require((float)get(arrow,"dx")<-.999,"north uses the selected bearing parent frame, as physical yaw limits do");
            matrices.put(device.body(),original);set(preview,"pitch",0d);
            arrow=call(preview,"northArrow",new Class<?>[0]);
            require(Math.hypot((float)get(arrow,"dx"),(float)get(arrow,"dy"))<.001&&(float)get(arrow,"depth")<-.999,
                    "looking directly north produces the away-facing marker without an arbitrary screen arrow");
        } finally {
            matrices.put(device.body(),original);set(preview,"yaw",yaw);set(preview,"pitch",pitch);
            set(preview,"showDeviceBoxes",boxes);set(preview,"centerDirection",center);
        }
    }
    private static void verifyComplexModels(Minecraft mc)throws Exception {
        var renderer=(com.hooya.stabilizedturret.client.preview.StructurePreviewRenderer)get(preview,"renderer");
        List<String> diagnostics=new ArrayList<>();
        for(var entity:mc.level.entitiesForRendering())if(entity instanceof com.simibubi.create.content.contraptions.AbstractContraptionEntity c)
            diagnostics.add(c.getClass().getSimpleName()+" "+c.position()+" anchor="+(c.getContraption()==null?null:c.getContraption().anchor)+" containing="+dev.ryanhcode.sable.Sable.HELPER.getContaining(c)+" tracking="+dev.ryanhcode.sable.Sable.HELPER.getTrackingOrVehicleSubLevel(c));
        require(renderer.entityCount()>=2,"assembled CBC cannon and Create bearing entity are included in preview: count="+renderer.entityCount()+" entities="+diagnostics);
        @SuppressWarnings("unchecked") var entities=(Map<UUID,Object>)get(get(renderer,"contraptions"),"entries");
        Object cannon=entities.get(PreviewComplexModelsFixture.cannon);
        require(cannon!=null&&get(cannon,"layers")!=null,"production-assembled modeled medium cannon uploads native geometry");
        if(net.neoforged.fml.ModList.get().isLoaded("littletiles")) {
            int tileEntityVertices=0;
            for(Object entry:entities.values())if(get(entry,"entity") instanceof com.simibubi.create.content.contraptions.ControlledContraptionEntity) {
                var layers=(List<?>)get(entry,"layers");if(layers!=null)for(Object layer:layers)tileEntityVertices+=(int)get(layer,"vertices");
            }
            require(tileEntityVertices>=48,"Create entity includes native LittleTiles geometry without installing an idle LT render hook");
        }
        @SuppressWarnings("unchecked") var completed=(Map<Object,com.hooya.stabilizedturret.client.preview.StructureHullCache.Section>)get(preview,"completed");
        for(String kind:List.of("ItemBlockEntity","ShowBlockEntity","WorkspaceBlockEntity","BETiles")) {
            if(kind.equals("WorkspaceBlockEntity")&&!net.neoforged.fml.ModList.get().isLoaded("qmodeling"))continue;
            if(kind.equals("BETiles")&&!net.neoforged.fml.ModList.get().isLoaded("littletiles"))continue;
            var section=completed.values().stream().filter(s->s.blocks().stream().anyMatch(b->{var be=mc.level.getBlockEntity(b.pos());return be!=null&&be.getClass().getSimpleName().equals(kind);})).findFirst().orElseThrow();
            var block=section.blocks().stream().filter(b->{var be=mc.level.getBlockEntity(b.pos());return be!=null&&be.getClass().getSimpleName().equals(kind);}).findFirst().orElseThrow();
            var only=new com.hooya.stabilizedturret.client.preview.StructureHullCache.Section(section.key(),section.fingerprint(),List.of(block),section.states(),section.materials());
            try(var isolated=new com.hooya.stabilizedturret.client.preview.StructurePreviewRenderer()) {
                isolated.publish(List.of(only));
                @SuppressWarnings("unchecked")var meshes=(Map<Object,Object>)get(isolated,"meshes");var mesh=meshes.values().iterator().next();
                var layers=(List<?>)get(mesh,block.opaque()?"movingOpaque":"movingContext");
                require(layers!=null&&!layers.isEmpty(),kind+" uploads nonempty native preview geometry");
                int vertices=0;for(Object layer:layers)vertices+=(int)get(layer,"vertices");
                require(vertices>=24,kind+" retains a complete model");
                if(kind.equals("BETiles")) {
                    var bounds=(AABB)get(layers.getFirst(),"bounds");
                    require(Math.abs(bounds.getXsize()-.25)<.001&&Math.abs(bounds.getYsize()-.5)<.001,"LT quarter-width/half-height geometry remains exact");
                }
            }
        }
        openCannonMenu(true);
    }
    private static void openCannonMenu(boolean verifyToggle)throws Exception {
        var renderer=(com.hooya.stabilizedturret.client.preview.StructurePreviewRenderer)get(preview,"renderer");
        @SuppressWarnings("unchecked")var entities=(Map<UUID,Object>)get(get(renderer,"contraptions"),"entries");
        Object cannon=entities.get(PreviewComplexModelsFixture.cannon);require(cannon!=null,"assembled cannon is available for selection");
        @SuppressWarnings("unchecked")var matrices=(Map<UUID,Matrix4f>)get(preview,"bodyMatrices");
        var model=(Matrix4f)call(cannon,"model",new Class<?>[]{Map.class,float.class},matrices,1f);
        Matrix4f clip=((Matrix4f)call(preview,"projection",new Class<?>[0])).mul((Matrix4f)call(preview,"camera",new Class<?>[0]));
        var layers=(List<?>)get(cannon,"layers");boolean picked=false;
        for(Object layer:layers) {
            float[] p=(float[])get(layer,"positions");
            for(int i=0;i+11<p.length&&!picked;i+=12) {
                Vector3f point=new Matrix4f(clip).mul(model).transformProject(new Vector3f((p[i]+p[i+3]+p[i+6]+p[i+9])/4,(p[i+1]+p[i+4]+p[i+7]+p[i+10])/4,(p[i+2]+p[i+5]+p[i+8]+p[i+11])/4));
                var hit=renderer.pickTarget(clip,matrices,point.x,point.y);
                if(hit==null||!PreviewComplexModelsFixture.cannon.equals(hit.entity()))continue;
                double x=8+(point.x+1)*(int)call(preview,"viewWidth",new Class<?>[0])/2.0,y=52+(1-point.y)*(int)call(preview,"viewHeight",new Class<?>[0])/2.0;
                preview.mouseClicked(x,y,1);preview.mouseReleased(x,y,1);
                var target=(com.hooya.stabilizedturret.client.preview.StructurePreviewRenderer.Target)get(preview,"contextTarget");
                Button popup=(Button)get(preview,"contextButton");
                if(popup==null||target==null||!PreviewComplexModelsFixture.cannon.equals(target.entity()))continue;
                require(popup.getWidth()<96&&popup.getHeight()==16,"transparency menu is compact and fits its text");
                if(verifyToggle) {
                    boolean before=renderer.transparent(target);click(preview,popup);
                    require(before!=renderer.transparent(target),"actual right click on modeled assembled cannon toggles its own transparency");
                    click(preview,popup);set(preview,"contextButton",null);
                }
                picked=true;
            }
        }
        require(picked,"assembled modeled cannon is selectable by its real triangles");
    }
    private static void verifyNativeModels(Minecraft mc)throws Exception{
        @SuppressWarnings("unchecked") var completed=(Map<Object,com.hooya.stabilizedturret.client.preview.StructureHullCache.Section>)get(preview,"completed");
        for(var type:List.of(Blocks.STONE_SLAB,Blocks.OAK_STAIRS,Blocks.OAK_FENCE,Blocks.OAK_TRAPDOOR)) {
            var section=completed.values().stream().filter(s->s.blocks().stream().anyMatch(b->b.state().is(type))).findFirst().orElseThrow();
            var block=section.blocks().stream().filter(b->b.state().is(type)).findFirst().orElseThrow();
            var only=new com.hooya.stabilizedturret.client.preview.StructureHullCache.Section(section.key(),section.fingerprint(),List.of(block),section.states(),section.materials());
            try(var renderer=new com.hooya.stabilizedturret.client.preview.StructurePreviewRenderer()) {
                renderer.publish(List.of(only));
                @SuppressWarnings("unchecked") var meshes=(Map<Object,Object>)get(renderer,"meshes");
                var mesh=meshes.values().iterator().next();
                var layers=(List<?>)get(mesh,block.opaque()?"opaque":"context");
                require(layers!=null&&!layers.isEmpty(),"native model uploads for "+type);
                boolean detail=false,uv=false;float minY=Float.POSITIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY;
                for(var layer:layers) {
                    var buffer=(com.mojang.blaze3d.vertex.VertexBuffer)get(layer,"buffer");buffer.bind();
                    int bytes=org.lwjgl.opengl.GL15.glGetBufferParameteri(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER,org.lwjgl.opengl.GL15.GL_BUFFER_SIZE);
                    var data=org.lwjgl.BufferUtils.createByteBuffer(bytes);
                    org.lwjgl.opengl.GL15.glGetBufferSubData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER,0,data);
                    int stride=com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize();
                    for(int offset=0;offset+stride<=bytes;offset+=stride){
                        for(int axis=0;axis<3;axis++){float value=data.getFloat(offset+axis*4);detail|=Math.abs(value-Math.round(value))>.001;}
                        float y=data.getFloat(offset+4)-(block.pos().getY()-section.key().origin().getY());minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                        uv|=data.getFloat(offset+12)>0||data.getFloat(offset+16)>0;
                    }
                    com.mojang.blaze3d.vertex.VertexBuffer.unbind();
                }
                require(detail&&uv,"GPU mesh retains fractional model contours and atlas UVs for "+type);
                if(type==Blocks.STONE_SLAB)require(Math.abs(minY)<.001&&Math.abs(maxY-.5)<.001,"bottom slab remains exactly half height in uploaded geometry");
                if(type==Blocks.OAK_TRAPDOOR)require(maxY-minY<.2,"closed trapdoor keeps its thin profile");
            }
        }
        @SuppressWarnings("unchecked") var meshes=(Map<Object,Object>)get(get(preview,"renderer"),"meshes");
        require(meshes.values().stream().anyMatch(mesh->{try{return get(mesh,"movingOpaque")!=null;}catch(Exception e){throw new RuntimeException(e);}}),"ordinary bearing partial models survive Flywheel fallback capture");
        require(!com.hooya.stabilizedturret.client.preview.StructurePreviewRenderer.capturingModels(),"native visualization fallback is scoped only to preview capture");
        var section=completed.values().stream().filter(s->s.blocks().stream().anyMatch(b->b.state().is(Blocks.STONE_SLAB))).findFirst().orElseThrow();
        var slab=section.blocks().stream().filter(b->b.state().is(Blocks.STONE_SLAB)).findFirst().orElseThrow();
        var materials=(com.hooya.stabilizedturret.client.preview.StructurePreviewMaterials)get(preview,"materials");
        var sub=dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level).getSubLevel(section.key().body());
        mc.level.setBlock(slab.pos(),slab.state().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE,net.minecraft.world.level.block.state.properties.SlabType.TOP),2);
        try {
            var changed=com.hooya.stabilizedturret.client.preview.StructureHullCache.sample(mc.level,sub,section.key(),materials);
            require(changed.fingerprint()!=section.fingerprint(),"changing slab shape invalidates cache even with identical occupancy/material");
        } finally {mc.level.setBlock(slab.pos(),slab.state(),2);}
    }
    private static void rightClickAxis(String role)throws Exception{
        var state=(StructurePreviewStatePayload)get(preview,"state");var d=state.devices().stream().filter(v->v.role().equals(role)).findFirst().orElseThrow();
        Matrix4f projection=(Matrix4f)call(preview,"projection",new Class<?>[0]);Matrix4f camera=(Matrix4f)call(preview,"camera",new Class<?>[0]);
        Matrix4f model=(Matrix4f)call(preview,"boxMatrix",new Class<?>[]{StructurePreviewStatePayload.Device.class},d);
        Vector3f screen=projection.mul(camera).mul(model).transformProject(new Vector3f(.5f,.5f,.5f));
        int width=(int)call(preview,"viewWidth",new Class<?>[0]),height=(int)call(preview,"viewHeight",new Class<?>[0]);
        preview.mouseClicked(8+(screen.x+1)*width/2,52+(1-screen.y)*height/2,1);
        require(get(preview,"contextButton")==null,"right-click bound device never opens the transparency popup");
    }
    private static Button button(Screen screen,String key){String label=StructurePreviewScreen.text(key).getString();return screen.children().stream().filter(c->c instanceof Button b&&b.getMessage().getString().equals(label)).map(c->(Button)c).findFirst().orElse(null);}
    private static Object get(Object o,String key)throws Exception{var f=o.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String key,Object value)throws Exception{var f=o.getClass().getDeclaredField(key);f.setAccessible(true);f.set(o,value);}
    private static Object call(Object o,String name,Class<?>[] types,Object...args)throws Exception{var m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    private static void capture(Minecraft mc,String name)throws Exception{Files.createDirectories(Path.of("structure-preview-qa"));try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(Path.of("structure-preview-qa",name));}}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void finish(Minecraft mc,Throwable failure){done=true;if(failure!=null)StabilizedTurret.LOGGER.error("STRUCTURE PREVIEW CHECK FAILED",failure);
        try{Files.writeString(Path.of("structure-preview-result.txt"),failure==null?"PASS: opaque fire-control equipment, translucent chassis VBOs, opaque articulated turret/barrel bodies; both entries, right-click edits, device isolation, persistence, compact scrolling, hidden world entries, real physical pitch offset/head visibility\n":"FAIL: "+failure);}
        catch(Exception e){throw new RuntimeException(e);}finally{mc.stop();}}
}
