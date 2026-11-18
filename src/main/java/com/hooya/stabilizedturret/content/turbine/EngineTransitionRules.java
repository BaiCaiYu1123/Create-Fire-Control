package com.hooya.stabilizedturret.content.turbine;

public final class EngineTransitionRules {
    private EngineTransitionRules() {}

    /** The selected incoming behavior has priority; defaults yield to an explicit departure choice. */
    public static EngineDriveBehavior rates(EngineDriveBehavior outgoing, EngineDriveBehavior incoming) {
        var rule = incoming.enteringRates() != EngineTransitionRule.DEFAULT
                ? incoming.enteringRates() : outgoing.leavingRates();
        return rule == EngineTransitionRule.INCOMING ? incoming : outgoing;
    }

}
