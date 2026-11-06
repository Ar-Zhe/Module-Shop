package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** 浏览某个玩家的摊位，物品按上架先后顺序排列。 */
public class StallBrowseScreen extends SkiaDialog implements JeiHoverSource {
    /** JEI 桥接用：本帧鼠标悬停的物品。 */
    private net.minecraft.world.item.ItemStack jeiHover = net.minecraft.world.item.ItemStack.EMPTY;

    @Override
    public net.minecraft.world.item.ItemStack jeiHoveredStack(double mouseX, double mouseY) {
        return this.jeiHover == null ? net.minecraft.world.item.ItemStack.EMPTY : this.jeiHover;
    }

    private static final int PANEL_W = 260;
    private static final int PANEL_H = 220;
    private static final int ROW_H = 30;
    private static final int PAGE_SIZE = 5;

    private final String owner;
    private final String ownerName;
    private final UUID ownerUuid;
    private final List<Entry> entries = new ArrayList<>();
    private final List<int[]> rowRects = new ArrayList<>();
    private int page;

    /** id = 商品 id（服务端 StallEntry.order），购买按它定位；index 只给旧流程用。 */
    private record Entry(ItemStack stack, long price, int stock, int sold, int index, long id) {
    }

    /** 这是不是我自己的摊位（自己不能买）。 */
    private boolean ownStall;

    public StallBrowseScreen(CompoundTag tag) {
        super(Component.translatable("gui.module_shop.stall_browse_title"));
        this.owner = tag.getString("owner");
        this.ownerName = tag.getString("ownerName");
        UUID parsedOwner = null;
        try {
            parsedOwner = UUID.fromString(this.owner);
        } catch (IllegalArgumentException ignored) {
        }
        this.ownerUuid = parsedOwner;
        applyEntries(tag);
    }

    /** 需求 5：购买后服务端推来最新库存，就地刷新列表而不是重建界面。 */
    public void updateEntries(CompoundTag tag) {
        this.entries.clear();
        applyEntries(tag);
    }

    private void applyEntries(CompoundTag tag) {
        this.ownStall = tag.getBoolean("own");
        // 服务端推回最新库存 = 上一次购买请求已处理完
        this.buying = false;
        ListTag list = tag.getList("entries", 10);
        var registries = Minecraft.getInstance().level == null ? null : Minecraft.getInstance().level.registryAccess();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            ItemStack stack = registries == null ? ItemStack.EMPTY : ItemStack.parseOptional(registries, c.getCompound("item"));
            // 需求 5/6：携带单件价格与剩余库存（旧数据没有 stock 时以栈数量回退）。
            int stock = c.contains("stock") ? c.getInt("stock") : Math.max(1, stack.getCount());
            // 需求 9：按商品分别显示已售出件数。
            int sold = c.contains("sold") ? c.getInt("sold") : 0;
            this.entries.add(new Entry(stack, c.getLong("price"), stock, sold, c.getInt("index"),
                    c.contains("id") ? c.getLong("id") : -1L - i));
        }
    }

    private int left() {
        return (this.width - PANEL_W) / 2;
    }

    private int top() {
        return (this.height - PANEL_H) / 2;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (skiaRender(g, mouseX, mouseY, partialTick)) {
            return;
        }
        this.rowRects.clear();
        // JEI 桥接：每帧先清空，只有画了物品 tooltip 的那一行会重新赋值。
        this.jeiHover = net.minecraft.world.item.ItemStack.EMPTY;
        super.render(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        Bricks.panel(g, x, y, PANEL_W, PANEL_H);
        IOStyle.centerAt(g, this.font, Component.literal(ownerName), x + PANEL_W / 2, y + 10, IOStyle.ORANGE);
        // 需求 1：摊位界面显示摊主头像。
        if (ownerUuid != null) {
            Skins.drawHead(g, ownerUuid, x + 10, y + 6, 14);
        }

        int pages = Math.max(1, (this.entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        this.page = Math.max(0, Math.min(this.page, pages - 1));
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = this.page * PAGE_SIZE + i;
            if (index >= this.entries.size()) {
                break;
            }
            Entry entry = this.entries.get(index);
            int rowY = y + 28 + i * ROW_H;
            g.fill(x + 10, rowY, x + PANEL_W - 10, rowY + ROW_H - 4, 0xFF2A2A2A);
            Bricks.slot(g, x + 15, rowY + 3);
            g.renderItem(entry.stack, x + 15, rowY + 3);
            g.renderItemDecorations(this.font, entry.stack, x + 15, rowY + 3);
            g.drawString(this.font, entry.stack.getHoverName(), x + 38, rowY + 3, IOStyle.TEXT, false);
            // 需求 4/5：单价按单件计算。
            String priceText = Component.translatable("gui.module_shop.stall_unit_price",
                    cn.autoforged.module_shop.client.ClientShopData.money(entry.price)).getString();
            g.drawString(this.font, priceText, x + 38, rowY + 14, IOStyle.ORANGE, false);
            if (entry.stock <= 0) {
                g.drawString(this.font, Component.translatable("gui.module_shop.stall_sold_out"),
                        x + PANEL_W - 58, rowY + 3, IOStyle.RED, true);
            } else {
                g.drawString(this.font, Component.translatable("gui.module_shop.stall_stock", entry.stock),
                        x + PANEL_W - 58, rowY + 3, IOStyle.TEXT_2, true);
            }
            // 需求 9：在商品旁显示该商品累计已售出件数。
            g.drawString(this.font, Component.translatable("gui.module_shop.stall_sold_count", entry.sold),
                    x + PANEL_W - 58, rowY + 14, 0x88BBFF, true);
            this.rowRects.add(new int[]{x + 10, rowY, PANEL_W - 20, ROW_H - 4, index});
            if (mouseX >= x + 15 && mouseX < x + 31 && mouseY >= rowY + 3 && mouseY < rowY + 19) {
                this.jeiHover = entry.stack;
                g.renderTooltip(this.font, entry.stack, mouseX, mouseY);
            }
        }
        if (this.entries.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.stall_empty"),
                    x + PANEL_W / 2, y + 100, IOStyle.TEXT_2);
        }
        if (pages > 1) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.page", this.page + 1, pages),
                    x + PANEL_W / 2, y + PANEL_H - 34, IOStyle.TEXT_2);
        }
        drawButton(g, x + 10, y + PANEL_H - 24, 70, 16, Component.translatable("gui.module_shop.back"));
        if (pages > 1) {
            drawButton(g, x + 88, y + PANEL_H - 24, 58, 16, Component.translatable("gui.module_shop.page_prev"));
            drawButton(g, x + PANEL_W - 68, y + PANEL_H - 24, 58, 16, Component.translatable("gui.module_shop.page_next"));
        }
    }

    /** 鼠标滚轮翻页。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (skia()) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        if (scrollY != 0.0D) {
            int pages = Math.max(1, (this.entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            int next = Math.max(0, Math.min(pages - 1, this.page + (scrollY > 0.0D ? -1 : 1)));
            if (next != this.page) {
                this.page = next;
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (skia()) {
            return skiaClicked(mouseX, mouseY, button);
        }
        int x = left();
        int y = top();
        if (mouseX >= x + 10 && mouseX < x + 80 && mouseY >= y + PANEL_H - 24 && mouseY < y + PANEL_H - 8) {
            Bricks.click();
            // 返回 = 直接回商店主界面（玩家摊位那一页）。
            // 原来这里是「新开一个玩家摊位列表」，于是列表的返回又弹回本界面，两个界面来回循环。
            backToShop();
            return true;
        }
        int pages = Math.max(1, (this.entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (pages > 1 && mouseX >= x + 88 && mouseX < x + 146
                && mouseY >= y + PANEL_H - 24 && mouseY < y + PANEL_H - 8) {
            Bricks.click();
            this.page = Math.max(0, this.page - 1);
            return true;
        }
        if (pages > 1 && mouseX >= x + PANEL_W - 68 && mouseX < x + PANEL_W - 10
                && mouseY >= y + PANEL_H - 24 && mouseY < y + PANEL_H - 8) {
            Bricks.click();
            this.page = Math.min(pages - 1, this.page + 1);
            return true;
        }
        for (int[] rect : this.rowRects) {
            if (mouseX >= rect[0] && mouseX < rect[0] + rect[2] && mouseY >= rect[1] && mouseY < rect[1] + rect[3]) {
                Bricks.click();
                Entry entry = this.entries.get(rect[4]);
                if (entry.stock <= 0) {
                    return true;
                }
                // 需求 5：点击商品弹出数量选择小窗口（中间数量、下方输入框、底部取消/购买）。
                CompoundTag request = new CompoundTag();
                request.putString("kind", "input");
                request.putString("action", "stall_buy");
                request.putString("title", "gui.module_shop.input.stall_buy");
                request.putString("owner", this.owner);
                request.putInt("index", entry.index);
                request.putString("default_quantity", "1");
                // 需求 7：防误触按钮对调——左侧确认、右侧取消。
                request.putBoolean("confirm_right", false);
                // 需求 7：弹窗内实时显示总价与余额变化。
                request.putLong("unit_price", entry.price);
                request.putLong("balance",
                        cn.autoforged.module_shop.client.ClientShopData.balance);
                request.putString("info", Component.translatable("gui.module_shop.stall_buy_info",
                        entry.stack.getHoverName(),
                        cn.autoforged.module_shop.client.ClientShopData.money(entry.price),
                        entry.stock).getString());
                ListTag fields = new ListTag();
                fields.add(net.minecraft.nbt.StringTag.valueOf("quantity"));
                request.put("fields", fields);
                Nav.open(new ShopInputScreen(request));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ------------------------------------------------------------------ Skija：摊位详情（原版容器风格）
    //
    // 顶部：店名 / 店主 / 营业状态 / 商品数；左侧：物品格子卡片（图标、名称、单价、库存），分页；
    // 右侧：选中商品的详情、购买数量（− / 输入 / + / 最大）、总价 = 单价 × 数量、购买按钮。
    // 购买请求带商品 id + 我看到的单价 + 请求号；服务端核对后成交并推回最新库存（就地刷新，不重建界面）。

    private long selectedId = -1L;
    private cn.autoforged.module_shop.client.ui.widget.SkiaTextField qtyField;
    private boolean buying;
    private long buyingAt;
    private int gridPage;
    private int perPage = 1;
    private int pages = 1;
    private boolean jumpToSelection;

    @Override
    protected void initSkia() {
        String keep = this.qtyField == null ? "1" : this.qtyField.value();
        this.qtyField = field(6, "1");
        this.qtyField.setValue(keep.isEmpty() ? "1" : keep);
        if (this.selectedId < 0L && cn.autoforged.module_shop.client.StallClient.pendingSelect >= 0L) {
            // 从商店大厅点进来：预选那件商品并翻到它所在的页
            this.selectedId = cn.autoforged.module_shop.client.StallClient.pendingSelect;
            this.jumpToSelection = true;
            cn.autoforged.module_shop.client.StallClient.pendingSelect = -1L;
        }
    }

    private Entry selected() {
        for (Entry e : this.entries) {
            if (e.id() == this.selectedId) {
                return e;
            }
        }
        return null;
    }

    private int quantity() {
        try {
            return Integer.parseInt(this.qtyField.value().trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private void setQuantity(int q, Entry e) {
        int max = Math.max(1, e.stock());
        this.qtyField.setValue(Integer.toString(Math.max(1, Math.min(max, q))));
    }

    private void buy(Entry e) {
        int q = quantity();
        if (q < 1 || q > e.stock() || this.ownStall) {
            return;
        }
        if (this.buying && System.currentTimeMillis() - this.buyingAt < 4000L) {
            return; // 等服务端回包，防止连点重复购买（服务端另有请求号去重）
        }
        this.buying = true;
        this.buyingAt = System.currentTimeMillis();
        CompoundTag tag = new CompoundTag();
        tag.putString("owner", this.owner);
        tag.putLong("id", e.id());
        tag.putInt("index", e.index());
        tag.putString("quantity", Integer.toString(q));
        tag.putLong("expect", e.price());
        cn.autoforged.module_shop.client.StallClient.sendOnce("stall_buy", tag);
    }

    private static long safeTotal(long price, int count) {
        if (price <= 0L || count <= 0) {
            return 0L;
        }
        return price > Long.MAX_VALUE / count ? Long.MAX_VALUE : price * count;
    }

    @Override
    protected boolean onScroll(double mx, double my, double amount) {
        this.gridPage = Math.max(0, Math.min(this.pages - 1, this.gridPage + (amount > 0 ? -1 : 1)));
        return true;
    }

    @Override
    protected boolean onEnter() {
        Entry e = selected();
        if (e != null) {
            buy(e);
            return true;
        }
        return false;
    }

    @Override
    protected void paint(io.github.humbleui.skija.Canvas c) {
        float w = Math.min(410.0F, this.width - 16.0F);
        float h = Math.min(260.0F, this.height - 16.0F);
        float x = Math.round((this.width - w) / 2.0F);
        float y = Math.round((this.height - h) / 2.0F);
        float ix = x + PAD;
        float iw = w - PAD * 2;
        float bottom = y + h - PAD;
        panel(c, x, y, w, h, Component.translatable("gui.module_shop.stall.shop_name", this.ownerName).getString());
        if (this.ownerUuid != null) {
            final UUID uuid = this.ownerUuid;
            final int hx = (int) (x + PAD);
            final int hy = (int) (y + (HEADER_H - 14) / 2);
            this.items.addCustom(hx, hy, 14, 14, (g, font) -> Skins.drawHead(g, uuid, hx, hy, 14));
        }
        // ---- 顶部信息 ----
        int inStock = 0;
        for (Entry e : this.entries) {
            if (e.stock() > 0) {
                inStock++;
            }
        }
        boolean open = inStock > 0;
        float infoY = y + HEADER_H + 9;
        String info = Component.translatable("gui.module_shop.stall.owner_line", this.ownerName).getString();
        float iwidth = small(c, info, ix, infoY, iw * 0.5F, IOStyle.TEXT_2);
        String status = Component.translatable(open ? "gui.module_shop.stall.status_open"
                : "gui.module_shop.stall.status_closed").getString();
        float sx = ix + iwidth + 10;
        cn.autoforged.module_shop.client.ui.render.Draw.fill(c, sx, infoY - 4, 6, 6, 3, open ? IOStyle.GREEN : IOStyle.TEXT_3);
        float stw = small(c, status, sx + 9, infoY, 80, open ? IOStyle.GREEN : IOStyle.TEXT_3);
        small(c, Component.translatable("gui.module_shop.stall.goods_count", this.entries.size(), inStock).getString(),
                sx + 9 + stw + 10, infoY, ix + iw - (sx + 9 + stw + 10), IOStyle.TEXT_2);

        // ---- 左：商品格子 ----
        float detailW = 132.0F;
        float gx = ix;
        float gy = infoY + 9;
        float gw = iw - detailW - 8;
        float gh = bottom - 24 - gy;
        int cols = gw >= 230 ? 3 : 2;
        float cellGap = 3.0F;
        float cellW = (gw - cellGap * (cols - 1)) / cols;
        float cellH = 34.0F;
        int rows = Math.max(1, (int) ((gh + cellGap) / (cellH + cellGap)));
        this.perPage = rows * cols;
        this.pages = Math.max(1, (this.entries.size() + this.perPage - 1) / this.perPage);
        if (this.jumpToSelection && selected() != null) {
            this.jumpToSelection = false;
            int pos = this.entries.indexOf(selected());
            if (pos >= this.perPage) {
                this.gridPage = pos / this.perPage; // 从大厅点进来：翻到预选商品所在页（只翻一次）
            }
        }
        this.gridPage = Math.max(0, Math.min(this.gridPage, this.pages - 1));
        if (this.entries.isEmpty()) {
            center(c, Component.translatable("gui.module_shop.stall_empty").getString(), gx, gy + gh / 2, gw,
                    IOStyle.TEXT_2);
        }
        for (int i = 0; i < this.perPage; i++) {
            int idx = this.gridPage * this.perPage + i;
            if (idx >= this.entries.size()) {
                break;
            }
            Entry e = this.entries.get(idx);
            float cx = gx + (i % cols) * (cellW + cellGap);
            float cy = gy + (i / cols) * (cellH + cellGap);
            boolean sel = e.id() == this.selectedId;
            boolean soldOut = e.stock() <= 0;
            float hv = hover("cell" + e.id(), cx, cy, cellW, cellH);
            int fill = sel ? IOStyle.BLUE_TINT : cn.autoforged.module_shop.client.ui.render.Draw.lerp(IOStyle.CARD,
                    IOStyle.ROW_HOVER, hv);
            cn.autoforged.module_shop.client.ui.render.Draw.fill(c, cx, cy, cellW, cellH, 2, fill);
            cn.autoforged.module_shop.client.ui.render.Draw.stroke(c, cx, cy, cellW, cellH, 2, 1,
                    sel ? IOStyle.BLUE : IOStyle.BORDER_DIM);
            slotFrame(c, cx + 4, cy + 4);
            item(e.stack(), cx + 4, cy + 4);
            if (over(cx + 3, cy + 3, 18, 18)) {
                this.items.setHovered(e.stack());
            }
            float tx = cx + 25;
            float tw = cellW - 28;
            small(c, e.stack().getHoverName().getString(), tx, cy + 8, tw, soldOut ? IOStyle.TEXT_3 : IOStyle.TEXT);
            cn.autoforged.module_shop.client.ui.render.Text.drawLine(c,
                    cn.autoforged.module_shop.client.ClientShopData.money(e.price()),
                    cn.autoforged.module_shop.client.ui.AtomUi.BOLD, cn.autoforged.module_shop.client.ui.render.Text.SMALL,
                    soldOut ? IOStyle.TEXT_3 : IOStyle.ORANGE, tx, cy + 17, tw,
                    cn.autoforged.module_shop.client.ui.render.Text.Align.LEFT);
            small(c, soldOut ? Component.translatable("gui.module_shop.stall_sold_out").getString()
                            : Component.translatable("gui.module_shop.stall_stock", e.stock()).getString(), tx, cy + 26,
                    tw, soldOut ? IOStyle.RED : IOStyle.TEXT_2);
            final long id = e.id();
            hit(cx, cy, cellW, cellH, () -> {
                if (this.selectedId != id) {
                    this.selectedId = id;
                    this.qtyField.setValue("1");
                }
            }, null);
        }

        // ---- 右：商品详情与购买 ----
        float dx = ix + iw - detailW;
        float dy = gy;
        float dh = bottom - 24 - dy;
        card(c, dx, dy, detailW, dh);
        Entry e = selected();
        float px = dx + 6;
        float pw = detailW - 12;
        if (e == null) {
            this.qtyField.visible(false);
            paragraph(c, Component.translatable("gui.module_shop.stall.pick_hint").getString(), px, dy + dh / 2 - 10,
                    pw, cn.autoforged.module_shop.client.ui.render.Text.SMALL, IOStyle.TEXT_3, 3, true);
        } else {
            cn.autoforged.module_shop.client.ui.render.Draw.fill(c, px, dy + 6, 22, 22, 2, IOStyle.SLOT_INNER);
            cn.autoforged.module_shop.client.ui.render.Draw.stroke(c, px, dy + 6, 22, 22, 2, 1, IOStyle.SLOT_BORDER);
            this.items.add(e.stack(), px + 2, dy + 8, 18.0F, false);
            if (over(px, dy + 6, 22, 22)) {
                this.items.setHovered(e.stack());
            }
            text(c, e.stack().getHoverName().getString(), px + 27, dy + 13, pw - 27, IOStyle.TEXT);
            small(c, Component.translatable("gui.module_shop.stall_sold_count", e.sold()).getString(), px + 27,
                    dy + 23, pw - 27, IOStyle.TEXT_3);
            float ly = dy + 38;
            small(c, Component.translatable("gui.module_shop.stall.unit_price").getString(), px, ly, pw / 2, IOStyle.TEXT_3);
            right(c, cn.autoforged.module_shop.client.ClientShopData.money(e.price()), px, ly, pw, IOStyle.ORANGE);
            ly += 12;
            small(c, Component.translatable("gui.module_shop.stall.stock_label").getString(), px, ly, pw / 2, IOStyle.TEXT_3);
            right(c, Integer.toString(e.stock()), px, ly, pw, e.stock() > 0 ? IOStyle.TEXT : IOStyle.RED);
            ly += 14;
            boolean canBuy = !this.ownStall && e.stock() > 0;
            if (canBuy) {
                // 数量：− [输入] +   最大
                float bw = 16.0F;
                float fw = pw - bw * 2 - 4 - 26;
                button(c, "qminus", "-", px, ly, bw, 16, cn.autoforged.module_shop.client.ui.widget.Button.Kind.NORMAL,
                        quantity() > 1, () -> setQuantity(quantity() - 1, e));
                this.qtyField.bounds(px + bw + 2, ly, fw, 16).visible(true);
                button(c, "qplus", "+", px + bw + 2 + fw + 2, ly, bw, 16,
                        cn.autoforged.module_shop.client.ui.widget.Button.Kind.NORMAL, quantity() < e.stock(),
                        () -> setQuantity(quantity() + 1, e));
                button(c, "qmax", Component.translatable("gui.module_shop.stall.max").getString(), px + pw - 24, ly, 24,
                        16, cn.autoforged.module_shop.client.ui.widget.Button.Kind.NORMAL, true,
                        () -> setQuantity(e.stock(), e));
                ly += 22;
                int q = quantity();
                boolean qOk = q >= 1 && q <= e.stock();
                long total = qOk ? safeTotal(e.price(), q) : 0L;
                small(c, Component.translatable("gui.module_shop.stall.total").getString(), px, ly, pw / 2, IOStyle.TEXT_2);
                cn.autoforged.module_shop.client.ui.render.Text.drawLine(c,
                        qOk ? cn.autoforged.module_shop.client.ClientShopData.money(total) : "-",
                        cn.autoforged.module_shop.client.ui.AtomUi.BOLD, cn.autoforged.module_shop.client.ui.render.Text.BODY,
                        IOStyle.ORANGE, px, ly, pw, cn.autoforged.module_shop.client.ui.render.Text.Align.RIGHT);
                ly += 12;
                long balance = cn.autoforged.module_shop.client.ClientShopData.balance;
                String hint;
                int hintColor;
                if (!qOk) {
                    hint = Component.translatable("gui.module_shop.stall.qty_range", e.stock()).getString();
                    hintColor = IOStyle.RED;
                } else if (total > balance) {
                    hint = Component.translatable("gui.module_shop.stall.no_money",
                            cn.autoforged.module_shop.client.ClientShopData.money(balance)).getString();
                    hintColor = IOStyle.RED;
                } else {
                    hint = Component.translatable("gui.module_shop.stall.balance_line",
                            cn.autoforged.module_shop.client.ClientShopData.money(balance)).getString();
                    hintColor = IOStyle.TEXT_3;
                }
                small(c, hint, px, ly, pw, hintColor);
                boolean busy = this.buying && System.currentTimeMillis() - this.buyingAt < 4000L;
                button(c, "buy", Component.translatable(busy ? "gui.module_shop.auction.submitting"
                                : "gui.module_shop.stall.buy").getString(), px, dy + dh - 24, pw, 18,
                        cn.autoforged.module_shop.client.ui.widget.Button.Kind.ACCENT,
                        qOk && total <= balance && !busy, () -> buy(e));
            } else {
                this.qtyField.visible(false);
                paragraph(c, Component.translatable(this.ownStall ? "gui.module_shop.stall.own_hint"
                                : "gui.module_shop.stall_sold_out").getString(), px, ly, pw,
                        cn.autoforged.module_shop.client.ui.render.Text.SMALL, IOStyle.TEXT_3, 3, false);
            }
        }

        // ---- 底栏：返回 + 翻页 ----
        button(c, "back", Component.translatable("gui.module_shop.back").getString(), ix, bottom - 18, 60, 18,
                cn.autoforged.module_shop.client.ui.widget.Button.Kind.NORMAL, true, this::backToShop);
        if (this.pages > 1) {
            float nx = ix + gw - 40;
            button(c, "next", Component.translatable("gui.module_shop.page_next").getString(), nx, bottom - 18, 40, 18,
                    cn.autoforged.module_shop.client.ui.widget.Button.Kind.NORMAL, this.gridPage < this.pages - 1,
                    () -> this.gridPage++);
            center(c, (this.gridPage + 1) + " / " + this.pages, nx - 50, bottom - 9, 46, IOStyle.TEXT_2);
            button(c, "prev", Component.translatable("gui.module_shop.page_prev").getString(), nx - 94, bottom - 18, 40,
                    18, cn.autoforged.module_shop.client.ui.widget.Button.Kind.NORMAL, this.gridPage > 0,
                    () -> this.gridPage--);
        }
    }

    @Override
    protected void paintOver(io.github.humbleui.skija.Canvas c) {
        this.jeiHover = this.items.hovered();
    }

    private void openBuy(Entry entry) {
        CompoundTag request = new CompoundTag();
        request.putString("kind", "input");
        request.putString("action", "stall_buy");
        request.putString("title", "gui.module_shop.input.stall_buy");
        request.putString("owner", this.owner);
        request.putInt("index", entry.index);
        if (entry.id() >= 0L) {
            // 按商品 id 定位 + 带上看到的单价：卖家改价 / 下架其它商品时不会买错
            request.putLong("id", entry.id());
            request.putLong("expect", entry.price());
        }
        request.putString("default_quantity", "1");
        request.putBoolean("confirm_right", false);
        request.putLong("unit_price", entry.price);
        request.putLong("balance", cn.autoforged.module_shop.client.ClientShopData.balance);
        request.putString("info", Component.translatable("gui.module_shop.stall_buy_info",
                entry.stack.getHoverName(), cn.autoforged.module_shop.client.ClientShopData.money(entry.price),
                entry.stock).getString());
        ListTag fields = new ListTag();
        fields.add(net.minecraft.nbt.StringTag.valueOf("quantity"));
        request.put("fields", fields);
        Nav.open(new ShopInputScreen(request));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, Component label) {
        // 新 UI 皮肤：渐变底 + 内高光 + 悬停上浮 + 按下缩放 + 点击水波。
        double[] m = IOStyle.mouseGui();
        boolean hover = IOStyle.inside(x, y, w, h, m[0], m[1]);
        IOStyle.smallButton(g, x, y, w, h, label, this.font, hover, IOStyle.GRAY_BTN, IOStyle.GRAY_BTN_HOVER,
                IOStyle.TEXT, (int) Math.round(m[0]), (int) Math.round(m[1]));
    }

    /**
     * 返回商店主界面：优先回到返回栈里那个商店界面（保留在「玩家摊位」页签），
     * 栈里没有就新建一个 —— 绝不会把本界面自己留在栈里造成来回循环。
     */
    private void backToShop() {
        Nav.backToShop();
    }

    @Override
    public void onClose() {
        // ESC 与「返回」一致：回到商店主界面（玩家摊位页），不再在列表/浏览之间循环。
        backToShop();
    }
}
