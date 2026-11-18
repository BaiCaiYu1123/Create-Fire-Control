package com.hooya.stabilizedturret.content.bearing;

/** Runs the production operator ramp with changing geometric and compensated goals. */
final class AimCompensationChecks {
    static void run() {
        double oldWorst=0;
        for (boolean damped:new boolean[]{false,true}) for (double initialCorrection:new double[]{.02,.08,.14}) {
            AimCompensation correction=new AimCompensation();
            var motion=new BearingServoMath.OperatorMotion();
            double launch=0,geometric=0;
            for (int frame=0;frame<600;frame++) {
                // Continuous head movement, reversal, a near/far face change, then sky.
                double requestedGeometry=frame<120 ? frame*.0007 : frame<240 ? .084-(frame-120)*.0009
                        : frame<400 ? -.024 : .08;
                double requestedCorrection=frame<180 ? initialCorrection : frame<400 ? initialCorrection*.25 : 0;
                correction.request(requestedCorrection,false);
                double error=requestedGeometry+requestedCorrection-launch;
                if (damped) motion.restartIfReversing(error);
                double step=damped ? motion.advanceDamped(error,.25,.025) : motion.advance(error,.25,.025);
                double fraction=Math.abs(error)<1e-10 ? 1 : Math.clamp(step/error,0,1);
                if (step*error<0) geometric+=step;
                else geometric+=(requestedGeometry-geometric)*fraction;
                correction.advance(error,step);
                launch+=step;
                require(Math.abs(launch-correction.acquired()-geometric)<1e-10,
                        "reticle removes acquired correction throughout traverse, braking, reversal and sky transition");
                oldWorst=Math.max(oldWorst,Math.abs(launch-requestedCorrection-geometric));
            }
            require(Math.abs(correction.acquired())<1e-9,"sky arrival leaves no remembered ballistic correction");
        }
        require(oldWorst>.01,"subtracting the future full correction reproduces a visible low/high reticle");
        var first=new AimCompensation(); var second=new AimCompensation();
        first.request(.12,false); first.advance(.3,.05);
        double acquired=first.acquired();
        first.request(.15,true);
        require(Math.abs(first.acquired()-acquired-.03)<1e-12,"automatic transport preserves pending acquisition");
        second.request(-.05,false); second.advance(-.05,-.05);
        require(Math.abs(first.acquired()-acquired-.03)<1e-12,"another bearing does not change the acquired angle");
        first.request(-.12,false); first.advance(-1.1e-8,.005);
        require(Math.abs(first.acquired()-acquired-.03)<1e-12,"coasting across a tiny reverse error cannot magnify the angle");
        first.advance(5e-9,0);
        require(Math.abs(first.acquired()+.12)<1e-12,"operator arrival finishes correction even when no further step is emitted");
        first.reset(); require(first.acquired()==0,"staff/reassembly resets stale corrections");
        System.out.printf("PASS: acquired-angle production ramp; old premature subtraction reaches %.6f degrees%n",
                Math.toDegrees(oldWorst));
    }
    private static void require(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
