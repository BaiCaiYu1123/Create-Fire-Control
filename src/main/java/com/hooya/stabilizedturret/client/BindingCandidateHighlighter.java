package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.network.BindingSessionPayload;
import com.simibubi.create.AllItems;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3d;

import java.util.List;

/**
 * Create-style oriented outlines for every server-approved binding candidate.
 *
 * <p>An axis-aligned world box can never coincide with a rotated Sable block.
 * Each of the twelve block edges is therefore transformed by the exact client
 * render pose that Sable uses for the current frame.</p>
 */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class BindingCandidateHighlighter {
    private static final int UNBOUND_COLOR = 0x5EA7FF;
    private static final int BOUND_COLOR = 0xE94B4B;
    private static final int SCOPE_COLOR = 0x43E86B;
    private static final double SCOPE_BOX_HALF_SIZE = 0.18;
    private static final int[][] EDGES = {
            {0, 1}, {0, 2}, {0, 4},
            {1, 3}, {1, 5},
            {2, 3}, {2, 6},
            {3, 7},
            {4, 5}, {4, 6},
            {5, 7},
            {6, 7}
    };

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientBindingInspectionState.tick();
        if (ClientBindingState.isActive() && (minecraft.player == null
                || (!AllItems.WRENCH.isIn(minecraft.player.getMainHandItem())
                && !AllItems.WRENCH.isIn(minecraft.player.getOffhandItem())))) {
            ClientBindingState.accept(false, java.util.List.of());
            return;
        }
        if (minecraft.level == null) return;
        SubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
        if (ClientBindingState.isActive()) {
            for (BindingSessionPayload.Candidate candidate : ClientBindingState.getCandidates()) {
                renderCandidate(candidate, container);
            }
        }
        if (ClientBindingInspectionState.isActive()) {
            for (BindingSessionPayload.Candidate candidate
                    : ClientBindingInspectionState.getBindings()) {
                renderCandidate(candidate, container);
            }
            renderScopeBox();
        }
    }

    private static void renderCandidate(
            BindingSessionPayload.Candidate candidate,
            SubLevelContainer container
    ) {
            Vec3[] corners = candidateCorners(candidate, container);
            if (corners == null) return;
            int color = candidate.bound() ? BOUND_COLOR : UNBOUND_COLOR;
            for (int edge = 0; edge < EDGES.length; edge++) {
                int[] endpoints = EDGES[edge];
                Outliner.getInstance()
                        .showLine(new EdgeKey(candidate, edge),
                                corners[endpoints[0]], corners[endpoints[1]])
                        .colored(color)
                        .disableLineNormals()
                        .lineWidth(1.0F / 16.0F);
            }
    }

    private static void renderScopeBox() {
        Minecraft minecraft = Minecraft.getInstance();
        SubLevelContainer container = minecraft.level == null
                ? null : SubLevelContainer.getContainer(minecraft.level);
        Vec3 centre = inspectionScopePosition(container);
        if (centre == null) return;
        Vec3[] corners = new Vec3[8];
        double minX = centre.x - SCOPE_BOX_HALF_SIZE;
        double minY = centre.y - SCOPE_BOX_HALF_SIZE;
        double minZ = centre.z - SCOPE_BOX_HALF_SIZE;
        double maxX = centre.x + SCOPE_BOX_HALF_SIZE;
        double maxY = centre.y + SCOPE_BOX_HALF_SIZE;
        double maxZ = centre.z + SCOPE_BOX_HALF_SIZE;
        for (int corner = 0; corner < corners.length; corner++) {
            corners[corner] = new Vec3(
                    (corner & 1) == 0 ? minX : maxX,
                    (corner & 2) == 0 ? minY : maxY,
                    (corner & 4) == 0 ? minZ : maxZ
            );
        }
        for (int edge = 0; edge < EDGES.length; edge++) {
            int[] endpoints = EDGES[edge];
            Outliner.getInstance()
                    .showLine(new ScopeEdgeKey(edge), corners[endpoints[0]], corners[endpoints[1]])
                    .colored(SCOPE_COLOR)
                    .disableLineNormals()
                    .lineWidth(1.0F / 12.0F);
        }
    }

    private static Vec3 inspectionScopePosition(SubLevelContainer container) {
        List<BindingSessionPayload.Candidate> references =
                ClientBindingInspectionState.getScopeReferences();
        if (references.isEmpty()) return null;
        Vec3 centre = Vec3.ZERO;
        int count = 0;
        for (BindingSessionPayload.Candidate reference : references) {
            Vec3 world = candidatePoint(reference, Vec3.ZERO, container);
            if (world == null) continue;
            centre = centre.add(world);
            count++;
        }
        if (count == 0) return null;
        centre = centre.scale(1.0 / count);
        BindingSessionPayload.Candidate frame = references.get(0);
        Vec3 frameCentre = candidatePoint(frame, Vec3.ZERO, container);
        Vec3 frameOffset = candidatePoint(
                frame, ClientBindingInspectionState.getScopeOffset(), container);
        if (frameCentre == null || frameOffset == null) return centre;
        return centre.add(frameOffset.subtract(frameCentre));
    }

    private static Vec3 candidatePoint(
            BindingSessionPayload.Candidate candidate,
            Vec3 offset,
            SubLevelContainer container
    ) {
        Vec3 local = Vec3.atCenterOf(candidate.pos()).add(offset);
        if (candidate.subLevelId() == null) return local;
        if (container == null) return null;
        SubLevel subLevel = container.getSubLevel(candidate.subLevelId());
        if (subLevel == null || subLevel.isRemoved()) return null;
        Vector3d point = new Vector3d(local.x, local.y, local.z);
        var pose = subLevel instanceof ClientSubLevel clientSubLevel
                ? clientSubLevel.renderPose()
                : subLevel.logicalPose();
        pose.transformPosition(point);
        return new Vec3(point.x, point.y, point.z);
    }

    private static Vec3[] candidateCorners(
            BindingSessionPayload.Candidate candidate,
            SubLevelContainer container
    ) {
        Vec3[] corners = new Vec3[8];
        if (candidate.subLevelId() == null) {
            for (int corner = 0; corner < corners.length; corner++) {
                corners[corner] = new Vec3(
                        candidate.pos().getX() + ((corner & 1) == 0 ? 0.0 : 1.0),
                        candidate.pos().getY() + ((corner & 2) == 0 ? 0.0 : 1.0),
                        candidate.pos().getZ() + ((corner & 4) == 0 ? 0.0 : 1.0)
                );
            }
            return corners;
        }
        if (container == null) return null;
        SubLevel subLevel = container.getSubLevel(candidate.subLevelId());
        if (subLevel == null || subLevel.isRemoved()) return null;

        // The logical pose is authoritative for physics, but the player sees
        // ClientSubLevel.renderPose(). Using it here eliminates the visible
        // one-tick offset on moving/rotating structures.
        var pose = subLevel instanceof ClientSubLevel clientSubLevel
                ? clientSubLevel.renderPose()
                : subLevel.logicalPose();
        for (int corner = 0; corner < corners.length; corner++) {
            Vector3d point = new Vector3d(
                    candidate.pos().getX() + ((corner & 1) == 0 ? 0.0 : 1.0),
                    candidate.pos().getY() + ((corner & 2) == 0 ? 0.0 : 1.0),
                    candidate.pos().getZ() + ((corner & 4) == 0 ? 0.0 : 1.0)
            );
            pose.transformPosition(point);
            corners[corner] = new Vec3(point.x, point.y, point.z);
        }
        return corners;
    }

    private record EdgeKey(BindingSessionPayload.Candidate candidate, int edge) {
    }

    private record ScopeEdgeKey(int edge) {
    }

    private BindingCandidateHighlighter() {
    }
}
