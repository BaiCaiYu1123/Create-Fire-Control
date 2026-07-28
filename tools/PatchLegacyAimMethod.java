import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Replaces only AxisLock.aimAtDirection in the preserved 01:02 physical
 * stabilizer class. All constraint/pose/physics methods stay byte-for-byte
 * equivalent at the instruction level to the known-good legacy build.
 */
public final class PatchLegacyAimMethod {
    private static final String METHOD_NAME = "aimAtDirection";

    private PatchLegacyAimMethod() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: PatchLegacyAimMethod <legacy class> <compiled source class> <output class>"
            );
        }

        Path legacyPath = Path.of(args[0]);
        Path sourcePath = Path.of(args[1]);
        Path outputPath = Path.of(args[2]);
        ClassNode legacy = read(legacyPath);
        ClassNode source = read(sourcePath);

        MethodNode replacement = findUnique(source, METHOD_NAME);
        int legacyIndex = findUniqueIndex(legacy, METHOD_NAME, replacement.desc);
        MethodNode copy = new MethodNode(
                Opcodes.ASM9,
                replacement.access,
                replacement.name,
                replacement.desc,
                replacement.signature,
                replacement.exceptions.toArray(String[]::new)
        );
        replacement.accept(copy);
        legacy.methods.set(legacyIndex, copy);

        ClassWriter writer = new ClassWriter(0);
        legacy.accept(writer);
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(outputPath, writer.toByteArray());
    }

    private static ClassNode read(Path path) throws IOException {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(Files.readAllBytes(path)).accept(node, 0);
        return node;
    }

    private static MethodNode findUnique(ClassNode owner, String name) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (!method.name.equals(name)) {
                continue;
            }
            if (found != null) {
                throw new IllegalStateException("Overloaded source method: " + name);
            }
            found = method;
        }
        if (found == null) {
            throw new IllegalStateException("Missing source method: " + name);
        }
        return found;
    }

    private static int findUniqueIndex(ClassNode owner, String name, String descriptor) {
        int found = -1;
        for (int i = 0; i < owner.methods.size(); i++) {
            MethodNode method = owner.methods.get(i);
            if (!method.name.equals(name) || !method.desc.equals(descriptor)) {
                continue;
            }
            if (found >= 0) {
                throw new IllegalStateException("Duplicate legacy method: " + name + descriptor);
            }
            found = i;
        }
        if (found < 0) {
            throw new IllegalStateException("Missing legacy method: " + name + descriptor);
        }
        return found;
    }
}
