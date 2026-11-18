package com.hooya.stabilizedturret.client;

import java.util.ArrayDeque;

/** Sustained salvos and high FPS must not multiply live or per-tick particles. */
public final class RocketParticleBudgetChecks {
    private record Active(long expires, RocketParticleBudget.Ticket ticket) {}

    public static void main(String[] args) {
        for (var kind : RocketParticleBudget.Kind.values()) sustainedSalvo(kind);
        removalAndWorldChange();
        simultaneousRocketAndFlare();
        System.out.println("Rocket particle budget checks passed");
    }

    private static void sustainedSalvo(RocketParticleBudget.Kind kind) {
        var budget = new RocketParticleBudget();
        var active = new ArrayDeque<Active>();
        int admitted = 0;
        boolean reachedCap = false;
        for (long tick = 0; tick < 1200; tick++) {
            while (!active.isEmpty() && active.peekFirst().expires <= tick) {
                active.removeFirst().ticket.close();
            }
            int thisTick = 0;
            // 192 missiles rendered 13 times per 20 Hz simulation tick.
            for (int frame = 0; frame < 13; frame++) {
                for (int shot = 0; shot < 192; shot++) {
                    var ticket = budget.acquire(kind, tick);
                    if (ticket != null) {
                        active.addLast(new Active(tick + kind.lifetime, ticket));
                        admitted++;
                        thisTick++;
                    }
                }
            }
            require(thisTick <= kind.perTick, "render FPS bypassed spawn limit: " + kind);
            require(budget.live(kind) <= kind.maxLive, "salvo exceeded live limit: " + kind);
            require(budget.live(kind) == active.size(), "live accounting drifted: " + kind);
            reachedCap |= budget.live(kind) == kind.maxLive;
        }
        require(reachedCap, "stress run did not exercise live limit: " + kind);
        require(admitted > kind.maxLive * 5, "particles stopped replenishing: " + kind);
        active.forEach(value -> value.ticket.close());
        require(budget.live(kind) == 0, "expired salvo did not clear: " + kind);
        require(budget.acquire(kind, 2000) != null, "dropped requests blocked a later shot");
    }

    private static void removalAndWorldChange() {
        var budget = new RocketParticleBudget();
        var kind = RocketParticleBudget.Kind.SMOKE;
        for (int i = 0; i < kind.perTick; i++) {
            var ticket = budget.acquire(kind, 1);
            require(ticket != null, "fresh budget refused particle");
            ticket.close();
            ticket.close();
        }
        require(budget.live(kind) == 0, "double removal corrupted count");
        require(budget.acquire(kind, 1) == null, "early eviction bypassed per-tick cap");
        var oldWorld = budget.acquire(kind, 2);
        budget.clear();
        var newWorld = budget.acquire(kind, 2);
        require(newWorld != null, "world change did not reset spawn budget");
        oldWorld.close();
        require(budget.live(kind) == 1, "old-world removal corrupted new-world count");
        newWorld.close();
        require(budget.live(kind) == 0, "new-world cleanup leaked count");
    }

    private static void simultaneousRocketAndFlare() {
        var budget = new RocketParticleBudget();
        var tickets = new java.util.ArrayList<RocketParticleBudget.Ticket>();
        for (long tick = 0; tick < 8; tick++) for (int i = 0; i < 32; i++) {
            var ticket = budget.acquire(RocketParticleBudget.Kind.FLARE_GLOW, tick);
            require(ticket != null, "flare stress setup failed");
            tickets.add(ticket);
        }
        require(budget.acquire(RocketParticleBudget.Kind.FLARE_GLOW, 8) == null, "flare live cap bypassed");
        for (int i = 0; i < RocketParticleBudget.Kind.FLAME.perTick; i++) {
            var ticket = budget.acquire(RocketParticleBudget.Kind.FLAME, 8);
            require(ticket != null, "flare saturation starved rocket exhaust");
            tickets.add(ticket);
        }
        var smoke = budget.acquire(RocketParticleBudget.Kind.FLARE_SMOKE, 8);
        require(smoke != null, "glow saturation starved flare smoke");
        tickets.add(smoke);
        tickets.forEach(RocketParticleBudget.Ticket::close);
        for (var kind : RocketParticleBudget.Kind.values()) require(budget.live(kind) == 0, "mixed salvo leaked particles");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private RocketParticleBudgetChecks() {}
}
