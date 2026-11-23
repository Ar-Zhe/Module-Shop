package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState.PhysTierView;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.shop.PageKit;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import io.github.humbleui.skija.Canvas;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 玩家实体货币阶梯兑换界面（继承 {@link SkiaDialog}，Forge 1.20.1）：
 * <ul>
 *     <li>顶部：背包实体货币折算总值、当前结算模式与各级货币持有量总览条；</li>
 *     <li>主体：相邻货币等级双向兑换卡片（如 {@code 10 × 泥土 ⇄ 1 × 铁}），
 *         支持一键 ×1 / ×10 / 最大可换 凑整（{@code up}）与拆零（{@code down}）；</li>
 *     <li>发包：{@code action = "phys_currency_convert"}（{@code tierIndex >= 1}, {@code direction}, {@code batches >= 1}）。</li>
 * </ul>
 */
public class PhysicalCurrencyExchangeScreen extends SkiaDialog {
    private static final float MAX_W = 420.0F;
    private static final float MAX_H = 262.0F;
    private static final long ACTION_COOLDOWN_MS = 120L;
    private static final long SYNC_TIMEOUT_MS = 3000L;

    private static int maxExchangeOutput() {
        return Math.max(1, Math.min(64000, ClientHomeCurrencyState.physMaxConvertItems));
    }

    private final ScrollController listScroll = new ScrollController();

    private long lastActionMs;
    private int lastSeenSnapshotVersion;
    private boolean waitingSync;
    private long waitingSinceMs;
    private String statusText = "";
    private int statusColor = IOStyle.TEXT_3;

    private int[] fbBackBtn;

    public PhysicalCurrencyExchangeScreen() {
        super(Component.literal(tr("gui.module_shop.phys_ex.title", "实体货币兑换")));
        this.lastSeenSnapshotVersion = ClientHomeCurrencyState.snapshotVersion;
    }

    private static String tr(String key, String fallback, Object... args) {
        return ClientHomeCurrencyState.tr(key, fallback, args);
    }

    @Override
    public void onClose() {
        Nav.back();
    }

    private void sendConvert(int tierIndex, String direction, int batches, String desc) {
        long now = System.currentTimeMillis();
        if (now - this.lastActionMs < ACTION_COOLDOWN_MS || tierIndex < 1 || batches < 1) {
            return;
        }
        this.lastActionMs = now;
        this.waitingSync = true;
        this.waitingSinceMs = now;
        this.statusColor = IOStyle.BLUE;
        this.statusText = desc;

        CompoundTag tag = new CompoundTag();
        tag.putString("action", "phys_currency_convert");
        tag.putInt("tierIndex", tierIndex);
        tag.putString("direction", direction);
        tag.putInt("batches", batches);
        ServerboundShopActionPayload.send(tag);
    }

    // ------------------------------------------------------------------ Skija 主绘制

    @Override
    protected void paint(Canvas canvas) {
        if (ClientHomeCurrencyState.snapshotVersion != this.lastSeenSnapshotVersion) {
            this.lastSeenSnapshotVersion = ClientHomeCurrencyState.snapshotVersion;
            if (this.waitingSync) {
                this.waitingSync = false;
                this.statusColor = IOStyle.GREEN;
                this.statusText = tr("gui.module_shop.phys_ex.synced", "兑换完成，持有量已同步");
            }
        } else if (this.waitingSync && System.currentTimeMillis() - this.waitingSinceMs >= SYNC_TIMEOUT_MS) {
            this.waitingSync = false;
            this.statusColor = IOStyle.ORANGE;
            this.statusText = tr("gui.module_shop.phys_ex.sync_timeout", "兑换未收到同步响应，请查看聊天栏提示");
        }

        float w = Math.max(300.0F, Math.min(MAX_W, this.width - 20.0F));
        float h = Math.max(220.0F, Math.min(MAX_H, this.height - 20.0F));
        float x = Math.round((this.width - w) / 2.0F);
        float y = Math.round((this.height - h) / 2.0F);

        panel(canvas, x, y, w, h, this.title.getString());
        float closeX = x + w - PAD - 8.0F;
        float closeY = y + HEADER_H * 0.5F;
        float chv = hover("close", closeX - 6.0F, closeY - 6.0F, 12.0F, 12.0F);
        Draw.cross(canvas, closeX, closeY, 3.0F, 1.1F, Draw.lerp(IOStyle.TEXT_2, IOStyle.RED, chv));
        hit(closeX - 6.0F, closeY - 6.0F, 12.0F, 12.0F, this::onClose, null);

        float innerX = x + PAD;
        float innerW = w - PAD * 2.0F;
        float topY = y + HEADER_H + 6.0F;
        List<PhysTierView> tiers = ClientHomeCurrencyState.physTiers;

        // 顶部总览与各级持有条
        float overviewH = 36.0F;
        card(canvas, innerX, topY, innerW, overviewH);
        paintOverview(canvas, innerX, topY, innerW, overviewH, tiers);

        float btnH = 18.0F;
        float btnY = y + h - PAD - btnH;
        float listY = topY + overviewH + 6.0F;
        float listH = Math.max(70.0F, btnY - 6.0F - listY);

        if (!ClientHomeCurrencyState.physEnabled) {
            card(canvas, innerX, listY, innerW, listH);
            center(canvas, tr("gui.module_shop.phys_ex.disabled", "实体货币系统当前未启用"),
                    innerX + 10.0F, listY + listH * 0.5F, innerW - 20.0F, IOStyle.TEXT_3);
        } else if (tiers.size() < 2) {
            card(canvas, innerX, listY, innerW, listH);
            center(canvas, tr("gui.module_shop.phys_ex.need_two", "需要至少 2 个货币等级才能进行阶梯兑换"),
                    innerX + 10.0F, listY + listH * 0.5F, innerW - 20.0F, IOStyle.TEXT_3);
        } else {
            paintPairsList(canvas, innerX, listY, innerW, listH, tiers);
        }

        // 底栏提示 + 返回按钮
        float backW = 68.0F;
        String defaultHint = ClientHomeCurrencyState.physDailyConvertLimit > 0L
                ? tr("gui.module_shop.phys_ex.hint_daily", "今日额度：%s / %s · 凑整：低换高 · 拆零：高拆低",
                        String.format("%,d", ClientHomeCurrencyState.physConvertUsedToday),
                        String.format("%,d", ClientHomeCurrencyState.physDailyConvertLimit))
                : tr("gui.module_shop.phys_ex.hint", "凑整：低面值换高面值 · 拆零：高面值拆低面值（背包满自动转入邮箱）");
        String hint = this.statusText.isEmpty() ? defaultHint : this.statusText;
        small(canvas, hint, innerX, btnY + btnH * 0.5F, Math.max(40.0F, innerW - backW - 8.0F),
                this.statusText.isEmpty() ? IOStyle.TEXT_3 : this.statusColor);
        button(canvas, "back", tr("gui.module_shop.cfg.back_btn", "返回"),
                innerX + innerW - backW, btnY, backW, btnH, Button.Kind.NORMAL, true, this::onClose);
    }

    private void paintOverview(Canvas canvas, float x, float y, float w, float h, List<PhysTierView> tiers) {
        float leftW = Math.min(168.0F, w * 0.44F);
        float lx = x + 9.0F;
        small(canvas, tr("gui.module_shop.phys_ex.bag_total_v2", "背包实体总面值"),
                lx, y + 10.0F, leftW - 10.0F, IOStyle.TEXT_3);
        long totalVal = ClientHomeCurrencyState.physPlayerValue;
        if (totalVal <= 0L && !tiers.isEmpty()) {
            totalVal = cn.autoforged.module_shop.shop.currency.PhysicalCurrencyEngine.totalPhysicalValue(
                    ClientHomeCurrencyState.tierValues(), ClientHomeCurrencyState.tierOwnedCounts());
        }
        String formattedTotal = String.format("%,d", totalVal);
        String valStr = (!tiers.isEmpty() && tiers.get(0).value == 1L)
                ? (formattedTotal + " " + tiers.get(0).displayName())
                : tr("gui.module_shop.phys_ex.value_units", "%s 面值", formattedTotal);
        Text.drawLine(canvas, valStr, AtomUi.BOLD, Text.TITLE, IOStyle.GREEN,
                lx, y + 24.5F, leftW - 10.0F, Text.Align.LEFT);

        // 右侧展示各级持有量胶囊
        float sepX = x + leftW;
        Draw.fill(canvas, sepX, y + 6.0F, 0.5F, h - 12.0F, 0.0F, IOStyle.BORDER_DIM);

        float stripX = sepX + 7.0F;
        float stripW = Math.max(20.0F, x + w - 7.0F - stripX);
        if (tiers.isEmpty()) {
            small(canvas, "—", stripX, y + h * 0.5F, stripW, IOStyle.TEXT_3);
            return;
        }
        int count = tiers.size();
        float gap = 4.0F;
        float pillW = Math.min(62.0F, Math.max(28.0F, (stripW - gap * (count - 1)) / count));
        float pillH = 24.0F;
        float py = y + (h - pillH) * 0.5F;
        for (int i = 0; i < count; i++) {
            float px = stripX + i * (pillW + gap);
            if (px + pillW > x + w - 4.0F) {
                break;
            }
            PhysTierView tv = tiers.get(i);
            Draw.fill(canvas, px, py, pillW, pillH, 3.0F, IOStyle.FIELD_BG);
            Draw.stroke(canvas, px, py, pillW, pillH, 3.0F, 0.6F,
                    tv.ownedCount > 0 ? Draw.alpha(IOStyle.BLUE, 0.45F) : IOStyle.BORDER_DIM);
            item(tv.itemStack, px + 2.0F, py + 4.0F);
            float tx = px + 19.0F;
            float tw = Math.max(8.0F, pillW - 21.0F);
            Text.drawLine(canvas, "×" + tv.ownedCount, AtomUi.BOLD, Text.SMALL,
                    tv.ownedCount > 0 ? IOStyle.TEXT : IOStyle.TEXT_3,
                    tx, py + 9.0F, tw, Text.Align.LEFT);
            Text.drawLine(canvas, tv.displayName(), AtomUi.REGULAR, 4.75F, IOStyle.TEXT_3,
                    tx, py + 18.0F, tw, Text.Align.LEFT);
            if (over(px, py, pillW, pillH)) {
                tip(Component.literal(tv.displayName() + " · "
                        + tr("gui.module_shop.phys_ex.unit_val", "单枚面值 %s", String.format("%,d", tv.value))
                        + " · " + tr("gui.module_shop.phys_ex.owned_tip", "持有 %s", tv.ownedCount)));
            }
        }
    }

    private static boolean isDivisiblePair(PhysTierView lower, PhysTierView upper) {
        return upper.stepRatio > 1 && lower.value > 0L && upper.value > lower.value;
    }

    private void paintPairsList(Canvas canvas, float x, float y, float w, float h, List<PhysTierView> tiers) {
        float gap = 6.0F;
        float padIn = 2.0F;
        float contentH = padIn * 2.0F;
        for (int i = 1; i < tiers.size(); i++) {
            boolean div = isDivisiblePair(tiers.get(i - 1), tiers.get(i));
            contentH += (div ? 56.0F : 68.0F) + (i + 1 < tiers.size() ? gap : 0.0F);
        }
        float oy = beginList(canvas, this.listScroll, x, y, w, h, contentH);
        float cw = w - (this.listScroll.canScroll() ? 8.0F : 0.0F);

        float ry = oy + padIn;
        for (int i = 1; i < tiers.size(); i++) {
            final int tierIndex = i;
            PhysTierView lower = tiers.get(i - 1);
            PhysTierView upper = tiers.get(i);
            boolean divisible = isDivisiblePair(lower, upper);
            float cardH = divisible ? 56.0F : 68.0F;
            if (ry + cardH < y || ry > y + h) {
                ry += cardH + gap;
                continue;
            }
            int ratio = divisible ? upper.stepRatio : 0;
            int maxOut = maxExchangeOutput();
            int maxUp = divisible ? Math.min(lower.ownedCount / ratio, maxOut) : 0;
            int maxDown = divisible ? Math.min(upper.ownedCount, Math.max(0, maxOut / ratio)) : 0;

            Draw.fill(canvas, x, ry, cw, cardH, 4.0F, IOStyle.CARD);
            Draw.stroke(canvas, x, ry, cw, cardH, 4.0F, 0.75F, IOStyle.BORDER_DIM);

            // 上半部分：双向兑换比例与持有量展示
            float topRowY = ry + 5.0F;
            float midX = x + cw * 0.5F;
            float swapW = 20.0F;
            float sideW = Math.max(60.0F, (cw - 24.0F - swapW) * 0.5F);

            // 左侧（低等级）
            float lx = x + 8.0F;
            slotFrame(canvas, lx + 1.0F, topRowY + 2.0F);
            item(lower.itemStack, lx + 1.0F, topRowY + 2.0F);
            float lTextX = lx + 21.0F;
            float lTextW = Math.max(20.0F, sideW - 21.0F);
            String leftTitle = divisible ? (ratio + " × " + lower.displayName()) : lower.displayName();
            Text.drawLine(canvas, leftTitle, AtomUi.BOLD, Text.BODY, IOStyle.TEXT,
                    lTextX, topRowY + 6.5F, lTextW, Text.Align.LEFT);
            String leftOwned = tr("gui.module_shop.phys_ex.owned_val", "持有 %s · 面值 %s",
                    lower.ownedCount, lower.value);
            Text.drawLine(canvas, leftOwned, AtomUi.REGULAR, 5.25F,
                    maxUp >= 1 ? IOStyle.GREEN : IOStyle.TEXT_3,
                    lTextX, topRowY + 15.5F, lTextW, Text.Align.LEFT);

            // 中间双向箭头徽标（非整倍时改显示 ≠ 提醒不可整比互换）
            Draw.fill(canvas, midX - 8.0F, topRowY + 4.0F, 16.0F, 12.0F, 3.0F,
                    Draw.alpha(divisible ? IOStyle.BLUE : IOStyle.ORANGE, 0.16F));
            Text.drawLine(canvas, divisible ? "⇄" : "≠", AtomUi.BOLD, Text.BODY,
                    divisible ? IOStyle.BLUE : IOStyle.ORANGE,
                    midX - 8.0F, topRowY + 10.0F, 16.0F, Text.Align.CENTER);

            // 右侧（高等级）
            float rx = midX + swapW * 0.5F + 2.0F;
            slotFrame(canvas, rx + 1.0F, topRowY + 2.0F);
            item(upper.itemStack, rx + 1.0F, topRowY + 2.0F);
            float rTextX = rx + 21.0F;
            float rTextW = Math.max(20.0F, x + cw - 8.0F - rTextX);
            String rightTitle = divisible ? ("1 × " + upper.displayName()) : upper.displayName();
            Text.drawLine(canvas, rightTitle, AtomUi.BOLD, Text.BODY, IOStyle.TEXT,
                    rTextX, topRowY + 6.5F, rTextW, Text.Align.LEFT);
            String rightOwned = tr("gui.module_shop.phys_ex.owned_val", "持有 %s · 面值 %s",
                    upper.ownedCount, upper.value);
            Text.drawLine(canvas, rightOwned, AtomUi.REGULAR, 5.25F,
                    maxDown >= 1 ? IOStyle.ORANGE : IOStyle.TEXT_3,
                    rTextX, topRowY + 15.5F, rTextW, Text.Align.LEFT);

            float divY;
            float btnY;
            if (!divisible) {
                float warnY = ry + 27.0F;
                Draw.fill(canvas, x + 8.0F, warnY, cw - 16.0F, 12.0F, 2.5F, Draw.alpha(IOStyle.ORANGE, 0.12F));
                Text.drawLine(canvas, tr("gui.module_shop.phys_ex.non_divisible",
                                "非整倍面值（仅自动找零结算，不支持手动整兑）"),
                        AtomUi.REGULAR, 5.25F, IOStyle.ORANGE,
                        x + 12.0F, warnY + 6.0F, cw - 24.0F, Text.Align.CENTER);
                divY = ry + 41.5F;
                btnY = ry + 45.5F;
            } else {
                divY = ry + 27.5F;
                btnY = ry + 32.5F;
            }

            // 分隔线
            Draw.hairline(canvas, x + 8.0F, divY, cw - 16.0F, Draw.alpha(IOStyle.BORDER_DIM, 0.75F));

            // 下半部分：左组（凑整 ↑） + 右组（拆零 ↓）
            float btnH = 17.0F;
            float groupGap = 12.0F;
            float groupW = Math.max(80.0F, (cw - 16.0F - groupGap) * 0.5F);
            Draw.fill(canvas, midX - 0.25F, btnY + 2.0F, 0.5F, btnH - 4.0F, 0.0F, IOStyle.BORDER_DIM);

            // 凑整组（低 -> 高）
            String up1 = tr("gui.module_shop.phys_ex.up_1", "凑整 ×1");
            String up10 = "×10";
            String upMax = maxUp > 0
                    ? tr("gui.module_shop.phys_ex.max_n", "最大(%s)", maxUp)
                    : tr("gui.module_shop.phys_ex.max", "最大");
            float[] upWidths = PageKit.distribute(groupW - 6.0F,
                    new float[]{groupW * 0.36F, groupW * 0.22F, groupW * 0.42F},
                    new float[]{40.0F, 26.0F, 42.0F});
            float ux = x + 8.0F;
            button(canvas, "up1:" + i, up1, ux, btnY, upWidths[0], btnH, Button.Kind.ACCENT, maxUp >= 1,
                    () -> sendConvert(tierIndex, "up", 1,
                            tr("gui.module_shop.phys_ex.sending_up", "正在凑整兑换 %s ×1…", upper.displayName())));
            ux += upWidths[0] + 3.0F;
            button(canvas, "up10:" + i, up10, ux, btnY, upWidths[1], btnH, Button.Kind.NORMAL, maxUp >= 10,
                    () -> sendConvert(tierIndex, "up", 10,
                            tr("gui.module_shop.phys_ex.sending_up_n", "正在凑整兑换 %s ×%s…", upper.displayName(), 10)));
            ux += upWidths[1] + 3.0F;
            button(canvas, "upMax:" + i, upMax, ux, btnY, upWidths[2], btnH, Button.Kind.NORMAL, maxUp >= 1,
                    () -> sendConvert(tierIndex, "up", maxUp,
                            tr("gui.module_shop.phys_ex.sending_up_n", "正在凑整兑换 %s ×%s…", upper.displayName(), maxUp)));

            // 拆零组（高 -> 低）
            String dn1 = tr("gui.module_shop.phys_ex.down_1", "拆零 ×1");
            String dn10 = "×10";
            String dnMax = maxDown > 0
                    ? tr("gui.module_shop.phys_ex.max_n", "最大(%s)", maxDown)
                    : tr("gui.module_shop.phys_ex.max", "最大");
            float[] dnWidths = PageKit.distribute(groupW - 6.0F,
                    new float[]{groupW * 0.36F, groupW * 0.22F, groupW * 0.42F},
                    new float[]{40.0F, 26.0F, 42.0F});
            float dx = x + cw - 8.0F - groupW;
            button(canvas, "dn1:" + i, dn1, dx, btnY, dnWidths[0], btnH, Button.Kind.NORMAL, maxDown >= 1,
                    () -> sendConvert(tierIndex, "down", 1,
                            tr("gui.module_shop.phys_ex.sending_dn", "正在拆零兑换 %s ×%s…", lower.displayName(), ratio)));
            dx += dnWidths[0] + 3.0F;
            button(canvas, "dn10:" + i, dn10, dx, btnY, dnWidths[1], btnH, Button.Kind.NORMAL, maxDown >= 10,
                    () -> sendConvert(tierIndex, "down", 10,
                            tr("gui.module_shop.phys_ex.sending_dn", "正在拆零兑换 %s ×%s…", lower.displayName(), 10 * ratio)));
            dx += dnWidths[1] + 3.0F;
            button(canvas, "dnMax:" + i, dnMax, dx, btnY, dnWidths[2], btnH, Button.Kind.NORMAL, maxDown >= 1,
                    () -> sendConvert(tierIndex, "down", maxDown,
                            tr("gui.module_shop.phys_ex.sending_dn", "正在拆零兑换 %s ×%s…", lower.displayName(),
                                    (long) maxDown * ratio)));
            ry += cardH + gap;
        }
        endList(canvas);
    }

    // ------------------------------------------------------------------ 非 Skia 兜底

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (skiaRender(g, mouseX, mouseY, partialTick)) {
            return;
        }
        this.renderBackground(g);
        int pw = 280;
        int ph = 110;
        int x = (this.width - pw) / 2;
        int y = (this.height - ph) / 2;
        Bricks.panel(g, x, y, pw, ph);
        IOStyle.centerAt(g, this.font, this.title, x + pw / 2, y + 14, IOStyle.ORANGE);
        IOStyle.centerAt(g, this.font,
                Component.literal(tr("gui.module_shop.phys_ex.no_skia", "请在支持 Skija 的客户端环境中使用实体货币兑换界面")),
                x + pw / 2, y + 46, IOStyle.TEXT_2);
        int bx = x + (pw - 80) / 2;
        int by = y + ph - 30;
        IOStyle.button(g, bx, by, 80, 18, Component.translatable("gui.module_shop.cfg.back_btn"),
                this.font, mouseX, mouseY, true, IOStyle.Btn.GRAY);
        this.fbBackBtn = new int[]{bx, by, 80, 18};
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (skia()) {
            return skiaClicked(mouseX, mouseY, button);
        }
        if (button == 0 && this.fbBackBtn != null
                && IOStyle.inside(this.fbBackBtn[0], this.fbBackBtn[1], this.fbBackBtn[2], this.fbBackBtn[3], mouseX, mouseY)) {
            ClickSound.play();
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
