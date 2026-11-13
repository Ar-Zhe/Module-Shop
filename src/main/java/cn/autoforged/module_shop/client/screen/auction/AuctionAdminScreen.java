package cn.autoforged.module_shop.client.screen.auction;

import cn.autoforged.module_shop.client.AuctionClient;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.Nav;
import cn.autoforged.module_shop.client.screen.SkiaDialog;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import io.github.humbleui.skija.Canvas;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

/**
 * 拍卖行管理（管理员）：开关、数量限制、手续费、价格范围、防截拍、时长选项、黑名单与物品限制；
 * 底部显示运行统计，可切到「异常记录」查看资金 / 结算异常。保存后写入 module_shop-common.toml（重启后仍生效）。
 * 服务端会再次校验并夹取每一项的取值范围。
 */
public final class AuctionAdminScreen extends SkiaDialog {
    /** 数值 / 文本字段：配置键 → 输入框。 */
    private final Map<String, SkiaTextField> fields = new LinkedHashMap<>();
    private final Map<String, Boolean> toggles = new LinkedHashMap<>();
    private final ScrollController logScroll = new ScrollController();
    private int seen = -1;
    private boolean showLog;

    private static final String[] NUMBERS = {"maxGlobal", "maxPerPlayer", "feeFixed", "feePercent", "saleFeePercent",
            "minIncrement", "minStart", "maxStart", "antiSnipeSeconds", "antiSnipeExtension", "antiSnipeMax", "endingSoon"};
    private static final String[] SWITCHES = {"enabled", "allowBuyout", "antiSnipe", "allowBound", "allowCustomData",
            "blockCreative"};

    public AuctionAdminScreen() {
        super(Component.translatable("gui.module_shop.auction.admin_title"));
    }

    @Override
    protected void initSkia() {
        fields.clear();
        for (String key : NUMBERS) {
            fields.put(key, field(20, ""));
        }
        fields.put("durations", field(200, "30,60,180"));
        fields.put("blacklist", field(2000, "minecraft:bedrock,#c:ores,somemod:*"));
        seen = -1;
        AuctionClient.send("admin_auction_config", new CompoundTag());
    }

    private void load(CompoundTag t) {
        for (String key : NUMBERS) {
            Tag v = t.get(key);
            String text = v == null ? "" : v.getAsString();
            if (text.endsWith(".0")) {
                text = text.substring(0, text.length() - 2);
            }
            fields.get(key).setValue(text);
        }
        fields.get("durations").setValue(t.getString("durations"));
        fields.get("blacklist").setValue(t.getString("blacklist"));
        for (String key : SWITCHES) {
            toggles.put(key, t.getBoolean(key));
        }
    }

    private void save() {
        CompoundTag t = new CompoundTag();
        for (String key : NUMBERS) {
            String s = fields.get(key).value().trim();
            if (key.endsWith("Percent")) {
                try {
                    t.putDouble(key, Double.parseDouble(s));
                } catch (NumberFormatException e) {
                    t.putDouble(key, 0.0D);
                }
            } else if (key.equals("feeFixed") || key.equals("minIncrement") || key.equals("minStart")
                    || key.equals("maxStart")) {
                t.putLong(key, Math.max(0L, AuctionUi.parse(s)));
            } else {
                t.putInt(key, (int) Math.max(0L, Math.min(Integer.MAX_VALUE, AuctionUi.parse(s))));
            }
        }
        t.putString("durations", fields.get("durations").value());
        t.putString("blacklist", fields.get("blacklist").value());
        for (String key : SWITCHES) {
            t.putBoolean(key, toggles.getOrDefault(key, false));
        }
        AuctionClient.send("admin_auction_config_set", t);
    }

    @Override
    protected void paint(Canvas c) {
        CompoundTag data = AuctionClient.admin;
        if (data != null && AuctionClient.adminSeq != seen) {
            seen = AuctionClient.adminSeq;
            load(data);
        }
        float w = Math.min(420.0F, this.width - 16.0F);
        float h = Math.min(272.0F, this.height - 16.0F);
        float x = Math.round((this.width - w) / 2.0F);
        float y = Math.round((this.height - h) / 2.0F);
        panel(c, x, y, w, h, this.title.getString());
        float ix = x + PAD;
        float iy = y + HEADER_H + 6.0F;
        float iw = w - PAD * 2;
        float bottom = y + h - PAD;
        if (data == null) {
            for (SkiaTextField f : fields.values()) {
                f.visible(false);
            }
            center(c, AuctionUi.tr("gui.module_shop.loading"), ix, iy + 40, iw, IOStyle.TEXT_2);
            return;
        }
        if (showLog) {
            for (SkiaTextField f : fields.values()) {
                f.visible(false);
            }
            var list = data.getList("anomalies", Tag.TAG_STRING);
            float lh = bottom - iy - 24;
            float row = 11.0F;
            if (list.isEmpty()) {
                center(c, AuctionUi.tr("gui.module_shop.auction.admin_no_anomaly"), ix, iy + lh / 2, iw, IOStyle.GREEN);
            } else {
                float oy = beginList(c, logScroll, ix, iy, iw, lh, list.size() * row);
                for (int i = 0; i < list.size(); i++) {
                    small(c, list.getString(i), ix + 2, oy + i * row + 5, iw - 10, IOStyle.RED);
                }
                endList(c);
            }
            button(c, "back_form", AuctionUi.tr("gui.module_shop.auction.admin_back_form"), ix, bottom - 18, 90, 18,
                    Button.Kind.NORMAL, true, () -> showLog = false);
            button(c, "close", AuctionUi.tr("gui.module_shop.back"), ix + iw - 70, bottom - 18, 70, 18,
                    Button.Kind.NORMAL, true, this::onClose);
            return;
        }
        // 左两列：数值
        float colW = (iw - 8) / 3.0F;
        float labelW = colW * 0.58F;
        for (int i = 0; i < NUMBERS.length; i++) {
            int col = i / 6;
            int r = i % 6;
            float fx = ix + col * (colW + 4);
            float fy = iy + r * 19;
            small(c, AuctionUi.tr("gui.module_shop.auction.cfg." + NUMBERS[i]), fx, fy + 8, labelW - 2, IOStyle.TEXT_2);
            fields.get(NUMBERS[i]).bounds(fx + labelW, fy, colW - labelW, 16).visible(true);
        }
        // 第三列：开关
        float sx = ix + 2 * (colW + 4);
        for (int i = 0; i < SWITCHES.length; i++) {
            String key = SWITCHES[i];
            float fy = iy + i * 19;
            small(c, AuctionUi.tr("gui.module_shop.auction.cfg." + key), sx, fy + 8, colW - 30, IOStyle.TEXT_2);
            toggle(c, key, sx + colW - 24, fy + 2, toggles.getOrDefault(key, false),
                    () -> toggles.put(key, !toggles.getOrDefault(key, false)));
        }
        float ty = iy + 6 * 19 + 4;
        small(c, AuctionUi.tr("gui.module_shop.auction.cfg.durations"), ix, ty + 8, 90, IOStyle.TEXT_2);
        fields.get("durations").bounds(ix + 92, ty, iw - 92, 16).visible(true);
        ty += 19;
        small(c, AuctionUi.tr("gui.module_shop.auction.cfg.blacklist"), ix, ty + 8, 90, IOStyle.TEXT_2);
        fields.get("blacklist").bounds(ix + 92, ty, iw - 92, 16).visible(true);
        ty += 22;
        small(c, AuctionUi.tr("gui.module_shop.auction.cfg_hint"), ix, ty + 4, iw, IOStyle.TEXT_3);
        ty += 13;
        small(c, AuctionUi.tr("gui.module_shop.auction.admin_stats", data.getInt("statActive"),
                AuctionUi.money(data.getLong("statEscrow")), data.getInt("statHistory"), data.getInt("statCorrupt")),
                ix, ty + 4, iw, IOStyle.TEXT_2);
        int anomalies = data.getList("anomalies", Tag.TAG_STRING).size();
        button(c, "log", AuctionUi.tr("gui.module_shop.auction.admin_anomalies", anomalies), ix, bottom - 18, 110, 18,
                anomalies > 0 ? Button.Kind.DANGER : Button.Kind.NORMAL, true, () -> showLog = true);
        button(c, "close", AuctionUi.tr("gui.module_shop.back"), ix + iw - 150, bottom - 18, 70, 18,
                Button.Kind.NORMAL, true, this::onClose);
        button(c, "save", AuctionUi.tr("gui.module_shop.auction.admin_save"), ix + iw - 76, bottom - 18, 76, 18,
                Button.Kind.ACCENT, true, this::save);
    }

    @Override
    public void onClose() {
        Nav.back();
    }
}
