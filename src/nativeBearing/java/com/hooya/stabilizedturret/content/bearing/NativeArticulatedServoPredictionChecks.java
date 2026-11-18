package com.hooya.stabilizedturret.content.bearing;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import java.util.List;
import java.util.Locale;
import java.nio.file.Files;
import java.nio.file.Path;

/** Compares production prediction with the next native pose; no solver internals are replaced. */
public final class NativeArticulatedServoPredictionChecks {
    private static final Path REPORT=Path.of("bearing-articulated-prediction-result.txt");
    private static final StringBuilder CSV=new StringBuilder("hz,yaw,root_goal,linear_goal,cap,active_mask,world_first,root_error_deg,child_error_deg,root_position_error,child_position_error\n");
    private static int cases;
    private static double maximumRotationError,maximumPositionError;
    public static void main(String[] args) throws Exception {
        Files.writeString(REPORT,"RUNNING\n");
        try {
            for(double dt:new double[]{1.0/40,1.0/120})for(double yaw:new double[]{0,.7,2.9})
                for(boolean first:new boolean[]{false,true})for(boolean partial:new boolean[]{false,true})runAngularHold(dt,yaw,first,partial);
            for(double dt:new double[]{1.0/40,1.0/120})for(double yaw:new double[]{.5,1.5})for(double rootGoal:new double[]{0,.06})for(double linear:new double[]{0,.04})for(double cap:new double[]{20,10000})for(int mask:new int[]{63,47})for(boolean staffFirst:new boolean[]{false,true})run(dt,yaw,rootGoal,linear,cap,mask,staffFirst);
            for(double dt:new double[]{1.0/40,1.0/120})for(boolean springOnly:new boolean[]{false,true})for(double cap:new double[]{20,10000})runSingleGainWorldMotor(dt,springOnly,cap);
            for(double dt:new double[]{1.0/40,1.0/120})for(double yaw:new double[]{2.9,3.3})for(double childTilt:new double[]{0,.06})runMutableGenericWorldFrames(dt,yaw,childTilt);
            for(double dt:new double[]{1.0/40,1.0/120})for(double angle:new double[]{-.1,.1})runChildOnlyActuator(dt,angle);
            for(double dt:new double[]{1.0/40,1.0/120})for(double angle:new double[]{-.5,-Math.toRadians(10),0,Math.toRadians(20),.5})
                for(double goal:new double[]{-.6,.6})runPitchStop(dt,angle,goal);
            for(double dt:new double[]{1.0/40,1.0/120})for(boolean generic:new boolean[]{false,true})
                for(boolean ground:new boolean[]{false,true})runFixedWeld(dt,generic,ground);
            for(double dt:new double[]{1.0/40,1.0/120})for(boolean star:new boolean[]{false,true})
                for(boolean worldFirst:new boolean[]{false,true})for(boolean noContact:new boolean[]{false,true})
                    runMixedFixedWelds(dt,star,worldFirst,noContact);
            for(double dt:new double[]{1.0/40,1.0/120})for(double yaw:new double[]{.5,1.5})for(double rootGoal:new double[]{0,.06})for(boolean staffFirst:new boolean[]{false,true})run(dt,yaw,rootGoal,.04,10000,63,staffFirst,true);
            String result=String.format(Locale.ROOT,"PASS: %d native cases; maximum pose error %.9f degrees, maximum position error %.12f blocks%n",cases,maximumRotationError,maximumPositionError);
            Files.writeString(REPORT,result);System.out.print(result);
        } catch(Throwable failure) {Files.writeString(REPORT,"FAIL\n"+failure+"\n");throw failure;}
        finally { Files.writeString(Path.of("bearing-articulated-prediction.csv"),CSV); }
    }
    private static void runAngularHold(double dt,double yaw,boolean holdFirst,boolean partial) {
        long scene=Rapier3D.initialize(0,0,0,0);
        try {
            Rapier3D.configFrequencyAndDamping(40,5);Rapier3D.configSolverIterations(18,2,2);Rapier3D.configMinIslandSize(128);
            Quaterniond q=new Quaterniond().rotationY(yaw).rotateX(.13).rotateZ(-.09);
            Vector3d velocity=new Vector3d(.5,1.3,-.2),omega=new Vector3d(.3,-.2,.1);
            Rapier3D.createBox(scene,1,3,.25,.25,.25,pose(new Vector3d(),q));
            Rapier3D.addLinearAngularVelocities(scene,1,velocity.x,velocity.y,velocity.z,omega.x,omega.y,omega.z,false);
            long hold=0,pull=0;
            for(int index=0;index<2;index++) {
                if((index==0)==holdFirst) {
                    hold=Rapier3D.addGenericConstraint(scene,-1,1,0,0,0,q.x,q.y,q.z,q.w,0,0,0,0,0,0,1,partial?48:56);
                    Rapier3D.setConstraintMotor(scene,hold,3,0,0,0,true,0);
                } else {
                    pull=Rapier3D.addGenericConstraint(scene,-1,1,0,0,0,q.x,q.y,q.z,q.w,.8,0,0,0,0,0,1,0);
                    Rapier3D.setConstraintMotor(scene,pull,1,1,20000,1000,false,0);
                }
            }
            var input=List.of(new ArticulatedServoPredictor.BodyState(3,new Matrix3d().scaling(.125),new Vector3d(),q,velocity,omega));
            var held=partial ? ArticulatedServoPredictor.HingeMotor.angularHinge(0,q,new Quaterniond(),holdFirst?0:1)
                    : ArticulatedServoPredictor.HingeMotor.angularHold(0,q,holdFirst?0:1);
            var world=new ArticulatedServoPredictor.WorldMotor(0,q,new Vector3d(.8,0,0),new Vector3d(),
                    new double[3],new double[]{0,1,0},new double[]{0,20000,0,0,0,0},new double[]{0,1000,0,0,0,0},
                    new double[]{0,Double.POSITIVE_INFINITY,0,0,0,0},2,!holdFirst,holdFirst?1:0);
            var forecast=ArticulatedServoPredictor.predict(input,List.of(held),null,List.of(world),new Vector3d(),dt,18,2,2,550,4,false).bodies().getFirst();
            Rapier3D.step(scene,dt);
            double[] actual=new double[7];Rapier3D.getPose(scene,1,actual);
            double rotationError=error(new Quaterniond(actual[3],actual[4],actual[5],actual[6]),forecast.orientation());
            double positionError=new Vector3d(actual[0],actual[1],actual[2]).distance(forecast.position());
            maximumRotationError=Math.max(maximumRotationError,rotationError);maximumPositionError=Math.max(maximumPositionError,positionError);cases++;
            if(rotationError>=.001 || positionError>=1e-6 || new Vector3d(actual[0],actual[1],actual[2]).length()<.001)
                throw new AssertionError("Angular hold native forecast/free translation: "+rotationError+" / "+positionError);
        } finally {Rapier3D.dispose(scene);}
    }

    /** Exercise partial and full SIMD groups, with a real world motor and zero-row native links. */
    private static void runMixedFixedWelds(double dt,boolean star,boolean worldFirst,boolean noContact) {
        long scene=Rapier3D.initialize(0,0,0,0);
        try {
            Rapier3D.configFrequencyAndDamping(40,5);Rapier3D.configSolverIterations(18,2,2);Rapier3D.configMinIslandSize(128);
            var input=new java.util.ArrayList<ArticulatedServoPredictor.BodyState>();
            var fixed=new java.util.ArrayList<ArticulatedServoPredictor.HingeMotor>();
            Quaterniond q=new Quaterniond().rotationY(.7);
            for(int i=0;i<8;i++) {
                Vector3d p=new Vector3d(i*.8,0,0),v=new Vector3d(.02*i,-.01*i,.03*i),w=new Vector3d(.01*i,.02*i,-.01*i);
                Rapier3D.createBox(scene,i+1,3,.25,.25,.25,pose(p,q));
                Rapier3D.addLinearAngularVelocities(scene,i+1,v.x,v.y,v.z,w.x,w.y,w.z,false);
                input.add(new ArticulatedServoPredictor.BodyState(3,new Matrix3d().scaling(.125),p,q,v,w));
            }
            double[] k={100000,100000,100000,20000,20000,20000},d={1000,1000,1000,500,500,500};
            double[] goals={.01,-.02,.03,.02,-.01,.01},caps=new double[6];java.util.Arrays.fill(caps,Double.POSITIVE_INFINITY);
            long rowOrder=0;
            if(worldFirst) {
                long h=Rapier3D.addGenericConstraint(scene,-1,1,0,0,0,q.x,q.y,q.z,q.w,0,0,0,0,0,0,1,0);
                for(int axis=0;axis<6;axis++)Rapier3D.setConstraintMotor(scene,h,axis,goals[axis],k[axis],d[axis],false,0);
                rowOrder++;
            }
            if(noContact) for(int i=1;i<8;i++) {
                long h=Rapier3D.addGenericConstraint(scene,1,i+1,0,0,0,0,0,0,1,0,0,0,0,0,0,1,0);
                Rapier3D.setConstraintContactsEnabled(scene,h,false);rowOrder++;
            }
            int[][] edges=star?new int[][]{{0,1},{0,2},{0,3},{0,4},{0,5},{0,6},{0,7}}
                    :new int[][]{{0,1},{2,3},{4,5},{6,7},{0,2},{2,4},{4,6}};
            for(int[] pair:edges) {
                Vector3d a=q.transformInverse(new Vector3d((pair[1]-pair[0])*.8,0,0));
                long h=Rapier3D.addFixedConstraint(scene,pair[0]+1,pair[1]+1,a.x,a.y,a.z,0,0,0,0,0,0,1);
                Rapier3D.setConstraintContactsEnabled(scene,h,false);
                fixed.add(ArticulatedServoPredictor.HingeMotor.fixed(pair[0],pair[1],a,new Vector3d(),new Quaterniond(),new Quaterniond(),rowOrder++));
            }
            if(!worldFirst) {
                long h=Rapier3D.addGenericConstraint(scene,-1,1,0,0,0,q.x,q.y,q.z,q.w,0,0,0,0,0,0,1,0);
                for(int axis=0;axis<6;axis++)Rapier3D.setConstraintMotor(scene,h,axis,goals[axis],k[axis],d[axis],false,0);
            }
            var world=new ArticulatedServoPredictor.WorldMotor(0,q,new Vector3d(),new Vector3d(),
                    new double[]{goals[3],goals[4],goals[5]},new double[]{goals[0],goals[1],goals[2]},k,d,caps,63,worldFirst,worldFirst?0:rowOrder);
            var predicted=ArticulatedServoPredictor.predict(input,fixed,null,List.of(world),new Vector3d(),dt,18,2,2,550,4,false).bodies();
            Rapier3D.step(scene,dt);
            for(int i=0;i<8;i++) {
                double[] actual=new double[7];Rapier3D.getPose(scene,i+1,actual);
                double rotationError=error(new Quaterniond(actual[3],actual[4],actual[5],actual[6]),predicted.get(i).orientation());
                double positionError=new Vector3d(actual[0],actual[1],actual[2]).distance(predicted.get(i).position());
                maximumRotationError=Math.max(maximumRotationError,rotationError);maximumPositionError=Math.max(maximumPositionError,positionError);
                if(!Double.isFinite(rotationError)||rotationError>=.001||!Double.isFinite(positionError)||positionError>=1e-6)
                    throw new AssertionError("Mixed native fixed welds differ: star="+star+", worldFirst="+worldFirst
                            +", noContact="+noContact+", body="+i+", rotation="+rotationError+", position="+positionError);
            }
            cases++;
            System.out.printf(Locale.ROOT,"mixed fixed welds %.0f Hz star=%s worldFirst=%s noContact=%s: PASS%n",1/dt,star,worldFirst,noContact);
        } finally {Rapier3D.dispose(scene);}
    }
    private static void runFixedWeld(double dt,boolean generic,boolean ground) {
        long scene=Rapier3D.initialize(0,0,0,0);
        try {
            Rapier3D.configFrequencyAndDamping(40,5);Rapier3D.configSolverIterations(18,2,2);Rapier3D.configMinIslandSize(128);
            Quaterniond qa=new Quaterniond().rotationY(.7),qb=new Quaterniond(qa).rotateX(.13);
            Vector3d p=new Vector3d(1,.5,.7),b=new Vector3d(.2,-.1,.3);
            Vector3d worldAnchor=qb.transform(new Vector3d(b)).add(p);
            Vector3d a=ground?worldAnchor:qa.transformInverse(new Vector3d(worldAnchor));
            Quaterniond frame=ground?new Quaterniond(qb):new Quaterniond(qa).conjugate().mul(qb);
            if(!ground)Rapier3D.createBox(scene,1,7.5,.5,.5,.5,pose(new Vector3d(),qa));
            Rapier3D.createBox(scene,2,3,.5,.5,.5,pose(p,qb));
            long joint=generic?Rapier3D.addGenericConstraint(scene,ground?-1:1,2,
                    a.x,a.y,a.z,frame.x,frame.y,frame.z,frame.w,b.x,b.y,b.z,0,0,0,1,63)
                    :Rapier3D.addFixedConstraint(scene,ground?-1:1,2,a.x,a.y,a.z,b.x,b.y,b.z,
                    frame.x,frame.y,frame.z,frame.w);
            Rapier3D.setConstraintContactsEnabled(scene,joint,false);
            Vector3d linear=new Vector3d(.2,-.1,.4),angular=new Vector3d(.4,-.3,.2);
            Rapier3D.addLinearAngularVelocities(scene,2,linear.x,linear.y,linear.z,angular.x,angular.y,angular.z,false);
            var child=new ArticulatedServoPredictor.BodyState(3,new Matrix3d().scaling(.5),p,qb,linear,angular);
            var input=ground?List.of(child):List.of(new ArticulatedServoPredictor.BodyState(7.5,
                    new Matrix3d().scaling(1.25),new Vector3d(),qa,new Vector3d(),new Vector3d()),child);
            var fixed=ArticulatedServoPredictor.HingeMotor.fixed(ground?-1:0,ground?0:1,a,b,frame,new Quaterniond(),0);
            var predicted=ArticulatedServoPredictor.predict(input,List.of(fixed),null,new Vector3d(),dt,18,2,2,550,4,false)
                    .bodies().getLast();
            Rapier3D.step(scene,dt);
            double[] actual=new double[7];Rapier3D.getPose(scene,2,actual);
            double rotationError=error(new Quaterniond(actual[3],actual[4],actual[5],actual[6]),predicted.orientation());
            double positionError=new Vector3d(actual[0],actual[1],actual[2]).distance(predicted.position());
            maximumRotationError=Math.max(maximumRotationError,rotationError);maximumPositionError=Math.max(maximumPositionError,positionError);cases++;
            if(!Double.isFinite(rotationError)||rotationError>=.001||!Double.isFinite(positionError)||positionError>=1e-6)
                throw new AssertionError("Fixed weld differs from native: generic="+generic+", ground="+ground
                        +", rotation="+rotationError+", position="+positionError);
            System.out.printf(Locale.ROOT,"fixed weld %.0f Hz generic=%s ground=%s: %.9f degrees, %.12f blocks%n",
                    1/dt,generic,ground,rotationError,positionError);
        } finally {Rapier3D.dispose(scene);}
    }
    private static void runPitchStop(double dt,double angle,double goal) {
        long scene=Rapier3D.initialize(0,0,0,0);
        try {
            Rapier3D.configFrequencyAndDamping(40,5);Rapier3D.configSolverIterations(18,2,2);Rapier3D.configMinIslandSize(128);
            Quaterniond parent=new Quaterniond().rotationZ(.2),child=new Quaterniond(parent).rotateX(angle).rotateY(.015);
            Vector3d parentOmega=new Vector3d(.2,-.4,.3),childOmega=new Vector3d(1,-.3,-.2);
            Rapier3D.createBox(scene,1,7.5,.5,.5,.5,pose(new Vector3d(),parent));
            Rapier3D.createBox(scene,2,3,.5,.5,.5,pose(new Vector3d(),child));
            Rapier3D.addLinearAngularVelocities(scene,1,0,0,0,parentOmega.x,parentOmega.y,parentOmega.z,false);
            Rapier3D.addLinearAngularVelocities(scene,2,0,0,0,childOmega.x,childOmega.y,childOmega.z,false);
            long hinge=Rapier3D.addGenericConstraint(scene,1,2,0,0,0,0,0,0,1,0,0,0,0,0,0,1,0b110111);
            Rapier3D.setConstraintContactsEnabled(scene,hinge,false);
            Rapier3D.setConstraintLimit(scene,hinge,3,-Math.toRadians(10),Math.toRadians(20));
            long actuator=Rapier3D.addGenericConstraint(scene,-1,2,0,0,0,parent.x,parent.y,parent.z,parent.w,0,0,0,0,0,0,1,0);
            Rapier3D.setConstraintMotor(scene,actuator,3,goal,120000,1000,true,10000);
            var input=List.of(new ArticulatedServoPredictor.BodyState(7.5,new Matrix3d().scaling(1.25),new Vector3d(),parent,new Vector3d(),parentOmega),
                    new ArticulatedServoPredictor.BodyState(3,new Matrix3d().scaling(.5),new Vector3d(),child,new Vector3d(),childOmega));
            var hingeModel=new ArticulatedServoPredictor.HingeMotor(0,1,new Vector3d(),new Vector3d(),new Quaterniond(),new Quaterniond(),goal,120000,1000,10000,0,true,1)
                    .withLimits(-Math.toRadians(10),Math.toRadians(20));
            var predicted=ArticulatedServoPredictor.predict(input,List.of(hingeModel),null,new Vector3d(),dt,18,2,2,550,4,false).bodies();
            Rapier3D.step(scene,dt);
            for(int i=0;i<2;i++) {
                double[] pose=new double[7];Rapier3D.getPose(scene,i+1,pose);
                double difference=error(new Quaterniond(pose[3],pose[4],pose[5],pose[6]),predicted.get(i).orientation());
                maximumRotationError=Math.max(maximumRotationError,difference);
                if(difference>=.001)throw new AssertionError("Native pitch-stop prediction: angle="+angle+", goal="+goal+", body="+i+", pose error="+difference);
            }
            cases++;
        } finally {Rapier3D.dispose(scene);}
    }
    private static void runChildOnlyActuator(double dt,double angle) {
        long scene=Rapier3D.initialize(0,0,0,0);
        try {
            Rapier3D.configFrequencyAndDamping(40,5);Rapier3D.configSolverIterations(18,2,2);Rapier3D.configMinIslandSize(128);
            Quaterniond frame=BearingServoMath.hingeFrame(new Quaterniond(),0);
            Rapier3D.createBox(scene,1,7.5,.5,.5,.5,pose(new Vector3d(),new Quaterniond()));
            Rapier3D.createBox(scene,2,3,.5,.5,.5,pose(new Vector3d(),new Quaterniond()));
            long hinge=Rapier3D.addGenericConstraint(scene,1,2,0,0,0,frame.x,frame.y,frame.z,frame.w,
                    0,0,0,frame.x,frame.y,frame.z,frame.w,0b110111);
            Rapier3D.setConstraintContactsEnabled(scene,hinge,false);
            Rapier3D.setConstraintMotor(scene,hinge,3,0,0,0,true,0);
            long actuator=Rapier3D.addGenericConstraint(scene,-1,2,0,0,0,frame.x,frame.y,frame.z,frame.w,
                    0,0,0,frame.x,frame.y,frame.z,frame.w,0);
            Rapier3D.setConstraintMotor(scene,actuator,3,angle,120000,1000,true,10000);
            var input=List.of(new ArticulatedServoPredictor.BodyState(7.5,new Matrix3d().scaling(1.25),new Vector3d(),new Quaterniond(),new Vector3d(),new Vector3d()),
                    new ArticulatedServoPredictor.BodyState(3,new Matrix3d().scaling(.5),new Vector3d(),new Quaterniond(),new Vector3d(),new Vector3d()));
            var drive=new ArticulatedServoPredictor.HingeMotor(0,1,new Vector3d(),new Vector3d(),frame,frame,angle,120000,1000,10000,0,true,1);
            var predicted=ArticulatedServoPredictor.predict(input,List.of(drive),null,new Vector3d(),dt,18,2,2,550,4,false).bodies();
            Rapier3D.step(scene,dt);
            double[] parent=new double[7],child=new double[7],omega=new double[3];
            Rapier3D.getPose(scene,1,parent);Rapier3D.getPose(scene,2,child);Rapier3D.getAngularVelocity(scene,1,omega);
            double rootError=error(new Quaterniond(parent[3],parent[4],parent[5],parent[6]),predicted.get(0).orientation());
            double childError=error(new Quaterniond(child[3],child[4],child[5],child[6]),predicted.get(1).orientation());
            if(rootError>=.001 || childError>=.001)throw new AssertionError("Child-only native prediction mismatch: "+rootError+" / "+childError);
            if(new Vector3d(omega[0],omega[1],omega[2]).length()>1e-7)throw new AssertionError("Actuator feeds rotational reaction to carrier");
            if(error(new Quaterniond(child[3],child[4],child[5],child[6]),new Quaterniond())<.01)throw new AssertionError("Child-only actuator did not rotate the child");
            maximumRotationError=Math.max(maximumRotationError,Math.max(rootError,childError));cases++;
            System.out.printf(Locale.ROOT,"child-only drive %.0f Hz: child prediction %.9f degrees; carrier omega %.12f rad/s%n",1/dt,childError,new Vector3d(omega[0],omega[1],omega[2]).length());
        } finally {Rapier3D.dispose(scene);}
    }
    private static void runMutableGenericWorldFrames(double dt,double yaw,double childTilt) {
        long scene=Rapier3D.initialize(0,0,0,0);
        try {
            Rapier3D.configFrequencyAndDamping(40,5);Rapier3D.configSolverIterations(18,2,2);Rapier3D.configMinIslandSize(128);
            Quaterniond parentFrame=new Quaterniond().rotationY(yaw),childFrame=new Quaterniond().rotationX(childTilt).rotateZ(-childTilt/3);
            Quaterniond orientation=new Quaterniond(parentFrame).rotateX(.015).mul(new Quaterniond(childFrame).conjugate());
            Vector3d position=new Vector3d(.4,.3,.2),anchor=new Vector3d(.2,-.1,.05),parentAnchor=new Vector3d(.1,.02,-.1);
            Vector3d worldGoal=new Vector3d(position).add(orientation.transform(new Vector3d(anchor))),linearGoal=parentFrame.transformInverse(new Vector3d(worldGoal).sub(parentAnchor));
            Rapier3D.createBox(scene,1,3,.5,.5,.5,pose(position,orientation));
            long joint=Rapier3D.addGenericConstraint(scene,-1,1,0,0,0,0,0,0,1,0,0,0,0,0,0,1,0);
            // Exercise the native frame-update API, rather than only constructing a matching joint.
            Rapier3D.setConstraintFrame(scene,joint,0,parentAnchor.x,parentAnchor.y,parentAnchor.z,parentFrame.x,parentFrame.y,parentFrame.z,parentFrame.w);
            Rapier3D.setConstraintFrame(scene,joint,1,anchor.x,anchor.y,anchor.z,childFrame.x,childFrame.y,childFrame.z,childFrame.w);
            double[] k={2650,2650,2650,10000,10000,10000},d={125,125,125,850,850,850};
            double[] caps=new double[6];java.util.Arrays.fill(caps,Double.POSITIVE_INFINITY);
            for(int axis=0;axis<6;axis++)Rapier3D.setConstraintMotor(scene,joint,axis,axis<3?linearGoal.get(axis):0,k[axis],d[axis],false,0);
            var input=new ArticulatedServoPredictor.BodyState(3,new Matrix3d().scaling(.5),position,orientation,new Vector3d(),new Vector3d());
            var world=new ArticulatedServoPredictor.WorldMotor(0,parentFrame,anchor,parentAnchor,new double[3],new double[]{linearGoal.x,linearGoal.y,linearGoal.z},k,d,caps,63,false,0,childFrame);
            var predicted=ArticulatedServoPredictor.predict(List.of(input),List.of(),null,List.of(world),new Vector3d(),dt,18,2,2,550,4,false).bodies().getFirst();
            Rapier3D.step(scene,dt);
            double[] actual=new double[7];Rapier3D.getPose(scene,1,actual);
            double rotationError=error(new Quaterniond(actual[3],actual[4],actual[5],actual[6]),predicted.orientation());
            double positionError=new Vector3d(actual[0],actual[1],actual[2]).distance(predicted.position());
            maximumRotationError=Math.max(maximumRotationError,rotationError);maximumPositionError=Math.max(maximumPositionError,positionError);cases++;
            CSV.append(String.format(Locale.ROOT,"%.0f,%.3f,%.3f,0.000,Infinity,63,false,%.9f,0.000000000,%.12f,0.000000000000%n",1/dt,yaw,childTilt,rotationError,positionError));
            if(!Double.isFinite(rotationError)||rotationError>=.001||!Double.isFinite(positionError)||positionError>=1e-6)throw new AssertionError("Mutable generic world frame prediction differs from native: yaw="+yaw+", child="+childTilt+", rotation="+rotationError+", position="+positionError);
        }finally{Rapier3D.dispose(scene);}
    }
    private static void runSingleGainWorldMotor(double dt,boolean springOnly,double cap) {
        long scene=Rapier3D.initialize(0,0,0,0);
        try {
            Rapier3D.configFrequencyAndDamping(40,5);Rapier3D.configSolverIterations(18,2,2);Rapier3D.configMinIslandSize(128);
            Quaterniond orientation=springOnly?new Quaterniond().rotationY(.03):new Quaterniond();
            Vector3d omega=springOnly?new Vector3d():new Vector3d(0,1,0);
            Rapier3D.createBox(scene,1,3,.5,.5,.5,pose(new Vector3d(),orientation));
            Rapier3D.addLinearAngularVelocities(scene,1,0,0,0,omega.x,omega.y,omega.z,false);
            long world=Rapier3D.addFreeConstraint(scene,-1,1,0,0,0,0,0,0,0,0,0,1);
            double stiffness=springOnly?10000:0,damping=springOnly?0:100;
            Rapier3D.setConstraintMotor(scene,world,4,0,stiffness,damping,true,cap);
            double[] stiffnessAxes=new double[6],dampingAxes=new double[6],caps=new double[6];
            stiffnessAxes[4]=stiffness;dampingAxes[4]=damping;java.util.Arrays.fill(caps,Double.POSITIVE_INFINITY);caps[4]=cap;
            var body=new ArticulatedServoPredictor.BodyState(3,new Matrix3d().scaling(.5,.5,.5),new Vector3d(),orientation,new Vector3d(),omega);
            var motor=new ArticulatedServoPredictor.WorldMotor(0,new Quaterniond(),new Vector3d(),new Vector3d(),new double[3],new double[3],stiffnessAxes,dampingAxes,caps,1<<4);
            var prediction=ArticulatedServoPredictor.predict(List.of(body),List.of(),null,List.of(motor),new Vector3d(),dt,18,2,2,550,4,false);
            Rapier3D.step(scene,dt);
            double[] actual=new double[7];Rapier3D.getPose(scene,1,actual);
            double rotationError=error(new Quaterniond(actual[3],actual[4],actual[5],actual[6]),prediction.bodies().getFirst().orientation());
            double positionError=new Vector3d(actual[0],actual[1],actual[2]).distance(prediction.bodies().getFirst().position());
            maximumRotationError=Math.max(maximumRotationError,rotationError);maximumPositionError=Math.max(maximumPositionError,positionError);cases++;
            CSV.append(String.format(Locale.ROOT,"%.0f,%.3f,0.000,0.000,%.0f,%d,false,%.9f,0.000000000,%.12f,0.000000000000%n",1/dt,springOnly?.03:0,cap,1<<4,rotationError,positionError));
            if(!Double.isFinite(rotationError) || rotationError>=0.001 || !Double.isFinite(positionError) || positionError>=1.0e-6)throw new AssertionError("Native single-gain world motor prediction exceeds strict precision threshold: springOnly="+springOnly+", dt="+dt+", cap="+cap+", rotation="+rotationError);
            if(!orientation.equals(body.orientation(),1e-12) || omega.distance(body.angularVelocity())>1e-12 || stiffnessAxes[4]!=stiffness || dampingAxes[4]!=damping || caps[4]!=cap)throw new AssertionError("Single-gain prediction modified caller-owned snapshots");
        }finally{Rapier3D.dispose(scene);}
    }
    private static void run(double dt,double yaw,double rootGoal,double linear,double cap,int mask,boolean staffFirst) {
        run(dt,yaw,rootGoal,linear,cap,mask,staffFirst,false);
    }
    private static void run(double dt,double yaw,double rootGoal,double linear,double cap,int mask,boolean staffFirst,boolean childOnly) {
        long scene=Rapier3D.initialize(0,-11,0,0);
        try {
            Rapier3D.configFrequencyAndDamping(40,5);Rapier3D.configSolverIterations(18,2,2);Rapier3D.configMinIslandSize(128);
            Quaterniond qa=new Quaterniond(),qb=new Quaterniond().rotationY(yaw),frame=BearingServoMath.hingeFrame(new Quaterniond(),0);
            Vector3d pa=new Vector3d(),ra=new Vector3d(.8,2,0),rb=new Vector3d(.1,.2,0),pb=new Vector3d(ra).sub(qb.transform(new Vector3d(rb)));
            Rapier3D.createBox(scene,1,7.5,2,.5,1,pose(pa,qa));Rapier3D.createBox(scene,2,3,.5,.5,.5,pose(pb,qb));
            Quaterniond queued=new Quaterniond().rotationY(rootGoal).rotateX(.02).rotateZ(.01);
            Vector3d anchor=new Vector3d(.8,0,0),goal=new Vector3d(.8,linear,0),local=queued.transformInverse(new Vector3d(goal));
            long hinge=0,staff=0;
            for(int slot=0;slot<2;slot++) {
                boolean addStaff=slot==0?staffFirst:!staffFirst;
                if(addStaff) {
                    staff=Rapier3D.addFreeConstraint(scene,-1,1,0,0,0,anchor.x,anchor.y,anchor.z,queued.x,queued.y,queued.z,queued.w);
                    for(int axis=3;axis<6;axis++)if((mask&(1<<axis))!=0)Rapier3D.setConstraintMotor(scene,staff,axis,0,10000,850,false,0);
                    for(int axis=0;axis<3;axis++)if((mask&(1<<axis))!=0)Rapier3D.setConstraintMotor(scene,staff,axis,axis==0?local.x:axis==1?local.y:local.z,2650,125,false,0);
                }else {
                    hinge=Rapier3D.addGenericConstraint(scene,1,2,ra.x,ra.y,ra.z,frame.x,frame.y,frame.z,frame.w,rb.x,rb.y,rb.z,frame.x,frame.y,frame.z,frame.w,0b110111);
                    Rapier3D.setConstraintContactsEnabled(scene,hinge,false);
                    if (!childOnly) Rapier3D.setConstraintMotor(scene,hinge,3,yaw-.025,120000,1000,true,cap);
                    else {
                        Rapier3D.setConstraintMotor(scene,hinge,3,0,0,0,true,0);
                        long drive=Rapier3D.addGenericConstraint(scene,-1,2,0,0,0,frame.x,frame.y,frame.z,frame.w,
                                0,0,0,frame.x,frame.y,frame.z,frame.w,0);
                        Rapier3D.setConstraintMotor(scene,drive,3,yaw-.025,120000,1000,true,cap);
                    }
                }
            }
            var bodies=List.of(new ArticulatedServoPredictor.BodyState(7.5,new Matrix3d().scaling(7.5*1.25/3,7.5*5/3,7.5*4.25/3),pa,qa,new Vector3d(),new Vector3d()),
                    new ArticulatedServoPredictor.BodyState(3,new Matrix3d().scaling(.5,.5,.5),pb,qb,new Vector3d(),new Vector3d()));
            var hinges=List.of(new ArticulatedServoPredictor.HingeMotor(0,1,ra,rb,frame,frame,yaw-.025,120000,1000,cap,
                    staffFirst?1:0,childOnly,staffFirst?2:1));
            var staffConfig=new ArticulatedServoPredictor.StaffMotor(0,queued,anchor,goal,10000,850,2650,125);
            var worldConfig=new ArticulatedServoPredictor.WorldMotor(0,queued,anchor,new Vector3d(),new double[3],
                    new double[]{local.x,local.y,local.z},new double[]{2650,2650,2650,10000,10000,10000},
                    new double[]{125,125,125,850,850,850},new double[]{Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},mask,staffFirst);
            var predicted=mask==63
                    ? ArticulatedServoPredictor.predict(bodies,hinges,staffConfig,new Vector3d(0,-11,0),dt,18,2,2,550,4,staffFirst)
                    : ArticulatedServoPredictor.predict(bodies,hinges,null,List.of(worldConfig),new Vector3d(0,-11,0),dt,18,2,2,550,4,staffFirst);
            Rapier3D.step(scene,dt);
            double[] a=new double[7],b=new double[7];Rapier3D.getPose(scene,1,a);Rapier3D.getPose(scene,2,b);
            var actualA=new Quaterniond(a[3],a[4],a[5],a[6]);var actualB=new Quaterniond(b[3],b[4],b[5],b[6]);
            double errorA=error(actualA,predicted.bodies().get(0).orientation()),errorB=error(actualB,predicted.bodies().get(1).orientation());
            double positionA=new Vector3d(a[0],a[1],a[2]).distance(predicted.bodies().get(0).position()),positionB=new Vector3d(b[0],b[1],b[2]).distance(predicted.bodies().get(1).position());
            maximumRotationError=Math.max(maximumRotationError,Math.max(errorA,errorB));maximumPositionError=Math.max(maximumPositionError,Math.max(positionA,positionB));cases++;
            CSV.append(String.format(Locale.ROOT,"%.0f,%.1f,%.3f,%.3f,%.0f,%d,%s,%.9f,%.9f,%.12f,%.12f%n",1/dt,yaw,rootGoal,linear,cap,mask,staffFirst,errorA,errorB,positionA,positionB));
            if(!Double.isFinite(errorA) || !Double.isFinite(errorB) || errorA>=0.001 || errorB>=0.001
                    || !Double.isFinite(positionA) || !Double.isFinite(positionB) || positionA>=1.0e-6 || positionB>=1.0e-6)throw new AssertionError("Native articulated prediction exceeds strict precision threshold");
            if(pa.lengthSquared()!=0 || Math.abs(qb.y-Math.sin(yaw/2))>1e-12 || Math.abs(pb.y-1.8)>1e-12)throw new AssertionError("Prediction modified caller-owned snapshots");
        }finally{Rapier3D.dispose(scene);}
    }
    private static double error(Quaterniond actual,Quaterniond expected){Quaterniond difference=new Quaterniond(actual).normalize().mul(new Quaterniond(expected).normalize().conjugate()).normalize();return Math.toDegrees(2*Math.atan2(Math.sqrt(difference.x*difference.x+difference.y*difference.y+difference.z*difference.z),Math.abs(difference.w)));}
    private static double[] pose(Vector3d position,Quaterniond q){return new double[]{position.x,position.y,position.z,q.x,q.y,q.z,q.w};}
}
