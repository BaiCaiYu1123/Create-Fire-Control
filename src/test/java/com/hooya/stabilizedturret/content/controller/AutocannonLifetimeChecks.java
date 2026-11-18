package com.hooya.stabilizedturret.content.controller;

/** Executable off-by-one regression for the hard autocannon-shell lifetime. */
public final class AutocannonLifetimeChecks {
    public static void main(String[] args) {
        require(MianbaoAutocannonProjectileLifetimeHandler.LIFETIME_TICKS == 400,
                "20 seconds at 20 TPS must equal 400 ticks");
        long expiry = MianbaoAutocannonProjectileLifetimeHandler.expiryTick(100L);
        require(expiry == 500L, "expiry must be launch tick plus 400");
        require(!MianbaoAutocannonProjectileLifetimeHandler.isExpired(499L, expiry),
                "shell must survive through the tick immediately before expiry");
        require(MianbaoAutocannonProjectileLifetimeHandler.isExpired(500L, expiry),
                "shell must be deleted exactly on the 400th elapsed tick");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private AutocannonLifetimeChecks() {
    }
}
