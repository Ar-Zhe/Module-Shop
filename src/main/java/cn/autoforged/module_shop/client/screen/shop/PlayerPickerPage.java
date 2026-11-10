package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 交易 / 转账（UI v2 列表视图）：在线玩家表（搜索、发起交易 / 转账）。
 * 交易页顶部直接列出收到的交易请求，可就地接受 / 拒绝（与「交易请求」界面同一套动作）。
 */
public final class PlayerPickerPage implements ShopPage {
    private static final Map<Boolean, String> SAVED_QUERY = new HashMap<>();
    private static final Map<Boolean, ListTable> TABLES = new HashMap<>();
    private static final float REQ_H = 24.0F;

    private final boolean transfer;
    private SkiaTextField search;
    private List<ClientShopData.OnlinePlayer> shown = List.of();
    private final List<float[]> btnRects = new ArrayList<>();
    private final List<Runnable> btnRuns = new ArrayList<>();

    public PlayerPickerPage(boolean transfer) {
        this.transfer = transfer;
    }

    @Override
    public String id() {
        return transfer ? "transfer" : "trade";
    }

    private ListTable table() {
        return TABLES.computeIfAbsent(transfer, k -> new ListTable());
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    @Override
    public void init(ShopHost host) {
        String value = search == null ? SAVED_QUERY.getOrDefault(transfer, "") : search.value();
        search = new SkiaTextField(host.screen(), host.font(), 32)
                .placeholder(tr("gui.module_shop.trade.search_hint")).fontSize(V2.FS_S)
                .onChange(text -> SAVED_QUERY.put(transfer, text));
        search.setValue(value);
        host.registerInput(search.editBox());
    }

    private List<ClientShopData.OnlinePlayer> players() {
        String self = ClientShopData.playerName;
        String q = search == null ? "" : search.value().trim().toLowerCase(Locale.ROOT);
        List<ClientShopData.OnlinePlayer> out = new ArrayList<>();
        for (ClientShopData.OnlinePlayer p : ClientShopData.onlinePlayers) {
            if (p.name.equals(self)) {
                continue;
            }
            if (!q.isEmpty() && !p.name.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            out.add(p);
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    private static boolean over(PageFrame f, float x, float y, float w, float h) {
        return f.mouseX() >= x && f.mouseX() < x + w && f.mouseY() >= y && f.mouseY() < y + h;
    }

    private float btn(Canvas c, PageFrame f, String label, V2.Btn kind, float x, float y, Runnable run) {
        float w = V2.buttonWidth(label);
        V2.button(c, label, kind, x, y, w, V2.CTRL, over(f, x, y, w, V2.CTRL) ? 1 : 0);
        btnRects.add(new float[]{x, y, w, V2.CTRL});
        btnRuns.add(run);
        return w;
    }

    private static void sendRequest(String action, long id) {
        CompoundTag tag = new CompoundTag();
        tag.putString("action", action);
        tag.putLong("id", id);
        ServerboundShopActionPayload.send(tag);
    }

    @Override
    public void render(Canvas c, PageFrame f) {
        btnRects.clear();
        btnRuns.clear();
        float x = f.x();
        float cy = f.y() + V2.BAR / 2;
        float by = cy - V2.CTRL / 2;
        Draw.hairline(c, x, f.y() + V2.BAR - 0.5F, f.w(), IOStyle.BORDER_DIM);
        float tw = V2.bold(c, tr(transfer ? "tab.module_shop.transfer" : "tab.module_shop.trade"), V2.FS_T, IOStyle.TEXT, x + 8, cy,
                90, Text.Align.LEFT);
        search.visible(true).bounds(x + 8 + tw + 10, by, 160, V2.CTRL);
        search.render(c, f.mouseX(), f.mouseY(), f.dtMs());
        shown = players();
        V2.text(c, tr("gui.module_shop.v2.trade.online", shown.size()), V2.FS_XS, IOStyle.TEXT_2, x + 8 + tw + 178, cy, 120,
                Text.Align.LEFT);
        if (!transfer) {
            String rl = tr("gui.module_shop.v2.trade.requests", ClientShopData.tradeRequests.size());
            float rw = V2.buttonWidth(rl);
            btn(c, f, rl, V2.Btn.NORMAL, x + f.w() - 8 - rw, by, () -> f.host().action("trade_requests_open", null));
        }
        float top = f.y() + V2.BAR;
        // 交易请求条
        if (!transfer && !ClientShopData.tradeRequests.isEmpty()) {
            Draw.fill(c, x, top, f.w(), REQ_H, 0, Draw.alpha(IOStyle.ORANGE, 0.08F));
            Draw.hairline(c, x, top + REQ_H - 0.5F, f.w(), IOStyle.BORDER_DIM);
            float rx = x + 8;
            float rcy = top + REQ_H / 2;
            rx += V2.text(c, tr("gui.module_shop.v2.trade.incoming"), V2.FS_XS, IOStyle.TEXT_2, rx, rcy, 80, Text.Align.LEFT) + 8;
            for (ClientShopData.TradeRequest r : ClientShopData.tradeRequests) {
                if (rx > x + f.w() - 120) {
                    break;
                }
                long age = (r.ageMs + System.currentTimeMillis() - r.receivedAt) / 1000;
                float nw = V2.bold(c, r.from, V2.FS_S, IOStyle.TEXT, rx, rcy, 90, Text.Align.LEFT);
                rx += nw + 4;
                rx += V2.text(c, tr("gui.module_shop.v2.trade.ago", age), V2.FS_XS, IOStyle.TEXT_3, rx, rcy, 60, Text.Align.LEFT) + 5;
                long id = r.id;
                rx += btn(c, f, tr("gui.module_shop.v2.trade.accept"), V2.Btn.PRIMARY, rx, rcy - V2.CTRL / 2,
                        () -> sendRequest("trade_accept", id)) + 3;
                rx += btn(c, f, tr("gui.module_shop.v2.trade.decline"), V2.Btn.NORMAL, rx, rcy - V2.CTRL / 2,
                        () -> sendRequest("trade_decline", id)) + 14;
            }
            top += REQ_H;
        }
        float bottom = f.y() + f.h() - V2.FOOT;
        ListTable table = table();
        float[][] cols = V2.columns(x, f.w(), 10, 8, 12, 0, 80, 90);
        String[] heads = {"", tr("gui.module_shop.v2.col.player"), tr("gui.module_shop.v2.col.state"), ""};
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.RIGHT};
        table.layout(x, top, f.w(), bottom - top, cols, heads, al, null);
        if (shown.isEmpty()) {
            V2.tableHead(c, x, top, f.w(), cols, heads, al, -1, false);
            V2.empty(c, tr("gui.module_shop.trade.no_online"), x, top + V2.HEAD, f.w(), bottom - top - V2.HEAD);
        } else {
            table.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), shown.size(), (cv, row, ry, hover) -> {
                ClientShopData.OnlinePlayer p = shown.get(row);
                float rcy = ry + V2.ROW / 2;
                int col = transfer ? IOStyle.BLUE : IOStyle.ORANGE;
                Draw.fill(cv, cols[0][0], rcy - 5.5F, 11, 11, 2.5F, Draw.alpha(col, 0.22F));
                V2.bold(cv, p.name.isEmpty() ? "?" : p.name.substring(0, 1).toUpperCase(Locale.ROOT), V2.FS_S, col, cols[0][0], rcy, 11,
                        Text.Align.CENTER);
                V2.text(cv, p.name, V2.FS, IOStyle.TEXT, cols[1][0], rcy, cols[1][1], Text.Align.LEFT);
                Draw.fill(cv, cols[2][0], rcy - 2, 4, 4, 2, IOStyle.GREEN);
                V2.text(cv, tr("gui.module_shop.v2.trade.st_online"), V2.FS_S, IOStyle.TEXT_2, cols[2][0] + 7, rcy, cols[2][1] - 7,
                        Text.Align.LEFT);
                table.button(cv, row, "pick", tr(transfer ? "gui.module_shop.v2.trade.transfer" : "gui.module_shop.v2.trade.start"),
                        V2.Btn.NORMAL, cols[3][0] + cols[3][1], ry, hover);
            });
        }
        V2.foot(c, x, bottom, f.w(), tr("gui.module_shop.v2.trade.online", shown.size()),
                tr(transfer ? "gui.module_shop.v2.trade.transfer_hint" : "gui.module_shop.v2.trade.hint"));
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
        for (int i = 0; i < btnRects.size(); i++) {
            float[] r = btnRects.get(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                ClickSound.play();
                btnRuns.get(i).run();
                return true;
            }
        }
        ListTable table = table();
        if (table.pressBar(mx, my)) {
            return true;
        }
        ListTable.Hit hit = table.click(mx, my);
        if (hit == null || hit.row() < 0 || hit.row() >= shown.size()) {
            return hit != null;
        }
        ClickSound.play();
        CompoundTag tag = new CompoundTag();
        tag.putString("name", shown.get(hit.row()).name);
        f.host().action("trade_pick", tag);
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
        return table().releaseBar();
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        if (search != null && search.mouseDragged(mx, my, button, dx, dy)) {
            return true;
        }
        return table().dragBar(my);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        return table().wheel(mx, my, scrollY, false);
    }

    @Override
    public void cancelInput() {
        if (search != null) {
            search.visible(false);
        }
        table().releaseBar();
    }
}
