package cn.autoforged.module_shop.shop.home;

import cn.autoforged.module_shop.shop.media.ShopMediaStore;
import com.google.gson.JsonObject;
import java.util.Locale;
import net.minecraft.nbt.CompoundTag;

/**
 * 服务器公告正文结构化块：支持纯文本段落与服务端内容寻址图片块混排。
 */
public record AnnouncementBlock(
        String type,
        String text,
        String mediaId,
        int widthPct,
        String align,
        String caption,
        int imgW,
        int imgH) {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_IMAGE = "image";

    public static final String ALIGN_LEFT = "left";
    public static final String ALIGN_CENTER = "center";
    public static final String ALIGN_RIGHT = "right";

    public static final int MAX_BLOCK_TEXT = 2000;
    public static final int MAX_CAPTION = 120;

    public AnnouncementBlock {
        type = TYPE_IMAGE.equalsIgnoreCase(type == null ? "" : type.trim()) ? TYPE_IMAGE : TYPE_TEXT;
        text = normalizeText(text);
        String rawMedia = mediaId == null ? "" : mediaId.trim().toLowerCase(Locale.ROOT);
        mediaId = ShopMediaStore.isValidId(rawMedia) ? rawMedia : "";
        widthPct = TYPE_IMAGE.equals(type) ? clampPct(widthPct) : 100;
        align = normalizeAlign(align);
        caption = truncate(caption == null ? "" : caption.replace("\r\n", " ").replace('\n', ' ').trim(), MAX_CAPTION);
        imgW = Math.max(0, imgW);
        imgH = Math.max(0, imgH);
    }

    public static AnnouncementBlock textBlock(String text) {
        return new AnnouncementBlock(TYPE_TEXT, text, "", 100, ALIGN_CENTER, "", 0, 0);
    }

    public static AnnouncementBlock imageBlock(String mediaId, int widthPct, String align,
            String caption, int imgW, int imgH) {
        return new AnnouncementBlock(TYPE_IMAGE, "", mediaId, widthPct, align, caption, imgW, imgH);
    }

    public boolean isImage() {
        return TYPE_IMAGE.equals(type) && ShopMediaStore.isValidId(mediaId);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", type);
        if (TYPE_IMAGE.equals(type)) {
            tag.putString("mediaId", mediaId);
            tag.putInt("widthPct", widthPct);
            tag.putString("align", align);
            if (!caption.isEmpty()) {
                tag.putString("caption", caption);
            }
            tag.putInt("imgW", imgW);
            tag.putInt("imgH", imgH);
        } else {
            tag.putString("text", text);
        }
        return tag;
    }

    public static AnnouncementBlock fromTag(CompoundTag tag) {
        if (tag == null) {
            return textBlock("");
        }
        String t = tag.getString("type");
        if (TYPE_IMAGE.equalsIgnoreCase(t.trim())) {
            return imageBlock(
                    tag.getString("mediaId"),
                    tag.contains("widthPct") ? tag.getInt("widthPct") : 100,
                    tag.getString("align"),
                    tag.getString("caption"),
                    tag.getInt("imgW"),
                    tag.getInt("imgH"));
        }
        return textBlock(tag.getString("text"));
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        if (TYPE_IMAGE.equals(type)) {
            obj.addProperty("mediaId", mediaId);
            obj.addProperty("widthPct", widthPct);
            obj.addProperty("align", align);
            if (!caption.isEmpty()) {
                obj.addProperty("caption", caption);
            }
            if (imgW > 0) {
                obj.addProperty("imgW", imgW);
            }
            if (imgH > 0) {
                obj.addProperty("imgH", imgH);
            }
        } else {
            obj.addProperty("text", text);
        }
        return obj;
    }

    public static AnnouncementBlock fromJson(JsonObject obj) {
        if (obj == null) {
            return textBlock("");
        }
        String t = str(obj, "type", TYPE_TEXT);
        if (TYPE_IMAGE.equalsIgnoreCase(t.trim())) {
            return imageBlock(
                    str(obj, "mediaId", ""),
                    intVal(obj, "widthPct", 100),
                    str(obj, "align", ALIGN_CENTER),
                    str(obj, "caption", ""),
                    intVal(obj, "imgW", 0),
                    intVal(obj, "imgH", 0));
        }
        return textBlock(str(obj, "text", ""));
    }

    private static String normalizeText(String raw) {
        if (raw == null) {
            return "";
        }
        String norm = raw.replace("\r\n", "\n").replace('\r', '\n');
        return norm.length() <= MAX_BLOCK_TEXT ? norm : norm.substring(0, MAX_BLOCK_TEXT);
    }

    private static int clampPct(int pct) {
        if (pct <= 0) {
            return 100;
        }
        return Math.max(20, Math.min(100, pct));
    }

    private static String normalizeAlign(String raw) {
        if (raw == null) {
            return ALIGN_CENTER;
        }
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        if (ALIGN_LEFT.equals(clean) || ALIGN_RIGHT.equals(clean)) {
            return clean;
        }
        return ALIGN_CENTER;
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
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
