package com.hooya.stabilizedturret.content.controller;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Copy only reviewed methods, rename their private lambda helpers, preserve all other installed features. */
public final class RadarMemoryMethodPatch {
    public static void main(String[] args) throws Exception {
        Map<String, Set<String>> changes = new LinkedHashMap<>();
        changes.put("content/controller/StabilizerControllerBlockEntity", Set.of("cycleAircraftRadarTarget", "setRadarLockedTarget", "toggleRadarLockFromWireless", "toggleRadarLock", "updateRadarScan"));
        changes.put("content/display/ConnectedDisplayBlockEntity", Set.of("getContacts", "isRadarMemoryActive"));
        changes.put("network/RadarSessionPayload", Set.of("from"));
        changes.put("client/ClientRadarState", Set.of("accept", "getContacts"));
        changes.put("client/RadarHudRenderer", Set.of("drawTwsPanel", "drawWhiteContactCircle", "drawWhiteSelectionBars", "renderRadarScope", "drawRadarLockBars", "renderWorldFrames"));
        changes.put("client/ConnectedDisplayRenderer", Set.of("render"));
        changes.put("client/ConnectedDisplayRenderer$GeometryBatch", Set.of("begin", "vertex"));
        try (var base = new ZipFile(args[0]); var compiled = new ZipFile(args[1])) {
            for (var entry : changes.entrySet()) {
                String path = "com/hooya/stabilizedturret/" + entry.getKey() + ".class";
                ClassNode original = read(base.getInputStream(base.getEntry(path)).readAllBytes());
                ClassNode source = read(compiled.getInputStream(compiled.getEntry(path)).readAllBytes());
                Set<String> names = entry.getValue();
                Map<String, byte[]> before = audit(original, names);
                List<MethodNode> selected = new ArrayList<>(source.methods.stream().filter(m -> names.contains(m.name)).toList());
                Set<String> actual = new HashSet<>(); selected.forEach(m -> actual.add(m.name));
                if (!actual.equals(names)) throw new AssertionError("Missing reviewed method: " + path + actual);
                Map<String, String> lambdaNames = new HashMap<>();
                for (int i=0;i<selected.size();i++) {
                    for (AbstractInsnNode insn : selected.get(i).instructions) if (insn instanceof InvokeDynamicInsnNode dynamic) {
                        for (int j=0;j<dynamic.bsmArgs.length;j++) if (dynamic.bsmArgs[j] instanceof Handle h
                                && h.getOwner().equals(source.name) && h.getName().startsWith("lambda$")) {
                            String old = h.getName() + h.getDesc();
                            if (!lambdaNames.containsKey(old)) {
                                String renamed = "radarMemory$" + h.getName(); lambdaNames.put(old, renamed);
                                MethodNode helper = source.methods.stream().filter(m -> (m.name+m.desc).equals(old)).findFirst().orElseThrow();
                                helper.name = renamed; selected.add(helper);
                            }
                            dynamic.bsmArgs[j] = new Handle(h.getTag(), h.getOwner(), lambdaNames.get(old), h.getDesc(), h.isInterface());
                        }
                    }
                }
                original.methods.removeIf(m -> names.contains(m.name)); original.methods.addAll(selected);
                if (entry.getKey().endsWith("$GeometryBatch")) {
                    if (original.fields.stream().anyMatch(f -> f.name.equals("opacity"))) throw new AssertionError("Opacity already present");
                    original.fields.add(source.fields.stream().filter(f -> f.name.equals("opacity")).findFirst().orElseThrow());
                }
                Map<String, byte[]> after = audit(original, names);
                for (var m : before.entrySet()) if (!Arrays.equals(m.getValue(), after.get(m.getKey()))) throw new AssertionError("Unrelated method changed: " + path + m.getKey());
                ClassWriter writer = new ClassWriter(0); original.accept(writer);
                Path output = Path.of(args[2]).resolve(entry.getKey() + ".class"); Files.createDirectories(output.getParent()); Files.write(output, writer.toByteArray());
                System.out.println("PASS: " + entry.getKey() + " reviewed " + names + "; preserved " + before.size() + " other methods; isolated lambdas " + lambdaNames.size());
            }
        }
    }
    private static ClassNode read(byte[] bytes) { ClassNode c = new ClassNode(); new ClassReader(bytes).accept(c,0); return c; }
    private static Map<String,byte[]> audit(ClassNode c, Set<String> excluded) {
        Map<String,byte[]> out = new LinkedHashMap<>();
        for (MethodNode m:c.methods) if (!excluded.contains(m.name) && !m.name.startsWith("radarMemory$")) {
            ClassWriter w = new ClassWriter(0); w.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,"Audit",null,"java/lang/Object",null); m.accept(w);
            out.put(m.name+m.desc,w.toByteArray());
        }
        return out;
    }
}
