package cn.autoforged.server_shop_mod_1789689358.shop.mail;

import cn.autoforged.server_shop_mod_1789689358.data.ShopNbt;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * 统一邮件模板体系（需求：全模块邮件模板 + 占位符解析 + 构建器）。
 * <p>
 * 设计目标：**所有模块（拍卖 / 摊位 / 交易 / 兑换码 / 系统 / 活动）都用这里的模板发信**，
 * 与既有的 {@code ShopManager.addMail / addMoneyMail} 完全兼容——旧调用点不用改，
 * 只是多带一份 {@link DraftMail}（标题 / 正文 / 到期时间 / 星标）即可。
 * <p>
 * 颜色代码沿用 &amp; 前缀：&amp;e 金（奖励）/ &amp;a 绿（售出/成功）/ &amp;c 红（失败、拦截）/ &amp;7 灰（退回、下架）。
 * 正文里的 {@code \n} 会在客户端渲染成换行。
 */
public final class MailTemplates {
    private MailTemplates() {
    }

    /** 邮件业务类型（用于 UI 图标与配色区分；附件形态仍由 MailEntry 的 type 决定）。 */
    public enum Kind {
        SYSTEM,
        AUCTION,
        STALL,
        TRADE,
        REDEEM,
        ACTIVITY,
        REFUND,
    }

    /**
     * 标准模板：type（业务类型）/ sender / title / content / defaultExpireDays（0=永久）/ hasAttachment。
     */
    public enum Template {
        AUCTION_WON(Kind.AUCTION, "拍卖会系统", "&e【拍卖会】竞拍成功通知",
                "亲爱的 {player}，恭喜您在拍卖会中成功竞拍 {item}，成交价为 {price} 星币。物品已附在附件中，请查收。",
                0, true),
        AUCTION_OUTBID(Kind.AUCTION, "拍卖会系统", "&c【拍卖会】您的出价已被超越",
                "亲爱的 {player}，您对 {item} 的出价 {price} 已被其他玩家超越。当前最高价为 {newPrice} 星币。如需继续竞拍，请前往拍卖会查看。",
                0, true),
        AUCTION_UNSOLD(Kind.REFUND, "拍卖会系统", "&7【拍卖会】您的物品流拍",
                "亲爱的 {player}，您上架的 {item} 在拍卖期内无人竞拍，物品已退回至附件，请查收。",
                0, true),
        AUCTION_SOLD(Kind.AUCTION, "拍卖会系统", "&a【拍卖会】您的物品已售出",
                "亲爱的 {player}，您上架的 {item} 已成功售出，成交价为 {price} 星币。扣除手续费 {fee} 后，实际收入 {income} 星币已附在附件中。",
                0, true),
        AUCTION_UNLISTED(Kind.REFUND, "拍卖会系统", "&7【拍卖会】物品已下架",
                "亲爱的 {player}，您上架的 {item} 已下架，物品已退回至附件，请查收。",
                0, true),
        STALL_SOLD(Kind.STALL, "摊位系统", "&a【摊位】商品售出通知",
                "亲爱的 {player}，您在摊位出售的 {item} x{amount} 已被玩家 {buyer} 购买，获得 {price} 星币。收入已附在附件中，请查收。",
                0, true),
        STALL_DEPLETED(Kind.STALL, "摊位系统", "&e【摊位】商品已售空",
                "亲爱的 {player}，您摊位上架的 {item} 已全部售出（累计 {amount} 件），摊位库存已清空。货款已直接存入你的钱包，无需领取。如需继续出售，请重新上架。",
                0, false),
        STALL_EXPIRED(Kind.REFUND, "摊位系统", "&7【摊位】摊位到期，商品已退回",
                "亲爱的 {player}，您的摊位已到期，剩余商品已全部退回至附件，请查收。如需继续摆摊，请前往摊位区重新上架。",
                0, true),
        STALL_BOUGHT(Kind.STALL, "摊位系统", "&e【摊位】购买成功",
                "亲爱的 {player}，您已成功购买 {item} x{amount}，花费 {price} 星币。物品已附在附件中，请查收。",
                0, true),
        TRADE_DONE(Kind.TRADE, "交易系统", "&a【交易】交易成功",
                "亲爱的 {player}，您与玩家 {target} 的交易已完成。您获得：{items}。物品已附在附件中，请查收。",
                0, true),
        TRADE_CANCELLED(Kind.TRADE, "交易系统", "&c【交易】交易已取消",
                "亲爱的 {player}，您与玩家 {target} 的交易已取消，物品已退回至附件，请查收。",
                0, true),
        TRADE_DENIED(Kind.TRADE, "交易系统", "&c【交易】交易请求被拒绝",
                "亲爱的 {player}，您向玩家 {target} 发起的交易请求已被拒绝。",
                0, false),
        API_MAIL(Kind.SYSTEM, "系统", "&e【系统】您有一封新邮件",
                "亲爱的 {player}，您有一条来自系统/外部模组的通知，详情请查看邮件正文与附件。",
                0, true),
        DEMAND_DONE(Kind.STALL, "求购系统", "&a【求购】您的求购已有人交付",
                "亲爱的 {player}，您发布的求购「{item}」x{count} 已由玩家 {target} 交付完成，悬赏已付给 ta。您要的物品已附在附件中（在线时直接进背包），请查收。",
                0, false),
        REDEEM_OK(Kind.REDEEM, "兑换码系统", "&e【兑换码】兑换成功",
                "亲爱的 {player}，您使用的兑换码 {code} 已成功兑换。奖励已附在附件中，请查收。感谢您的支持！",
                0, true),
        BAG_FULL(Kind.SYSTEM, "系统", "&c【系统】背包已满，物品已转入邮箱",
                "亲爱的 {player}，由于您的背包已满（背包物品已达限额提醒），以下物品已自动转入邮箱，请清理背包后领取。",
                0, true),
        SYSTEM_NOTICE(Kind.SYSTEM, "服务器管理组", "&e【系统】版本更新补偿",
                "亲爱的 {player}，感谢您对服务器的支持。本次更新补偿已发放至附件，请查收。",
                0, true),
        ACTIVITY_REWARD(Kind.ACTIVITY, "活动系统", "&a【活动】活动奖励发放",
                "亲爱的 {player}，您在活动 {activityName} 中获得了奖励，已附在附件中，请查收。",
                30, true);

        private final Kind kind;
        private final String sender;
        private final String title;
        private final String content;
        private final int defaultExpireDays;
        private final boolean hasAttachment;

        Template(Kind kind, String sender, String title, String content, int defaultExpireDays,
                boolean hasAttachment) {
            this.kind = kind;
            this.sender = sender;
            this.title = title;
            this.content = content;
            this.defaultExpireDays = defaultExpireDays;
            this.hasAttachment = hasAttachment;
        }

        public Kind kind() {
            return kind;
        }

        public String sender() {
            return sender;
        }

        public String title() {
            return title;
        }

        public String content() {
            return content;
        }

        /** 默认有效期天数：0 = 永久。 */
        public int defaultExpireDays() {
            return defaultExpireDays;
        }

        public boolean hasAttachment() {
            return hasAttachment;
        }

        /** 该模板默认的到期时间戳（epoch 毫秒；0 = 永久）。 */
        public long defaultExpireAt() {
            return defaultExpireDays <= 0 ? 0L : System.currentTimeMillis() + defaultExpireDays * 86_400_000L;
        }
    }

    /**
     * 占位符解析器：统一处理 {@code {player} {target} {item} {amount} {price} {fee} {income}
     * {newPrice} {buyer} {code} {activityName} {time} {items}}。
     * <p>
     * 未知占位符**原样保留**（方便定位漏填的模块），重复占位符一次全部替换。
     */
    public static String resolve(String text, Map<String, String> placeholders) {
        if (text == null || text.isEmpty() || placeholders == null || placeholders.isEmpty()) {
            return text == null ? "" : text;
        }
        String out = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            out = out.replace("{" + entry.getKey() + "}", value);
        }
        return out;
    }

    /** 便捷构造占位符表：{@code map("player", name, "item", stackName)}。 */
    public static Map<String, String> map(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** 按模板 + 占位符构建一封待发送邮件。 */
    public static DraftMail build(Template template, Map<String, String> placeholders) {
        return new DraftMail(template, placeholders);
    }

    /** 物品名称文本（含数量），供 {item} / {items} 占位符使用。 */
    public static String itemText(ItemStack stack, boolean withCount) {
        if (stack == null || stack.isEmpty()) {
            return "-";
        }
        return withCount ? stack.getHoverName().getString() + " x" + stack.getCount()
                : stack.getHoverName().getString();
    }

    /** 物品列表文本：{@code 钻石 x2、星币 x1000}。 */
    public static String itemsText(List<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(itemText(stack, true));
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /**
     * 待发送的邮件草稿：标题 / 正文 / 发件人 / 类型 / 到期时间 / 附件（物品 + 星币）。
     * <p>
     * {@link #toTag(HolderLookup.Provider)} 产出的标签与 {@code MailEntry.save} 的字段**同形**，
     * 并额外带 {@code title / content / kind / expireAt / starred}，
     * 因此接入时只需在 {@code MailEntry} 补这几个字段即可，旧存档与旧调用点都不受影响。
     */
    public static final class DraftMail {
        private final Template template;
        private String title;
        private String content;
        private String sender;
        private Kind kind;
        private long expireAt;
        private boolean starred;
        private String source = "system";
        private final List<ItemStack> items = new ArrayList<>();
        private long money;
        /** 金额展示图标（可为空）。 */
        private ItemStack moneyDisplay = ItemStack.EMPTY;

        DraftMail(Template template, Map<String, String> placeholders) {
            this.template = template;
            this.title = resolve(template.title(), placeholders);
            this.content = resolve(template.content(), placeholders);
            this.sender = template.sender();
            this.kind = template.kind();
            this.expireAt = template.defaultExpireAt();
        }

        public DraftMail title(String value) {
            this.title = value == null ? "" : value;
            return this;
        }

        public DraftMail content(String value) {
            this.content = value == null ? "" : value;
            return this;
        }

        public DraftMail sender(String value) {
            this.sender = value == null ? "" : value;
            return this;
        }

        public DraftMail kind(Kind value) {
            this.kind = value == null ? Kind.SYSTEM : value;
            return this;
        }

        /** 来源标记（沿用 ShopManager 的 SOURCE_* 常量，决定旧版说明文案与图标回退）。 */
        public DraftMail source(String value) {
            this.source = value == null ? "system" : value;
            return this;
        }

        /** 覆盖到期时间（epoch 毫秒；0 = 永久）。 */
        public DraftMail expireAt(long value) {
            this.expireAt = Math.max(0L, value);
            return this;
        }

        /** 覆盖为「N 天后过期」（0 = 永久）。 */
        public DraftMail expireDays(int days) {
            this.expireAt = days <= 0 ? 0L : System.currentTimeMillis() + days * 86_400_000L;
            return this;
        }

        public DraftMail starred(boolean value) {
            this.starred = value;
            return this;
        }

        /** 追加一件附件（内部会做深拷贝，跨信任边界安全）。 */
        public DraftMail addItem(ItemStack stack) {
            if (stack != null && !stack.isEmpty()) {
                this.items.add(stack.copy());
            }
            return this;
        }

        /** 追加一批附件。 */
        public DraftMail addItems(List<ItemStack> stacks) {
            if (stacks != null) {
                for (ItemStack stack : stacks) {
                    addItem(stack);
                }
            }
            return this;
        }

        public DraftMail money(long amount) {
            this.money = Math.max(0L, amount);
            return this;
        }

        public DraftMail moneyDisplay(ItemStack stack) {
            this.moneyDisplay = stack == null ? ItemStack.EMPTY : stack.copy();
            return this;
        }

        public Template template() {
            return template;
        }

        public String title() {
            return title;
        }

        public String content() {
            return content;
        }

        public String sender() {
            return sender;
        }

        public Kind kind() {
            return kind;
        }

        public long expireAt() {
            return expireAt;
        }

        public boolean starred() {
            return starred;
        }

        public String source() {
            return source;
        }

        public List<ItemStack> items() {
            return items;
        }

        public long money() {
            return money;
        }

        /** 金额附件展示图标（可为空）。 */
        public ItemStack moneyDisplayStack() {
            return moneyDisplay;
        }

        /** 是否有附件（物品或金额）。 */
        public boolean hasAttachment() {
            return !items.isEmpty() || money > 0L;
        }

        /** 服务端校验：没有附件也没有内容时不该发信。 */
        public boolean valid() {
            return (!title.isEmpty() || !content.isEmpty());
        }

        /**
         * 转成邮件标签：与 {@code MailEntry.save} 同形 + 新增 title/content/kind/expireAt/starred。
         * 金额类附件写在 {@code currencyAmount}，物品类附件写在第一个物品槽（其余追加在 {@code extraItems}）。
         */
        public CompoundTag toTag(HolderLookup.Provider registries) {
            ItemStack first = items.isEmpty() ? ItemStack.EMPTY : items.get(0);
            CompoundTag tag = ShopNbt.saveStack(first, registries);
            tag.putString("source", source);
            tag.putString("sender", sender);
            tag.putString("title", title);
            tag.putString("content", content);
            tag.putString("kind", kind.name().toLowerCase(java.util.Locale.ROOT));
            tag.putString("template", template.name().toLowerCase(java.util.Locale.ROOT));
            tag.putLong("expireAt", expireAt);
            tag.putBoolean("starred", starred);
            tag.putLong("currencyAmount", money);
            if (!moneyDisplay.isEmpty()) {
                tag.put("moneyDisplay", ShopNbt.saveStack(moneyDisplay, registries));
            }
            if (items.size() > 1) {
                net.minecraft.nbt.ListTag extra = new net.minecraft.nbt.ListTag();
                for (int i = 1; i < items.size(); i++) {
                    extra.add(ShopNbt.saveStack(items.get(i), registries));
                }
                tag.put("extraItems", extra);
            }
            return tag;
        }
    }
}
