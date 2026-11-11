package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.V2;
import io.github.humbleui.skija.Canvas;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 货币兑换（UI v2 列表视图）：货币物品 · 汇率 · 背包持有 · 全部兑换可得 · 余额可取出 · 兑换 / 取款
 * （管理员另有改价 / 删除，以及顶部「添加货币」）。动作字符串与旧界面一致。
 */
public final class ExchangePage implements ShopPage {
    private static final ListTable TABLE = new ListTable();
    private float[] addRect;

    @Override
    public String id() {
        return "exchange";
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
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
        if (ClientShopData.canAdmin) {
            String al = tr("gui.module_shop.v2.exchange.add");
            float aw = V2.buttonWidth(al);
            right -= aw;
            boolean hv = f.mouseX() >= right && f.mouseX() < right + aw && f.mouseY() >= by && f.mouseY() < by + V2.CTRL;
            V2.button(c, al, V2.Btn.NORMAL, right, by, aw, V2.CTRL, hv ? 1 : 0);
            addRect = new float[]{right, by, aw, V2.CTRL};
            right -= 8;
        }
        long total = 0;
        for (ClientShopData.ExchangeView v : ClientShopData.exchange) {
            total += v.owned * v.value;
        }
        if (total > 0) {
            V2.text(c, tr("gui.module_shop.v2.exchange.total", V2.money(total)), V2.FS_XS, IOStyle.TEXT_2, x + 8 + tw + 12,
                    cy, right - (x + 8 + tw + 12), Text.Align.RIGHT);
        }

        float top = f.y() + V2.BAR;
        float bottom = f.y() + f.h() - V2.FOOT;
        List<ClientShopData.ExchangeView> list = ClientShopData.exchange;
        float[][] cols = V2.columns(x, f.w(), 10, 6, 14, 0, 90, 64, 84, 84, ClientShopData.canAdmin ? 170 : 92);
        String[] heads = {"", tr("gui.module_shop.v2.col.currency_item"), tr("gui.module_shop.v2.col.rate"),
                tr("gui.module_shop.v2.col.have"), tr("gui.module_shop.v2.col.exchange_all"), tr("gui.module_shop.v2.col.withdrawable"), ""};
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.RIGHT, Text.Align.RIGHT, Text.Align.RIGHT,
                Text.Align.RIGHT, Text.Align.RIGHT};
        TABLE.layout(x, top, f.w(), bottom - top, cols, heads, al, null);
        if (list.isEmpty()) {
            V2.tableHead(c, x, top, f.w(), cols, heads, al, -1, false);
            V2.empty(c, tr("gui.module_shop.exchange.empty"), x, top + V2.HEAD, f.w(), bottom - top - V2.HEAD);
        } else {
            TABLE.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), list.size(), (cv, row, ry, hover) -> {
                ClientShopData.ExchangeView v = list.get(row);
                float ry2 = ry + V2.ROW / 2;
                f.host().items().add(v.stack, cols[0][0], ry + (V2.ROW - V2.ICON) / 2, V2.ICON, false);
                V2.text(cv, v.stack.getHoverName().getString(), V2.FS, IOStyle.TEXT, cols[1][0], ry2, cols[1][1], Text.Align.LEFT);
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
                float bx = cols[6][0] + cols[6][1];
                if (ClientShopData.canAdmin) {
                    bx = TABLE.button(cv, row, "remove", tr("gui.module_shop.v2.remove"), V2.Btn.DANGER, bx, ry, hover);
                    bx = TABLE.button(cv, row, "edit", tr("gui.module_shop.v2.edit"), V2.Btn.GHOST, bx, ry, hover);
                }
                bx = TABLE.button(cv, row, "withdraw", tr("gui.module_shop.v2.exchange.withdraw"),
                        can > 0 ? V2.Btn.NORMAL : V2.Btn.DISABLED, bx, ry, hover);
                TABLE.button(cv, row, "deposit", tr("gui.module_shop.v2.exchange.deposit"),
                        v.owned > 0 ? V2.Btn.NORMAL : V2.Btn.DISABLED, bx, ry, hover);
            });
        }
        V2.foot(c, x, bottom, f.w(), tr("gui.module_shop.v2.exchange.count", list.size()), tr("gui.module_shop.v2.exchange.hint"));
    }

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        if (button != 0) {
            return false;
        }
        if (addRect != null && mx >= addRect[0] && mx < addRect[0] + addRect[2] && my >= addRect[1] && my < addRect[1] + addRect[3]) {
            ClickSound.play();
            f.host().action("admin_exchange_add", null);
            return true;
        }
        if (TABLE.pressBar(mx, my)) {
            return true;
        }
        ListTable.Hit hit = TABLE.click(mx, my);
        if (hit == null || hit.button() == null || hit.row() < 0) {
            return hit != null;
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("index", hit.row());
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
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        return TABLE.releaseBar();
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        return TABLE.dragBar(my);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        return TABLE.wheel(mx, my, scrollY, false);
    }

    @Override
    public void cancelInput() {
        TABLE.releaseBar();
    }
}
