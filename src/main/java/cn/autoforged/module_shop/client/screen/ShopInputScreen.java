package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.atom.AtomUi;
import cn.autoforged.module_shop.data.Rarity;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import com.atom.lib.design.atomchat.Design;
import com.atom.lib.design.atomchat.UiCards;
import com.atom.lib.design.atomchat.UiTokens;
import com.atom.lib.font.FontId;
import com.atom.lib.geometry.Rect;
import com.atom.lib.input.PointerEvent;
import com.atom.lib.input.PointerTarget;
import com.atom.lib.layout.Constraints;
import com.atom.lib.layout.LayoutBox;
import com.atom.lib.layout.LayoutNode;
import com.atom.lib.layout.LayoutStyle;
import com.atom.lib.layout.Length;
import com.atom.lib.layout.NodeId;
import com.atom.lib.layout.PresentationFrame;
import com.atom.lib.text.TextLayout;
import com.atom.lib.text.TextRequest;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.Util;
import net.minecraft.util.StringUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 通用数值/文本输入界面（AtomLib 绘制，打开路径和提交内容与原来的输入框一致）。
 * <p>
 * 构造器仍是 {@code ShopInputScreen(CompoundTag)}。玩家点开走 {@link Nav#open}，
 * 服务端 {@code kind=input} 仍走 {@link Nav#openPushed}。确认把整份请求原样发回
 * {@link ServerboundShopActionPayload}，再 {@link Nav#back()}；取消、ESC、{@link #onClose()}
 * 不发包，同样返回上一级。交易金额还会在返回后打开交易界面。
 * 点按钮响 {@link Bricks#click()}。文本框不处理 Enter，所以打字时按 Enter 不会提交；
 * Tab 到确认/取消后按 Enter 或空格才会按那个按钮。
 */
public class ShopInputScreen extends AtomScreen {
    /** 品质字段不用输入框，改成四个品质按钮。 */
    private static final String RARITY_FIELD = "rarity";
    private static final float CARD_W = 280.0F;
    private static final float TEXT_W = 256.0F;
    private static final float FIELD_H = 20.0F;
    private static final float BUTTON_H = 20.0F;
    private static final float BUTTON_W = 100.0F;
    private static final float INPUT_SIZE = 13.0F;

    private static final NodeId CARD = new NodeId("input.card");
    private static final NodeId LEFT = new NodeId("input.left");
    private static final NodeId RIGHT = new NodeId("input.right");

    private final CompoundTag request;
    private final List<String> fieldNames = new ArrayList<>();
    private final List<Slot> chain = new ArrayList<>();
    private final Map<NodeId, Slot> slots = new LinkedHashMap<>();
    private final Map<NodeId, PointerTarget> targets = new LinkedHashMap<>();
    private final Set<NodeId> interactive;
    private final Component title;
    private final String info;
    private final boolean confirmOnLeft;
    /** 当前选中的品质 id。窗口缩放时保留，不跟选项字段一起重置。 */
    private String rarityValue = Rarity.COMMON.id();
    /** 选项字段（请求里带 {@code choices_<字段名>}）：字段名 → 当前选择。 */
    private final Map<String, String> choiceValues = new LinkedHashMap<>();

    private boolean closed;
    private int focusIndex = -1;
    private int mouseX;
    private int mouseY;

    public ShopInputScreen(CompoundTag request) {
        super(Component.translatable("gui.module_shop.input.title"));
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
        // 需求 7/8：防误触按钮统一为“左侧确认、右侧取消”；confirm_right 为 true 才反过来。
        this.confirmOnLeft = !request.getBoolean("confirm_right");
        for (int i = 0; i < this.fieldNames.size(); i++) {
            this.addFieldSlots(i, this.fieldNames.get(i));
        }
        this.addSlot(Slot.press(LEFT, -1, this.confirmOnLeft ? this::confirm : this::onClose));
        this.addSlot(Slot.press(RIGHT, -1, this.confirmOnLeft ? this::onClose : this::confirm));
        this.interactive = Set.copyOf(this.slots.keySet());
        this.resetChoices();
        this.applyText(Map.of());
    }

    private void addFieldSlots(int index, String field) {
        if (isRarityField(field)) {
            Rarity[] all = Rarity.values();
            for (int k = 0; k < all.length; k++) {
                Rarity rarity = all[k];
                this.addSlot(Slot.rarity(new NodeId("input.rarity." + index + "." + k), index,
                        () -> this.rarityValue = rarity.id(), rarity));
            }
            return;
        }
        ListTag choices = this.choicesOf(field);
        if (!choices.isEmpty()) {
            int count = Math.min(4, choices.size());
            for (int k = 0; k < count; k++) {
                String picked = choices.getString(k);
                this.addSlot(Slot.choice(new NodeId("input.choice." + index + "." + k), index,
                        () -> this.choiceValues.put(field, picked), field, picked));
            }
            return;
        }
        TextField box = new TextField(field, isTimeField(field) ? 5 : 32);
        this.addSlot(Slot.text(new NodeId("input.box." + index), index, box));
    }

    private void addSlot(Slot slot) {
        this.chain.add(slot);
        this.slots.put(slot.id, slot);
        this.targets.put(slot.id, new SlotTarget(slot));
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
        return Component.translatable("gui.module_shop.choice." + field + "." + value);
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

    /** 「HH:mm」是否合法。留空表示不改这项。 */
    private static boolean validClock(String text) {
        if (text == null || text.isEmpty()) {
            return true;
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
        for (Slot slot : this.chain) {
            if (slot.field != null && isTimeField(slot.field.name) && !validClock(slot.field.value.trim())) {
                return true;
            }
        }
        return false;
    }

    /** 需求 3：交易金额界面在标题/提示下方额外显示一行“玩家余额”。 */
    private boolean showBalance() {
        return this.request.getBoolean("show_balance");
    }

    /** 输入框里填的数量（第一个文本框；不是数字时返回 0）。 */
    private long enteredQuantity() {
        TextField first = this.firstText();
        if (first == null) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(first.value.trim()));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private TextField firstText() {
        for (Slot slot : this.chain) {
            if (slot.field != null) {
                return slot.field;
            }
        }
        return null;
    }

    /**
     * 输入的数量是否超出余额能承担的范围（用于货币兑换取款的「余额不足」提示）。
     * 用「数量 &gt; 余额 / 单价」判断，避免 数量 × 单价 溢出。
     */
    private boolean overBalance() {
        long unit = this.request.getLong("unit_price");
        long balance = this.request.getLong("balance");
        if (unit <= 0L || !this.showBalance()) {
            return false;
        }
        long quantity = this.enteredQuantity();
        return quantity > 0L && quantity > balance / unit;
    }

    /** 需求 1（bug 修复）：交易金额输入界面关闭/确认后需要回到交易界面。 */
    private boolean isTradeMoney() {
        return "trade_set_money".equals(this.request.getString("action"));
    }

    private void returnToTradeIfNeeded() {
        if (this.isTradeMoney() && !ClientShopData.tradeSelfName.isEmpty()) {
            Nav.open(new TradeScreen());
        }
    }

    /** 窗口缩放会再次 {@code init}：按字段名留下已输入的字，空字符串仍回退到预设。 */
    @Override
    protected void init() {
        super.init();
        Map<String, String> previous = new LinkedHashMap<>();
        for (Slot slot : this.chain) {
            if (slot.field != null) {
                previous.put(slot.field.name, slot.field.value);
            }
        }
        this.applyText(previous);
        // 选项按钮跟原来一样，缩放时回到请求里的默认值；品质选择保留。
        this.resetChoices();
        this.focusFirstText();
    }

    private void applyText(Map<String, String> previous) {
        for (Slot slot : this.chain) {
            if (slot.field == null) {
                continue;
            }
            String kept = previous.get(slot.field.name);
            String preset = this.request.getString("default_" + slot.field.name);
            slot.field.setValue(kept != null && !kept.isEmpty() ? kept : preset);
        }
    }

    private void resetChoices() {
        this.choiceValues.clear();
        for (String field : this.fieldNames) {
            ListTag choices = this.choicesOf(field);
            if (choices.isEmpty() || isRarityField(field)) {
                continue;
            }
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
        }
    }

    private void focusFirstText() {
        this.focusIndex = -1;
        for (int i = 0; i < this.chain.size(); i++) {
            if (this.chain.get(i).field != null) {
                this.focusIndex = i;
                this.chain.get(i).field.poke();
                return;
            }
        }
    }

    private Slot focused() {
        if (this.focusIndex < 0 || this.focusIndex >= this.chain.size()) {
            return null;
        }
        return this.chain.get(this.focusIndex);
    }

    private void confirm() {
        // 时间字段格式不对：不进包、不关界面，红字让玩家改（服务端也会再校验一次）。
        if (this.closed || this.timeBad()) {
            return;
        }
        CompoundTag tag = this.request.copy();
        for (Slot slot : this.chain) {
            if (slot.field != null) {
                tag.putString(slot.field.name, slot.field.value.trim());
            }
        }
        // 品质是按钮选的，值单独带上（没有输入框，所以不在上面的循环里）。
        if (this.fieldNames.contains(RARITY_FIELD)) {
            tag.putString(RARITY_FIELD, this.rarityValue);
        }
        // 选项字段（开关 / 二选一）同样是按钮选的。
        for (Map.Entry<String, String> entry : this.choiceValues.entrySet()) {
            tag.putString(entry.getKey(), entry.getValue());
        }
        ServerboundShopActionPayload.send(tag);
        // 确认后立刻返回上一级，和取消走同一条返回路径。
        this.leave();
    }

    @Override
    public void onClose() {
        this.leave();
    }

    /** 回到上一级，只走一次，避免关闭过程里再退一层。 */
    private void leave() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        Nav.back();
        this.returnToTradeIfNeeded();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.closed) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && this.shouldCloseOnEsc()) {
            this.onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB && !this.chain.isEmpty()) {
            int direction = Screen.hasShiftDown() ? -1 : 1;
            if (this.focusIndex < 0) {
                this.focusIndex = direction > 0 ? 0 : this.chain.size() - 1;
            } else {
                this.focusIndex = Math.floorMod(this.focusIndex + direction, this.chain.size());
            }
            Slot slot = this.focused();
            if (slot != null && slot.field != null) {
                slot.field.poke();
            }
            return true;
        }
        Slot slot = this.focused();
        if (slot != null && slot.field != null && slot.field.keyPressed(keyCode)) {
            return true;
        }
        if (slot != null && slot.field == null && slot.action != null && isActivateKey(keyCode)) {
            Bricks.click();
            slot.action.run();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static boolean isActivateKey(int keyCode) {
        return keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.closed || Screen.hasControlDown() || Screen.hasAltDown()) {
            return super.charTyped(codePoint, modifiers);
        }
        Slot slot = this.focused();
        if (slot != null && slot.field != null && StringUtil.isAllowedChatCharacter(codePoint)) {
            slot.field.insert(String.valueOf(codePoint));
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        if (this.width < 2 || this.height < 2 || this.closed) {
            return;
        }
        int cardFill = UiTokens.cardFill();
        boolean light = Design.isLight(cardFill);
        int titleColor = light ? 0xFF8A4B12 : 0xFFE0B15E;
        int bodyColor = light ? 0xFF1B1E27 : 0xFFF3F5F9;
        int labelColor = light ? 0xFF5C6570 : 0xFF9AA4B5;
        int fieldFill = light ? 0xFFFFFFFF : 0xFF12151C;
        int danger = 0xFFE5645F;
        int choiceColor = light ? 0xFF2B4BFF : 0xFF8EB4FF;
        String titleText = this.title == null ? "" : this.title.getString();
        String infoText = this.info == null ? "" : this.info;
        Map<NodeId, Glyph> glyphs = new LinkedHashMap<>();
        List<LayoutNode> column = new ArrayList<>();
        column.add(this.glyph(glyphs, "input.title", titleText, true, 15.0F, titleColor, TEXT_W));
        if (!infoText.isEmpty()) {
            column.add(this.glyph(glyphs, "input.info", infoText, false, 13.0F, titleColor, TEXT_W));
        }
        if (this.showBalance()) {
            long balance = this.request.getLong("balance");
            String balanceText = Component.translatable("gui.module_shop.trade.balance", ClientShopData.money(balance))
                    .getString();
            if (this.overBalance()) {
                String warnText = Component.translatable("gui.module_shop.input.not_enough").getString();
                float warnW = Math.min(this.textWidth(warnText, false, 12.0F), TEXT_W * 0.46F);
                float balanceW = Math.max(48.0F, TEXT_W - warnW - 8.0F);
                column.add(AtomUi.node("input.balance.row",
                        LayoutStyle.row().size(Length.points(TEXT_W), Length.auto()).spacing(8.0F, 0.0F)
                                .justify(LayoutStyle.Justify.SPACE_BETWEEN).align(LayoutStyle.Align.CENTER),
                        List.of(
                                this.glyph(glyphs, "input.balance", balanceText, false, 12.0F, titleColor, balanceW),
                                this.glyph(glyphs, "input.balance.warn", warnText, false, 12.0F, danger, warnW))));
            } else {
                column.add(this.glyph(glyphs, "input.balance", balanceText, false, 13.0F, titleColor, TEXT_W));
            }
        }
        for (int i = 0; i < this.fieldNames.size(); i++) {
            column.add(this.fieldBlock(glyphs, i, labelColor, choiceColor));
        }
        long unitPrice = this.request.getLong("unit_price");
        TextField first = this.firstText();
        if (unitPrice > 0L && first != null) {
            long quantity = this.enteredQuantity();
            long total = quantity > Long.MAX_VALUE / unitPrice ? Long.MAX_VALUE : quantity * unitPrice;
            long balanceAfter = this.request.getLong("balance") - total;
            String totalKey = this.request.contains("total_key")
                    ? this.request.getString("total_key") : "gui.module_shop.buy_total";
            String totalText = Component.translatable(totalKey, ClientShopData.money(total),
                    ClientShopData.money(balanceAfter)).getString();
            int totalColor = this.overBalance() ? danger : titleColor;
            column.add(this.glyph(glyphs, "input.total", totalText, false, 12.0F, totalColor, TEXT_W));
        }
        if (this.timeBad()) {
            String timeText = Component.translatable("gui.module_shop.input.time_bad").getString();
            column.add(this.glyph(glyphs, "input.time", timeText, false, 12.0F, danger, TEXT_W));
        }
        column.add(AtomUi.node("input.actions",
                LayoutStyle.row().size(Length.points(TEXT_W), Length.auto())
                        .justify(LayoutStyle.Justify.SPACE_BETWEEN).align(LayoutStyle.Align.CENTER),
                List.of(AtomUi.fixed(LEFT.value(), BUTTON_W, BUTTON_H),
                        AtomUi.fixed(RIGHT.value(), BUTTON_W, BUTTON_H))));
        LayoutNode card = AtomUi.node(CARD.value(),
                LayoutStyle.column().size(Length.points(CARD_W), Length.auto()).padding(12.0F).spacing(6.0F, 0.0F)
                        .align(LayoutStyle.Align.CENTER),
                column);
        LayoutNode root = AtomUi.node("input.root",
                LayoutStyle.column().size(Length.points(this.width), Length.points(this.height))
                        .align(LayoutStyle.Align.CENTER).justify(LayoutStyle.Justify.CENTER),
                List.of(card));
        PresentationFrame frame = AtomUi.present(root, this.width, this.height);
        this.pointers.use(frame, this.interactive, this.targets);
        String confirmText = Component.translatable("gui.module_shop.confirm").getString();
        String cancelText = Component.translatable("gui.module_shop.cancel").getString();
        paintAtom(graphics, (canvas, borrowed) -> {
            for (NodeId id : frame.paintOrder()) {
                LayoutBox box = frame.box(id);
                if (box == null) {
                    continue;
                }
                Rect rect = box.border();
                if (CARD.equals(id)) {
                    UiCards.drawCard(canvas, rect.x(), rect.y(), rect.width(), rect.height(), UiTokens.cardRadius(),
                            0.0F);
                    continue;
                }
                Glyph glyph = glyphs.get(id);
                if (glyph != null) {
                    this.drawGlyph(canvas, glyph, box.content());
                    continue;
                }
                Slot slot = this.slots.get(id);
                if (slot == null) {
                    continue;
                }
                boolean hot = rect.contains(this.mouseX, this.mouseY);
                boolean keyed = slot == this.focused();
                if (slot.field != null) {
                    int ring = keyed ? UiTokens.accentFill() : UiTokens.hairline();
                    this.drawChrome(canvas, rect, fieldFill, ring, false);
                    this.drawFieldText(canvas, rect, slot.field, bodyColor, keyed);
                } else if (slot.rarity != null) {
                    boolean selected = slot.rarity.id().equals(this.rarityValue);
                    int color = slot.rarity.color() | 0xFF000000;
                    int fill = selected ? (light ? 0xFFFFF8EE : 0xFF2C3344) : (light ? 0xFFF3F0EA : 0xFF232833);
                    this.drawChrome(canvas, rect, fill, selected ? color : UiTokens.hairline(), hot || keyed);
                    AtomUi.drawTextCentered(canvas, Component.translatable(slot.rarity.translationKey()).getString(),
                            AtomUi.bold(), 12.0F, selected ? color : bodyColor, rect);
                } else if (slot.choiceField != null) {
                    boolean selected = slot.choiceValue.equals(this.choiceValues.get(slot.choiceField));
                    int fill = selected ? (light ? 0xFFF4F7FF : 0xFF243044) : (light ? 0xFFF3F0EA : 0xFF232833);
                    this.drawChrome(canvas, rect, fill, selected ? choiceColor : UiTokens.hairline(), hot || keyed);
                    AtomUi.drawTextCentered(canvas, choiceLabel(slot.choiceField, slot.choiceValue).getString(),
                            AtomUi.bold(), 12.0F, selected ? choiceColor : bodyColor, rect);
                } else if (LEFT.equals(id) || RIGHT.equals(id)) {
                    boolean confirm = LEFT.equals(id) == this.confirmOnLeft;
                    int fill = confirm ? UiTokens.accentFill() : 0xFF5A6270;
                    // 确认钮本身就是强调色，键盘焦点用正文色描边才看得出。
                    int ring = keyed ? bodyColor : 0;
                    this.drawChrome(canvas, rect, fill, ring, hot || keyed);
                    AtomUi.drawTextCentered(canvas, confirm ? confirmText : cancelText, AtomUi.bold(), 13.0F,
                            UiTokens.onAccent(fill), rect);
                }
            }
        });
    }

    private LayoutNode fieldBlock(Map<NodeId, Glyph> glyphs, int index, int labelColor, int choiceColor) {
        String field = this.fieldNames.get(index);
        List<LayoutNode> kids = new ArrayList<>();
        if (isRarityField(field)) {
            String label = Component.translatable("gui.module_shop.field." + RARITY_FIELD).getString();
            Rarity current = Rarity.byId(this.rarityValue);
            String currentText = Component.translatable("gui.module_shop.quality_current",
                    Component.translatable(current.translationKey())).getString();
            kids.add(this.splitLabel(glyphs, index, label, labelColor, currentText, current.color() | 0xFF000000));
            kids.add(this.optionRow(index));
        } else if (!this.choicesOf(field).isEmpty()) {
            String label = Component.translatable("gui.module_shop.field." + field).getString();
            String value = this.choiceValues.getOrDefault(field, "");
            String currentText = Component.translatable("gui.module_shop.choice_current",
                    value.isEmpty() ? Component.literal("-") : choiceLabel(field, value)).getString();
            kids.add(this.splitLabel(glyphs, index, label, labelColor, currentText, choiceColor));
            kids.add(this.optionRow(index));
        } else {
            String label = Component.translatable("gui.module_shop.field." + field).getString();
            kids.add(this.glyph(glyphs, "input.label." + index, label, false, 12.0F, labelColor, TEXT_W));
            kids.add(AtomUi.fixed("input.box." + index, TEXT_W, FIELD_H));
        }
        return AtomUi.node("input.block." + index,
                LayoutStyle.column().size(Length.points(TEXT_W), Length.auto()).spacing(2.0F, 0.0F)
                        .align(LayoutStyle.Align.FLEX_START),
                kids);
    }

    private LayoutNode splitLabel(Map<NodeId, Glyph> glyphs, int index, String label, int labelColor, String current,
            int currentColor) {
        float labelW = Math.min(Math.max(24.0F, this.textWidth(label, false, 12.0F)), TEXT_W * 0.48F);
        float currentW = Math.max(40.0F, TEXT_W - labelW - 8.0F);
        return AtomUi.node("input.caption." + index,
                LayoutStyle.row().size(Length.points(TEXT_W), Length.auto()).spacing(8.0F, 0.0F)
                        .align(LayoutStyle.Align.CENTER),
                List.of(
                        this.glyph(glyphs, "input.label." + index, label, false, 12.0F, labelColor, labelW),
                        this.glyph(glyphs, "input.current." + index, current, false, 12.0F, currentColor, currentW)));
    }

    private LayoutNode optionRow(int index) {
        List<Slot> options = new ArrayList<>();
        for (Slot slot : this.chain) {
            if (slot.fieldIndex == index && slot.field == null) {
                options.add(slot);
            }
        }
        float gap = 4.0F;
        int count = Math.max(1, options.size());
        float width = Math.min(110.0F, (TEXT_W - gap * (count - 1)) / count);
        List<LayoutNode> buttons = new ArrayList<>();
        for (Slot slot : options) {
            buttons.add(AtomUi.fixed(slot.id.value(), width, BUTTON_H));
        }
        return AtomUi.node("input.opts." + index,
                LayoutStyle.row().spacing(gap, 0.0F).align(LayoutStyle.Align.CENTER),
                buttons);
    }

    private LayoutNode glyph(Map<NodeId, Glyph> glyphs, String id, String text, boolean bold, float size, int color,
            float width) {
        glyphs.put(new NodeId(id), new Glyph(text == null ? "" : text, bold, size, color));
        return AtomUi.textLeaf(id, text == null ? "" : text, bold ? AtomUi.bold() : AtomUi.regular(), size, color,
                width);
    }

    private void drawGlyph(Canvas canvas, Glyph glyph, Rect rect) {
        AtomUi.drawText(canvas, glyph.text, glyph.bold ? AtomUi.bold() : AtomUi.regular(), glyph.size, glyph.color,
                rect.x(), rect.y(), rect.width());
    }

    private void drawChrome(Canvas canvas, Rect rect, int fill, int ring, boolean hot) {
        if (ring != 0) {
            UiCards.drawCard(canvas, rect.x() - 1.0F, rect.y() - 1.0F, rect.width() + 2.0F, rect.height() + 2.0F,
                    UiTokens.buttonRadius() + 1.0F, 0.0F, 0, 0.0F, ring);
        }
        UiCards.drawCard(canvas, rect.x(), rect.y(), rect.width(), rect.height(), UiTokens.buttonRadius(),
                hot ? 1.0F : 0.0F, 0x55000000, hot ? 6.0F : 2.0F, fill);
    }

    private void drawFieldText(Canvas canvas, Rect rect, TextField field, int color, boolean focused) {
        float padX = 6.0F;
        float visible = Math.max(1.0F, rect.width() - padX * 2.0F);
        TextLayout laid = field.layout();
        float textH = laid == null ? INPUT_SIZE : Math.max(INPUT_SIZE, laid.size().height());
        float textX = rect.x() + padX;
        float textY = rect.y() + (rect.height() - textH) / 2.0F;
        float caret = field.caretX(laid);
        float view = field.adjustScroll(caret, visible, laid);
        field.originX = textX;
        field.originY = textY;
        field.scroll = view;
        canvas.save();
        PathBuilder clipBuilder = new PathBuilder();
        Path clipPath = null;
        try {
            float clipY = rect.y() + 2.0F;
            float clipH = Math.max(1.0F, rect.height() - 4.0F);
            clipBuilder.moveTo(textX, clipY).lineTo(textX + visible, clipY).lineTo(textX + visible, clipY + clipH)
                    .lineTo(textX, clipY + clipH).closePath();
            clipPath = clipBuilder.snapshot();
            canvas.clipPath(clipPath);
            if (laid != null && field.cursor != field.anchor) {
                int from = Math.max(0, Math.min(field.value.length(), Math.min(field.cursor, field.anchor)));
                int to = Math.max(0, Math.min(field.value.length(), Math.max(field.cursor, field.anchor)));
                for (Rect piece : laid.selection(from, to)) {
                    UiCards.drawCard(canvas, textX + piece.x() - view, textY + piece.y(), piece.width(),
                            piece.height(), 1.0F, 0.0F, 0, 0.0F, 0x664C8DFF);
                }
            }
            AtomUi.drawText(canvas, field.value, AtomUi.regular(), INPUT_SIZE, color, textX - view, textY, 8000.0F);
            if (focused && field.caretOn()) {
                float caretX = textX + caret - view;
                UiCards.drawCard(canvas, caretX, textY, 1.25F, textH, 0.0F, 0.0F, 0, 0.0F, color);
            }
        } finally {
            canvas.restore();
            if (clipPath != null) {
                clipPath.close();
            }
            clipBuilder.close();
        }
    }

    private float textWidth(String value, boolean bold, float size) {
        if (value == null || value.isEmpty()) {
            return 0.0F;
        }
        TextLayout laid = AtomUi.text().layout(
                new TextRequest(value, bold ? AtomUi.bold() : AtomUi.regular(), size, 0xFFFFFFFF, 1, false),
                new Constraints(Constraints.Axis.atMost(2000.0F), Constraints.Axis.undefined()));
        return laid.size().width();
    }

    private final class SlotTarget implements PointerTarget {
        private final Slot slot;

        private SlotTarget(Slot slot) {
            this.slot = slot;
        }

        @Override
        public boolean press(PointerEvent event) {
            if (ShopInputScreen.this.closed) {
                return true;
            }
            ShopInputScreen.this.focusIndex = ShopInputScreen.this.chain.indexOf(this.slot);
            if (this.slot.field != null) {
                this.slot.field.place(event.x(), event.y(), false);
                this.slot.field.poke();
                return true;
            }
            Bricks.click();
            if (this.slot.action != null) {
                this.slot.action.run();
            }
            return true;
        }

        @Override
        public void drag(PointerEvent event) {
            if (this.slot.field != null && !ShopInputScreen.this.closed) {
                this.slot.field.place(event.x(), event.y(), true);
            }
        }

        @Override
        public void release(PointerEvent event) {
        }

        @Override
        public void cancel() {
        }
    }

    /** 一个文本框。光标、选区、时间冒号和长度限制都在这里，不依赖原版 {@code EditBox}。 */
    private static final class TextField {
        private final String name;
        private final int maxLength;
        private final boolean time;
        private String value = "";
        private int cursor;
        private int anchor;
        private float scroll;
        private float originX;
        private float originY;
        private long pokedAt;

        private TextField(String name, int maxLength) {
            this.name = name;
            this.maxLength = maxLength;
            this.time = isTimeField(name);
            this.poke();
        }

        private void poke() {
            this.pokedAt = Util.getMillis();
        }

        private boolean caretOn() {
            return ((Util.getMillis() - this.pokedAt) / 500L) % 2L == 0L;
        }

        private void setValue(String raw) {
            String text = raw == null ? "" : raw;
            if (text.length() > this.maxLength) {
                text = text.substring(0, this.maxLength);
            }
            if (this.time && !this.accept(text)) {
                return;
            }
            this.value = text;
            this.cursor = this.value.length();
            this.anchor = this.cursor;
            this.normalizeTime();
            this.poke();
        }

        private boolean accept(String text) {
            return text.isEmpty() || text.matches("[0-9:]{0,5}");
        }

        private void normalizeTime() {
            if (!this.time) {
                return;
            }
            String formatted = autoColon(this.value);
            if (formatted.equals(this.value) || !this.accept(formatted)) {
                return;
            }
            this.value = formatted;
            this.cursor = this.value.length();
            this.anchor = this.cursor;
        }

        private void insert(String raw) {
            int from = Math.min(this.cursor, this.anchor);
            int to = Math.max(this.cursor, this.anchor);
            int room = this.maxLength - this.value.length() - (from - to);
            if (room <= 0) {
                return;
            }
            String clean = StringUtil.filterText(raw == null ? "" : raw);
            if (clean.length() > room) {
                if (Character.isHighSurrogate(clean.charAt(room - 1))) {
                    room--;
                }
                clean = room <= 0 ? "" : clean.substring(0, room);
            }
            String next = new StringBuilder(this.value).replace(from, to, clean).toString();
            if (this.time && !this.accept(next)) {
                return;
            }
            this.value = next;
            this.cursor = from + clean.length();
            this.anchor = this.cursor;
            this.normalizeTime();
            this.poke();
        }

        private boolean keyPressed(int keyCode) {
            boolean shift = Screen.hasShiftDown();
            boolean control = Screen.hasControlDown();
            if (Screen.isSelectAll(keyCode)) {
                this.anchor = 0;
                this.cursor = this.value.length();
                this.poke();
                return true;
            }
            if (Screen.isCopy(keyCode)) {
                Minecraft.getInstance().keyboardHandler.setClipboard(this.selected());
                return true;
            }
            if (Screen.isPaste(keyCode)) {
                this.insert(clipboard());
                return true;
            }
            if (Screen.isCut(keyCode)) {
                Minecraft.getInstance().keyboardHandler.setClipboard(this.selected());
                this.insert("");
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_LEFT) {
                this.move(-1, shift, control);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_RIGHT) {
                this.move(1, shift, control);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_HOME) {
                this.cursor = 0;
                if (!shift) {
                    this.anchor = this.cursor;
                }
                this.poke();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_END) {
                this.cursor = this.value.length();
                if (!shift) {
                    this.anchor = this.cursor;
                }
                this.poke();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                this.erase(-1, control);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DELETE) {
                this.erase(1, control);
                return true;
            }
            // 原版输入框不处理 Enter，打字时按 Enter 不会提交。
            return false;
        }

        private static String clipboard() {
            String text = Minecraft.getInstance().keyboardHandler.getClipboard();
            return text == null ? "" : text;
        }

        private String selected() {
            int from = Math.min(this.cursor, this.anchor);
            int to = Math.max(this.cursor, this.anchor);
            if (from == to || from < 0 || to > this.value.length()) {
                return "";
            }
            return this.value.substring(from, to);
        }

        private void move(int direction, boolean select, boolean word) {
            int next = word ? this.word(direction) : Util.offsetByCodepoints(this.value, this.cursor, direction);
            this.cursor = Math.max(0, Math.min(this.value.length(), next));
            if (!select) {
                this.anchor = this.cursor;
            }
            this.poke();
        }

        /** 和原版输入框一样，只把空格当成单词边界。 */
        private int word(int direction) {
            int index = this.cursor;
            if (direction >= 0) {
                int length = this.value.length();
                index = this.value.indexOf(' ', index);
                if (index == -1) {
                    return length;
                }
                while (index < length && this.value.charAt(index) == ' ') {
                    index++;
                }
                return index;
            }
            while (index > 0 && this.value.charAt(index - 1) == ' ') {
                index--;
            }
            while (index > 0 && this.value.charAt(index - 1) != ' ') {
                index--;
            }
            return index;
        }

        private void erase(int direction, boolean word) {
            if (this.cursor != this.anchor) {
                this.insert("");
                return;
            }
            int next = word ? this.word(direction) : Util.offsetByCodepoints(this.value, this.cursor, direction);
            int from = Math.min(this.cursor, next);
            int to = Math.max(this.cursor, next);
            if (from == to) {
                return;
            }
            this.anchor = to;
            this.cursor = from;
            this.insert("");
        }

        private void place(float mouseX, float mouseY, boolean extend) {
            TextLayout laid = this.layout();
            int hit = 0;
            if (laid != null && !laid.lines().isEmpty()) {
                float localX = mouseX - this.originX + this.scroll;
                float localY = mouseY - this.originY;
                if (Float.isFinite(localX) && Float.isFinite(localY)) {
                    try {
                        hit = laid.hitTest(Math.max(0.0F, localX), Math.max(0.0F, localY));
                    } catch (RuntimeException ignored) {
                        hit = this.value.length();
                    }
                }
            } else if (mouseX > this.originX) {
                hit = this.value.length();
            }
            this.cursor = Math.max(0, Math.min(this.value.length(), hit));
            if (!extend) {
                this.anchor = this.cursor;
            }
            this.poke();
        }

        private TextLayout layout() {
            if (this.value.isEmpty()) {
                return null;
            }
            return AtomUi.text().layout(
                    new TextRequest(this.value, AtomUi.regular(), INPUT_SIZE, 0xFFFFFFFF, 1, false),
                    new Constraints(Constraints.Axis.atMost(8000.0F), Constraints.Axis.undefined()));
        }

        private float caretX(TextLayout laid) {
            if (laid == null) {
                return 0.0F;
            }
            float x = 0.0F;
            for (TextLayout.Line line : laid.lines()) {
                for (TextLayout.Caret caret : line.carets()) {
                    x = caret.x();
                    if (caret.offset() >= this.cursor) {
                        return caret.x();
                    }
                }
            }
            return x;
        }

        private float adjustScroll(float caret, float visible, TextLayout laid) {
            float view = this.scroll;
            if (caret < view) {
                view = caret;
            }
            if (caret > view + visible - 2.0F) {
                view = caret - visible + 2.0F;
            }
            float width = laid == null ? 0.0F : laid.size().width();
            float max = Math.max(0.0F, width - visible);
            if (view > max) {
                view = max;
            }
            if (view < 0.0F) {
                view = 0.0F;
            }
            return view;
        }
    }

    private static final class Slot {
        private final NodeId id;
        private final int fieldIndex;
        private final TextField field;
        private final Runnable action;
        private final Rarity rarity;
        private final String choiceField;
        private final String choiceValue;

        private Slot(NodeId id, int fieldIndex, TextField field, Runnable action, Rarity rarity, String choiceField,
                String choiceValue) {
            this.id = id;
            this.fieldIndex = fieldIndex;
            this.field = field;
            this.action = action;
            this.rarity = rarity;
            this.choiceField = choiceField;
            this.choiceValue = choiceValue;
        }

        private static Slot text(NodeId id, int fieldIndex, TextField field) {
            return new Slot(id, fieldIndex, field, null, null, null, null);
        }

        private static Slot press(NodeId id, int fieldIndex, Runnable action) {
            return new Slot(id, fieldIndex, null, action, null, null, null);
        }

        private static Slot rarity(NodeId id, int fieldIndex, Runnable action, Rarity rarity) {
            return new Slot(id, fieldIndex, null, action, rarity, null, null);
        }

        private static Slot choice(NodeId id, int fieldIndex, Runnable action, String choiceField, String choiceValue) {
            return new Slot(id, fieldIndex, null, action, null, choiceField, choiceValue);
        }
    }

    private record Glyph(String text, boolean bold, float size, int color) {
    }
}
