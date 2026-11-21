package cn.autoforged.module_shop.client.media;

import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.config.ShopTemplateStore;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import com.mojang.logging.LogUtils;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Codec;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.IPoint;
import io.github.humbleui.types.Rect;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWDropCallback;
import org.lwjgl.glfw.GLFWDropCallbackI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.slf4j.Logger;

/**
 * 客户端选图与预处理上传管线：
 * <ul>
 *     <li>支持非全屏置顶 {@link JFileChooser}（全屏或失败时友好引导拖入窗口 / 从传图目录选取）；</li>
 *     <li>支持 GLFW 窗口文件拖放（链式挂接并在界面关闭时还原原回调）；</li>
 *     <li>上传前用 Skija {@link Codec}/{@link Surface} 安全验尺寸、等比缩放并压缩编码为 PNG（超限自动降采样/JPEG 兜底），
 *         再经分片包 {@code admin_media_upload} 发往服务端。</li>
 * </ul>
 */
public final class ClientImageUploader {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final long MAX_RAW_FILE_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_DECODE_SIDE = 4096;
    private static final int TARGET_MAX_UPLOAD_BYTES = 950 * 1024;
    private static final Set<String> SUPPORTED_EXT = Set.of("png", "jpg", "jpeg", "webp", "gif", "bmp");
    private static final AtomicBoolean PICKER_OPEN = new AtomicBoolean(false);

    public interface UploadCallback {
        void onUploaded(String mediaId, int width, int height);

        default void onFailed(String reason) {
        }
    }

    /** 窗口文件拖放挂钩句柄，在界面 {@code removed()} 中调用 {@link #uninstall()}。 */
    public static final class DropHandle {
        private final long window;
        private final GLFWDropCallback installed;
        private final @Nullable GLFWDropCallback previous;
        private boolean active = true;

        private DropHandle(long window, GLFWDropCallback installed, @Nullable GLFWDropCallback previous) {
            this.window = window;
            this.installed = installed;
            this.previous = previous;
        }

        public void uninstall() {
            if (!this.active) {
                return;
            }
            this.active = false;
            try {
                GLFW.glfwSetDropCallback(this.window, this.previous);
            } catch (Throwable ignored) {
            }
            try {
                this.installed.free();
            } catch (Throwable ignored) {
            }
        }
    }

    private ClientImageUploader() {
    }

    public static Path inboxDir() {
        Minecraft mc = Minecraft.getInstance();
        Path gameDir = mc == null || mc.gameDirectory == null ? Path.of(".") : mc.gameDirectory.toPath();
        Path dir = gameDir.resolve("config").resolve(ShopTemplateStore.DIR_NAME).resolve("upload_inbox");
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
        }
        return dir;
    }

    public static List<Path> listInboxImages() {
        Path dir = inboxDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(ClientImageUploader::isImageFile)
                    .sorted(Comparator.comparingLong(ClientImageUploader::lastModifiedSafe).reversed())
                    .limit(64)
                    .forEach(files::add);
        } catch (Exception ignored) {
        }
        return files;
    }

    public static void openInboxDir() {
        try {
            Util.getPlatform().openFile(inboxDir().toFile());
        } catch (Throwable t) {
            LOGGER.warn("[module_shop] 无法打开传图目录", t);
        }
    }

    public static boolean isImageFile(@Nullable Path path) {
        if (path == null) {
            return false;
        }
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot + 1 < name.length() && SUPPORTED_EXT.contains(name.substring(dot + 1));
    }

    /** 链式安装窗口拖放回调，接收首个有效图片文件；非图片文件回传原回调。 */
    public static @Nullable DropHandle installDropCallback(Consumer<Path> onImageDropped) {
        if (onImageDropped == null) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            return null;
        }
        long window = mc.getWindow().getWindow();
        if (window == 0L) {
            return null;
        }
        final GLFWDropCallback[] prevRef = new GLFWDropCallback[1];
        GLFWDropCallbackI handler = (win, count, names) -> {
            boolean consumed = false;
            try {
                PointerBuffer buf = MemoryUtil.memPointerBuffer(names, count);
                for (int i = 0; i < count; i++) {
                    String p = MemoryUtil.memUTF8(buf.get(i));
                    Path path = Path.of(p);
                    if (Files.isRegularFile(path) && isImageFile(path)) {
                        consumed = true;
                        onImageDropped.accept(path);
                        break;
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[module_shop] 处理拖入文件失败", t);
            }
            if (!consumed && prevRef[0] != null) {
                prevRef[0].invoke(win, count, names);
            }
        };
        GLFWDropCallback callback = GLFWDropCallback.create(handler);
        prevRef[0] = GLFW.glfwSetDropCallback(window, callback);
        return new DropHandle(window, callback, prevRef[0]);
    }

    /** 弹出本地文件选择器选图并上传；全屏时友好提示改用拖入窗口或传图目录。 */
    public static void pickAndUpload(String purpose, int maxSidePx, UploadCallback callback) {
        UploadCallback cb = safeCallback(callback);
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getWindow() != null && mc.getWindow().isFullscreen()) {
            cb.onFailed(ClientHomeCurrencyState.tr("gui.module_shop.media.fullscreen_hint",
                    "当前为全屏模式，请直接拖入图片到窗口或点击「打开传图目录」从收图箱选取"));
            return;
        }
        if (!PICKER_OPEN.compareAndSet(false, true)) {
            cb.onFailed(ClientHomeCurrencyState.tr("gui.module_shop.media.picker_busy", "文件选择窗口已打开"));
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Path picked = openSystemImagePicker();
                if (picked != null) {
                    uploadFile(picked, purpose, maxSidePx, cb);
                }
            } catch (Throwable err) {
                LOGGER.warn("[module_shop] 系统文件弹窗失败", err);
                runOnClient(() -> cb.onFailed(ClientHomeCurrencyState.tr("gui.module_shop.media.picker_fail",
                        "无法打开系统文件选择框，请直接将图片拖入游戏窗口或放入传图目录")));
            } finally {
                PICKER_OPEN.set(false);
            }
        }, "module_shop-file-picker");
        t.setDaemon(true);
        t.start();
    }

    /** 从磁盘读取指定图片文件，压缩预处理后上传服务端。 */
    public static void uploadFile(Path path, String purpose, int maxSidePx, UploadCallback callback) {
        UploadCallback cb = safeCallback(callback);
        if (path == null || !Files.isRegularFile(path)) {
            runOnClient(() -> cb.onFailed(ClientHomeCurrencyState.tr("gui.module_shop.media.file_missing", "找不到指定的图片文件")));
            return;
        }
        Thread t = new Thread(() -> {
            try {
                long size = Files.size(path);
                if (size <= 0L) {
                    runOnClient(() -> cb.onFailed(ClientHomeCurrencyState.tr("msg.module_shop.media_bad_image",
                            "图片格式不支持或尺寸超限（支持 PNG/WebP/JPG，最大 1MB / 2048px）")));
                    return;
                }
                if (size > MAX_RAW_FILE_BYTES) {
                    runOnClient(() -> cb.onFailed(ClientHomeCurrencyState.tr("gui.module_shop.media.raw_too_large",
                            "原图文件过大（最大支持 16MB），请先适当裁剪后重试")));
                    return;
                }
                byte[] raw = Files.readAllBytes(path);
                uploadBytes(raw, purpose, maxSidePx, cb);
            } catch (Exception ex) {
                LOGGER.warn("[module_shop] 读取本地图片失败: {}", path, ex);
                runOnClient(() -> cb.onFailed(ClientHomeCurrencyState.tr("msg.module_shop.media_bad_image",
                        "图片格式不支持或尺寸超限（支持 PNG/WebP/JPG，最大 1MB / 2048px）")));
            }
        }, "module_shop-image-read");
        t.setDaemon(true);
        t.start();
    }

    public static void uploadBytes(byte[] rawBytes, String purpose, int maxSidePx, UploadCallback callback) {
        UploadCallback cb = safeCallback(callback);
        if (rawBytes == null || rawBytes.length == 0 || rawBytes.length > MAX_RAW_FILE_BYTES) {
            runOnClient(() -> cb.onFailed(ClientHomeCurrencyState.tr("msg.module_shop.media_bad_image",
                    "图片格式不支持或尺寸超限（支持 PNG/WebP/JPG，最大 1MB / 2048px）")));
            return;
        }
        runOnClient(() -> processAndSendOnRenderThread(rawBytes, purpose, maxSidePx, cb));
    }

    private static void processAndSendOnRenderThread(byte[] rawBytes, String purpose, int maxSidePx, UploadCallback cb) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            cb.onFailed(ClientHomeCurrencyState.tr("gui.module_shop.media.not_connected", "未连接到服务器"));
            return;
        }
        int capSide = Math.max(32, Math.min(2048, maxSidePx));
        try (Data rawData = Data.makeFromBytes(rawBytes);
             Codec codec = Codec.makeFromData(rawData)) {
            if (codec == null) {
                cb.onFailed(ClientHomeCurrencyState.tr("msg.module_shop.media_bad_image",
                        "图片格式不支持或尺寸超限（支持 PNG/WebP/JPG，最大 1MB / 2048px）"));
                return;
            }
            IPoint size = codec.getSize();
            int srcW = size.getX();
            int srcH = size.getY();
            if (srcW <= 0 || srcH <= 0) {
                cb.onFailed(ClientHomeCurrencyState.tr("msg.module_shop.media_bad_image",
                        "图片格式不支持或尺寸超限（支持 PNG/WebP/JPG，最大 1MB / 2048px）"));
                return;
            }
            if (srcW > MAX_DECODE_SIDE || srcH > MAX_DECODE_SIDE) {
                cb.onFailed(ClientHomeCurrencyState.tr("gui.module_shop.media.res_too_large",
                        "原图分辨率过大（最大支持 4096×4096）"));
                return;
            }
            try (Image src = Image.makeFromEncoded(rawBytes)) {
                if (src == null) {
                    cb.onFailed(ClientHomeCurrencyState.tr("msg.module_shop.media_bad_image",
                            "图片格式不支持或尺寸超限（支持 PNG/WebP/JPG，最大 1MB / 2048px）"));
                    return;
                }
                int[] dims = fitInside(srcW, srcH, capSide);
                int dstW = dims[0];
                int dstH = dims[1];
                byte[] encoded = encodeScaledPng(src, srcW, srcH, dstW, dstH);

                // 若大图 PNG 超过 950 KiB，逐档缩边并必要时尝试 JPEG（非图标优先保持透明）
                while ((encoded == null || encoded.length > TARGET_MAX_UPLOAD_BYTES) && Math.max(dstW, dstH) > 256) {
                    int nextSide = Math.max(256, Math.round(Math.max(dstW, dstH) * 0.78F));
                    int[] nextDims = fitInside(srcW, srcH, nextSide);
                    dstW = nextDims[0];
                    dstH = nextDims[1];
                    encoded = encodeScaledPng(src, srcW, srcH, dstW, dstH);
                }
                if ((encoded == null || encoded.length > TARGET_MAX_UPLOAD_BYTES) && !"brand_icon".equals(purpose)) {
                    byte[] jpg = encodeScaledJpeg(src, srcW, srcH, dstW, dstH, 86);
                    if (jpg != null && jpg.length <= TARGET_MAX_UPLOAD_BYTES) {
                        encoded = jpg;
                    }
                }
                if (encoded == null || encoded.length == 0 || encoded.length > TARGET_MAX_UPLOAD_BYTES) {
                    cb.onFailed(ClientHomeCurrencyState.tr("msg.module_shop.media_too_large",
                            "压缩后图片仍超过 1MB 上限，请换小一点的图片"));
                    return;
                }

                String requestId = UUID.randomUUID().toString();
                ClientMediaCache.registerPendingUpload(requestId, encoded, dstW, dstH, cb);

                CompoundTag req = new CompoundTag();
                req.putString("action", "admin_media_upload");
                req.putString("purpose", purpose == null ? "announcement" : purpose);
                req.putString("requestId", requestId);
                req.putByteArray("bytes", encoded);
                ServerboundShopActionPayload.send(req);
            }
        } catch (Throwable t) {
            LOGGER.warn("[module_shop] Skija 预处理上传图片失败", t);
            cb.onFailed(ClientHomeCurrencyState.tr("msg.module_shop.media_bad_image",
                    "图片格式不支持或尺寸超限（支持 PNG/WebP/JPG，最大 1MB / 2048px）"));
        }
    }

    private static @Nullable byte[] encodeScaledPng(Image src, int srcW, int srcH, int dstW, int dstH) {
        return encodeScaled(src, srcW, srcH, dstW, dstH, EncodedImageFormat.PNG, 100);
    }

    private static @Nullable byte[] encodeScaledJpeg(Image src, int srcW, int srcH, int dstW, int dstH, int quality) {
        return encodeScaled(src, srcW, srcH, dstW, dstH, EncodedImageFormat.JPEG, quality);
    }

    private static @Nullable byte[] encodeScaled(Image src, int srcW, int srcH, int dstW, int dstH,
            EncodedImageFormat format, int quality) {
        try (Surface surface = Surface.makeRasterN32Premul(dstW, dstH);
             Paint paint = new Paint().setAntiAlias(true)) {
            Canvas canvas = surface.getCanvas();
            canvas.clear(0x00000000);
            canvas.drawImageRect(src,
                    Rect.makeXYWH(0, 0, srcW, srcH),
                    Rect.makeXYWH(0, 0, dstW, dstH),
                    SamplingMode.LINEAR, paint, false);
            try (Image snap = surface.makeImageSnapshot();
                 Data out = snap.encodeToData(format, quality)) {
                return out == null ? null : out.getBytes();
            }
        }
    }

    private static int[] fitInside(int w, int h, int maxSide) {
        if (w <= maxSide && h <= maxSide) {
            return new int[]{w, h};
        }
        double scale = Math.min((double) maxSide / w, (double) maxSide / h);
        return new int[]{Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale))};
    }

    private static @Nullable Path openSystemImagePicker() throws Exception {
        if (!GraphicsEnvironment.isHeadless()) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Throwable ignored) {
            }
            JFrame owner = new JFrame();
            try {
                owner.setUndecorated(true);
                owner.setAlwaysOnTop(true);
                owner.setType(Window.Type.UTILITY);
                owner.setSize(0, 0);
                owner.setLocationRelativeTo(null);
                owner.setVisible(true);
                owner.toFront();
                owner.requestFocus();

                JFileChooser chooser = new JFileChooser(inboxDir().toFile());
                chooser.setDialogTitle(ClientHomeCurrencyState.tr("gui.module_shop.media.pick_title", "选择图片文件"));
                chooser.setAcceptAllFileFilterUsed(true);
                chooser.setFileFilter(new FileNameExtensionFilter(
                        "Images (*.png, *.jpg, *.jpeg, *.webp, *.gif, *.bmp)",
                        "png", "jpg", "jpeg", "webp", "gif", "bmp"));
                int res = chooser.showOpenDialog(owner);
                if (res == JFileChooser.APPROVE_OPTION) {
                    File f = chooser.getSelectedFile();
                    return f == null ? null : f.toPath();
                }
                return null;
            } finally {
                owner.dispose();
            }
        }
        String picked = TinyFileDialogs.tinyfd_openFileDialog(
                ClientHomeCurrencyState.tr("gui.module_shop.media.pick_title", "选择图片文件"),
                inboxDir().toAbsolutePath() + File.separator,
                null,
                "Images (PNG/JPG/WEBP)",
                false);
        return picked == null || picked.isBlank() ? null : Path.of(picked);
    }

    private static long lastModifiedSafe(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static UploadCallback safeCallback(@Nullable UploadCallback callback) {
        return callback != null ? callback : (id, w, h) -> {};
    }

    private static void runOnClient(Runnable task) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        if (mc.isSameThread()) {
            task.run();
        } else {
            mc.execute(task);
        }
    }
}
