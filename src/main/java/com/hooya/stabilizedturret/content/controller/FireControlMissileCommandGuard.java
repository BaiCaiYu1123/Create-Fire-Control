package com.hooya.stabilizedturret.content.controller;

/** Marks the synchronous native launcher call made by a bound fire-control computer. */
public final class FireControlMissileCommandGuard {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    public static Scope enter() {
        DEPTH.set(DEPTH.get() + 1);
        return new Scope();
    }

    public static boolean isLaunching() {
        return DEPTH.get() > 0;
    }

    public static final class Scope implements AutoCloseable {
        private boolean closed;

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            int next = DEPTH.get() - 1;
            if (next <= 0) DEPTH.remove();
            else DEPTH.set(next);
        }
    }

    private FireControlMissileCommandGuard() {
    }
}