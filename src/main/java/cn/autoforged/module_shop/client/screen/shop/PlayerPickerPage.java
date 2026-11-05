package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClickSound;
import cn.autoforged.module_shop.client.ClientNotifications;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.screen.Skins;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.data.NotificationType;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 交易 / 转账页（Skija 版）：在线玩家列表 + 搜索框。两个页签共用此类，transfer 只影响文案、色条与点击后的行为
 * （点击统一走 trade_pick，由 ShopScreen 按当前页签决定是发起交易还是输入转账金额）。
 *
 * <p>搜索框是 {@link SkiaTextField}（支持中文输入法）；列表平滑滚动替代翻页；
 * 交易页底部有「交易请求」入口，带未读红点。
 */
public final class PlayerPickerPage implements ShopPage {
    private static final float SEARCH_H = 17.0F;
    private static final float ROW_H = 24.0F;
    private static final float CARD_H = 22.0F;
    /** 搜索词按页签分别保留（跨界面重建）。 */
    private static final Map<Boolean, String> SAVED_QUERY = new HashMap<>();

    private final boolean transfer;
    private final ScrollController scroll = new ScrollController();
    private final Map<String, Float> hoverT = new HashMap<>();
    private SkiaTextField search;

    private final Button requests = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop.trade.requests").getString(), null)
            .radius(2.0F).fontSize(Text.SMALL);

    private float listX, listY, listW, listH;
    private List<ClientShopData.OnlinePlayer> shown = List.of();

    public PlayerPickerPage(boolean transfer) {
        this.transfer = transfer;
    }

    @Override
    public String id() {
        return transfer ? "transfer" : "trade";
    }

    @Override
    public void init(ShopHost host) {
        String value = search == null ? SAVED_QUERY.getOrDefault(transfer, "") : search.value();
        search = new SkiaTextField(host.screen(), host.font(), 32)
                .placeholder(Component.translatable("gui.module_shop.trade.search_hint").getString())
                .onChange(text -> {
                    SAVED_QUERY.put(transfer, text);
                    scroll.scrollTo(0.0F, false);
                });
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
        return out;
    }

    @Override
    public void render(Canvas canvas, PageFrame f) {
        if (search != null) {
            search.bounds(f.x() + 4.0F, f.y(), f.w() - 8.0F, SEARCH_H).visible(true);
            search.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        }
        shown = players();
        listX = f.x();
        listY = f.y() + SEARCH_H + 4.0F;
        listW = f.w();
        listH = f.listBottom() - listY;

        if (shown.isEmpty()) {
            PageKit.emptyText(canvas, Component.translatable("gui.module_shop.trade.no_online").getString(),
                    f.x(), listY + 44.0F, f.w());
        } else {
            scroll.setContent(shown.size() * ROW_H - (ROW_H - CARD_H), listH);
            scroll.update();
            float rowW = listW - 8.0F - (scroll.canScroll() ? 6.0F : 0.0F);
            boolean inList = f.mouseX() >= listX + 4.0F && f.mouseX() < listX + 4.0F + rowW
                    && f.mouseY() >= listY && f.mouseY() < listY + listH;
            int saved = canvas.save();
            f.host().items().pushClip(listX, listY, listW, listH);
            try {
                Draw.clip(canvas, listX, listY, listW, listH);
                float off = scroll.offset();
                for (int i = Math.max(0, (int) (off / ROW_H)); i < shown.size(); i++) {
                    float y = listY + i * ROW_H - off;
                    if (y > listY + listH) {
                        break;
                    }
                    ClientShopData.OnlinePlayer p = shown.get(i);
                    boolean hover = inList && !scroll.isDragging() && f.mouseY() >= y && f.mouseY() < y + CARD_H;
                    float ht = Motion.approach(hoverT.getOrDefault(p.name, 0.0F), hover ? 1.0F : 0.0F, f.dtMs(),
                            Motion.HOVER_MS);
                    hoverT.put(p.name, ht);
                    float x = listX + 4.0F;
                    PageKit.hoverCard(canvas, x, y, rowW, CARD_H, ht);
                    // 色条：交易 = 金、转账 = 蓝
                    Draw.fill(canvas, x + 4.0F, y + 4.0F, 2.5F, CARD_H - 8.0F, 1.0F,
                            transfer ? IOStyle.BLUE : IOStyle.ORANGE);
                    if (p.uuid != null) {
                        int hx = Math.round(x + 9.0F);
                        int hy = Math.round(y + 3.0F);
                        f.host().items().addCustom(hx, hy, 16.0F, 16.0F,
                                (g, font) -> Skins.drawHead(g, p.uuid, hx, hy, 16));
                    }
                    String hint = Component.translatable(transfer ? "gui.module_shop.transfer.click_to_transfer"
                            : "gui.module_shop.trade.click_to_trade").getString();
                    float hw = Text.width(hint, AtomUi.REGULAR, Text.SMALL);
                    Text.drawLine(canvas, hint, AtomUi.REGULAR, Text.SMALL,
                            Draw.lerp(IOStyle.TEXT_3, IOStyle.TEXT_2, ht), x + rowW - 6.0F - hw, y + CARD_H / 2.0F,
                            hw + 1.0F, Text.Align.LEFT);
                    Text.drawLine(canvas, p.name, AtomUi.REGULAR, Text.BODY, IOStyle.TEXT,
                            x + 30.0F, y + CARD_H / 2.0F, rowW - 30.0F - hw - 12.0F, Text.Align.LEFT);
                }
            } finally {
                f.host().items().popClip();
                canvas.restoreToCount(saved);
            }
            scroll.drawBar(canvas, listX, listY, listW, listH, f.mouseX(), f.mouseY(), f.dtMs());
        }

        // 交易页：交易请求入口 + 未读红点
        requests.visible(!transfer);
        if (!transfer) {
            float barMid = f.barTop() + PageFrame.BAR_H / 2.0F;
            float bx = f.x() + 4.0F;
            float by = barMid - 7.0F;
            requests.onClick(() -> f.host().action("trade_requests_open", null)).bounds(bx, by, 66.0F, 14.0F);
            requests.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
            int right = Math.round(bx + 66.0F);
            int top = Math.round(by);
            f.host().items().addCustom(right - 10, top, 12.0F, 12.0F,
                    (g, font) -> ClientNotifications.drawBadge(g, font, NotificationType.TRADE, right, top));
        }
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(PageFrame f, double mx, double my, int button) {
        if (search != null && search.mouseClicked(mx, my, button)) {
            return true;
        }
        if (button == 0 && scroll.pressBar(mx, my, listX, listY, listW, listH)) {
            return true;
        }
        if (requests.isVisible() && requests.mouseClicked(mx, my, button)) {
            return true;
        }
        if (button == 0 && mx >= listX + 4.0F && mx < listX + listW - 6.0F && my >= listY && my < listY + listH) {
            float local = (float) (my - listY + scroll.offset());
            int index = (int) (local / ROW_H);
            if (index >= 0 && index < shown.size() && local - index * ROW_H < CARD_H) {
                ClickSound.play();
                CompoundTag tag = new CompoundTag();
                tag.putString("name", shown.get(index).name);
                f.host().action("trade_pick", tag);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(PageFrame f, double mx, double my, int button) {
        boolean handled = scroll.releaseBar() | requests.mouseReleased(mx, my, button);
        if (search != null) {
            handled |= search.mouseReleased(mx, my, button);
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(PageFrame f, double mx, double my, int button, double dx, double dy) {
        if (search != null && search.mouseDragged(mx, my, button, dx, dy)) {
            return true;
        }
        return scroll.dragBar(my, listH);
    }

    @Override
    public boolean mouseScrolled(PageFrame f, double mx, double my, double scrollY) {
        if (my >= listY && my < f.listBottom()) {
            scroll.wheel(scrollY);
            return true;
        }
        return false;
    }

    @Override
    public void clickedOutside() {
        if (search != null && search.isFocused()) {
            search.blur();
        }
    }

    @Override
    public void cancelInput() {
        scroll.releaseBar();
        requests.cancelPress();
        if (search != null) {
            search.blur();
            search.visible(false); // 下次渲染时重新显示；不渲染期间不能被点中
        }
    }
}
