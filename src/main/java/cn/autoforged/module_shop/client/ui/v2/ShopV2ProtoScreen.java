package cn.autoforged.module_shop.client.ui.v2;

import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.ShopScreen;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.ItemOverlay;
import cn.autoforged.module_shop.client.ui.render.SkiaFrame;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.data.Rarity;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.types.RRect;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

/**
 * UI v2 原型（只用于验证可行性，不接入正式商店）：购物城列表视图。
 *
 * <p>要验证的三件事：
 * <ol>
 *     <li><b>自有缩放</b>：界面不跟随 Minecraft 的 GUI 缩放，按窗口物理像素决定 1 单位 = 多少像素，
 *     Skija（{@link SkiaFrame#drawScaled}）、原版物品层（pose 缩放）与鼠标坐标三者换算一致；</li>
 *     <li><b>密度</b>：一屏约 20 行，每行带真实物品图标；</li>
 *     <li><b>分层</b>：悬停卡片 / 确认弹窗盖在物品图标之上（第二遍 Skija + 第二个物品层）。</li>
 * </ol>
 * 购物城有真实数据时用真实数据，否则用原版物品生成演示数据。
 */
public final class ShopV2ProtoScreen extends Screen {
    // ---- 版式（单位：UI 单位，1 单位 = ui 个物理像素）----
    private static final float DESIGN_W = 740.0F;
    private static final float DESIGN_H = 400.0F;
    private static final float TOP = 22.0F;
    private static final float RAIL = 76.0F;
    private static final float TB = 24.0F;
    private static final float FILT = 84.0F;
    private static final float HEAD = 15.0F;
    private static final float ROW = 15.0F;
    private static final float FOOT = 17.0F;
    private static final float ICON = 12.0F;
    private static final float FS = 7.0F;
    private static final float FS_S = 5.75F;
    private static final float FS_T = 9.5F;
    private static final float R = 3.5F;

    /** 一行商品（真实数据来自 MallView；演示数据由原版物品生成）。 */
    private record Row(ItemStack stack, String name, long price, long oldPrice, Rarity rarity, int stock, int max,
                       String mod, String limit, boolean fav) {
    }

    private final List<Row> rows = new ArrayList<>();
    private final Map<String, Integer> mods = new LinkedHashMap<>();
    private final ItemOverlay items = new ItemOverlay();
    private final ItemOverlay topItems = new ItemOverlay();
    private boolean demo;
    private float ui = 2.0F;
    private float vw;
    private float vh;
    private float px;
    private float py;
    private float pw;
    private float ph;
    private float mx;
    private float my;
    private int scroll;
    private int hoverRow = -1;
    private boolean hoverName;
    private Row dialog;
    private int qty = 1;
    private final List<float[]> buyRects = new ArrayList<>();
    private float[] cancelRect;
    private float[] confirmRect;
    private float[] dialogRect;

    // ---- 测试钩子（UiV2Tour 用）----
    Float forceMx;
    Float forceMy;
    /** 最近 60 帧的平均绘制耗时（毫秒）。 */
    double avgFrameMs;
    private long frameNanosSum;
    private int frameCount;

    public ShopV2ProtoScreen() {
        super(Component.literal("Module Shop UI v2 原型"));
    }

    @Override
    protected void init() {
        var win = this.minecraft.getWindow();
        float fw = win.getWidth();
        float fh = win.getHeight();
        float s = Math.min(fw * 0.94F / DESIGN_W, fh * 0.92F / DESIGN_H);
        // 0.25 步进，至少 1：避免奇怪的小数缩放；窗口太小时退到 1 单位 = 1 像素，面板随窗口收窄。
        this.ui = Math.max(1.0F, (float) Math.floor(s * 4.0F) / 4.0F);
        this.vw = fw / this.ui;
        this.vh = fh / this.ui;
        this.pw = Math.min(this.vw * 0.96F, 800.0F);
        this.ph = Math.min(this.vh * 0.94F, 440.0F);
        this.px = (float) Math.floor((this.vw - this.pw) / 2.0F);
        this.py = (float) Math.floor((this.vh - this.ph) / 2.0F);
        buildRows();
        this.scroll = Math.min(this.scroll, maxScroll());
    }

    // =========================================================== 数据

    private void buildRows() {
        this.rows.clear();
        this.mods.clear();
        List<ClientShopData.MallView> real = ShopScreen.mallFiltered();
        this.demo = real.isEmpty();
        if (!this.demo) {
            for (ClientShopData.MallView v : real) {
                String mod = ShopScreen.mallModName(v.namespace());
                this.rows.add(new Row(v.stack, v.stack.getHoverName().getString(), v.price, 0L, v.rarity, v.stock, -1,
                        mod, "", false));
            }
        } else {
            int i = 0;
            for (Item item : BuiltInRegistries.ITEM) {
                if (item == Items.AIR || (i++ % 7) != 3 || this.rows.size() >= 160) {
                    continue;
                }
                ItemStack stack = new ItemStack(item);
                int h = Math.abs(BuiltInRegistries.ITEM.getKey(item).hashCode());
                Rarity rarity = h % 23 == 0 ? Rarity.LEGENDARY : h % 9 == 0 ? Rarity.EPIC : h % 4 == 0 ? Rarity.RARE : Rarity.COMMON;
                long price = switch (rarity) {
                    case LEGENDARY -> 2000 + h % 9000;
                    case EPIC -> 400 + h % 1200;
                    case RARE -> 60 + h % 300;
                    case COMMON -> 1 + h % 60;
                };
                long old = h % 11 == 0 ? price * 5 / 4 : 0L;
                int max = rarity == Rarity.COMMON ? 200 + h % 800 : 5 + h % 40;
                int stock = h % 17 == 0 ? 0 : h % 5 == 0 ? -1 : max * (10 + h % 90) / 100;
                String mod = h % 6 == 0 ? "机械动力" : h % 13 == 0 ? "农夫乐事" : "原版";
                String limit = rarity == Rarity.LEGENDARY ? "每周 1" : rarity == Rarity.EPIC ? "每日 " + (1 + h % 3) : "";
                this.rows.add(new Row(stack, stack.getHoverName().getString(), price, old, rarity, stock, max, mod, limit,
                        h % 8 == 0));
            }
        }
        for (Row r : this.rows) {
            this.mods.merge(r.mod(), 1, Integer::sum);
        }
    }

    // =========================================================== 几何

    private float tableX() {
        return this.px + RAIL + FILT;
    }

    private float tableW() {
        return this.pw - RAIL - FILT;
    }

    private float listTop() {
        return this.py + TOP + TB + HEAD;
    }

    private int visibleRows() {
        return Math.max(1, (int) Math.floor((this.py + this.ph - FOOT - listTop()) / ROW));
    }

    private int maxScroll() {
        return Math.max(0, this.rows.size() - visibleRows());
    }

    /** 列：图标、名称（弹性）、模组、收藏、单价、库存、限购、操作。返回每列左边界与宽度。 */
    private float[][] columns() {
        float[] fixed = {16, 0, 62, 26, 70, 84, 52, 40};
        float pad = 8.0F;
        float sum = 0;
        for (float f : fixed) {
            sum += f;
        }
        float gap = 6.0F;
        float flex = tableW() - pad * 2 - sum - gap * (fixed.length - 1);
        float x = tableX() + pad;
        float[][] out = new float[fixed.length][2];
        for (int i = 0; i < fixed.length; i++) {
            float w = i == 1 ? Math.max(60, flex) : fixed[i];
            out[i][0] = x;
            out[i][1] = w;
            x += w + gap;
        }
        return out;
    }

    private static boolean in(float[] r, float x, float y) {
        return r != null && x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    // =========================================================== 绘制

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long t0 = System.nanoTime();
        super.render(g, mouseX, mouseY, partialTick);
        IOStyle.tickTheme();
        double gs = this.minecraft.getWindow().getGuiScale();
        float k = (float) (this.ui / gs);
        this.mx = this.forceMx != null ? this.forceMx : (float) (mouseX * gs / this.ui);
        this.my = this.forceMy != null ? this.forceMy : (float) (mouseY * gs / this.ui);
        if (!AtomUi.available()) {
            g.drawCenteredString(this.font, "AtomLib 不可用：UI v2 原型无法绘制", this.width / 2, this.height / 2, 0xFFFF6060);
            return;
        }
        computeHover();
        this.items.begin(this.mx, this.my, this.dialog == null);
        SkiaFrame.drawScaled(g, this.ui, this::drawMain);
        flushItems(g, this.items, k);
        boolean card = this.dialog == null && this.hoverRow >= 0 && this.hoverName;
        if (this.dialog != null || card) {
            this.topItems.begin(this.mx, this.my, true);
            SkiaFrame.drawScaled(g, this.ui, c -> {
                if (this.dialog != null) {
                    drawDialog(c);
                } else {
                    drawHoverCard(c, this.rows.get(this.hoverRow));
                }
            });
            flushItems(g, this.topItems, k);
        }
        ItemStack hovered = this.dialog != null ? this.topItems.hovered() : this.items.hovered();
        if (!hovered.isEmpty() && this.forceMx == null) {
            g.renderTooltip(this.font, hovered, mouseX, mouseY);
        }
        this.frameNanosSum += System.nanoTime() - t0;
        if (++this.frameCount >= 60) {
            this.avgFrameMs = this.frameNanosSum / 1_000_000.0 / this.frameCount;
            this.frameNanosSum = 0;
            this.frameCount = 0;
        }
    }

    private static void flushItems(GuiGraphics g, ItemOverlay layer, float k) {
        g.pose().pushPose();
        g.pose().scale(k, k, 1.0F);
        layer.flush(g, net.minecraft.client.Minecraft.getInstance().font);
        g.pose().popPose();
    }

    private void computeHover() {
        this.hoverRow = -1;
        this.hoverName = false;
        if (this.dialog != null) {
            return;
        }
        float top = listTop();
        if (this.mx < tableX() || this.mx >= tableX() + tableW() || this.my < top) {
            return;
        }
        int idx = (int) Math.floor((this.my - top) / ROW);
        if (idx >= 0 && idx < visibleRows() && this.scroll + idx < this.rows.size()) {
            this.hoverRow = this.scroll + idx;
            float[][] col = columns();
            this.hoverName = this.mx >= col[1][0] && this.mx < col[1][0] + col[1][1];
        }
    }

    private void drawMain(Canvas c) {
        Draw.outerShadow(c, this.px, this.py, this.pw, this.ph, 6.0F, 12.0F, 3.0F, IOStyle.SHADOW_TINT);
        int saved = c.save();
        c.clipRRect(RRect.makeXYWH(this.px, this.py, this.pw, this.ph, 6.0F), true);
        Draw.fill(c, this.px, this.py, this.pw, this.ph, 0, IOStyle.CONTENT);
        drawTop(c);
        drawRail(c);
        drawToolbar(c);
        drawFilter(c);
        drawTable(c);
        c.restoreToCount(saved);
        Draw.stroke(c, this.px, this.py, this.pw, this.ph, 6.0F, 1.0F / this.ui * 1.0F + 0.5F, IOStyle.BORDER_DIM);
    }

    private void text(Canvas c, String s, boolean bold, float size, int color, float x, float cy, float w, Text.Align a) {
        Text.drawLine(c, s, bold ? AtomUi.BOLD : AtomUi.REGULAR, size, color, x, cy, w, a);
    }

    private void drawTop(Canvas c) {
        float x = this.px;
        float y = this.py;
        Draw.fill(c, x, y, this.pw, TOP, 0, IOStyle.SIDEBAR);
        Draw.hairline(c, x, y + TOP - 1, this.pw, IOStyle.BORDER_DIM);
        float cy = y + TOP / 2.0F;
        Draw.gradientH(c, x + 6, cy - 6, 12, 12, 3, IOStyle.BLUE, IOStyle.ACCENT_2);
        text(c, "心", true, 7.0F, 0xFFFFFFFF, x + 6, cy, 12, Text.Align.CENTER);
        String title = ClientShopData.shopTitle == null || ClientShopData.shopTitle.isBlank() ? "模块商店" : ClientShopData.shopTitle;
        float tw = Text.width(title + " › ", AtomUi.REGULAR, FS);
        text(c, title + " › ", false, FS, IOStyle.TEXT_3, x + 23, cy, 200, Text.Align.LEFT);
        text(c, "购物城", true, FS, IOStyle.TEXT, x + 23 + tw, cy, 80, Text.Align.LEFT);
        float sx = x + 23 + tw + 40;
        Draw.fill(c, sx, cy - 7.5F, 170, 15, R, IOStyle.CARD);
        Draw.stroke(c, sx, cy - 7.5F, 170, 15, R, 0.5F, IOStyle.BORDER_DIM);
        text(c, "搜索物品 / 玩家 / 功能", false, FS - 0.5F, IOStyle.TEXT_3, sx + 7, cy, 120, Text.Align.LEFT);
        Draw.stroke(c, sx + 140, cy - 4.5F, 24, 9, 2, 0.5F, IOStyle.BORDER);
        text(c, "Ctrl K", false, 5.0F, IOStyle.TEXT_2, sx + 140, cy, 24, Text.Align.CENTER);

        // 右侧：4 个图标按钮 + 3 个指标
        float bx = x + this.pw - 6 - 15;
        String[] glyphs = {"×", "◐", "✉", "≡"};
        for (int i = 0; i < glyphs.length; i++) {
            Draw.fill(c, bx, cy - 7.5F, 15, 15, R, IOStyle.CARD);
            Draw.stroke(c, bx, cy - 7.5F, 15, 15, R, 0.5F, IOStyle.BORDER_DIM);
            if (i == 0) {
                Draw.cross(c, bx + 7.5F, cy, 2.6F, 1.0F, IOStyle.TEXT_2);
            } else {
                text(c, glyphs[i], false, 7.5F, IOStyle.TEXT_2, bx, cy, 15, Text.Align.CENTER);
            }
            if (i == 2) {
                Draw.fill(c, bx + 10, cy - 10, 8, 8, 4, IOStyle.RED);
                text(c, "3", true, 5.0F, 0xFFFFFFFF, bx + 10, cy - 6, 8, Text.Align.CENTER);
            }
            bx -= 19;
        }
        String[][] stats = {
                {"本月支出", ClientShopData.billUpdatedAt > 0 ? "−" + fmt(ClientShopData.billMonthOut) : "—"},
                {"今日支出", ClientShopData.billUpdatedAt > 0 ? "−" + fmt(ClientShopData.billTodayOut) : "—"},
                {"余额", fmt(ClientShopData.balance)}};
        float rx = bx + 15 - 6;
        for (String[] st : stats) {
            float w = Math.max(Text.width(st[0], AtomUi.REGULAR, FS_S), Text.width(st[1], AtomUi.BOLD, FS + 0.5F)) + 12;
            rx -= w;
            Draw.fill(c, rx, cy - 7, 0.5F, 14, 0, IOStyle.BORDER_DIM);
            text(c, st[0], false, FS_S, IOStyle.TEXT_3, rx + 6, cy - 3.6F, w, Text.Align.LEFT);
            boolean bal = "余额".equals(st[0]);
            text(c, st[1], true, FS + 0.5F, bal ? IOStyle.TEXT : IOStyle.RED, rx + 6, cy + 3.6F, w, Text.Align.LEFT);
        }
    }

    private static final String[][] NAV = {
            {"商店"}, {"每日商店", "刷新 05:12:40", ""}, {"每日收购", "可卖 7 种 · +3,486", "g"}, {"购物城", "", ""},
            {"出货箱", "待结算 +740 · 21:00", "g"},
            {"市场"}, {"拍卖会", "2 场即将结束", "r", "2"}, {"玩家摊位", "36 个摊位 · 412 件", ""}, {"玩家求购", "12 条悬赏", ""},
            {"我的"}, {"邮箱", "2 封带附件", "", "3"}, {"签到", "今日未签 · 连签 12", "r"}, {"货币兑换", "1 钻石 = 100", ""},
            {"账单", "今日 +2,115 / −1,376", ""},
            {"社交"}, {"交易", "在线 18 人", ""}, {"转账", "今日已转 2 次", ""},
            {"扩展"}, {"红包", "3 个可抢", "g", "", "x"}, {"股市", "指数 1,040.8 ↑0.99%", "r", "", "x"},
            {"遗迹盲仓", "下界堡垒 · 匹配 2/4", "o", "", "x"}};

    private void drawRail(Canvas c) {
        float x = this.px;
        float y = this.py + TOP;
        float h = this.ph - TOP;
        Draw.fill(c, x, y, RAIL, h, 0, IOStyle.SIDEBAR);
        Draw.fill(c, x + RAIL - 0.5F, y, 0.5F, h, 0, IOStyle.BORDER_DIM);
        float cy = y + 5;
        for (String[] n : NAV) {
            if (cy > this.py + this.ph - 8) {
                break;
            }
            if (n.length == 1) {
                text(c, n[0], false, FS_S, IOStyle.TEXT_3, x + 7, cy + 4, 60, Text.Align.LEFT);
                cy += 10;
                continue;
            }
            boolean sel = "购物城".equals(n[0]);
            String status = sel ? this.rows.size() + " 件" + (this.demo ? " · 演示数据" : "") : n[1];
            float rh = 18.5F;
            if (sel) {
                Draw.fill(c, x + 3, cy, RAIL - 6, rh, 3, Draw.alpha(IOStyle.BLUE, 0.18F));
                Draw.fill(c, x, cy + 4, 1.5F, rh - 8, 1, IOStyle.BLUE);
            }
            text(c, n[0], sel, FS, sel ? IOStyle.TEXT : IOStyle.TEXT_2, x + 8, cy + 6, RAIL - 20, Text.Align.LEFT);
            int sc = switch (n[2]) {
                case "g" -> IOStyle.GREEN;
                case "r" -> IOStyle.RED;
                case "o" -> IOStyle.ORANGE;
                default -> IOStyle.TEXT_3;
            };
            text(c, status, false, FS_S - 0.25F, sc, x + 8, cy + 13.5F, RAIL - 12, Text.Align.LEFT);
            if (n.length > 3 && !n[3].isEmpty()) {
                Draw.fill(c, x + RAIL - 16, cy + 2.5F, 9, 8, 4, IOStyle.RED);
                text(c, n[3], true, 5.0F, 0xFFFFFFFF, x + RAIL - 16, cy + 6.5F, 9, Text.Align.CENTER);
            } else if (n.length > 4) {
                Draw.stroke(c, x + RAIL - 22, cy + 2.5F, 16, 8, 1.5F, 0.5F, IOStyle.BORDER);
                text(c, "附属", false, 4.5F, IOStyle.TEXT_3, x + RAIL - 22, cy + 6.5F, 16, Text.Align.CENTER);
            }
            cy += rh + 0.5F;
        }
    }

    private void drawToolbar(Canvas c) {
        float x = this.px + RAIL;
        float y = this.py + TOP;
        float w = this.pw - RAIL;
        float cy = y + TB / 2.0F;
        Draw.hairline(c, x, y + TB - 1, w, IOStyle.BORDER_DIM);
        text(c, "购物城", true, FS_T, IOStyle.TEXT, x + 8, cy, 60, Text.Align.LEFT);
        float sx = x + 50;
        Draw.fill(c, sx, cy - 7.5F, 130, 15, R, IOStyle.CARD);
        Draw.stroke(c, sx, cy - 7.5F, 130, 15, R, 0.75F, IOStyle.BLUE);
        text(c, "搜索名称 / 模组 / id", false, FS - 0.5F, IOStyle.TEXT_3, sx + 7, cy, 110, Text.Align.LEFT);
        // 分段：全部 / 收藏 / 最近购买
        String[] seg = {"全部 " + this.rows.size(), "收藏", "最近购买"};
        float gx = sx + 136;
        float total = 4;
        float[] ws = new float[seg.length];
        for (int i = 0; i < seg.length; i++) {
            ws[i] = Text.width(seg[i], AtomUi.REGULAR, FS - 0.5F) + 12;
            total += ws[i];
        }
        Draw.fill(c, gx, cy - 7.5F, total, 15, R, IOStyle.CARD);
        float ox = gx + 2;
        for (int i = 0; i < seg.length; i++) {
            if (i == 0) {
                Draw.fill(c, ox, cy - 5.5F, ws[i], 11, 2.5F, IOStyle.ROW_HOVER);
            }
            text(c, seg[i], i == 0, FS - 0.5F, i == 0 ? IOStyle.TEXT : IOStyle.TEXT_2, ox, cy, ws[i], Text.Align.CENTER);
            ox += ws[i];
        }
        if (this.demo) {
            float dx = gx + total + 6;
            Draw.fill(c, dx, cy - 5, 40, 10, 2, Draw.alpha(IOStyle.ORANGE, 0.18F));
            text(c, "演示数据", true, 5.25F, IOStyle.ORANGE, dx, cy, 40, Text.Align.CENTER);
        }
        double gs = this.minecraft.getWindow().getGuiScale();
        String meta = "原型 · UI ×" + this.ui + " · GUI 缩放 " + (int) gs + " · 每行 " + (int) (ROW * this.ui) + "px";
        text(c, meta, false, FS_S, IOStyle.TEXT_3, x + w - 260, cy, 252, Text.Align.RIGHT);
    }

    private void drawFilter(Canvas c) {
        float x = this.px + RAIL;
        float y = this.py + TOP + TB;
        float h = this.ph - TOP - TB;
        Draw.fill(c, x + FILT - 0.5F, y, 0.5F, h, 0, IOStyle.BORDER_DIM);
        float cy = y + 9;
        text(c, "模组分类", false, FS_S, IOStyle.TEXT_3, x + 7, cy, 70, Text.Align.LEFT);
        cy += 6;
        List<String[]> list = new ArrayList<>();
        list.add(new String[]{"全部", String.valueOf(this.rows.size())});
        this.mods.forEach((k, v) -> list.add(new String[]{k, String.valueOf(v)}));
        for (int i = 0; i < list.size() && i < 8; i++) {
            if (i == 0) {
                Draw.fill(c, x + 3, cy, FILT - 6, 13.5F, 3, Draw.alpha(IOStyle.BLUE, 0.18F));
            }
            text(c, list.get(i)[0], i == 0, FS - 0.25F, i == 0 ? IOStyle.TEXT : IOStyle.TEXT_2, x + 8, cy + 6.75F, 50, Text.Align.LEFT);
            text(c, list.get(i)[1], false, FS_S, IOStyle.TEXT_3, x + 8, cy + 6.75F, FILT - 16, Text.Align.RIGHT);
            cy += 14;
        }
        cy += 4;
        Draw.hairline(c, x + 5, cy, FILT - 10, IOStyle.BORDER_DIM);
        cy += 8;
        text(c, "筛选", false, FS_S, IOStyle.TEXT_3, x + 7, cy, 70, Text.Align.LEFT);
        cy += 6;
        String[] checks = {"只看有货", "只看折扣", "买得起", "只看收藏"};
        for (int i = 0; i < checks.length; i++) {
            boolean on = i == 0;
            if (on) {
                Draw.fill(c, x + 8, cy + 3.5F, 7, 7, 1.5F, IOStyle.BLUE);
                text(c, "✓", true, 5.5F, 0xFFFFFFFF, x + 8, cy + 7, 7, Text.Align.CENTER);
            } else {
                Draw.stroke(c, x + 8, cy + 3.5F, 7, 7, 1.5F, 0.5F, IOStyle.BORDER);
            }
            text(c, checks[i], false, FS - 0.25F, IOStyle.TEXT_2, x + 19, cy + 7, 60, Text.Align.LEFT);
            cy += 14;
        }
    }

    private void drawTable(Canvas c) {
        float x = tableX();
        float w = tableW();
        float hy = this.py + TOP + TB;
        float[][] col = columns();
        Draw.fill(c, x, hy, w, HEAD, 0, IOStyle.SIDEBAR);
        Draw.hairline(c, x, hy + HEAD - 0.5F, w, IOStyle.BORDER_DIM);
        String[] heads = {"", "物品", "模组", "收藏", "单价 ▲", "库存", "限购", ""};
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.CENTER, Text.Align.RIGHT,
                Text.Align.RIGHT, Text.Align.RIGHT, Text.Align.RIGHT};
        for (int i = 0; i < heads.length; i++) {
            text(c, heads[i], i == 4, FS_S + 0.25F, i == 4 ? IOStyle.TEXT : IOStyle.TEXT_3, col[i][0], hy + HEAD / 2, col[i][1], al[i]);
        }
        this.buyRects.clear();
        float top = listTop();
        int vis = visibleRows();
        for (int v = 0; v < vis; v++) {
            int idx = this.scroll + v;
            if (idx >= this.rows.size()) {
                break;
            }
            Row r = this.rows.get(idx);
            float ry = top + v * ROW;
            float cy = ry + ROW / 2.0F;
            boolean hov = idx == this.hoverRow;
            boolean sold = r.stock() == 0;
            if (hov) {
                Draw.fill(c, x, ry, w, ROW, 0, IOStyle.ROW_HOVER);
                Draw.fill(c, x, ry, 1.5F, ROW, 0, IOStyle.BLUE);
            } else if (v % 2 == 1) {
                Draw.fill(c, x, ry, w, ROW, 0, Draw.alpha(IOStyle.CARD, 0.55F));
            }
            Draw.hairline(c, x, ry + ROW - 0.5F, w, Draw.alpha(IOStyle.BORDER_DIM, 0.6F));
            // 稀有度细条
            Draw.fill(c, col[0][0] - 5, ry + 3, 1.5F, ROW - 6, 0.75F, IOStyle.rarity(r.rarity()));
            this.items.add(r.stack(), col[0][0], ry + (ROW - ICON) / 2.0F, ICON, true);
            int nameColor = sold ? IOStyle.TEXT_3 : IOStyle.TEXT;
            float nw = Text.drawLine(c, r.name(), AtomUi.REGULAR, FS, nameColor, col[1][0], cy, col[1][1] - 40, Text.Align.LEFT);
            float tx = col[1][0] + nw + 4;
            if (r.oldPrice() > 0) {
                tx += tag(c, "-" + (100 - r.price() * 100 / r.oldPrice()) + "%", IOStyle.RED, tx, cy);
            }
            if (!r.limit().isEmpty()) {
                tag(c, "限购", IOStyle.EPIC, tx, cy);
            }
            text(c, r.mod(), false, FS_S + 0.25F, IOStyle.TEXT_3, col[2][0], cy, col[2][1], Text.Align.LEFT);
            text(c, r.fav() ? "★" : "☆", false, FS, r.fav() ? IOStyle.ORANGE : IOStyle.TEXT_3, col[3][0], cy, col[3][1], Text.Align.CENTER);
            if (sold) {
                text(c, "售罄", false, FS, IOStyle.TEXT_3, col[4][0], cy, col[4][1], Text.Align.RIGHT);
            } else {
                float pwid = Text.drawLine(c, fmt(r.price()), AtomUi.BOLD, FS, IOStyle.ORANGE, col[4][0], cy, col[4][1], Text.Align.RIGHT);
                if (r.oldPrice() > 0) {
                    String o = fmt(r.oldPrice());
                    float ow = Text.width(o, AtomUi.REGULAR, FS_S);
                    float ox = col[4][0] + col[4][1] - pwid - 4 - ow;
                    text(c, o, false, FS_S, IOStyle.TEXT_3, ox, cy, ow + 1, Text.Align.LEFT);
                    Draw.line(c, ox, cy, ox + ow, cy, 0.6F, IOStyle.TEXT_3);
                }
            }
            if (r.stock() < 0) {
                text(c, "∞", false, FS, IOStyle.TEXT_2, col[5][0], cy, col[5][1], Text.Align.RIGHT);
            } else if (r.max() > 0) {
                float bw = 22;
                float pct = Math.min(1.0F, r.stock() / (float) r.max());
                int bc = pct < 0.2F ? IOStyle.RED : pct < 0.5F ? IOStyle.ORANGE : IOStyle.GREEN;
                Draw.fill(c, col[5][0] + col[5][1] - bw, cy - 1, bw, 2, 1, IOStyle.BORDER_DIM);
                Draw.fill(c, col[5][0] + col[5][1] - bw, cy - 1, bw * pct, 2, 1, bc);
                text(c, r.stock() + "/" + r.max(), true, FS_S + 0.5F, IOStyle.TEXT, col[5][0], cy, col[5][1] - bw - 4, Text.Align.RIGHT);
            } else {
                text(c, String.valueOf(r.stock()), true, FS_S + 0.5F, IOStyle.TEXT, col[5][0], cy, col[5][1], Text.Align.RIGHT);
            }
            text(c, r.limit().isEmpty() ? "—" : r.limit(), false, FS_S + 0.5F, r.limit().isEmpty() ? IOStyle.TEXT_3 : IOStyle.TEXT_2,
                    col[6][0], cy, col[6][1], Text.Align.RIGHT);
            float bw = 30;
            float bx = col[7][0] + col[7][1] - bw;
            float[] rect = {bx, cy - 5.5F, bw, 11, idx};
            boolean bh = in(rect, this.mx, this.my) || (hov && this.forceMx != null);
            if (sold) {
                Draw.stroke(c, bx, cy - 5.5F, bw, 11, 2.5F, 0.5F, IOStyle.BORDER_DIM);
                text(c, "售罄", false, FS_S, IOStyle.TEXT_3, bx, cy, bw, Text.Align.CENTER);
            } else {
                if (hov) {
                    Draw.gradientH(c, bx, cy - 5.5F, bw, 11, 2.5F, bh ? IOStyle.BLUE_HOVER : IOStyle.BLUE, IOStyle.ACCENT_2);
                } else {
                    Draw.fill(c, bx, cy - 5.5F, bw, 11, 2.5F, IOStyle.GRAY_BTN);
                    Draw.stroke(c, bx, cy - 5.5F, bw, 11, 2.5F, 0.5F, IOStyle.BORDER_DIM);
                }
                text(c, "购买", true, FS_S, hov ? 0xFFFFFFFF : IOStyle.TEXT, bx, cy, bw, Text.Align.CENTER);
                this.buyRects.add(rect);
            }
        }
        // 滚动条
        if (this.rows.size() > vis) {
            float trackH = vis * ROW;
            float th = Math.max(12, trackH * vis / this.rows.size());
            float ty = top + (trackH - th) * this.scroll / Math.max(1, maxScroll());
            Draw.fill(c, x + w - 3, ty, 2, th, 1, IOStyle.BORDER);
        }
        // 底栏
        float fy = this.py + this.ph - FOOT;
        Draw.fill(c, x, fy, w, FOOT, 0, IOStyle.SIDEBAR);
        Draw.hairline(c, x, fy, w, IOStyle.BORDER_DIM);
        int first = this.rows.isEmpty() ? 0 : this.scroll + 1;
        int last = Math.min(this.rows.size(), this.scroll + vis);
        text(c, "显示 " + first + "–" + last + " / " + this.rows.size() + " 件", false, FS_S + 0.25F, IOStyle.TEXT_2, x + 8, fy + FOOT / 2, 160, Text.Align.LEFT);
        text(c, "左键 购买 · 右键 输入数量 · 中键 收藏 · 滚轮 滚动", false, FS_S, IOStyle.TEXT_3, x + 8, fy + FOOT / 2, w - 16, Text.Align.RIGHT);
    }

    /** 小标签，返回占用宽度。 */
    private float tag(Canvas c, String s, int color, float x, float cy) {
        float w = Text.width(s, AtomUi.BOLD, 5.0F) + 5;
        Draw.fill(c, x, cy - 4.25F, w, 8.5F, 2, Draw.alpha(color, 0.2F));
        text(c, s, true, 5.0F, color, x, cy, w, Text.Align.CENTER);
        return w + 3;
    }

    private void drawHoverCard(Canvas c, Row r) {
        float w = 150;
        float h = 92;
        float x = Math.min(this.mx + 10, this.px + this.pw - w - 6);
        float y = Math.min(this.my + 8, this.py + this.ph - h - 6);
        int rc = IOStyle.rarity(r.rarity());
        Draw.shadow(c, x, y, w, h, 5, 8, 2, IOStyle.SHADOW_TINT);
        Draw.fill(c, x, y, w, h, 5, IOStyle.SHEET);
        Draw.stroke(c, x, y, w, h, 5, 0.75F, rc);
        Draw.fill(c, x + 7, y + 7, 20, 20, 3, IOStyle.CARD);
        this.topItems.add(r.stack(), x + 9, y + 9, 16, false);
        text(c, r.name(), true, FS + 0.5F, rc, x + 32, y + 13, w - 38, Text.Align.LEFT);
        text(c, r.mod() + " · " + BuiltInRegistries.ITEM.getKey(r.stack().getItem()), false, FS_S - 0.25F, IOStyle.TEXT_3,
                x + 32, y + 22, w - 38, Text.Align.LEFT);
        String[][] kv = {
                {"单价", fmt(r.price())},
                {"库存", r.stock() < 0 ? "不限" : r.max() > 0 ? r.stock() + " / " + r.max() : String.valueOf(r.stock())},
                {"限购", r.limit().isEmpty() ? "无" : r.limit()},
                {"买后余额", fmt(ClientShopData.balance - r.price())}};
        float ky = y + 36;
        for (String[] e : kv) {
            text(c, e[0], false, FS - 0.5F, IOStyle.TEXT_2, x + 8, ky, 60, Text.Align.LEFT);
            text(c, e[1], true, FS - 0.5F, "单价".equals(e[0]) ? IOStyle.ORANGE : IOStyle.TEXT, x + 8, ky, w - 16, Text.Align.RIGHT);
            ky += 10;
        }
        Draw.hairline(c, x + 8, y + h - 13, w - 16, IOStyle.BORDER_DIM);
        text(c, "左键买 1 · 右键输入数量 · 中键收藏", false, FS_S - 0.25F, IOStyle.TEXT_3, x + 8, y + h - 6.5F, w - 16, Text.Align.LEFT);
    }

    private void drawDialog(Canvas c) {
        Draw.fill(c, 0, 0, this.vw, this.vh, 0, 0x8C05070C);
        Row r = this.dialog;
        float w = 230;
        float h = 136;
        float x = (float) Math.floor((this.vw - w) / 2);
        float y = (float) Math.floor((this.vh - h) / 2);
        this.dialogRect = new float[]{x, y, w, h};
        Draw.shadow(c, x, y, w, h, 6, 14, 3, IOStyle.SHADOW_TINT);
        Draw.fill(c, x, y, w, h, 6, IOStyle.CONTENT);
        Draw.stroke(c, x, y, w, h, 6, 0.75F, IOStyle.BORDER);
        text(c, "确认购买", true, FS_T - 0.5F, IOStyle.TEXT, x + 10, y + 12, 120, Text.Align.LEFT);
        Draw.hairline(c, x, y + 23, w, IOStyle.BORDER_DIM);
        Draw.fill(c, x + 10, y + 30, 22, 22, 3, IOStyle.CARD);
        this.topItems.add(r.stack(), x + 13, y + 33, 16, true);
        text(c, r.name(), true, FS, IOStyle.rarity(r.rarity()), x + 38, y + 36, w - 48, Text.Align.LEFT);
        text(c, r.mod() + " · 单价 " + fmt(r.price()), false, FS_S, IOStyle.TEXT_2, x + 38, y + 46, w - 48, Text.Align.LEFT);
        // 数量
        float qy = y + 60;
        Draw.fill(c, x + 10, qy, w - 20, 16, R, IOStyle.CARD);
        Draw.stroke(c, x + 10, qy, w - 20, 16, R, 0.75F, IOStyle.BLUE);
        text(c, "−", true, FS + 1, IOStyle.TEXT_2, x + 10, qy + 8, 18, Text.Align.CENTER);
        text(c, String.valueOf(this.qty), true, FS + 0.5F, IOStyle.TEXT, x + 10, qy + 8, w - 20, Text.Align.CENTER);
        text(c, "+", true, FS + 1, IOStyle.TEXT_2, x + w - 28, qy + 8, 18, Text.Align.CENTER);
        long total = r.price() * this.qty;
        text(c, "合计", false, FS - 0.5F, IOStyle.TEXT_2, x + 10, qy + 26, 60, Text.Align.LEFT);
        text(c, fmt(total), true, FS + 0.5F, IOStyle.ORANGE, x + 10, qy + 26, w - 20, Text.Align.RIGHT);
        text(c, "买后余额", false, FS - 0.5F, IOStyle.TEXT_2, x + 10, qy + 37, 60, Text.Align.LEFT);
        text(c, fmt(ClientShopData.balance - total), true, FS - 0.5F, IOStyle.TEXT, x + 10, qy + 37, w - 20, Text.Align.RIGHT);
        float by = y + h - 20;
        Draw.fill(c, x, by - 4, w, 24, 0, Draw.alpha(IOStyle.SIDEBAR, 0.9F));
        this.confirmRect = new float[]{x + w - 10 - 56, by, 56, 13};
        this.cancelRect = new float[]{x + w - 10 - 56 - 6 - 40, by, 40, 13};
        Draw.fill(c, this.cancelRect[0], by, 40, 13, 3, IOStyle.GRAY_BTN);
        Draw.stroke(c, this.cancelRect[0], by, 40, 13, 3, 0.5F, IOStyle.BORDER);
        text(c, "取消", true, FS_S + 0.25F, IOStyle.TEXT, this.cancelRect[0], by + 6.5F, 40, Text.Align.CENTER);
        Draw.gradientH(c, this.confirmRect[0], by, 56, 13, 3, IOStyle.BLUE, IOStyle.ACCENT_2);
        text(c, "确认购买", true, FS_S + 0.25F, 0xFFFFFFFF, this.confirmRect[0], by + 6.5F, 56, Text.Align.CENTER);
        text(c, "原型：确认不会真的购买", false, FS_S - 0.5F, IOStyle.TEXT_3, x + 10, by + 6.5F, 100, Text.Align.LEFT);
    }

    private static String fmt(long v) {
        return String.format("%,d", v);
    }

    // =========================================================== 输入

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double gs = this.minecraft.getWindow().getGuiScale();
        float x = (float) (mouseX * gs / this.ui);
        float y = (float) (mouseY * gs / this.ui);
        if (this.dialog != null) {
            if (in(this.cancelRect, x, y) || in(this.confirmRect, x, y) || !in(this.dialogRect, x, y)) {
                this.dialog = null;
            }
            return true;
        }
        for (float[] r : this.buyRects) {
            if (in(r, x, y)) {
                openDialog((int) r[4]);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = hasShiftDown() ? visibleRows() : 3;
        this.scroll = Math.max(0, Math.min(maxScroll(), this.scroll - (int) Math.signum(scrollY) * step));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && this.dialog != null) {
            this.dialog = null;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // =========================================================== 测试钩子

    void openDialog(int index) {
        if (index >= 0 && index < this.rows.size()) {
            this.dialog = this.rows.get(index);
            this.qty = 1;
        }
    }

    void closeDialog() {
        this.dialog = null;
    }

    /** 把（模拟的）鼠标放到第 visibleIndex 行的名称列上。 */
    void hoverRowName(int visibleIndex) {
        float[][] col = columns();
        this.forceMx = col[1][0] + 20;
        this.forceMy = listTop() + visibleIndex * ROW + ROW / 2.0F;
    }

    void clearHover() {
        this.forceMx = null;
        this.forceMy = null;
    }

    String report() {
        var win = this.minecraft.getWindow();
        return String.format("window=%dx%d guiScale=%d ui=%.2f panel=%.0fx%.0f units (%.0fx%.0f px) rows=%d visibleRows=%d demo=%s avgFrameMs=%.2f",
                win.getWidth(), win.getHeight(), (int) win.getGuiScale(), this.ui, this.pw, this.ph, this.pw * this.ui,
                this.ph * this.ui, this.rows.size(), visibleRows(), this.demo, this.avgFrameMs);
    }
}
