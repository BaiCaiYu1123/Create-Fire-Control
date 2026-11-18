package com.hooya.stabilizedturret.content.controller;

import org.objectweb.asm.*;
import java.nio.file.*;
import java.util.zip.ZipFile;

/** Patches only one call in the installed controller, preserving its other feature branches. */
public final class RadarControllerPriorityPatch {
    public static void main(String[] args) throws Exception {
        String owner = "com/hooya/stabilizedturret/content/controller/StabilizerControllerBlockEntity";
        String radar = "com/hooya/stabilizedturret/content/controller/RadarVisibility";
        String oldDescriptor = "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;Ljava/util/Set;Ljava/util/List;)Ljava/util/Set;";
        String newDescriptor = oldDescriptor.replace("Ljava/util/List;)", "Ljava/util/List;Ljava/util/Set;)");
        byte[] bytes;
        try (var zip = new ZipFile(args[0])) { bytes = zip.getInputStream(zip.getEntry(owner + ".class")).readAllBytes(); }
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] changes = {0}, existingScans = {0}, existingPriorities = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("updateRadarScan") || !descriptor.equals("()V")) return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override public void visitMethodInsn(int opcode, String callOwner, String name, String descriptor, boolean isInterface) {
                        if (callOwner.equals(radar) && name.equals("scan") && descriptor.equals(newDescriptor)) existingScans[0]++;
                        if (callOwner.equals(radar) && name.equals("lockPriority")) existingPriorities[0]++;
                        if (callOwner.equals(radar) && name.equals("scan") && descriptor.equals(oldDescriptor)) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, owner, "radarLockedSubLevelId", "Ljava/util/UUID;");
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, owner, "aircraftSelectedTargetId", "Ljava/util/UUID;");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, radar, "lockPriority", "(Ljava/util/UUID;Ljava/util/UUID;)Ljava/util/Set;", false);
                            super.visitMethodInsn(opcode, callOwner, name, newDescriptor, isInterface);
                            changes[0]++;
                        } else super.visitMethodInsn(opcode, callOwner, name, descriptor, isInterface);
                    }
                };
            }
        }, 0);
        boolean alreadyPatched = changes[0] == 0 && existingScans[0] == 1 && existingPriorities[0] == 1;
        if (!alreadyPatched && !(changes[0] == 1 && existingScans[0] == 0 && existingPriorities[0] == 0))
            throw new AssertionError("Unexpected controller radar calls: old=" + changes[0]
                    + ", new=" + existingScans[0] + ", priority=" + existingPriorities[0]);
        Path result = Path.of(args[1]); Files.createDirectories(result.getParent());
        Files.write(result, alreadyPatched ? bytes : writer.toByteArray());
        System.out.println(alreadyPatched
                ? "PASS: installed controller already has exactly one lock-priority scan; preserved byte-for-byte"
                : "PASS: inserted lock priority into exactly one installed-controller call; all other methods preserved");
    }
}
