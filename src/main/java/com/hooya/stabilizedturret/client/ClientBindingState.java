package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.network.BindingSessionPayload;

import java.util.List;

public final class ClientBindingState {
    private static boolean active;
    private static List<BindingSessionPayload.Candidate> candidates = List.of();

    public static boolean isActive() {
        return active;
    }

    public static List<BindingSessionPayload.Candidate> getCandidates() {
        return candidates;
    }

    public static void accept(boolean active, List<BindingSessionPayload.Candidate> candidates) {
        accept(active,false,candidates);
    }
    public static void accept(boolean active,boolean append,List<BindingSessionPayload.Candidate> candidates) {
        if(active&&append&&ClientBindingState.active) {
            var combined=new java.util.LinkedHashSet<>(ClientBindingState.candidates);combined.addAll(candidates);
            ClientBindingState.candidates=List.copyOf(combined);return;
        }
        ClientBindingState.active = active;
        ClientBindingState.candidates = active ? List.copyOf(candidates) : List.of();
    }

    private ClientBindingState() {
    }
}
