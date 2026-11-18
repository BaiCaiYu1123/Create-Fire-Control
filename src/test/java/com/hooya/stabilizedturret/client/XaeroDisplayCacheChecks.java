package com.hooya.stabilizedturret.client;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;

/** Read-only regression against a supplied real Xaero cache, without launching Minecraft. */
public final class XaeroDisplayCacheChecks {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("xaeroCacheRoot"));
        Method read = XaeroDisplayMapCompat.class.getDeclaredMethod("readXaeroCacheData", DataInputStream.class);
        read.setAccessible(true);
        Class<?> region = Class.forName(XaeroDisplayMapCompat.class.getName() + "$XaeroCacheRegion");
        Method count = region.getDeclaredMethod("textureCount");
        Method color = region.getDeclaredMethod("color", int.class, int.class);
        count.setAccessible(true);
        color.setAccessible(true);
        Map<String, Object> regions = new HashMap<>();
        int textures = 0;
        try (var files = Files.list(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".xwmc")).sorted().toList()) {
                try (ZipFile zip = new ZipFile(file.toFile());
                     DataInputStream input = new DataInputStream(new BufferedInputStream(
                             zip.getInputStream(zip.getEntry("cache.xaero"))))) {
                    Object cache = read.invoke(null, input);
                    require(input.read() == -1, "The reader must consume the complete cache: " + file);
                    textures += (int) count.invoke(cache);
                    regions.put(file.getFileName().toString().replace(".xwmc", ""), cache);
                }
            }
        }
        require(textures > 0, "Expected saved Xaero terrain textures");
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        int resolved = 0;
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                int worldX = (int) Math.floor(242.5 + (x + 0.5 - 128) * 4000 / 256);
                int worldZ = (int) Math.floor(-6698.5 + (y + 0.5 - 128) * 4000 / 256);
                Object cache = regions.get(Math.floorDiv(worldX, 512) + "_" + Math.floorDiv(worldZ, 512));
                Integer rgb = cache == null ? null : (Integer) color.invoke(cache, worldX, worldZ);
                if (rgb != null) resolved++;
                image.setRGB(x, y, rgb == null ? 0x101820 : rgb);
            }
        }
        require(resolved > 256, "The reported display position must resolve actual terrain: " + resolved);
        Object local = regions.get("0_-14");
        require(local != null && color.invoke(local, 242, -6699) != null,
                "Negative Z coordinates must resolve the display's saved region");
        ImageIO.write(image, "png", Path.of("expected-xaero-map.png").toFile());
        System.out.println("PASS: " + regions.size() + " real cache files, " + textures + " saved textures, "
                + resolved + "/65536 terrain pixels at the reported display; negative coordinates and complete format parsing.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
