package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientAdminAuth;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState.AnnouncementView;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.AnnouncementAdminScreen;
import cn.autoforged.module_shop.client.screen.AnnouncementBlockRenderer;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.Nav;
import cn.autoforged.module_shop.client.screen.PhysicalCurrencyExchangeScreen;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.EntityPreview;
import cn.autoforged.module_shop.client.ui.render.StyledText;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.types.RRect;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 服务器个人主页（UI v2，{@code id() = "home"}）：
 * <ul>
 *     <li>左栏：3D 跟随鼠标动态玩家形象、LuckPerms 称号徽章与组别、实时在线时长、核心资产（星币余额 + 实体货币总值与快捷兑换）；</li>
 *     <li>右栏：服务器公告轮播与长文滚动阅读（置顶优先、悬停暂停、6 秒进度条、圆点/箭头切换）+ 签到联动按钮 + 管理员公告管理入口。</li>
 * </ul>
 */
public final class ProfileHomePage implements ShopPage {
    private static final float AUTO_ROTATE_MS = 6000.0F;
    private static final float SLIDE_TRANS_MS = 180.0F;

    // 跨界面重建保留状态（点击签到等页内操作刷新快照时不重置轮播和阅读位置）
    private static int carouselIndex;
    private static String trackedAnnouncementId = "";
    private static float carouselElapsedMs;
    private static long slideTransNanos;
    private static int slideDir = 1;
    private static final ScrollController BODY_SCROLL = new ScrollController();

    private final Map<String, Float> anim = new HashMap<>();

    private float[] signInRect;
    private float[] adminAnnRect;
    private float[] exchangeRect;
    private float[] billRect;
    private float[] prevRect;
    private float[] nextRect;
    private float[] jumpPinnedRect;
    private float[] cardRect;
    private float[] bodyRect;
    private final List<float[]> dotRects = new ArrayList<>();

    private final AnnouncementBlockRenderer blockRenderer = new AnnouncementBlockRenderer();

    @Override
    public String id() {
        return "home";
    }

    private static String tr(String key, String fallback, Object... args) {
        return ClientHomeCurrencyState.tr(key, fallback, args);
    }

    private float hover(PageFrame f, String key, float x, float y, float w, float h) {
        boolean on = over(f, x, y, w, h);
        float cur = this.anim.getOrDefault(key, 0.0F);
        float next = Motion.hover(cur, on, f.dtMs());
        this.anim.put(key, next);
        return next;
    }

    private static boolean over(PageFrame f, float x, float y, float w, float h) {
        return f.mouseX() >= x && f.mouseX() < x + w && f.mouseY() >= y && f.mouseY() < y + h;
    }

    private static boolean in(float[] r, double mx, double my) {
        return r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    @Override
    public void render(Canvas c, PageFrame f) {
        this.signInRect = null;
        this.adminAnnRect = null;
        this.exchangeRect = null;
        this.billRect = null;
        this.prevRect = null;
        this.nextRect = null;
        this.jumpPinnedRect = null;
        this.cardRect = null;
        this.bodyRect = null;
        this.dotRects.clear();

        float pad = f.w() < 520.0F ? 8.0F : 10.0F;
        float gap = f.w() < 520.0F ? 8.0F : 10.0F;
        float innerX = f.x() + pad;
        float innerY = f.y() + pad;
        float innerW = Math.max(200.0F, f.w() - pad * 2.0F);
        float innerH = Math.max(180.0F, f.h() - pad * 2.0F);

        float leftW = Math.max(150.0F, Math.min(210.0F, innerW * 0.34F));
        float rightX = innerX + leftW + gap;
        float rightW = Math.max(180.0F, innerX + innerW - rightX);

        renderLeftColumn(c, f, innerX, innerY, leftW, innerH);
        renderRightColumn(c, f, rightX, innerY, rightW, innerH);
    }

    // ------------------------------------------------------------------ 左栏：形象 + 身份 + 资产

    private void renderLeftColumn(Canvas c, PageFrame f, float x, float y, float w, float h) {
        boolean physOn = ClientHomeCurrencyState.physEnabled;
        float cardGap = 8.0F;
        float assetH = physOn ? Math.min(118.0F, Math.max(94.0F, h * 0.31F))
                : Math.min(68.0F, Math.max(54.0F, h * 0.19F));
        float heroH = Math.max(100.0F, h - assetH - cardGap);

        renderHeroCard(c, f, x, y, w, heroH);
        renderAssetsCard(c, f, x, y + heroH + cardGap, w, h - heroH - cardGap, physOn);
    }

    private void renderHeroCard(Canvas c, PageFrame f, float x, float y, float w, float h) {
        Draw.fill(c, x, y, w, h, 5.0F, IOStyle.CARD);

        float infoBandH = 58.0F;
        float previewPad = 6.0F;
        float previewX = x + previewPad;
        float previewY = y + previewPad;
        float previewW = Math.max(40.0F, w - previewPad * 2.0F);
        float previewH = Math.max(54.0F, h - infoBandH - previewPad * 1.5F);

        // 3D 玩家预览框底板与微光底座（严格裁剪在圆角框内）
        int saved = c.save();
        c.clipRRect(RRect.makeXYWH(previewX, previewY, previewW, previewH, 4.0F), true);
        Draw.gradientV(c, previewX, previewY, previewW, previewH, 0.0F,
                Draw.lerp(IOStyle.SIDEBAR, IOStyle.CARD, 0.45F), IOStyle.CARD);
        Draw.glow(c, previewX + previewW * 0.5F, previewY + previewH * 0.45F,
                Math.max(previewW, previewH) * 0.68F, Draw.alpha(IOStyle.BLUE, 0.14F));
        // 脚底微光底座圆盘
        float baseW = Math.min(previewW * 0.56F, 72.0F);
        float baseH = 7.0F;
        float baseX = previewX + (previewW - baseW) * 0.5F;
        float baseY = previewY + previewH - 11.0F;
        Draw.fill(c, baseX, baseY, baseW, baseH, baseH * 0.5F, Draw.alpha(IOStyle.BLUE, 0.18F));
        Draw.stroke(c, baseX, baseY, baseW, baseH, baseH * 0.5F, 0.6F, Draw.alpha(IOStyle.BLUE, 0.35F));
        c.restoreToCount(saved);
        Draw.stroke(c, previewX, previewY, previewW, previewH, 4.0F, 0.5F, Draw.alpha(IOStyle.BORDER_DIM, 0.85F));

        // 登记 3D 动态皮肤（位于 Skija 层之上，自动处理缩放与滑入动画）
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            float entityScale = Math.max(18.0F, Math.min(previewW * 0.52F, previewH * 0.41F));
            EntityPreview.drawFollowsMouse(
                    f.host().items(),
                    previewX, previewY, previewW, previewH,
                    2.0F, entityScale, EntityPreview.DEFAULT_Y_OFFSET,
                    f.mouseX(), f.mouseY(), mc.player);
        } else {
            V2.bold(c, "?", V2.FS_T, IOStyle.TEXT_3, previewX, previewY + previewH * 0.5F, previewW, Text.Align.CENTER);
        }

        // 右上角在线小徽标
        float dotX = previewX + previewW - 10.0F;
        float dotY = previewY + 7.0F;
        Draw.fill(c, dotX - 2.5F, dotY - 2.5F, 5.0F, 5.0F, 2.5F, Draw.alpha(IOStyle.GREEN, 0.25F));
        Draw.fill(c, dotX - 1.5F, dotY - 1.5F, 3.0F, 3.0F, 1.5F, IOStyle.GREEN);

        // 身份信息区
        float infoY = previewY + previewH + 5.0F;
        float innerW = w - 16.0F;
        float ix = x + 8.0F;

        String playerName = ClientShopData.playerName == null || ClientShopData.playerName.isBlank()
                ? mc.getUser().getName()
                : ClientShopData.playerName;
        V2.bold(c, playerName, V2.FS + 1.5F, IOStyle.TEXT, ix, infoY + 6.5F, innerW, Text.Align.CENTER);

        // 称号徽章胶囊（LuckPerms 优先，无 LP 时柔和默认身份标签）
        float badgeY = infoY + 16.0F;
        drawIdentityBadge(c, ix, badgeY, innerW);

        // 在线时长行
        float timeY = badgeY + 18.0F;
        Draw.hairline(c, ix, timeY, innerW, Draw.alpha(IOStyle.BORDER_DIM, 0.65F));
        float rowCy = timeY + 9.5F;
        // 小巧时钟图标
        Draw.stroke(c, ix + 1.0F, rowCy - 3.2F, 6.4F, 6.4F, 3.2F, 0.7F, IOStyle.TEXT_3);
        Draw.line(c, ix + 4.2F, rowCy, ix + 4.2F, rowCy - 1.8F, 0.7F, IOStyle.TEXT_3);
        Draw.line(c, ix + 4.2F, rowCy, ix + 5.6F, rowCy + 0.6F, 0.7F, IOStyle.TEXT_3);

        String timeLabel = tr("gui.module_shop.home.playtime_label", "在线时长");
        String timeVal = ClientHomeCurrencyState.formatPlayTime(ClientHomeCurrencyState.currentPlayTimeTicks());
        float valW = Math.min(innerW * 0.62F, V2.width(timeVal, V2.FS_S, true) + 4.0F);
        float labelMaxW = Math.max(20.0F, innerW - 11.0F - valW - 4.0F);
        V2.text(c, timeLabel, V2.FS_XS, IOStyle.TEXT_3, ix + 10.0F, rowCy, labelMaxW, Text.Align.LEFT);
        V2.bold(c, timeVal, V2.FS_S, IOStyle.TEXT, ix + innerW - valW, rowCy, valW, Text.Align.RIGHT);

        Draw.stroke(c, x, y, w, h, 5.0F, 0.75F, IOStyle.BORDER_DIM);
    }

    private void drawIdentityBadge(Canvas c, float x, float y, float maxW) {
        float pillH = 13.0F;
        String prefix = ClientHomeCurrencyState.lpPrefix == null ? "" : ClientHomeCurrencyState.lpPrefix.trim();
        String group = ClientHomeCurrencyState.lpGroup == null ? "" : ClientHomeCurrencyState.lpGroup.trim();
        boolean hasLp = ClientHomeCurrencyState.lpAvailable && (!prefix.isEmpty() || !group.isEmpty());

        if (!hasLp) {
            String fallbackRole = ClientShopData.canAdmin
                    ? tr("gui.module_shop.home.role_admin", "服务器管理员")
                    : tr("gui.module_shop.home.role_default", "服务器成员");
            int accent = ClientShopData.canAdmin ? IOStyle.ORANGE : IOStyle.TEXT_2;
            float pw = Math.min(maxW, V2.width(fallbackRole, V2.FS_XS, false) + 14.0F);
            float px = x + (maxW - pw) * 0.5F;
            Draw.fill(c, px, y, pw, pillH, pillH * 0.5F,
                    ClientShopData.canAdmin ? Draw.alpha(IOStyle.ORANGE, 0.14F) : IOStyle.GRAY_BTN);
            Draw.stroke(c, px, y, pw, pillH, pillH * 0.5F, 0.6F,
                    ClientShopData.canAdmin ? Draw.alpha(IOStyle.ORANGE, 0.42F) : IOStyle.BORDER_DIM);
            V2.text(c, fallbackRole, V2.FS_XS, accent, px + 4.0F, y + pillH * 0.5F, pw - 8.0F, Text.Align.CENTER);
            return;
        }

        String primaryRaw = !prefix.isEmpty() ? prefix : group;
        List<StyledText.Run> runs = ClientHomeCurrencyState.parseStyled(primaryRaw, IOStyle.TEXT);
        int accent = IOStyle.BLUE;
        for (StyledText.Run r : runs) {
            if (r.color() != IOStyle.TEXT) {
                accent = r.color();
                break;
            }
        }

        boolean showGroupTag = !prefix.isEmpty() && !group.isEmpty()
                && !ClientHomeCurrencyState.stripColorCodes(prefix).equalsIgnoreCase(group);
        float groupW = 0.0F;
        if (showGroupTag) {
            groupW = Math.min(maxW * 0.36F, V2.width(group, V2.FS_XS, false) + 10.0F);
        }
        float gap = showGroupTag ? 4.0F : 0.0F;
        float availPrefixW = Math.max(24.0F, maxW - groupW - gap - 12.0F);
        StyledText.Line line = StyledText.singleLine(runs, availPrefixW, V2.FS_XS);
        float prefixPillW = Math.min(maxW - groupW - gap, Math.max(26.0F, line.width() + 12.0F));
        float totalW = prefixPillW + gap + groupW;
        float startX = x + Math.max(0.0F, (maxW - totalW) * 0.5F);

        Draw.fill(c, startX, y, prefixPillW, pillH, pillH * 0.5F, Draw.alpha(accent, 0.15F));
        Draw.stroke(c, startX, y, prefixPillW, pillH, pillH * 0.5F, 0.6F, Draw.alpha(accent, 0.45F));
        float textX = startX + Math.max(4.0F, (prefixPillW - line.width()) * 0.5F);
        float textY = y + (pillH - StyledText.lineHeight(V2.FS_XS)) * 0.5F;
        StyledText.draw(c, line, textX, textY, V2.FS_XS);

        if (showGroupTag) {
            float gx = startX + prefixPillW + gap;
            Draw.fill(c, gx, y, groupW, pillH, pillH * 0.5F, IOStyle.GRAY_BTN);
            Draw.stroke(c, gx, y, groupW, pillH, pillH * 0.5F, 0.5F, IOStyle.BORDER_DIM);
            V2.text(c, group, V2.FS_XS, IOStyle.TEXT_2, gx + 3.0F, y + pillH * 0.5F, groupW - 6.0F, Text.Align.CENTER);
        }
    }

    private void renderAssetsCard(Canvas c, PageFrame f, float x, float y, float w, float h, boolean physOn) {
        Draw.fill(c, x, y, w, h, 5.0F, IOStyle.CARD);
        Draw.stroke(c, x, y, w, h, 5.0F, 0.75F, IOStyle.BORDER_DIM);

        float ix = x + 9.0F;
        float iw = w - 18.0F;

        // 标题行 + 账单入口
        String title = tr("gui.module_shop.home.assets", "核心资产");
        String billLabel = tr("gui.module_shop.home.bill_btn", "账单 ›");
        float bw = V2.width(billLabel, V2.FS_XS, false) + 8.0F;
        float billX = ix + iw - bw;
        float billY = y + 6.0F;
        float bh = 12.0F;
        float billHv = hover(f, "bill", billX, billY, bw, bh);
        if (billHv > 0.01F) {
            Draw.fill(c, billX, billY, bw, bh, 3.0F, Draw.alpha(IOStyle.ROW_HOVER, billHv));
        }
        V2.text(c, billLabel, V2.FS_XS, Draw.lerp(IOStyle.TEXT_3, IOStyle.BLUE, billHv),
                billX, billY + bh * 0.5F, bw, Text.Align.CENTER);
        this.billRect = new float[]{billX, billY, bw, bh};

        V2.text(c, title, V2.FS_XS, IOStyle.TEXT_3, ix, billY + bh * 0.5F, Math.max(20.0F, iw - bw - 4.0F),
                Text.Align.LEFT);

        // 虚拟余额主展示
        String balText = V2.money(ClientShopData.balance) + " " + ClientShopData.currencyName();
        boolean full = ClientShopData.maxBalance > 0L && ClientShopData.balance >= ClientShopData.maxBalance;
        float balSize = V2.width(balText, V2.FS_T, true) > iw - (full ? 28.0F : 0.0F) ? V2.FS + 0.5F : V2.FS_T;
        float balY = y + 25.0F;
        float tagW = full ? V2.tagWidth(tr("gui.module_shop.home.balance_full", "已满")) : 0.0F;
        float usedW = V2.bold(c, balText, balSize, IOStyle.ORANGE, ix, balY, Math.max(20.0F, iw - tagW), Text.Align.LEFT);
        if (full && iw - usedW >= tagW) {
            V2.tag(c, tr("gui.module_shop.home.balance_full", "已满"), IOStyle.ORANGE, ix + usedW + 4.0F, balY);
        }

        if (physOn) {
            float divY = y + 34.5F;
            Draw.hairline(c, ix, divY, iw, Draw.alpha(IOStyle.BORDER_DIM, 0.7F));
            float physHeadY = divY + 7.5F;
            String physTitle = tr("gui.module_shop.home.phys_ledger", "实体货币");
            String exMini = tr("gui.module_shop.home.phys_exchange", "货币兑换") + " ›";
            float exW = V2.width(exMini, V2.FS_XS, false) + 8.0F;
            float exX = ix + iw - exW;
            float exY = physHeadY - 6.0F;
            float exHv = hover(f, "exchange", exX, exY, exW, 12.0F);
            if (exHv > 0.01F) {
                Draw.fill(c, exX, exY, exW, 12.0F, 3.0F, Draw.alpha(IOStyle.ROW_HOVER, exHv));
            }
            V2.text(c, exMini, V2.FS_XS, Draw.lerp(IOStyle.BLUE, IOStyle.TEXT, exHv),
                    exX, physHeadY, exW, Text.Align.CENTER);
            this.exchangeRect = new float[]{exX, exY, exW, 12.0F};
            V2.text(c, physTitle, V2.FS_XS, IOStyle.TEXT_3, ix, physHeadY, Math.max(20.0F, iw - exW - 4.0F),
                    Text.Align.LEFT);

            List<ClientHomeCurrencyState.PhysTierView> tiers = ClientHomeCurrencyState.physTiers;
            float stripTop = physHeadY + 7.0F;
            float stripAvailH = Math.max(16.0F, y + h - 6.0F - stripTop);
            if (tiers.isEmpty()) {
                V2.text(c, tr("gui.module_shop.phys_ex.need_two", "暂无实体货币档位"),
                        V2.FS_XS, IOStyle.TEXT_3, ix, stripTop + stripAvailH * 0.5F, iw, Text.Align.CENTER);
            } else {
                int cols = iw >= 152.0F && tiers.size() >= 3 ? 3 : Math.min(2, tiers.size());
                float gap = 3.0F;
                float pillW = (iw - gap * (cols - 1)) / cols;
                float pillH = 17.0F;
                int maxRows = Math.max(1, (int) ((stripAvailH + gap) / (pillH + gap)));
                int maxShow = Math.min(tiers.size(), cols * maxRows);
                for (int i = 0; i < maxShow; i++) {
                    ClientHomeCurrencyState.PhysTierView tv = tiers.get(i);
                    int r = i / cols;
                    int col = i % cols;
                    float px = ix + col * (pillW + gap);
                    float py = stripTop + r * (pillH + gap);
                    Draw.fill(c, px, py, pillW, pillH, 3.0F, IOStyle.FIELD_BG);
                    Draw.stroke(c, px, py, pillW, pillH, 3.0F, 0.5F,
                            tv.ownedCount > 0 ? Draw.alpha(IOStyle.GREEN, 0.45F) : IOStyle.BORDER_DIM);
                    if (!tv.itemStack.isEmpty()) {
                        f.host().items().add(tv.itemStack.copyWithCount(1),
                                px + 2.0F, py + (pillH - 12.0F) * 0.5F, 12.0F, false);
                    }
                    float tx = px + 15.5F;
                    float tw = Math.max(8.0F, pillW - 17.5F);
                    V2.text(c, tv.displayName(), 4.75F, IOStyle.TEXT_3, tx, py + 5.2F, tw, Text.Align.LEFT);
                    V2.bold(c, "×" + tv.ownedCount, V2.FS_XS,
                            tv.ownedCount > 0 ? IOStyle.GREEN : IOStyle.TEXT_2, tx, py + 12.5F, tw, Text.Align.LEFT);
                }
            }
        } else {
            this.exchangeRect = null;
            if (ClientShopData.billUpdatedAt > 0L && h >= 58.0F) {
                float divY = y + 38.0F;
                Draw.hairline(c, ix, divY, iw, Draw.alpha(IOStyle.BORDER_DIM, 0.7F));
                float rowY = divY + (y + h - divY) * 0.5F;
                String inTxt = tr("gui.module_shop.v2.today_in", "今日收入") + " " + V2.signed(ClientShopData.billTodayIn);
                V2.text(c, inTxt, V2.FS_XS, IOStyle.GREEN, ix, rowY, iw * 0.52F, Text.Align.LEFT);
                String outTxt = "−" + V2.money(ClientShopData.billTodayOut);
                V2.text(c, outTxt, V2.FS_XS, IOStyle.RED, ix + iw * 0.52F, rowY, iw * 0.48F, Text.Align.RIGHT);
            }
        }
    }

    // ------------------------------------------------------------------ 右栏：签到联动 + 公告轮播

    private void renderRightColumn(Canvas c, PageFrame f, float x, float y, float w, float h) {
        float headerH = 26.0F;
        renderRightHeader(c, f, x, y, w, headerH);

        float cardY = y + headerH + 6.0F;
        float cardH = Math.max(100.0F, h - headerH - 6.0F);
        renderAnnouncementCard(c, f, x, cardY, w, cardH);
    }

    private void renderRightHeader(Canvas c, PageFrame f, float x, float y, float w, float h) {
        Draw.fill(c, x, y, w, h, 5.0F, IOStyle.CARD);
        Draw.stroke(c, x, y, w, h, 5.0F, 0.75F, IOStyle.BORDER_DIM);

        float cy = y + h * 0.5F;
        float bh = V2.CTRL;
        float by = cy - bh * 0.5F;
        float right = x + w - 7.0F;

        // 签到联动按钮（仅按钮，无日历；开启后未签到高亮，已签到原地显示连签天数）
        if (ClientShopData.signInEnabled) {
            boolean done = ClientShopData.signInDone;
            String signLabel;
            if (done) {
                signLabel = tr("gui.module_shop.home.signin_done", "已签到 · 连签 %s 天", ClientShopData.signInStreak);
            } else {
                int nextStreak = ClientShopData.signInStreak + 1;
                boolean hasGain = ClientShopData.signInBaseSpec.isValid()
                        || SignInPage.tierBonusSpecAt(nextStreak).isValid();
                signLabel = hasGain
                        ? tr("gui.module_shop.home.signin_do_gain", "立即签到 +%s",
                                SignInPage.formatDailyGain(nextStreak))
                        : tr("gui.module_shop.home.signin_do", "立即签到");
            }
            float sw = Math.min(w * 0.48F, V2.buttonWidth(signLabel) + (done ? 8.0F : 6.0F));
            float sx = right - sw;
            if (done) {
                float hv = hover(f, "signin_done", sx, by, sw, bh);
                Draw.fill(c, sx, by, sw, bh, V2.R, Draw.alpha(IOStyle.GREEN, 0.14F + 0.06F * hv));
                Draw.stroke(c, sx, by, sw, bh, V2.R, 0.75F, Draw.alpha(IOStyle.GREEN, 0.48F + 0.22F * hv));
                // 绿色对勾小点
                float cx = sx + 7.5F;
                Draw.line(c, cx - 2.0F, cy, cx - 0.5F, cy + 1.6F, 1.0F, IOStyle.GREEN);
                Draw.line(c, cx - 0.5F, cy + 1.6F, cx + 2.2F, cy - 1.8F, 1.0F, IOStyle.GREEN);
                V2.bold(c, signLabel, V2.FS_S, IOStyle.GREEN, sx + 12.0F, cy, Math.max(10.0F, sw - 16.0F),
                        Text.Align.CENTER);
                if (over(f, sx, by, sw, bh)) {
                    f.host().textTooltip(Component.literal(tr("gui.module_shop.home.signin_done_tip",
                            "今日已签到 · 当前连签 %s 天（历史最高 %s 天）",
                            ClientShopData.signInStreak, Math.max(ClientShopData.signInBest, ClientShopData.signInStreak))));
                }
            } else {
                float hv = hover(f, "signin_do", sx, by, sw, bh);
                V2.button(c, signLabel, V2.Btn.PRIMARY, sx, by, sw, bh, hv);
                this.signInRect = new float[]{sx, by, sw, bh};
                if (over(f, sx, by, sw, bh)) {
                    int nextStreak = ClientShopData.signInStreak + 1;
                    String gain = SignInPage.formatDailyGain(nextStreak);
                    f.host().textTooltip(Component.literal(tr("gui.module_shop.home.signin_todo_tip",
                            "点击立即领取今日签到奖励 +%s（当前连签 %s 天）",
                            gain, ClientShopData.signInStreak)));
                }
            }
            right = sx - 6.0F;
        }

        // 管理员「管理公告」紧凑按钮
        if (ClientShopData.canAdmin) {
            String admLabel = tr("gui.module_shop.home.ann_manage", "管理公告");
            float aw = Math.min(right - (x + 66.0F), V2.buttonWidth(admLabel));
            if (aw >= 38.0F) {
                float ax = right - aw;
                float hv = hover(f, "ann_admin", ax, by, aw, bh);
                V2.button(c, admLabel, V2.Btn.NORMAL, ax, by, aw, bh, hv);
                this.adminAnnRect = new float[]{ax, by, aw, bh};
                right = ax - 6.0F;
            }
        }

        // 标题与副标（严格限制宽度防溢出）
        float lx = x + 9.0F;
        Draw.fill(c, lx, cy - 4.5F, 2.5F, 9.0F, 1.25F, IOStyle.BLUE);
        String heading = tr("gui.module_shop.home.ann_title", "服务器公告");
        float avail = Math.max(20.0F, right - (lx + 7.0F));
        float hw = V2.bold(c, heading, V2.FS_T, IOStyle.TEXT, lx + 7.0F, cy, avail, Text.Align.LEFT);

        List<AnnouncementView> list = ClientHomeCurrencyState.announcements;
        if (!list.isEmpty() && avail - hw > 32.0F) {
            String countStr = tr("gui.module_shop.home.ann_count", "%s 条", list.size());
            V2.text(c, countStr, V2.FS_XS, IOStyle.TEXT_3, lx + 7.0F + hw + 6.0F, cy,
                    Math.max(0.0F, avail - hw - 6.0F), Text.Align.LEFT);
        }
    }

    private void renderAnnouncementCard(Canvas c, PageFrame f, float x, float y, float w, float h) {
        Draw.fill(c, x, y, w, h, 5.0F, IOStyle.CARD);
        Draw.stroke(c, x, y, w, h, 5.0F, 0.75F, IOStyle.BORDER_DIM);
        this.cardRect = new float[]{x, y, w, h};

        List<AnnouncementView> list = ClientHomeCurrencyState.announcements;
        if (list.isEmpty()) {
            String empty = tr("gui.module_shop.home.ann_empty", "暂无服务器公告");
            V2.empty(c, empty, x, y, w, h - (ClientShopData.canAdmin ? 14.0F : 0.0F));
            if (ClientShopData.canAdmin) {
                V2.text(c, tr("gui.module_shop.home.ann_empty_admin", "管理员可点击上方「管理公告」新建与置顶公告"),
                        V2.FS_XS, IOStyle.TEXT_3, x + 12.0F, y + h * 0.5F + 12.0F, w - 24.0F, Text.Align.CENTER);
            }
            return;
        }

        // 热同步更新后，尽量跟随当前阅读中的公告 id，否则夹紧下标
        if (!trackedAnnouncementId.isEmpty()) {
            int found = findIndexById(list, trackedAnnouncementId);
            if (found >= 0) {
                carouselIndex = found;
            }
        }
        if (carouselIndex < 0 || carouselIndex >= list.size()) {
            carouselIndex = 0;
        }

        boolean hoveringCard = over(f, x, y, w, h);
        boolean paused = hoveringCard || BODY_SCROLL.isDragging() || AnnouncementBlockRenderer.LightboxScreen.isOpen();
        if (list.size() > 1 && !paused) {
            float dt = Math.max(0.0F, Math.min(80.0F, f.dtMs()));
            carouselElapsedMs += dt;
            if (carouselElapsedMs >= AUTO_ROTATE_MS) {
                carouselElapsedMs = 0.0F;
                stepCarousel(list, 1);
            }
        }

        AnnouncementView cur = list.get(carouselIndex);
        trackedAnnouncementId = cur.id == null ? "" : cur.id;

        float footH = 22.0F;
        float progressH = 2.0F;
        float footTop = y + h - footH;
        float progressY = footTop - progressH;

        // 切页淡入+微滑过渡
        float transT = slideTransNanos == 0L ? 1.0F
                : Math.min(1.0F, (System.nanoTime() - slideTransNanos) / 1_000_000.0F / SLIDE_TRANS_MS);
        float transE = Motion.easeOutCubic(transT);
        float offsetX = slideDir * 10.0F * (1.0F - transE);

        int contentSaved = c.save();
        Draw.clip(c, x + 1.0F, y + 1.0F, w - 2.0F, Math.max(10.0F, progressY - y - 1.0F));
        int layerSaved = Draw.beginOpacity(c, 0.35F + 0.65F * transE);
        try {
            c.translate(offsetX, 0.0F);
            float ix = x + 11.0F;
            float iw = Math.max(40.0F, w - 22.0F);
            float metaY = y + 12.0F;
            float cursorX = ix;

            // 置顶徽标
            if (cur.pinned) {
                String pinTxt = tr("gui.module_shop.home.ann_pinned", "置顶");
                cursorX += V2.tag(c, pinTxt, IOStyle.ORANGE, cursorX, metaY);
            }
            // 分类徽标
            if (cur.category != null && !cur.category.isBlank()) {
                cursorX += V2.tag(c, cur.category, IOStyle.BLUE, cursorX, metaY);
            }
            // 若存在置顶公告但当前已轮播到非置顶公告，提供快捷回顶按钮
            if (!cur.pinned && hasPinned(list)) {
                String backPin = tr("gui.module_shop.home.ann_back_pinned", "查看置顶");
                float pw = V2.width(backPin, V2.FS_XS, false) + 8.0F;
                if (cursorX + pw + 60.0F < ix + iw) {
                    float phv = hover(f, "jump_pin", cursorX, metaY - 5.0F, pw, 10.0F);
                    Draw.fill(c, cursorX, metaY - 5.0F, pw, 10.0F, 2.0F,
                            Draw.alpha(IOStyle.ORANGE, 0.12F + 0.10F * phv));
                    V2.text(c, backPin, V2.FS_XS, IOStyle.ORANGE, cursorX, metaY, pw, Text.Align.CENTER);
                    this.jumpPinnedRect = new float[]{cursorX, metaY - 5.0F, pw, 10.0F};
                    cursorX += pw + 4.0F;
                }
            }
            // 右侧日期
            if (cur.date != null && !cur.date.isBlank() && ix + iw - cursorX > 24.0F) {
                V2.text(c, cur.date, V2.FS_XS, IOStyle.TEXT_3, cursorX, metaY,
                        Math.max(0.0F, ix + iw - cursorX), Text.Align.RIGHT);
            }

            // 公告单行标题（严格 maxWidth 截断防溢出）
            float titleY = y + 27.0F;
            String rawTitle = cur.title == null || cur.title.isBlank()
                    ? tr("gui.module_shop.home.ann_untitled", "未命名公告")
                    : cur.title;
            List<StyledText.Run> titleRuns = ClientHomeCurrencyState.parseStyled(rawTitle, IOStyle.TEXT);
            StyledText.Line titleLine = StyledText.singleLine(titleRuns, iw, V2.FS_T);
            StyledText.draw(c, titleLine, ix, titleY - StyledText.lineHeight(V2.FS_T) * 0.5F, V2.FS_T);

            float divY = y + 38.0F;
            Draw.hairline(c, ix, divY, iw, Draw.alpha(IOStyle.BORDER_DIM, 0.8F));

            // 长公告可滚动图文混排正文区
            float bodyY = divY + 6.0F;
            float bodyH = Math.max(20.0F, progressY - bodyY - 5.0F);
            this.bodyRect = new float[]{ix, bodyY, iw, bodyH};
            this.blockRenderer.render(c, BODY_SCROLL, ix, bodyY, iw, bodyH,
                    Math.max(72.0F, bodyH * 0.72F),
                    cur.effectiveBlocks(),
                    (cur.id == null ? "" : cur.id) + "|" + carouselIndex,
                    V2.FS, 2.5F, IOStyle.TEXT_2, f.mouseX(), f.mouseY(), f.dtMs(), true);
        } finally {
            c.restoreToCount(layerSaved);
            c.restoreToCount(contentSaved);
        }

        // 轮播进度条（悬停暂停时变柔灰提示）
        if (list.size() > 1) {
            Draw.fill(c, x + 1.0F, progressY, w - 2.0F, 1.5F, 0.0F, Draw.alpha(IOStyle.BORDER_DIM, 0.6F));
            float pct = Math.max(0.0F, Math.min(1.0F, carouselElapsedMs / AUTO_ROTATE_MS));
            int progColor = hoveringCard ? IOStyle.TEXT_3 : Draw.alpha(IOStyle.BLUE, 0.85F);
            Draw.fill(c, x + 1.0F, progressY, (w - 2.0F) * pct, 1.5F, 0.0F, progColor);
        } else {
            Draw.hairline(c, x + 1.0F, progressY, w - 2.0F, IOStyle.BORDER_DIM);
        }

        // 底栏：左右箭头 + 圆点指示器 + 页码/悬停暂停状态
        renderCarouselFooter(c, f, x, footTop, w, footH, list, hoveringCard);
    }

    private void renderCarouselFooter(Canvas c, PageFrame f, float x, float y, float w, float h,
                                      List<AnnouncementView> list, boolean hoveringCard) {
        int saved = c.save();
        c.clipRRect(RRect.makeXYWH(x, y, w, h, 4.0F), true);
        Draw.fill(c, x, y, w, h, 0.0F, Draw.alpha(IOStyle.SIDEBAR, 0.55F));
        c.restoreToCount(saved);

        float cy = y + h * 0.5F;
        float arrowW = 18.0F;
        float arrowH = 14.0F;
        float ay = cy - arrowH * 0.5F;
        boolean multi = list.size() > 1;

        float prevX = x + 8.0F;
        float prevHv = multi ? hover(f, "ann_prev", prevX, ay, arrowW, arrowH) : 0.0F;
        V2.button(c, "<", multi ? V2.Btn.NORMAL : V2.Btn.DISABLED, prevX, ay, arrowW, arrowH, prevHv);
        this.prevRect = multi ? new float[]{prevX, ay, arrowW, arrowH} : null;

        float nextX = x + w - 8.0F - arrowW;
        float nextHv = multi ? hover(f, "ann_next", nextX, ay, arrowW, arrowH) : 0.0F;
        V2.button(c, ">", multi ? V2.Btn.NORMAL : V2.Btn.DISABLED, nextX, ay, arrowW, arrowH, nextHv);
        this.nextRect = multi ? new float[]{nextX, ay, arrowW, arrowH} : null;

        // 页码与暂停提示在右侧箭头左方
        String idxTxt = (carouselIndex + 1) + " / " + list.size();
        String statusTxt = (multi && hoveringCard)
                ? tr("gui.module_shop.home.ann_paused", "已暂停") + " · " + idxTxt
                : idxTxt;
        float statusW = Math.min(72.0F, V2.width(statusTxt, V2.FS_XS, false) + 6.0F);
        float statusX = nextX - statusW - 6.0F;
        V2.text(c, statusTxt, V2.FS_XS, hoveringCard && multi ? IOStyle.ORANGE : IOStyle.TEXT_3,
                statusX, cy, statusW, Text.Align.RIGHT);

        // 圆点指示器放在 [prevX + arrowW + 6, statusX - 6] 内，超出宽度自动降级为紧凑计数条
        float dotsLeft = prevX + arrowW + 8.0F;
        float dotsAvail = Math.max(0.0F, statusX - 8.0F - dotsLeft);
        if (list.size() <= 14 && dotsAvail >= list.size() * 8.0F) {
            float dotGap = 4.0F;
            float totalDotsW = 0.0F;
            for (int i = 0; i < list.size(); i++) {
                totalDotsW += (i == carouselIndex ? 12.0F : 5.0F);
                if (i + 1 < list.size()) {
                    totalDotsW += dotGap;
                }
            }
            float dx = dotsLeft + Math.max(0.0F, (dotsAvail - totalDotsW) * 0.5F);
            for (int i = 0; i < list.size(); i++) {
                boolean active = i == carouselIndex;
                boolean pinned = list.get(i).pinned;
                float dw = active ? 12.0F : 5.0F;
                float dh = 4.0F;
                float dy = cy - dh * 0.5F;
                boolean dHover = over(f, dx - 1.5F, ay, dw + 3.0F, arrowH);
                int dotColor = active
                        ? (pinned ? IOStyle.ORANGE : IOStyle.BLUE)
                        : (pinned ? Draw.alpha(IOStyle.ORANGE, dHover ? 0.9F : 0.55F)
                                : (dHover ? IOStyle.TEXT_2 : IOStyle.BORDER));
                Draw.fill(c, dx, dy, dw, dh, dh * 0.5F, dotColor);
                this.dotRects.add(new float[]{dx - 1.5F, ay, dw + 3.0F, arrowH});
                dx += dw + dotGap;
            }
        }
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        if (button != 0) {
            return false;
        }
        if (in(this.signInRect, mx, my)) {
            ClickSound.play();
            f.host().action("sign_in", null);
            return true;
        }
        if (in(this.adminAnnRect, mx, my)) {
            ClickSound.play();
            ClientAdminAuth.guard(() -> Nav.open(new AnnouncementAdminScreen()));
            return true;
        }
        if (in(this.exchangeRect, mx, my)) {
            ClickSound.play();
            Nav.open(new PhysicalCurrencyExchangeScreen());
            return true;
        }
        if (in(this.billRect, mx, my)) {
            ClickSound.play();
            f.host().action("open_bill", null);
            return true;
        }
        if (in(this.jumpPinnedRect, mx, my)) {
            ClickSound.play();
            jumpToIndex(ClientHomeCurrencyState.announcements, 0, -1);
            return true;
        }
        if (in(this.prevRect, mx, my)) {
            ClickSound.play();
            stepCarousel(ClientHomeCurrencyState.announcements, -1);
            return true;
        }
        if (in(this.nextRect, mx, my)) {
            ClickSound.play();
            stepCarousel(ClientHomeCurrencyState.announcements, 1);
            return true;
        }
        for (int i = 0; i < this.dotRects.size(); i++) {
            if (in(this.dotRects.get(i), mx, my)) {
                ClickSound.play();
                jumpToIndex(ClientHomeCurrencyState.announcements, i, i >= carouselIndex ? 1 : -1);
                return true;
            }
        }
        if (this.bodyRect != null && BODY_SCROLL.pressBar(mx, my,
                this.bodyRect[0], this.bodyRect[1], this.bodyRect[2] + 4.0F, this.bodyRect[3])) {
            return true;
        }
        AnnouncementBlockRenderer.ImageHit imgHit = this.blockRenderer.hitTestImage(mx, my);
        if (imgHit != null) {
            ClickSound.play();
            Nav.open(new AnnouncementBlockRenderer.LightboxScreen(
                    imgHit.mediaId(), imgHit.caption(), imgHit.imgW(), imgHit.imgH()));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        return BODY_SCROLL.releaseBar();
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        if (this.bodyRect != null && BODY_SCROLL.dragBar(my, this.bodyRect[3])) {
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        if (in(this.cardRect, mx, my) && BODY_SCROLL.canScroll()) {
            BODY_SCROLL.wheel(scrollY);
            return true;
        }
        return false;
    }

    @Override
    public void cancelInput() {
        BODY_SCROLL.releaseBar();
    }

    // ------------------------------------------------------------------ 轮播辅助

    private static void stepCarousel(List<AnnouncementView> list, int delta) {
        if (list.isEmpty()) {
            return;
        }
        int next = (carouselIndex + delta) % list.size();
        if (next < 0) {
            next += list.size();
        }
        jumpToIndex(list, next, delta >= 0 ? 1 : -1);
    }

    private static void jumpToIndex(List<AnnouncementView> list, int next, int dir) {
        if (list.isEmpty()) {
            return;
        }
        int clamped = Math.max(0, Math.min(list.size() - 1, next));
        if (clamped == carouselIndex) {
            carouselElapsedMs = 0.0F;
            return;
        }
        carouselIndex = clamped;
        trackedAnnouncementId = list.get(clamped).id == null ? "" : list.get(clamped).id;
        carouselElapsedMs = 0.0F;
        slideDir = dir;
        slideTransNanos = System.nanoTime();
        BODY_SCROLL.reset();
    }

    private static int findIndexById(List<AnnouncementView> list, String id) {
        if (id == null || id.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(list.get(i).id)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasPinned(List<AnnouncementView> list) {
        for (AnnouncementView v : list) {
            if (v.pinned) {
                return true;
            }
        }
        return false;
    }
}
