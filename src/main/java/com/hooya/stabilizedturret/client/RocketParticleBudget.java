package com.hooya.stabilizedturret.client;

/** Admission control shared by all virtual rockets, independent of render FPS. */
public final class RocketParticleBudget {
    public enum Kind {
        FLAME(24, 96, 5), SMOKE(8, 128, 32), EXPLOSION(12, 192, 50),
        FLARE_GLOW(32, 256, 12), FLARE_SPARK(24, 48, 3), FLARE_SMOKE(8, 128, 32);

        public final int perTick;
        public final int maxLive;
        public final int lifetime;

        Kind(int perTick, int maxLive, int lifetime) {
            this.perTick = perTick;
            this.maxLive = maxLive;
            this.lifetime = lifetime;
        }
    }

    private final int[] live = new int[Kind.values().length];
    private final int[] spawned = new int[Kind.values().length];
    private long currentTick = Long.MIN_VALUE;
    private long generation;

    /** A failed request is dropped; it never creates a delayed spawn queue. */
    public Ticket acquire(Kind kind, long tick) {
        if (tick != currentTick) {
            java.util.Arrays.fill(spawned, 0);
            currentTick = tick;
        }
        int index = kind.ordinal();
        if (live[index] >= kind.maxLive || spawned[index] >= kind.perTick) return null;
        live[index]++;
        spawned[index]++;
        return new Ticket(index, generation);
    }

    public void clear() {
        generation++;
        currentTick = Long.MIN_VALUE;
        java.util.Arrays.fill(live, 0);
        java.util.Arrays.fill(spawned, 0);
    }

    public int live(Kind kind) { return live[kind.ordinal()]; }

    /** Release follows particle removal or the owner's eviction cleanup. */
    public final class Ticket implements AutoCloseable {
        private final int index;
        private final long acquiredGeneration;
        private boolean closed;

        private Ticket(int index, long acquiredGeneration) {
            this.index = index;
            this.acquiredGeneration = acquiredGeneration;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (acquiredGeneration == generation) live[index]--;
        }
    }
}
