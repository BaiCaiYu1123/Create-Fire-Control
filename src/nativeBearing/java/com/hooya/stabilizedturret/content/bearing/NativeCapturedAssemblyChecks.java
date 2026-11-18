package com.hooya.stabilizedturret.content.bearing;

import com.google.gson.*;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import org.joml.Vector3d;
import org.joml.Quaterniond;
import org.joml.Matrix3d;
import java.nio.file.*;
import java.util.*;

/** Replays a read-only game capture with real inertia and joint frames, without game contacts. */
public final class NativeCapturedAssemblyChecks {
    static Vector3d vec(JsonElement value) {var a=value.getAsJsonArray();return new Vector3d(a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble());}
    static Quaterniond quat(JsonElement value) {var a=value.getAsJsonArray();return new Quaterniond(a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble(),a.get(3).getAsDouble()).normalize();}
    static double[] array(JsonElement value) {var a=value.getAsJsonArray();double[] d=new double[a.size()];for(int i=0;i<d.length;i++)d[i]=a.get(i).isJsonNull()?Double.POSITIVE_INFINITY:a.get(i).getAsDouble();return d;}
    static int index(Map<String,Integer> indices,JsonElement id) {return id.isJsonNull()?-1:indices.get(id.getAsString());}
    static double error(Quaterniond a,Quaterniond b) {var q=new Quaterniond(a).conjugate().mul(b).normalize();return Math.toDegrees(2*Math.atan2(Math.sqrt(q.x*q.x+q.y*q.y+q.z*q.z),Math.abs(q.w)));}
    public static void main(String[] args)throws Exception {
        JsonObject capture=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        List<ArticulatedServoPredictor.BodyState> bodies=new ArrayList<>();List<Vector3d> centers=new ArrayList<>();Map<String,Integer> indices=new HashMap<>();
        Vector3d origin=vec(capture.getAsJsonArray("bodies").get(0).getAsJsonObject().get("position"));
        long scene=Rapier3D.initialize(0,-10,0,0);double dt=.025;
        try {
            Rapier3D.configSolverIterations(18,2,2);Rapier3D.configFrequencyAndDamping(40,5);Rapier3D.configMinIslandSize(128);
            var massMethod=Rapier3D.class.getDeclaredMethod("setMassProperties",long.class,int.class,double.class,double[].class,double[].class);massMethod.setAccessible(true);
            for(JsonElement element:capture.getAsJsonArray("bodies")) {
                var body=element.getAsJsonObject();int i=bodies.size();indices.put(body.get("id").getAsString(),i);
                Vector3d position=vec(body.get("position")).sub(origin),v=vec(body.get("linearVelocity")),w=vec(body.get("angularVelocity"));Quaterniond q=quat(body.get("orientation"));
                double mass=body.get("mass").getAsDouble();double[] inertia=array(body.get("inertia"));
                bodies.add(new ArticulatedServoPredictor.BodyState(mass,new Matrix3d().set(inertia),position,q,v,w));centers.add(vec(body.get("center")));
                Rapier3D.createBox(scene,i+1,1e-12,.000001,.000001,.000001,new double[]{position.x,position.y,position.z,q.x,q.y,q.z,q.w});
                massMethod.invoke(null,scene,i+1,mass,new double[3],inertia);
                Rapier3D.addLinearAngularVelocities(scene,i+1,v.x,v.y,v.z,w.x,w.y,w.z,false);
            }
            List<ArticulatedServoPredictor.HingeMotor> hinges=new ArrayList<>();List<ArticulatedServoPredictor.WorldMotor> worlds=new ArrayList<>();
            Map<Long,Long> handles=new HashMap<>();
            Map<Long,JsonObject> limits=new HashMap<>();
            for(JsonElement element:capture.getAsJsonArray("locks")) {var lock=element.getAsJsonObject();if(!lock.get("yawAxis").getAsBoolean() && lock.has("hingeOrder"))limits.put(lock.get("hingeOrder").getAsLong(),lock.getAsJsonObject("pitchStops"));}
            var edges=new ArrayList<JsonObject>();for(JsonElement e:capture.getAsJsonArray("edges"))edges.add(e.getAsJsonObject());edges.sort(Comparator.comparingLong(e->e.get("order").getAsLong()));
            for(var edge:edges) {
                int a=index(indices,edge.get("parent")),b=index(indices,edge.get("child"));long order=edge.get("order").getAsLong();
                if(edge.get("frame").isJsonNull())throw new IllegalArgumentException("Unmodelled captured joint");
                Vector3d anchorA=vec(edge.get("parentAnchor")),anchorB=vec(edge.get("childAnchor"));
                if(a>=0)anchorA.sub(centers.get(a));else anchorA.sub(origin);if(b>=0)anchorB.sub(centers.get(b));
                Quaterniond frameA=quat(edge.get("frame")),frameB=quat(edge.get("childFrame"));
                int locked=edge.get("lockedMask").getAsInt(),active=edge.get("activeMask").getAsInt();
                long handle=Rapier3D.addGenericConstraint(scene,a<0?-1:a+1,b<0?-1:b+1,
                        anchorA.x,anchorA.y,anchorA.z,frameA.x,frameA.y,frameA.z,frameA.w,
                        anchorB.x,anchorB.y,anchorB.z,frameB.x,frameB.y,frameB.z,frameB.w,locked);
                Rapier3D.setConstraintContactsEnabled(scene,handle,false);
                handles.put(order,handle);
                double[] goals=array(edge.get("goals")),k=array(edge.get("stiffness")),d=array(edge.get("damping")),cap=array(edge.get("caps"));
                for(int axis=0;axis<6;axis++)if((edge.get("motorMask").getAsInt()&(1<<axis))!=0)Rapier3D.setConstraintMotor(scene,handle,axis,goals[axis],k[axis],d[axis],true,cap[axis]);
                if(limits.containsKey(order)) {var limit=limits.get(order);Rapier3D.setConstraintLimit(scene,handle,3,limit.get("min").getAsDouble(),limit.get("max").getAsDouble());}
                if(locked==63)hinges.add(ArticulatedServoPredictor.HingeMotor.fixed(a,b,anchorA,anchorB,frameA,frameB,order));
                else if(locked==55) {
                    var h=new ArticulatedServoPredictor.HingeMotor(a,b,anchorA,anchorB,frameA,frameB,goals[3],k[3],d[3],cap[3],order);
                    if(limits.containsKey(order)) {var limit=limits.get(order);h=h.withLimits(limit.get("min").getAsDouble(),limit.get("max").getAsDouble());}hinges.add(h);
                } else if(locked==0 && active!=0 && a<0)worlds.add(new ArticulatedServoPredictor.WorldMotor(b,frameA,anchorB,anchorA,
                        Arrays.copyOfRange(goals,3,6),Arrays.copyOfRange(goals,0,3),k,d,cap,active,false,order,frameB));
                else if(locked!=0 || active!=0)throw new IllegalArgumentException("Unmodelled mask: "+locked+"/"+active);
            }
            List<ArticulatedServoPredictor.BodyState> predicted;
            List<ArticulatedServoSolver.RayGoal> rayGoals=new ArrayList<>();List<Integer> controlled=new ArrayList<>();
            List<RigidWeldGeometry.Link<Integer>> welds=new ArrayList<>();
            for(var h:hinges)if(h.fixed())welds.add(new RigidWeldGeometry.Link<>(h.parent(),h.child(),h.parentAnchor(),h.childAnchor(),h.parentFrame(),h.childFrame()));
            for(JsonElement item:capture.getAsJsonArray("locks")) {
                var lock=item.getAsJsonObject();int hIndex=-1,wIndex=-1;
                for(int j=0;j<hinges.size();j++)if(hinges.get(j).rowOrder()==lock.get("hingeOrder").getAsLong())hIndex=j;
                for(int j=0;j<worlds.size();j++)if(worlds.get(j).rowOrder()==lock.get("actuatorOrder").getAsLong())wIndex=j;
                var h=hinges.get(hIndex);var drive=worlds.remove(wIndex);
                int plate=indices.get(lock.get("childId").getAsString());
                Vector3d pivot=vec(lock.get("childLocalPivot")).sub(centers.get(plate));
                var geometry=RigidWeldGeometry.from(plate,pivot,new Vector3d(1,0,0),welds);
                if(geometry.containsKey(h.parent()))throw new AssertionError("Weld geometry crossed controlled bearing");
                for(var entry:geometry.entrySet()) {
                    var back=RigidWeldGeometry.from(entry.getKey(),entry.getValue().anchor(),entry.getValue().axis(),welds).get(plate);
                    if(back.anchor().distance(pivot)>1e-7 || back.axis().distance(new Vector3d(1,0,0))>1e-10)
                        throw new AssertionError("Saved weld pivot/axis fails round trip");
                }
                Vector3d anchor=geometry.get(h.child()).anchor();
                System.out.printf(Locale.ROOT,"%s pivot baked deflection %.9f blocks%n",lock.get("yawAxis").getAsBoolean()?"yaw":"pitch",anchor.distance(h.childAnchor()));
                Quaterniond frame=h.childFrame();Rapier3D.setConstraintFrame(scene,handles.get(h.rowOrder()),1,anchor.x,anchor.y,anchor.z,frame.x,frame.y,frame.z,frame.w);
                hinges.set(hIndex,new ArticulatedServoPredictor.HingeMotor(h.parent(),h.child(),h.parentAnchor(),anchor,h.parentFrame(),frame,drive.angularGoals()[0],drive.stiffness()[3],drive.damping()[3],drive.cap()[3],h.rowOrder(),true,drive.rowOrder(),false,h.limitMin(),h.limitMax()));
                int source=lock.get("directionSourceId").isJsonNull()?indices.get(capture.getAsJsonArray("locks").get(0).getAsJsonObject().get("directionSourceId").getAsString()):indices.get(lock.get("directionSourceId").getAsString());
                Vector3d ray=lock.get("directionSourceId").isJsonNull()?vec(capture.getAsJsonArray("locks").get(0).getAsJsonObject().get("directionSourceLocalForward")):vec(lock.get("directionSourceLocalForward"));
                Vector3d axis=h.parentFrame().transform(new Vector3d(1,0,0)),held=vec(lock.get("heldAimDirection"));
                rayGoals.add(lock.get("yawAxis").getAsBoolean()?new ArticulatedServoSolver.RayGoal(h.parent(),source,axis,ray,held):new ArticulatedServoSolver.RayGoal(h.parent(),axis,held,List.of(new ArticulatedServoSolver.RaySource(source,ray)),output->BearingServoMath.pitchDirection(held,output.get(source).orientation().transform(new Vector3d(ray)),output.get(h.parent()).orientation())));
                controlled.add(hIndex);
            }
            var inverse=ArticulatedServoSolver.solveIndependent(bodies,hinges,null,worlds,new Vector3d(0,-10,0),dt,rayGoals,controlled.stream().mapToInt(Integer::intValue).toArray(),18,2,2,550,4,false);
            System.out.printf(Locale.ROOT,"Captured inverse: accepted=%s failure=%s residual=%.9f deg%n",inverse.accepted(),inverse.failure(),Math.toDegrees(inverse.maxResidualRadians()));
            double peakHeld=0,peakForecast=0;int rejected=0;
            for(int tick=0;tick<120;tick++) {
                for(int j=0;j<controlled.size();j++) {
                    int n=controlled.get(j);var h=hinges.get(n);var g=rayGoals.get(j);var s=g.sources().getFirst();
                    Quaterniond world=bodies.get(h.parent()).orientation().mul(h.parentFrame());
                    long actuator=handles.get(h.driveOrder());Rapier3D.setConstraintFrame(scene,actuator,0,0,0,0,world.x,world.y,world.z,world.w);
                    Vector3d actual=bodies.get(s.barrelIndex()).orientation().transform(s.localRay());
                    Vector3d desired=g.dynamicTarget()==null?g.heldWorldRay():g.dynamicTarget().direction(bodies);
                    double measured=BearingServoMath.jointAngle(world,bodies.get(h.child()).orientation().mul(h.childFrame()));
                    double correction=BearingServoMath.signedDirectionAngle(actual,desired,bodies.get(h.parent()).orientation().transform(g.localAxis()));
                    hinges.set(n,h.withGoal(measured+correction));
                }
                inverse=ArticulatedServoSolver.solveIndependent(bodies,hinges,null,worlds,new Vector3d(0,-10,0),dt,rayGoals,controlled.stream().mapToInt(Integer::intValue).toArray(),18,2,2,550,4,false);
                if(!inverse.accepted()){rejected++;if(rejected<8)System.out.println("tick "+tick+" inverse "+inverse.failure()+" residual="+Math.toDegrees(inverse.maxResidualRadians()));}
                for(int n:controlled){var h=hinges.get(n).withGoal(inverse.targets()[n]);hinges.set(n,h);Rapier3D.setConstraintMotor(scene,handles.get(h.driveOrder()),3,h.goal(),h.stiffness(),h.damping(),true,h.torqueCap());}
                predicted=ArticulatedServoPredictor.predict(bodies,hinges,null,worlds,new Vector3d(0,-10,0),dt,18,2,2,550,4,false).bodies();
                Rapier3D.step(scene,dt);List<ArticulatedServoPredictor.BodyState> next=new ArrayList<>();
                for(int i=0;i<bodies.size();i++){double[] p=new double[7],v=new double[3],w=new double[3];Rapier3D.getPose(scene,i+1,p);Rapier3D.getLinearVelocity(scene,i+1,v);Rapier3D.getAngularVelocity(scene,i+1,w);var state=bodies.get(i);next.add(new ArticulatedServoPredictor.BodyState(state.mass(),state.inertiaLocal(),new Vector3d(p[0],p[1],p[2]),new Quaterniond(p[3],p[4],p[5],p[6]),new Vector3d(v),new Vector3d(w)));}
                bodies=next;
                for(var g:rayGoals){var s=g.sources().getFirst();Vector3d actual=bodies.get(s.barrelIndex()).orientation().transform(s.localRay()),desired=g.dynamicTarget()==null?g.heldWorldRay():g.dynamicTarget().direction(bodies);double held=Math.toDegrees(Math.abs(BearingServoMath.signedDirectionAngle(actual,desired,bodies.get(g.parentIndex()).orientation().transform(g.localAxis()))));peakHeld=Math.max(peakHeld,held);peakForecast=Math.max(peakForecast,error(bodies.get(s.barrelIndex()).orientation(),predicted.get(s.barrelIndex()).orientation()));if(held>.1 && tick%10==0)System.out.println("tick "+tick+" held error="+held);}
            }
            System.out.printf(Locale.ROOT,"Captured %d bodies / %d native edges: held error %.9f deg, barrel forecast %.9f deg, rejected %d%n",bodies.size(),edges.size(),peakHeld,peakForecast,rejected);
            if(peakHeld>.005 || rejected>0)throw new AssertionError("Captured closed-loop hold failed");
        } finally {Rapier3D.dispose(scene);}
    }
}
