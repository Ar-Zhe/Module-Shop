package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 购物城的「整模组导入」：点上方「选择模组分类」展开 / 收起模组列表，
 * 选中一个模组后点底下「添加模组 +」，进入统一价格 / 库存 / 品质的输入界面，
 * 保存后服务端把该模组的全部物品一次性导入货架。
 */
public class MallImportScreen extends Screen {
    private static final int PANEL_W = 260;
    private static final int PANEL_H = 244;
    private static final int ROW_H = 14;
    private static final int LIST_ROWS = 9;
    /** 位掩码最多支持多少件（再多就不做过滤，交给服务端按数量上限处理）。 */
    private static final int MASK_LIMIT = 8192;
    private static final int COLOR_TEXT = IOStyle.TEXT;
    private static final int COLOR_MUTED = IOStyle.TEXT_2;
    private static final int COLOR_GOLD = IOStyle.ORANGE;

    /** 全部模组（命名空间 → 显示名），按显示名排序；客户端注册表不会变，所以只建一次。 */
    private static List<String[]> modCache;

    private final List<int[]> rowRects = new ArrayList<>();
    private boolean listOpen = true;
    private int scroll;
    private String selected = "";
    /** 跳过「没有显示名字的技术物品」（名字直接显示成 item.xxx 的那种，通常是模组内部用的图标 / 属性占位物品）。 */
    private boolean skipUnnamed = true;
    /** 一键删除该模组的二次确认状态。 */
    private boolean confirmRemove;
    /** 选中模组当前在购物城里的物品数（界面显示 / 删除用）。 */
    private int removeCount;
    private int left;
    private int top;

    public MallImportScreen() {
        super(Component.translatable("gui.module_shop.mall.import_title"));
    }

    /** 模组清单：{命名空间, 显示名}，按显示名（忽略大小写）排序。 */
    private static List<String[]> mods() {
        if (modCache != null) {
            return modCache;
        }
        Map<String, String> names = new TreeMap<>();
        for (var key : BuiltInRegistries.ITEM.keySet()) {
            String ns = key.getNamespace();
            names.computeIfAbsent(ns, MallImportScreen::displayName);
        }
        List<String[]> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : names.entrySet()) {
            list.add(new String[]{entry.getKey(), entry.getValue()});
        }
        list.sort(Comparator.comparing(m -> m[1].toLowerCase()));
        modCache = list;
        return list;
    }

    private static String displayName(String namespace) {
        try {
            return net.neoforged.fml.ModList.get().getModContainerById(namespace)
                    .map(c -> c.getModInfo().getDisplayName())
                    .orElse(namespace);
        } catch (Throwable ignored) {
            return namespace;
        }
    }

    @Override
    protected void init() {
        super.init();
        this.left = (this.width - PANEL_W) / 2;
        this.top = (this.height - PANEL_H) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Bricks.panel(g, this.left, this.top, PANEL_W, PANEL_H);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        this.rowRects.clear();
        int x = this.left;
        int y = this.top;
        IOStyle.centerAt(g, this.font, this.title, x + PANEL_W / 2, y + 10, COLOR_GOLD);
        IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.mall.import_hint"),
                x + PANEL_W / 2, y + 24, COLOR_MUTED);

        // ① 上方的「选择模组分类」折叠条：点一下展开 / 收起。
        int headY = y + 38;
        boolean headHover = IOStyle.inside(x + 10, headY, PANEL_W - 20, 18, mouseX, mouseY);
        IOStyle.rounded(g, x + 10, headY, PANEL_W - 20, 18, 0, headHover ? IOStyle.ROW_HOVER : IOStyle.CARD);
        IOStyle.ring(g, x + 10, headY, PANEL_W - 20, 18, 0, IOStyle.BORDER);
        String head = this.selected.isEmpty()
                ? Component.translatable("gui.module_shop.mall.choose_mod").getString()
                : displayName(this.selected);
        drawSmall(g, x + 16, headY + 5, Component.literal(head), this.selected.isEmpty() ? COLOR_TEXT : COLOR_GOLD);
        // 直边倒三角：展开朝下、收起朝右（这里统一用三角方向表示状态）
        IOStyle.triangleDown(g, x + PANEL_W - 22, headY + 7, 4, 4,
                this.listOpen ? IOStyle.BLUE : IOStyle.TEXT_2);

        // ② 模组列表（展开时）
        int listTop = headY + 22;
        if (this.listOpen) {
            List<String[]> mods = mods();
            int rows = Math.min(LIST_ROWS, mods.size());
            int listH = rows * ROW_H + 4;
            this.scroll = Mth.clamp(this.scroll, 0, Math.max(0, mods.size() - rows));
            IOStyle.card(g, x + 10, listTop, PANEL_W - 20, listH, 0);
            int last = Math.min(mods.size(), this.scroll + rows);
            for (int i = this.scroll; i < last; i++) {
                String[] mod = mods.get(i);
                int ry = listTop + 2 + (i - this.scroll) * ROW_H;
                boolean current = mod[0].equals(this.selected);
                boolean hover = IOStyle.inside(x + 12, ry, PANEL_W - 24, ROW_H - 1, mouseX, mouseY);
                IOStyle.rounded(g, x + 12, ry, PANEL_W - 24, ROW_H - 1, 0,
                        current ? IOStyle.BLUE_TINT : (hover ? IOStyle.ROW_HOVER : IOStyle.SIDEBAR));
                IOStyle.ring(g, x + 12, ry, PANEL_W - 24, ROW_H - 1, 0,
                        current ? IOStyle.BORDER_HOVER : IOStyle.BORDER_DIM);
                String name = mod[1];
                int nameMax = (int) ((PANEL_W - 40) / 0.8F);
                if (this.font.width(name) > nameMax) {
                    name = this.font.plainSubstrByWidth(name, Math.max(1, nameMax - 6)) + "…";
                }
                drawSmall(g, x + 18, ry + 3, Component.literal(name), current ? IOStyle.BLUE : COLOR_TEXT);
                drawSmallRight(g, x + PANEL_W - 18, ry + 3, Component.literal(mod[0]), IOStyle.TEXT_3);
                this.rowRects.add(new int[]{x + 12, ry, PANEL_W - 24, ROW_H - 1, i});
            }
            if (mods.size() > rows) {
                int viewH = rows * ROW_H;
                int trackX = x + PANEL_W - 13;
                g.fill(trackX, listTop + 2, trackX + 1, listTop + 2 + viewH, IOStyle.HAIR);
                int thumbH = Math.max(12, viewH * rows / Math.max(1, mods.size()));
                int thumbY = listTop + 2 + (viewH - thumbH) * this.scroll / Math.max(1, mods.size() - rows);
                g.fill(trackX, thumbY, trackX + 1, thumbY + thumbH, IOStyle.BLUE);
                IOStyle.text(g, this.font, Component.literal((this.scroll + 1) + "-" + last + "/" + mods.size()),
                        x + 16, listTop + listH + 3, IOStyle.TEXT_3);
            }
        }

        // ③ 底部：是否跳过"没有名字的技术物品" + 添加模组 + / 删除该模组 / 取消
        int toggleY = y + PANEL_H - 52;
        int toggleW = PANEL_W - 20;
        boolean toggleHover = IOStyle.inside(x + 10, toggleY, toggleW, 14, mouseX, mouseY);
        IOStyle.button(g, x + 10, toggleY, toggleW, 14,
                Component.translatable("gui.module_shop.mall.skip_unnamed"), this.font, mouseX, mouseY,
                true, this.skipUnnamed ? IOStyle.Btn.PRIMARY : IOStyle.Btn.GRAY);
        int by = y + PANEL_H - 32;
        int bw = (PANEL_W - 44) / 3;
        if (this.confirmRemove) {
            // 二次确认：一键删除该模组在购物城里的全部物品
            IOStyle.card(g, x + 10, by - 20, PANEL_W - 20, 20, 0);
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.mall.remove_confirm",
                    displayName(this.selected), this.removeCount), x + PANEL_W / 2, by - 14, IOStyle.RED);
        }
        boolean addHover = IOStyle.inside(x + 12, by, bw, 20, mouseX, mouseY);
        IOStyle.button(g, x + 12, by, bw, 20,
                Component.translatable("gui.module_shop.mall.add_mod"), this.font, mouseX, mouseY,
                !this.selected.isEmpty() && !this.confirmRemove,
                this.selected.isEmpty() ? IOStyle.Btn.GRAY : IOStyle.Btn.PRIMARY);
        int delX = x + 12 + bw + 6;
        boolean delEnabled = !this.selected.isEmpty() && this.removeCount > 0;
        IOStyle.button(g, delX, by, bw, 20,
                Component.translatable(this.confirmRemove ? "gui.module_shop.mall.remove_yes"
                        : "gui.module_shop.mall.remove_mod"), this.font, mouseX, mouseY,
                delEnabled, delEnabled ? IOStyle.Btn.DANGER_TINT : IOStyle.Btn.GRAY);
        int cx2 = delX + bw + 6;
        IOStyle.button(g, cx2, by, bw, 20, Component.translatable("gui.module_shop.cancel"), this.font,
                mouseX, mouseY, true, IOStyle.Btn.GRAY);
        if (this.selected.isEmpty() && addHover) {
            g.renderTooltip(this.font, Component.translatable("gui.module_shop.mall.choose_first"),
                    mouseX, mouseY);
        }
        if (this.skipUnnamed && toggleHover) {
            g.renderTooltip(this.font, Component.translatable("gui.module_shop.mall.skip_unnamed_tip"),
                    mouseX, mouseY);
        }
    }

    private void drawSmall(GuiGraphics g, int x, int y, Component text, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(0.8F, 0.8F, 1.0F);
        g.drawString(this.font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    private void drawSmallRight(GuiGraphics g, int right, int y, Component text, int color) {
        drawSmall(g, right - (int) (this.font.width(text) * 0.8F), y, text, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = this.left;
        int y = this.top;
        int headY = y + 38;
        if (button == 0 && IOStyle.inside(x + 10, headY, PANEL_W - 20, 18, mouseX, mouseY)) {
            Bricks.click();
            this.listOpen = !this.listOpen;
            return true;
        }
        for (int[] rect : this.rowRects) {
            if (IOStyle.inside(rect[0], rect[1], rect[2], rect[3], mouseX, mouseY)) {
                Bricks.click();
                this.selected = mods().get(rect[4])[0];
                this.confirmRemove = false;
                this.removeCount = countInMall(this.selected);
                return true;
            }
        }
        int by = y + PANEL_H - 32;
        int bw = (PANEL_W - 44) / 3;
        int toggleY = y + PANEL_H - 52;
        if (button == 0 && IOStyle.inside(x + 10, toggleY, PANEL_W - 20, 14, mouseX, mouseY)) {
            Bricks.click();
            this.skipUnnamed = !this.skipUnnamed;
            return true;
        }
        if (button == 0 && IOStyle.inside(x + 12, by, bw, 20, mouseX, mouseY)) {
            Bricks.click();
            if (!this.selected.isEmpty() && !this.confirmRemove) {
                startImport();
            }
            return true;
        }
        int delX = x + 12 + bw + 6;
        if (button == 0 && IOStyle.inside(delX, by, bw, 20, mouseX, mouseY)) {
            Bricks.click();
            if (this.selected.isEmpty()) {
                return true;
            }
            if (this.confirmRemove) {
                // 二次确认：一键删除该模组在购物城里的全部物品
                CompoundTag tag = new CompoundTag();
                tag.putString("action", "admin_mall_remove_mod");
                tag.putString("mod", this.selected);
                ServerboundShopActionPayload.send(tag);
                this.confirmRemove = false;
                this.onClose();
                return true;
            }
            this.confirmRemove = true;
            this.removeCount = countInMall(this.selected);
            return true;
        }
        int cx2 = delX + bw + 6;
        if (button == 0 && IOStyle.inside(cx2, by, bw, 20, mouseX, mouseY)) {
            Bricks.click();
            if (this.confirmRemove) {
                this.confirmRemove = false;
                return true;
            }
            this.onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 选中的模组当前在购物城里有多少件（删除确认里显示）。 */
    private static int countInMall(String namespace) {
        int count = 0;
        for (ClientShopData.MallView view : ClientShopData.mall) {
            if (view.namespace().equals(namespace)) {
                count++;
            }
        }
        return count;
    }

    /** 打开统一价格 / 库存 / 品质输入界面；确认后由服务端把整个模组导入。 */
    private void startImport() {
        CompoundTag request = new CompoundTag();
        // 动作与模组命名空间随包带上（ShopInputScreen 确认时会把整个 request 原样发回服务端）。
        request.putString("action", "admin_mall_import");
        request.putString("mod", this.selected);
        request.putString("title", "gui.module_shop.input.mall_import");
        request.put("title_args", stringList(displayName(this.selected)));
        request.put("fields", stringList("price", "quantity", "rarity"));
        // 库存留 -1 = 无限；品质默认普通（界面上是四个按钮，点一下即改）。
        request.putString("default_quantity", "-1");
        request.putString("default_rarity", "common");
        // 「只要物品，不要技术物品」：客户端按**自己语言的名字表**判断哪些物品有显示名，
        // 用一个位掩码告诉服务端要保留哪几件（注册表顺序两端一致，掩码很小，几十字节）。
        byte[] mask = this.skipUnnamed ? buildNameMask(this.selected) : new byte[0];
        request.putByteArray("mask", mask);
        request.putInt("mask_bits", this.skipUnnamed ? mask.length * 8 : 0);
        Nav.open(new ShopInputScreen(request));
    }

    /**
     * 生成「保留哪些物品」的位掩码：按**注册表顺序**遍历该模组的物品，
     * 有显示名的（不是 {@code item.xxx} 这种裸键）置 1。
     *
     * <p>客户端有完整的语言表（模组自带的中英名字都在），所以这个判断在客户端做最准；
     * 服务端按同样的顺序遍历一遍即可还原。物品太多（超过 {@link #MASK_LIMIT}）时返回空掩码 =
     * 不做过滤，交给服务端按数量上限处理。
     */
    private static byte[] buildNameMask(String namespace) {
        List<Boolean> keep = new ArrayList<>();
        for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
            if (id == null || !namespace.equals(id.getNamespace()) || item == net.minecraft.world.item.Items.AIR) {
                continue;
            }
            keep.add(hasDisplayName(item));
            if (keep.size() > MASK_LIMIT) {
                return new byte[0];
            }
        }
        byte[] mask = new byte[(keep.size() + 7) / 8];
        for (int i = 0; i < keep.size(); i++) {
            if (keep.get(i)) {
                mask[i >> 3] |= (byte) (1 << (i & 7));
            }
        }
        return mask;
    }

    /** 这件物品在当前语言下有真正的名字吗（没有的话显示出来就是 {@code item.xxx} 这种裸键）。 */
    private static boolean hasDisplayName(net.minecraft.world.item.Item item) {
        try {
            String plain = new net.minecraft.world.item.ItemStack(item).getHoverName().getString();
            if (plain.isEmpty()) {
                return false;
            }
            // 没有翻译时 getHoverName() 会原样返回描述 id（item.xxx / block.xxx）。
            return !plain.equals(item.getDescriptionId())
                    && !plain.startsWith("item.") && !plain.startsWith("block.");
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** ESC / 取消：回上一级（商店主界面），不要直接关到游戏里。 */
    @Override
    public void onClose() {
        Nav.back();
    }

    private static net.minecraft.nbt.ListTag stringList(String... values) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (String value : values) {
            list.add(net.minecraft.nbt.StringTag.valueOf(value));
        }
        return list;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.listOpen && scrollY != 0.0D) {
            this.scroll = Math.max(0, this.scroll + (scrollY > 0 ? -1 : 1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
