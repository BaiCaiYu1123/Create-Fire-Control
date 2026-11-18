package com.hooya.stabilizedturret.content.armor;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ArmorContactGridTest {
    private static final int SIZE = 64;
    private static final List<Vec3> BOX_AXES = List.of(new Vec3(1, 0, 0),
            new Vec3(0, 1, 0), new Vec3(0, 0, 1));

    @Test
    void optimizedSweepMatchesExhaustiveBoundaryGrid() {
        var faces = new ArrayList<ArmorMesh.Face>();
        var random = new Random(31);
        for (int i = 0; i < 36; i++) {
            faces.add(face(point(random), point(random), point(random)));
        }
        for (double x : new double[] {.5, .5 - 2e-9, .5 + 2e-9, .503}) {
            faces.add(face(new Vec3(x, .1, .1), new Vec3(x, .9, .1), new Vec3(x, .8, .9)));
        }
        faces.add(face(new Vec3(.01, .02, .03), new Vec3(.99, .96, .94), new Vec3(.2, .21, .22)));
        var mesh = new ArmorMesh(faces);
        assertArrayEquals(exhaustiveBoundaryGrid(mesh), ArmorMeshContacts.boundaryCells(mesh));
    }

    private static ArmorMesh.Face face(Vec3 a, Vec3 b, Vec3 c) {
        return new ArmorMesh.Face(List.of(a, b, c));
    }

    private static Vec3 point(Random random) {
        return new Vec3(random.nextDouble(), random.nextDouble(), random.nextDouble());
    }

    private static boolean[] exhaustiveBoundaryGrid(ArmorMesh mesh) {
        var cells = new boolean[SIZE * SIZE * SIZE];
        for (var face : mesh.faces()) {
            var points = face.vertices();
            var axes = new ArrayList<Vec3>();
            axes.add(points.get(1).subtract(points.get(0)).cross(points.get(2).subtract(points.get(0))));
            for (int i = 0; i < 3; i++) {
                Vec3 edge = points.get((i + 1) % 3).subtract(points.get(i));
                for (Vec3 boxAxis : BOX_AXES) axes.add(edge.cross(boxAxis));
            }
            var planes = axes.stream().map(axis -> {
                double a = axis.dot(points.get(0)), b = axis.dot(points.get(1)), c = axis.dot(points.get(2));
                double extent = (Math.abs(axis.x) + Math.abs(axis.y) + Math.abs(axis.z)) * (.5 / SIZE - 1e-9);
                return new Plane(axis, Math.min(a, Math.min(b, c)), Math.max(a, Math.max(b, c)), extent);
            }).filter(plane -> plane.extent >= 1e-15).toArray(Plane[]::new);
            int[] first = new int[3], last = new int[3];
            for (int dimension = 0; dimension < 3; dimension++) {
                final int axis = dimension;
                var bounds = points.stream().mapToDouble(p -> coordinate(p, axis)).summaryStatistics();
                first[axis] = Math.max(0, (int) Math.floor(bounds.getMin() * SIZE + 1e-7));
                last[axis] = Math.min(SIZE - 1, (int) Math.ceil(bounds.getMax() * SIZE - 1e-7) - 1);
            }
            for (int z = first[2]; z <= last[2]; z++) {
                for (int y = first[1]; y <= last[1]; y++) {
                    for (int x = first[0]; x <= last[0]; x++) {
                        int index = (z * SIZE + y) * SIZE + x;
                        if (cells[index]) continue;
                        boolean hit = true;
                        for (Plane plane : planes) {
                            double projection = plane.axis.x * ((x + .5) / SIZE)
                                    + plane.axis.y * ((y + .5) / SIZE) + plane.axis.z * ((z + .5) / SIZE);
                            if (plane.low - projection >= plane.extent || plane.high - projection <= -plane.extent) {
                                hit = false;
                                break;
                            }
                        }
                        cells[index] = hit;
                    }
                }
            }
        }
        return cells;
    }

    private record Plane(Vec3 axis, double low, double high, double extent) {}

    private static double coordinate(Vec3 p, int axis) {
        return axis == 0 ? p.x : axis == 1 ? p.y : p.z;
    }
}
