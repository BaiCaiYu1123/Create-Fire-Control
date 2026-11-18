package com.hooya.stabilizedturret.client;

import net.minecraft.world.phys.Vec3;

public final class BombRenderInterpolationChecks {
    public static void main(String[] args) {
        for(double speed:new double[]{.2,4,12}) {
            var visual=new BombRenderInterpolation();Vec3 p=new Vec3(0,200,0),v=new Vec3(speed,-.1,0);visual.initialize(p,v);
            for(int tick=1;tick<=120;tick++) {
                Vec3 end=visual.position(1);p=p.add(v);v=v.add(0,-.024525,0);
                Vec3 corrected=p.add(tick%7==0?.3:0,0,0);visual.advance(corrected,v);
                if(visual.position(0).distanceTo(end)>1e-9)throw new AssertionError("Snapshot correction breaks frame continuity");
                if(visual.position(1).x<=end.x)throw new AssertionError("Forward flight stalled");
                if(tick%7==0&&visual.position(1).equals(corrected))throw new AssertionError("Packet error snapped rather than reconciled");
                for(int frame=0;frame<=6;frame++)if(!Double.isFinite(visual.position(frame/6f).lengthSqr()))throw new AssertionError("Nonfinite render position");
            }
        }
        System.out.println("PASS: virtual bomb display continuity, packet corrections and frame interpolation");
    }
}
