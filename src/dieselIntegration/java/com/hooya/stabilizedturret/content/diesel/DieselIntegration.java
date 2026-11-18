package com.hooya.stabilizedturret.content.diesel;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.controller.*;
import com.hooya.stabilizedturret.content.turbine.*;
import com.hooya.stabilizedturret.registry.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import edn.stratodonut.drivebywire.compat.ControllerSignalStore;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.Shapes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Opt-in transformed Create/Sable server checks with a test-only remote signal provider. */
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID)
public final class DieselIntegration {
    private static final List<Fixture> FIXTURES=new ArrayList<>();
    private static final Map<Fixture,float[]> PREVIOUS_SPEEDS=new HashMap<>();
    private static final Map<Fixture,Float> PREVIOUS_SOUND_RPM=new HashMap<>();
    private static final List<String> KEYS=List.of("buttonA","buttonB","buttonX","buttonY","buttonGuide");
    private static final String[][] INPUTS={{"buttonGuide"},{"buttonA"},{"buttonA","buttonX"},{"buttonA"},
            {"buttonA","buttonY"},{"buttonA"},{"buttonB"},{"buttonB","buttonX"},{"buttonB"},
            {"buttonB","buttonY"},{"buttonB"},{"buttonX"},{"buttonY"},{"buttonA","buttonB"},{},{"buttonGuide"},
            {"buttonGuide","buttonA"},{"buttonA","buttonX"},{"buttonA"},{"buttonB"},{},{"buttonA"},{"buttonGuide"}};
    private static final float[][] EXPECTED={{0,0},{256,256},{0,256},{256,256},
            {256,0},{256,256},{-256,-256},{-256,0},{-256,-256},
            {0,-256},{-256,-256},{-128,128},{128,-128},{0,0},{0,0},{0,0},
            {256,256},{0,256},{256,256},{-256,-256},{0,0},{256,256},{0,0}};
    private static ServerLevel level;
    private static int age,stage;
    private static boolean finished;
    private static final boolean FOOTPRINT_ONLY=Boolean.getBoolean("create_fire_control.dieselFootprintOnly");
    private record Fixture(BlockPos center, Direction facing, boolean sable, boolean turbine) {}

    @EventBusSubscriber(modid=StabilizedTurret.MOD_ID,bus=EventBusSubscriber.Bus.MOD)
    public static final class RegistryFixture {
        @SubscribeEvent public static void register(net.neoforged.neoforge.registries.RegisterEvent event) {
            event.register(Registries.BLOCK, helper -> helper.register(
                    ResourceLocation.fromNamespaceAndPath("drivebywire","tweaked_controller_hub"),
                    new Block(net.minecraft.world.level.block.state.BlockBehaviour.Properties.of())));
        }
    }
    @SubscribeEvent public static void start(ServerStartedEvent event) {
        level=event.getServer().overworld();
        try {
            level.setDayTime(6000);
            for (int i=0;i<4;i++) {
                Direction facing=new Direction[]{Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST}[i];
                place(new Fixture(new BlockPos(i*16,240,0),facing,false,false));
                BlockPos anchor=new BlockPos(i*16,220,40);
                level.setBlockAndUpdate(anchor,Blocks.IRON_BLOCK.defaultBlockState());
                var assembly=dev.simulated_team.simulated.util.SimAssemblyHelper.assembleFromSingleBlock(
                        level,anchor.below(),anchor,false,false);
                require(assembly!=null,"real Sable assembly");
                SubLevel body=assembly.subLevel();
                ((dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer)SubLevelContainer.getContainer(level)).addForceLoadTicket(
                        (dev.ryanhcode.sable.sublevel.ServerSubLevel)body,
                        dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,
                        net.minecraft.util.Unit.INSTANCE);
                BlockPos plot=anchor.offset(assembly.offset()).above(2);
                level.setBlockAndUpdate(plot.below(),Blocks.IRON_BLOCK.defaultBlockState());
                body.logicalPose().orientation().rotationY(.6).rotateZ(.2);
                place(new Fixture(plot,facing,true,false));
                body.forceUpdateGlobalBounds();
            }
            if(FOOTPRINT_ONLY) { finish(null); return; }
            place(new Fixture(new BlockPos(80,240,0),Direction.NORTH,false,true));
            setInputs();
        } catch(Throwable error) { finish(error); }
    }
    private static void place(Fixture f) {
        level.removeBlock(f.center,false);
        if (!f.sable) {
            for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++)
                level.setChunkForced((f.center.getX()>>4)+dx,(f.center.getZ()>>4)+dz,true);
        }
        BlockState state=f.turbine ? ModBlocks.GAS_TURBINE.get().defaultBlockState().setValue(GasTurbineBlock.FACING,f.facing)
                : ModBlocks.ZTZ99A_DIESEL_ENGINE.get().defaultBlockState().setValue(Ztz99aDieselEngineBlock.FACING,f.facing);
        if(FOOTPRINT_ONLY) {
            for(var part:Ztz99aDieselEnginePart.values()) if(!part.isOccupied())
                level.setBlock(f.center.offset(Ztz99aDieselEngineBlock.offset(part,f.facing)),Blocks.STONE.defaultBlockState(),3);
            var context=new net.minecraft.world.item.context.DirectionalPlaceContext(level,f.center,
                    f.facing.getOpposite(),new ItemStack(state.getBlock()),Direction.UP);
            require(((Ztz99aDieselEngineBlock)state.getBlock()).getStateForPlacement(context)!=null,
                    "engine placement permits blocks in all thirteen visual-only cells");
        }
        require(level.setBlock(f.center,state,3),"controller placement "+f.center);
        state.getBlock().setPlacedBy(level,f.center,state,null,ItemStack.EMPTY);
        GasTurbineBlockEntity engine=engine(f);
        require(engine!=null,"controller fixture");
        if(FOOTPRINT_ONLY) { checkFootprint(f,state,(Ztz99aDieselEngineBlockEntity)engine); return; }
        level.setBlock(f.center.below(),Blocks.IRON_BLOCK.defaultBlockState(),3);
        level.setBlock(f.center.below(2),Blocks.IRON_BLOCK.defaultBlockState(),3);
        BlockPos hub=f.center.below(3);
        level.setBlock(hub,BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(
                "drivebywire","tweaked_controller_hub")).defaultBlockState(),3);
        require(engine.bindAdvancedHub(BindingRef.capture(level,hub)),"advanced hub binding");
        require(engine.applySettings(settings()),"settings fixture");
        for (int side : new int[]{-1,1}) {
            BlockPos port=port(f,side);
            Direction out=side<0 ? f.facing.getCounterClockWise() : f.facing.getClockWise();
            level.setBlock(port.relative(out),AllBlocks.SHAFT.get().defaultBlockState()
                    .setValue(BlockStateProperties.AXIS,out.getAxis()),3);
            // A second ordinary shaft followed by a gearbox exercises native connection rules.
            level.setBlock(port.relative(out,2),AllBlocks.GEARBOX.get().defaultBlockState()
                    .setValue(BlockStateProperties.AXIS,Direction.Axis.Y),3);
            level.setBlock(port.relative(out,3),AllBlocks.ENCASED_FAN.get().defaultBlockState()
                    .setValue(BlockStateProperties.FACING,out),3);
        }
        FIXTURES.add(f);
        if (!f.turbine) {
            require((Sable.HELPER.getContaining(engine)!=null)==f.sable,"Sable ownership");
            var combined=Shapes.empty();
            int solidCells=0;
            for (var part : Ztz99aDieselEnginePart.values()) {
                BlockPos cell=f.center.offset(Ztz99aDieselEngineBlock.offset(part,f.facing));
                BlockState cellState=level.getBlockState(cell);
                if(!part.isOccupied()) { require(cellState.isAir(),"visual-only cells are free world space"); continue; }
                require(cellState.getValue(Ztz99aDieselEngineBlock.PART)==part,"all mesh cells");
                require(Ztz99aDieselEngineBlock.controllerPos(cell,cellState).equals(f.center),"rotated controller lookup");
                var shape=((Ztz99aDieselEngineBlock)cellState.getBlock()).getSubLevelCollisionShape(level,cellState);
                require(shape.toAabbs().equals(cellState.getCollisionShape(level,cell).toAabbs()),"entity and Sable collision agree");
                if(part.isOccupied()) {
                    require(shape.toAabbs().equals(Shapes.block().toAabbs()),"body and transmission cells have full collision");
                    var offset=Ztz99aDieselEngineBlock.offset(part,f.facing);
                    combined=Shapes.or(combined,shape.move(offset.getX(),offset.getY(),offset.getZ()));
                    solidCells++;
                } else require(shape.isEmpty(),"outer and upper model slices have no physical collision");
                for (Direction d : Direction.values()) require(((Ztz99aDieselEngineBlock)cellState.getBlock())
                        .hasShaftTowards(level,cell,cellState,d)==(d==Ztz99aDieselEngineBlock.outputDirection(cellState)),"shaft face");
            }
            var bounds=combined.bounds();
            require(solidCells==5,"T-shaped collision body has five solid cells");
            require(bounds.minY==0 && bounds.maxY==1,"one-block collision height");
            require(bounds.getXsize()==3 && bounds.getZsize()==3,"rotated T-shaped collision bounds");
            StabilizedTurret.LOGGER.info("DIESEL COLLISION {} sable={} PASS: {}",f.facing,f.sable,bounds);
        }
    }
    private static void checkFootprint(Fixture f,BlockState state,Ztz99aDieselEngineBlockEntity engine) {
        var block=(Ztz99aDieselEngineBlock)state.getBlock();
        int count=0; BlockPos preserved=null;
        for(var part:Ztz99aDieselEnginePart.values()) {
            BlockPos cell=f.center.offset(Ztz99aDieselEngineBlock.offset(part,f.facing));
            var actual=level.getBlockState(cell);
            if(part.isOccupied()) {
                require(actual.is(block) && actual.getValue(Ztz99aDieselEngineBlock.PART)==part,"physical T cell present");
                require(actual.getCollisionShape(level,cell).toAabbs().equals(Shapes.block().toAabbs()),"physical cell collider");
                count++;
            } else {
                require(actual.is(Blocks.STONE),"placement leaves surrounding player blocks intact");
                if(preserved==null) preserved=cell;
                else level.setBlock(cell,state.setValue(Ztz99aDieselEngineBlock.PART,part),3);
            }
        }
        require(count==5,"exactly five actual engine blocks");
        // Simulate the normal load initialization of an old engine with twelve remaining placeholders.
        engine.initialize();
        require(level.getBlockEntity(f.center)==engine && !engine.isRemoved(),"legacy cleanup preserves controller");
        for(var part:Ztz99aDieselEnginePart.values()) {
            BlockPos cell=f.center.offset(Ztz99aDieselEngineBlock.offset(part,f.facing));
            if(!part.isOccupied()) {
                require(cell.equals(preserved)?level.getBlockState(cell).is(Blocks.STONE):level.getBlockState(cell).isAir(),
                        "legacy cleanup frees only own placeholders");
                require(level.setBlock(cell,Blocks.STONE.defaultBlockState(),3) || cell.equals(preserved),
                        "ordinary block can occupy old model position");
            } else if(part.isOutput()) require(level.getBlockEntity(cell) instanceof Ztz99aDieselEngineBlockEntity,
                    "transmission block entity survives cleanup");
        }
        require(!engine.isRemoved() && level.getBlockEntity(f.center)==engine,"placing surrounding blocks keeps engine");
        StabilizedTurret.LOGGER.info("DIESEL FOOTPRINT {} sable={} PASS: 5 engine blocks, 13 usable cells, safe old-layout cleanup",f.facing,f.sable);
    }
    private static GasTurbineBlockEntity engine(Fixture f) {
        return (GasTurbineBlockEntity)level.getBlockEntity(f.turbine ? f.center.relative(f.facing.getOpposite()) : f.center);
    }
    private static BlockPos port(Fixture f,int side) {
        return f.turbine ? f.center.relative(f.facing.getOpposite()) : f.center.relative(
                side<0 ? f.facing.getCounterClockWise() : f.facing.getClockWise());
    }
    private static void setInputs() {
        if(age==0 && (stage==16 || stage==19)) for(var fixture:FIXTURES)
            require(engine(fixture).applySettings(settings()),"apply explicit or instant-rate settings");
        Map<String,Integer> signals=new HashMap<>();
        for(String key:INPUTS[stage]) if(!(stage==16 && age==0 && key.equals("buttonGuide"))) signals.put(key,15);
        ControllerSignalStore.signals=signals;
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(level==null || finished)return;
        try {
            ++age;
            if(stage==16 && age==1) setInputs(); // Release the preceding stop key for one tick before restarting.
            checkContinuousOutputs();
            if (age<35) return;
            for (Fixture f:FIXTURES) {
                GasTurbineBlockEntity control=engine(f);
                require(control!=null && !control.isRemoved(),"engine survives speed changes "+f
                        +" state="+level.getBlockState(f.center)+" control="+control);
                float[] expected=EXPECTED[stage];
                close(control.getCurrentLeftSpeed(),expected[0],"left control "+stage+" "+f);
                close(control.getCurrentRightSpeed(),expected[1],"right control "+stage+" "+f);
                close(control.getSoundMaxRpm(),512,"independent audio maximum");
                close(control.getSoundRpmAcceleration(),64,"independent audio acceleration");
                close(control.getSoundRpmDeceleration(),80,"independent audio deceleration");
                close(control.getSoundRpmMagnitude(),Math.abs(control.getSimulatedSoundRpm()),"sound source uses simulation");
                if(stage==1) require(control.getSimulatedSoundRpm()>0 && control.getSimulatedSoundRpm()<150,
                        "audio rises slowly while physical shafts reach 256 RPM");
                if(stage==6) require(control.getSimulatedSoundRpm()>0,
                        "reverse behavior retains current sound RPM while decelerating toward negative target");
                if(stage==11 || stage==12) require(control.getSimulatedSoundRpm()<0,
                        "either reversing side yields negative sound RPM");
                if(stage==13) require(Math.abs(control.getSimulatedSoundRpm())>20,
                        "zero-output behavior still drives independent audio while shafts are stopped");
                if(stage==15) close(control.getSimulatedSoundRpm(),0,"engine stop resets sound simulation");
                for (int side:new int[]{-1,1}) {
                    Direction out=side<0 ? f.facing.getCounterClockWise() : f.facing.getClockWise();
                    BlockPos shaft=port(f,side).relative(out);
                    KineticBlockEntity be=(KineticBlockEntity)level.getBlockEntity(shaft);
                    require(be!=null && !be.isRemoved(),"shaft survives propagation");
                    // Create expresses both ports in the common axis sign convention.
                    float sign=KineticBlockEntity.convertToDirection(1,f.facing.getCounterClockWise());
                    close(be.getSpeed(),expected[side<0?0:1]*sign,"shaft RPM "+stage+" "+f+" "+side);
                    KineticBlockEntity gearbox=(KineticBlockEntity)level.getBlockEntity(shaft.relative(out));
                    require(gearbox!=null && !gearbox.isRemoved(),"gearbox survives propagation");
                    close(Math.abs(gearbox.getSpeed()),Math.abs(be.getSpeed()),"gearbox RPM");
                    if(!f.turbine) close(((Ztz99aDieselEngineBlockEntity)level.getBlockEntity(port(f,side)))
                            .calculateAddedStressCapacity(),(float)com.simibubi.create.api.stress.BlockStressValues.getCapacity(
                                    com.simibubi.create.AllBlocks.CREATIVE_MOTOR.get()),"creative motor SU capacity");
                }
                if(stage==2) checkPersistence(f,control);
                if (!f.turbine && Math.max(Math.abs(expected[0]),Math.abs(expected[1])) > 0) {
                    // Real Create consumers: changing RPM does not replace measured stress/capacity.
                    float leftLoad = ((Ztz99aDieselEngineBlockEntity)level.getBlockEntity(port(f,-1))).calculateEngineLoad();
                    float rightLoad = ((Ztz99aDieselEngineBlockEntity)level.getBlockEntity(port(f,1))).calculateEngineLoad();
                    close(control.calculateEngineLoad(),Math.max(leftLoad,rightLoad),"actual independent SU ratios");
                    require(control.calculateEngineLoad()>0 && control.calculateEngineLoad()<=1,"fan SU load is audible");
                }
            }
            StabilizedTurret.LOGGER.info("DIESEL CHECK stage {} PASS",stage);
            age=0;
            if(++stage==INPUTS.length) {
                for(Fixture f:FIXTURES) {
                    level.destroyBlock(port(f,-1),false);
                    if(!f.turbine) for(var part:Ztz99aDieselEnginePart.values())
                        require(!level.getBlockState(f.center.offset(Ztz99aDieselEngineBlock.offset(part,f.facing)))
                                .is(ModBlocks.ZTZ99A_DIESEL_ENGINE.get()),"no leftover cells");
                }
                finish(null);
            } else setInputs();
        } catch(Throwable error) { finish(error); }
    }
    private static void checkPersistence(Fixture f,GasTurbineBlockEntity engine) {
        var tag=engine.saveWithFullMetadata(level.registryAccess());
        GasTurbineBlockEntity restored=(GasTurbineBlockEntity)BlockEntity.loadStatic(engine.getBlockPos(),engine.getBlockState(),tag,level.registryAccess());
        require(restored!=null && restored.getType()==engine.getType(),"independent BE type restoration");
        restored.setLevel(level);
        require(restored.getControlSettings().equals(settings()) && restored.isEngineEnabled(),"NBT behaviors, independent rates, toggle and enabled");
        require(restored.hasValidAdvancedHub(),"NBT hub reference");
        close(restored.getSoundVolume(),150,"NBT volume");
        close(restored.getSimulatedSoundRpm(),engine.getSimulatedSoundRpm(),"NBT signed audio state");
        require(!restored.applySettings(new EngineControlSettings(150,"invalid-signal",settings().behaviors())),"reject invalid remote mapping");
    }
    private static void checkContinuousOutputs() {
        for (Fixture f:FIXTURES) {
            var engine=engine(f);
            require(engine!=null && !engine.isRemoved(),"engine survives every transition tick");
            float[] current={engine.getCurrentLeftSpeed(),engine.getCurrentRightSpeed()};
            var previous=PREVIOUS_SPEEDS.put(f,current);
            Float previousAudio=PREVIOUS_SOUND_RPM.put(f,engine.getSimulatedSoundRpm());
            if(engine.isEngineEnabled() && previousAudio!=null) {
                require(Math.abs(engine.getSimulatedSoundRpm()-previousAudio)<=4.01F,
                        "audio continues at its own separate rates across all behavior transitions");
                if(stage==1 && age>2) close(engine.getSimulatedSoundRpm(),previousAudio+3.2F,
                        "audio accelerates at 64 RPM/s independently of output");
                if(stage==6 && age>2 && previousAudio>0) close(engine.getSimulatedSoundRpm(),previousAudio-4,
                        "audio brakes at 80 RPM/s before reverse acceleration");
            }
            if (!engine.isEngineEnabled() || previous==null) continue;
            require(engine.calculateAddedStressCapacity()>64,"engine capacity is upgraded beyond its old 64 SU/RPM");
            if(age==2 && stage==2) close(current[0],204.8F,"default A to B uses outgoing A's 512 RPM/s braking");
            if(age==2 && stage==3) close(current[0],25.6F,"default B to A uses outgoing B's 256 RPM/s acceleration");
            if(age==2 && stage==4) close(current[1],179.2F,"right-side switch uses outgoing A's 768 RPM/s braking");
            if(age==2 && stage==5) close(current[1],38.4F,"right-side fallback uses outgoing chord's 384 RPM/s acceleration");
            if(age==2 && stage==17) close(current[0],230.4F,"B's explicit entry overrides default A and uses B's 256 RPM/s braking");
            if(age==2 && stage==18) close(current[0],25.6F,"explicit entry does not change B's default departure");
            if(age==1 && stage>=19) {
                close(current[0],EXPECTED[stage][0],"instant left output in first tick");
                close(current[1],EXPECTED[stage][1],"instant right output in first tick");
            }
            for(int side=0;side<2;side++) {
                if(stage<19) require(Math.abs(current[side]-previous[side])<=102.41F,"continuous rate-limited physical output");
                if(stage>0 && EXPECTED[stage-1][side]*EXPECTED[stage][side]>0)
                    require(current[side]*EXPECTED[stage][side]>0,"same-direction side never forced to zero during another side's reversal");
            }
        }
    }
    private static EngineControlSettings settings() {
        var rules = new ArrayList<EngineDriveBehavior>();
        rules.add(rule(List.of(KEYS.get(0)),256,256));
        rules.add(rule(List.of(KEYS.get(1)),-256,-256));
        rules.add(rule(List.of(KEYS.get(2)),-128,128));
        rules.add(rule(List.of(KEYS.get(3)),128,-128));
        rules.add(rule(List.of(KEYS.get(0),KEYS.get(2)),0,256));
        rules.add(rule(List.of(KEYS.get(0),KEYS.get(3)),256,0));
        rules.add(rule(List.of(KEYS.get(1),KEYS.get(2)),-256,0));
        rules.add(rule(List.of(KEYS.get(1),KEYS.get(3)),0,-256));
        rules.add(rule(List.of(KEYS.get(0),KEYS.get(1)),0,0));
        return new EngineControlSettings(150,KEYS.get(4),rules,true,64,80,512);
    }
    private static EngineDriveBehavior rule(List<String> keys,float left,float right) {
        float leftRate=stage>=19?1024:keys.size()>1?256:512,rightRate=stage>=19?1024:keys.size()>1?384:768;
        boolean explicit=stage>=16 && keys.equals(List.of("buttonA","buttonX"));
        return new EngineDriveBehavior(keys,left,leftRate,leftRate,right,rightRate,rightRate,
                explicit?com.hooya.stabilizedturret.content.turbine.EngineTransitionRule.INCOMING
                        :com.hooya.stabilizedturret.content.turbine.EngineTransitionRule.DEFAULT,
                com.hooya.stabilizedturret.content.turbine.EngineTransitionRule.DEFAULT);
    }
    private static void close(float actual,float expected,String label) { require(Math.abs(actual-expected)<.01F,label+": "+actual+" != "+expected); }
    private static void require(boolean value,String label) { if(!value)throw new AssertionError(label); }
    private static void finish(Throwable error) {
        finished=true;
        if(error!=null) StabilizedTurret.LOGGER.error("DIESEL INTEGRATION FAILED",error);
        try { Files.writeString(Path.of("diesel-integration-result.txt"),error==null?"PASS":"FAIL: "+error); }
        catch(Exception failure) { throw new RuntimeException(failure); }
        finally { level.getServer().halt(false); }
    }
}
