package cn.autoforged.module_shop.config;

import cn.autoforged.module_shop.config.ShopTemplate.ExportSection;
import cn.autoforged.module_shop.config.ShopTemplate.ValidationIssue;
import cn.autoforged.module_shop.config.ShopTemplateStore.ReadDetailedResult;
import cn.autoforged.module_shop.config.ShopTemplateStore.TemplateFormat;
import cn.autoforged.module_shop.data.ShopSavedData;
import cn.autoforged.module_shop.network.payload.ClientboundShopSyncPayload;
import cn.autoforged.module_shop.shop.ShopManager;
import cn.autoforged.module_shop.shop.TemplateService;
import cn.autoforged.module_shop.shop.TemplateService.ApplyDetailedResult;
import cn.autoforged.module_shop.shop.TemplateService.Mode;
import cn.autoforged.module_shop.shop.TemplateService.PreviewDetailed;
import cn.autoforged.module_shop.shop.auth.AdminAuthService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 商店模板的**服务端传输与会话编排器**（对应客户端 {@code ClientTemplateIO}）。
 *
 * <p>核心能力：
 * <ul>
 *     <li>彻底消除 {@code StringTag} 65,535 字节限制与 JSON 三倍回声冗余；</li>
 *     <li>大文件走 {@link #TRANSFER_CHUNK_BYTES}（256 KiB）{@code ByteArrayTag} 二进制分片流式上传/下载，支持进度与取消；</li>
 *     <li>单人集成服（{@code !server.isDedicatedServer()}）直接服务端本机落盘与读取，零网络拷贝；</li>
 *     <li>服务端暂存 {@link PendingTemplate} 会话，预览后确认应用直接复用内存中对象，并原样保留上传压缩包到服务端目录；</li>
 *     <li>全部操作受 OP 权限与 {@link AdminAuthService} 管理员密码锁保护。</li>
 * </ul>
 */
public final class TemplateTransferManager {
    private static final Logger LOG = LoggerFactory.getLogger("module_shop/transfer");

    /** 每个分片 NBT 包的原始载荷字节数（远低于 NbtTransport.SERVERBOUND_MAX_BYTES = 8 MiB）。 */
    public static final int TRANSFER_CHUNK_BYTES = 256 * 1024;
    private static final long PENDING_TTL_MS = 5L * 60L * 1000L;
    private static final int MAX_PREVIEW_ISSUES = 100;

    private static final class PendingUpload {
        final String transferId;
        final String fileName;
        final int totalChunks;
        final long declaredBytes;
        final ByteArrayOutputStream buffer;
        int nextChunkIndex;
        long updatedAt;

        PendingUpload(String transferId, String fileName, int totalChunks, long declaredBytes) {
            this.transferId = transferId;
            this.fileName = fileName;
            this.totalChunks = totalChunks;
            this.declaredBytes = declaredBytes;
            int initialCap = (int) Math.min(Math.max(declaredBytes, 4096L), 2L * 1024L * 1024L);
            this.buffer = new ByteArrayOutputStream(initialCap);
            this.nextChunkIndex = 0;
            this.updatedAt = System.currentTimeMillis();
        }
    }

    private static final class PendingTemplate {
        final String transferId;
        final String source;
        final String fileKey;
        final String fileName;
        final ShopTemplate template;
        final @Nullable byte[] rawBytes;
        final long updatedAt;

        PendingTemplate(String transferId, String source, String fileKey, String fileName,
                        ShopTemplate template, @Nullable byte[] rawBytes) {
            this.transferId = transferId;
            this.source = source;
            this.fileKey = fileKey;
            this.fileName = fileName;
            this.template = template;
            this.rawBytes = rawBytes;
            this.updatedAt = System.currentTimeMillis();
        }
    }

    private static final Map<UUID, PendingUpload> UPLOADS = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingTemplate> PREVIEWS = new ConcurrentHashMap<>();

    private TemplateTransferManager() {
    }

    /** 由 {@link ShopManager} 统一分发所有 {@code cfg_*} 服务端动作；已消费返回 {@code true}。 */
    public static boolean handleServerAction(MinecraftServer server, ServerPlayer player, ShopSavedData data,
                                             String action, CompoundTag tag) {
        if (action == null || !action.startsWith("cfg_")) {
            return false;
        }
        if (server == null || player == null || data == null) {
            return true;
        }
        pruneExpired(System.currentTimeMillis());

        switch (action) {
            case "cfg_list" -> handleList(server, player);
            case "cfg_export", "cfg_export_v3" -> handleExport(server, player, data, tag);
            case "cfg_save_default" -> handleSaveDefault(server, player, data, tag);
            case "cfg_upload_chunk" -> handleUploadChunk(server, player, data, tag);
            case "cfg_upload_cancel" -> handleUploadCancel(player, tag);
            case "cfg_server_preview" -> handleServerPreview(server, player, data, tag);
            case "cfg_import_apply" -> handleImportApply(server, player, data, tag);
            case "cfg_init_from_default" -> handleInitFromDefault(server, player, data);
            case "cfg_download_file" -> handleDownloadFile(server, player, tag);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static boolean requireUnlockedAdmin(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        if (!ShopManager.canEditAdmin(player)) {
            if (AdminAuthService.isLockEnabled()) {
                ShopManager.promptAdminAuth(player);
            }
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ 列表同步

    private static void handleList(MinecraftServer server, ServerPlayer player) {
        if (!requireUnlockedAdmin(player)) {
            return;
        }
        boolean dedicated = server.isDedicatedServer();
        boolean lockEnabled = AdminAuthService.isLockEnabled();
        boolean canEdit = ShopManager.canEditAdmin(player);

        CompoundTag out = new CompoundTag();
        out.putString("kind", "cfg_list");
        out.putBoolean("dedicated", dedicated);
        out.putBoolean("adminLockEnabled", lockEnabled);
        out.putBoolean("canAdmin", canEdit);

        ListTag list = new ListTag();
        if (canEdit && dedicated) {
            Path configDir = FMLPaths.CONFIGDIR.get();
            for (Path file : ShopTemplateStore.listTemplates(configDir)) {
                CompoundTag item = new CompoundTag();
                String name = file.getFileName().toString();
                item.putString("key", ShopTemplateStore.relativeKey(configDir, file));
                item.putString("name", name);
                item.putBoolean("isDefault", ShopTemplateStore.isDefaultName(name));
                item.putString("format", ShopTemplateStore.TemplateFormat.fromFileName(name, TemplateFormat.JSON)
                        .name().toLowerCase(java.util.Locale.ROOT));
                try {
                    item.putLong("size", Files.size(file));
                    item.putLong("mtime", Files.getLastModifiedTime(file).toMillis());
                } catch (IOException ignored) {
                    item.putLong("size", 0L);
                    item.putLong("mtime", 0L);
                }
                list.add(item);
            }
        }
        out.put("files", list);
        ClientboundShopSyncPayload.send(player, out);
    }

    // ------------------------------------------------------------------ 导出与保存默认

    private static void handleExport(MinecraftServer server, ServerPlayer player, ShopSavedData data, CompoundTag tag) {
        if (!requireUnlockedAdmin(player)) {
            return;
        }
        String rawName = tag.contains("rawName") ? tag.getString("rawName") : tag.getString("name");
        TemplateFormat format = TemplateFormat.byId(tag.getString("format"), TemplateFormat.MSHOP);
        EnumSet<ExportSection> sections = ExportSection.parseMask(tag.getString("sections"));
        if (sections.isEmpty()) {
            sections = ExportSection.allDefault(false);
        }

        String metaName = ShopTemplateStore.isDefaultName(rawName)
                ? "shop_config"
                : ShopTemplateStore.sanitizePrefix(rawName);
        ShopTemplate template = TemplateService.extract(server, data, metaName, sections);

        boolean dedicated = server.isDedicatedServer();
        String dest = tag.getString("dest").trim().toLowerCase(java.util.Locale.ROOT);
        boolean saveOnServerDisk = !dedicated || "server".equals(dest);

        if (saveOnServerDisk) {
            Path configDir = FMLPaths.CONFIGDIR.get();
            Path written = ShopTemplateStore.saveExportOrDefault(configDir, template, rawName, format, sections);
            if (written == null) {
                sendTransferError(player, "", "io_error", "msg.module_shop.cfg_export_fail",
                        "导出配置文件失败，请检查服务端磁盘空间与写入权限。", "");
                return;
            }
            String relKey = ShopTemplateStore.relativeKey(configDir, written);
            String relPath = "config/" + ShopTemplateStore.DIR_NAME + "/" + relKey;
            boolean isDefault = ShopTemplateStore.isDefaultName(rawName);

            Component msg = isDefault
                    ? Component.translatable("msg.module_shop.cfg_saved_default", relPath)
                    : (dedicated
                            ? Component.translatable("msg.module_shop.cfg_exported_server", relPath)
                            : Component.translatable("msg.module_shop.cfg_exported", relPath));
            player.displayClientMessage(msg.copy().withStyle(ChatFormatting.GREEN), false);

            CompoundTag done = new CompoundTag();
            done.putString("kind", "cfg_export_done");
            done.putString("fileName", written.getFileName().toString());
            done.putString("relKey", relKey);
            done.putString("relPath", relPath);
            done.putBoolean("isDefault", isDefault);
            done.putBoolean("dedicated", dedicated);
            done.putString("format", format.name().toLowerCase(java.util.Locale.ROOT));
            try {
                done.putLong("size", Files.size(written));
            } catch (IOException ignored) {
                done.putLong("size", 0L);
            }
            ClientboundShopSyncPayload.send(player, done);
            if (dedicated) {
                handleList(server, player);
            }
            return;
        }

        // 专用服 -> 分片下发到本地玩家计算机
        streamTemplateDownload(player, template, format, sections,
                ShopTemplateStore.previewExportFileName(rawName, format),
                ShopTemplateStore.isDefaultName(rawName), "export");
    }

    private static void handleSaveDefault(MinecraftServer server, ServerPlayer player, ShopSavedData data,
                                          CompoundTag tag) {
        if (!requireUnlockedAdmin(player)) {
            return;
        }
        String name = tag.getString("name");
        TemplateFormat format = TemplateFormat.byId(tag.getString("format"), TemplateFormat.MSHOP);
        boolean includeSecurity = tag.getBoolean("includeSecurity");
        EnumSet<ExportSection> sections = ExportSection.parseMask(tag.getString("sections"));
        if (sections.isEmpty()) {
            sections = ExportSection.allDefault(includeSecurity);
        } else if (includeSecurity) {
            sections.add(ExportSection.SECURITY);
        }

        ShopTemplate template = TemplateService.extract(server, data, name, sections);
        boolean dedicated = server.isDedicatedServer();
        String dest = tag.getString("dest").trim().toLowerCase(java.util.Locale.ROOT);
        if (!dedicated || !"local".equals(dest)) {
            Path configDir = FMLPaths.CONFIGDIR.get();
            Path target = ShopTemplateStore.defaultFile(configDir, format);
            String err = ShopTemplateStore.write(target, template, sections);
            if (!err.isEmpty()) {
                sendTransferError(player, "", err, "msg.module_shop.cfg_save_fail",
                        "保存默认模板失败。", err);
                return;
            }
            String relKey = ShopTemplateStore.relativeKey(configDir, target);
            String relPath = "config/" + ShopTemplateStore.DIR_NAME + "/" + relKey;
            player.displayClientMessage(Component.translatable("msg.module_shop.cfg_saved_default", relPath)
                    .withStyle(ChatFormatting.GREEN), false);
            CompoundTag done = new CompoundTag();
            done.putString("kind", "cfg_export_done");
            done.putString("fileName", target.getFileName().toString());
            done.putString("relKey", relKey);
            done.putString("relPath", relPath);
            done.putBoolean("isDefault", true);
            done.putBoolean("dedicated", dedicated);
            done.putString("format", format.name().toLowerCase(java.util.Locale.ROOT));
            ClientboundShopSyncPayload.send(player, done);
            if (dedicated) {
                handleList(server, player);
            }
            return;
        }

        streamTemplateDownload(player, template, format, sections, format.defaultFileName, true, "save_default");
    }

    private static void handleDownloadFile(MinecraftServer server, ServerPlayer player, CompoundTag tag) {
        if (!requireUnlockedAdmin(player)) {
            return;
        }
        Path configDir = FMLPaths.CONFIGDIR.get();
        String key = tag.getString("key");
        Path file = ShopTemplateStore.resolveSafeServerFile(configDir, key);
        if (file == null || !Files.isRegularFile(file)) {
            sendTransferError(player, "", "missing", "msg.module_shop.cfg_missing",
                    "未找到指定模板文件，或文件已被删除。", key);
            return;
        }
        try {
            long size = Files.size(file);
            if (size <= 0L) {
                sendTransferError(player, "", "empty", "msg.module_shop.cfg_missing",
                        "模板文件为空，无法下载。", key);
                return;
            }
            if (size > ShopTemplateStore.MAX_FILE_BYTES) {
                sendTransferError(player, "", "too_large", "msg.module_shop.cfg_too_large",
                        "模板文件超过 32 MiB 上限，无法传输。", String.valueOf(size));
                return;
            }
            byte[] raw = Files.readAllBytes(file);
            String name = file.getFileName().toString();
            streamRawDownload(player, name, raw, ShopTemplateStore.isDefaultName(name), "download_file");
        } catch (IOException | RuntimeException ex) {
            sendTransferError(player, "", "io_error", "msg.module_shop.cfg_bad_file",
                    "读取服务端模板失败。", ex.toString());
        }
    }

    private static void streamTemplateDownload(ServerPlayer player, ShopTemplate template, TemplateFormat format,
                                               EnumSet<ExportSection> sections, String fileName, boolean isDefault,
                                               String purpose) {
        try {
            byte[] raw = ShopTemplateStore.writeToBytes(template, format, sections);
            if (raw.length > ShopTemplateStore.MAX_FILE_BYTES) {
                sendTransferError(player, "", "too_large", "msg.module_shop.cfg_too_large",
                        "生成的导出文件超过 32 MiB 传输上限（可取消勾选图片公告后重试）。",
                        raw.length + " bytes");
                return;
            }
            streamRawDownload(player, fileName, raw, isDefault, purpose);
        } catch (IOException | RuntimeException ex) {
            sendTransferError(player, "", "io_error", "msg.module_shop.cfg_export_fail",
                    "序列化导出文件失败。", ex.toString());
        }
    }

    private static void streamRawDownload(ServerPlayer player, String fileName, byte[] raw,
                                          boolean isDefault, String purpose) {
        String transferId = UUID.randomUUID().toString();
        int total = Math.max(1, (raw.length + TRANSFER_CHUNK_BYTES - 1) / TRANSFER_CHUNK_BYTES);
        for (int i = 0; i < total; i++) {
            int from = i * TRANSFER_CHUNK_BYTES;
            int to = Math.min(raw.length, from + TRANSFER_CHUNK_BYTES);
            byte[] slice = (from == 0 && to == raw.length) ? raw : Arrays.copyOfRange(raw, from, to);
            CompoundTag chunk = new CompoundTag();
            chunk.putString("kind", "cfg_download_chunk");
            chunk.putString("transferId", transferId);
            chunk.putInt("index", i);
            chunk.putInt("total", total);
            chunk.putLong("totalBytes", raw.length);
            chunk.putString("fileName", fileName);
            chunk.putBoolean("isDefault", isDefault);
            chunk.putString("purpose", purpose);
            chunk.putByteArray("bytes", slice);
            ClientboundShopSyncPayload.send(player, chunk);
        }
    }

    // ------------------------------------------------------------------ 上传分片 & 预览

    private static void handleUploadChunk(MinecraftServer server, ServerPlayer player,
                                          ShopSavedData data, CompoundTag tag) {
        if (!requireUnlockedAdmin(player)) {
            return;
        }
        String transferId = tag.getString("transferId").trim();
        String fileName = tag.getString("fileName").trim();
        int index = tag.getInt("index");
        int total = tag.getInt("total");
        long totalBytes = tag.getLong("totalBytes");
        byte[] bytes = tag.getByteArray("bytes");

        if (transferId.isEmpty() || total < 1 || total > 512
                || totalBytes <= 0L || totalBytes > ShopTemplateStore.MAX_FILE_BYTES
                || bytes.length > TRANSFER_CHUNK_BYTES + 1024) {
            UPLOADS.remove(player.getUUID());
            sendTransferError(player, transferId, "too_large", "msg.module_shop.cfg_too_large",
                    "上传模板体积超过 32 MiB 上限或分片参数非法。",
                    "totalBytes=" + totalBytes + " total=" + total);
            return;
        }

        UUID uuid = player.getUUID();
        PendingUpload upload;
        if (index == 0) {
            upload = new PendingUpload(transferId, fileName, total, totalBytes);
            UPLOADS.put(uuid, upload);
        } else {
            upload = UPLOADS.get(uuid);
            if (upload == null || !upload.transferId.equals(transferId) || upload.totalChunks != total) {
                UPLOADS.remove(uuid);
                sendTransferError(player, transferId, "expired", "msg.module_shop.cfg_bad_file",
                        "上传会话已过期或分片不匹配，请重试。", "expected existing upload");
                return;
            }
        }

        if (index != upload.nextChunkIndex) {
            UPLOADS.remove(uuid);
            sendTransferError(player, transferId, "io_error", "msg.module_shop.cfg_bad_file",
                    "上传分片乱序，请重试。", "expected=" + upload.nextChunkIndex + " got=" + index);
            return;
        }
        if ((long) upload.buffer.size() + bytes.length > ShopTemplateStore.MAX_FILE_BYTES) {
            UPLOADS.remove(uuid);
            sendTransferError(player, transferId, "too_large", "msg.module_shop.cfg_too_large",
                    "上传数据累计超过 32 MiB 上限，已中断。", "");
            return;
        }

        upload.buffer.writeBytes(bytes);
        upload.nextChunkIndex++;
        upload.updatedAt = System.currentTimeMillis();

        if (upload.nextChunkIndex < upload.totalChunks) {
            CompoundTag ack = new CompoundTag();
            ack.putString("kind", "cfg_upload_ack");
            ack.putString("transferId", transferId);
            ack.putInt("receivedChunks", upload.nextChunkIndex);
            ack.putInt("totalChunks", upload.totalChunks);
            ack.putLong("receivedBytes", upload.buffer.size());
            ack.putLong("totalBytes", upload.declaredBytes);
            ClientboundShopSyncPayload.send(player, ack);
            return;
        }

        UPLOADS.remove(uuid);
        byte[] allBytes = upload.buffer.toByteArray();
        ReadDetailedResult read = ShopTemplateStore.readBytesDetailed(allBytes, upload.fileName);
        if (!read.ok()) {
            sendErrorForReadResult(player, transferId, read);
            return;
        }

        PendingTemplate pending = new PendingTemplate(
                transferId, "local", "", upload.fileName, read.template(), allBytes);
        PREVIEWS.put(uuid, pending);
        sendPreview(server, player, data, pending, read.issues());
    }

    private static void handleUploadCancel(ServerPlayer player, CompoundTag tag) {
        if (!ShopManager.canEditAdmin(player)) {
            return;
        }
        String transferId = tag.getString("transferId").trim();
        PendingUpload current = UPLOADS.get(player.getUUID());
        if (current != null && (transferId.isEmpty() || transferId.equals(current.transferId))) {
            UPLOADS.remove(player.getUUID());
        }
    }

    private static void handleServerPreview(MinecraftServer server, ServerPlayer player,
                                            ShopSavedData data, CompoundTag tag) {
        if (!requireUnlockedAdmin(player)) {
            return;
        }
        Path configDir = FMLPaths.CONFIGDIR.get();
        String key = tag.getString("key");
        Path file = ShopTemplateStore.resolveSafeServerFile(configDir, key);
        if (file == null) {
            sendTransferError(player, "", "missing", "msg.module_shop.cfg_missing",
                    "目标模板路径无效或为系统保留文件。", key);
            return;
        }
        ReadDetailedResult read = ShopTemplateStore.readDetailed(file);
        if (!read.ok()) {
            sendErrorForReadResult(player, "", read);
            return;
        }
        String transferId = UUID.randomUUID().toString();
        PendingTemplate pending = new PendingTemplate(
                transferId, "server", key, file.getFileName().toString(), read.template(), null);
        PREVIEWS.put(player.getUUID(), pending);
        sendPreview(server, player, data, pending, read.issues());
    }

    private static void sendPreview(MinecraftServer server, ServerPlayer player, ShopSavedData data,
                                    PendingTemplate pending, List<ValidationIssue> parseIssues) {
        PreviewDetailed detailed = TemplateService.previewDetailed(
                pending.template, data, server.registryAccess(), null);
        var p = detailed.preview();

        CompoundTag out = new CompoundTag();
        out.putString("kind", "cfg_preview");
        out.putString("transferId", pending.transferId);
        out.putString("source", pending.source);
        out.putString("fileKey", pending.fileKey);
        out.putString("fileName", pending.fileName);
        out.putBoolean("syncServer", "local".equals(pending.source) && server.isDedicatedServer());
        out.putString("name", p.name());
        out.putString("createdAt", p.createdAt() == null ? "" : p.createdAt());
        out.putInt("formatVersion", pending.template.formatVersion);
        out.putInt("total", p.total());
        out.putInt("valid", p.valid());
        out.putInt("invalid", p.invalid());
        out.putInt("conflicts", p.conflicts());
        out.putString("sections", ExportSection.serializeMask(detailed.includedSections()));
        out.putInt("mediaCount", detailed.mediaCount());
        out.putLong("mediaBytes", detailed.mediaBytes());
        out.putBoolean("hasSecurity", detailed.hasSecurity());

        ListTag issueList = new ListTag();
        int totalIssues = 0;
        for (ValidationIssue issue : parseIssues) {
            totalIssues++;
            if (issueList.size() < MAX_PREVIEW_ISSUES) {
                issueList.add(issueToTag(issue));
            }
        }
        for (ValidationIssue issue : detailed.issues()) {
            totalIssues++;
            if (issueList.size() < MAX_PREVIEW_ISSUES) {
                issueList.add(issueToTag(issue));
            }
        }
        out.putInt("totalIssues", totalIssues);
        out.put("issues", issueList);
        ClientboundShopSyncPayload.send(player, out);
    }

    private static CompoundTag issueToTag(ValidationIssue issue) {
        CompoundTag it = new CompoundTag();
        it.putString("code", issue.code());
        it.putString("location", issue.location());
        it.putString("itemId", issue.itemId());
        it.putString("detail", issue.detail());
        return it;
    }

    // ------------------------------------------------------------------ 确认应用导入

    private static void handleImportApply(MinecraftServer server, ServerPlayer player,
                                          ShopSavedData data, CompoundTag tag) {
        if (!requireUnlockedAdmin(player)) {
            return;
        }
        String transferId = tag.getString("transferId").trim();
        String source = tag.getString("source");
        boolean fromServer = "server".equals(source);

        PendingTemplate pending = PREVIEWS.get(player.getUUID());
        ShopTemplate template = null;
        @Nullable byte[] uploadedBytes = null;
        String fileName = tag.getString("fileName");

        if (pending != null && !transferId.isEmpty() && transferId.equals(pending.transferId)) {
            template = pending.template;
            uploadedBytes = pending.rawBytes;
            if (fileName == null || fileName.isBlank()) {
                fileName = pending.fileName;
            }
            PREVIEWS.remove(player.getUUID());
        } else if (fromServer) {
            Path configDir = FMLPaths.CONFIGDIR.get();
            Path file = ShopTemplateStore.resolveSafeServerFile(configDir, tag.getString("fileKey"));
            if (file == null) {
                sendTransferError(player, transferId, "missing", "msg.module_shop.cfg_missing",
                        "服务端模板文件不存在或路径非法。", tag.getString("fileKey"));
                return;
            }
            ReadDetailedResult read = ShopTemplateStore.readDetailed(file);
            if (!read.ok()) {
                sendErrorForReadResult(player, transferId, read);
                return;
            }
            template = read.template();
        } else {
            sendTransferError(player, transferId, "expired", "msg.module_shop.cfg_bad_file",
                    "模板预览会话已失效，请重新打开文件预览后再导入。", "");
            return;
        }

        Mode mode = "merge".equals(tag.getString("mode")) ? Mode.MERGE : Mode.REPLACE;
        EnumSet<ExportSection> selectedSections = ExportSection.parseMask(tag.getString("sections"));
        ApplyDetailedResult detailed = TemplateService.applyDetailed(
                template, data, mode, server.registryAccess(),
                selectedSections.isEmpty() ? null : selectedSections);
        var result = detailed.result();

        data.setDirty();
        try {
            ShopManager.refreshAllClients(server, data);
        } catch (Throwable ex) {
            LOG.warn("[ModuleShop] 刷新全服客户端快照失败: {}", ex.toString(), ex);
        }

        player.displayClientMessage(Component.translatable("msg.module_shop.cfg_applied",
                        result.applied(), result.invalid(), result.duplicates())
                .withStyle(result.invalid() > 0 ? ChatFormatting.YELLOW : ChatFormatting.GREEN), false);

        if (!fromServer && server.isDedicatedServer()) {
            Path configDir = FMLPaths.CONFIGDIR.get();
            Path saved = uploadedBytes != null
                    ? ShopTemplateStore.saveUploadedBytes(configDir, fileName, uploadedBytes)
                    : ShopTemplateStore.saveUploadedFile(configDir, fileName, template);
            if (saved != null) {
                String relPath = "config/" + ShopTemplateStore.DIR_NAME + "/"
                        + ShopTemplateStore.relativeKey(configDir, saved);
                player.displayClientMessage(Component.translatable("msg.module_shop.cfg_synced_server", relPath)
                        .withStyle(ChatFormatting.AQUA), false);
            }
            handleList(server, player);
        }

        CompoundTag done = new CompoundTag();
        done.putString("kind", "cfg_applied_done");
        done.putInt("applied", result.applied());
        done.putInt("invalid", result.invalid());
        done.putInt("duplicates", result.duplicates());
        done.putInt("removed", result.removed());
        done.putInt("issueCount", detailed.issues().size());
        ClientboundShopSyncPayload.send(player, done);

        LOG.info("管理员 {} 导入商店模板（{}）：{}，问题条数={}",
                player.getGameProfile().getName(), mode, result.summary(), detailed.issues().size());
    }

    private static void handleInitFromDefault(MinecraftServer server, ServerPlayer player, ShopSavedData data) {
        if (!requireUnlockedAdmin(player)) {
            return;
        }
        Path configDir = FMLPaths.CONFIGDIR.get();
        Path defFile = ShopTemplateStore.resolveExistingDefaultFile(configDir);
        if (defFile == null) {
            defFile = ShopTemplateStore.defaultFile(configDir);
        }
        ReadDetailedResult read = ShopTemplateStore.readDetailed(defFile);
        if (!read.ok()) {
            if ("missing".equals(read.errorCode()) || "empty".equals(read.errorCode())) {
                player.displayClientMessage(Component.translatable("msg.module_shop.cfg_no_default")
                        .withStyle(ChatFormatting.RED), false);
            } else {
                sendErrorForReadResult(player, "", read);
            }
            return;
        }
        ApplyDetailedResult detailed = TemplateService.applyDetailed(
                read.template(), data, Mode.REPLACE, server.registryAccess(), null);
        var result = detailed.result();
        data.templateInitDone = true;
        data.setDirty();
        try {
            ShopManager.refreshAllClients(server, data);
        } catch (Throwable ignored) {
        }
        player.displayClientMessage(Component.translatable("msg.module_shop.cfg_applied",
                        result.applied(), result.invalid(), result.duplicates())
                .withStyle(result.invalid() > 0 ? ChatFormatting.YELLOW : ChatFormatting.GREEN), false);
    }

    // ------------------------------------------------------------------ 精准错误分类

    private static void sendErrorForReadResult(ServerPlayer player, String transferId, ReadDetailedResult read) {
        sendErrorForParseResult(player, transferId, read.errorCode(), read.errorDetail());
    }

    private static void sendErrorForParseResult(ServerPlayer player, String transferId, String code, String detail) {
        String key;
        String fallback;
        switch (code == null ? "" : code) {
            case "too_large" -> {
                key = "msg.module_shop.cfg_too_large";
                fallback = "模板文件过大（超过 32 MiB 压缩上限或 128 MiB 解压上限），已拒绝读取。";
            }
            case "newer_format" -> {
                key = "msg.module_shop.cfg_newer_format";
                fallback = "该模板格式版本高于当前模组支持版本，请升级模组后再导入。";
            }
            case "missing", "empty" -> {
                key = "msg.module_shop.cfg_missing";
                fallback = "找不到指定的模板文件，或文件内容为空。";
            }
            case "corrupt_archive" -> {
                key = "msg.module_shop.cfg_corrupt_archive";
                fallback = "模板压缩包已损坏或非法的 ZIP/GZip 格式。";
            }
            case "bad_json" -> {
                key = "msg.module_shop.cfg_bad_json";
                fallback = "配置文件格式不正确（JSON 解析失败），请检查语法。";
            }
            default -> {
                key = "msg.module_shop.cfg_bad_file";
                fallback = "读取模板文件失败。";
            }
        }
        sendTransferError(player, transferId, code, key, fallback, detail);
    }

    private static void sendTransferError(ServerPlayer player, String transferId, String code,
                                          String langKey, String fallbackText, String detail) {
        player.displayClientMessage(Component.translatableWithFallback(langKey, fallbackText)
                .withStyle(ChatFormatting.RED), false);
        CompoundTag err = new CompoundTag();
        err.putString("kind", "cfg_transfer_error");
        err.putString("transferId", transferId == null ? "" : transferId);
        err.putString("code", code == null ? "error" : code);
        err.putString("langKey", langKey);
        err.putString("fallback", fallbackText);
        err.putString("detail", detail == null ? "" : detail);
        ClientboundShopSyncPayload.send(player, err);
    }

    private static void pruneExpired(long now) {
        Iterator<Map.Entry<UUID, PendingUpload>> upIt = UPLOADS.entrySet().iterator();
        while (upIt.hasNext()) {
            if (now - upIt.next().getValue().updatedAt > PENDING_TTL_MS) {
                upIt.remove();
            }
        }
        Iterator<Map.Entry<UUID, PendingTemplate>> prevIt = PREVIEWS.entrySet().iterator();
        while (prevIt.hasNext()) {
            if (now - prevIt.next().getValue().updatedAt > PENDING_TTL_MS) {
                prevIt.remove();
            }
        }
    }
}
