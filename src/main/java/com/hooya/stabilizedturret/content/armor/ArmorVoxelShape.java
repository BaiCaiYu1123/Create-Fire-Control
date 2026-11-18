package com.hooya.stabilizedturret.content.armor;

import it.unimi.dsi.fastutil.doubles.*;
import net.minecraft.core.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import java.util.*;

/** One compressed contact grid, without repeatedly unioning growing voxel shapes. */
public final class ArmorVoxelShape extends VoxelShape {
    private record Grid(BitSetDiscreteVoxelShape voxels,DoubleList[] coordinates){}
    private final DoubleList[] coordinates;private final List<AABB> boxes;private final ArmorMesh mesh;
    private List<ArmorMeshOutline.Line> outline;
    public ArmorVoxelShape(List<AABB> boxes,ArmorMesh mesh){this(grid(boxes),boxes,mesh);}
    private ArmorVoxelShape(Grid grid,List<AABB> boxes,ArmorMesh mesh){super(grid.voxels);this.coordinates=grid.coordinates;this.boxes=boxes;this.mesh=mesh;}
    private static Grid grid(List<AABB> boxes){
        var coordinates=new DoubleList[3];for(int a=0;a<3;a++){var values=new TreeSet<Double>();values.add(0.0);values.add(1.0);for(var b:boxes){values.add(min(b,a));values.add(max(b,a));}coordinates[a]=DoubleLists.unmodifiable(new DoubleArrayList(values));}
        var voxels=new BitSetDiscreteVoxelShape(coordinates[0].size()-1,coordinates[1].size()-1,coordinates[2].size()-1);
        for(var b:boxes){int[] low=new int[3],high=new int[3];for(int a=0;a<3;a++){low[a]=coordinates[a].indexOf(min(b,a));high[a]=coordinates[a].indexOf(max(b,a));}
            for(int x=low[0];x<high[0];x++)for(int y=low[1];y<high[1];y++)for(int z=low[2];z<high[2];z++)voxels.fill(x,y,z);}
        return new Grid(voxels,coordinates);
    }
    private static double min(AABB b,int a){return a==0?b.minX:a==1?b.minY:b.minZ;}
    private static double max(AABB b,int a){return a==0?b.maxX:a==1?b.maxY:b.maxZ;}
    @Override public DoubleList getCoords(Direction.Axis axis){return coordinates[axis.ordinal()];}
    @Override public List<AABB> toAabbs(){return boxes;}
    @Override public void forAllBoxes(Shapes.DoubleLineConsumer action){for(var b:boxes)action.consume(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ);}
    @Override public BlockHitResult clip(Vec3 from,Vec3 to,BlockPos pos){
        Vec3 offset=Vec3.atLowerCornerOf(pos);var hit=mesh.clip(from.subtract(offset),to.subtract(offset));
        return hit==null?null:new ArmorHitResult(from.lerp(to,hit.fraction()),Direction.getNearest(hit.normal().x,hit.normal().y,hit.normal().z),pos,false,hit.normal());
    }
    @Override public void forAllEdges(Shapes.DoubleLineConsumer consumer){
        if(outline==null)outline=ArmorMeshOutline.build(mesh);
        for(var e:outline){var a=e.a();var b=e.b();consumer.consume(a.x,a.y,a.z,b.x,b.y,b.z);}
    }
}
