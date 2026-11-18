package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.client.preview.StructurePreviewRenderer;
import com.hooya.stabilizedturret.client.preview.VoxelPreviewMesher;
import com.hooya.stabilizedturret.client.preview.StructurePreviewMaterials;
import com.hooya.stabilizedturret.content.bearing.PreviewTurretBodies;
import com.hooya.stabilizedturret.content.bearing.PreviewBearingSettings;
import com.hooya.stabilizedturret.content.bearing.YawLimits;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import java.util.Arrays;
import java.util.Random;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Geometry invariants and persistent values, independent of the rendering implementation. */
public final class StructurePreviewChecks {
    public static void main(String[] args) {
        byte[] data = new byte[18*18*18];
        require(VoxelPreviewMesher.mesh(data).isEmpty(), "empty section has no hull");
        fill(data, 1);
        require(VoxelPreviewMesher.mesh(data).size()==6, "a solid section merges to six outer rectangles");
        require(area(data)==6*16*16, "solid section surface area");
        for(int y=1;y<=16;y++)for(int z=1;z<=16;z++)data[VoxelPreviewMesher.index(17,y,z)]=1;
        require(VoxelPreviewMesher.mesh(data).size()==5, "adjacent section suppresses the shared internal wall");
        Arrays.fill(data,(byte)-1); fill(data,1);
        require(VoxelPreviewMesher.mesh(data).isEmpty(), "unknown unloaded halo is never assumed to be air");
        Arrays.fill(data,(byte)0);fill(data,1);data[VoxelPreviewMesher.index(8,8,8)]=0;
        require(area(data)==6*16*16+6, "interior cavities have six exposed surfaces");
        Random random = new Random(7321);
        for(int attempt=0;attempt<40;attempt++) {
            Arrays.fill(data,(byte)0);
            for(int x=1;x<=16;x++)for(int y=1;y<=16;y++)for(int z=1;z<=16;z++)
                if(random.nextDouble()<.08)data[VoxelPreviewMesher.index(x,y,z)]=1;
            int expected=0;
            int[][] neighbors={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
            for(int x=1;x<=16;x++)for(int y=1;y<=16;y++)for(int z=1;z<=16;z++)if(data[VoxelPreviewMesher.index(x,y,z)]==1)
                for(int[] n:neighbors)if(data[VoxelPreviewMesher.index(x+n[0],y+n[1],z+n[2])]==0)expected++;
            require(area(data)==expected, "greedy rectangles preserve exposed area without overlaps");
        }
        long before=VoxelPreviewMesher.fingerprint(data);data[0]=-1;
        require(before!=VoxelPreviewMesher.fingerprint(data), "halo edits invalidate the neighboring cached mesh");
        Arrays.fill(data,(byte)0);
        for(int x=1;x<=16;x++)for(int y=1;y<=16;y++)for(int z=1;z<=16;z++)if((x+y+z)%2==0)data[VoxelPreviewMesher.index(x,y,z)]=1;
        var coarse=VoxelPreviewMesher.mesh(data);
        require(coarse.size()<=4096 && coarse.stream().allMatch(f->f.step()==2), "checkerboard overload falls back to bounded coarse geometry");
        data[VoxelPreviewMesher.index(1,1,1)]=2;
        require(VoxelPreviewMesher.mesh(data).stream().filter(VoxelPreviewMesher.Face::opaque).mapToInt(f->f.width()*f.height()).sum()==6,
                "high-complexity material fallback never promotes neighboring contextual blocks to opaque equipment");
        checkMaterials();
        require(Double.isFinite(StructurePreviewRenderer.pickDepth(new Matrix4f().translate(-.5f,-.5f,0),0,0)), "pick hits visible box");
        require(!Double.isFinite(StructurePreviewRenderer.pickDepth(new Matrix4f(),2,2)), "pick ignores empty screen space");
        double near=StructurePreviewRenderer.pickDepth(new Matrix4f().translate(-.5f,-.5f,-.5f),0,0);
        double far=StructurePreviewRenderer.pickDepth(new Matrix4f().translate(-.5f,-.5f,.5f),0,0);
        require(near<far, "overlapping boxes select their frontmost surface");
        var settings=new PreviewBearingSettings(12,34,57,true,true,new Vec3(-1.25,2.5,-3.75),new YawLimits(40,60,YawLimits.WEST));
        require(settings.equals(PreviewBearingSettings.load(settings.save())), "all single-device settings persist losslessly");
        require(!new PreviewBearingSettings(0,0,Double.NaN,false,false,Vec3.ZERO,YawLimits.UNLIMITED).finite(), "reject nonfinite speed");
        require(!new PreviewBearingSettings(0,0,1,false,false,new Vec3(Double.POSITIVE_INFINITY,0,0),YawLimits.UNLIMITED).finite(), "reject nonfinite offset");
        System.out.println("PASS: hull geometry, enclosed opaque equipment, material boundaries, material cache invalidation, directed turret/weld classification, depth picking and persisted settings");
    }
    private static void checkMaterials() {
        byte[] data=new byte[18*18*18];fill(data,1);
        long contextFingerprint=VoxelPreviewMesher.fingerprint(data);
        data[VoxelPreviewMesher.index(8,8,8)]=2;
        var faces=VoxelPreviewMesher.mesh(data);
        require(faces.stream().filter(VoxelPreviewMesher.Face::opaque).mapToInt(f->f.width()*f.height()).sum()==6,
                "opaque equipment retains every face when enclosed by translucent armor");
        require(faces.stream().filter(f->!f.opaque()).mapToInt(f->f.width()*f.height()).sum()==6*16*16,
                "context keeps only its outside shell, without internal ghost walls");
        require(contextFingerprint!=VoxelPreviewMesher.fingerprint(data),"relevance change invalidates cache without an occupancy change");
        Arrays.fill(data,(byte)0);data[VoxelPreviewMesher.index(1,1,1)]=2;data[VoxelPreviewMesher.index(2,1,1)]=1;
        faces=VoxelPreviewMesher.mesh(data);
        require(faces.stream().filter(VoxelPreviewMesher.Face::opaque).mapToInt(f->f.width()*f.height()).sum()==6
                        && faces.stream().filter(f->!f.opaque()).mapToInt(f->f.width()*f.height()).sum()==5,
                "coplanar rectangles never merge translucent and opaque materials");
        UUID chassis=new UUID(0,1),turret=new UUID(0,2),barrel=new UUID(0,3),barrelWeld=new UUID(0,4),other=new UUID(0,5);
        var selected=PreviewTurretBodies.descendants(Set.of(turret),Map.of(chassis,Set.of(turret,other),turret,Set.of(barrel)),
                Map.of(barrel,Set.of(barrelWeld),barrelWeld,Set.of(barrel)));
        require(selected.equals(Set.of(turret,barrel,barrelWeld)),"nested pitch and rigid barrel welds stay opaque without including parent chassis/siblings");
        var materials=new StructurePreviewMaterials(Set.of(turret),Map.of(chassis,Set.of(net.minecraft.core.BlockPos.ZERO)));
        require(materials.opaque(chassis,net.minecraft.core.BlockPos.ZERO),"bound external equipment is opaque on the chassis");
        require(!materials.opaque(chassis,new net.minecraft.core.BlockPos(1,0,0)),"adjacent unrelated chassis block is translucent");
        require(materials.opaque(turret,new net.minecraft.core.BlockPos(1,0,0)),"every block of the driven turret remains opaque");
        require(StructurePreviewMaterials.fireControlBlock(net.minecraft.resources.ResourceLocation.parse("create_fire_control:stabilizer_controller")),"unbound fire-control hardware stays opaque");
        require(!StructurePreviewMaterials.fireControlBlock(net.minecraft.resources.ResourceLocation.parse("create_fire_control:gas_turbine")),"unrelated engine is contextual");
    }
    private static int area(byte[] data){return VoxelPreviewMesher.mesh(data).stream().mapToInt(f->f.width()*f.height()).sum();}
    private static void fill(byte[] data,int value){for(int x=1;x<=16;x++)for(int y=1;y<=16;y++)for(int z=1;z<=16;z++)data[VoxelPreviewMesher.index(x,y,z)]=(byte)value;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
