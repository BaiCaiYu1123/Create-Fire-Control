package com.hooya.stabilizedturret.network;

/** Boundary checks for the suppression of inactive HUD packets. */
public final class InactiveSessionSyncChecks {
    public static void main(String[] args) {
        var gate = new InactiveSessionSync<String>();
        Object first = new Object(), respawn = new Object(), other = new Object();
        require(gate.shouldSend("a", first, "overworld", false), "Initial client state must be initialized");
        for (int i = 0; i < 100_000; i++)
            require(!gate.shouldSend("a", first, "overworld", false), "Idle state must not repeat");
        for (int i = 0; i < 5; i++)
            require(gate.shouldSend("a", first, "overworld", true), "Active sources retain real-time updates");
        require(gate.shouldSend("a", first, "overworld", false), "Leaving must clear the HUD");
        require(!gate.shouldSend("a", first, "overworld", false), "Clear state must only be sent once");
        require(gate.shouldSend("a", first, "nether", false), "Dimension transfer must reinitialize");
        require(gate.shouldSend("a", respawn, "nether", false), "Same UUID respawn must reinitialize");
        require(gate.shouldSend("b", other, "overworld", false), "Players must be independent");
        require(!gate.shouldSend("a", respawn, "nether", false), "Another player must not reset this one");
        gate.forget("a");
        require(gate.shouldSend("a", respawn, "nether", false), "Rejoin must reinitialize");
        require(!gate.shouldSend("b", other, "overworld", false), "Logout must only invalidate its player");
        gate.clear();
        require(gate.shouldSend("b", other, "overworld", false), "Integrated-server restart must reinitialize");
        System.out.println("PASS: inactive initialization, 100000 idle ticks, active updates, leaving, dimension, respawn, multiplayer, rejoin, restart");
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
