package com.hooya.stabilizedturret.content.bearing;

import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.constraint.generic.RapierGenericConstraintHandle;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Diagnostic replay of the exported five-body service fixture using existing Sable JNI only. */
public final class NativeArticulatedTurnChecks {
    private static final String FIXTURE = """
capture=ServerTickEvent.Pre before sustained-turn steering
age=200
dimension=minecraft:overworld
body_order=carrier,yaw1,pitch1,yaw2,pitch2
inertia_order=m00,m01,m02,m10,m11,m12,m20,m21,m22 (JOML names)
dimension.universal_drag=0.090000003576278690
dimension.gravity=0.0000000000000000,-11.000000000000000,0.0000000000000000
body.carrier.uuid=7506dc00-e907-4aa4-93a0-1bb5d327be65
body.carrier.mass=7.5000000000000000
body.carrier.com_local=20481031.033333324,129.40770833333326,20481032.500000000
body.carrier.com_world=39.033336639404300,171.39344787597656,0.50000435113906860
body.carrier.inertia_local=4.3263658854164335,3.6308333379111337,0.0000000000000000,3.6308333379111337,29.116666663794504,0.0000000000000000,0.0000000000000000,0.0000000000000000,32.193032549210940
body.carrier.position=39.033336639404300,171.39344787597656,0.50000435113906860
body.carrier.orientation_xyzw=-1.4057573025638703e-05,1.4707698028360028e-06,2.2339147108141333e-05,1.0000000000000000
body.carrier.rotation_point=20481031.033333324,129.40770833333326,20481032.500000000
body.carrier.velocity=0.0000000000000000,0.0000000000000000,0.0000000000000000
body.carrier.omega=0.0000000000000000,0.0000000000000000,0.0000000000000000
body.yaw1.uuid=4f9c0e2f-4f88-46e2-95e5-ed95bffe3141
body.yaw1.mass=3.0000000000000000
body.yaw1.com_local=20481032.467968754,128.69791666666663,20483080.500000000
body.yaw1.com_world=40.472187042236330,173.68469238281250,0.51596826314926150
body.yaw1.inertia_local=1.2301432291666579,0.077075196028282380,0.0000000000000000,0.077075196028282380,0.50923401078098410,0.0000000000000000,0.0000000000000000,0.0000000000000000,1.2393772399476422
body.yaw1.position=40.472187042236330,173.68469238281250,0.51596826314926150
body.yaw1.orientation_xyzw=-1.9620358216343448e-05,0.25881659984588623,-9.2220388978603300e-06,0.96592652797698970
body.yaw1.rotation_point=20481032.467968754,128.69791666666663,20483080.500000000
body.yaw1.velocity=0.0000000000000000,0.0000000000000000,0.0000000000000000
body.yaw1.omega=0.0000000000000000,0.0000000000000000,0.0000000000000000
body.pitch1.uuid=2981a6bc-3eca-40b1-a0bd-0bb0a3d4993f
body.pitch1.mass=2.2500000000000000
body.pitch1.com_local=20481032.430555556,128.50000000000000,20485128.500000000
body.pitch1.com_world=41.306682586669920,174.48674011230470,0.034144382923841476
body.pitch1.inertia_local=0.37500000000000000,0.0000000000000000,0.0000000000000000,0.0000000000000000,0.46180555568490590,0.0000000000000000,0.0000000000000000,0.0000000000000000,0.46180555568490590
body.pitch1.position=41.306682586669920,174.48674011230470,0.034144382923841476
body.pitch1.orientation_xyzw=-0.084189884364604950,0.25783291459083557,0.022543042898178100,0.96225053071975710
body.pitch1.rotation_point=20481032.430555556,128.50000000000000,20485128.500000000
body.pitch1.velocity=0.0000000000000000,0.0000000000000000,0.0000000000000000
body.pitch1.omega=0.0000000000000000,0.0000000000000000,0.0000000000000000
body.yaw2.uuid=49ab6cb5-35bc-4bfb-9c57-1f373f8a3e9a
body.yaw2.mass=3.0000000000000000
body.yaw2.com_local=20481032.532031246,128.69791666666663,20487176.500000000
body.yaw2.com_world=36.526138305664060,173.68452453613280,0.51831889152526860
body.yaw2.inertia_local=1.2301432291666579,-0.077075193787287430,0.0000000000000000,-0.077075193787287430,0.50923401095997240,0.0000000000000000,0.0000000000000000,0.0000000000000000,1.2393772401266303
body.yaw2.position=36.526138305664060,173.68452453613280,0.51831889152526860
body.yaw2.orientation_xyzw=-1.2432047697075177e-05,-0.30070981383323670,4.5128988858778030e-05,0.95371574163436890
body.yaw2.rotation_point=20481032.532031246,128.69791666666663,20487176.500000000
body.yaw2.velocity=0.0000000000000000,0.0000000000000000,0.0000000000000000
body.yaw2.omega=0.0000000000000000,0.0000000000000000,0.0000000000000000
body.pitch2.uuid=54828fe0-5c22-48a4-aaaa-2c12f0ed3d69
body.pitch2.mass=2.2500000000000000
body.pitch2.com_local=20481032.569444444,128.50000000000000,20489224.500000000
body.pitch2.com_world=35.736751556396484,174.48651123046875,-0.034419942647218704
body.pitch2.inertia_local=0.37500000000000000,0.0000000000000000,0.0000000000000000,0.0000000000000000,0.46180555568490590,0.0000000000000000,0.0000000000000000,0.0000000000000000,0.46180555568490590
body.pitch2.position=35.736751556396484,174.48651123046875,-0.034419942647218704
body.pitch2.orientation_xyzw=0.041586216539144516,-0.30042159557342530,0.013162086717784405,0.95280861854553220
body.pitch2.rotation_point=20481032.569444444,128.50000000000000,20489224.500000000
body.pitch2.velocity=0.0000000000000000,0.0000000000000000,0.0000000000000000
body.pitch2.omega=0.0000000000000000,0.0000000000000000,0.0000000000000000
lock.yaw1.parent_uuid=7506dc00-e907-4aa4-93a0-1bb5d327be65
lock.yaw1.child_uuid=4f9c0e2f-4f88-46e2-95e5-ed95bffe3141
lock.yaw1.bearing_facing=up
lock.yaw1.plate_facing=up
lock.yaw1.parent_local_anchor=20481032.500000000,131.50000000000000,20481032.500000000
lock.yaw1.child_local_pivot=20481032.500000000,128.49899999995250,20483080.500000000
lock.yaw1.parent_world_anchor=40.499909833929050,173.48580506783267,0.49994121101152705
lock.yaw1.child_world_pivot=40.499925472451900,173.48577482031470,0.49996127074108800
lock.yaw1.native_zero_degrees=0.0000000000000000
lock.pitch1.parent_uuid=4f9c0e2f-4f88-46e2-95e5-ed95bffe3141
lock.pitch1.child_uuid=2981a6bc-3eca-40b1-a0bd-0bb0a3d4993f
lock.pitch1.bearing_facing=east
lock.pitch1.plate_facing=east
lock.pitch1.parent_local_anchor=20481033.500000000,129.50000000000000,20483080.500000000
lock.pitch1.child_local_pivot=20481032.499000000,128.50000000000000,20485128.500000000
lock.pitch1.parent_world_anchor=41.365961086798634,174.48674684765095,-7.7043059497183820e-05
lock.pitch1.child_world_pivot=41.365957393223510,174.48673807700345,-7.7531912663783560e-05
lock.pitch1.native_zero_degrees=0.0000000000000000
lock.yaw2.parent_uuid=7506dc00-e907-4aa4-93a0-1bb5d327be65
lock.yaw2.child_uuid=49ab6cb5-35bc-4bfb-9c57-1f373f8a3e9a
lock.yaw2.bearing_facing=up
lock.yaw2.plate_facing=up
lock.yaw2.parent_local_anchor=20481028.500000000,131.50000000000000,20481032.500000000
lock.yaw2.child_local_pivot=20481032.500000000,128.49899999995250,20487176.500000000
lock.yaw2.parent_world_anchor=36.499909837938660,173.48562635482133,0.49995297968221510
lock.yaw2.child_world_pivot=36.499915635124140,173.48560487353288,0.49995641980376093
lock.yaw2.native_zero_degrees=0.0000000000000000
lock.pitch2.parent_uuid=49ab6cb5-35bc-4bfb-9c57-1f373f8a3e9a
lock.pitch2.child_uuid=54828fe0-5c22-48a4-aaaa-2c12f0ed3d69
lock.pitch2.bearing_facing=west
lock.pitch2.plate_facing=west
lock.pitch2.parent_local_anchor=20481031.500000000,129.50000000000000,20487176.500000000
lock.pitch2.child_local_pivot=20481032.501000000,128.50000000000000,20489224.500000000
lock.pitch2.parent_world_anchor=35.680689721372870,174.48651131187228,-0.073677787705009880
lock.pitch2.child_world_pivot=35.680685476332560,174.48650472110990,-0.073678531199010970
lock.pitch2.native_zero_degrees=8.8464438690624230e-16

""";
    private static final Quaterniond Y_FRAME = BearingServoMath.hingeFrame(new Quaterniond(), 0);
    private static final Vector3d FORWARD = new Vector3d(0,0,1);
    private static final String[] NAMES = {"carrier","yaw1","pitch1","yaw2","pitch2"};
    private static final double[] SPEEDS = {120,-120,360,30};
    private static long acceptedCalls,rejectedCalls;
    private static final long[] iterationCounts=new long[4];
    private static final Map<ArticulatedServoSolver.Failure,Long> rejectionReasons=new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        System.setProperty("bodyMode", "SUBLEVEL");
        System.setProperty("modelSupport", "true");
        Map<String,String> snapshot = new LinkedHashMap<>();
        for (String line : FIXTURE.lines().toList()) {
            int split=line.indexOf('=');
            if(split>0)snapshot.put(line.substring(0,split),line.substring(split+1));
        }
        StringBuilder report = new StringBuilder("Diagnostic actual service five-body mass/tensor/anchor replay\n");
        StringBuilder csv = new StringBuilder("hz,speed_deg_s,peak_ray1_deg,peak_ray2_deg,peak_carrier_omega,peak_child_omega,strict_005_pass\n");
        boolean passed=true;
        for (int hz : new int[]{40,120}) {
            Result result=run(snapshot,hz);
            report.append(result.report);
            csv.append(result.csv);
            passed &= result.passed;
        }
        report.insert(0,passed?"PASS\n":"FAIL: strict <0.05 degree ray error\n");
        Files.writeString(Path.of("bearing-articulated-turn-result.txt"),report);
        Files.writeString(Path.of("bearing-articulated-turn.csv"),csv);
        if(!passed)throw new AssertionError("Strict actual-mass replay ray accuracy failed");
    }

    private static Result run(Map<String,String> snapshot,int hz) throws Exception {
        acceptedCalls=0;rejectedCalls=0;java.util.Arrays.fill(iterationCounts,0);rejectionReasons.clear();
        double dt=1.0/hz;
        String mode=System.getProperty("velocityMode","BASE");
        Vector3d gravity=new Vector3d(numbers(snapshot,"dimension.gravity"));
        long id=Rapier3D.initialize(gravity.x,gravity.y,gravity.z,number(snapshot,"dimension.universal_drag"));
        Rapier3D.configFrequencyAndDamping(40,5);
        Rapier3D.configSolverIterations(18,2,2);
        Rapier3D.configMinIslandSize(128);
        try {
            Map<String,Body> bodies=new LinkedHashMap<>();
            Vector3d translation=new Vector3d(numbers(snapshot,"body.carrier.com_world"));
            for(int i=0;i<NAMES.length;i++) {
                String name=NAMES[i], prefix="body."+name;
                double[] q=numbers(snapshot,prefix+".orientation_xyzw");
                Body body=new Body(i+1,number(snapshot,prefix+".mass"),new Vector3d(numbers(snapshot,prefix+".com_local")),
                        new Matrix3d().set(numbers(snapshot,prefix+".inertia_local")));
                Vector3d position=new Vector3d(numbers(snapshot,prefix+".com_world")).sub(translation);
                double[] pose={position.x,position.y,position.z,q[0],q[1],q[2],q[3]};
                if(System.getProperty("bodyMode","BOX").equals("SUBLEVEL")) {
                    Rapier3D.createSubLevel(id,body.id,pose);
                    Rapier3D.setCenterOfMass(id,body.id,0,0,0);
                    Rapier3D.setLocalBounds(id,body.id,-1,-1,-1,1,1,1);
                } else Rapier3D.createBox(id,body.id,0,0.01,0.01,0.01,pose);
                // Existing JNI accepts additional mass/tensor on an existing body. The zero-mass box adds no inertia.
                Rapier3D.setMassProperties(id,body.id,body.mass,new double[]{0,0,0},numbers(snapshot,prefix+".inertia_local"));
                if(!Boolean.getBoolean("zeroInitialVelocity")) {
                    double[] v=numbers(snapshot,prefix+".velocity"),w=numbers(snapshot,prefix+".omega");
                    Rapier3D.addLinearAngularVelocities(id,body.id,v[0],v[1],v[2],w[0],w[1],w[2],false);
                }
                bodies.put(name,body);
            }
            boolean supportBeforeHinges=Boolean.getBoolean("supportBeforeHinges");
            if(supportBeforeHinges)addSupport(id);
            Vector3d held1=new Quaterniond().rotationY(Math.toRadians(30)).rotateX(Math.toRadians(-10)).transform(new Vector3d(FORWARD));
            Vector3d held2=new Quaterniond().rotationY(Math.toRadians(-35)).rotateX(Math.toRadians(5)).transform(new Vector3d(FORWARD));
            List<Axis> axes=new ArrayList<>();
            axes.add(axis(snapshot,id,"yaw1",bodies.get("carrier"),bodies.get("yaw1"),bodies.get("pitch1"),held1,true));
            axes.add(axis(snapshot,id,"pitch1",bodies.get("yaw1"),bodies.get("pitch1"),bodies.get("pitch1"),held1,false));
            axes.add(axis(snapshot,id,"yaw2",bodies.get("carrier"),bodies.get("yaw2"),bodies.get("pitch2"),held2,true));
            axes.add(axis(snapshot,id,"pitch2",bodies.get("yaw2"),bodies.get("pitch2"),bodies.get("pitch2"),held2,false));
            // BearingIntegration creates this support at age 720, after both turrets' hinges exist.
            // Native PGS responds to row order, so reproduce that order in the default service replay.
            // The opt-in supportBeforeHinges mode keeps the original boundary case and strict checks.
            if(!supportBeforeHinges)addSupport(id);
            StringBuilder report=new StringBuilder(), csv=new StringBuilder();
            StringBuilder trace=new StringBuilder("hz,time,phase,axis,error_deg,goal,measured,predicted_rate,base_rate,native_relative_rate,pose_rate,bias,velocity_feed,residual,motor_target,cap,carrier_omega\n");
            boolean passed=true;
            double predictionPeak1=0,predictionPeak2=0;
            double predictionSteadyPeak1=0,predictionSteadyPeak2=0;
            long inverseNanos=0,inverseCalls=0;
            RootForecast rootForecast=new RootForecast();
            int phaseSteps=4*hz;
            double maxCarrierOmega=0,maxChildOmega=0;
            for(int phase=0;phase<SPEEDS.length;phase++) {
                double speed=Math.toRadians(SPEEDS[phase]),peak1=0,peak2=0;
                double phaseModel1=0,phaseModel2=0,startup1=0,startup2=0;
                for(int step=0;step<phaseSteps;step++) {
                    Rapier3D.tick(id, dt);
                    Vector3d carrierOmega=omega(id,1);
                    Rapier3D.addLinearAngularVelocities(id,1,0,0,0,0,speed-carrierOmega.y,0,false);
                    Quaterniond carrierDelta=rootForecast.sample(orientation(id,1),omega(id,1),dt);
                    if(System.getProperty("positionMode","GOAL").startsWith("MOMENTUM")) {
                        carrierDelta=momentumYawDelta(id,bodies,axes,dt,number(snapshot,"dimension.universal_drag"));
                    }
                    for(Axis axis:axes) {
                        Quaterniond parentQ=orientation(id,axis.parent.id),childQ=orientation(id,axis.child.id);
                        Vector3d worldAxis=parentQ.transform(new Vector3d(axis.localAxis));
                        Vector3d actual=orientation(id,axis.barrel.id).transform(new Vector3d(FORWARD));
                        double measured=BearingServoMath.jointAngle(new Quaterniond(parentQ).mul(axis.frame),
                                new Quaterniond(childQ).mul(axis.frame));
                        double goal=BearingServoMath.wrapRadians(measured+BearingServoMath.signedDirectionAngle(actual,axis.held,worldAxis));
                        if(!axis.yaw)goal=Math.clamp(goal,-Math.PI/2,Math.PI/2);
                        double base=BearingServoMath.stabilizationRate(axis.held,worldAxis,omega(id,axis.parent.id));
                        double predicted=axis.target.sample(goal,base,dt);
                        if(!System.getProperty("positionMode","GOAL").equals("GOAL")) {
                            Vector3d rootYawAxis=orientation(id,1).transform(new Vector3d(0,1,0));
                            double yawDisplacement=StaffCarrierForecast.carrierJointDisplacement(actual,axis.held,rootYawAxis,carrierDelta);
                            double displacement=axis.yaw?yawDisplacement:StaffCarrierForecast.pitchDisplacement(
                                    actual,axis.held,worldAxis,carrierDelta,rootYawAxis,yawDisplacement);
                            predicted=displacement/dt;
                        }
                        double nativeRate=omega(id,axis.child.id).sub(omega(id,axis.parent.id)).dot(worldAxis);
                        double poseRate=axis.bias.poseRate(measured,nativeRate,dt);
                        double bias=axis.bias.bias;
                        Vector3d childPivot=childQ.transform(new Vector3d(axis.childPivot)).add(position(id,axis.child.id));
                        Vector3d parentPivot=parentQ.transform(new Vector3d(axis.parentAnchor)).add(position(id,axis.parent.id));
                        double childInertia=inertia(axis.child,id,childPivot,worldAxis);
                        if(axis.yaw)childInertia+=inertia(axis.barrel,id,childPivot,worldAxis);
                        BearingServoMath.Motor motor=BearingServoMath.motor(childInertia,inertia(axis.parent,id,parentPivot,worldAxis));
                        double velocity=BearingServoMath.heldTargetVelocityRate(base,dt,motor);
                        switch(mode) {
                            case "BETA_BASE_BIAS" -> velocity+=bias;
                            case "BETA_TARGET_BIAS" -> velocity=BearingServoMath.heldTargetVelocityRate(predicted,dt,motor)+bias;
                            case "FULL_OBSERVER" -> velocity=predicted+bias;
                            case "BETA_CORRECTED" -> velocity=BearingServoMath.heldTargetVelocityRate(base+bias,dt,motor);
                            case "BETA_TARGET_ONLY" -> velocity=BearingServoMath.heldTargetVelocityRate(predicted,dt,motor);
                            case "BETA_TARGET_CORRECTED" -> velocity=BearingServoMath.heldTargetVelocityRate(predicted+bias,dt,motor);
                            case "ZERO" -> velocity=0;
                            case "BETA_POSE_PARENT" -> velocity=BearingServoMath.heldTargetVelocityRate(
                                    BearingServoMath.stabilizationRate(axis.held,worldAxis,axis.parentPose.sample(parentQ,omega(id,axis.parent.id),dt)),dt,motor);
                            default -> {}
                        }
                        double reference=axis.yaw?measured:0;
                        if(axis.yaw)axis.joint.hinge().setFrame2(axis.childPivot,BearingServoMath.yawMotorFrame(axis.frame,measured));
                        double residual=axis.residual.update(goal,measured,dt);
                        double command=BearingServoMath.servoGoal(BearingServoMath.wrapRadians(goal-reference+residual),predicted,velocity,dt,motor);
                        double worldRate=axis.worldVelocity.sample(actual,worldAxis,dt);
                        double dampingScale=Double.parseDouble(System.getProperty("worldDampingScale","0"));
                        double tau=System.getProperty("dampingUnit","MOTOR").equals("DT")?dt:motor.damping()/motor.stiffness();
                        command=BearingServoMath.wrapRadians(command-dampingScale*tau*worldRate);
                        if(!Double.isFinite(command))throw new AssertionError("nonfinite native command");
                        axis.measured=measured;
                        axis.command=command;
                        axis.motor=motor;
                        axis.joint.drive(command,motor);
                        trace.append(String.format(Locale.ROOT,"%d,%.9f,%d,%s,%.9f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.9f,%.9f%n",
                                hz,(phase*phaseSteps+step)*dt,phase,axis.name,error(actual,axis.held),goal,measured,predicted,base,nativeRate,poseRate,bias,velocity,residual,command,motor.maxTorque(),omega(id,1).length()));
                    }
                    long inverseStart=System.nanoTime();
                    NativePrediction prediction=inverseEndpoint(id,bodies,axes,dt,gravity);
                    inverseNanos+=System.nanoTime()-inverseStart;inverseCalls++;
                    for(int body=1;body<=5;body++)Rapier3D.wakeUpObject(id,body);
                    Rapier3D.step(id,dt);
                    predictionPeak1=Math.max(predictionPeak1,error(orientation(id,3).transform(new Vector3d(FORWARD)),prediction.rayA));
                    predictionPeak2=Math.max(predictionPeak2,error(orientation(id,5).transform(new Vector3d(FORWARD)),prediction.rayB));
                    double error1=error(orientation(id,3).transform(new Vector3d(FORWARD)),held1);
                    double error2=error(orientation(id,5).transform(new Vector3d(FORWARD)),held2);
                    if(step>=hz) {
                        peak1=Math.max(peak1,error1);peak2=Math.max(peak2,error2);
                        phaseModel1=Math.max(phaseModel1,error(orientation(id,3).transform(new Vector3d(FORWARD)),prediction.rayA));
                        phaseModel2=Math.max(phaseModel2,error(orientation(id,5).transform(new Vector3d(FORWARD)),prediction.rayB));
                    }else{startup1=Math.max(startup1,error1);startup2=Math.max(startup2,error2);}
                    maxCarrierOmega=Math.max(maxCarrierOmega,omega(id,1).length());
                    for(int body=2;body<=5;body++)maxChildOmega=Math.max(maxChildOmega,omega(id,body).length());
                    if(!Double.isFinite(error1)||!Double.isFinite(error2)||!Double.isFinite(maxChildOmega))throw new AssertionError("nonfinite replay state");
                }
                boolean phasePass=peak1<0.05&&peak2<0.05&&startup1<0.05&&startup2<0.05;
                passed &=phasePass;
                String line=String.format(Locale.ROOT,"%dHz %.0f deg/s %s: ray peaks %.9f/%.9f deg max omega %.9f/%.9f%n",hz,SPEEDS[phase],phasePass?"PASS":"FAIL",peak1,peak2,maxCarrierOmega,maxChildOmega);
                report.append(line);System.out.print(line);
                predictionSteadyPeak1=Math.max(predictionSteadyPeak1,phaseModel1);predictionSteadyPeak2=Math.max(predictionSteadyPeak2,phaseModel2);
                String fidelityPhase=String.format(Locale.ROOT,"  model/native steady %.9f/%.9f deg; initial 1s ray peak %.9f/%.9f deg%n",phaseModel1,phaseModel2,startup1,startup2);
                report.append(fidelityPhase);System.out.print(fidelityPhase);
                csv.append(String.format(Locale.ROOT,"%d,%.0f,%.9f,%.9f,%.9f,%.9f,%s%n",hz,SPEEDS[phase],peak1,peak2,maxCarrierOmega,maxChildOmega,phasePass));
            }
            String fidelity=String.format(Locale.ROOT,"%dHz model/native discrepancy %.9f/%.9f deg; inverse avg %.3f ms%n",hz,predictionPeak1,predictionPeak2,inverseNanos*1e-6/inverseCalls);
            report.append(fidelity);System.out.print(fidelity);
            String adaptive=String.format(Locale.ROOT,"  production solver accepted=%d rejected=%d iterations=%s rejection=%s%n",acceptedCalls,rejectedCalls,java.util.Arrays.toString(iterationCounts),rejectionReasons);
            report.append(adaptive);System.out.print(adaptive);
            report.append(String.format(Locale.ROOT,"  steady model/native discrepancy %.9f/%.9f deg%n",predictionSteadyPeak1,predictionSteadyPeak2));
            Files.writeString(Path.of("actual-server-replay-trace-"+hz+".csv"),trace);
            return new Result(passed,report.toString(),csv.toString());
        }finally{Rapier3D.dispose(id);}
    }

    /** Soft FreeConstraint position and X/Z angular motors, with yaw free, as in the service fixture. */
    private static void addSupport(long id) {
        if(System.getProperty("supportMode","FREE").equals("HINGE")) {
            long support=Rapier3D.addGenericConstraint(id,-1,1,0,0,0,Y_FRAME.x,Y_FRAME.y,Y_FRAME.z,Y_FRAME.w,
                    0,0,0,Y_FRAME.x,Y_FRAME.y,Y_FRAME.z,Y_FRAME.w,0b110111);
            Rapier3D.setConstraintContactsEnabled(id,support,false);
        } else {
            long support=Rapier3D.addFreeConstraint(id,-1,1,0,0,0,0,0,0,0,0,0,1);
            for(int axis:new int[]{0,1,2,3,5})Rapier3D.setConstraintMotor(id,support,axis,0,3e7,3e5,false,0);
        }
    }

    private static Axis axis(Map<String,String> snapshot,long id,String name,Body parent,Body child,Body barrel,Vector3d held,boolean yaw) {
        Vector3d anchor=new Vector3d(numbers(snapshot,"lock."+name+".parent_local_anchor")).sub(parent.localCom);
        Vector3d pivot=new Vector3d(numbers(snapshot,"lock."+name+".child_local_pivot")).sub(child.localCom);
        boolean west="west".equals(snapshot.get("lock."+name+".bearing_facing"));
        Quaterniond frame=yaw?new Quaterniond(Y_FRAME):west?new Quaterniond().rotationY(Math.PI):new Quaterniond();
        long handle=Rapier3D.addGenericConstraint(id,parent.id,child.id,anchor.x,anchor.y,anchor.z,frame.x,frame.y,frame.z,frame.w,
                pivot.x,pivot.y,pivot.z,frame.x,frame.y,frame.z,frame.w,0b110111);
        NativeBearingJoint joint=new NativeBearingJoint(new RapierGenericConstraintHandle(id,handle));
        joint.setContactsEnabled(false);
        if(!yaw)joint.hinge().setLimit(ConstraintJointAxis.ANGULAR_X,-Math.PI/2,Math.PI/2);
        return new Axis(name,parent,child,barrel,held,yaw,anchor,pivot,frame,yaw?new Vector3d(0,1,0):new Vector3d(west?-1:1,0,0),joint);
    }

    private static Quaterniond momentumYawDelta(long id,Map<String,Body> bodies,List<Axis> axes,double dt,double drag) {
        Body carrier=bodies.get("carrier");
        Vector3d center=position(id,carrier.id),carrierVelocity=velocity(id,carrier.id);
        Quaterniond carrierOrientation=orientation(id,carrier.id);
        Vector3d localY=carrierOrientation.transformInverse(new Vector3d(0,1,0));
        double carrierInertia=carrier.tensor.transform(new Vector3d(localY)).dot(localY);
        double momentum=worldMomentum(carrier,id).y;
        double[] masses=new double[4];Vector3d[] anchors=new Vector3d[4],offsets=new Vector3d[4];
        int index=0;
        for(Axis axis:axes)if(axis.yaw) {
            Vector3d anchor=carrierOrientation.transform(new Vector3d(axis.parentAnchor));
            for(Body child:new Body[]{axis.child,axis.barrel}) {
                Vector3d radius=position(id,child.id).sub(center);
                Vector3d relativeVelocity=velocity(id,child.id).sub(carrierVelocity);
                momentum+=worldMomentum(child,id).y+new Vector3d(radius).cross(relativeVelocity.mul(child.mass)).y;
                masses[index]=child.mass;anchors[index]=new Vector3d(anchor);offsets[index]=new Vector3d(radius).sub(anchor);index++;
            }
        }
        double integratedMomentum=momentum*(drag>0?-Math.expm1(-drag*dt)/drag:dt);
        double zeroCoefficient=coefficient(carrierInertia,masses,anchors,offsets,0);
        double displacement=integratedMomentum/zeroCoefficient;
        for(int iteration=0;iteration<8;iteration++) {
            double derivative=coefficient(carrierInertia,masses,anchors,offsets,displacement);
            if(!(Math.abs(derivative)>1e-9)||!Double.isFinite(displacement))throw new AssertionError("Singular articulated yaw momentum geometry");
            double error=integratedCoefficient(carrierInertia,masses,anchors,offsets,displacement)-integratedMomentum;
            displacement-=error/derivative;
        }
        if(!Double.isFinite(displacement))throw new AssertionError("Nonfinite articulated yaw prediction");
        return new Quaterniond().rotationY(displacement);
    }
    private static double coefficient(double carrierInertia,double[] masses,Vector3d[] anchors,Vector3d[] offsets,double angle) {
        double result=carrierInertia,c=Math.cos(angle),s=Math.sin(angle);
        for(int i=0;i<masses.length;i++) {
            Vector3d a=anchors[i],b=offsets[i];
            result+=masses[i]*(a.x*a.x+a.z*a.z+b.x*(c*a.x+s*a.z)+b.z*(-s*a.x+c*a.z));
        }
        return result;
    }
    private static double integratedCoefficient(double carrierInertia,double[] masses,Vector3d[] anchors,Vector3d[] offsets,double angle) {
        double result=carrierInertia*angle,c=Math.cos(angle),s=Math.sin(angle);
        for(int i=0;i<masses.length;i++) {
            Vector3d a=anchors[i],b=offsets[i];
            result+=masses[i]*((a.x*a.x+a.z*a.z)*angle+b.x*(s*a.x+(1-c)*a.z)+b.z*((c-1)*a.x+s*a.z));
        }
        return result;
    }
    private static Vector3d worldMomentum(Body body,long id) {
        Quaterniond q=orientation(id,body.id);
        Vector3d localOmega=q.transformInverse(omega(id,body.id));
        return q.transform(body.tensor.transform(localOmega));
    }
    private static Vector3d velocity(long id,int body){double[] v=new double[3];Rapier3D.getLinearVelocity(id,body,v);return new Vector3d(v);}
    private static double inertia(Body body,long id,Vector3d pivot,Vector3d axis) {
        Vector3d local=orientation(id,body.id).transformInverse(new Vector3d(axis));
        double center=body.tensor.transform(new Vector3d(local)).dot(local);
        return BearingServoMath.pivotInertia(center,body.mass,new Vector3d(pivot).sub(position(id,body.id)),axis);
    }
    private static double[] numbers(Map<String,String> snapshot,String key) {
        String value=snapshot.get(key);if(value==null)throw new IllegalArgumentException(key);
        String[] cells=value.split(",");double[] data=new double[cells.length];
        for(int i=0;i<cells.length;i++)data[i]=Double.parseDouble(cells[i]);return data;
    }
    private static double number(Map<String,String> snapshot,String key){return Double.parseDouble(snapshot.get(key));}
    private static Quaterniond orientation(long id,int body){double[] p=new double[7];Rapier3D.getPose(id,body,p);return new Quaterniond(p[3],p[4],p[5],p[6]);}
    private static Vector3d position(long id,int body){double[] p=new double[7];Rapier3D.getPose(id,body,p);return new Vector3d(p[0],p[1],p[2]);}
    private static Vector3d omega(long id,int body){double[] w=new double[3];Rapier3D.getAngularVelocity(id,body,w);return new Vector3d(w);}
    private static double error(Vector3d actual,Vector3d held){return Math.toDegrees(Math.acos(Math.clamp(actual.dot(held),-1,1)));}

    private record Body(int id,double mass,Vector3d localCom,Matrix3d tensor){}
    private record Result(boolean passed,String report,String csv){}
    private record NativeState(List<ArticulatedServoPredictor.BodyState> bodies){}
    private record NativePrediction(double[] residual,Vector3d rayA,Vector3d rayB){}
    private static NativeState capture(long id,Map<String,Body> bodies) {
        List<ArticulatedServoPredictor.BodyState> state=new ArrayList<>();
        for(Body body:bodies.values())state.add(new ArticulatedServoPredictor.BodyState(body.mass,
                new Matrix3d(body.tensor),position(id,body.id),orientation(id,body.id),velocity(id,body.id),omega(id,body.id)));
        return new NativeState(state);
    }
    private static NativePrediction endpoint(NativeState state,List<Axis> axes,double[] targets,double dt,Vector3d gravity) {
        var hinges=hinges(axes,targets);
        var output=ArticulatedServoPredictor.predict(state.bodies,hinges,null,worldSupport(),gravity,dt,18,2,2,550,4,false).bodies();
        Vector3d rayA=output.get(2).orientation().transform(new Vector3d(FORWARD)),rayB=output.get(4).orientation().transform(new Vector3d(FORWARD));
        Vector3d yawAxis=output.get(0).orientation().transform(new Vector3d(0,1,0));
        double[] residual=new double[4];
        for(int i=0;i<4;i++) {
            Axis axis=axes.get(i);
            Vector3d worldAxis=axis.yaw?yawAxis:output.get(axis.parent.id-1).orientation().transform(new Vector3d(axis.localAxis));
            residual[i]=BearingServoMath.signedDirectionAngle(i<2?rayA:rayB,axis.held,worldAxis);
        }
        return new NativePrediction(residual,rayA,rayB);
    }
    private static List<ArticulatedServoPredictor.HingeMotor> hinges(List<Axis> axes,double[] targets) {
        List<ArticulatedServoPredictor.HingeMotor> hinges=new ArrayList<>();
        for(int i=0;i<axes.size();i++) {
            Axis axis=axes.get(i);
            Quaterniond childFrame=axis.yaw?BearingServoMath.yawMotorFrame(axis.frame,axis.measured):new Quaterniond(axis.frame);
            hinges.add(new ArticulatedServoPredictor.HingeMotor(axis.parent.id-1,axis.child.id-1,
                    new Vector3d(axis.parentAnchor),new Vector3d(axis.childPivot),new Quaterniond(axis.frame),childFrame,
                    targets[i],axis.motor.stiffness(),axis.motor.damping(),axis.motor.maxTorque()));
        }
        return hinges;
    }
    private static List<ArticulatedServoPredictor.WorldMotor> worldSupport() {
        if(!Boolean.parseBoolean(System.getProperty("modelSupport","true")))return List.of();
        double[] stiffness=new double[6],damping=new double[6],cap=new double[6];
        java.util.Arrays.fill(stiffness,3e7);java.util.Arrays.fill(damping,3e5);java.util.Arrays.fill(cap,Double.POSITIVE_INFINITY);
        return List.of(new ArticulatedServoPredictor.WorldMotor(0,new Quaterniond(),new Vector3d(),new Vector3d(),
                new double[3],new double[3],stiffness,damping,cap,47,Boolean.getBoolean("supportBeforeHinges")));
    }
    private static NativePrediction inverseEndpoint(long id,Map<String,Body> bodies,List<Axis> axes,double dt,Vector3d gravity) {
        NativeState state=capture(id,bodies);double[] targets=axes.stream().mapToDouble(a->a.command).toArray();
        List<ArticulatedServoSolver.RayGoal> goals=new ArrayList<>();
        for(Axis axis:axes)goals.add(new ArticulatedServoSolver.RayGoal(axis.parent.id-1,axis.barrel.id-1,
                new Vector3d(axis.localAxis),new Vector3d(FORWARD),new Vector3d(axis.held)));
        var solved=ArticulatedServoSolver.solve(state.bodies,hinges(axes,targets),null,worldSupport(),gravity,dt,goals,18,2,2,550,4,false);
        if(solved.accepted())acceptedCalls++;else {rejectedCalls++;rejectionReasons.merge(solved.failure(),1L,Long::sum);}
        iterationCounts[Math.clamp(solved.iterations(),0,3)]++;
        targets=solved.targets();
        NativePrediction result=endpoint(state,axes,targets,dt,gravity);
        for(int i=0;i<4;i++)axes.get(i).joint.drive(targets[i],axes.get(i).motor);
        return result;
    }
    private static double[] solve4(double[][] matrix,double[] rhs) {
        int n=4;
        for(int c=0;c<n;c++) {
            int p=c;for(int r=c+1;r<n;r++)if(Math.abs(matrix[r][c])>Math.abs(matrix[p][c]))p=r;
            double[] swap=matrix[c];matrix[c]=matrix[p];matrix[p]=swap;double val=rhs[c];rhs[c]=rhs[p];rhs[p]=val;
            double div=matrix[c][c];if(Math.abs(div)<1e-10)return new double[4];
            for(int j=c;j<n;j++)matrix[c][j]/=div;rhs[c]/=div;
            for(int r=0;r<n;r++)if(r!=c) {
                double f=matrix[r][c];for(int j=c;j<n;j++)matrix[r][j]-=f*matrix[c][j];rhs[r]-=f*rhs[c];
            }
        }
        return rhs;
    }
    private static final class Axis {
        final String name;final Body parent,child,barrel;final Vector3d held;final boolean yaw;
        final Vector3d parentAnchor,childPivot,localAxis;final Quaterniond frame;final NativeBearingJoint joint;
        final FilteredTarget target=new FilteredTarget();final Bias bias=new Bias();
        final BearingServoMath.AimVelocity worldVelocity=new BearingServoMath.AimVelocity();
        final ParentPose parentPose=new ParentPose();final BearingServoMath.MotorResidual residual=new BearingServoMath.MotorResidual();
        double measured,command;BearingServoMath.Motor motor;
        Axis(String name,Body parent,Body child,Body barrel,Vector3d held,boolean yaw,Vector3d anchor,Vector3d pivot,Quaterniond frame,Vector3d localAxis,NativeBearingJoint joint){
            this.name=name;this.parent=parent;this.child=child;this.barrel=barrel;this.held=held;this.yaw=yaw;parentAnchor=anchor;childPivot=pivot;this.frame=frame;this.localAxis=localAxis;this.joint=joint;
        }
    }
    private static final class FilteredTarget {
        final BearingServoMath.MotorTarget target=new BearingServoMath.MotorTarget();
        boolean initialized;double previousRate,filteredDelta;
        double sample(double goal,double initial,double dt){
            double rate=target.velocity(goal,initial,dt),delta=initialized?rate-previousRate:0;
            filteredDelta+=(delta-filteredDelta)*(1-Math.exp(-2*dt));previousRate=rate;initialized=true;return rate+filteredDelta;
        }
    }
    private static final class Bias {
        boolean initialized;double previous,bias;
        double poseRate(double angle,double nativeRate,double dt){
            double pose=initialized?BearingServoMath.wrapRadians(angle-previous)/dt:nativeRate;
            double difference=nativeRate-pose,cutoff=Double.parseDouble(System.getProperty("biasCutoff","0"));
            if(cutoff<=0)bias=difference;else bias+=(difference-bias)*(1-Math.exp(-cutoff*dt));
            previous=angle;initialized=true;return pose;
        }
    }
    private static final class RootForecast {
        boolean initialized;
        final Quaterniond previous=new Quaterniond();
        final Vector3d previousVector=new Vector3d(),filteredChange=new Vector3d();
        Quaterniond sample(Quaterniond current,Vector3d nativeOmega,double dt) {
            Quaterniond delta=initialized?new Quaterniond(current).normalize().mul(new Quaterniond(previous).normalize().conjugate()).normalize()
                    : rotation(new Vector3d(nativeOmega).mul(dt));
            if(delta.w<0)delta.mul(-1);
            double sine=Math.sqrt(delta.x*delta.x+delta.y*delta.y+delta.z*delta.z);
            double factor=sine<1e-12?2:2*Math.atan2(sine,delta.w)/sine;
            Vector3d rotationVector=new Vector3d(delta.x,delta.y,delta.z).mul(factor);
            Vector3d change=initialized?new Vector3d(rotationVector).sub(previousVector):new Vector3d();
            filteredChange.add(change.sub(filteredChange).mul(1-Math.exp(-2*dt)));
            previousVector.set(rotationVector);previous.set(current);initialized=true;
            if(System.getProperty("positionMode","GOAL").equals("ROOT_POSE_ACCEL"))rotationVector.add(filteredChange);
            return rotation(rotationVector);
        }
        private static Quaterniond rotation(Vector3d vector) {
            double angle=vector.length();
            return angle<1e-12?new Quaterniond():new Quaterniond().rotationAxis(angle,vector.x/angle,vector.y/angle,vector.z/angle);
        }
    }
    private static final class ParentPose {
        boolean initialized;final Quaterniond previous=new Quaterniond();
        Vector3d sample(Quaterniond current,Vector3d nativeOmega,double dt){
            Vector3d rate=new Vector3d(nativeOmega);
            if(initialized){
                Quaterniond delta=new Quaterniond(current).mul(new Quaterniond(previous).conjugate()).normalize();
                if(delta.w<0)delta.set(-delta.x,-delta.y,-delta.z,-delta.w);
                double sine=Math.sqrt(delta.x*delta.x+delta.y*delta.y+delta.z*delta.z);
                double factor=sine<1e-12?2/dt:2*Math.atan2(sine,delta.w)/(dt*sine);
                rate.set(delta.x*factor,delta.y*factor,delta.z*factor);
            }
            previous.set(current);initialized=true;return rate;
        }
    }
}







