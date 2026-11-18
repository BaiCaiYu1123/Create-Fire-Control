package com.hooya.stabilizedturret.client;

public final class MouseWheelBindingMathChecks {
    public static void main(String[] args) {
        require(MouseWheelBindingMath.isActive(0.0, 1.0, 1.0),
                "positive wheel mapping must accept wheel-up");
        require(!MouseWheelBindingMath.isActive(0.0, 1.0, -1.0),
                "positive wheel mapping must reject wheel-down");
        require(MouseWheelBindingMath.isActive(0.0, -1.0, -1.0),
                "negative wheel mapping must accept wheel-down");
        require(!MouseWheelBindingMath.isActive(0.0, -1.0, 1.0),
                "negative wheel mapping must reject wheel-up");
        require(!MouseWheelBindingMath.isActive(0.0, 0.0, 1.0),
                "invalid zero-width mapping must stay inactive");
        require(!MouseWheelBindingMath.isActive(Double.NaN, 1.0, 1.0),
                "non-finite bounds must stay inactive");
        require(MouseWheelBindingMath.isActive(0,1,.05)&&MouseWheelBindingMath.isActive(0,-1,-.05),"high resolution wheel keeps both directions");
        require(!MouseWheelBindingMath.isActive(0,1,0)&&!MouseWheelBindingMath.isActive(0,-1,Double.POSITIVE_INFINITY),"zero/nonfinite wheel input rejected");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private MouseWheelBindingMathChecks() {
    }
}
