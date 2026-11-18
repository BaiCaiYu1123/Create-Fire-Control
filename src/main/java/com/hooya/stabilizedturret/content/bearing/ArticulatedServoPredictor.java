package com.hooya.stabilizedturret.content.bearing;

import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Predicts the joint-connected assembly without mutating native bodies or caller-owned snapshots. */
public final class ArticulatedServoPredictor {
    public static final int MAX_BODIES = 64;
    public static final int MAX_HINGES = 64;
    public static final int MAX_WORLD_MOTORS = 128;
    public static final int MAX_SUBSTEPS = 32;
    public static final int MAX_ITERATIONS = 8;

    private ArticulatedServoPredictor() {}

    /** Coordinates and velocities are world-space; inertia is about the local center of mass. */
    public record BodyState(double mass,Matrix3d inertiaLocal,Vector3d position,Quaterniond orientation,
                            Vector3d linearVelocity,Vector3d angularVelocity) {
        public BodyState {
            positive(mass,"mass");
            inertiaLocal = copyInertia(inertiaLocal);
            position = copyVector(position); orientation = copyRotation(orientation);
            linearVelocity = copyVector(linearVelocity); angularVelocity = copyVector(angularVelocity);
        }
        @Override public Matrix3d inertiaLocal() { return new Matrix3d(inertiaLocal); }
        @Override public Vector3d position() { return new Vector3d(position); }
        @Override public Quaterniond orientation() { return new Quaterniond(orientation); }
        @Override public Vector3d linearVelocity() { return new Vector3d(linearVelocity); }
        @Override public Vector3d angularVelocity() { return new Vector3d(angularVelocity); }
    }
    /** Anchors are local offsets from each body's center of mass; -1 denotes a static parent. */
    public record HingeMotor(int parent,int child,Vector3d parentAnchor,Vector3d childAnchor,
                             Quaterniond parentFrame,Quaterniond childFrame,double goal,
                             double stiffness,double damping,double torqueCap,long rowOrder,
                             boolean childOnly,long driveOrder,boolean fixed,double limitMin,double limitMax,boolean angularOnly) {
        public HingeMotor {
            if(parent < -1 || child < 0 || parent == child) throw new IllegalArgumentException("Invalid hinge bodies");
            parentAnchor = copyVector(parentAnchor); childAnchor = copyVector(childAnchor);
            parentFrame = copyRotation(parentFrame); childFrame = copyRotation(childFrame);
            finite(goal,"motor goal"); gain(stiffness); gain(damping); cap(torqueCap);
            if (fixed && (childOnly || goal != 0 || stiffness != 0 || damping != 0))
                throw new IllegalArgumentException("Fixed weld cannot have a driven axis");
            if (Double.isNaN(limitMin) || Double.isNaN(limitMax) || limitMin>limitMax)
                throw new IllegalArgumentException("Invalid hinge limits");
        }
        @Override public Vector3d parentAnchor() { return new Vector3d(parentAnchor); }
        @Override public Vector3d childAnchor() { return new Vector3d(childAnchor); }
        @Override public Quaterniond parentFrame() { return new Quaterniond(parentFrame); }
        @Override public Quaterniond childFrame() { return new Quaterniond(childFrame); }
        public HingeMotor(int parent,int child,Vector3d parentAnchor,Vector3d childAnchor,
                          Quaterniond parentFrame,Quaterniond childFrame,double goal,
                          double stiffness,double damping,double torqueCap,long rowOrder,
                          boolean childOnly,long driveOrder,boolean fixed,double limitMin,double limitMax) {
            this(parent,child,parentAnchor,childAnchor,parentFrame,childFrame,goal,stiffness,damping,torqueCap,
                    rowOrder,childOnly,driveOrder,fixed,limitMin,limitMax,false);
        }
        public static HingeMotor angularHold(int child,Quaterniond worldFrame,long rowOrder) {
            return new HingeMotor(-1,child,new Vector3d(),new Vector3d(),worldFrame,new Quaterniond(),
                    0,0,0,0,rowOrder,false,rowOrder,true,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,true);
        }
        public static HingeMotor angularHinge(int child,Quaterniond worldFrame,Quaterniond childFrame,long rowOrder) {
            return new HingeMotor(-1,child,new Vector3d(),new Vector3d(),worldFrame,childFrame,
                    0,0,0,0,rowOrder,false,rowOrder,false,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,true);
        }
        public HingeMotor withParentFrame(Quaterniond frame) { return new HingeMotor(parent,child,parentAnchor,childAnchor,frame,childFrame,goal,stiffness,damping,torqueCap,rowOrder,childOnly,driveOrder,fixed,limitMin,limitMax,angularOnly); }
        public HingeMotor(int parent,int child,Vector3d parentAnchor,Vector3d childAnchor,
                          Quaterniond parentFrame,Quaterniond childFrame,double goal,
                          double stiffness,double damping,double torqueCap,long rowOrder,
                          boolean childOnly,long driveOrder,boolean fixed) {
            this(parent,child,parentAnchor,childAnchor,parentFrame,childFrame,goal,stiffness,damping,torqueCap,
                    rowOrder,childOnly,driveOrder,fixed,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY);
        }
        public HingeMotor(int parent,int child,Vector3d parentAnchor,Vector3d childAnchor,
                          Quaterniond parentFrame,Quaterniond childFrame,double goal,
                          double stiffness,double damping,double torqueCap,long rowOrder,
                          boolean childOnly,long driveOrder) {
            this(parent,child,parentAnchor,childAnchor,parentFrame,childFrame,goal,stiffness,damping,torqueCap,
                    rowOrder,childOnly,driveOrder,false);
        }
        public static HingeMotor fixed(int parent,int child,Vector3d parentAnchor,Vector3d childAnchor,
                                       Quaterniond parentFrame,Quaterniond childFrame,long rowOrder) {
            return new HingeMotor(parent,child,parentAnchor,childAnchor,parentFrame,childFrame,
                    0,0,0,0,rowOrder,false,rowOrder,true);
        }
        public HingeMotor(int parent,int child,Vector3d parentAnchor,Vector3d childAnchor,
                          Quaterniond parentFrame,Quaterniond childFrame,double goal,
                          double stiffness,double damping,double torqueCap,long rowOrder) {
            this(parent,child,parentAnchor,childAnchor,parentFrame,childFrame,goal,stiffness,damping,torqueCap,rowOrder,false,rowOrder);
        }
        public HingeMotor(int parent,int child,Vector3d parentAnchor,Vector3d childAnchor,
                          Quaterniond parentFrame,Quaterniond childFrame,double goal,
                          double stiffness,double damping,double torqueCap) {
            this(parent,child,parentAnchor,childAnchor,parentFrame,childFrame,goal,stiffness,damping,torqueCap,Long.MAX_VALUE);
        }
        public HingeMotor withGoal(double goal) { return new HingeMotor(parent,child,parentAnchor,childAnchor,parentFrame,childFrame,goal,stiffness,damping,torqueCap,rowOrder,childOnly,driveOrder,fixed,limitMin,limitMax,angularOnly); }
        public HingeMotor withLimits(double min,double max) { return new HingeMotor(parent,child,parentAnchor,childAnchor,parentFrame,childFrame,goal,stiffness,damping,torqueCap,rowOrder,childOnly,driveOrder,fixed,min,max,angularOnly); }
    }
    public record StaffMotor(int child,Quaterniond queued,Vector3d anchor,Vector3d worldGoal,
                             double angularStiffness,double angularDamping,double linearStiffness,double linearDamping) {
        public StaffMotor {
            if(child < 0) throw new IllegalArgumentException("Invalid staff body");
            queued = copyRotation(queued); anchor = copyVector(anchor); worldGoal = copyVector(worldGoal);
            gain(angularStiffness); gain(angularDamping); gain(linearStiffness); gain(linearDamping);
        }
        @Override public Quaterniond queued() { return new Quaterniond(queued); }
        @Override public Vector3d anchor() { return new Vector3d(anchor); }
        @Override public Vector3d worldGoal() { return new Vector3d(worldGoal); }
        public WorldMotor asWorldMotor(boolean beforeHinges) {
            Vector3d localGoal = queued.transformInverse(new Vector3d(worldGoal));
            return new WorldMotor(child,queued,anchor,new Vector3d(),new double[3],
                    new double[]{localGoal.x,localGoal.y,localGoal.z},
                    new double[]{linearStiffness,linearStiffness,linearStiffness,angularStiffness,angularStiffness,angularStiffness},
                    new double[]{linearDamping,linearDamping,linearDamping,angularDamping,angularDamping,angularDamping},
                    new double[]{Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},63,beforeHinges);
        }
    }
    /** Axis indices match Sable: linear XYZ are 0..2 and angular XYZ are 3..5. */
    public record WorldMotor(int child,Quaterniond queuedFrame,Vector3d childAnchor,Vector3d parentWorldAnchor,
                             double[] angularGoals,double[] linearGoals,double[] stiffness,double[] damping,
                             double[] cap,int activeMask,boolean beforeHinges,long rowOrder,Quaterniond childFrame) {
        public WorldMotor {
            if(child < 0 || activeMask < 0 || activeMask > 63) throw new IllegalArgumentException("Invalid world motor");
            queuedFrame = copyRotation(queuedFrame); childAnchor = copyVector(childAnchor); parentWorldAnchor = copyVector(parentWorldAnchor);
            childFrame = copyRotation(childFrame);
            angularGoals = copyNumbers(angularGoals,3,false); linearGoals = copyNumbers(linearGoals,3,false);
            stiffness = copyNumbers(stiffness,6,false); damping = copyNumbers(damping,6,false); cap = copyNumbers(cap,6,true);
            for(int i=0;i<6;i++) { gain(stiffness[i]); gain(damping[i]); ArticulatedServoPredictor.cap(cap[i]); }
        }
        public WorldMotor(int child,Quaterniond queuedFrame,Vector3d childAnchor,Vector3d parentWorldAnchor,
                           double[] angularGoals,double[] linearGoals,double[] stiffness,double[] damping,
                           double[] cap,int activeMask,boolean beforeHinges,long rowOrder) {
            this(child,queuedFrame,childAnchor,parentWorldAnchor,angularGoals,linearGoals,stiffness,damping,cap,
                    activeMask,beforeHinges,rowOrder,new Quaterniond());
        }
        public WorldMotor(int child,Quaterniond queuedFrame,Vector3d childAnchor,Vector3d parentWorldAnchor,
                           double[] angularGoals,double[] linearGoals,double[] stiffness,double[] damping,
                           double[] cap,int activeMask,boolean beforeHinges) {
            this(child,queuedFrame,childAnchor,parentWorldAnchor,angularGoals,linearGoals,stiffness,damping,cap,
                    activeMask,beforeHinges,beforeHinges?Long.MIN_VALUE:Long.MAX_VALUE);
        }
        public WorldMotor(int child,Quaterniond queuedFrame,Vector3d childAnchor,Vector3d parentWorldAnchor,
                           double[] angularGoals,double[] linearGoals,double[] stiffness,double[] damping,double[] cap,int activeMask) {
            this(child,queuedFrame,childAnchor,parentWorldAnchor,angularGoals,linearGoals,stiffness,damping,cap,activeMask,false);
        }
        @Override public Quaterniond queuedFrame() { return new Quaterniond(queuedFrame); }
        @Override public Quaterniond childFrame() { return new Quaterniond(childFrame); }
        @Override public Vector3d childAnchor() { return new Vector3d(childAnchor); }
        @Override public Vector3d parentWorldAnchor() { return new Vector3d(parentWorldAnchor); }
        @Override public double[] angularGoals() { return angularGoals.clone(); }
        @Override public double[] linearGoals() { return linearGoals.clone(); }
        @Override public double[] stiffness() { return stiffness.clone(); }
        @Override public double[] damping() { return damping.clone(); }
        @Override public double[] cap() { return cap.clone(); }
    }
    public record Prediction(List<BodyState> bodies) {
        public Prediction { bodies = List.copyOf(bodies); }
    }

    public static Prediction predict(List<BodyState> input,List<HingeMotor> hinges,StaffMotor staff,
                                      Vector3d gravity,double dt,int substeps,int pgs,int stabilization,
                                      double jointFrequency,double jointDampingRatio,boolean staffFirst) {
        return predict(input,hinges,staff,List.of(),gravity,dt,substeps,pgs,stabilization,jointFrequency,jointDampingRatio,staffFirst);
    }

    /** Additional native world joints keep their recorded order relative to the controlled hinges. */
    public static Prediction predict(List<BodyState> input,List<HingeMotor> hinges,StaffMotor staff,
                                      List<WorldMotor> worldMotors,Vector3d gravity,double dt,int substeps,int pgs,int stabilization,
                                      double jointFrequency,double jointDampingRatio,boolean staffFirst) {
        input = List.copyOf(input); hinges = List.copyOf(hinges); worldMotors = List.copyOf(worldMotors);
        gravity = copyVector(gravity);
        if(input.isEmpty() || input.size()>MAX_BODIES || hinges.size()>MAX_HINGES
                || worldMotors.size()+(staff==null?0:1)>MAX_WORLD_MOTORS) throw new IllegalArgumentException("Assembly prediction bounds exceeded");
        if(!(dt>=1.0e-6 && dt<=0.25) || !Double.isFinite(dt) || substeps<1 || substeps>MAX_SUBSTEPS
                || pgs<1 || pgs>MAX_ITERATIONS || stabilization<0 || stabilization>MAX_ITERATIONS) throw new IllegalArgumentException("Invalid prediction step");
        positive(jointFrequency,"joint frequency"); positive(jointDampingRatio,"joint damping ratio");
        List<WorldMotor> worlds = new ArrayList<>(worldMotors);
        if(staff!=null) worlds.add(staff.asWorldMotor(staffFirst));
        for(HingeMotor hinge:hinges) if(hinge.parent>=input.size() || hinge.child>=input.size()) throw new IllegalArgumentException("Missing hinge body");
        for(WorldMotor world:worlds) if(world.child>=input.size()) throw new IllegalArgumentException("Missing world motor body");
        List<OrderedJoint> ordered = new ArrayList<>();
        for (int i=0;i<hinges.size();i++) {
            HingeMotor hinge = hinges.get(i);
            ordered.add(new OrderedJoint(hinge.rowOrder==Long.MAX_VALUE?i:hinge.rowOrder,hinge,null));
            if (hinge.childOnly) {
                Quaterniond frame = hinge.parent < 0 ? hinge.parentFrame()
                        : input.get(hinge.parent).orientation().mul(hinge.parentFrame);
                WorldMotor actuator = new WorldMotor(hinge.child,frame,new Vector3d(),new Vector3d(),
                        new double[]{hinge.goal,0,0},new double[3],
                        new double[]{0,0,0,hinge.stiffness,0,0},new double[]{0,0,0,hinge.damping,0,0},
                        new double[]{0,0,0,hinge.torqueCap,0,0},8,false,hinge.driveOrder,hinge.childFrame);
                ordered.add(new OrderedJoint(hinge.driveOrder,null,actuator));
            }
        }
        for (WorldMotor world:worlds) ordered.add(new OrderedJoint(world.rowOrder,null,world));
        ordered.sort(java.util.Comparator.comparingLong(OrderedJoint::order));
        ordered = nativeFixedJointOrder(ordered, input.size());
        int bodies=input.size(),size=bodies*6;
        Vector3d[] pos=new Vector3d[bodies];Quaterniond[] q=new Quaterniond[bodies];
        Matrix3d[] invI=new Matrix3d[bodies];double[] im=new double[bodies],velocity=new double[size];
        for(int i=0;i<bodies;i++) {
            var state=input.get(i);pos[i]=new Vector3d(state.position);q[i]=new Quaterniond(state.orientation).normalize();im[i]=1/state.mass;
            Matrix3d r=new Matrix3d().rotation(q[i]);
            invI[i]=new Matrix3d(r).mul(state.inertiaLocal).mul(new Matrix3d(r).transpose()).invert();
            velocity[i*6]=state.linearVelocity.x;velocity[i*6+1]=state.linearVelocity.y;velocity[i*6+2]=state.linearVelocity.z;
            // Sable applies its gyroscopic update once per complete physics step,
            // before the internal joint substeps. This only changes the local forecast.
            Vector3d gyro = NativeGyroForecast.predict(state.inertiaLocal, q[i], state.angularVelocity, dt);
            velocity[i*6+3]=gyro.x;velocity[i*6+4]=gyro.y;velocity[i*6+5]=gyro.z;
        }
        double h=dt/substeps,w=2*Math.PI*jointFrequency,lockedK=w*w,lockedD=2*jointDampingRatio*w;
        RowWorkspace workspace=new RowWorkspace(ordered.size()*7);
        for(int substep=0;substep<substeps;substep++) {
            for(int i=0;i<bodies;i++){velocity[i*6]+=gravity.x*h;velocity[i*6+1]+=gravity.y*h;velocity[i*6+2]+=gravity.z*h;}
            workspace.beginSubstep();
            for(OrderedJoint joint:ordered) {
                if(joint.hinge!=null)appendHinge(workspace,joint.hinge,pos,q,im,invI,h,size,lockedK,lockedD);
                else appendWorld(workspace,joint.world,pos,q,im,invI,h,size);
            }
            solve(workspace.rows,velocity,pgs,false);
            for(int i=0;i<bodies;i++) {
                pos[i].add(h*velocity[i*6],h*velocity[i*6+1],h*velocity[i*6+2]);
                q[i]=new Quaterniond(h*velocity[i*6+3]/2,h*velocity[i*6+4]/2,h*velocity[i*6+5]/2,1).normalize().mul(q[i]).normalize();
            }
            solve(workspace.rows,velocity,stabilization,true);
            for(double value:velocity) finite(value,"predicted velocity");
        }
        List<BodyState> output=new ArrayList<>();
        for(int i=0;i<bodies;i++) {
            var state=input.get(i);
            output.add(new BodyState(state.mass,new Matrix3d(state.inertiaLocal),pos[i],q[i],
                    new Vector3d(velocity[i*6],velocity[i*6+1],velocity[i*6+2]),
                    new Vector3d(velocity[i*6+3],velocity[i*6+4],velocity[i*6+5])));
        }
        return new Prediction(List.copyOf(output));
    }
    private record OrderedJoint(long order,HingeMotor hinge,WorldMotor world) {}

    /** Rapier queues motor joints first, then incomplete SIMD buckets, then complete SIMD groups. */
    private static List<OrderedJoint> nativeFixedJointOrder(List<OrderedJoint> dense,int bodies) {
        List<OrderedJoint> scalar=new ArrayList<>(),simd=new ArrayList<>();
        List<List<OrderedJoint>> buckets=new ArrayList<>();
        boolean[][] masks=new boolean[bodies][MAX_HINGES];
        for(OrderedJoint joint:dense) {
            if(joint.hinge==null || !joint.hinge.fixed || joint.hinge.angularOnly) {scalar.add(joint);continue;}
            HingeMotor fixed=joint.hinge;
            int target=-1;
            // Prefer an occupied compatible bucket; a body cannot occur twice in one SIMD group.
            for(int i=0;i<buckets.size();i++) if(!buckets.get(i).isEmpty()
                    && !masks[fixed.child][i] && (fixed.parent<0 || !masks[fixed.parent][i])) {target=i;break;}
            if(target<0) {
                for(int i=0;i<buckets.size();i++) if(buckets.get(i).isEmpty()) {target=i;break;}
                if(target<0) {target=buckets.size();buckets.add(new ArrayList<>());}
            }
            List<OrderedJoint> bucket=buckets.get(target);
            bucket.add(joint);
            masks[fixed.child][target]=true;
            if(fixed.parent>=0)masks[fixed.parent][target]=true;
            if(bucket.size()==4) {simd.addAll(bucket);bucket.clear();}
        }
        for(List<OrderedJoint> bucket:buckets)scalar.addAll(bucket);
        scalar.addAll(simd);
        return scalar;
    }
    private static void appendWorld(RowWorkspace workspace,WorldMotor staff,Vector3d[] pos,Quaterniond[] q,
                                    double[] im,Matrix3d[] invI,double h,int size) {
        int b=staff.child;
        // FreeConstraintConfiguration uses an identity child frame. Preserve its old
        // arithmetic exactly while representing mutable generic free joints as well.
        Quaterniond childOrientation=staff.childFrame.x==0 && staff.childFrame.y==0
                && staff.childFrame.z==0 && staff.childFrame.w==1
                ? q[b] : new Quaterniond(q[b]).mul(staff.childFrame);
        Quaterniond error=error(staff.queuedFrame,childOrientation);
        Vector3d[] basis=basis(staff.queuedFrame);
        Vector3d radius=q[b].transform(new Vector3d(staff.childAnchor)),delta=new Vector3d(pos[b]).add(radius).sub(staff.parentWorldAnchor);
        for(int i=0;i<3;i++) {
            int axis=i+3;
            if(!active(staff,axis))continue;
            double angle=wrap(2*Math.asin(Math.clamp(component(error,i),-1,1))-staff.angularGoals[i]);
            Row row=row(workspace,-1,b,new Vector3d(),new Vector3d(),basis[i],basis[i],angle,staff.stiffness[axis],staff.damping[axis],false,staff.cap[axis],h,size,im,invI);
            workspace.rows.add(row);
        }
        for(int i=0;i<3;i++) {
            if(!active(staff,i))continue;
            Vector3d torque=radius.cross(basis[i],new Vector3d());
            workspace.rows.add(row(workspace,-1,b,basis[i],basis[i],new Vector3d(),torque,delta.dot(basis[i])-staff.linearGoals[i],staff.stiffness[i],staff.damping[i],false,staff.cap[i],h,size,im,invI));
        }
    }
    private static void appendHinge(RowWorkspace workspace,HingeMotor hinge,Vector3d[] pos,Quaterniond[] q,
                                    double[] im,Matrix3d[] invI,double h,int size,double lockedK,double lockedD) {
        int a=hinge.parent,b=hinge.child;
        Quaterniond qa=a<0?new Quaterniond(hinge.parentFrame):new Quaterniond(q[a]).mul(hinge.parentFrame);
        Quaterniond qb=new Quaterniond(q[b]).mul(hinge.childFrame),error=error(qa,qb);
        Vector3d[] basis=basis(qa);Vector3d e=new Vector3d(error.x,error.y,error.z);
        double motorAngle=2*Math.asin(Math.clamp(error.x,-1,1));
        if(!hinge.childOnly && hinge.stiffness>0 && hinge.damping>0 && hinge.torqueCap>0) workspace.rows.add(row(workspace,a,b,new Vector3d(),new Vector3d(),basis[0],basis[0],wrap(motorAngle-hinge.goal),hinge.stiffness,hinge.damping,false,hinge.torqueCap,h,size,im,invI));
        List<Row> locked=workspace.locked;
        locked.clear();
        for(int i=hinge.fixed?0:1;i<3;i++) {
            Vector3d axis=new Vector3d(i==0?1:0,i==1?1:0,i==2?1:0);
            Vector3d gradient=qa.transform(e.cross(axis,new Vector3d()).add(new Vector3d(axis).mul(error.w)).mul(.5));
            locked.add(row(workspace,a,b,new Vector3d(),new Vector3d(),gradient,gradient,component(error,i),lockedK,lockedD,true,Double.POSITIVE_INFINITY,h,size,im,invI));
        }
        Vector3d ra=a<0?new Vector3d(hinge.parentAnchor):q[a].transform(new Vector3d(hinge.parentAnchor));
        Vector3d rb=q[b].transform(new Vector3d(hinge.childAnchor));
        Vector3d anchorA=a<0?ra:new Vector3d(pos[a]).add(ra),anchorB=new Vector3d(pos[b]).add(rb);
        Vector3d delta=anchorB.sub(anchorA);
        for(int i=0;i<3 && !hinge.angularOnly;i++) {
            Vector3d ta=ra.cross(basis[i],new Vector3d()),tb=rb.cross(basis[i],new Vector3d());
            locked.add(row(workspace,a,b,basis[i],basis[i],ta,tb,delta.dot(basis[i]),lockedK,lockedD,true,Double.POSITIVE_INFINITY,h,size,im,invI));
        }
        if (!hinge.fixed && Double.isFinite(hinge.limitMin) && Double.isFinite(hinge.limitMax)) {
            double min=Math.sin(hinge.limitMin*.5),max=Math.sin(hinge.limitMax*.5);
            Vector3d gradient=qa.transform(e.cross(new Vector3d(1,0,0),new Vector3d())
                    .add(new Vector3d(error.w,0,0)).mul(.5));
            Row limit=row(workspace,a,b,new Vector3d(),new Vector3d(),gradient,gradient,
                    Math.max(error.x-max,0)-Math.max(min-error.x,0),lockedK,lockedD,true,
                    Double.POSITIVE_INFINITY,h,size,im,invI);
            limit.bilateral=false;
            // Our Jacobian/impulse convention is the negative of Rapier's stored impulse.
            limit.minImpulse=error.x>=max ? Double.NEGATIVE_INFINITY : 0;
            limit.maxImpulse=error.x<=min ? Double.POSITIVE_INFINITY : 0;
            locked.add(limit);
        }
        // Locked rows project into later locked/limit rows; bounded rows never project back.
        for(int j=0;j<locked.size();j++) {
            Row rj=locked.get(j);finish(rj);
            if (!rj.bilateral) continue;
            for(int i=j+1;i<locked.size();i++) {
                Row ri=locked.get(i);double coefficient=dot(ri.jac,rj.response)/rj.mobility;
                // Every locked row of this joint has the same one/two-body support.
                for(int k=0;k<ri.width;k++){ri.jac[k]-=rj.jac[k]*coefficient;ri.response[k]-=rj.response[k]*coefficient;}
                ri.bias-=rj.bias*coefficient;
            }
        }
        for(int i=0;i<locked.size();i++)workspace.rows.add(locked.get(i));
    }
    private static Row row(RowWorkspace workspace,int a,int b,Vector3d la,Vector3d lb,Vector3d aa,Vector3d ab,double error,
                            double k,double d,boolean locked,double cap,double h,int size,double[] im,Matrix3d[] invI) {
        Row row=workspace.next();row.locked=locked;row.bilateral=locked;row.bias=error*k/(h*k+d);
        row.cfm=1/(h*h*k+h*d);row.cap=cap*h;
        row.minImpulse=-row.cap;row.maxImpulse=row.cap;
        // Store only nonzero body blocks, in the original global summation order.
        // Gram-Schmidt within a joint never introduces another body's entries.
        row.firstOffset=(a<0?b:Math.min(a,b))*6;
        row.secondOffset=a<0?-1:Math.max(a,b)*6;
        row.width=a<0?6:12;
        if(a>=0)set(row.jac,a*6==row.firstOffset?0:6,la,aa,-1);
        set(row.jac,b*6==row.firstOffset?0:6,lb,ab,1);
        for(int offset=0;offset<row.width;offset+=6) {
            int body=(offset==0?row.firstOffset:row.secondOffset)/6;
            for(int j=0;j<3;j++)row.response[offset+j]=row.jac[offset+j]*im[body];
            Vector3d angular=invI[body].transform(workspace.angular.set(row.jac[offset+3],row.jac[offset+4],row.jac[offset+5]));
            row.response[offset+3]=angular.x;row.response[offset+4]=angular.y;row.response[offset+5]=angular.z;
        }
        if(!locked)finish(row);
        return row;
    }
    private static void set(double[] row,int offset,Vector3d linear,Vector3d angular,double scale) {
        row[offset]=linear.x*scale;row[offset+1]=linear.y*scale;row[offset+2]=linear.z*scale;
        row[offset+3]=angular.x*scale;row[offset+4]=angular.y*scale;row[offset+5]=angular.z*scale;
    }
    private static void finish(Row row){row.mobility=dot(row.jac,row.response);row.soft=row.mobility*row.cfm;row.inverse=1/(row.mobility+row.soft);positive(row.mobility,"constraint mobility");finite(row.inverse,"constraint response");}
    private static void solve(List<Row> rows,double[] velocity,int count,boolean removeBias) {
        for(int iter=0;iter<count;iter++)for(Row row:rows) {
            double speed=0;
            for(int j=0;j<row.width;j++)speed+=row.jac[j]*velocity[(j<6?row.firstOffset:row.secondOffset-6)+j];
            double delta=-(speed+(removeBias&&row.locked?0:row.bias)+row.soft*row.impulse)*row.inverse;
            double next=Math.clamp(row.impulse+delta,row.minImpulse,row.maxImpulse);delta=next-row.impulse;row.impulse=next;
            for(int j=0;j<row.width;j++)velocity[(j<6?row.firstOffset:row.secondOffset-6)+j]+=row.response[j]*delta;
        }
    }
    private static Quaterniond error(Quaterniond a,Quaterniond b){Quaterniond error=new Quaterniond(a).conjugate().mul(b).normalize();if(error.w<0)error.mul(-1);return error;}
    private static Vector3d[] basis(Quaterniond q){return new Vector3d[]{q.transform(new Vector3d(1,0,0)),q.transform(new Vector3d(0,1,0)),q.transform(new Vector3d(0,0,1))};}
    private static double component(Quaterniond q,int i){return i==0?q.x:i==1?q.y:q.z;}
    private static double wrap(double angle){return Math.atan2(Math.sin(angle),Math.cos(angle));}
    private static double dot(double[] a,double[] b){double result=0;for(int i=0;i<a.length;i++)result+=a[i]*b[i];return result;}
    private static boolean active(WorldMotor motor,int axis){return (motor.activeMask&(1<<axis))!=0 && (motor.stiffness[axis]>0 || motor.damping[axis]>0) && motor.cap[axis]>0;}
    private static void finite(double value,String name){if(!Double.isFinite(value) || Math.abs(value)>1.0e100)throw new IllegalArgumentException("Invalid "+name);}
    private static void positive(double value,String name){finite(value,name);if(!(value>1.0e-12))throw new IllegalArgumentException("Invalid "+name);}
    private static void gain(double value){finite(value,"motor gain");if(value<0 || value>1.0e16)throw new IllegalArgumentException("Invalid motor gain");}
    private static void cap(double value){if(!(value>=0) || value==Double.NEGATIVE_INFINITY)throw new IllegalArgumentException("Invalid motor cap");}
    private static Vector3d copyVector(Vector3d value){Objects.requireNonNull(value,"vector");finite(value.x,"vector");finite(value.y,"vector");finite(value.z,"vector");return new Vector3d(value);}
    private static Quaterniond copyRotation(Quaterniond value){Objects.requireNonNull(value,"rotation");finite(value.x,"rotation");finite(value.y,"rotation");finite(value.z,"rotation");finite(value.w,"rotation");positive(value.lengthSquared(),"rotation");return new Quaterniond(value).normalize();}
    private static double[] copyNumbers(double[] values,int count,boolean allowInfinite){if(values==null || values.length!=count)throw new IllegalArgumentException("Invalid motor axis values");double[] copy=values.clone();for(double value:copy){if(allowInfinite)cap(value);else finite(value,"motor axis value");}return copy;}
    private static Matrix3d copyInertia(Matrix3d value){
        Objects.requireNonNull(value,"inertia");double[] numbers=value.get(new double[9]);for(double number:numbers)finite(number,"inertia");
        double scale=Math.max(1,Math.max(Math.abs(value.m00()),Math.max(Math.abs(value.m11()),Math.abs(value.m22()))));
        if(Math.abs(value.m01()-value.m10())>scale*1.0e-6 || Math.abs(value.m02()-value.m20())>scale*1.0e-6
                || Math.abs(value.m12()-value.m21())>scale*1.0e-6 || !(value.m00()>0)
                || !(value.m00()*value.m11()-value.m01()*value.m10()>0) || !(value.determinant()>0))throw new IllegalArgumentException("Invalid inertia tensor");
        return new Matrix3d(value);
    }
    private static final class Row {
        final double[] jac=new double[12],response=new double[12];
        int firstOffset,secondOffset,width;
        boolean locked,bilateral;double bias,cfm,cap,minImpulse,maxImpulse,mobility,soft,inverse,impulse;
    }

    /** Local to one prediction; no native state or scratch data survives the call. */
    private static final class RowWorkspace {
        final List<Row> rows;
        final List<Row> locked=new ArrayList<>(5);
        final Vector3d angular=new Vector3d();
        private final Row[] storage;
        private int used;

        RowWorkspace(int capacity) {
            storage=new Row[capacity];
            rows=new ArrayList<>(capacity);
        }

        void beginSubstep() {
            rows.clear();
            locked.clear();
            used=0;
        }

        Row next() {
            if(used>=storage.length)throw new IllegalArgumentException("Constraint row bounds exceeded");
            Row row=storage[used];
            if(row==null)storage[used]=row=new Row();
            else {
                // Gram-Schmidt and PGS modify these arrays and the accumulated impulse.
                // Every substep must start with the same zero state as a new row.
                java.util.Arrays.fill(row.jac,0.0);
                java.util.Arrays.fill(row.response,0.0);
            }
            row.locked=false;
            row.bias=row.cfm=row.cap=row.mobility=row.soft=row.inverse=row.impulse=0.0;
            used++;
            return row;
        }
    }
}
