package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.registry.ModCbcContent;
import com.hooya.stabilizedturret.compat.yuushya.YuushyaCannonCompat;
import com.yuushya.modelling.blockentity.itemblock.ItemBlockEntity;
import com.yuushya.modelling.blockentity.showblock.ShowBlockEntity;
import com.yuushya.modelling.blockentity.transformData.TransformBlockData;
import com.yuushya.modelling.blockentity.transformData.TransformItemData;
import com.yuushya.modelling.registries.BlockRegistry;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.joml.*;
import java.util.*;

/** Real optional-mod data and production mount assembly, not placeholder cubes. Never shipped. */
final class PreviewComplexModelsFixture {
    static UUID cannon;
    static void place(ServerLevel level,BlockPos base)throws Exception {
        show(level,base.offset(-3,1,0),false);
        item(level,base.offset(-1,1,0),false);
        var mount=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("createbigcannons:cannon_mount"));
        level.setBlockAndUpdate(base.offset(-5,1,0),mount.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.SOUTH));
        level.setBlockAndUpdate(base.offset(-5,3,0),ModCbcContent.MEDIUM_CANNON_BREECH.get().defaultBlockState().setValue(BlockStateProperties.FACING,Direction.SOUTH));
        show(level,base.offset(-5,3,1),true);
        item(level,base.offset(-5,3,2),true);
        var bearing=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:mechanical_bearing"));
        level.setBlockAndUpdate(base.offset(6,1,-1),bearing.defaultBlockState().setValue(BlockStateProperties.FACING,Direction.UP));
        level.setBlockAndUpdate(base.offset(6,2,-1),Blocks.COPPER_BLOCK.defaultBlockState());
        qmodel(level,base.offset(4,1,0));
        littleTiles(level,base.offset(6,1,0));
    }
    private static void show(ServerLevel level,BlockPos pos,boolean barrel) {
        level.setBlockAndUpdate(pos,BlockRegistry.SHOW_BLOCK.get().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.SOUTH));
        var be=(ShowBlockEntity)level.getBlockEntity(pos);be.getTransformData().clear();
        be.getTransformData().add(new TransformBlockData(new Vector3d(),new Vector3f(),new Vector3f(.35f,.35f,barrel?1.6f:.8f),Blocks.DIAMOND_BLOCK.defaultBlockState(),true));
        be.setChanged();if(barrel)YuushyaCannonCompat.setBarrel(level,pos,true);
    }
    private static void item(ServerLevel level,BlockPos pos,boolean barrel) {
        level.setBlockAndUpdate(pos,BlockRegistry.ITEM_BLOCK.get().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.SOUTH));
        var be=(ItemBlockEntity)level.getBlockEntity(pos);be.getTransformData().clear();
        be.getTransformData().add(new TransformItemData(new Vector3d(),new Vector3f(),new Vector3f(.4f,.4f,barrel?1.4f:1),new ItemStack(Blocks.GOLD_BLOCK),-1,true,false));
        be.setChanged();if(barrel)YuushyaCannonCompat.setBarrel(level,pos,true);
    }
    private static void qmodel(ServerLevel level,BlockPos pos)throws Exception {
        if(!net.neoforged.fml.ModList.get().isLoaded("qmodeling"))return;
        var block=BuiltInRegistries.BLOCK.stream().filter(b->b.getClass().getName().equals("qwxon.qmodeling.block.WorkspaceBlock")).findFirst().orElseThrow();
        level.setBlockAndUpdate(pos,block.defaultBlockState());var be=level.getBlockEntity(pos);
        Class<?> part=Class.forName("qwxon.qmodeling.block.entity.WorkspacePartData");
        Object data=part.getConstructor(UUID.class,net.minecraft.world.level.block.state.BlockState.class,net.minecraft.nbt.CompoundTag.class,Vec3.class,Vec3.class,Vec3.class)
                .newInstance(UUID.randomUUID(),Blocks.LAPIS_BLOCK.defaultBlockState(),new net.minecraft.nbt.CompoundTag(),new Vec3(0,.25,0),new Vec3(0,25,0),new Vec3(1.3,.5,.6));
        be.getClass().getMethod("replaceAllParts",List.class).invoke(be,List.of(data));
    }
    private static void littleTiles(ServerLevel level,BlockPos pos)throws Exception {
        if(!net.neoforged.fml.ModList.get().isLoaded("littletiles"))return;
        var block=BuiltInRegistries.BLOCK.stream().filter(b->b.getClass().getName().equals("team.creative.littletiles.common.block.mc.BlockTile")).findFirst().orElseThrow();
        level.setBlockAndUpdate(pos,block.defaultBlockState());var be=level.getBlockEntity(pos);
        Class<?> grid=Class.forName("team.creative.littletiles.common.grid.LittleGrid");
        Object grid16=grid.getMethod("get",int.class).invoke(null,16);be.getClass().getMethod("convertTo",grid).invoke(be,grid16);
        Class<?> box=Class.forName("team.creative.littletiles.common.math.box.LittleBox"),tile=Class.forName("team.creative.littletiles.common.block.little.tile.LittleTile");
        Object shape=box.getConstructor(int.class,int.class,int.class,int.class,int.class,int.class).newInstance(0,0,0,4,8,16);
        Object data=tile.getConstructor(net.minecraft.world.level.block.state.BlockState.class,int.class,List.class).newInstance(Blocks.REDSTONE_BLOCK.defaultBlockState(),-1,List.of(shape));
        Object parent=be.getClass().getMethod("noneStructureTiles").invoke(be);
        var add=Arrays.stream(parent.getClass().getMethods()).filter(m->m.getName().equals("add")&&m.getParameterCount()==1&&m.getParameterTypes()[0]==tile).findFirst().orElseThrow();
        add.invoke(parent,data);be.getClass().getMethod("updateTiles").invoke(be);
    }
    static void assemble(net.minecraft.world.level.Level level,BlockPos plot)throws Exception {
        var mount=(rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity)level.getBlockEntity(plot.offset(-5,1,0));
        var method=mount.getClass().getDeclaredMethod("assemble");method.setAccessible(true);method.invoke(mount);
        if(mount.getContraption()==null)throw new AssertionError("modeled medium cannon failed production mount assembly: "+mount.getLastAssemblyException());
        cannon=mount.getContraption().getUUID();
        if(!level.getBlockState(plot.offset(-5,3,1)).isAir())throw new AssertionError("assembled modeled barrel was not removed into the entity");
        var bearing=(com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity)level.getBlockEntity(plot.offset(6,1,-1));
        bearing.assemble();bearing.setSpeed(8);
        var queued=bearing.getClass().getDeclaredField("assembleNextTick");queued.setAccessible(true);queued.setBoolean(bearing,false);
        if(!bearing.isRunning())throw new AssertionError("Create mechanical bearing failed production assembly: "+bearing.getLastAssemblyException());
        if(net.neoforged.fml.ModList.get().isLoaded("littletiles")) {
            var moved=bearing.getClass().getDeclaredField("movedContraption");moved.setAccessible(true);
            var entity=(com.simibubi.create.content.contraptions.ControlledContraptionEntity)moved.get(bearing);
            var tiles=level.getBlockEntity(plot.offset(6,1,0));BlockPos local=new BlockPos(0,0,1);
            entity.getContraption().getBlocks().put(local,new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(local,tiles.getBlockState(),tiles.saveWithFullMetadata(level.registryAccess())));
            entity.getContraption().getIsLegacy().put(local,true);
            entity.getContraption().bounds=new net.minecraft.world.phys.AABB(0,0,0,1,1,2);
        }
    }
    private PreviewComplexModelsFixture() {}
}
