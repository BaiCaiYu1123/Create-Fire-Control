package com.hooya.stabilizedturret.compat.simulated;

import com.hooya.stabilizedturret.StabilizedTurret;
import dev.simulated_team.simulated.content.blocks.merging_glue.MergingGlueBlockEntity;
import dev.simulated_team.simulated.index.SimBlocks;
import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Exercises the transformed production class with and without Let it Walk!. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID)
public final class MergingGlueIntegration {
    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        try {
            var level = event.getServer().overworld();
            BlockPos pos = new BlockPos(0, 160, 0);
            level.setBlockAndUpdate(pos, SimBlocks.MERGING_GLUE.getDefaultState());
            var glue = (MergingGlueBlockEntity) level.getBlockEntity(pos);
            require(glue != null, "production merging glue is registered and constructed");

            for (var method : MergingGlueBlockEntity.class.getDeclaredMethods()) {
                if (method.getName().contains("createFireControl$preferBound")) {
                    method.setAccessible(true);
                    for (double mass : new double[]{12.5, Double.MAX_VALUE / 4.0}) {
                        require((double) method.invoke(glue, mass) == mass,
                                "unbound glue preserves the incoming mass in " + method.getName());
                    }
                }
            }

            var transformed = new ClassNode();
            new ClassReader(Files.readAllBytes(Path.of(".mixin.out/class/"
                    + MergingGlueBlockEntity.class.getName().replace('.', '/') + ".class")))
                    .accept(transformed, 0);
            List<String> massCalls = new ArrayList<>();
            for (var method : transformed.methods) {
                if (!method.name.equals("tick")) continue;
                for (var instruction : method.instructions) {
                    if (!(instruction instanceof MethodInsnNode call)) continue;
                    if (call.name.contains("walkers$walkersAreHeaviest")) massCalls.add("walker");
                    if (call.name.equals("getMass")) massCalls.add("mass");
                    if (call.name.contains("createFireControl$preferBoundPartner")) massCalls.add("partner");
                    if (call.name.contains("createFireControl$preferBoundController")) massCalls.add("controller");
                }
            }
            boolean walkersLoaded = ModList.get().isLoaded("let_it_walk");
            String provider = walkersLoaded ? "walker" : "mass";
            require(massCalls.equals(List.of(provider, "partner", provider, "controller")),
                    "both fire-control modifiers follow the original mass providers: " + massCalls);
            Files.writeString(Path.of("merging-glue-result.txt"),
                    "PASS: production glue loaded; both mass modifiers compose; let_it_walk="
                            + walkersLoaded + "\n");
        } catch (Throwable failure) {
            failure.printStackTrace();
            try {
                Files.writeString(Path.of("merging-glue-result.txt"), "FAIL: " + failure + "\n");
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        } finally {
            event.getServer().halt(false);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
