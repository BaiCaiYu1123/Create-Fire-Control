package com.hooya.stabilizedturret.client;

/** Pure counterpart of Tweaked Controllers' MouseWheelInput button threshold. */
final class MouseWheelBindingMath {
    static boolean isActive(double minimum, double maximum, double scrollY) {
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum)
                || !Double.isFinite(scrollY) || Math.abs(scrollY)<1.0e-6 || minimum == maximum) {
            return false;
        }
        double direction=Math.copySign(Math.max(1.0,Math.abs(scrollY)),scrollY);
        double normalized = (direction - minimum) / (maximum - minimum);
        normalized = Math.max(0.0, Math.min(1.0, normalized));
        return normalized >= 0.5;
    }

    private MouseWheelBindingMath() {
    }
}
