package com.hooya.stabilizedturret.content.bearing;

import com.hooya.stabilizedturret.content.controller.CannonBallisticsCompat;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import java.util.List;
import java.util.UUID;

/** Actual Sable native steps with the production finite inverse and holding model. */
public final class NativeFiniteCompensationChecks {
    private static double fixedPeak,oldPeak,supportedPeak,oldSupportedPeak;
    public static void main(String[] args) {
        for (double dt:new double[]{1.0/40,1.0/120}) for (double length:new double[]{1,6,20,40})
            for (double omega:new double[]{-.4,.4}) { run(dt,length,omega,true); run(dt,length,omega,false); }
        require(oldPeak>.01,"the old trial-velocity inverse reproduces a length-dependent angular bias");
        for (double dt:new double[]{1.0/40,1.0/120}) for (double length:new double[]{1,40})
            for (double speed:new double[]{2,0}) { supported(dt,length,speed,true); supported(dt,length,speed,false); }
        require(oldSupportedPeak>.02,"unsupported prediction reproduces the post-stop elevation bias");
        for (double dt:new double[]{1.0/40,1.0/120}) for (double length:new double[]{6.5,20})
            for (double omega:new double[]{-.4,.4}) run(dt,length,omega,true,true);
        System.out.println("PASS: 8 real native virtual-scope steps retain the 7.244-degree saved lift without finite muzzle convergence");
        System.out.printf("PASS: 32 real native finite-compensation steps; old holding miss %.6f deg; corrected miss %.9f deg%n",
                oldPeak,fixedPeak);
        System.out.printf("PASS: 16 supported native finite-compensation steps with gravity and moving/stopped carriers; old miss %.6f deg; corrected miss %.9f deg%n",
                oldSupportedPeak,supportedPeak);
    }

    /** The real support is present in native physics but absent from the joint-only forecast, like terrain contacts. */
    private static void supported(double dt,double length,double speed,boolean corrected) {
        long scene=Rapier3D.initialize(0,-11,0,1);
        try {
            Rapier3D.configFrequencyAndDamping(40,5); Rapier3D.configSolverIterations(18,2,2);
            Rapier3D.configMinIslandSize(128);
            Quaterniond child=new Quaterniond().rotationXYZ(-.05,0,0);
            Rapier3D.createBox(scene,1,2000,.5,.5,.5,new double[]{0,-2,0,0,0,0,1});
            Rapier3D.createBox(scene,2,3,.5,.5,.5,pose(child));
            Rapier3D.addGenericConstraint(scene,-1,1,0,-2,0,0,0,0,1,0,0,0,0,0,0,1,0b111011);
            Rapier3D.addLinearAngularVelocities(scene,1,0,0,speed,0,0,0,false);
            Rapier3D.addLinearAngularVelocities(scene,2,0,0,speed,0,0,0,false);
            long hinge=Rapier3D.addGenericConstraint(scene,1,2,0,2,0,0,0,0,1,0,0,0,0,0,0,1,0b110111);
            Rapier3D.setConstraintContactsEnabled(scene,hinge,false);
            long actuator=Rapier3D.addGenericConstraint(scene,-1,2,0,0,0,0,0,0,1,0,0,0,0,0,0,1,0);
            var carrier=new ArticulatedServoPredictor.BodyState(2000,new Matrix3d().scaling(2000.0/6),
                    new Vector3d(0,-2,0),new Quaterniond(),new Vector3d(0,0,speed),new Vector3d());
            var barrel=new ArticulatedServoPredictor.BodyState(3,new Matrix3d().scaling(.5),new Vector3d(),
                    child,new Vector3d(0,0,speed),new Vector3d());
            var bodies=List.of(carrier,barrel);
            var motor=new ArticulatedServoPredictor.HingeMotor(0,1,new Vector3d(0,2,0),new Vector3d(),
                    new Quaterniond(),new Quaterniond(),-.05,120000,1000,10000,0,true,1);
            var profile=new CannonBallisticsCompat.ShotProfile(Vec3.ZERO,new Vec3(0,0,1),Vec3.ZERO,
                    9,-.05,0,false,"test","test",(int)length,1,400,1,1,0);
            var target=new FiniteAimTarget(new UUID(0,1),new Vec3(0,0,length),new Vec3(0,0,1),
                    new Vec3(0,0,180),profile);
            var camera=new MovingAimRay(new UUID(0,2),new Vec3(0,2,0),new Vec3(0,0,1),
                    target.worldTarget(),new Vec3(0,0,-1));
            var holding=new StableMuzzleVelocity.Prediction(0,List.of(new StableMuzzleVelocity.JointFrame(
                    0,1,new Vector3d(1,0,0),new Vector3d())));
            ArticulatedServoSolver.DynamicRayTarget inverse=predicted->{
                var body=predicted.get(1);
                Vector3d ray=body.orientation().transform(new Vector3d(0,0,1));
                Vector3d muzzle=body.position().add(new Vector3d(ray).mul(length));
                Vector3d velocity=corrected ? holding.velocity(predicted,carrier,muzzle,ray)
                        : holding.velocity(predicted,muzzle,ray);
                Vec3 point=camera.predictedTarget(predicted.get(0),new Vector3d(),new Vector3d());
                return target.predictedDirection(body,new Vector3d(),new Vector3d(),point,vec(velocity.div(20)));
            };
            var goal=new ArticulatedServoSolver.RayGoal(0,List.of(new ArticulatedServoSolver.RaySource(1,new Vector3d(0,0,1))),
                    new Vector3d(1,0,0),inverse.direction(bodies),inverse);
            var solved=ArticulatedServoSolver.solveIndependent(bodies,List.of(motor),null,List.of(),new Vector3d(0,-11,0),dt,
                    List.of(goal),new int[]{0},18,2,2,550,4,false);
            require(solved.accepted(),"supported finite inverse rejected: "+solved.failure());
            Rapier3D.setConstraintMotor(scene,actuator,3,solved.targets()[0],120000,1000,true,10000);
            Rapier3D.step(scene,dt);
            var actualBodies=List.of(nativeState(scene,1,2000,2000.0/6),nativeState(scene,2,3,.5));
            Vector3d ray=actualBodies.get(1).orientation().transform(new Vector3d(0,0,1));
            Vector3d muzzle=actualBodies.get(1).position().add(new Vector3d(ray).mul(length));
            Vector3d velocity=holding.velocity(actualBodies,actualBodies.get(0),muzzle,ray);
            Vec3 point=camera.predictedTarget(actualBodies.get(0),new Vector3d(),new Vector3d());
            Vector3d expected=target.predictedDirection(actualBodies.get(1),new Vector3d(),new Vector3d(),point,vec(velocity.div(20)));
            double error=Math.abs(Math.toDegrees(BearingServoMath.signedDirectionAngle(ray,expected,new Vector3d(1,0,0))));
            if (corrected) { supportedPeak=Math.max(supportedPeak,error); require(error<.001,"supported corrected miss: "+error); }
            else oldSupportedPeak=Math.max(oldSupportedPeak,error);
        } finally { Rapier3D.dispose(scene); }
    }

    private static ArticulatedServoPredictor.BodyState nativeState(long scene,int id,double mass,double inertia) {
        double[] pose=new double[7],v=new double[3],w=new double[3];
        Rapier3D.getPose(scene,id,pose); Rapier3D.getLinearVelocity(scene,id,v); Rapier3D.getAngularVelocity(scene,id,w);
        return new ArticulatedServoPredictor.BodyState(mass,new Matrix3d().scaling(inertia),
                new Vector3d(pose[0],pose[1],pose[2]),new Quaterniond(pose[3],pose[4],pose[5],pose[6]),
                new Vector3d(v),new Vector3d(w));
    }
    private static void run(double dt,double length,double omega,boolean corrected) {
        run(dt,length,omega,corrected,false);
    }
    private static void run(double dt,double length,double omega,boolean corrected,boolean parallel) {
        long scene=Rapier3D.initialize(0,0,0,0);
        try {
            Rapier3D.configFrequencyAndDamping(40,5); Rapier3D.configSolverIterations(18,2,2);
            Rapier3D.configMinIslandSize(128);
            Quaterniond child=new Quaterniond().rotationXYZ(-.05,0,0);
            Rapier3D.createBox(scene,2,3,.5,.5,.5,pose(child));
            Rapier3D.addLinearAngularVelocities(scene,2,0,0,0,omega,0,0,false);
            long hinge=Rapier3D.addGenericConstraint(scene,-1,2,0,0,0,0,0,0,1,0,0,0,0,0,0,1,0b110111);
            Rapier3D.setConstraintContactsEnabled(scene,hinge,false);
            long actuator=Rapier3D.addGenericConstraint(scene,-1,2,0,0,0,0,0,0,1,0,0,0,0,0,0,1,0);
            var base=state(new Quaterniond(),new Vector3d(),7.5,1.25);
            var barrel=state(child,new Vector3d(omega,0,0),3,.5);
            var bodies=List.of(base,barrel);
            var motor=new ArticulatedServoPredictor.HingeMotor(-1,1,new Vector3d(),new Vector3d(),
                    new Quaterniond(),new Quaterniond(),-.05,120000,1000,10000,0,true,1);
            var profile=new CannonBallisticsCompat.ShotProfile(Vec3.ZERO,new Vec3(0,0,1),Vec3.ZERO,
                    9,-.05,0,false,"test","test",(int)length,1,400,1,1,0);
            var finite=new FiniteAimTarget(new UUID(0,1),new Vec3(0,0,length),new Vec3(0,0,1),
                    new Vec3(0,0,180),profile);
            var target=parallel ? finite.withElevationCorrection(Math.toRadians(7.244054267964293))
                    .withParallelSight(new Vec3(0,0,1)) : finite;
            var holding=new StableMuzzleVelocity.Prediction(0,List.of(new StableMuzzleVelocity.JointFrame(
                    0,1,new Vector3d(1,0,0),new Vector3d())));
            ArticulatedServoSolver.DynamicRayTarget inverse=predicted->{
                var body=predicted.get(1);
                Vector3d ray=body.orientation().transform(new Vector3d(0,0,1));
                Vector3d muzzle=body.position().add(new Vector3d(ray).mul(length));
                Vector3d velocity=holding.velocity(predicted,muzzle,ray);
                return target.predictedDirection(body,new Vector3d(),new Vector3d(),target.worldTarget(),
                        corrected && velocity!=null ? vec(velocity.div(20)) : null);
            };
            Vector3d initial=inverse.direction(bodies);
            var goal=new ArticulatedServoSolver.RayGoal(0,List.of(new ArticulatedServoSolver.RaySource(1,new Vector3d(0,0,1))),
                    new Vector3d(1,0,0),initial,inverse);
            var solved=ArticulatedServoSolver.solveIndependent(bodies,List.of(motor),null,List.of(),new Vector3d(),dt,
                    List.of(goal),new int[]{0},18,2,2,550,4,false);
            require(solved.accepted(),"native finite inverse rejected: "+solved.failure()+" length="+length
                    +" corrected="+corrected+" residual="+solved.maxResidualRadians()+" initial="+initial);
            Rapier3D.setConstraintMotor(scene,actuator,3,solved.targets()[0],120000,1000,true,10000);
            Rapier3D.step(scene,dt);
            double[] actual=new double[7]; Rapier3D.getPose(scene,2,actual);
            Quaterniond rotation=new Quaterniond(actual[3],actual[4],actual[5],actual[6]);
            Vector3d ray=rotation.transform(new Vector3d(0,0,1));
            var actualBody=new ArticulatedServoPredictor.BodyState(3,new Matrix3d().scaling(.5),
                    new Vector3d(actual[0],actual[1],actual[2]),rotation,new Vector3d(),new Vector3d());
            Vector3d expected=target.predictedDirection(actualBody,new Vector3d(),new Vector3d(),target.worldTarget(),Vec3.ZERO);
            double error=Math.abs(Math.toDegrees(BearingServoMath.signedDirectionAngle(ray,expected,new Vector3d(1,0,0))));
            if (parallel) {
                Vec3 sight=com.hooya.stabilizedturret.content.controller.LaserRangeCompensation.raise(vec(ray),
                        -target.elevationCorrection());
                require(Math.toDegrees(Math.acos(Math.clamp(sight.z,-1,1)))<.001,
                        "real virtual-scope barrel and infinite sight must retain the same compensated heading");
            }
            if (corrected) { fixedPeak=Math.max(fixedPeak,error); require(error<.001,"corrected native holding miss: "+error); }
            else oldPeak=Math.max(oldPeak,error);
        } finally { Rapier3D.dispose(scene); }
    }
    private static ArticulatedServoPredictor.BodyState state(Quaterniond q,Vector3d w,double mass,double inertia) {
        return new ArticulatedServoPredictor.BodyState(mass,new Matrix3d().scaling(inertia),new Vector3d(),q,new Vector3d(),w);
    }
    private static Vec3 vec(Vector3d value) { return new Vec3(value.x,value.y,value.z); }
    private static double[] pose(Quaterniond q) { return new double[]{0,0,0,q.x,q.y,q.z,q.w}; }
    private static void require(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
