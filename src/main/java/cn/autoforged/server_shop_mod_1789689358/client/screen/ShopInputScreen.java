package cn.autoforged.server_shop_mod_1789689358.client.screen;

import cn.autoforged.server_shop_mod_1789689358.data.Rarity;
import cn.autoforged.server_shop_mod_1789689358.network.payload.ServerboundShopActionPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** 通用数值/文本输入界面：管理员配置、拍卖出价、转账、修改价格都走这里。 */
public class ShopInputScreen extends Screen {
    /** 品质字段不用输入框，改成四个品质按钮（点一下就选中，按钮上方显示「当前品质：X」）。 */
    private static final String RARITY_FIELD = "rarity";
    private final CompoundTag request;
    private final List<String> fieldNames = new ArrayList<>();
    private final List<EditBox> boxes = new ArrayList<>();
    /** 与 {@link #boxes} 一一对应的字段名（品质字段没有输入框，所以下标不能直接跟着 fieldNames 走）。 */
    private final List<String> boxFields = new ArrayList<>();
    /** 每个品质按钮的矩形（x, y, w, h, rarityIndex），渲染时给选中项描边。 */
    private final List<int[]> rarityRects = new ArrayList<>();
    /** 当前选中的品质 id。 */
    private String rarityValue = Rarity.COMMON.id();
    /**
     * 选项字段（提供 {@code choices_<字段名>} 的字段）：值 → 当前选择；值 → 按钮矩形。
     * 用于「补货设置」这类开关 / 二选一（关/开、现实时间/游戏时间），点按钮即选中，不用手打。
     */
    private final java.util.Map<String, String> choiceValues = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, List<int[]>> choiceRects = new java.util.LinkedHashMap<>();
    private final Component title;
    private final String info;
    private final int panelW = 240;
    private int panelH;
    private int left;
    private int top;

    public ShopInputScreen(CompoundTag request) {
        super(Component.translatable("gui.server_shop_mod.input.title"));
        this.request = request.copy();
        // 标题允许带参数（例如「取款数量（星币 → 银币）」里的货币名），在客户端按本地语言渲染。
        ListTag titleArgs = request.getList("title_args", 8);
        if (titleArgs.isEmpty()) {
            this.title = Component.translatable(request.getString("title"));
        } else {
            Object[] args = new Object[titleArgs.size()];
            for (int i = 0; i < titleArgs.size(); i++) {
                args[i] = titleArgs.getString(i);
            }
            this.title = Component.translatable(request.getString("title"), args);
        }
        this.info = request.getString("info");
        ListTag fields = request.getList("fields", 8);
        for (int i = 0; i < fields.size(); i++) {
            this.fieldNames.add(fields.getString(i));
        }
        String presetRarity = request.getString("default_" + RARITY_FIELD).trim();
        Rarity preset = Rarity.byIdOrNull(presetRarity);
        this.rarityValue = preset == null ? Rarity.COMMON.id() : preset.id();
    }

    private static boolean isRarityField(String field) {
        return RARITY_FIELD.equals(field);
    }

    /** 这个字段是不是「选项字段」（请求里带 {@code choices_<字段>} 列表）。 */
    private ListTag choicesOf(String field) {
        return this.request.getList("choices_" + field, 8);
    }

    /** 选项字段的显示文案（用 lang 键，随语言切换）。 */
    private static Component choiceLabel(String field, String value) {
        return Component.translatable("gui.server_shop_mod.choice." + field + "." + value);
    }

    /** 时间字段（以 {@code _time} 结尾）：只输数字、自动补冒号，格式不对会提示且不让确认。 */
    private static boolean isTimeField(String field) {
        return field != null && field.endsWith("_time");
    }

    /** 「0600」→「06:00」：只留数字，超过两位就自动插冒号。 */
    private static String autoColon(String raw) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < raw.length() && digits.length() < 4; i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        if (digits.length() <= 2) {
            return digits.toString();
        }
        return digits.substring(0, 2) + ":" + digits.substring(2);
    }

    /** 「HH:mm」是否合法。 */
    private static boolean validClock(String text) {
        if (text == null || text.isEmpty()) {
            return true;   // 留空 = 不改这项
        }
        int colon = text.indexOf(':');
        if (colon <= 0 || colon >= text.length() - 1) {
            return false;
        }
        try {
            int hour = Integer.parseInt(text.substring(0, colon));
            int minute = Integer.parseInt(text.substring(colon + 1));
            return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** 有没有格式不对的时间字段（有的话红字提示并且不让确认）。 */
    private boolean timeBad() {
        for (int i = 0; i < this.boxFields.size(); i++) {
            if (isTimeField(this.boxFields.get(i)) && !validClock(this.boxes.get(i).getValue().trim())) {
                return true;
            }
        }
        return false;
    }

    private int infoOffset() {
        return this.info.isEmpty() ? 0 : 14;
    }

    /** 需求 3：交易金额界面在标题/提示下方额外显示一行“玩家余额”。 */
    private boolean showBalance() {
        return this.request.getBoolean("show_balance");
    }

    /** 输入框里填的数量（不是数字时返回 0）。 */
    private long enteredQuantity() {
        if (this.boxes.isEmpty()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(this.boxes.get(0).getValue().trim()));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * 输入的数量是否超出余额能承担的范围（用于货币兑换取款的「余额不足」提示）。
     * 用「数量 &gt; 余额 / 单价」判断，避免 数量 × 单价 溢出。
     */
    private boolean overBalance() {
        long unit = this.request.getLong("unit_price");
        long balance = this.request.getLong("balance");
        if (unit <= 0L || !showBalance()) {
            return false;
        }
        long quantity = enteredQuantity();
        return quantity > 0L && quantity > balance / unit;
    }

    private int headerOffset() {
        return infoOffset() + (showBalance() ? 14 : 0);
    }

    /** 需求 1（bug 修复）：交易金额输入界面关闭/确认后需要回到交易界面。 */
    private boolean isTradeMoney() {
        return "trade_set_money".equals(this.request.getString("action"));
    }

    private void returnToTradeIfNeeded() {
        if (isTradeMoney() && !cn.autoforged.server_shop_mod_1789689358.client.ClientShopData.tradeSelfName.isEmpty()) {
            Nav.open(new TradeScreen());
        }
    }

    @Override
    protected void init() {
        super.init();
        // init() 在窗口 resize 时会再次执行：先把已输入的值记下来、清空 boxes 再重建，
        // 否则 boxes 会累积成 2N 条，setInitialFocus/confirm 读到的是上一轮已经不在界面上的旧输入框
        // （玩家看到的输入框里敲的数字不会进入发送包）。
        List<String> previous = new ArrayList<>();
        for (EditBox box : this.boxes) {
            previous.add(box.getValue());
        }
        this.boxes.clear();
        this.boxFields.clear();
        this.rarityRects.clear();
        this.choiceValues.clear();
        this.choiceRects.clear();
        this.panelH = 70 + this.fieldNames.size() * 33 + headerOffset();
        this.left = (this.width - this.panelW) / 2;
        this.top = (this.height - this.panelH) / 2;
        for (int i = 0; i < this.fieldNames.size(); i++) {
            String field = this.fieldNames.get(i);
            if (isRarityField(field)) {
                // 品质：不再用输入框（以前要手打 legendary/epic/rare/common，容易打错），
                // 改成四个按钮，点一下即选中，按钮上方显示「品质　当前品质：X」。
                int rowY = this.top + 38 + headerOffset() + i * 33;
                int btnW = 47;
                int gap = 4;
                Rarity[] all = Rarity.values();
                for (int k = 0; k < all.length; k++) {
                    Rarity rarity = all[k];
                    int bx = this.left + 20 + k * (btnW + gap);
                    this.rarityRects.add(new int[]{bx, rowY, btnW, 18, k});
                    this.addRenderableWidget(new ShopButton(bx, rowY, btnW, 18,
                            Component.translatable(rarity.translationKey()),
                            b -> this.rarityValue = rarity.id()));
                }
                continue;
            }
            ListTag choices = choicesOf(field);
            if (!choices.isEmpty()) {
                // 选项字段：一排按钮（关/开、现实时间/游戏时间…），点一下即选中，上方显示「当前：X」。
                int rowY = this.top + 38 + headerOffset() + i * 33;
                int count = Math.min(4, choices.size());
                int gap = 4;
                int btnW = Math.min(110, (this.panelW - 40 - gap * (count - 1)) / Math.max(1, count));
                String presetValue = this.request.getString("default_" + field).trim();
                String current = presetValue;
                for (int k = 0; k < choices.size(); k++) {
                    if (choices.getString(k).equals(presetValue)) {
                        current = choices.getString(k);
                    }
                }
                if (current.isEmpty() && choices.size() > 0) {
                    current = choices.getString(0);
                }
                this.choiceValues.put(field, current);
                List<int[]> rects = new ArrayList<>();
                for (int k = 0; k < count; k++) {
                    String value = choices.getString(k);
                    int bx = this.left + 20 + k * (btnW + gap);
                    rects.add(new int[]{bx, rowY, btnW, 18});
                    final String picked = value;
                    this.addRenderableWidget(new ShopButton(bx, rowY, btnW, 18,
                            choiceLabel(field, value), b -> this.choiceValues.put(field, picked)));
                }
                this.choiceRects.put(field, rects);
                continue;
            }
            EditBox box = new EditBox(this.font, this.left + 20, this.top + 38 + headerOffset() + i * 33, this.panelW - 40, 18,
                    Component.translatable("gui.server_shop_mod.field." + field));
            box.setMaxLength(32);
            // 需求 6/7：管理界面编辑时带出当前值，方便修改而不是重新输入。
            String preset = request.getString("default_" + field);
            if (!preset.isEmpty()) {
                box.setValue(preset);
            }
            // resize 前已经输入过的内容优先保留，避免重建输入框时把玩家输入清掉。
            if (i < previous.size() && !previous.get(i).isEmpty()) {
                box.setValue(previous.get(i));
            }
            if (isTimeField(field)) {
                // 时间输入：自动补冒号（输入 0600 就变成 06:00），并且只允许数字与冒号
                box.setMaxLength(5);
                box.setFilter(text -> text.isEmpty() || text.matches("[0-9:]{0,5}"));
                box.setResponder(text -> {
                    String formatted = autoColon(text);
                    if (!formatted.equals(text)) {
                        box.setValue(formatted);
                    }
                });
            }
            this.addRenderableWidget(box);
            this.boxes.add(box);
            this.boxFields.add(field);
        }
        int buttonY = this.top + this.panelH - 30;
        // 需求 7/8：防误触按钮统一为“左侧确认、右侧取消”；confirm_right 保留作兼容开关（true 才反过来）。
        boolean confirmRight = this.request.getBoolean("confirm_right");
        int leftX = this.left + 24;
        int rightX = this.left + this.panelW - 114;
        this.addRenderableWidget(new ShopButton(leftX, buttonY, 90, 20,
                Component.translatable(confirmRight ? "gui.server_shop_mod.cancel" : "gui.server_shop_mod.confirm"),
                b -> {
                    if (confirmRight) {
                        onClose();
                    } else {
                        confirm();
                    }
                }));
        this.addRenderableWidget(new ShopButton(rightX, buttonY, 90, 20,
                Component.translatable(confirmRight ? "gui.server_shop_mod.confirm" : "gui.server_shop_mod.cancel"),
                b -> {
                    if (confirmRight) {
                        confirm();
                    } else {
                        onClose();
                    }
                }));
        if (!this.boxes.isEmpty()) {
            this.setInitialFocus(this.boxes.get(0));
        }
    }

    private void confirm() {
        // 时间字段格式不对：不进包、不关界面，红字让玩家改（服务端也会再校验一次）
        if (timeBad()) {
            return;
        }
        CompoundTag tag = this.request.copy();
        for (int i = 0; i < this.boxes.size(); i++) {
            tag.putString(this.boxFields.get(i), this.boxes.get(i).getValue().trim());
        }
        // 品质是按钮选的，值单独带上（没有输入框，所以不在上面的循环里）。
        if (this.fieldNames.contains(RARITY_FIELD)) {
            tag.putString(RARITY_FIELD, this.rarityValue);
        }
        // 选项字段（开关 / 二选一）同样是按钮选的。
        for (java.util.Map.Entry<String, String> entry : this.choiceValues.entrySet()) {
            tag.putString(entry.getKey(), entry.getValue());
        }
        ServerboundShopActionPayload.send(tag);
        // 需求 1（bug 修复）：确认后**立刻返回上一级**（和「取消」走同一条返回路径）。
        // 旧版只对「交易金额」做了返回，其它入口（管理员编辑商品、出价、兑换…）确认后就一直停在
        // 这个输入界面上，玩家以为「确认没生效」，其实数据早就在服务端改好了。
        Nav.back();
        returnToTradeIfNeeded();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        Bricks.panel(g, this.left, this.top, this.panelW, this.panelH);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        // 标题/提示过长时截断，避免顶出面板（货币名可能很长，例如别的模组的“银币锭”）。
        int maxTextW = this.panelW - 20;
        IOStyle.centerAt(g, this.font, truncate(this.title, maxTextW), this.left + this.panelW / 2, this.top + 12, IOStyle.ORANGE);
        if (!this.info.isEmpty()) {
            IOStyle.centerAt(g, this.font, truncate(Component.literal(this.info), maxTextW), this.left + this.panelW / 2,
                    this.top + 27, IOStyle.ORANGE);
        }
        // 需求 3：交易金额界面显示玩家余额（数值由服务端在打开输入界面时下发）。
        if (showBalance()) {
            long balance = this.request.getLong("balance");
            int balanceY = this.top + 27 + infoOffset();
            Component balanceText = Component.translatable("gui.server_shop_mod.trade.balance",
                    cn.autoforged.server_shop_mod_1789689358.client.ClientShopData.money(balance));
            if (overBalance()) {
                // 输入的数量超过余额能兑换的数量：余额改为左对齐，右侧腾出位置红字提示「余额不足」。
                g.drawString(this.font, balanceText, this.left + 20, balanceY, IOStyle.ORANGE, false);
                Component warn = Component.translatable("gui.server_shop_mod.input.not_enough");
                g.drawString(this.font, warn, this.left + this.panelW - 12 - this.font.width(warn), balanceY,
                        IOStyle.RED, true);
            } else {
                IOStyle.centerAt(g, this.font, balanceText, this.left + this.panelW / 2, balanceY, IOStyle.ORANGE);
            }
        }
        for (int i = 0; i < this.fieldNames.size(); i++) {
            String field = this.fieldNames.get(i);
            int labelY = this.top + 26 + headerOffset() + i * 33;
            if (isRarityField(field)) {
                // 「品质」+ 右侧「当前品质：X」（按品质配色），与按钮同一行上方，互不重叠。
                Component label = Component.translatable("gui.server_shop_mod.field." + RARITY_FIELD);
                g.drawString(this.font, label, this.left + 20, labelY, IOStyle.TEXT_2, true);
                Rarity current = Rarity.byId(this.rarityValue);
                g.drawString(this.font,
                        Component.translatable("gui.server_shop_mod.quality_current",
                                Component.translatable(current.translationKey())),
                        this.left + 20 + this.font.width(label) + 10, labelY, current.color(), true);
                // 选中的那一个按钮加一圈品质色描边（按钮本身是原版样式，这里只勾边）。
                for (int[] rect : this.rarityRects) {
                    Rarity rarity = Rarity.values()[rect[4]];
                    boolean selected = rarity.id().equals(this.rarityValue);
                    if (selected) {
                        IOStyle.ring(g, rect[0] - 1, rect[1] - 1, rect[2] + 2, rect[3] + 2, 0, rarity.color());
                    } else {
                        IOStyle.ring(g, rect[0] - 1, rect[1] - 1, rect[2] + 2, rect[3] + 2, 0, IOStyle.BORDER_DIM);
                    }
                }
                continue;
            }
            List<int[]> rects = this.choiceRects.get(field);
            if (rects != null) {
                // 「字段名　当前：X」+ 选中项描边（和品质按钮同一套观感）。
                Component label = Component.translatable("gui.server_shop_mod.field." + field);
                g.drawString(this.font, label, this.left + 20, labelY, IOStyle.TEXT_2, true);
                String value = this.choiceValues.getOrDefault(field, "");
                g.drawString(this.font,
                        Component.translatable("gui.server_shop_mod.choice_current",
                                value.isEmpty() ? Component.literal("-") : choiceLabel(field, value)),
                        this.left + 20 + this.font.width(label) + 10, labelY, IOStyle.BLUE, true);
                for (int k = 0; k < rects.size(); k++) {
                    int[] rect = rects.get(k);
                    boolean selected = k < choicesOf(field).size()
                            && choicesOf(field).getString(k).equals(value);
                    IOStyle.ring(g, rect[0] - 1, rect[1] - 1, rect[2] + 2, rect[3] + 2, 0,
                            selected ? IOStyle.BLUE : IOStyle.BORDER_DIM);
                }
                continue;
            }
            g.drawString(this.font, Component.translatable("gui.server_shop_mod.field." + field),
                    this.left + 20, labelY, IOStyle.TEXT_2, true);
        }
        // 需求 7：购买弹窗实时显示总价与余额变化（货币兑换取款复用，并换用自己的文案 key）。
        long unitPrice = this.request.getLong("unit_price");
        if (unitPrice > 0L && !this.boxes.isEmpty()) {
            long quantity = enteredQuantity();
            long total = quantity > Long.MAX_VALUE / unitPrice ? Long.MAX_VALUE : quantity * unitPrice;
            long balanceAfter = this.request.getLong("balance") - total;
            String totalKey = this.request.contains("total_key")
                    ? this.request.getString("total_key") : "gui.server_shop_mod.buy_total";
            IOStyle.centerAt(g, this.font, Component.translatable(totalKey,
                            cn.autoforged.server_shop_mod_1789689358.client.ClientShopData.money(total),
                            cn.autoforged.server_shop_mod_1789689358.client.ClientShopData.money(balanceAfter)),
                    this.left + this.panelW / 2, this.top + this.panelH - 46, overBalance() ? IOStyle.RED : IOStyle.ORANGE);
        }
        // 时间格式错误：红字提示（并且确认按钮点了不会生效）
        if (timeBad()) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.server_shop_mod.input.time_bad"),
                    this.left + this.panelW / 2, this.top + this.panelH - 46, IOStyle.RED);
        }
    }

    /** 按面板宽度截断文本（超出部分用省略号结尾）。 */
    private Component truncate(Component text, int maxWidth) {
        String plain = text.getString();
        if (this.font.width(plain) <= maxWidth) {
            return text;
        }
        return Component.literal(this.font.plainSubstrByWidth(plain, maxWidth - 6) + "...");
    }

    @Override
    public void onClose() {
        // 需求 1（bug 修复）：取消交易金额输入后回到交易界面，而不是直接关闭所有界面。
        Nav.back();
        returnToTradeIfNeeded();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
