package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.ClientHomeCurrencyState;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.PriceDisplay;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.PriceSpecEditor;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop.data.PriceSpec;
import cn.autoforged.module_shop.data.PriceSpec.CurrencyMode;
import cn.autoforged.module_shop.data.Rarity;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

/** 通用数值/文本/配价输入界面：管理员配价、商店/收购/摊位改价、数量与通道确认都走这里。 */
public class ShopInputScreen extends SkiaDialog {
    /** 品质字段不用输入框，改成四个品质按钮（点一下就选中，按钮上方显示「当前品质：X」）。 */
    private static final String RARITY_FIELD = "rarity";
    private final CompoundTag request;
    private final String action;
    private final List<String> fieldNames = new ArrayList<>();
    private final List<EditBox> boxes = new ArrayList<>();
    /** 与 {@link #boxes} 一一对应的字段名（品质、选项与可视化配价器不走 boxes）。 */
    private final List<String> boxFields = new ArrayList<>();
    /** 每个品质按钮的矩形（x, y, w, h, rarityIndex），渲染时给选中项描边。 */
    private final List<int[]> rarityRects = new ArrayList<>();
    /** 当前选中的品质 id。 */
    private String rarityValue = Rarity.COMMON.id();
    /** 选项字段（choices_<field>） */
    private final Map<String, String> choiceValues = new LinkedHashMap<>();
    private final Map<String, List<int[]>> choiceRects = new LinkedHashMap<>();
    /** Skija 模式：挂载 PriceSpecEditor 的字段（如 price / amount / base / makeupCost）。 */
    private final Map<String, PriceSpecEditor> priceEditors = new LinkedHashMap<>();
    private final Map<String, PriceSpec> savedEditorSpecs = new LinkedHashMap<>();
    private final Map<String, float[]> editorBounds = new LinkedHashMap<>();
    private final ScrollController bodyScroll = new ScrollController();
    private boolean bodyScrolling;
    private float bodyTop;
    private float bodyBottom;

    /** 玩家购买/出售确认态携带的物品定价规格（EITHER / PHYS_ONLY / BOTH / STAR_ONLY）。 */
    private final boolean hasItemPriceSpec;
    private final PriceSpec itemPriceSpec;
    private final boolean sellAction;
    private String payChannel = "star";

    private final Component title;
    private final String info;
    private final int panelW = 240;
    private int panelH;
    private int left;
    private int top;

    public ShopInputScreen(CompoundTag request) {
        super(Component.translatable("gui.module_shop.input.title"));
        this.request = request.copy();
        this.action = request.getString("action");
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

        boolean priceConfig = hasAnyPriceEditorField();
        this.sellAction = "sell_buyback".equals(this.action);
        this.hasItemPriceSpec = !priceConfig && request.contains("priceSpec", Tag.TAG_COMPOUND);
        if (this.hasItemPriceSpec) {
            this.itemPriceSpec = PriceSpec.fromTag(request.getCompound("priceSpec"), request.getLong("unit_price"));
            String presetChannel = request.getString("pay_channel");
            this.payChannel = resolveInitialChannel(this.itemPriceSpec, presetChannel);
        } else {
            this.itemPriceSpec = PriceSpec.ZERO;
        }
    }

    private static String resolveInitialChannel(PriceSpec spec, @Nullable String presetChannel) {
        if (spec == null) {
            return "star";
        }
        return switch (spec.mode) {
            case STAR_ONLY -> "star";
            case PHYS_ONLY -> "phys";
            case BOTH -> "both";
            case EITHER -> ("star".equals(presetChannel) || "phys".equals(presetChannel))
                    ? presetChannel
                    : PriceDisplay.resolveBuyChannel(spec, 1);
        };
    }

    private boolean hasAnyPriceEditorField() {
        for (String field : this.fieldNames) {
            if (priceScopeForField(field) != null) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private PriceSpecEditor.Scope priceScopeForField(String field) {
        if ("stall_apply_price".equals(this.action) || "stall_set_price_new".equals(this.action)) {
            return "price".equals(field) ? PriceSpecEditor.Scope.PLAYER : null;
        }
        if ("admin_apply_price".equals(this.action)
                || "admin_batch_apply".equals(this.action)
                || "admin_mall_import".equals(this.action)
                || "admin_set_price_new".equals(this.action)) {
            return "price".equals(field) ? PriceSpecEditor.Scope.OP : null;
        }
        if ("admin_buyback_bonus_set".equals(this.action)) {
            return "amount".equals(field) ? PriceSpecEditor.Scope.OP : null;
        }
        if ("admin_signin_set".equals(this.action)) {
            return ("base".equals(field) || "makeupCost".equals(field)) ? PriceSpecEditor.Scope.OP : null;
        }
        if (this.request.getBoolean("supports_price_spec") && ("price".equals(field) || "amount".equals(field))) {
            return PriceSpecEditor.Scope.OP;
        }
        return null;
    }

    private boolean allowZeroForField(String field) {
        return "admin_buyback_bonus_set".equals(this.action)
                || "admin_signin_set".equals(this.action)
                || this.request.getBoolean("allow_zero_" + field)
                || this.request.getBoolean("allow_zero");
    }

    private String specKeyForField(String field) {
        if ("admin_signin_set".equals(this.action)) {
            if ("base".equals(field)) {
                return "baseSpec";
            }
            if ("makeupCost".equals(field)) {
                return "makeupSpec";
            }
        }
        return "priceSpec";
    }

    @Nullable
    private PriceSpec initialSpecForField(String field) {
        PriceSpec saved = this.savedEditorSpecs.get(field);
        if (saved != null) {
            return saved;
        }
        String specKey = specKeyForField(field);
        if (this.request.contains(specKey, Tag.TAG_COMPOUND)) {
            long fallback = tryParseLong(this.request.getString("default_" + field), 0L);
            return PriceSpec.fromTag(this.request.getCompound(specKey), fallback);
        }
        String preset = this.request.getString("default_" + field).trim();
        if (!preset.isEmpty()) {
            PriceSpec parsed = PriceSpec.parseRuleText(preset);
            if (parsed != null) {
                return parsed;
            }
        }
        if ("admin_signin_set".equals(this.action)) {
            if ("base".equals(field)) {
                return ClientShopData.signInBaseSpec;
            }
            if ("makeupCost".equals(field)) {
                return ClientShopData.signInMakeupSpec;
            }
        }
        if ("admin_buyback_bonus_set".equals(this.action)) {
            return ClientShopData.buybackBonusSpec;
        }
        return null;
    }

    private static long tryParseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(0L, Long.parseLong(raw.trim()));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
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

    /** 出货箱单物品每日上限（可选非负整数：留空或 0 代表无限）。 */
    private static boolean isDailyMaxField(String field) {
        return "daily_max".equals(field)
                || "daily_deposit_limit".equals(field)
                || "daily_withdraw_limit".equals(field);
    }

    private static boolean isShippingLevelLimitField(String field) {
        if (field == null || !field.startsWith("limit") || field.length() <= 5) {
            return false;
        }
        for (int i = 5; i < field.length(); i++) {
            if (!Character.isDigit(field.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static Component fieldLabel(String field) {
        if (isShippingLevelLimitField(field)) {
            return Component.translatableWithFallback("gui.module_shop.field." + field,
                    "等级 %s 每日额度", field.substring(5));
        }
        return Component.translatable("gui.module_shop.field." + field);
    }

    private boolean dailyMaxBad() {
        for (int i = 0; i < this.boxFields.size(); i++) {
            String f = this.boxFields.get(i);
            if (isDailyMaxField(f)) {
                String v = this.boxes.get(i).getValue().trim();
                if (!v.isEmpty() && !v.matches("\\d{1,9}")) {
                    return true;
                }
            } else if (isShippingLevelLimitField(f)) {
                String v = this.boxes.get(i).getValue().trim();
                if (!v.isEmpty() && !v.matches("\\d{1,14}")) {
                    return true;
                }
            }
        }
        return false;
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
    private static boolean validTime(String raw) {
        if (raw == null || raw.length() != 5 || raw.charAt(2) != ':') {
            return false;
        }
        try {
            int hh = Integer.parseInt(raw.substring(0, 2));
            int mm = Integer.parseInt(raw.substring(3, 5));
            return hh >= 0 && hh <= 23 && mm >= 0 && mm <= 59;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** 当前是否填了一个不合法的时间（用于红字提示和禁用确认）。 */
    private boolean timeBad() {
        for (int i = 0; i < this.boxFields.size(); i++) {
            if (isTimeField(this.boxFields.get(i)) && !validTime(this.boxes.get(i).getValue().trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDualChannelSelector() {
        return this.hasItemPriceSpec && this.itemPriceSpec.mode == CurrencyMode.EITHER;
    }

    private PriceSpec activeItemTotalSpec(long qty) {
        if (!this.hasItemPriceSpec || qty <= 0L) {
            return PriceSpec.ZERO;
        }
        int clamped = (int) Math.min(Integer.MAX_VALUE, qty);
        PriceSpec total = this.itemPriceSpec.scale(clamped);
        if (this.itemPriceSpec.mode == CurrencyMode.EITHER) {
            return "phys".equals(this.payChannel)
                    ? PriceSpec.phys(total.physTierKey, total.physCount)
                    : PriceSpec.star(total.starPrice);
        }
        return total;
    }

    private int infoOffset() {
        return this.info.isEmpty() ? 0 : 14;
    }

    private boolean showBalance() {
        return this.request.getBoolean("show_balance")
                || ("trade_set_money".equals(this.action) && this.request.contains("balance"));
    }

    private long enteredQuantity() {
        int idx = this.boxFields.indexOf("quantity");
        EditBox target = idx >= 0 ? this.boxes.get(idx) : (this.boxes.isEmpty() ? null : this.boxes.get(0));
        if (target == null) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(target.getValue().trim()));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * 输入的数量是否超出余额能承担的范围（支持旧单星币与双独立并行账本）。
     */
    private boolean overBalance() {
        if (this.hasItemPriceSpec) {
            if (this.sellAction) {
                return false;
            }
            long qty = enteredQuantity();
            if (qty <= 0L) {
                return false;
            }
            int clamped = (int) Math.min(Integer.MAX_VALUE, qty);
            return !PriceDisplay.canAffordChannel(this.itemPriceSpec, clamped, this.payChannel);
        }
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

    private boolean isTradeMoney() {
        return "trade_set_money".equals(this.action);
    }

    private void returnToTradeIfNeeded() {
        if (isTradeMoney() && !ClientShopData.tradeSelfName.isEmpty()) {
            Nav.open(new TradeScreen());
        }
    }

    @Override
    protected void init() {
        List<String> kept = new ArrayList<>();
        for (EditBox box : this.boxes) {
            kept.add(box.getValue());
        }
        for (Map.Entry<String, PriceSpecEditor> e : this.priceEditors.entrySet()) {
            PriceSpec current = e.getValue().buildSpec();
            if (current != null) {
                this.savedEditorSpecs.put(e.getKey(), current);
            }
        }
        if (skiaInit()) {
            for (int i = 0; i < this.boxes.size() && i < kept.size(); i++) {
                if (!kept.get(i).isEmpty()) {
                    this.boxes.get(i).setValue(kept.get(i));
                }
            }
            return;
        }

        List<String> previous = new ArrayList<>(kept);
        this.boxes.clear();
        this.boxFields.clear();
        this.rarityRects.clear();
        this.choiceValues.clear();
        this.choiceRects.clear();
        this.priceEditors.clear();
        this.editorBounds.clear();

        int dualExtra = hasDualChannelSelector() ? 32 : 0;
        int footerExtra = (this.hasItemPriceSpec || this.request.getLong("unit_price") > 0L) ? 16 : 0;
        this.panelH = 68 + this.fieldNames.size() * 33 + headerOffset() + dualExtra + footerExtra;
        this.left = (this.width - this.panelW) / 2;
        this.top = (this.height - this.panelH) / 2;

        for (int i = 0; i < this.fieldNames.size(); i++) {
            String field = this.fieldNames.get(i);
            if (isRarityField(field)) {
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
                    fieldLabel(field));
            box.setMaxLength(isDailyMaxField(field) ? 9 : (isShippingLevelLimitField(field) ? 14 : 64));
            PriceSpec initialSpec = priceScopeForField(field) != null ? initialSpecForField(field) : null;
            String preset = initialSpec != null ? initialSpec.toRuleText() : this.request.getString("default_" + field);
            if (isDailyMaxField(field)) {
                if ("0".equals(preset.trim())) {
                    preset = "";
                }
                box.setFilter(text -> text.isEmpty() || text.matches("\\d{0,9}"));
            } else if (isShippingLevelLimitField(field)) {
                box.setFilter(text -> text.isEmpty() || text.matches("\\d{0,14}"));
            }
            if (!preset.isEmpty()) {
                box.setValue(preset);
            }
            int boxIdx = this.boxes.size();
            if (boxIdx < previous.size() && !previous.get(boxIdx).isEmpty()) {
                box.setValue(previous.get(boxIdx));
            }
            if (isTimeField(field)) {
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

        if (hasDualChannelSelector()) {
            int chY = this.top + 38 + headerOffset() + this.fieldNames.size() * 33 + 2;
            int half = (this.panelW - 44) / 2;
            this.addRenderableWidget(new ShopButton(this.left + 20, chY, half, 18,
                    Component.literal(ClientHomeCurrencyState.tr("gui.module_shop.input.channel_star", "星币账本")),
                    b -> this.payChannel = "star"));
            this.addRenderableWidget(new ShopButton(this.left + 24 + half, chY, half, 18,
                    Component.literal(ClientHomeCurrencyState.tr("gui.module_shop.input.channel_phys", "实体货币账本")),
                    b -> this.payChannel = "phys"));
        }

        int buttonY = this.top + this.panelH - 30;
        boolean confirmRight = this.request.getBoolean("confirm_right");
        int leftX = this.left + 24;
        int rightX = this.left + this.panelW - 114;
        this.addRenderableWidget(new ShopButton(leftX, buttonY, 90, 20,
                Component.translatable(confirmRight ? "gui.module_shop.cancel" : "gui.module_shop.confirm"),
                b -> {
                    if (confirmRight) {
                        onClose();
                    } else {
                        confirm();
                    }
                }));
        this.addRenderableWidget(new ShopButton(rightX, buttonY, 90, 20,
                Component.translatable(confirmRight ? "gui.module_shop.confirm" : "gui.module_shop.cancel"),
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

    @Nullable
    private String editorValidationError() {
        for (PriceSpecEditor ed : this.priceEditors.values()) {
            String err = ed.validateError();
            if (err != null && !err.isBlank()) {
                return err;
            }
        }
        return null;
    }

    private void confirm() {
        if (timeBad() || dailyMaxBad()) {
            return;
        }
        if (editorValidationError() != null) {
            return;
        }
        CompoundTag tag = this.request.copy();
        for (int i = 0; i < this.boxes.size(); i++) {
            String field = this.boxFields.get(i);
            String val = this.boxes.get(i).getValue().trim();
            tag.putString(field, val);
            if (!skia() && priceScopeForField(field) != null) {
                PriceSpec parsed = PriceSpec.parseRuleText(val);
                if (parsed != null && (parsed.isValid() || (allowZeroForField(field) && parsed.isZero()))) {
                    tag.put(specKeyForField(field), parsed.toTag());
                } else if (parsed == null) {
                    return;
                }
            }
        }
        for (Map.Entry<String, PriceSpecEditor> e : this.priceEditors.entrySet()) {
            String field = e.getKey();
            PriceSpec spec = e.getValue().buildSpec();
            if (spec == null) {
                return;
            }
            tag.put(specKeyForField(field), spec.toTag());
            tag.putString(field, spec.toRuleText());
        }
        if (this.fieldNames.contains(RARITY_FIELD)) {
            tag.putString(RARITY_FIELD, this.rarityValue);
        }
        for (Map.Entry<String, String> entry : this.choiceValues.entrySet()) {
            tag.putString(entry.getKey(), entry.getValue());
        }
        if (this.hasItemPriceSpec) {
            tag.putString("pay_channel", this.payChannel);
        }
        ServerboundShopActionPayload.send(tag);
        Nav.back();
        returnToTradeIfNeeded();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        if (skia()) {
            return;
        }
        Bricks.panel(g, this.left, this.top, this.panelW, this.panelH);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (skiaRender(g, mouseX, mouseY, partialTick)) {
            return;
        }
        super.render(g, mouseX, mouseY, partialTick);
        int maxTextW = this.panelW - 20;
        IOStyle.centerAt(g, this.font, truncate(this.title, maxTextW), this.left + this.panelW / 2, this.top + 12,
                IOStyle.ORANGE);
        if (!this.info.isEmpty()) {
            IOStyle.centerAt(g, this.font, truncate(Component.literal(this.info), maxTextW),
                    this.left + this.panelW / 2, this.top + 27, IOStyle.ORANGE);
        }
        if (showBalance()) {
            long balance = this.request.getLong("balance");
            int balanceY = this.top + 27 + infoOffset();
            Component balanceText = Component.translatable("gui.module_shop.trade.balance",
                    ClientShopData.money(balance));
            if (overBalance()) {
                g.drawString(this.font, balanceText, this.left + 20, balanceY, IOStyle.ORANGE, false);
                Component warn = Component.translatable("gui.module_shop.input.not_enough");
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
                Component label = Component.translatable("gui.module_shop.field." + RARITY_FIELD);
                g.drawString(this.font, label, this.left + 20, labelY, IOStyle.TEXT_2, true);
                Rarity current = Rarity.byId(this.rarityValue);
                g.drawString(this.font,
                        Component.translatable("gui.module_shop.quality_current",
                                Component.translatable(current.translationKey())),
                        this.left + 20 + this.font.width(label) + 10, labelY, current.color(), true);
                for (int[] rect : this.rarityRects) {
                    Rarity rarity = Rarity.values()[rect[4]];
                    boolean selected = rarity.id().equals(this.rarityValue);
                    IOStyle.ring(g, rect[0] - 1, rect[1] - 1, rect[2] + 2, rect[3] + 2, 0,
                            selected ? IOStyle.rarity(rarity) : IOStyle.BORDER_DIM);
                }
                continue;
            }
            List<int[]> rects = this.choiceRects.get(field);
            if (rects != null) {
                Component label = Component.translatable("gui.module_shop.field." + field);
                g.drawString(this.font, label, this.left + 20, labelY, IOStyle.TEXT_2, true);
                String value = this.choiceValues.getOrDefault(field, "");
                g.drawString(this.font,
                        Component.translatable("gui.module_shop.choice_current",
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
            Component fieldLbl = fieldLabel(field);
            g.drawString(this.font, fieldLbl, this.left + 20, labelY, IOStyle.TEXT_2, true);
            if (isDailyMaxField(field)) {
                Component hint = Component.translatable("gui.module_shop.field.daily_max.hint");
                g.drawString(this.font, hint, this.left + this.panelW - 20 - this.font.width(hint),
                        labelY, IOStyle.TEXT_3, false);
            }
        }
        if (hasDualChannelSelector()) {
            int chY = this.top + 38 + headerOffset() + this.fieldNames.size() * 33 + 2;
            int half = (this.panelW - 44) / 2;
            IOStyle.ring(g, this.left + 19, chY - 1, half + 2, 20, 0,
                    "star".equals(this.payChannel) ? IOStyle.ORANGE : IOStyle.BORDER_DIM);
            IOStyle.ring(g, this.left + 23 + half, chY - 1, half + 2, 20, 0,
                    "phys".equals(this.payChannel) ? IOStyle.BLUE : IOStyle.BORDER_DIM);
        }
        if (this.hasItemPriceSpec) {
            long qty = enteredQuantity();
            PriceSpec preview = activeItemTotalSpec(qty);
            boolean lack = !this.sellAction && qty > 0L
                    && !PriceDisplay.canAffordChannel(this.itemPriceSpec, (int) Math.min(Integer.MAX_VALUE, qty),
                            this.payChannel);
            String label = this.sellAction
                    ? ClientHomeCurrencyState.tr("gui.module_shop.input.sell_total_preview", "预计入账：%s",
                            PriceDisplay.formatPlain(preview))
                    : ClientHomeCurrencyState.tr("gui.module_shop.input.buy_total_preview", "总计应付：%s%s",
                            PriceDisplay.formatPlain(preview),
                            lack ? "（" + Component.translatable("gui.module_shop.input.not_enough").getString() + "）" : "");
            IOStyle.centerAt(g, this.font, truncate(Component.literal(label), maxTextW),
                    this.left + this.panelW / 2, this.top + this.panelH - 46,
                    lack ? IOStyle.RED : IOStyle.ORANGE);
        } else {
            long unitPrice = this.request.getLong("unit_price");
            if (unitPrice > 0L && !this.boxes.isEmpty()) {
                long quantity = enteredQuantity();
                long total = quantity > Long.MAX_VALUE / unitPrice ? Long.MAX_VALUE : quantity * unitPrice;
                long balanceAfter = this.request.getLong("balance") - total;
                String totalKey = this.request.contains("total_key")
                        ? this.request.getString("total_key") : "gui.module_shop.buy_total";
                IOStyle.centerAt(g, this.font, Component.translatable(totalKey,
                                ClientShopData.money(total),
                                ClientShopData.money(balanceAfter)),
                        this.left + this.panelW / 2, this.top + this.panelH - 46,
                        overBalance() ? IOStyle.RED : IOStyle.ORANGE);
            }
        }
        if (timeBad()) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.input.time_bad"),
                    this.left + this.panelW / 2, this.top + this.panelH - 46, IOStyle.RED);
        }
    }

    // ------------------------------------------------------------------ Skija

    private static final float SK_W = 250.0F;
    private static final float SK_WIDE_W = 308.0F;
    private static final float SK_ROW = 38.0F;
    private final Map<String, SkiaTextField> skFields = new LinkedHashMap<>();

    @Override
    protected void initSkia() {
        this.boxes.clear();
        this.boxFields.clear();
        this.choiceValues.clear();
        this.skFields.clear();
        this.priceEditors.clear();
        this.editorBounds.clear();

        SkiaTextField first = null;
        for (String field : this.fieldNames) {
            if (isRarityField(field)) {
                continue;
            }
            ListTag choices = choicesOf(field);
            if (!choices.isEmpty()) {
                String preset = this.request.getString("default_" + field).trim();
                String current = choices.getString(0);
                for (int k = 0; k < choices.size(); k++) {
                    if (choices.getString(k).equals(preset)) {
                        current = preset;
                    }
                }
                this.choiceValues.put(field, current);
                continue;
            }
            PriceSpecEditor.Scope scope = priceScopeForField(field);
            if (scope != null) {
                PriceSpecEditor ed = new PriceSpecEditor(this, this.font, scope)
                        .allowZero(allowZeroForField(field))
                        .items(this.items);
                PriceSpec initial = initialSpecForField(field);
                if (initial != null) {
                    ed.setInitial(initial);
                }
                ed.registerInputs(this::addWidget);
                this.priceEditors.put(field, ed);
                if (first == null) {
                    first = ed.mode() == CurrencyMode.PHYS_ONLY ? ed.physField() : ed.starField();
                }
                continue;
            }
            int maxLen = isTimeField(field) ? 5 : (isDailyMaxField(field) ? 9 : (isShippingLevelLimitField(field) ? 14 : 32));
            String ph = isTimeField(field) ? "HH:mm"
                    : (isDailyMaxField(field)
                            ? Component.translatable("gui.module_shop.field.daily_max.hint").getString()
                            : "");
            SkiaTextField tf = field(maxLen, ph);
            EditBox box = tf.editBox();
            String preset = this.request.getString("default_" + field);
            if (isDailyMaxField(field)) {
                if ("0".equals(preset.trim())) {
                    preset = "";
                }
                box.setFilter(text -> text.isEmpty() || text.matches("\\d{0,9}"));
            } else if (isShippingLevelLimitField(field)) {
                box.setFilter(text -> text.isEmpty() || text.matches("\\d{0,14}"));
            }
            if (!preset.isEmpty()) {
                box.setValue(preset);
            }
            if (isTimeField(field)) {
                box.setFilter(text -> text.isEmpty() || text.matches("[0-9:]{0,5}"));
                box.setResponder(text -> {
                    String formatted = autoColon(text);
                    if (!formatted.equals(text)) {
                        box.setValue(formatted);
                    }
                });
            }
            this.boxes.add(box);
            this.boxFields.add(field);
            this.skFields.put(field, tf);
            if (first == null) {
                first = tf;
            }
        }
        if (first != null && first.editBox().isVisible()) {
            first.focus();
        }
    }

    private float dialogWidth() {
        return this.priceEditors.isEmpty() ? SK_W : SK_WIDE_W;
    }

    private float fieldHeight(String field, float innerW) {
        PriceSpecEditor ed = this.priceEditors.get(field);
        if (ed != null) {
            ed.bounds(0, 0, innerW);
            return 12.0F + ed.preferredHeight() + 8.0F;
        }
        return SK_ROW;
    }

    private boolean hasFooterLine() {
        return this.hasItemPriceSpec
                || (this.request.getLong("unit_price") > 0L && !this.boxes.isEmpty())
                || timeBad()
                || editorValidationError() != null;
    }

    @Override
    protected void paint(Canvas c) {
        float w = dialogWidth();
        float innerW = w - PAD * 2;
        float headerExtra = (this.info.isEmpty() ? 0.0F : 13.0F) + (showBalance() ? 13.0F : 0.0F);
        float fieldsTotalH = 0.0F;
        for (String field : this.fieldNames) {
            fieldsTotalH += fieldHeight(field, innerW);
        }
        float channelSelectorH = hasDualChannelSelector() ? 32.0F : 0.0F;
        float footerH = (hasFooterLine() ? 16.0F : 0.0F) + 4.0F + 20.0F + PAD;
        float chromeTop = HEADER_H + 8.0F + headerExtra;
        float wantedH = chromeTop + fieldsTotalH + channelSelectorH + footerH;
        float maxH = Math.max(180.0F, this.height - 16.0F);
        float h = Math.min(wantedH, maxH);

        float x = Math.round((this.width - w) / 2.0F);
        float y = Math.round((this.height - h) / 2.0F);
        float innerX = x + PAD;
        panel(c, x, y, w, h, this.title.getString());

        float cy = y + HEADER_H + 8.0F;
        if (!this.info.isEmpty()) {
            center(c, this.info, innerX, cy + 5.0F, innerW, IOStyle.TEXT_2);
            cy += 13.0F;
        }
        if (showBalance()) {
            String bal = Component.translatable("gui.module_shop.trade.balance",
                    ClientShopData.money(this.request.getLong("balance"))).getString();
            if (overBalance()) {
                text(c, bal, innerX, cy + 5.0F, innerW * 0.6F, IOStyle.ORANGE);
                right(c, Component.translatable("gui.module_shop.input.not_enough").getString(), innerX, cy + 5.0F,
                        innerW, IOStyle.RED);
            } else {
                center(c, bal, innerX, cy + 5.0F, innerW, IOStyle.ORANGE);
            }
            cy += 13.0F;
        }

        float viewH = h - (cy - y) - channelSelectorH - footerH;
        this.bodyScrolling = fieldsTotalH > viewH + 0.5F;
        this.bodyTop = cy;
        this.bodyBottom = cy + viewH;
        float contentW = this.bodyScrolling ? innerW - 8.0F : innerW;
        float drawY = this.bodyScrolling
                ? beginList(c, this.bodyScroll, innerX, cy, innerW, viewH, fieldsTotalH)
                : cy;

        this.editorBounds.clear();
        Component pendingEditorTip = null;

        for (String field : this.fieldNames) {
            float fh = fieldHeight(field, contentW);
            boolean rowVisible = !this.bodyScrolling
                    || (drawY >= this.bodyTop - 1.0F && drawY + fh <= this.bodyBottom + 1.0F);
            String label = fieldLabel(field).getString();
            float labelW = small(c, label, innerX, drawY + 5.0F, contentW, IOStyle.TEXT_2);
            float ctrlY = drawY + 12.0F;

            PriceSpecEditor ed = this.priceEditors.get(field);
            if (ed != null) {
                ed.bounds(innerX, ctrlY, contentW);
                ed.visible(rowVisible);
                ed.render(c, this.items, this.mouseX, this.mouseY, this.dtMs);
                if (rowVisible) {
                    this.editorBounds.put(field, new float[]{innerX, ctrlY, contentW, ed.preferredHeight()});
                    Component edTip = ed.hoveredTooltip();
                    if (edTip != null) {
                        pendingEditorTip = edTip;
                    }
                }
            } else if (isRarityField(field)) {
                Rarity current = Rarity.byId(this.rarityValue);
                small(c, Component.translatable("gui.module_shop.quality_current",
                                Component.translatable(current.translationKey())).getString(),
                        innerX + labelW + 8.0F, drawY + 5.0F, contentW - labelW - 8.0F, IOStyle.rarity(current));
                Rarity[] all = Rarity.values();
                float gap = 4.0F;
                float bw = (contentW - gap * (all.length - 1)) / all.length;
                for (int k = 0; k < all.length; k++) {
                    Rarity r = all[k];
                    chip(c, "rarity:" + r.id(), Component.translatable(r.translationKey()).getString(),
                            innerX + k * (bw + gap), ctrlY, bw, 18.0F, r.id().equals(this.rarityValue),
                            IOStyle.rarity(r), () -> this.rarityValue = r.id());
                }
            } else if (this.choiceValues.containsKey(field)) {
                ListTag choices = choicesOf(field);
                String value = this.choiceValues.get(field);
                int count = Math.min(4, choices.size());
                float gap = 4.0F;
                float bw = (contentW - gap * (count - 1)) / Math.max(1, count);
                for (int k = 0; k < count; k++) {
                    String v = choices.getString(k);
                    chip(c, "choice:" + field + ":" + v, choiceLabel(field, v).getString(), innerX + k * (bw + gap),
                            ctrlY, bw, 18.0F, v.equals(value), IOStyle.BLUE, () -> this.choiceValues.put(field, v));
                }
            } else {
                if (isDailyMaxField(field)) {
                    String hint = Component.translatable("gui.module_shop.field.daily_max.hint").getString();
                    right(c, hint, innerX + labelW + 4.0F, drawY + 5.0F,
                            Math.max(20.0F, contentW - labelW - 4.0F), IOStyle.TEXT_3);
                }
                SkiaTextField tf = this.skFields.get(field);
                if (tf != null) {
                    tf.visible(rowVisible);
                    if (rowVisible) {
                        tf.bounds(innerX, ctrlY, contentW, 18.0F);
                    }
                }
            }
            drawY += fh;
        }
        if (this.bodyScrolling) {
            endList(c);
        }
        if (pendingEditorTip != null) {
            tip(pendingEditorTip);
        }

        cy += viewH;

        if (hasDualChannelSelector()) {
            String chLbl = ClientHomeCurrencyState.tr("gui.module_shop.input.pay_channel_lbl",
                    this.sellAction ? "收款账本" : "支付账本");
            small(c, chLbl, innerX, cy + 4.0F, innerW, IOStyle.TEXT_2);
            float gap = 6.0F;
            float chipW = (innerW - gap) * 0.5F;
            long qty = Math.max(1L, enteredQuantity());
            int clamped = (int) Math.min(Integer.MAX_VALUE, qty);
            PriceSpec starTotal = PriceSpec.star(this.itemPriceSpec.scale(clamped).starPrice);
            PriceSpec physTotal = PriceSpec.phys(this.itemPriceSpec.physTierKey,
                    this.itemPriceSpec.scale(clamped).physCount);
            String starTag = ClientHomeCurrencyState.tr("gui.module_shop.input.channel_star_val", "星币 (%s)",
                    PriceDisplay.formatCompact(starTotal));
            String physTag = ClientHomeCurrencyState.tr("gui.module_shop.input.channel_phys_val", "实体 (%s)",
                    PriceDisplay.formatCompact(physTotal));
            chip(c, "pay_star", starTag, innerX, cy + 11.0F, chipW, 18.0F, "star".equals(this.payChannel),
                    IOStyle.ORANGE, () -> this.payChannel = "star");
            chip(c, "pay_phys", physTag, innerX + chipW + gap, cy + 11.0F, chipW, 18.0F, "phys".equals(this.payChannel),
                    IOStyle.BLUE, () -> this.payChannel = "phys");
            cy += 32.0F;
        }

        String edErr = editorValidationError();
        if (timeBad()) {
            center(c, Component.translatable("gui.module_shop.input.time_bad").getString(), innerX, cy + 6.0F, innerW,
                    IOStyle.RED);
            cy += 16.0F;
        } else if (edErr != null) {
            center(c, edErr, innerX, cy + 6.0F, innerW, IOStyle.RED);
            cy += 16.0F;
        } else if (this.hasItemPriceSpec) {
            long qty = enteredQuantity();
            int clamped = (int) Math.min(Integer.MAX_VALUE, qty);
            PriceSpec preview = activeItemTotalSpec(qty);
            boolean lack = !this.sellAction && qty > 0L
                    && !PriceDisplay.canAffordChannel(this.itemPriceSpec, clamped, this.payChannel);
            String prefix = this.sellAction
                    ? ClientHomeCurrencyState.tr("gui.module_shop.input.prefix_earn", "预计入账 ")
                    : ClientHomeCurrencyState.tr("gui.module_shop.input.prefix_cost", "总计应付 ");
            if (lack) {
                float warnW = Math.min(82.0F, innerW * 0.36F);
                PriceDisplay.drawCell(c, this.items, preview, innerX, cy + 6.0F, innerW - warnW - 4.0F,
                        Text.Align.LEFT, IOStyle.RED, false, prefix);
                right(c, Component.translatable("gui.module_shop.input.not_enough").getString(), innerX, cy + 6.0F,
                        innerW, IOStyle.RED);
            } else {
                PriceDisplay.drawCell(c, this.items, preview, innerX, cy + 6.0F, innerW,
                        Text.Align.CENTER, this.sellAction ? IOStyle.GREEN : IOStyle.ORANGE, qty <= 0L, prefix);
            }
            if (over(innerX, cy, innerW, 14.0F) && qty > 0L) {
                tip(Component.literal(prefix + PriceDisplay.formatPlain(preview)));
            }
            cy += 16.0F;
        } else if (hasFooterLine()) {
            long unit = this.request.getLong("unit_price");
            long qty = enteredQuantity();
            long total = unit > 0L && qty > Long.MAX_VALUE / unit ? Long.MAX_VALUE : qty * unit;
            long after = this.request.getLong("balance") - total;
            String key = this.request.contains("total_key") ? this.request.getString("total_key")
                    : "gui.module_shop.buy_total";
            center(c, Component.translatable(key, ClientShopData.money(total),
                            ClientShopData.money(after)).getString(), innerX, cy + 6.0F,
                    innerW, overBalance() ? IOStyle.RED : IOStyle.ORANGE);
            cy += 16.0F;
        }

        boolean confirmRight = this.request.getBoolean("confirm_right");
        float bw = Math.min(96.0F, (innerW - 24.0F) * 0.5F);
        float by = y + h - PAD - 20.0F;
        float leftX = x + PAD + 8.0F;
        float rightX = x + w - PAD - 8.0F - bw;
        String confirm = Component.translatable("gui.module_shop.confirm").getString();
        String cancel = Component.translatable("gui.module_shop.cancel").getString();
        boolean canConfirm = !timeBad() && !dailyMaxBad() && edErr == null;
        button(c, "confirm", confirm, confirmRight ? rightX : leftX, by, bw, 20.0F,
                cn.autoforged.module_shop.client.ui.widget.Button.Kind.ACCENT, canConfirm, this::confirm);
        button(c, "cancel", cancel, confirmRight ? leftX : rightX, by, bw, 20.0F,
                cn.autoforged.module_shop.client.ui.widget.Button.Kind.NORMAL, true, this::onClose);
    }

    private double toDialogX(double guiX) {
        Minecraft mc = Minecraft.getInstance();
        int winW = Math.max(1, mc.getWindow().getGuiScaledWidth());
        return guiX * this.width / winW;
    }

    private double toDialogY(double guiY) {
        Minecraft mc = Minecraft.getInstance();
        int winH = Math.max(1, mc.getWindow().getGuiScaledHeight());
        return guiY * this.height / winH;
    }

    private boolean insideEditorBounds(double mx, double my) {
        if (this.bodyScrolling && (my < this.bodyTop || my > this.bodyBottom)) {
            return false;
        }
        for (float[] b : this.editorBounds.values()) {
            if (mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3]) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (skia() && !this.priceEditors.isEmpty()) {
            double sx = toDialogX(mx);
            double sy = toDialogY(my);
            if (!insideEditorBounds(sx, sy)) {
                for (PriceSpecEditor ed : this.priceEditors.values()) {
                    ed.blur();
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    protected boolean onClick(double mx, double my, int button) {
        if (this.priceEditors.isEmpty()) {
            return false;
        }
        if (this.bodyScrolling && (my < this.bodyTop || my > this.bodyBottom)) {
            for (PriceSpecEditor ed : this.priceEditors.values()) {
                ed.blur();
            }
            return false;
        }
        boolean handled = false;
        for (PriceSpecEditor ed : this.priceEditors.values()) {
            if (!handled && ed.mouseClicked(mx, my, button)) {
                handled = true;
            } else {
                ed.blur();
            }
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (skia() && !this.priceEditors.isEmpty()) {
            double sx = toDialogX(mx);
            double sy = toDialogY(my);
            double sdx = toDialogX(dx);
            double sdy = toDialogY(dy);
            for (PriceSpecEditor ed : this.priceEditors.values()) {
                if (ed.mouseDragged(sx, sy, button, sdx, sdy)) {
                    return true;
                }
            }
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (skia() && !this.priceEditors.isEmpty()) {
            double sx = toDialogX(mx);
            double sy = toDialogY(my);
            for (PriceSpecEditor ed : this.priceEditors.values()) {
                if (ed.mouseReleased(sx, sy, button)) {
                    return true;
                }
            }
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    protected boolean onScroll(double mx, double my, double amount) {
        for (PriceSpecEditor ed : this.priceEditors.values()) {
            if (ed.mouseScrolled(mx, my, amount)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean onEnter() {
        confirm();
        return true;
    }

    /** 按面板宽度截断文本（超出部分用省略号结尾）。 */
    private Component truncate(Component text, int maxWidth) {
        String plain = text.getString();
        if (this.font.width(plain) <= maxWidth) {
            return text;
        }
        return Component.literal(this.font.plainSubstrByWidth(plain, Math.max(10, maxWidth - 6)) + "...");
    }

    @Override
    public void onClose() {
        Nav.back();
        returnToTradeIfNeeded();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
