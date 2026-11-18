package com.hooya.stabilizedturret.client;

import com.hooya.stabilizedturret.StabilizedTurret;
import com.hooya.stabilizedturret.content.display.ConnectedDisplayBlockEntity;
import com.hooya.stabilizedturret.registry.ModSounds;
import com.mojang.blaze3d.platform.NativeImage;
import dev.ryanhcode.sable.Sable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jcodec.api.FrameGrab;
import org.jcodec.api.PictureWithMetadata;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.scale.AWTUtil;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.WeakHashMap;

/** Client-side decoder, dynamic texture, and positional soundtrack for the display easter egg. */
@EventBusSubscriber(modid = StabilizedTurret.MOD_ID, value = Dist.CLIENT)
public final class ConnectedDisplayVideoPlayer {
    /** GPU texture size; the cached source is 768x576 and is scaled off-thread. */
    public static final int VIDEO_WIDTH = 512;
    public static final int VIDEO_HEIGHT = 384;
    private static final int SOURCE_FPS = 30;
    private static final int FRAME_STEP = 2;
    private static final int DISPLAY_FPS = SOURCE_FPS / FRAME_STEP;
    private static final int PREBUFFER_SOURCE_FRAMES = 12;
    private static final int FRAME_RING_SIZE = 32;
    private static final long EXPECTED_VIDEO_BYTES = 33_164_052L;
    private static final String EXPECTED_VIDEO_SHA256 =
            "9082b926b2b684e6edc418dff0d5f2c93028a055793d5a6c6fe5d73d8fe55c20";
    private static final URI VIDEO_URI = URI.create(
            "https://aweme.snssdk.com/aweme/v1/play/"
                    + "?video_id=v1e00fgi0000ctj72h7og65qvm92fat0&ratio=360p&line=0");
    private static final long RETRY_DELAY_MILLIS = 60_000L;
    private static final int WORLD_SETTLE_TICKS = 200;
    private static final Map<ConnectedDisplayBlockEntity, Playback> PLAYBACKS =
            new WeakHashMap<>();
    private static volatile CacheState cacheState = CacheState.UNCHECKED;
    private static volatile Path cachedVideo;
    private static volatile long retryAfterMillis;
    private static volatile boolean backgroundIoAllowed = true;
    private static net.minecraft.client.multiplayer.ClientLevel trackedLevel;
    private static int ticksInWorld;
    private static int textureSerial;

    /**
     * Downloads on the main menu. If the player enters a world before it is
     * finished, disk reads/writes pause for ten seconds so initial chunks keep
     * the CPU and storage bandwidth to themselves.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (level == null) {
            trackedLevel = null;
            ticksInWorld = 0;
            // Do not compete with mod/resource loading or the terrain-loading
            // screen. The real title screen is the only pre-world safe window.
            backgroundIoAllowed = minecraft.screen instanceof TitleScreen;
            if (backgroundIoAllowed) ensureCacheStarted();
            return;
        }
        if (level != trackedLevel) {
            trackedLevel = level;
            ticksInWorld = 0;
        }
        if (minecraft.player != null && ticksInWorld < WORLD_SETTLE_TICKS) {
            ticksInWorld++;
        }
        backgroundIoAllowed = ticksInWorld >= WORLD_SETTLE_TICKS;
        if (backgroundIoAllowed) ensureCacheStarted();
    }

    /** Called from the block-entity renderer, therefore all texture work stays on the render thread. */
    public static int textureId(ConnectedDisplayBlockEntity display, float partialTick) {
        if (display == null || display.getLevel() == null
                || !display.isGodModeVideoPlaying()) {
            stop(display);
            return -1;
        }
        Path videoFile = readyVideoFile();
        if (videoFile == null) {
            stop(display);
            return -1;
        }
        long startedAt = display.getGodModeVideoStartedAt();
        Playback playback = PLAYBACKS.get(display);
        if (playback == null || playback.startedAt != startedAt) {
            if (playback != null) playback.close();
            playback = new Playback(display, startedAt, videoFile);
            PLAYBACKS.put(display, playback);
        }
        return playback.textureId();
    }

    /** Registered texture for the block-entity render buffer. */
    public static ResourceLocation textureLocation(ConnectedDisplayBlockEntity display, float partialTick) {
        if (textureId(display, partialTick) <= 0) return null;
        return PLAYBACKS.get(display).location;
    }

    public static void stop(ConnectedDisplayBlockEntity display) {
        if (display == null) return;
        Playback playback = PLAYBACKS.remove(display);
        if (playback != null) playback.close();
    }

    public static Component downloadStatusMessage() {
        return switch (cacheState) {
            case UNCHECKED, DOWNLOADING -> Component.translatable(
                    "screen.create_fire_control.connected_display.video.downloading");
            case FAILED -> Component.translatable(
                    "screen.create_fire_control.connected_display.video.failed");
            case READY -> Component.translatable(
                    "screen.create_fire_control.connected_display.video.preparing");
        };
    }

    /** True only after the persistent file has passed size and SHA-256 validation. */
    public static boolean isVideoReady() {
        Path file = cachedVideo;
        return cacheState == CacheState.READY && file != null
                && Files.isRegularFile(file);
    }

    private static Path readyVideoFile() {
        ensureCacheStarted();
        return isVideoReady() ? cachedVideo : null;
    }

    private static void ensureCacheStarted() {
        CacheState state = cacheState;
        long now = System.currentTimeMillis();
        if (state == CacheState.READY || state == CacheState.DOWNLOADING
                || state == CacheState.FAILED && now < retryAfterMillis) {
            return;
        }
        synchronized (ConnectedDisplayVideoPlayer.class) {
            state = cacheState;
            now = System.currentTimeMillis();
            if (state == CacheState.READY || state == CacheState.DOWNLOADING
                    || state == CacheState.FAILED && now < retryAfterMillis) {
                return;
            }
            cacheState = CacheState.DOWNLOADING;
            Thread downloader = new Thread(
                    ConnectedDisplayVideoPlayer::preparePersistentCache,
                    "Create Fire Control god-mode video download");
            downloader.setDaemon(true);
            downloader.setPriority(Thread.MIN_PRIORITY);
            downloader.start();
        }
    }

    private static void preparePersistentCache() {
        Path target = cachePath();
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.getParent());
            if (validVideo(target)) {
                cachedVideo = target;
                cacheState = CacheState.READY;
                StabilizedTurret.LOGGER.info(
                        "Using cached connected-display video at {}", target);
                return;
            }
            Files.deleteIfExists(partial);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(VIDEO_URI)
                    .timeout(Duration.ofMinutes(10))
                    .header("User-Agent", "Mozilla/5.0 Create-Fire-Control/0.1")
                    .header("Accept", "video/mp4,*/*")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("HTTP " + response.statusCode());
            }
            try (InputStream input = response.body();
                 OutputStream output = Files.newOutputStream(partial,
                         StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[256 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    awaitBackgroundIoWindow();
                    output.write(buffer, 0, read);
                }
            }
            if (!validVideo(partial)) {
                throw new IOException("Downloaded video failed size or SHA-256 validation");
            }
            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            }
            cachedVideo = target;
            cacheState = CacheState.READY;
            StabilizedTurret.LOGGER.info(
                    "Downloaded and cached connected-display video at {}", target);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failDownload(partial, exception);
        } catch (Exception exception) {
            failDownload(partial, exception);
        }
    }

    private static void failDownload(Path partial, Exception exception) {
        try {
            Files.deleteIfExists(partial);
        } catch (IOException cleanupError) {
            exception.addSuppressed(cleanupError);
        }
        cachedVideo = null;
        retryAfterMillis = System.currentTimeMillis() + RETRY_DELAY_MILLIS;
        cacheState = CacheState.FAILED;
        StabilizedTurret.LOGGER.warn(
                "Could not download connected-display video; retrying later", exception);
    }

    private static Path cachePath() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config")
                .resolve(StabilizedTurret.MOD_ID)
                .resolve("cache")
                .resolve("god_mode-9082b926.mp4");
    }

    private static boolean validVideo(Path path) throws Exception {
        if (!Files.isRegularFile(path) || Files.size(path) != EXPECTED_VIDEO_BYTES) {
            return false;
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ)) {
            byte[] buffer = new byte[1024 * 1024];
            while (true) {
                awaitBackgroundIoWindow();
                int read = input.read(buffer);
                if (read < 0) break;
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return EXPECTED_VIDEO_SHA256.equals(
                HexFormat.of().formatHex(digest.digest()));
    }

    private static void awaitBackgroundIoWindow() throws InterruptedException {
        while (!backgroundIoAllowed) Thread.sleep(50L);
    }

    private static final class Playback implements AutoCloseable {
        private final ConnectedDisplayBlockEntity display;
        private final long startedAt;
        private final DynamicTexture texture;
        private final ResourceLocation location;
        private final Decoder decoder;
        private DisplayVideoSound sound;
        private int uploadedFrame = -1;
        private long playbackStartedNanos = Long.MIN_VALUE;
        private boolean closed;

        private Playback(ConnectedDisplayBlockEntity display, long startedAt,
                         Path videoFile) {
            this.display = display;
            this.startedAt = startedAt;
            texture = new DynamicTexture(VIDEO_WIDTH, VIDEO_HEIGHT, false);
            location = Minecraft.getInstance().getTextureManager().register(
                    "create_fire_control_god_mode_" + textureSerial++, texture);
            decoder = new Decoder(videoFile);
            decoder.request(PREBUFFER_SOURCE_FRAMES);
            decoder.start();
        }

        private int textureId() {
            if (closed) return -1;
            NativeImage target = texture.getPixels();
            if (target == null) return -1;
            if (playbackStartedNanos == Long.MIN_VALUE) {
                int copied = decoder.copyFrameAtOrBefore(0, uploadedFrame, target);
                if (copied > uploadedFrame) {
                    texture.upload();
                    uploadedFrame = copied;
                }
                if (decoder.decodedThrough() < PREBUFFER_SOURCE_FRAMES) {
                    return uploadedFrame >= 0 ? texture.getId() : -1;
                }
                playbackStartedNanos = System.nanoTime();
                sound = new DisplayVideoSound(display, startedAt);
                Minecraft.getInstance().getSoundManager().play(sound);
            }
            double elapsedSeconds = Math.max(0.0,
                    (System.nanoTime() - playbackStartedNanos) / 1_000_000_000.0);
            int displayFrame = (int) Math.floor(elapsedSeconds * DISPLAY_FPS);
            int wantedSourceFrame = displayFrame * FRAME_STEP;
            decoder.request(wantedSourceFrame + PREBUFFER_SOURCE_FRAMES);
            int copied = decoder.copyFrameAtOrBefore(
                    displayFrame, uploadedFrame, target);
            if (copied > uploadedFrame) {
                texture.upload();
                uploadedFrame = copied;
            }
            return uploadedFrame >= 0 ? texture.getId() : -1;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            decoder.close();
            if (sound != null) sound.halt();
            texture.close();
        }
    }

    private static final class Decoder implements AutoCloseable, Runnable {
        private volatile boolean running = true;
        private volatile int wantedFrame;
        private volatile int decodedThrough = -1;
        private volatile SeekableByteChannel channel;
        private final NativeDecodedFrame[] frames =
                new NativeDecodedFrame[FRAME_RING_SIZE];
        private final Path videoFile;
        private final Thread thread = new Thread(this,
                "Create Fire Control connected-display video decoder");

        private Decoder(Path videoFile) {
            this.videoFile = videoFile;
        }

        private void start() {
            thread.setDaemon(true);
            thread.start();
        }

        private void request(int frame) {
            wantedFrame = Math.max(wantedFrame, frame);
        }

        private int decodedThrough() {
            return decodedThrough;
        }

        /** Copies one prepared frame with a native memcpy while holding the ring lock. */
        private synchronized int copyFrameAtOrBefore(
                int requestedDisplayFrame, int minimumExclusive, NativeImage target
        ) {
            NativeDecodedFrame best = null;
            for (NativeDecodedFrame frame : frames) {
                if (frame != null && frame.displayFrame <= requestedDisplayFrame
                        && (best == null || frame.displayFrame > best.displayFrame)) {
                    best = frame;
                }
            }
            if (best == null || best.displayFrame <= minimumExclusive) {
                return minimumExclusive;
            }
            target.copyFrom(best.image);
            return best.displayFrame;
        }

        private synchronized void publish(
                int displayFrame, double presentationTime, NativeImage image
        ) {
            if (!running) {
                image.close();
                return;
            }
            int slot = Math.floorMod(displayFrame, frames.length);
            NativeDecodedFrame previous = frames[slot];
            if (previous != null && previous.displayFrame > displayFrame) {
                image.close();
                return;
            }
            frames[slot] = new NativeDecodedFrame(
                    displayFrame, presentationTime, image);
            if (previous != null) previous.image.close();
        }

        @Override
        public void run() {
            try {
                channel = NIOUtils.readableChannel(videoFile.toFile());
                FrameGrab grab = FrameGrab.createFrameGrab(channel);
                int index = 0;
                while (running) {
                    if (index > wantedFrame) {
                        Thread.sleep(2L);
                        continue;
                    }
                    PictureWithMetadata frame = grab.getNativeFrameWithMetadata();
                    if (frame == null || frame.getPicture() == null) return;
                    double timestamp = frame.getTimestamp();
                    int displayFrame = (int) Math.round(timestamp * DISPLAY_FPS);
                    double displayTime = displayFrame / (double) DISPLAY_FPS;
                    // The CDN source contains B-frames and arrives in decode order.
                    // Select frames by PTS on the 15 FPS presentation grid instead
                    // of taking every second decoded packet.
                    if (Math.abs(timestamp - displayTime)
                            <= 1.0 / (SOURCE_FPS * 3.0)) {
                        publish(displayFrame, timestamp, toNativeFrame(
                                scaleFrame(AWTUtil.toBufferedImage(frame.getPicture()))));
                    }
                    decodedThrough = index;
                    index++;
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception exception) {
                if (running) {
                    StabilizedTurret.LOGGER.error(
                            "Could not decode connected-display god-mode video", exception);
                }
            } finally {
                SeekableByteChannel open = channel;
                channel = null;
                NIOUtils.closeQuietly(open);
            }
        }

        @Override
        public void close() {
            running = false;
            thread.interrupt();
            NIOUtils.closeQuietly(channel);
            closeFrames();
        }

        private synchronized void closeFrames() {
            for (int index = 0; index < frames.length; index++) {
                NativeDecodedFrame frame = frames[index];
                frames[index] = null;
                if (frame != null) frame.image.close();
            }
        }

        private static BufferedImage scaleFrame(BufferedImage source) {
            if (source.getWidth() == VIDEO_WIDTH
                    && source.getHeight() == VIDEO_HEIGHT) {
                return source;
            }
            BufferedImage scaled = new BufferedImage(
                    VIDEO_WIDTH, VIDEO_HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_SPEED);
                graphics.drawImage(source, 0, 0, VIDEO_WIDTH, VIDEO_HEIGHT, null);
            } finally {
                graphics.dispose();
            }
            return scaled;
        }

        private static NativeImage toNativeFrame(BufferedImage frame) {
            NativeImage image = new NativeImage(
                    VIDEO_WIDTH, VIDEO_HEIGHT, false);
            int[] argb = frame.getRGB(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT,
                    null, 0, VIDEO_WIDTH);
            for (int y = 0; y < VIDEO_HEIGHT; y++) {
                int row = y * VIDEO_WIDTH;
                for (int x = 0; x < VIDEO_WIDTH; x++) {
                    int color = argb[row + x];
                    int red = color >> 16 & 255;
                    int green = color >> 8 & 255;
                    int blue = color & 255;
                    image.setPixelRGBA(x, y,
                            FastColor.ABGR32.color(255, blue, green, red));
                }
            }
            return image;
        }
    }

    private static final class DisplayVideoSound extends AbstractTickableSoundInstance {
        private final WeakReference<ConnectedDisplayBlockEntity> displayReference;
        private final long startedAt;

        private DisplayVideoSound(ConnectedDisplayBlockEntity display, long startedAt) {
            super(ModSounds.CONNECTED_DISPLAY_GOD_MODE.get(),
                    net.minecraft.sounds.SoundSource.BLOCKS,
                    SoundInstance.createUnseededRandom());
            displayReference = new WeakReference<>(display);
            this.startedAt = startedAt;
            attenuation = SoundInstance.Attenuation.LINEAR;
            relative = false;
            looping = false;
            delay = 0;
            volume = 1.0F;
            pitch = 1.0F;
            updateLocation(display);
        }

        @Override
        public void tick() {
            ConnectedDisplayBlockEntity display = displayReference.get();
            if (display == null || display.isRemoved() || display.getLevel() == null
                    || !display.isGodModeVideoPlaying()
                    || display.getGodModeVideoStartedAt() != startedAt) {
                stop();
                return;
            }
            updateLocation(display);
        }

        private void updateLocation(ConnectedDisplayBlockEntity display) {
            Vec3 position = Sable.HELPER.projectOutOfSubLevel(
                    display.getLevel(), Vec3.atCenterOf(display.getBlockPos()));
            x = position.x;
            y = position.y;
            z = position.z;
        }

        private void halt() {
            stop();
        }
    }

    private record NativeDecodedFrame(
            int displayFrame, double presentationTime, NativeImage image
    ) {}

    private enum CacheState {
        UNCHECKED,
        DOWNLOADING,
        READY,
        FAILED
    }

    private ConnectedDisplayVideoPlayer() {}
}
