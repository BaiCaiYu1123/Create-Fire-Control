package com.hooya.stabilizedturret.content.controller;

import com.hooya.stabilizedturret.client.compat.VoxyLodRaycasterChecks;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.*;

import java.util.*;

/** Verifies the actual mixed production JAR, rather than source-set output. */
public final class RadarPackagedArtifactChecks {
    public static void main(String[] args) throws Exception {
        Class<?> controller = Class.forName(StabilizerControllerBlockEntity.class.getName(), false,
                RadarPackagedArtifactChecks.class.getClassLoader());
        controller.getDeclaredMethod("updateRadarScan"); // Resolves schema and triggers JVM method verification.
        RadarVisibility.class.getMethod("scan", Level.class, Vec3.class, Set.class, List.class, Set.class);
        if (!RadarVisibility.lockPriority(new UUID(0, 1), new UUID(0, 2)).equals(Set.of(new UUID(0, 1))))
            throw new AssertionError("An established lock must take precedence over selection");
        int[] priorityCalls = {0}, scanCalls = {0};
        try (var stream = controller.getResourceAsStream("StabilizerControllerBlockEntity.class")) {
            new ClassReader(Objects.requireNonNull(stream)).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                           String signature, String[] exceptions) {
                    if (!name.equals("updateRadarScan")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                               String descriptor, boolean isInterface) {
                            if (!owner.equals("com/hooya/stabilizedturret/content/controller/RadarVisibility")) return;
                            if (name.equals("lockPriority")) priorityCalls[0]++;
                            if (name.equals("scan") && descriptor.endsWith("Ljava/util/List;Ljava/util/Set;)Ljava/util/Set;"))
                                scanCalls[0]++;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG);
        }
        if (priorityCalls[0] != 1 || scanCalls[0] != 1)
            throw new AssertionError("Packaged controller must call the priority-aware scanner exactly once");
        System.out.println("PASS: packaged production controller verifies and links the lock-priority scanner");
        VoxyLodRaycasterChecks.main(args);
    }
    private RadarPackagedArtifactChecks() {}
}
