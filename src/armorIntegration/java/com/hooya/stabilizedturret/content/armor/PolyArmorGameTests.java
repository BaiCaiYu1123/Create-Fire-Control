package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.*;
import com.caca.copypoly.geometry.Polyhedron;
import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.cannon.munition.StabilizedApfsdsProjectile;
import com.hooya.stabilizedturret.registry.ModCbcContent;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.companion.math.*;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import rbasamoyai.createbigcannons.block_armor_properties.BlockArmorPropertiesHandler;
import rbasamoyai.createbigcannons.config.CBCCfgMunitions.GriefState;
import rbasamoyai.createbigcannons.munitions.*;
import org.joml.*;
import java.util.List;

@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid=StabilizedTurret.MOD_ID,bus=EventBusSubscriber.Bus.MOD)
public final class PolyArmorGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event){event.register(PolyArmorGameTests.class);event.register(CannonBoreGameTests.class);event.register(ArmorSeamGameTests.class);event.register(EquivalentArmorGameTests.class);event.register(AutocannonHoleGameTests.class);}
    @SubscribeEvent public static void registerCompositeArmor(RegisterGameTestsEvent event) {
        event.register(CompositeArmorGameTests.class);
    }
    private static final BlockPos P=new BlockPos(2,3,2);
    private static PolyBlockEntity armor(GameTestHelper h){h.setBlock(P,Copypoly.POLY.get());return h.getBlockEntity(P);}
    private static final class ProbeDart extends StabilizedApfsdsProjectile {
        ProbeDart(net.minecraft.world.level.Level level){super(ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get(),level);}
        ImpactResult impact(BlockHitResult hit){return calculateBlockPenetration(new ProjectileContext(this,GriefState.ALL_DAMAGE),level().getBlockState(hit.getBlockPos()),hit);}
    }
    private static ProbeDart dart(GameTestHelper h,Vec3 entry,Vec3 direction){var shot=new ProbeDart(h.getLevel());shot.setPos(entry.subtract(direction.normalize().scale(.5)));shot.setDeltaMovement(direction.normalize().scale(20));shot.setProjectileMass(200);return shot;}
    private static void close(GameTestHelper h,double expected,double actual,String message){h.assertTrue(java.lang.Math.abs(expected-actual)<1e-6,message+": "+expected+" != "+actual);}
    @GameTest(template="empty",templateNamespace="copypoly",batch="armor_terminal_traits")
    public static void terminalMaterialTraitsIgnoreArmorVolumeDamageAndRefreshWithMaterial(GameTestHelper h){
        if(!ModList.get().isLoaded("cbc_terminal_ballistics")){h.succeed();return;}
        var be=armor(h);var level=h.getLevel();var pos=h.absolutePos(P);var reference=pos.east(2);
        be.setMaterial(Blocks.ANCIENT_DEBRIS.defaultBlockState());level.setBlockAndUpdate(reference,Blocks.ANCIENT_DEBRIS.defaultBlockState());
        var full=com.cbc_terminal_ballistics.debug.InspectionSnapshot.build(level,reference,null);
        h.assertTrue(full.armorToughness()>=10,"Fixture needs a metallic CBC fallback material");
        for(int divisor:new int[]{1,2,160}){
            be.setPoly(Polyhedron.of(Polyhedron.CUBE_POINTS.stream().map(p->new Polyhedron.Point(p.x(),p.y(),p.z()/divisor)).toList()));
            var view=com.cbc_terminal_ballistics.debug.InspectionSnapshot.build(level,pos,null);
            close(h,full.armorToughness()/divisor,view.armorToughness(),"CBC toughness stopped scaling with volume");
            close(h,full.armorHardness()/divisor,view.armorHardness(),"CBC hardness stopped scaling with volume");
            TerminalTraits.same(h,full,view);h.assertTrue(view.materialId().equals(full.materialId()),"Inspector did not resolve the copied material");
        }
        var damage=((PolyArmorAccess)be).fireControl$armorDamage();damage.add(ArmorHole.along(new Vec3(.5,.5,0),new Vec3(0,0,1)));
        TerminalTraits.same(h,full,com.cbc_terminal_ballistics.debug.InspectionSnapshot.build(level,pos,null));
        be.setMaterial(Blocks.STONE.defaultBlockState());level.setBlockAndUpdate(reference,Blocks.STONE.defaultBlockState());
        var stone=com.cbc_terminal_ballistics.debug.InspectionSnapshot.build(level,reference,null);
        h.assertTrue(stone.ductility()!=full.ductility(),"Fixture needs distinct material traits");
        TerminalTraits.same(h,stone,com.cbc_terminal_ballistics.debug.InspectionSnapshot.build(level,pos,null));h.succeed();
    }
    // Keep optional CTB types out of the methods reflected by GameTest registration.
    private static final class TerminalTraits {
        private static void same(GameTestHelper h,com.cbc_terminal_ballistics.debug.InspectionSnapshot full,com.cbc_terminal_ballistics.debug.InspectionSnapshot armor){
            close(h,full.ductility(),armor.ductility(),"Copied ductility changed with armor volume");
            close(h,full.brittleness(),armor.brittleness(),"Copied brittleness changed with armor volume");
            close(h,full.spallMultiplier(),armor.spallMultiplier(),"Copied spall multiplier changed with armor volume");
        }
    }
    @GameTest(template="empty",templateNamespace="copypoly",batch="armor_terminal_traits")
    public static void terminalActualSpallUsesTheFullCopiedMaterialMultiplier(GameTestHelper h){
        if(!ModList.get().isLoaded("cbc_terminal_ballistics")){h.succeed();return;}
        var level=h.getLevel();var pos=new BlockPos(624,260,256);level.setBlockAndUpdate(pos,Copypoly.POLY.get().defaultBlockState());
        var be=(PolyBlockEntity)level.getBlockEntity(pos);var material=Blocks.ANCIENT_DEBRIS.defaultBlockState();be.setMaterial(material);
        be.setPoly(Polyhedron.of(Polyhedron.CUBE_POINTS.stream().map(p->new Polyhedron.Point(p.x(),p.y(),p.z()/160)).toList()));
        var full=BlockArmorPropertiesHandler.getProperties(material).toughness(level,material,pos,true);
        var scaled=BlockArmorPropertiesHandler.getProperties(be.getBlockState()).toughness(level,be.getBlockState(),pos,true);
        h.assertTrue(full>=10&&scaled<10,"Fixture does not cross the CTB fallback boundary");
        var stats=com.cbc_terminal_ballistics.data.MaterialManager.INSTANCE.get(material,full);var entry=Vec3.atLowerCornerOf(pos).add(.5,.5,0);var shot=dart(h,entry,new Vec3(0,0,1));
        double count=com.cbc_terminal_ballistics.config.TBConfig.GLOBAL_SPALL_MULTIPLIER.get()*stats.spallMultiplier()
                *com.cbc_terminal_ballistics.ballistics.ProjectileClassifier.classify(shot,false).spallScale
                *com.cbc_terminal_ballistics.ballistics.ProjectileClassifier.shellSpallCountModifier(shot);
        int expected=(int)java.lang.Math.min(java.lang.Math.round(scaled*count),com.cbc_terminal_ballistics.config.TBConfig.MAX_SPALL_FRAGMENTS.get());
        h.assertTrue(expected>0,"Fixture needs observable spall");shot.impact(new BlockHitResult(entry,Direction.NORTH,pos,false));
        var impact=com.cbc_terminal_ballistics.ballistics.TBImpactService.lastImpact(level,pos);
        h.assertTrue(impact!=null&&impact.outcome().equals("PENETRATE"),"CTB did not actually penetrate the thin armor");
        h.assertTrue(impact.spallFragments()==expected,"Actual CTB spall used scaled/default material traits: "+impact.spallFragments()+" != "+expected);h.succeed();
    }
    @GameTest(template="empty",templateNamespace="copypoly")
    public static void clientReplicaCannotShareTheServerProjectileContactHistory(GameTestHelper h){
        var first=ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get().create(h.getLevel());var replica=ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get().create(h.getLevel());replica.setId(first.getId());var pos=h.absolutePos(P);
        h.assertTrue(first.equals(replica),"Fixture did not reproduce Entity's numeric-ID equality");ArmorProjectilePassage.allow(first,pos);
        h.assertTrue(ArmorProjectilePassage.ignores(first,pos),"Penetrating projectile forgot its contact");h.assertTrue(!ArmorProjectilePassage.ignores(replica,pos),"Client/server replicas share predicted passage data");h.succeed();
    }
    @GameTest(template="empty",templateNamespace="copypoly",batch="armor_flight")
    public static void realTickCrossesTwoArmorLayersAndHitsTheRearTarget(GameTestHelper h){
        var level=h.getLevel();var first=new BlockPos(608,260,256);var second=first.south(2);var rear=first.south(5);
        for(int z=-3;z<=48;z++)for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)level.setBlockAndUpdate(first.offset(x,y,z),Blocks.AIR.defaultBlockState());
        for(var pos:List.of(first,second)){level.setBlockAndUpdate(pos,Copypoly.POLY.get().defaultBlockState());((PolyBlockEntity)level.getBlockEntity(pos)).setMaterial(Blocks.SANDSTONE.defaultBlockState());}
        level.setBlockAndUpdate(rear,Blocks.GLASS.defaultBlockState());var shot=ModCbcContent.APFSDS_MEDIUM_CANNON_PROJECTILE.get().create(level);shot.setPos(Vec3.atLowerCornerOf(first).add(.5,.5,-2));shot.setDeltaMovement(0,0,12);shot.setNoGravity(true);
        try{
            for(int i=0;i<2;i++)shot.tick();
            h.assertTrue(((PolyArmorAccess)level.getBlockEntity(first)).fireControl$armorDamage().damaged(),"First layer did not perforate");
            h.assertTrue(((PolyArmorAccess)level.getBlockEntity(second)).fireControl$armorDamage().damaged(),"Shot did not continue through the second layer; pos="+shot.position()+" grounded="+shot.isInGround()+" mass="+shot.getProjectileMass());
            h.assertTrue(level.getBlockState(rear).isAir(),"Rear target not reached after penetration");
            h.assertTrue(shot.getZ()>rear.getZ()+1&&!shot.isInGround(),"Shot became embedded after a successful penetration; pos="+shot.position());h.succeed();
        }finally{shot.discard();}
    }
    @GameTest(template="empty",templateNamespace="copypoly")
    public static void renamedRegistryLoadsLegacyBlocksItemsAndBlockEntities(GameTestHelper h){
        var legacy=net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("copypoly","copycat_polyhedron");var be=armor(h);var access=h.getLevel().registryAccess();
        h.assertTrue(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).equals(Copypoly.ARMOR_ID),"Block still has legacy ID");
        h.assertTrue(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(Copypoly.ITEM.get()).equals(Copypoly.ARMOR_ID),"Item still has legacy ID");
        h.assertTrue(net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(legacy)==Copypoly.POLY.get(),"Old block palette alias missing");
        h.assertTrue(net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.get(legacy)==Copypoly.ENTITY.get(),"Old BE alias missing");
        var item=new net.minecraft.nbt.CompoundTag();item.putString("id",legacy.toString());item.putInt("count",1);
        h.assertTrue(net.minecraft.world.item.ItemStack.parseOptional(access,item).is(Copypoly.ITEM.get()),"Old item NBT did not resolve");
        var block=new net.minecraft.nbt.CompoundTag();block.putString("Name",legacy.toString());h.assertTrue(net.minecraft.nbt.NbtUtils.readBlockState(h.getLevel().holderLookup(net.minecraft.core.registries.Registries.BLOCK),block).is(Copypoly.POLY.get()),"Old block state NBT did not resolve");
        var tag=be.saveWithFullMetadata(access);tag.putString("id",legacy.toString());h.assertTrue(net.minecraft.world.level.block.entity.BlockEntity.loadStatic(be.getBlockPos(),be.getBlockState(),tag,access) instanceof PolyBlockEntity,"Old BE NBT did not load");h.succeed();
    }
    @GameTest(template="empty",templateNamespace="copypoly")
    public static void copiedCbcArmorScalesBySolidVolumeAndUpdatesWithMaterialAndDamage(GameTestHelper h){
        var be=armor(h);var level=h.getLevel();var pos=h.absolutePos(P);be.setMaterial(Blocks.IRON_BLOCK.defaultBlockState());
        be.setPoly(Polyhedron.of(Polyhedron.CUBE_POINTS.stream().map(p->new Polyhedron.Point(p.x(),p.y(),p.z()/2)).toList()));
        var provider=BlockArmorPropertiesHandler.getProperties(be.getBlockState());var iron=BlockArmorPropertiesHandler.getProperties(Blocks.IRON_BLOCK);
        close(h,iron.hardness(level,Blocks.IRON_BLOCK.defaultBlockState(),pos,true)*.5,provider.hardness(level,be.getBlockState(),pos,true),"CBC hardness did not use half volume");
        close(h,iron.toughness(level,Blocks.IRON_BLOCK.defaultBlockState(),pos,true)*.5,provider.toughness(level,be.getBlockState(),pos,true),"CBC toughness did not use half volume");
        be.setMaterial(Blocks.STONE.defaultBlockState());var stone=BlockArmorPropertiesHandler.getProperties(Blocks.STONE);
        close(h,stone.toughness(level,Blocks.STONE.defaultBlockState(),pos,true)*.5,provider.toughness(level,be.getBlockState(),pos,true),"Material change kept stale armor");
        var damage=((PolyArmorAccess)be).fireControl$armorDamage();damage.add(ArmorHole.along(new Vec3(.5,.5,0),new Vec3(0,0,1)));
        close(h,stone.toughness(level,Blocks.STONE.defaultBlockState(),pos,true)*.5*(1-4.0/256),provider.toughness(level,be.getBlockState(),pos,true),"Hole volume did not reduce armor");h.succeed();
    }
    @GameTest(template="empty",templateNamespace="copypoly")
    public static void realApfsdsPerforatesWithoutRemovingArmorAndSurvivesSaveAndReshape(GameTestHelper h){
        var be=armor(h);be.setMaterial(Blocks.IRON_BLOCK.defaultBlockState());var pos=h.absolutePos(P);var point=Vec3.atLowerCornerOf(pos).add(.5,.5,0);
        var shot=dart(h,point,new Vec3(0,0,1));var result=shot.impact(new BlockHitResult(point,Direction.NORTH,pos,false));
        h.assertTrue(result.kinematics()==AbstractCannonProjectile.ImpactResult.KinematicOutcome.PENETRATE,"Dart did not penetrate");
        h.assertTrue(h.getLevel().getBlockEntity(pos)==be,"Penetration deleted/replaced the block entity");
        var damage=((PolyArmorAccess)be).fireControl$armorDamage();h.assertTrue(damage.holes().size()==1,"Shot did not create exactly one bore");
        h.assertTrue(damage.mesh(be.poly()).clip(new Vec3(.5,.5,-1),new Vec3(.5,.5,2))==null,"Shot left a decal instead of a through-hole");
        var restored=new PolyBlockEntity(pos,be.getBlockState());restored.loadWithComponents(be.saveWithoutMetadata(h.getLevel().registryAccess()),h.getLevel().registryAccess());
        h.assertTrue(((PolyArmorAccess)restored).fireControl$armorDamage().holes().equals(damage.holes()),"Save/reload lost bore axes");
        be.setPoly(Polyhedron.of(Polyhedron.CUBE_POINTS.stream().map(p->new Polyhedron.Point(p.x(),p.y(),p.z()/4)).toList()));
        h.assertTrue(damage.mesh(be.poly()).clip(new Vec3(.5,.5,-1),new Vec3(.5,.5,2))==null,"Reshaping filled the bore");
        var drops=Block.getDrops(be.getBlockState(),h.getLevel(),pos,be);h.assertTrue(!drops.isEmpty(),"No armor drop");
        var tag=drops.getFirst().getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        var itemDamage=new ArmorDamage();itemDamage.read(tag);h.assertTrue(itemDamage.holes().equals(damage.holes()),"Drops repaired the armor");h.succeed();
    }
    @GameTest(template="empty",templateNamespace="copypoly")
    public static void stoppedDartDoesNotDrillOrRemoveArmor(GameTestHelper h){
        var be=armor(h);be.setMaterial(Blocks.IRON_BLOCK.defaultBlockState());var pos=h.absolutePos(P);var entry=Vec3.atLowerCornerOf(pos).add(.5,.5,0);
        var shot=dart(h,entry,new Vec3(0,0,1));shot.setProjectileMass(.0001F);shot.setDeltaMovement(0,0,.1);
        var result=shot.impact(new BlockHitResult(entry,Direction.NORTH,pos,false));
        h.assertTrue(result.kinematics()!=AbstractCannonProjectile.ImpactResult.KinematicOutcome.PENETRATE,"Low-energy shot unexpectedly penetrated");
        h.assertTrue(!((PolyArmorAccess)be).fireControl$armorDamage().damaged(),"A stopped dart drilled a bore");
        h.assertTrue(h.getLevel().getBlockEntity(pos)==be,"A stopped dart destroyed armor");h.succeed();
    }
    @GameTest(template="empty",templateNamespace="copypoly")
    public static void editableHoleMeshPersistsInDropsAndTakesFurtherRealImpacts(GameTestHelper h){
        var be=armor(h);be.setMaterial(Blocks.IRON_BLOCK.defaultBlockState());var access=(PolyArmorAccess)be;var damage=access.fireControl$armorDamage();
        damage.add(ArmorHole.along(new Vec3(.5,.5,0),new Vec3(0,0,1)));var mesh=damage.editable(be.poly());
        mesh=mesh.transform(p->new Vec3(p.x,p.y,p.z*.5),false);damage.install(mesh);access.fireControl$armorChanged();
        close(h,.5*(1-4.0/256),damage.volume(be.poly()),"Editor did not change armor volume");h.assertTrue(damage.holes().isEmpty(),"Edited solid retained stale analytic masks");
        var pos=h.absolutePos(P);var drops=Block.getDrops(be.getBlockState(),h.getLevel(),pos,be);var target=pos.east(2);h.getLevel().setBlockAndUpdate(target,Copypoly.POLY.get().defaultBlockState());
        Copypoly.POLY.get().setPlacedBy(h.getLevel(),target,be.getBlockState(),h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE),drops.getFirst());
        var placed=(PolyBlockEntity)h.getLevel().getBlockEntity(target);var restored=((PolyArmorAccess)placed).fireControl$armorDamage();h.assertTrue(restored.editable(placed.poly()).equals(mesh),"Drop/replacement lost editable topology");
        var saved=new PolyBlockEntity(pos,be.getBlockState());saved.loadWithComponents(be.saveWithoutMetadata(h.getLevel().registryAccess()),h.getLevel().registryAccess());h.assertTrue(((PolyArmorAccess)saved).fireControl$armorDamage().editable(saved.poly()).equals(mesh),"World save lost editable topology");
        var entry=Vec3.atLowerCornerOf(pos).add(.2,.2,0);var result=dart(h,entry,new Vec3(0,0,1)).impact(new BlockHitResult(entry,Direction.NORTH,pos,false));
        h.assertTrue(result.kinematics()==AbstractCannonProjectile.ImpactResult.KinematicOutcome.PENETRATE,"Second dart did not use real penetration");h.assertTrue(damage.volume(be.poly())<.5*(1-4.0/256),"Second shot did not carve the edited solid");
        try{damage.editable(be.poly()).validate();}catch(IllegalArgumentException invalid){h.fail("Second impact broke editable topology: "+invalid.getMessage());}h.succeed();
    }
    @GameTest(template="empty",templateNamespace="copypoly",batch="armor_terminal_scope")
    public static void terminalBallisticsIsScopedToArmorAndKeepsItsOwnSolver(GameTestHelper h){
        if(!ModList.get().isLoaded("cbc_terminal_ballistics")){h.succeed();return;}
        var be=armor(h);be.setMaterial(Blocks.GLASS.defaultBlockState());var pos=h.absolutePos(P);var level=h.getLevel();
        var data=com.cbc_terminal_ballistics.state.ArmorIntegritySavedData.get(level);
        data.addDamage(level,pos,be.getBlockState(),1e6);close(h,0,data.damage(level,pos,be.getBlockState()),"CTB integrity remained enabled");
        var entry=Vec3.atLowerCornerOf(pos).add(.5,.5,0);var shot=dart(h,entry,new Vec3(0,0,1));shot.impact(new BlockHitResult(entry,Direction.NORTH,pos,false));
        h.assertTrue(level.getBlockEntity(pos)==be,"CTB shattered or phased the armor");
        h.assertTrue(data.entryFor(level,pos,be.getBlockState()).marks.isEmpty(),"CTB added an impact overlay");
        var impact=com.cbc_terminal_ballistics.ballistics.TBImpactService.lastImpact(level,pos);
        h.assertTrue(impact!=null&&impact.outcome().equals("PENETRATE"),"CTB solver was bypassed");
        be.setMaterial(Blocks.IRON_BLOCK.defaultBlockState());
        var secondEntry=Vec3.atLowerCornerOf(pos).add(.2,.5,0);dart(h,secondEntry,new Vec3(0,0,1)).impact(new BlockHitResult(secondEntry,Direction.NORTH,pos,false));
        var steelImpact=com.cbc_terminal_ballistics.ballistics.TBImpactService.lastImpact(level,pos);
        h.assertTrue(steelImpact!=null&&steelImpact.spallFragments()>0,"CTB's penetration after-effect was disabled");
        var ordinary=pos.east(2);level.setBlockAndUpdate(ordinary,Blocks.IRON_BLOCK.defaultBlockState());
        data.addDamage(level,ordinary,level.getBlockState(ordinary),2);close(h,2,data.damage(level,ordinary,level.getBlockState(ordinary)),"CTB was disabled on ordinary blocks");
        var glass=ordinary.above();level.setBlockAndUpdate(glass,Blocks.GLASS.defaultBlockState());var gentry=Vec3.atLowerCornerOf(glass).add(.5,.5,0);
        dart(h,gentry,new Vec3(0,0,1)).impact(new BlockHitResult(gentry,Direction.NORTH,glass,false));
        h.assertTrue(level.getBlockState(glass).isAir(),"Normal glass no longer uses CTB destruction");h.succeed();
    }
    @GameTest(template="empty",templateNamespace="copypoly",batch="armor_native_bore",timeoutTicks=200)
    public static void nativeSableCollisionRefreshesAfterBoreAndReshape(GameTestHelper h){
        var level=h.getLevel();var pipeline=SubLevelPhysicsSystem.require(level).getPipeline();var pos=new BlockPos(544,200,256);
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++){var p=pos.offset(x,-1,z);level.setBlockAndUpdate(p,Blocks.STONE.defaultBlockState());upload(h,p);}
        level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(pos,Copypoly.POLY.get().defaultBlockState());var be=(PolyBlockEntity)level.getBlockEntity(pos);upload(h,pos);
        contactHeight(h,1,drop(h,pos,.5),"Initial native armor");
        var armor=(PolyArmorAccess)be;armor.fireControl$armorDamage().add(ArmorHole.along(new Vec3(.5,0,.5),new Vec3(0,1,0)));armor.fireControl$armorChanged();
        contactHeight(h,0,drop(h,pos,.5),"Native collider filled the bore");contactHeight(h,1,drop(h,pos,.2),"Native collider erased solid armor");
        be.setPoly(Polyhedron.of(Polyhedron.CUBE_POINTS.stream().map(p->new Polyhedron.Point(p.x(),p.y()/2,p.z())).toList()));
        contactHeight(h,0,drop(h,pos,.5),"Reshape filled the native bore");contactHeight(h,.5,drop(h,pos,.2),"Reshape retained old native armor height");
        h.assertTrue(armor.fireControl$armorDamage().clearHoles(),"The recorded bore could not be cleared");
        armor.fireControl$armorChanged();
        contactHeight(h,.5,drop(h,pos,.5),"Clearing the bore did not restore the reshaped Sable collider");
        var restored = new PolyBlockEntity(pos, be.getBlockState());
        restored.loadWithComponents(be.saveWithoutMetadata(level.registryAccess()),level.registryAccess());
        h.assertTrue(restored.document().equals(be.document())&&!((PolyArmorAccess)restored).fireControl$armorDamage().damaged(),
                "Clearing the bore failed to persist or changed the base document");
        armor.fireControl$armorDamage().add(ArmorHole.along(new Vec3(.5,0,.5),new Vec3(0,1,0)));
        armor.fireControl$armorChanged();
        armor.fireControl$armorDamage().install(armor.fireControl$armorDamage().editable(be.poly()).transform(p->new Vec3(p.x,p.y*.5,p.z),false));armor.fireControl$armorChanged();
        contactHeight(h,0,drop(h,pos,.5),"Editable mesh filled native bore");contactHeight(h,.25,drop(h,pos,.2),"Native collider ignored edited outer mesh");h.succeed();
    }
    @GameTest(template="empty",templateNamespace="copypoly",batch="armor_rotated_sable",timeoutTicks=200)
    public static void shotOnRotatedSableBodyStoresLocalBoreAndRetainsItAfterAssembly(GameTestHelper h){
        var be=armor(h);be.setMaterial(Blocks.IRON_BLOCK.defaultBlockState());var original=h.absolutePos(P);var level=h.getLevel();
        // Two blocks keep Sable from replacing a one-block body with its optimized single-block representation.
        level.setBlockAndUpdate(original.below(),Blocks.STONE.defaultBlockState());
        var body=SubLevelAssemblyHelper.assembleBlocks(level,original,List.of(original,original.below()),new BoundingBox3i(original.below(),original));
        h.runAfterDelay(3,()->{
            h.assertTrue(level.getBlockEntity(body.getPlot().getCenterBlock()) instanceof PolyBlockEntity,"Assembly target was not the polyhedron before teleport: "+body.getPlot().getCenterBlock());
            level.getChunkAt(new BlockPos(560,210,256));
            var pipeline=SubLevelPhysicsSystem.require(level).getPipeline();pipeline.teleport(body,new Vector3d(560.5,210.5,256.5),new Quaterniond().rotateY(.7).rotateX(.25));pipeline.resetVelocity(body);
            // Read the native pose immediately. The GameTest world may unload remote
            // structures between ticks; coordinate conversion is tested at the actual rotated pose.
            pipeline.readPose(body,body.logicalPose());
            try{
                var pos=body.getPlot().getCenterBlock();var localEntry=Vec3.atLowerCornerOf(pos).add(.5,.5,0);var pose=body.logicalPose();
                h.assertTrue(level.getBlockEntity(pos) instanceof PolyBlockEntity,"Rotated body lost its block before the shot: "+pos);
                var worldEntry=pose.transformPosition(localEntry);var worldDirection=pose.transformNormal(new Vec3(0,0,1));
                dart(h,worldEntry,worldDirection).impact(new BlockHitResult(localEntry,Direction.NORTH,pos,false));
                var target=(PolyBlockEntity)level.getBlockEntity(pos);var damage=((PolyArmorAccess)target).fireControl$armorDamage();
                h.assertTrue(damage.holes().size()==1,"Sable hit lost the bore");
                h.assertTrue(java.lang.Math.abs(damage.holes().getFirst().direction().dot(new Vec3(0,0,1)))>.999,"World shot direction was not converted into the structure frame");
                var tag=target.saveWithoutMetadata(level.registryAccess());var copy=new PolyBlockEntity(pos,target.getBlockState());copy.loadWithComponents(tag,level.registryAccess());
                h.assertTrue(((PolyArmorAccess)copy).fireControl$armorDamage().holes().equals(damage.holes()),"Sable storage lost local bores");h.succeed();
            }finally{body.markRemoved();SubLevelContainer.getContainer(level).processSubLevelRemovals();}
        });
    }
    private static void upload(GameTestHelper h,BlockPos pos){var chunk=h.getLevel().getChunkAt(pos);var s=SectionPos.of(pos);SubLevelPhysicsSystem.require(h.getLevel()).getPipeline().handleChunkSectionAddition(chunk.getSection(chunk.getSectionIndex(pos.getY())),s.x(),s.y(),s.z(),true);}
    private static double drop(GameTestHelper h,BlockPos pos,double x){
        var pipeline=SubLevelPhysicsSystem.require(h.getLevel()).getPipeline();var pose=new Pose3d();pose.position().set(pos.getX()+x,pos.getY()+2,pos.getZ()+.5);
        var handle=pipeline.addBox(new BoxPhysicsObject(pose,new Vector3d(.025),1));try{for(int tick=0;tick<100;tick++){pipeline.prePhysicsTicks();for(int step=0;step<5;step++)pipeline.physicsTick(.01);}handle.readPose(pose);return pose.position().y-pos.getY()-.025;}finally{handle.remove();}
    }
    private static void contactHeight(GameTestHelper h,double expected,double actual,String message){h.assertTrue(java.lang.Math.abs(expected-actual)<.04,message+": "+actual);}
}
