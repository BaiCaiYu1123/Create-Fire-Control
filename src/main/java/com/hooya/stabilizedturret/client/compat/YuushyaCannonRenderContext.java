package com.hooya.stabilizedturret.client.compat;

public final class YuushyaCannonRenderContext {
    private static final ThreadLocal<Integer> CREATE_CONTRAPTION_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    public static void enterCreateContraption() {
        CREATE_CONTRAPTION_DEPTH.set(CREATE_CONTRAPTION_DEPTH.get() + 1);
    }

    public static void exitCreateContraption() {
        int depth = CREATE_CONTRAPTION_DEPTH.get() - 1;
        if (depth <= 0) {
            CREATE_CONTRAPTION_DEPTH.remove();
        } else {
            CREATE_CONTRAPTION_DEPTH.set(depth);
        }
    }

    public static boolean isRenderingCreateContraption() {
        return CREATE_CONTRAPTION_DEPTH.get() > 0;
    }

    private YuushyaCannonRenderContext() {
    }
}
