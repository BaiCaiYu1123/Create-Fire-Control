package edn.stratodonut.drivebywire.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import java.util.Map;

/** Test-only signal fixture. This source set never enters the released JAR. */
public final class ControllerSignalStore {
    public static Map<String,Integer> signals = Map.of();
    public static Map<String,Integer> getSignals(Level level, BlockPos pos) { return signals; }
}
