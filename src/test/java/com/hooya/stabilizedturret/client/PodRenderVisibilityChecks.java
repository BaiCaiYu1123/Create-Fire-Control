package com.hooya.stabilizedturret.client;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PodRenderVisibilityChecks {
    public static void main(String[] args) {
        exitRestoresThePreviousPod();
        switchingPodsRestoresTheOldMesh();
        worldPodsAndMutablePositions();
        System.out.println("PASS: pod mesh restoration on exit, rebind, session reset and world-mounted pods");
    }

    private static void exitRestoresThePreviousPod() {
        PodRenderVisibility visibility = new PodRenderVisibility();
        var pod = new PodRenderVisibility.Pod(new BlockPos(20000000, 75, 20000000), UUID.randomUUID());
        List<PodRenderVisibility.Pod> refreshed = new ArrayList<>();
        visibility.update(pod.pos(), pod.subLevelId(), target -> {
            require(target.equals(visibility.hiddenPod()), "Entry hides the pod before compiling its mesh");
            refreshed.add(target);
        });
        require(refreshed.equals(List.of(pod)), "Entry rebuilds the bound pod section");
        refreshed.clear();
        visibility.update(pod.pos(), pod.subLevelId(), refreshed::add);
        require(refreshed.isEmpty(), "Repeated session packets do not rebuild section meshes");

        // Exiting may replace the camera reference with a distant cannon or
        // null; the renderer must retain the actual previously hidden owner.
        visibility.update(null, UUID.randomUUID(), target -> {
            require(visibility.hiddenPod() == null, "Exit restores visibility before scheduling compilation");
            refreshed.add(target);
        });
        require(refreshed.equals(List.of(pod)), "Exit rebuilds the old pod even without its camera reference");
        refreshed.clear();
        visibility.update(null, null, refreshed::add);
        require(refreshed.isEmpty(), "Repeated reset leaves no hidden pod or redundant rebuild");
    }

    private static void switchingPodsRestoresTheOldMesh() {
        PodRenderVisibility visibility = new PodRenderVisibility();
        BlockPos pos = new BlockPos(20000000, 75, 20000000);
        var first = new PodRenderVisibility.Pod(pos, UUID.randomUUID());
        var second = new PodRenderVisibility.Pod(pos, UUID.randomUUID());
        List<PodRenderVisibility.Pod> refreshed = new ArrayList<>();
        visibility.update(first.pos(), first.subLevelId(), refreshed::add);
        refreshed.clear();
        visibility.update(second.pos(), second.subLevelId(), target -> {
            require(second.equals(visibility.hiddenPod()), "Rebinding publishes the new identity before any rebuild");
            refreshed.add(target);
        });
        require(refreshed.equals(List.of(first, second)), "Rebinding restores the old pod and hides the new one");
        require(!visibility.hiddenPod().matches(pos, first.subLevelId()), "Other structures at the same position stay visible");
        require(!visibility.hiddenPod().matches(pos.offset(1, 0, 0), second.subLevelId()), "Adjacent devices stay visible");
        refreshed.clear();
        visibility.update(null, null, refreshed::add);
        require(refreshed.equals(List.of(second)), "Reset restores the most recently hidden pod");
    }

    private static void worldPodsAndMutablePositions() {
        PodRenderVisibility visibility = new PodRenderVisibility();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(15, 80, -16);
        BlockPos original = cursor.immutable();
        var pod = new PodRenderVisibility.Pod(original, null);
        List<PodRenderVisibility.Pod> refreshed = new ArrayList<>();
        visibility.update(cursor, null, refreshed::add);
        cursor.move(1, 0, 0);
        require(visibility.hiddenPod().matches(original, null), "Mesh iteration cannot move the retained pod position");
        require(!visibility.hiddenPod().matches(original, UUID.randomUUID()), "World pods do not hide a structure's pod");
        visibility.update(null, null, refreshed::add);
        require(refreshed.equals(List.of(pod, pod)), "World-mounted pods receive both entry and exit invalidation");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
