package com.hooya.stabilizedturret.client.preview;

import java.util.ArrayList;
import java.util.List;

/** 18 cubed halo: -1 unknown, 0 air, 1 translucent context, 2 opaque equipment/turret. */
public final class VoxelPreviewMesher {
    public record Face(int axis, int sign, int plane, int u, int v, int width, int height, int step, boolean opaque) {}
    private VoxelPreviewMesher() {}
    public static int index(int x, int y, int z) { return (x * 18 + y) * 18 + z; }
    public static long fingerprint(byte[] occupancy) {
        long hash = 0xcbf29ce484222325L;
        for (byte cell : occupancy) { hash ^= cell; hash *= 0x100000001b3L; }
        return hash;
    }
    public static List<Face> mesh(byte[] occupancy) {
        if (occupancy.length != 18 * 18 * 18) throw new IllegalArgumentException("Expected 18 cubed occupancy");
        List<Face> result = mesh(occupancy, 1, 4096);
        if (result != null) return result;
        boolean opaque=false,context=false;
        for(byte cell:occupancy){opaque|=cell==2;context|=cell==1;}
        // Coarsening a mixed 2 cubed cell would make adjacent unrelated blocks opaque.
        // Preserve that boundary exactly; the scene/cache face budgets still bound total output.
        return mesh(occupancy, opaque&&context?1:2, Integer.MAX_VALUE);
    }
    private static List<Face> mesh(byte[] data, int step, int budget) {
        List<Face> result = new ArrayList<>();
        int n = 16 / step;
        for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
            for (int slice = 0; slice < n; slice++) {
                byte[][] mask = new byte[n][n];
                for (int u = 0; u < n; u++) for (int v = 0; v < n; v++) {
                    int[] cell = coordinates(axis, slice * step, u * step, v * step);
                    int[] neighbor = cell.clone(); neighbor[axis] += sign * step;
                    byte material = occupied(data, cell, step), adjacent = occupied(data, neighbor, step);
                    // Equipment enclosed by contextual blocks still needs a complete visible shell.
                    if (material > 0 && (adjacent == 0 || material == 2 && adjacent == 1)) mask[u][v] = material;
                }
                for (int u = 0; u < n; u++) for (int v = 0; v < n; v++) {
                    byte material = mask[u][v];
                    if (material == 0) continue;
                    int width = 1, height = 1;
                    while (u + width < n && mask[u + width][v] == material) width++;
                    outer: while (v + height < n) {
                        for (int k = 0; k < width; k++) if (mask[u + k][v + height] != material) break outer;
                        height++;
                    }
                    for (int a = 0; a < width; a++) for (int b = 0; b < height; b++) mask[u + a][v + b] = 0;
                    result.add(new Face(axis, sign, (slice + (sign > 0 ? 1 : 0)) * step,
                            u * step, v * step, width * step, height * step, step, material == 2));
                    if (result.size() > budget) return null;
                }
            }
        }
        return List.copyOf(result);
    }
    private static byte occupied(byte[] data, int[] cell, int step) {
        boolean unknown = false;
        boolean contextual = false;
        for (int x = cell[0]; x < cell[0] + step; x++) for (int y = cell[1]; y < cell[1] + step; y++)
            for (int z = cell[2]; z < cell[2] + step; z++) {
                // Coarse halo needs only the immediate neighbor; clamp to its sampled one-block border.
                byte value = data[index(Math.clamp(x + 1, 0, 17), Math.clamp(y + 1, 0, 17), Math.clamp(z + 1, 0, 17))];
                if (value == 2) return 2;
                contextual |= value == 1;
                unknown |= value < 0;
            }
        return (byte)(contextual ? 1 : unknown ? -1 : 0);
    }
    public static int[] coordinates(int axis, int plane, int u, int v) {
        return switch (axis) { case 0 -> new int[]{plane, u, v}; case 1 -> new int[]{v, plane, u}; default -> new int[]{u, v, plane}; };
    }
}
