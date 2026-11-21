package cn.autoforged.module_shop.shop.home;

import cn.autoforged.module_shop.shop.media.ShopMediaStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 服务器个人主页公告单条数据结构。
 *
 * <p>同时用于服务端 {@code announcements.json} 持久化、模板导出与 {@code sendSnapshot} 网络同步。
 * 支持图文混排结构化块（{@link #blocks}），并向后兼容仅含 {@link #content} 的旧版数据。
 */
public final class AnnouncementEntry {
    public static final int MAX_BLOCKS = 40;
    public static final int MAX_TOTAL_TEXT = 10000;

    public String id = "";
    public String title = "";
    public String category = "公告";
    public String content = "";
    public boolean pinned;
    public int order;
    public String date = "";
    public List<AnnouncementBlock> blocks = new ArrayList<>();

    public AnnouncementEntry() {
    }

    public AnnouncementEntry(String id, String title, String category, String content,
                             boolean pinned, int order, String date) {
        this(id, title, category, content, pinned, order, date, null);
    }

    public AnnouncementEntry(String id, String title, String category, String content,
                             boolean pinned, int order, String date, List<AnnouncementBlock> blocks) {
        this.id = id == null ? "" : id;
        this.title = title == null ? "" : title;
        this.category = category == null ? "" : category;
        this.content = content == null ? "" : content;
        this.pinned = pinned;
        this.order = order;
        this.date = date == null ? "" : date;
        this.blocks = new ArrayList<>();
        if (blocks != null) {
            for (AnnouncementBlock block : blocks) {
                if (block != null) {
                    this.blocks.add(block);
                }
            }
        }
        if (this.blocks.isEmpty() && !this.content.isBlank()) {
            this.blocks.add(AnnouncementBlock.textBlock(this.content));
        }
    }

    public List<AnnouncementBlock> blocks() {
        return blocks == null ? List.of() : blocks;
    }

    public AnnouncementEntry copy() {
        return new AnnouncementEntry(id, title, category, content, pinned, order, date, blocks);
    }

    public List<String> referencedMediaIds() {
        Set<String> out = new LinkedHashSet<>();
        if (blocks != null) {
            for (AnnouncementBlock block : blocks) {
                if (block != null && block.isImage() && ShopMediaStore.isValidId(block.mediaId())) {
                    out.add(block.mediaId());
                }
            }
        }
        return new ArrayList<>(out);
    }

    public void sanitize(int fallbackOrder) {
        if (id == null || id.isBlank()) {
            id = "ann-" + UUID.randomUUID().toString().substring(0, 8);
        } else {
            id = truncate(id.trim(), 48);
        }
        title = truncate(title == null ? "" : title.trim(), 120);
        if (category == null || category.isBlank()) {
            category = "公告";
        } else {
            category = truncate(category.trim(), 24);
        }
        date = truncate(date == null ? "" : date.trim(), 32);
        if (order < 0) {
            order = Math.max(0, fallbackOrder);
        }

        List<AnnouncementBlock> cleaned = new ArrayList<>();
        int totalText = 0;
        if (blocks != null) {
            for (AnnouncementBlock block : blocks) {
                if (block == null || cleaned.size() >= MAX_BLOCKS) {
                    continue;
                }
                if (AnnouncementBlock.TYPE_IMAGE.equals(block.type())) {
                    if (!ShopMediaStore.isValidId(block.mediaId())) {
                        continue;
                    }
                    cleaned.add(block);
                } else {
                    String txt = block.text() == null ? "" : block.text();
                    if (txt.isEmpty()) {
                        continue;
                    }
                    int remain = MAX_TOTAL_TEXT - totalText;
                    if (remain <= 0) {
                        continue;
                    }
                    if (txt.length() > remain) {
                        txt = txt.substring(0, remain);
                    }
                    totalText += txt.length();
                    cleaned.add(AnnouncementBlock.textBlock(txt));
                }
            }
        }

        String normalizedContent = content == null
                ? ""
                : truncate(content.replace("\r\n", "\n").replace('\r', '\n').trim(), MAX_TOTAL_TEXT);

        if (cleaned.isEmpty()) {
            if (!normalizedContent.isEmpty()) {
                cleaned.add(AnnouncementBlock.textBlock(normalizedContent));
            }
            this.blocks = cleaned;
            this.content = normalizedContent;
            return;
        }

        this.blocks = cleaned;
        StringBuilder summary = new StringBuilder();
        for (AnnouncementBlock block : cleaned) {
            String piece = block.isImage()
                    ? (block.caption().isEmpty() ? "[图片]" : "[图片: " + block.caption() + "]")
                    : block.text();
            if (piece == null || piece.isEmpty()) {
                continue;
            }
            if (summary.length() > 0) {
                summary.append('\n');
            }
            summary.append(piece);
        }
        this.content = truncate(summary.toString().trim(), MAX_TOTAL_TEXT);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id == null ? "" : id);
        tag.putString("title", title == null ? "" : title);
        tag.putString("category", category == null ? "" : category);
        tag.putString("content", content == null ? "" : content);
        tag.putBoolean("pinned", pinned);
        tag.putInt("order", order);
        tag.putString("date", date == null ? "" : date);
        ListTag blockList = new ListTag();
        if (blocks != null) {
            for (AnnouncementBlock block : blocks) {
                if (block != null) {
                    blockList.add(block.toTag());
                }
            }
        }
        tag.put("blocks", blockList);
        return tag;
    }

    public static AnnouncementEntry fromTag(CompoundTag tag) {
        if (tag == null) {
            return new AnnouncementEntry();
        }
        List<AnnouncementBlock> parsedBlocks = new ArrayList<>();
        if (tag.contains("blocks", Tag.TAG_LIST)) {
            ListTag list = tag.getList("blocks", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size() && parsedBlocks.size() < MAX_BLOCKS; i++) {
                parsedBlocks.add(AnnouncementBlock.fromTag(list.getCompound(i)));
            }
        }
        return new AnnouncementEntry(
                tag.getString("id"),
                tag.getString("title"),
                tag.getString("category"),
                tag.getString("content"),
                tag.getBoolean("pinned"),
                tag.getInt("order"),
                tag.getString("date"),
                parsedBlocks
        );
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id == null ? "" : id);
        obj.addProperty("title", title == null ? "" : title);
        obj.addProperty("category", category == null ? "" : category);
        obj.addProperty("content", content == null ? "" : content);
        obj.addProperty("pinned", pinned);
        obj.addProperty("order", order);
        obj.addProperty("date", date == null ? "" : date);
        JsonArray arr = new JsonArray();
        if (blocks != null) {
            for (AnnouncementBlock block : blocks) {
                if (block != null) {
                    arr.add(block.toJson());
                }
            }
        }
        obj.add("blocks", arr);
        return obj;
    }

    public static AnnouncementEntry fromJson(JsonObject obj) {
        if (obj == null) {
            return new AnnouncementEntry();
        }
        List<AnnouncementBlock> parsedBlocks = new ArrayList<>();
        if (obj.has("blocks") && obj.get("blocks").isJsonArray()) {
            for (JsonElement elem : obj.getAsJsonArray("blocks")) {
                if (elem != null && elem.isJsonObject() && parsedBlocks.size() < MAX_BLOCKS) {
                    parsedBlocks.add(AnnouncementBlock.fromJson(elem.getAsJsonObject()));
                }
            }
        }
        return new AnnouncementEntry(
                str(obj, "id", ""),
                str(obj, "title", ""),
                str(obj, "category", "公告"),
                str(obj, "content", ""),
                boolVal(obj, "pinned", false),
                intVal(obj, "order", 0),
                str(obj, "date", ""),
                parsedBlocks
        );
    }

    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen);
    }

    private static String str(JsonObject obj, String key, String fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsString();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static boolean boolVal(JsonObject obj, String key, boolean fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static int intVal(JsonObject obj, String key, int fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}
