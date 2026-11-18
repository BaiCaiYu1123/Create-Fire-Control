package com.hooya.stabilizedturret.client;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.Opcodes;
import java.io.InputStream;

/** Verifies the exact installed Sable hook and its existing velocity API without starting a game. */
public final class SableSnapshotMotionHookChecks {
    public static void main(String[] args) throws Exception {
        ClassNode packet=read("dev/ryanhcode/sable/network/packets/ClientboundSableSnapshotDualPacket.class");
        require(packet.fields.stream().anyMatch(f->f.name.equals("interpolationTick") && f.desc.equals("I")
                && (f.access & Opcodes.ACC_FINAL)!=0),"Sable's packet carries the exact interpolation tick");
        require(packet.methods.stream().anyMatch(m->m.name.equals("handleClient") && m.desc.equals(
                "(Lnet/minecraft/world/level/Level;Ldev/ryanhcode/sable/network/packets/PacketReceiveMode;)V")),
                "client injection target exists on both UDP and TCP's common receive path");
        ClassNode entry=read("dev/ryanhcode/sable/network/packets/ClientboundSableSnapshotDualPacket$Entry.class");
        for (String name : new String[]{"linearVelocity","angularVelocity"}) require(entry.methods.stream()
                .anyMatch(m->m.name.equals(name) && m.desc.equals("()Lorg/joml/Vector3fc;")),"native velocity accessor "+name);
        System.out.println("PASS: Sable 2.0.5 UDP/TCP motion hook, exact interpolation tick and native point-velocity data");
    }
    private static ClassNode read(String path) throws Exception {
        try (InputStream stream=SableSnapshotMotionHookChecks.class.getClassLoader().getResourceAsStream(path)) {
            if (stream==null) throw new AssertionError("Missing Sable bytecode "+path);
            ClassNode node=new ClassNode(); new ClassReader(stream).accept(node,ClassReader.SKIP_DEBUG); return node;
        }
    }
    private static void require(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
