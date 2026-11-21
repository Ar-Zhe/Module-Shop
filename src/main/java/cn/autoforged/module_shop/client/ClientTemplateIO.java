package cn.autoforged.module_shop.client;

import cn.autoforged.module_shop.client.screen.ConfigExportScreen;
import cn.autoforged.module_shop.client.screen.ConfigManagerScreen;
import cn.autoforged.module_shop.client.screen.TemplatePreviewScreen;
import cn.autoforged.module_shop.config.ShopTemplate;
import cn.autoforged.module_shop.config.ShopTemplateStore;
import cn.autoforged.module_shop.config.ShopTemplateStore.TemplateFormat;
import cn.autoforged.module_shop.config.TemplateTransferManager;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 客户端侧的模板文件读写、单人旁路优化与联机双向分片流式传输（对应服务端 {@link TemplateTransferManager}）。
 */
public final class ClientTemplateIO {
    private static final int CHUNKS_PER_TICK = 2;

    private static final class ActiveUpload {
        final String transferId;
        final String fileName;
        final byte[] rawBytes;
        final int totalChunks;
        int nextSendIndex;
        int ackedChunks;

        ActiveUpload(String transferId, String fileName, byte[] rawBytes, int chunkSize) {
            this.transferId = transferId;
            this.fileName = fileName;
            this.rawBytes = rawBytes;
            this.totalChunks = Math.max(1, (rawBytes.length + chunkSize - 1) / chunkSize);
        }
    }

    private static final class ActiveDownload {
        final String transferId;
        final String fileName;
        final boolean isDefault;
        final String purpose;
        final int totalChunks;
        final long declaredBytes;
        final ByteArrayOutputStream buffer;
        int nextIndex;

        ActiveDownload(String transferId, String fileName, boolean isDefault, String purpose,
                       int totalChunks, long declaredBytes) {
            this.transferId = transferId;
            this.fileName = fileName;
            this.isDefault = isDefault;
            this.purpose = purpose;
            this.totalChunks = totalChunks;
            this.declaredBytes = declaredBytes;
            this.buffer = new ByteArrayOutputStream((int) Math.min(Math.max(declaredBytes, 4096L), 2L * 1024L * 1024L));
        }
    }

    private static @Nullable ActiveUpload activeUpload;
    private static @Nullable ActiveDownload activeDownload;
    private static @Nullable String cancelledDownloadId;
    private static String lastErrorMessage = "";

    private ClientTemplateIO() {
    }

    public static Path configDir() {
        Minecraft minecraft = Minecraft.getInstance();
        Path gameDir = minecraft == null ? Path.of(".") : minecraft.gameDirectory.toPath();
        return gameDir.resolve("config");
    }

    /** 扁平化配置根目录 `config/moduleshop/`。 */
    public static Path templateDir() {
        return ShopTemplateStore.dir(configDir());
    }

    public static Path defaultFile() {
        Path existing = ShopTemplateStore.resolveExistingDefaultFile(configDir());
        return existing != null ? existing : ShopTemplateStore.defaultFile(configDir());
    }

    public static Path exportsDir() {
        return ShopTemplateStore.exportsDir(configDir());
    }

    /** 客户端当前是否连接本地整合服务端（未收到 `cfg_list` 前的本地预设判断）。 */
    public static boolean isSingleplayer() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.hasSingleplayerServer();
    }

    /** 创建本地配置文件夹并在系统文件管理器中打开。 */
    public static boolean openTemplateDir() {
        try {
            Path dir = templateDir();
            Files.createDirectories(dir);
            Util.getPlatform().openFile(dir.toFile());
            return true;
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    /** 向服务端请求配置列表与运行模式（单人 / 联机专用服）。 */
    public static void requestServerList() {
        CompoundTag tag = new CompoundTag();
        tag.putString("action", "cfg_list");
        ServerboundShopActionPayload.send(tag);
    }

    /** 读取默认模板（支持 `.mshop` / `.json.gz` / `.json`）。 */
    public static ShopTemplateStore.ReadResult readDefault() {
        return ShopTemplateStore.read(defaultFile());
    }

    /** 读取一个模板文件。 */
    public static ShopTemplateStore.ReadResult read(Path file) {
        return ShopTemplateStore.read(file);
    }

    /** 列出可导入的本地模板（`shop_config.*` 置顶，扫描 `.mshop` / `.json.gz` / `.json`）。 */
    public static List<Path> listImportable() {
        return ShopTemplateStore.listTemplates(configDir());
    }

    /** 计算本地模板列表的轻量指纹（供界面轮询自动刷新）。 */
    public static String localFingerprint() {
        List<Path> files = listImportable();
        if (files.isEmpty()) {
            return "";
        }
        Path cfg = configDir();
        StringBuilder sb = new StringBuilder();
        for (Path file : files) {
            sb.append(ShopTemplateStore.relativeKey(cfg, file)).append(':');
            try {
                sb.append(Files.getLastModifiedTime(file).toMillis()).append(':')
                        .append(Files.size(file));
            } catch (IOException ignored) {
                sb.append("0:0");
            }
            sb.append('|');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 传输状态与分片流控

    public static boolean hasActiveTransfer() {
        return activeUpload != null || activeDownload != null;
    }

    public static float transferProgress() {
        ActiveUpload up = activeUpload;
        if (up != null && up.totalChunks > 0) {
            int done = Math.max(up.ackedChunks, up.nextSendIndex);
            return Math.max(0f, Math.min(1f, (float) done / (float) up.totalChunks));
        }
        ActiveDownload down = activeDownload;
        if (down != null && down.totalChunks > 0) {
            return Math.max(0f, Math.min(1f, (float) down.nextIndex / (float) down.totalChunks));
        }
        return 0f;
    }

    public static String transferStatusText() {
        ActiveUpload up = activeUpload;
        if (up != null) {
            int pct = Math.round(transferProgress() * 100f);
            return Component.translatableWithFallback(
                    "msg.module_shop.cfg_transfer_progress",
                    "正在上传 %s（%s/%s 分片 · %s%%）",
                    up.fileName, up.nextSendIndex, up.totalChunks, pct).getString();
        }
        ActiveDownload down = activeDownload;
        if (down != null) {
            int pct = Math.round(transferProgress() * 100f);
            return Component.translatableWithFallback(
                    "msg.module_shop.cfg_transfer_progress",
                    "正在下载 %s（%s/%s 分片 · %s%%）",
                    down.fileName, down.nextIndex, down.totalChunks, pct).getString();
        }
        return "";
    }

    public static String lastErrorMessage() {
        return lastErrorMessage;
    }

    public static void clearLastError() {
        lastErrorMessage = "";
    }

    /** 取消当前进行中的上传或下载。 */
    public static void cancelTransfer() {
        ActiveUpload up = activeUpload;
        activeUpload = null;
        if (up != null) {
            CompoundTag cancel = new CompoundTag();
            cancel.putString("action", "cfg_upload_cancel");
            cancel.putString("transferId", up.transferId);
            ServerboundShopActionPayload.send(cancel);
        }
        ActiveDownload down = activeDownload;
        activeDownload = null;
        if (down != null) {
            cancelledDownloadId = down.transferId;
        }
    }

    /** 发起本地文件上传并请求服务端预览；若单人模式且文件就在配置目录，直接零拷贝走服务端直读。 */
    public static boolean startPreviewForLocalFile(Path file, boolean serverDedicated) {
        lastErrorMessage = "";
        if (file == null || !Files.isRegularFile(file)) {
            setErrorAndNotify("missing", "msg.module_shop.cfg_missing", "找不到指定的模板文件。", "");
            return false;
        }
        try {
            long size = Files.size(file);
            if (size <= 0L) {
                setErrorAndNotify("empty", "msg.module_shop.cfg_missing", "模板文件为空，无法导入。", "");
                return false;
            }
            if (size > ShopTemplateStore.MAX_FILE_BYTES) {
                setErrorAndNotify("too_large", "msg.module_shop.cfg_too_large",
                        "模板文件超过 32 MiB 上限，已拒绝读取。", formatSize(size));
                return false;
            }
        } catch (IOException ex) {
            setErrorAndNotify("io_error", "msg.module_shop.cfg_bad_file", "读取本地文件属性失败。", ex.toString());
            return false;
        }

        // 单人模式直通旁路：若文件就在本地 config/moduleshop/ 且被安全键解析，零网络字节由服务端直接读取
        if (!serverDedicated) {
            String relKey = ShopTemplateStore.relativeKey(configDir(), file);
            if (ShopTemplateStore.resolveSafeServerFile(configDir(), relKey) != null) {
                CompoundTag req = new CompoundTag();
                req.putString("action", "cfg_server_preview");
                req.putString("key", relKey);
                ServerboundShopActionPayload.send(req);
                return true;
            }
        }

        return startUploadBytes(file);
    }

    private static boolean startUploadBytes(Path file) {
        cancelTransfer();
        byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException | RuntimeException ex) {
            setErrorAndNotify("io_error", "msg.module_shop.cfg_bad_file", "读取本地模板文件失败。", ex.toString());
            return false;
        }
        if (raw.length == 0) {
            setErrorAndNotify("empty", "msg.module_shop.cfg_missing", "模板文件为空。", "");
            return false;
        }
        if (raw.length > ShopTemplateStore.MAX_FILE_BYTES) {
            setErrorAndNotify("too_large", "msg.module_shop.cfg_too_large",
                    "模板文件超过 32 MiB 上限。", formatSize(raw.length));
            return false;
        }

        // 快速魔数校验（避免把明显损坏的压缩包发上服务端浪费带宽）
        String name = file.getFileName() == null ? "template.mshop" : file.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mshop") && !hasZipMagic(raw)) {
            setErrorAndNotify("corrupt_archive", "msg.module_shop.cfg_corrupt_archive",
                    "该 .mshop 压缩包头部损坏，无法识别为 ZIP 容器。", "");
            return false;
        }
        if (lower.endsWith(".json.gz") && !hasGzipMagic(raw)) {
            setErrorAndNotify("corrupt_archive", "msg.module_shop.cfg_corrupt_archive",
                    "该 .json.gz 文件头部损坏，无法识别为 GZip 压缩流。", "");
            return false;
        }

        String transferId = UUID.randomUUID().toString();
        ActiveUpload up = new ActiveUpload(transferId, name, raw, TemplateTransferManager.TRANSFER_CHUNK_BYTES);
        activeUpload = up;

        int initialBurst = up.totalChunks <= 4 ? up.totalChunks : CHUNKS_PER_TICK;
        sendNextUploadChunks(up, initialBurst);
        return true;
    }

    /** 由打开中的界面在 {@code tick()} 驱动大文件后续分片平滑上传。 */
    public static void tick() {
        ActiveUpload up = activeUpload;
        if (up != null && up.nextSendIndex < up.totalChunks) {
            sendNextUploadChunks(up, CHUNKS_PER_TICK);
        }
    }

    private static void sendNextUploadChunks(ActiveUpload up, int count) {
        int sent = 0;
        while (up == activeUpload && up.nextSendIndex < up.totalChunks && sent < count) {
            int idx = up.nextSendIndex;
            int from = idx * TemplateTransferManager.TRANSFER_CHUNK_BYTES;
            int to = Math.min(up.rawBytes.length, from + TemplateTransferManager.TRANSFER_CHUNK_BYTES);
            byte[] slice = (from == 0 && to == up.rawBytes.length)
                    ? up.rawBytes
                    : Arrays.copyOfRange(up.rawBytes, from, to);

            CompoundTag req = new CompoundTag();
            req.putString("action", "cfg_upload_chunk");
            req.putString("transferId", up.transferId);
            req.putInt("index", idx);
            req.putInt("total", up.totalChunks);
            req.putLong("totalBytes", up.rawBytes.length);
            req.putString("fileName", up.fileName);
            req.putByteArray("bytes", slice);
            ServerboundShopActionPayload.send(req);

            up.nextSendIndex++;
            sent++;
        }
    }

    // ------------------------------------------------------------------ 服务端下行包统一分发

    /** 由 {@link ClientPayloadHandler} 统一投递全部 {@code cfg_*} 下行包。 */
    public static boolean handleServerPayload(String kind, CompoundTag tag) {
        if (kind == null || !kind.startsWith("cfg_")) {
            return false;
        }
        switch (kind) {
            case "cfg_list" -> onServerList(tag);
            case "cfg_preview" -> {
                activeUpload = null;
                showPreview(tag);
            }
            case "cfg_upload_ack" -> onUploadAck(tag);
            case "cfg_download_chunk" -> onDownloadChunk(tag);
            case "cfg_export_done" -> onExportDone(tag);
            case "cfg_transfer_error" -> onTransferError(tag);
            case "cfg_applied_done" -> onAppliedDone(tag);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static void onUploadAck(CompoundTag tag) {
        ActiveUpload up = activeUpload;
        if (up != null && up.transferId.equals(tag.getString("transferId"))) {
            up.ackedChunks = Math.max(up.ackedChunks, tag.getInt("receivedChunks"));
        }
    }

    private static void onDownloadChunk(CompoundTag tag) {
        String transferId = tag.getString("transferId");
        if (transferId.isEmpty() || transferId.equals(cancelledDownloadId)) {
            return;
        }
        int index = tag.getInt("index");
        int total = tag.getInt("total");
        long totalBytes = tag.getLong("totalBytes");
        String fileName = tag.getString("fileName");
        boolean isDefault = tag.getBoolean("isDefault");
        String purpose = tag.getString("purpose");
        byte[] slice = tag.getByteArray("bytes");

        if (total < 1 || totalBytes <= 0L || totalBytes > ShopTemplateStore.MAX_FILE_BYTES) {
            activeDownload = null;
            setErrorAndNotify("too_large", "msg.module_shop.cfg_too_large",
                    "下载文件超过 32 MiB 限制或分片非法。", "");
            return;
        }

        ActiveDownload down;
        if (index == 0) {
            down = new ActiveDownload(transferId, fileName, isDefault, purpose, total, totalBytes);
            activeDownload = down;
        } else {
            down = activeDownload;
            if (down == null || !down.transferId.equals(transferId) || down.totalChunks != total || index != down.nextIndex) {
                activeDownload = null;
                setErrorAndNotify("io_error", "msg.module_shop.cfg_bad_file", "下载分片乱序或中断。", "");
                return;
            }
        }

        if ((long) down.buffer.size() + slice.length > ShopTemplateStore.MAX_FILE_BYTES) {
            activeDownload = null;
            setErrorAndNotify("too_large", "msg.module_shop.cfg_too_large", "下载体积超过 32 MiB 限制。", "");
            return;
        }

        down.buffer.writeBytes(slice);
        down.nextIndex++;

        if (down.nextIndex < down.totalChunks) {
            return;
        }

        activeDownload = null;
        byte[] allBytes = down.buffer.toByteArray();
        saveDownloadedBytesToLocal(down.fileName, down.isDefault, down.purpose, allBytes);
    }

    private static void saveDownloadedBytesToLocal(String rawFileName, boolean isDefault,
                                                   String purpose, byte[] bytes) {
        Path cfg = configDir();
        Path dir = ShopTemplateStore.dir(cfg).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
            TemplateFormat fmt = TemplateFormat.fromFileName(rawFileName, TemplateFormat.MSHOP);
            Path target;
            if (isDefault || "save_default".equals(purpose)) {
                target = ShopTemplateStore.defaultFile(cfg, fmt);
            } else {
                String clean = sanitizeLocalDownloadName(rawFileName, fmt);
                target = dir.resolve(clean).normalize();
                if (!"download_file".equals(purpose) && Files.exists(target)) {
                    String stem = clean.replaceFirst("(?i)(\\.json\\.gz|\\.mshop|\\.json)$", "");
                    int idx = 2;
                    while (Files.exists(target) && idx <= 99) {
                        target = dir.resolve(stem + "_" + idx + fmt.extension).normalize();
                        idx++;
                    }
                }
            }
            if (!target.startsWith(dir)) {
                setErrorAndNotify("io_error", "msg.module_shop.cfg_export_fail", "目标文件名非法。", rawFileName);
                return;
            }
            String err = ShopTemplateStore.writeRawAtomic(target, bytes);
            if (!err.isEmpty()) {
                setErrorAndNotify("io_error", "msg.module_shop.cfg_export_fail", "写入本地文件失败。", err);
                return;
            }
            lastErrorMessage = "";
            Component msg = isDefault || "save_default".equals(purpose)
                    ? Component.translatable("msg.module_shop.cfg_saved_default", String.valueOf(target))
                    : Component.translatable("msg.module_shop.cfg_exported", String.valueOf(target));
            chat(msg.copy().withStyle(ChatFormatting.GREEN));
            notifyScreensLocalWritten(target);
        } catch (IOException | RuntimeException ex) {
            setErrorAndNotify("io_error", "msg.module_shop.cfg_export_fail", "写入本地文件异常。", ex.toString());
        }
    }

    private static void onExportDone(CompoundTag tag) {
        lastErrorMessage = "";
        String relKey = tag.getString("relKey");
        Path localTarget = relKey.isBlank() ? null : ShopTemplateStore.dir(configDir()).resolve(relKey);
        notifyScreensLocalWritten(localTarget);
    }

    private static void onTransferError(CompoundTag tag) {
        activeUpload = null;
        activeDownload = null;
        String code = tag.getString("code");
        String langKey = tag.getString("langKey");
        String fallback = tag.getString("fallback");
        String detail = tag.getString("detail");
        setErrorTextOnly(code, langKey, fallback, detail);
    }

    private static void onAppliedDone(CompoundTag tag) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.screen instanceof TemplatePreviewScreen preview) {
            preview.onAppliedDone(tag);
        } else if (minecraft != null && minecraft.screen instanceof ConfigManagerScreen manager) {
            manager.onAppliedDone(tag);
        }
    }

    private static void notifyScreensLocalWritten(@Nullable Path target) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        if (minecraft.screen instanceof ConfigManagerScreen manager && target != null) {
            manager.onLocalExported(target);
        } else if (minecraft.screen instanceof ConfigExportScreen exportScreen) {
            exportScreen.onExportFinished(target);
        }
    }

    // ------------------------------------------------------------------ 传统辅助与兼容方法

    /** 把服务端下发的模板写成导出文件或默认模板，返回写出的路径（失败 {@code null}）。 */
    public static Path writeExport(ShopTemplate template, String prefix) {
        return ShopTemplateStore.saveExportOrDefault(configDir(), template, prefix);
    }

    /** 把服务端下发的模板写成默认模板（`shop_config.json`）。 */
    public static String writeDefault(ShopTemplate template) {
        return ShopTemplateStore.write(ShopTemplateStore.defaultFile(configDir()), template);
    }

    /** 模板 JSON（兼容旧调用点）。 */
    public static String toJson(ShopTemplate template) {
        return template == null ? "" : template.toJson().toString();
    }

    /** 解析来自服务端 / 文件的 JSON。 */
    public static ShopTemplate parse(String json) {
        return ShopTemplate.fromJson(json);
    }

    private static void chat(Component message) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(message, false);
        }
    }

    /** 收到服务端配置列表同步：刷新当前打开的配置管理界面。 */
    public static void onServerList(CompoundTag data) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.screen instanceof ConfigManagerScreen manager) {
            manager.onServerList(data);
        }
    }

    /** 导入预览：交给配置管理界面打开二级确认框；若已在预览界面则刷新内容；无界面时聊天提示。 */
    public static void showPreview(CompoundTag data) {
        lastErrorMessage = "";
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.screen instanceof ConfigManagerScreen manager) {
            manager.onPreview(data);
        } else if (minecraft != null && minecraft.screen instanceof TemplatePreviewScreen preview) {
            preview.onPreview(data);
        } else {
            chat(Component.translatable("msg.module_shop.cfg_preview_chat",
                    data.getInt("total"), data.getInt("valid"), data.getInt("invalid")));
        }
    }

    private static void setErrorAndNotify(String code, String langKey, String fallback, String detail) {
        setErrorTextOnly(code, langKey, fallback, detail);
        chat(Component.translatableWithFallback(langKey, fallback).copy().withStyle(ChatFormatting.RED));
    }

    private static void setErrorTextOnly(String code, String langKey, String fallback, String detail) {
        String key = langKey == null || langKey.isBlank() ? "msg.module_shop.cfg_bad_file" : langKey;
        String fb = fallback == null || fallback.isBlank() ? "模板操作失败" : fallback;
        String base = Component.translatableWithFallback(key, fb).getString();
        lastErrorMessage = detail != null && !detail.isBlank() ? base + " (" + trimDetail(detail) + ")" : base;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.screen instanceof ConfigManagerScreen manager) {
            manager.onTransferError(lastErrorMessage);
        } else if (minecraft != null && minecraft.screen instanceof ConfigExportScreen exportScreen) {
            exportScreen.onTransferError(lastErrorMessage);
        }
    }

    private static String sanitizeLocalDownloadName(String rawName, TemplateFormat fmt) {
        if (rawName == null || rawName.isBlank()) {
            return "export" + fmt.extension;
        }
        String base = ShopTemplateStore.sanitizePrefix(rawName);
        if (ShopTemplateStore.isDefaultName(rawName)) {
            return fmt.defaultFileName;
        }
        return base + fmt.extension;
    }

    private static boolean hasZipMagic(byte[] raw) {
        return raw.length >= 4
                && raw[0] == 0x50 && raw[1] == 0x4B
                && ((raw[2] == 0x03 && raw[3] == 0x04) || (raw[2] == 0x05 && raw[3] == 0x06));
    }

    private static boolean hasGzipMagic(byte[] raw) {
        return raw.length >= 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B;
    }

    private static String trimDetail(String s) {
        String clean = s.replace('\n', ' ').replace('\r', ' ').strip();
        return clean.length() <= 80 ? clean : clean.substring(0, 80) + "...";
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f MiB", bytes / (1024.0 * 1024.0));
    }
}
