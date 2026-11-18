package com.hooya.stabilizedturret.content.controller;

import org.objectweb.asm.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Preserve all installed features; replace only the audited IR/AGM launch-validation methods. */
public final class RadarLockMethodPatch {
    public static void main(String[] args) throws Exception {
        try (var base = new ZipFile(args[0]); var compiled = new ZipFile(args[1])) {
            patch(base, compiled, "StabilizerControllerBlockEntity",
                    Set.of("getAirToGroundLaunchTargetSnapshot", "isMissileLockVisible", "isMissileLockPending"),
                    Set.of("isMissileLockPending"), Path.of(args[2]));
            patch(base, compiled, "InfraredTargeting", Set.of("launchTarget"), Set.of(), Path.of(args[2]));
        }
    }
    private static void patch(ZipFile base, ZipFile compiled, String name, Set<String> methods,
                              Set<String> added, Path output) throws Exception {
        String owner = "com/hooya/stabilizedturret/content/controller/" + name;
        byte[] original = base.getInputStream(base.getEntry(owner + ".class")).readAllBytes();
        byte[] replacement = compiled.getInputStream(compiled.getEntry(owner + ".class")).readAllBytes();
        var reader = new ClassReader(original); var writer = new ClassWriter(reader, 0);
        Set<String> removed = new HashSet<>(), inserted = new HashSet<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (methods.contains(name)) { removed.add(name); return null; }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);
        new ClassReader(replacement).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!methods.contains(name)) return null;
                if (!inserted.add(name)) throw new AssertionError("Unexpected overload: " + name);
                return writer.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);
        Set<String> expectedRemoved = new HashSet<>(methods); expectedRemoved.removeAll(added);
        if (!removed.equals(expectedRemoved) || !inserted.equals(methods)) throw new AssertionError("Unexpected method boundary " + name);
        byte[] result = writer.toByteArray();
        Map<String, byte[]> before = audit(original, methods), after = audit(result, methods);
        if (!before.keySet().equals(after.keySet())) throw new AssertionError("Unrelated methods changed");
        for (String key : before.keySet()) if (!Arrays.equals(before.get(key), after.get(key)))
            throw new AssertionError("Unrelated method changed: " + key);
        Files.createDirectories(output); Files.write(output.resolve(name + ".class"), result);
        System.out.println("PASS: " + name + " patched " + methods + "; " + before.size() + " other methods preserved");
    }
    private static Map<String, byte[]> audit(byte[] bytes, Set<String> excluded) {
        Map<String, ClassWriter> methods = new LinkedHashMap<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (excluded.contains(name)) return null;
                ClassWriter method = new ClassWriter(0);
                method.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "Audit", null, "java/lang/Object", null);
                methods.put(name + descriptor, method);
                return method.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);
        Map<String, byte[]> result = new LinkedHashMap<>();
        methods.forEach((key, value) -> { value.visitEnd(); result.put(key, value.toByteArray()); });
        return result;
    }
}
