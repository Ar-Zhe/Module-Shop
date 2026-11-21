package cn.autoforged.module_shop.shop.home;

import cn.autoforged.module_shop.config.ShopTemplateStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务器公告配置与热更新持久化（{@code config/<DIR_NAME>/announcements.json}）。
 *
 * <p>排序规则：{@code pinned} 降序，{@code order} 升序（保持同序稳定性）。
 * 仅在首次配置文件不存在时写入默认示例公告；若管理员主动清空并保存空列表，热重载时会保留空列表。
 */
public final class AnnouncementConfig {
    public static final String FILE_NAME = "announcements.json";
    public static final String CONFIG_SUBDIR = ShopTemplateStore.DIR_NAME;
    public static final int MAX_ANNOUNCEMENTS = 50;

    private static final class LogHolder {
        private static final Logger LOG = LoggerFactory.getLogger("module_shop/announcements");
    }

    private static final class GsonHolder {
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    }

    public int formatVersion = 1;
    public List<AnnouncementEntry> announcements = new ArrayList<>();

    private static volatile AnnouncementConfig instance;

    public static synchronized AnnouncementConfig get() {
        if (instance == null) {
            reload();
        }
        return instance;
    }

    public static synchronized void reload() {
        reload(resolveRuntimeConfigDir());
    }

    public static synchronized void reload(Path configDir) {
        Path file = resolveFile(configDir);
        try {
            if (Files.isRegularFile(file)) {
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                if (raw.startsWith("\uFEFF")) {
                    raw = raw.substring(1);
                }
                AnnouncementConfig parsed = parseJson(raw);
                if (parsed != null) {
                    parsed.normalizeAndSort();
                    instance = parsed;
                    return;
                }
            } else {
                AnnouncementConfig defaults = createDefault();
                defaults.normalizeAndSort();
                writeFileAtomic(file, defaults);
                instance = defaults;
                return;
            }
        } catch (Throwable ex) {
            LogHolder.LOG.warn("[ModuleShop] 读取 {} 失败，使用内置默认公告：{}", FILE_NAME, ex.toString());
        }
        AnnouncementConfig fallback = createDefault();
        fallback.normalizeAndSort();
        instance = fallback;
    }

    public static synchronized boolean saveFromTag(ListTag list) {
        return saveFromTag(resolveRuntimeConfigDir(), list);
    }

    public static synchronized boolean saveFromTag(Path configDir, ListTag list) {
        List<AnnouncementEntry> parsed = new ArrayList<>();
        if (list != null) {
            for (int i = 0; i < list.size() && parsed.size() < MAX_ANNOUNCEMENTS; i++) {
                AnnouncementEntry entry = AnnouncementEntry.fromTag(list.getCompound(i));
                if ((entry.title == null || entry.title.isBlank())
                        && (entry.content == null || entry.content.isBlank())
                        && (entry.blocks == null || entry.blocks.isEmpty())) {
                    continue;
                }
                if (!list.getCompound(i).contains("order")) {
                    entry.order = i;
                }
                entry.sanitize(i);
                parsed.add(entry);
            }
        }
        return replaceAndSave(configDir, parsed);
    }

    public static synchronized boolean replaceAndSave(Path configDir, List<AnnouncementEntry> entries) {
        AnnouncementConfig next = new AnnouncementConfig();
        next.formatVersion = 1;
        if (entries != null) {
            for (int i = 0; i < entries.size() && next.announcements.size() < MAX_ANNOUNCEMENTS; i++) {
                AnnouncementEntry entry = entries.get(i);
                if (entry == null) {
                    continue;
                }
                AnnouncementEntry clean = entry.copy();
                if ((clean.title == null || clean.title.isBlank())
                        && (clean.content == null || clean.content.isBlank())
                        && (clean.blocks == null || clean.blocks.isEmpty())) {
                    continue;
                }
                clean.sanitize(i);
                next.announcements.add(clean);
            }
        }
        next.normalizeAndSort();
        instance = next;
        return writeFileAtomic(resolveFile(configDir), next);
    }

    public List<String> referencedMediaIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (AnnouncementEntry entry : sortedCopy()) {
            if (entry != null) {
                ids.addAll(entry.referencedMediaIds());
            }
        }
        return new ArrayList<>(ids);
    }

    public synchronized void normalizeAndSort() {
        if (announcements == null) {
            announcements = new ArrayList<>();
        }
        List<IndexedEntry> indexed = new ArrayList<>(announcements.size());
        for (int i = 0; i < announcements.size() && indexed.size() < MAX_ANNOUNCEMENTS; i++) {
            AnnouncementEntry entry = announcements.get(i);
            if (entry == null) {
                continue;
            }
            AnnouncementEntry clean = entry.copy();
            clean.sanitize(i);
            indexed.add(new IndexedEntry(clean, i));
        }
        indexed.sort((a, b) -> {
            int pinCmp = Boolean.compare(b.entry.pinned, a.entry.pinned);
            if (pinCmp != 0) {
                return pinCmp;
            }
            int orderCmp = Integer.compare(a.entry.order, b.entry.order);
            if (orderCmp != 0) {
                return orderCmp;
            }
            return Integer.compare(a.index, b.index);
        });
        List<AnnouncementEntry> sorted = new ArrayList<>(indexed.size());
        for (IndexedEntry item : indexed) {
            sorted.add(item.entry);
        }
        this.announcements = sorted;
    }

    public List<AnnouncementEntry> sortedCopy() {
        List<AnnouncementEntry> out = new ArrayList<>(announcements.size());
        for (AnnouncementEntry entry : announcements) {
            out.add(entry.copy());
        }
        return out;
    }

    public void writeSnapshot(CompoundTag snapshotTag) {
        ListTag list = new ListTag();
        for (AnnouncementEntry entry : sortedCopy()) {
            list.add(entry.toTag());
        }
        snapshotTag.put("announcements", list);
    }

    public static AnnouncementConfig createDefault() {
        String today = LocalDate.now().toString();
        AnnouncementConfig cfg = new AnnouncementConfig();
        cfg.announcements.add(new AnnouncementEntry(
                "welcome-pinned",
                "欢迎来到服务器商店中心",
                "置顶",
                "在这里可以一站式浏览个人资产、完成每日签到领取补贴，并前往每日商店、购物城、拍卖行、摊位与出货箱自由交易。\n按 [K] 键或输入 /shop 即可随时打开面板，祝你游戏愉快！",
                true,
                0,
                today
        ));
        cfg.announcements.add(new AnnouncementEntry(
                "daily-signin-tip",
                "每日签到与连签里程奖励",
                "活动",
                "点击本页签右上角「立即签到」即可快速领取今日星币补助；连续签到达成指定天数，还可获得额外连签加成与每轮里程碑奖金！",
                false,
                1,
                today
        ));
        cfg.announcements.add(new AnnouncementEntry(
                "market-guide",
                "自由市场与玩家互通指南",
                "公告",
                "玩家可通过摊位摆摊、拍卖行竞拍、悬赏求购与面对面交易高效互通物资。管理员可在管理界面或本页实时编辑并热更新以上公告。",
                false,
                2,
                today
        ));
        return cfg;
    }

    private static Path resolveRuntimeConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    public static Path resolveFile(Path configDir) {
        return configDir.resolve(CONFIG_SUBDIR).resolve(FILE_NAME);
    }

    private static AnnouncementConfig parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonElement elem = JsonParser.parseString(raw);
        if (!elem.isJsonObject()) {
            return null;
        }
        JsonObject root = elem.getAsJsonObject();
        if (!root.has("announcements") || !root.get("announcements").isJsonArray()) {
            return null;
        }
        AnnouncementConfig cfg = new AnnouncementConfig();
        if (root.has("formatVersion") && root.get("formatVersion").isJsonPrimitive()) {
            try {
                cfg.formatVersion = root.get("formatVersion").getAsInt();
            } catch (RuntimeException ignored) {
            }
        }
        JsonArray arr = root.getAsJsonArray("announcements");
        for (JsonElement item : arr) {
            if (item != null && item.isJsonObject()) {
                cfg.announcements.add(AnnouncementEntry.fromJson(item.getAsJsonObject()));
            }
        }
        return cfg;
    }

    private static boolean writeFileAtomic(Path file, AnnouncementConfig cfg) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            JsonObject root = new JsonObject();
            root.addProperty("formatVersion", cfg.formatVersion);
            JsonArray arr = new JsonArray();
            for (AnnouncementEntry entry : cfg.announcements) {
                if (entry != null) {
                    arr.add(entry.toJson());
                }
            }
            root.add("announcements", arr);
            Files.writeString(tmp, GsonHolder.GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Throwable ex) {
            LogHolder.LOG.warn("[ModuleShop] 写入 {} 失败：{}", FILE_NAME, ex.toString());
            return false;
        }
    }

    private record IndexedEntry(AnnouncementEntry entry, int index) {
    }
}
