package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.Nav;
import cn.autoforged.module_shop.client.screen.PhysicalCurrencyExchangeScreen;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 货币兑换（UI v2 列表视图）：货币物品 · 汇率 · 背包持有 · 全部兑换可得 · 余额可取出 · 兑换 / 取款
 * （管理员另有改价 / 删除，以及顶部「添加货币」）。动作字符串与旧界面一致。
 */
public final class ExchangePage implements ShopPage {
    private static final ListTable TABLE = new ListTable();
    private static String query = "";

    private SkiaTextField search;
    private List<Integer> shown = List.of();
    private float[] addRect;
    private float[] physExRect;

    @Override
    public String id() {
        return "exchange";
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private static String formatLimitCell(ClientShopData.ExchangeView v) {
        if (v.dailyDepositLimit <= 0 && v.dailyWithdrawLimit <= 0) {
            return "∞";
        }
        String dep = v.dailyDepositLimit > 0 ? ("存" + v.depositUsed + "/" + v.dailyDepositLimit) : "存∞";
        String wdr = v.dailyWithdrawLimit > 0 ? ("取" + v.withdrawUsed + "/" + v.dailyWithdrawLimit) : "取∞";
        return dep + " " + wdr;
    }

    @Override
    public void init(ShopHost host) {
        search = new SkiaTextField(host.screen(), host.font(), 48)
                .placeholder(tr("gui.module_shop.search.placeholder"))
                .clearable(true)
                .fontSize(V2.FS_S)
                .onChange(t -> {
                    query = t == null ? "" : t;
                    TABLE.scrollToTop();
                });
        search.setValue(query);
        host.registerInput(search.editBox());
    }

    @Override
    public void render(Canvas c, PageFrame f) {
        float x = f.x();
        float cy = f.y() + V2.BAR / 2;
        float by = cy - V2.CTRL / 2;
        Draw.hairline(c, x, f.y() + V2.BAR - 0.5F, f.w(), IOStyle.BORDER_DIM);
        float tw = V2.bold(c, tr("tab.module_shop.exchange"), V2.FS_T, IOStyle.TEXT, x + 8, cy, 90, Text.Align.LEFT);
        float right = x + f.w() - 8;
        addRect = null;
        physExRect = null;
        if (ClientShopData.canAdmin) {
            String al = tr("gui.module_shop.v2.exchange.add");
            float aw = V2.buttonWidth(al);
            right -= aw;
            boolean hv = f.mouseX() >= right && f.mouseX() < right + aw && f.mouseY() >= by && f.mouseY() < by + V2.CTRL;
            V2.button(c, al, V2.Btn.NORMAL, right, by, aw, V2.CTRL, hv ? 1 : 0);
            addRect = new float[]{right, by, aw, V2.CTRL};
            right -= 6;
        }
        if (ClientHomeCurrencyState.physEnabled) {
            String pl = tr("gui.module_shop.exchange.phys_btn");
            float pw = V2.buttonWidth(pl);
            right -= pw;
            boolean hv = f.mouseX() >= right && f.mouseX() < right + pw && f.mouseY() >= by && f.mouseY() < by + V2.CTRL;
            V2.button(c, pl, V2.Btn.PRIMARY, right, by, pw, V2.CTRL, hv ? 1 : 0);
            physExRect = new float[]{right, by, pw, V2.CTRL};
            right -= 6;
        }
        List<ClientShopData.ExchangeView> list = ClientShopData.exchange;
        List<Integer> nextShown = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < list.size(); i++) {
            ClientShopData.ExchangeView v = list.get(i);
            total += v.owned * v.value;
            if (query.isBlank() || PageKit.matchesItemQuery(v.stack, query)) {
                nextShown.add(i);
            }
        }
        shown = nextShown;
        float sx = x + 8 + tw + 10;
        float avail = right - 8 - sx;
        float afterSearch = sx;
        if (search != null) {
            if (avail >= 52.0F) {
                float sw = Math.min(145.0F, Math.max(52.0F, avail >= 220.0F ? 120.0F : avail));
                search.visible(true).bounds(sx, by, sw, V2.CTRL);
                search.render(c, f.mouseX(), f.mouseY(), f.dtMs());
                afterSearch = sx + sw + 8.0F;
            } else {
                search.visible(false);
            }
        }
        String topInfo = "";
        if (total > 0) {
            topInfo = tr("gui.module_shop.v2.exchange.total", V2.money(total));
        } else if (ClientHomeCurrencyState.physEnabled) {
            topInfo = ClientHomeCurrencyState.tr("gui.module_shop.v2.exchange.dual_note",
                    "星币物品兑换与实体货币账本相互独立");
        }
        if (!topInfo.isEmpty() && right - 8 - afterSearch >= 65.0F) {
            V2.text(c, topInfo, V2.FS_XS, IOStyle.TEXT_2, afterSearch,
                    cy, right - 8 - afterSearch, Text.Align.RIGHT);
        }

        float top = f.y() + V2.BAR;
        float bottom = f.y() + f.h() - V2.FOOT;
        float actW = V2.buttonWidth(tr("gui.module_shop.v2.exchange.withdraw"))
                + V2.buttonWidth(tr("gui.module_shop.v2.exchange.deposit")) + 3.0F;
        if (ClientShopData.canAdmin) {
            actW += V2.buttonWidth(tr("gui.module_shop.v2.remove"))
                    + V2.buttonWidth(tr("gui.module_shop.v2.edit")) + 6.0F;
        }
        actW += 2.0F;
        float gap = 5.0F;
        float[][] baseCols = V2.columns(x, f.w() - actW - gap, 10, gap, 14, 0, 62, 40, 56, 56, 68);
        float[][] cols = new float[8][2];
        System.arraycopy(baseCols, 0, cols, 0, 7);
        cols[7][0] = x + f.w() - 10 - actW;
        cols[7][1] = actW;
        String[] heads = {"", tr("gui.module_shop.v2.col.currency_item"), tr("gui.module_shop.v2.col.rate"),
                tr("gui.module_shop.v2.col.have"), tr("gui.module_shop.v2.col.exchange_all"), tr("gui.module_shop.v2.col.withdrawable"),
                Component.translatableWithFallback("gui.module_shop.v2.col.daily_limit", "今日限额").getString(), ""};
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.RIGHT, Text.Align.RIGHT, Text.Align.RIGHT,
                Text.Align.RIGHT, Text.Align.RIGHT, Text.Align.RIGHT};
        TABLE.layout(x, top, f.w(), bottom - top, cols, heads, al, null);
        if (shown.isEmpty()) {
            V2.tableHead(c, x, top, f.w(), cols, heads, al, -1, false);
            V2.empty(c, tr(list.isEmpty() ? "gui.module_shop.exchange.empty" : "gui.module_shop.v2.no_match"),
                    x, top + V2.HEAD, f.w(), bottom - top - V2.HEAD);
        } else {
            TABLE.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), shown.size(), (cv, row, ry, hover) -> {
                ClientShopData.ExchangeView v = list.get(shown.get(row));
                float ry2 = ry + V2.ROW / 2;
                f.host().items().add(v.stack, cols[0][0], ry + (V2.ROW - V2.ICON) / 2, V2.ICON, false);
                V2.text(cv, cn.autoforged.module_shop.client.ui.ItemNames.display(v.stack), V2.FS, IOStyle.TEXT, cols[1][0], ry2, cols[1][1], Text.Align.LEFT);
                V2.bold(cv, tr("gui.module_shop.v2.exchange.rate", V2.money(v.value)), V2.FS_S, IOStyle.ORANGE, cols[2][0], ry2,
                        cols[2][1], Text.Align.RIGHT);
                if (v.owned > 0) {
                    V2.bold(cv, V2.money(v.owned), V2.FS, IOStyle.TEXT, cols[3][0], ry2, cols[3][1], Text.Align.RIGHT);
                    V2.bold(cv, V2.signed(v.owned * v.value), V2.FS, IOStyle.GREEN, cols[4][0], ry2, cols[4][1], Text.Align.RIGHT);
                } else {
                    V2.text(cv, "—", V2.FS, IOStyle.TEXT_3, cols[3][0], ry2, cols[3][1], Text.Align.RIGHT);
                    V2.text(cv, "—", V2.FS, IOStyle.TEXT_3, cols[4][0], ry2, cols[4][1], Text.Align.RIGHT);
                }
                long can = v.value <= 0 ? 0 : ClientShopData.balance / v.value;
                V2.text(cv, tr("gui.module_shop.v2.exchange.pieces", V2.money(can)), V2.FS_S, IOStyle.TEXT_2, cols[5][0], ry2,
                        cols[5][1], Text.Align.RIGHT);
                int remDep = v.remainingDeposit();
                int remWdr = v.remainingWithdraw();
                String limitText = formatLimitCell(v);
                int limitColor = (v.dailyDepositLimit > 0 && remDep <= 0) || (v.dailyWithdrawLimit > 0 && remWdr <= 0)
                        ? IOStyle.RED
                        : (v.dailyDepositLimit > 0 || v.dailyWithdrawLimit > 0 ? IOStyle.ORANGE : IOStyle.TEXT_3);
                V2.text(cv, limitText, V2.FS_XS, limitColor, cols[6][0], ry2, cols[6][1], Text.Align.RIGHT);
                float bx = cols[7][0] + cols[7][1];
                if (ClientShopData.canAdmin) {
                    bx = TABLE.button(cv, row, "remove", tr("gui.module_shop.v2.remove"), V2.Btn.DANGER, bx, ry, hover);
                    bx = TABLE.button(cv, row, "edit", tr("gui.module_shop.v2.edit"), V2.Btn.GHOST, bx, ry, hover);
                }
                boolean canWithdraw = can > 0 && remWdr > 0;
                boolean canDeposit = v.owned > 0 && remDep > 0;
                bx = TABLE.button(cv, row, "withdraw", tr("gui.module_shop.v2.exchange.withdraw"),
                        canWithdraw ? V2.Btn.NORMAL : V2.Btn.DISABLED, bx, ry, hover);
                TABLE.button(cv, row, "deposit", tr("gui.module_shop.v2.exchange.deposit"),
                        canDeposit ? V2.Btn.NORMAL : V2.Btn.DISABLED, bx, ry, hover);
            });
        }
        String footLeft = query.isBlank()
                ? tr("gui.module_shop.v2.exchange.count", list.size())
                : tr("gui.module_shop.v2.showing", TABLE.firstVisible(), TABLE.lastVisible(), shown.size());
        V2.foot(c, x, bottom, f.w(), footLeft, tr("gui.module_shop.v2.exchange.hint"));
    }

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        if (search != null && search.isVisible() && search.mouseClicked(mx, my, button)) {
            return true;
        }
        if (search != null && search.isFocused()) {
            search.blur();
        }
        if (button != 0) {
            return false;
        }
        if (addRect != null && mx >= addRect[0] && mx < addRect[0] + addRect[2] && my >= addRect[1] && my < addRect[1] + addRect[3]) {
            ClickSound.play();
            f.host().action("admin_exchange_add", null);
            return true;
        }
        if (physExRect != null && mx >= physExRect[0] && mx < physExRect[0] + physExRect[2]
                && my >= physExRect[1] && my < physExRect[1] + physExRect[3]) {
            ClickSound.play();
            net.minecraft.client.Minecraft.getInstance().execute(
                    () -> Nav.open(new PhysicalCurrencyExchangeScreen()));
            return true;
        }
        if (TABLE.pressBar(mx, my)) {
            return true;
        }
        ListTable.Hit hit = TABLE.click(mx, my);
        if (hit == null || hit.button() == null || hit.row() < 0 || hit.row() >= shown.size()) {
            return hit != null;
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("index", shown.get(hit.row()));
        String action = switch (hit.button()) {
            case "deposit" -> "exchange_deposit_gui";
            case "withdraw" -> "exchange_withdraw_gui";
            case "edit" -> "exchange_edit";
            case "remove" -> "admin_exchange_remove";
            default -> null;
        };
        if (action != null) {
            ClickSound.play();
            f.host().action(action, tag);
        }
        return true;
    }

    @Override
    public void clickedOutside() {
        if (search != null && search.isFocused()) {
            search.blur();
        }
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        if (search != null) {
            search.mouseReleased(mx, my, button);
        }
        return TABLE.releaseBar();
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        if (search != null && search.mouseDragged(mx, my, button, dx, dy)) {
            return true;
        }
        return TABLE.dragBar(my);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        return TABLE.wheel(mx, my, scrollY, false);
    }

    @Override
    public void cancelInput() {
        if (search != null) {
            search.visible(false);
        }
        TABLE.releaseBar();
    }
}
