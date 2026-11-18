package com.hooya.stabilizedturret.content.bearing;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** T-80's actual weld topology with synthetic masses/geometry; uses the installed Sable native solver. */
public final class NativeWeldedStartupChecks {
    private static final int[][] WELDS={{23,21},{23,5},{20,13},{20,18},{20,22},{20,3},{6,16},{13,26},
            {23,12},{23,8},{23,25},{23,17},{23,2},{23,10},{23,11},{23,0},{23,19},{23,14},
            {23,1},{23,24},{23,4},{23,7},{27,9},{5,27},{16,15},{29,28},{28,30},{29,31},
            {30,32},{20,28},{22,33},{3,34},{23,35}};
    private static double peakPrediction,peakHeld;
    private static long servoNanos;
    private static int servoSteps;

    public static void main(String[] args) throws Exception {
        Path report=Path.of("welded-startup-result.txt");
        Files.writeString(report,"RUNNING\n");
        try {
            for (int hz:new int[]{40,120}) { run(hz,false); run(hz,true); }
            String result=String.format(Locale.ROOT,
                    "PASS: 36 bodies, 33 welds, 561 no-contact links; plate and downstream welded barrel feedback, gravity, light plate/heavy cannon at 40/120 Hz; "
                            +"peak native prediction %.9f deg, peak held ray %.9f deg; servo computation %.3f ms/step (%d steps)%n",
                    peakPrediction,peakHeld,servoNanos/1e6/servoSteps,servoSteps);
            Files.writeString(report,result); System.out.print(result);
        } catch (Throwable failure) { Files.writeString(report,"FAIL\n"+failure+"\n"); throw failure; }
    }

    private static void run(int hz,boolean weldedBarrel) {
        double dt=1.0/hz;
        Vector3d gravity = weldedBarrel ? new Vector3d(0,-10,0) : new Vector3d();
        long scene=Rapier3D.initialize(0,gravity.y,0,0);
        try {
            Rapier3D.configFrequencyAndDamping(40,5);
            Rapier3D.configSolverIterations(18,2,2); Rapier3D.configMinIslandSize(128);
            List<ArticulatedServoPredictor.BodyState> bodies=new ArrayList<>();
            Quaterniond initial=new Quaterniond().rotationXYZ(.01,.2,-.015);
            for (int i=0;i<36;i++) {
                double mass=i==23?115:i==20?19:i==16?(weldedBarrel?120:12):i==6&&weldedBarrel?.05:i==13?4:.75;
                Vector3d p=new Vector3d((i%6)*1.2,(i/6)*1.2,(i%3)*1.1);
                Quaterniond orientation=new Quaterniond(initial);
                if (weldedBarrel && (i==16 || i==15)) orientation.rotateZ(.22);
                Rapier3D.createBox(scene,i+1,mass,.25,.25,.25,pose(p,orientation));
                bodies.add(new ArticulatedServoPredictor.BodyState(mass,new Matrix3d().scaling(mass/24),
                        p,orientation,new Vector3d(),new Vector3d()));
            }
            List<ArticulatedServoPredictor.HingeMotor> hinges=new ArrayList<>();
            long order=0;
            for (int[] edge:WELDS) {
                Vector3d anchor=bodies.get(edge[0]).orientation().transformInverse(
                        bodies.get(edge[1]).position().sub(bodies.get(edge[0]).position()));
                Quaterniond relative=bodies.get(edge[0]).orientation().conjugate().mul(bodies.get(edge[1]).orientation());
                long handle=Rapier3D.addFixedConstraint(scene,edge[0]+1,edge[1]+1,
                        anchor.x,anchor.y,anchor.z,0,0,0,relative.x,relative.y,relative.z,relative.w);
                Rapier3D.setConstraintContactsEnabled(scene,handle,false);
                hinges.add(ArticulatedServoPredictor.HingeMotor.fixed(edge[0],edge[1],anchor,new Vector3d(),
                        relative,new Quaterniond(),order++));
            }
            int noContact=0;
            for (int a=0;a<36 && noContact<561;a++) for (int b=a+1;b<36 && noContact<561;b++) {
                long h=Rapier3D.addGenericConstraint(scene,a+1,b+1,0,0,0,0,0,0,1,0,0,0,0,0,0,1,0);
                Rapier3D.setConstraintContactsEnabled(scene,h,false); order++; noContact++;
            }
            int[] parents={23,20},children={13,6},indices={hinges.size(),hinges.size()+1};
            Quaterniond[] frames={BearingServoMath.hingeFrame(new Quaterniond(),0),
                    BearingServoMath.hingeFrame(new Quaterniond().rotationXYZ(Math.PI/2,0,Math.PI),0)};
            Vector3d[] axes={new Vector3d(0,1,0),new Vector3d(0,0,-1)};
            Vector3d[] localRays={BearingServoMath.startupLocalRay(axes[0]),BearingServoMath.startupLocalRay(axes[1])};
            Vector3d[] held={initial.transform(new Vector3d(localRays[0])),initial.transform(new Vector3d(localRays[1]))};
            long[] actuators=new long[2];
            Quaterniond[] driveFrames=new Quaterniond[2];
            for (int i=0;i<2;i++) {
                Vector3d anchor=initial.transformInverse(bodies.get(children[i]).position().sub(bodies.get(parents[i]).position()));
                Quaterniond f=frames[i],world=new Quaterniond(initial).mul(f);
                int driven=weldedBarrel?(i==1?16:20):children[i];
                driveFrames[i]=bodies.get(driven).orientation().conjugate().mul(bodies.get(children[i]).orientation()).mul(f);
                Quaterniond driveFrame=driveFrames[i];
                Vector3d driveAnchor=bodies.get(driven).orientation().transformInverse(
                        bodies.get(children[i]).position().sub(bodies.get(driven).position()));
                long hinge=Rapier3D.addGenericConstraint(scene,parents[i]+1,driven+1,
                        anchor.x,anchor.y,anchor.z,f.x,f.y,f.z,f.w,driveAnchor.x,driveAnchor.y,driveAnchor.z,
                        driveFrame.x,driveFrame.y,driveFrame.z,driveFrame.w,55);
                Rapier3D.setConstraintMotor(scene,hinge,3,0,0,0,true,0);
                if (i==1) Rapier3D.setConstraintLimit(scene,hinge,3,Math.toRadians(-9),Math.toRadians(15));
                long hingeOrder=order++;
                actuators[i]=Rapier3D.addGenericConstraint(scene,-1,driven+1,
                        0,0,0,world.x,world.y,world.z,world.w,0,0,0,driveFrame.x,driveFrame.y,driveFrame.z,driveFrame.w,0);
                var motor=BearingServoMath.motor(i==0?25:8,Double.POSITIVE_INFINITY);
                var model=new ArticulatedServoPredictor.HingeMotor(parents[i],driven,anchor,driveAnchor,
                        f,driveFrame,0,motor.stiffness(),motor.damping(),motor.maxTorque(),hingeOrder,true,order++);
                hinges.add(i==1?model.withLimits(Math.toRadians(-9),Math.toRadians(15)):model);
            }
            Quaterniond supportFrame=new Quaterniond(initial);
            long support=Rapier3D.addGenericConstraint(scene,-1,24,0,0,0,supportFrame.x,supportFrame.y,
                    supportFrame.z,supportFrame.w,0,0,0,0,0,0,1,0);
            long supportOrder=order;
            double[] k={200000,200000,200000,100000,100000,100000},d={5000,5000,5000,3000,3000,3000};
            double[] caps={1e8,1e8,1e8,1e8,1e8,1e8};
            for (int tick=0;tick<48;tick++) {
                // Switch to a differently oriented cannon ray after entities finish loading.
                if (tick==12) for (int i=0;i<2;i++) {
                    localRays[i]=new Vector3d(-1,0,i==0?.2:0).normalize();
                    if (weldedBarrel) new Quaterniond().rotationZ(-.22).transform(localRays[i]);
                    held[i]=bodies.get(weldedBarrel?16:children[i]).orientation().transform(new Vector3d(localRays[i]));
                }
                List<ArticulatedServoSolver.RayGoal> goals=new ArrayList<>();
                for (int i=0;i<2;i++) {
                    var h=hinges.get(indices[i]);
                    Quaterniond world=bodies.get(parents[i]).orientation().mul(frames[i]);
                    Rapier3D.setConstraintFrame(scene,actuators[i],0,0,0,0,world.x,world.y,world.z,world.w);
                    double measured=BearingServoMath.jointAngle(world,bodies.get(h.child()).orientation().mul(h.childFrame()));
                    int source=weldedBarrel && tick>=12?16:children[i];
                    Vector3d physical=bodies.get(source).orientation().transform(new Vector3d(localRays[i]));
                    Vector3d worldAxis=bodies.get(parents[i]).orientation().transform(new Vector3d(axes[i]));
                    h=h.withGoal(measured+BearingServoMath.signedDirectionAngle(physical,held[i],worldAxis));
                    hinges.set(indices[i],h);
                    if (i==1) {
                        Vector3d target=new Vector3d(held[i]),ray=new Vector3d(localRays[i]);
                        int parentIndex=parents[i];
                        goals.add(new ArticulatedServoSolver.RayGoal(parentIndex,axes[i],target,
                                List.of(new ArticulatedServoSolver.RaySource(source,ray)),predicted ->
                                BearingServoMath.pitchDirection(target,
                                        predicted.get(source).orientation().transform(new Vector3d(ray)),
                                        predicted.get(parentIndex).orientation())));
                    } else goals.add(new ArticulatedServoSolver.RayGoal(parents[i],source,axes[i],localRays[i],held[i]));
                }
                double[] angular={.0003*Math.sin(tick*.15),.0005*tick,-.0002*Math.sin(tick*.1)};
                double[] linear={bodies.get(23).position().x,bodies.get(23).position().y,bodies.get(23).position().z};
                Vector3d supportPosition=supportFrame.transformInverse(new Vector3d(linear[0],linear[1],linear[2]));
                linear=new double[]{supportPosition.x,supportPosition.y,supportPosition.z};
                var worlds=List.of(new ArticulatedServoPredictor.WorldMotor(23,supportFrame,new Vector3d(),new Vector3d(),
                        angular,linear,k,d,caps,63,false,supportOrder));
                for (int axis=0;axis<6;axis++) Rapier3D.setConstraintMotor(scene,support,axis,
                        axis<3?linear[axis]:angular[axis-3],k[axis],d[axis],true,caps[axis]);
                long servoStart=System.nanoTime();
                var result=ArticulatedServoSolver.solveIndependent(bodies,hinges,null,worlds,gravity,dt,goals,indices,
                        18,2,2,550,4,false);
                if (!result.accepted()) {
                    for (int j=0;j<2;j++) {
                        var joint=hinges.get(indices[j]);
                        double measured=BearingServoMath.jointAngle(bodies.get(parents[j]).orientation().mul(frames[j]),
                                bodies.get(children[j]).orientation().mul(frames[j]));
                        System.out.printf(Locale.ROOT,"axis=%d measured=%.5f goal=%.5f rate=%.5f source=%s parent=%s%n",j,
                                Math.toDegrees(measured),Math.toDegrees(joint.goal()),bodies.get(children[j]).angularVelocity().length(),
                                bodies.get(16).orientation(),bodies.get(parents[j]).orientation());
                    }
                    throw new AssertionError("36-body startup inverse rejected: "+hz+" Hz tick "+tick+" "+result.failure()+" residual="+Math.toDegrees(result.maxResidualRadians()));
                }
                for (int i=0;i<2;i++) {
                    var h=hinges.get(indices[i]).withGoal(result.targets()[indices[i]]); hinges.set(indices[i],h);
                    Rapier3D.setConstraintMotor(scene,actuators[i],3,h.goal(),h.stiffness(),h.damping(),true,h.torqueCap());
                }
                var predicted=ArticulatedServoPredictor.predict(bodies,hinges,null,worlds,gravity,dt,18,2,2,550,4,false).bodies();
                servoNanos+=System.nanoTime()-servoStart; servoSteps++;
                Rapier3D.step(scene,dt);
                List<ArticulatedServoPredictor.BodyState> next=new ArrayList<>();
                for (int i=0;i<36;i++) {
                    double[] p=new double[7],v=new double[3],w=new double[3];
                    Rapier3D.getPose(scene,i+1,p); Rapier3D.getLinearVelocity(scene,i+1,v); Rapier3D.getAngularVelocity(scene,i+1,w);
                    Quaterniond q=new Quaterniond(p[3],p[4],p[5],p[6]);
                    double error=angle(q,predicted.get(i).orientation()); peakPrediction=Math.max(peakPrediction,error);
                    if (!Double.isFinite(error) || error>.001) throw new AssertionError(
                            "Native welded prediction error: "+hz+" Hz tick "+tick+" body "+i+" error "+error);
                    next.add(new ArticulatedServoPredictor.BodyState(bodies.get(i).mass(),bodies.get(i).inertiaLocal(),
                            new Vector3d(p[0],p[1],p[2]),q,new Vector3d(v),new Vector3d(w)));
                }
                bodies=next;
                for (int i=0;i<2;i++) {
                    Vector3d actual=bodies.get(weldedBarrel && tick>=12?16:children[i]).orientation().transform(new Vector3d(localRays[i]));
                    Vector3d worldAxis=bodies.get(parents[i]).orientation().transform(new Vector3d(axes[i]));
                    Vector3d desired=i==1?BearingServoMath.pitchDirection(held[i],actual,bodies.get(parents[i]).orientation()):held[i];
                    double error=Math.toDegrees(Math.abs(BearingServoMath.signedDirectionAngle(actual,desired,worldAxis)));
                    peakHeld=Math.max(peakHeld,error);
                    if (!Double.isFinite(error) || error>.002) throw new AssertionError("Welded startup ray drifts: "+error);
                }
            }
        } finally { Rapier3D.dispose(scene); }
    }
    private static double angle(Quaterniond a,Quaterniond b) {
        Quaterniond q=new Quaterniond(a).conjugate().mul(b).normalize();
        return Math.toDegrees(2*Math.atan2(Math.sqrt(q.x*q.x+q.y*q.y+q.z*q.z),Math.abs(q.w)));
    }
    private static double[] pose(Vector3d p,Quaterniond q) { return new double[]{p.x,p.y,p.z,q.x,q.y,q.z,q.w}; }
}
