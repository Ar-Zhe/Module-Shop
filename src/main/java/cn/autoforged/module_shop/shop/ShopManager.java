package cn.autoforged.module_shop.shop;

import cn.autoforged.module_shop.Config;
import cn.autoforged.module_shop.ModuleShopMod;
import cn.autoforged.module_shop.admin.AdminEditType;
import cn.autoforged.module_shop.config.ShopTemplateStore;
import cn.autoforged.module_shop.config.TemplateTransferManager;
import cn.autoforged.module_shop.data.ActivityDay;
import cn.autoforged.module_shop.data.AuctionEntry;
import cn.autoforged.module_shop.data.ConfiguredOffer;
import cn.autoforged.module_shop.data.DailyBuybackEntry;
import cn.autoforged.module_shop.data.DemandEntry;
import cn.autoforged.module_shop.data.DailyShopEntry;
import cn.autoforged.module_shop.data.ExchangeEntry;
import cn.autoforged.module_shop.data.MailEntry;
import cn.autoforged.module_shop.data.MailType;
import cn.autoforged.module_shop.data.MallItem;
import cn.autoforged.module_shop.data.MallPurchase;
import cn.autoforged.module_shop.data.ShippingBin;
import cn.autoforged.module_shop.data.NotificationType;
import cn.autoforged.module_shop.data.PlayerAccount;
import cn.autoforged.module_shop.data.PriceSpec;
import cn.autoforged.module_shop.data.PriceSpec.CurrencyMode;
import cn.autoforged.module_shop.data.Rarity;
import cn.autoforged.module_shop.data.RedeemCode;
import cn.autoforged.module_shop.data.ShopNbt;
import cn.autoforged.module_shop.data.ShopSavedData;
import cn.autoforged.module_shop.data.SidebarLayout;
import cn.autoforged.module_shop.data.SignInData;
import cn.autoforged.module_shop.data.StallEntry;
import cn.autoforged.module_shop.data.TransactionEntry;
import cn.autoforged.module_shop.menu.BatchAddMenu;
import cn.autoforged.module_shop.menu.DepositMenu;
import cn.autoforged.module_shop.menu.GrantMenu;
import cn.autoforged.module_shop.menu.ShippingBinMenu;
import cn.autoforged.module_shop.shop.auth.AdminAuthService;
import cn.autoforged.module_shop.shop.currency.CurrencySettlement;
import cn.autoforged.module_shop.shop.currency.PhysicalCurrencyConfig;
import cn.autoforged.module_shop.shop.currency.PhysicalCurrencyEngine;
import cn.autoforged.module_shop.shop.mail.MailTemplates;
import cn.autoforged.module_shop.shop.media.ShopMediaStore;
import cn.autoforged.module_shop.network.payload.ClientboundShopSyncPayload;
import cn.autoforged.module_shop.sound.ModSounds;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import javax.annotation.Nullable;

/** 服务端权威的商店逻辑。所有校验都在这里完成，客户端只负责显示与发起请求。 */
public final class ShopManager {
    public static final String SOURCE_AUCTION_UNSOLD = "auction_unsold";
    public static final String SOURCE_AUCTION_WON = "auction_won";
    public static final String SOURCE_STALL_UNSOLD = "stall_unsold";
    /** 摊位到期自动下架，剩余库存退回。 */
    public static final String SOURCE_STALL_EXPIRED = "stall_expired";
    public static final String SOURCE_ADMIN = "admin";
    /** 需求：拍卖成交后给卖家的金额类邮件来源。 */
    public static final String SOURCE_AUCTION_SOLD = "auction_sold";
    /** 需求：摊位售出后给卖家的金额类邮件来源。 */
    public static final String SOURCE_STALL_SOLD = "stall_sold";
    /** 买家在摊位购买后，因背包放不下而进邮箱的物品来源（区别于“卖家上架未售出退还”）。 */
    public static final String SOURCE_STALL_BOUGHT = "stall_bought";
    /** 被超价后，退款余额装不下而改发金额邮件的来源。 */
    public static final String SOURCE_AUCTION_REFUND = "auction_refund";
    /** 货币取款时背包放不下，改发邮件（或兑换找零退回）的物品来源。 */
    public static final String SOURCE_EXCHANGE = "exchange";
    /** 出货箱结算时余额装不下、差额转成金额邮件的来源。 */
    public static final String SOURCE_SHIPPING_UNPAID = "shipping_unpaid";

    private static final Map<UUID, String> DEPOSIT_CONTEXT = new HashMap<>();
    private static final Map<UUID, ItemStack> PENDING_ITEM = new HashMap<>();
    /**
     * 需求 11：管理员通过幽灵槽放入的“模板物品”。
     * 幽灵槽会把原物品返还给管理员，因此这里只保存模板副本；模板绝不作为真实物品返还，
     * 避免“幽灵槽返还原物 + 悬空副本再返还”造成的刷物品漏洞。
     */
    private static final Map<UUID, ItemStack> ADMIN_TEMPLATE = new HashMap<>();

    /**
     * 需求 8/9：所有涉及余额、库存、竞拍的变更都在这把锁内执行，
     * 防止多名玩家同时购买最后一件商品/竞拍同一物品导致数据不一致。
     */
    /** 第七轮：全服物品发放邮件的有效期（天）。 */
    private static final int GRANT_EXPIRE_DAYS = 30;
    public static final Object LOCK = new Object();

    /** 需求 11：服务端记录管理员当前正在编辑的模块（每日商店 / 每日收购）。 */
    private static final Map<UUID, AdminEditType> ADMIN_EDIT_TYPE = new HashMap<>();
    /** 购物城"最近购买"下发给客户端的条数（存档里最多留 200 条）。 */
    private static final int MALL_RECENT_SEND = 60;
    /** 一次「整模组导入」最多导入多少件（防止误点把几万件物品塞进快照 / 存档）。 */
    private static final int MALL_IMPORT_MAX = 2000;

    private ShopManager() {
    }

    public static ShopSavedData data(MinecraftServer server) {
        return ShopSavedData.get(server);
    }

    private static final Gson BRAND_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String BRAND_DEFAULT_FILE = "brand_default.json";

    public static boolean canEditAdmin(@Nullable ServerPlayer player) {
        return AdminAuthService.canEditAdmin(player);
    }

    public static void promptAdminAuth(@Nullable ServerPlayer player) {
        if (player == null) {
            return;
        }
        CompoundTag out = new CompoundTag();
        out.putString("reasonKey", "msg.module_shop.auth_required");
        out.putBoolean("adminLockEnabled", AdminAuthService.isLockEnabled());
        out.putBoolean("adminUnlocked", player.hasPermissions(2) && AdminAuthService.isUnlocked(player));
        out.putInt("retryAfterSeconds", AdminAuthService.remainingCooldownSeconds(player));
        sendSpecial(player, "admin_auth_prompt", out);
    }

    private static boolean requireUnlockedAdmin(@Nullable ServerPlayer player) {
        if (player == null || !player.hasPermissions(2)) {
            return false;
        }
        if (!AdminAuthService.isUnlocked(player)) {
            notifyPlayer(player, Component.translatable("msg.module_shop.auth_required")
                    .withStyle(ChatFormatting.RED));
            promptAdminAuth(player);
            return false;
        }
        return true;
    }

    public static void sendSpecial(ServerPlayer player, String kind, @Nullable CompoundTag extra) {
        if (player == null || kind == null || kind.isBlank()) {
            return;
        }
        CompoundTag out = extra == null ? new CompoundTag() : extra.copy();
        out.putString("kind", kind);
        ClientboundShopSyncPayload.send(player, out);
    }

    public static void refreshAllClients(MinecraftServer server, @Nullable ShopSavedData data) {
        if (server == null) {
            return;
        }
        if (data != null) {
            loadBrandDefaultIfBlank(data.sidebarLayout);
        }
        broadcastSnapshots(server);
        broadcastSidebarLayout(server);
    }

    private static Path brandDefaultFile() {
        return FMLPaths.CONFIGDIR.get().resolve(ShopTemplateStore.DIR_NAME).resolve(BRAND_DEFAULT_FILE);
    }

    public static void saveBrandDefault(@Nullable SidebarLayout layout) {
        if (layout == null) {
            return;
        }
        Path file = brandDefaultFile();
        try {
            Files.createDirectories(file.getParent());
            JsonObject obj = new JsonObject();
            String title = layout.shopTitle();
            obj.addProperty("shopTitle", title == null ? "" : title);
            obj.addProperty("brandIconId", layout.brandIconId());
            Path tmp = file.resolveSibling(BRAND_DEFAULT_FILE + ".tmp");
            Files.writeString(tmp, BRAND_GSON.toJson(obj), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable ex) {
            org.slf4j.LoggerFactory.getLogger("module_shop")
                    .warn("[ModuleShop] 写入 {} 失败：{}", BRAND_DEFAULT_FILE, ex.toString());
        }
    }

    public static void loadBrandDefaultIfBlank(@Nullable SidebarLayout layout) {
        if (layout == null) {
            return;
        }
        boolean needTitle = layout.shopTitle() == null;
        boolean needIcon = layout.brandIconId().isBlank();
        if (!needTitle && !needIcon) {
            return;
        }
        Path file = brandDefaultFile();
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (raw.startsWith("\uFEFF")) {
                raw = raw.substring(1);
            }
            var elem = JsonParser.parseString(raw);
            if (!elem.isJsonObject()) {
                return;
            }
            JsonObject obj = elem.getAsJsonObject();
            if (needTitle && obj.has("shopTitle") && obj.get("shopTitle").isJsonPrimitive()) {
                String savedTitle = obj.get("shopTitle").getAsString();
                if (savedTitle != null && !savedTitle.isBlank()) {
                    layout.setShopTitle(savedTitle);
                }
            }
            if (needIcon && obj.has("brandIconId") && obj.get("brandIconId").isJsonPrimitive()) {
                String savedIcon = obj.get("brandIconId").getAsString();
                if (ShopMediaStore.isValidId(savedIcon == null ? "" : savedIcon.trim())) {
                    layout.setBrandIconId(savedIcon);
                }
            }
        } catch (Throwable ex) {
            org.slf4j.LoggerFactory.getLogger("module_shop")
                    .warn("[ModuleShop] 读取 {} 失败：{}", BRAND_DEFAULT_FILE, ex.toString());
        }
    }

    private static void adminAuthUnlock(ServerPlayer player, CompoundTag tag) {
        if (player == null || !player.hasPermissions(2)) {
            return;
        }
        String password = tag == null ? "" : tag.getString("password");
        AdminAuthService.UnlockOutcome outcome = AdminAuthService.tryUnlock(player, password);
        CompoundTag out = new CompoundTag();
        out.putString("mode", "unlock");
        out.putBoolean("ok", outcome.ok());
        out.putString("errorKey", outcome.errorKey());
        out.putInt("retryAfterSeconds", outcome.retryAfterSeconds());
        out.putBoolean("adminLockEnabled", AdminAuthService.isLockEnabled());
        out.putBoolean("adminUnlocked", AdminAuthService.isUnlocked(player));
        out.putInt("adminSessionTimeout", AdminAuthService.sessionTimeoutMinutes());
        sendSpecial(player, "admin_auth_result", out);
        if (outcome.ok()) {
            sendSnapshot(player, null, true);
            notifyPlayer(player, Component.translatable("msg.module_shop.auth_ok")
                    .withStyle(ChatFormatting.GREEN));
        }
    }

    private static void adminAuthLock(ServerPlayer player) {
        if (player == null || !player.hasPermissions(2)) {
            return;
        }
        AdminAuthService.lockPlayer(player.getUUID());
        CompoundTag out = new CompoundTag();
        out.putString("mode", "lock");
        out.putBoolean("ok", true);
        out.putString("errorKey", "");
        out.putInt("retryAfterSeconds", 0);
        out.putBoolean("adminLockEnabled", AdminAuthService.isLockEnabled());
        out.putBoolean("adminUnlocked", AdminAuthService.isUnlocked(player));
        out.putInt("adminSessionTimeout", AdminAuthService.sessionTimeoutMinutes());
        sendSpecial(player, "admin_auth_result", out);
        sendSnapshot(player, null, true);
        notifyPlayer(player, Component.translatable("msg.module_shop.auth_locked")
                .withStyle(ChatFormatting.YELLOW));
    }

    private static void adminAuthConfigure(ServerPlayer player, CompoundTag tag) {
        if (player == null || !player.hasPermissions(2) || tag == null) {
            return;
        }
        boolean enable = tag.getBoolean("enable");
        String currentPassword = tag.getString("currentPassword");
        String newPassword = tag.getString("newPassword");
        int timeoutMinutes = tag.contains("timeoutMinutes")
                ? tag.getInt("timeoutMinutes")
                : AdminAuthService.sessionTimeoutMinutes();
        AdminAuthService.SaveAuthOutcome res = AdminAuthService.configure(
                player, enable, currentPassword, newPassword, timeoutMinutes);
        CompoundTag out = new CompoundTag();
        out.putString("mode", "configure");
        out.putBoolean("ok", res.ok());
        out.putString("errorKey", res.errorKey());
        out.putInt("retryAfterSeconds", 0);
        out.putBoolean("adminLockEnabled", AdminAuthService.isLockEnabled());
        out.putBoolean("adminUnlocked", AdminAuthService.isUnlocked(player));
        out.putInt("adminSessionTimeout", AdminAuthService.sessionTimeoutMinutes());
        sendSpecial(player, "admin_auth_result", out);
        if (res.ok()) {
            notifyPlayer(player, Component.translatable("msg.module_shop.auth_saved")
                    .withStyle(ChatFormatting.GREEN));
            for (ServerPlayer online : player.server.getPlayerList().getPlayers()) {
                if (online.hasPermissions(2)) {
                    sendSnapshot(online, null, true);
                }
            }
        }
    }

    private static void adminMediaUpload(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player) || tag == null) {
            return;
        }
        String purpose = tag.getString("purpose");
        String requestId = tag.getString("requestId");
        byte[] bytes = tag.getByteArray("bytes");
        ShopMediaStore.SaveResult res = ShopMediaStore.saveVerified(bytes);
        CompoundTag out = new CompoundTag();
        out.putBoolean("ok", res.ok());
        out.putString("errorKey", res.errorKey());
        out.putString("purpose", purpose);
        out.putString("requestId", requestId);
        if (res.ok() && res.meta() != null) {
            out.putString("id", res.meta().id());
            out.putInt("width", res.meta().width());
            out.putInt("height", res.meta().height());
            notifyPlayer(player, Component.translatable("msg.module_shop.media_upload_ok")
                    .withStyle(ChatFormatting.GREEN));
        } else {
            out.putString("id", "");
            out.putInt("width", 0);
            out.putInt("height", 0);
            notifyPlayer(player, Component.translatable("msg.module_shop.media_bad_image")
                    .withStyle(ChatFormatting.RED));
        }
        sendSpecial(player, "media_uploaded", out);
    }

    private static void adminShopIconSet(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player) || tag == null) {
            return;
        }
        String mediaId = tag.getString("mediaId").trim().toLowerCase(java.util.Locale.ROOT);
        if (mediaId.isEmpty() && tag.contains("icon", Tag.TAG_BYTE_ARRAY)) {
            byte[] iconBytes = tag.getByteArray("icon");
            if (iconBytes.length > 0) {
                ShopMediaStore.SaveResult saved = ShopMediaStore.saveVerified(iconBytes);
                if (!saved.ok() || saved.meta() == null) {
                    notifyPlayer(player, Component.translatable("msg.module_shop.media_bad_image")
                            .withStyle(ChatFormatting.RED));
                    return;
                }
                mediaId = saved.meta().id();
            }
        }
        if (!mediaId.isEmpty()) {
            if (!ShopMediaStore.isValidId(mediaId) || ShopMediaStore.readMeta(mediaId) == null) {
                notifyPlayer(player, Component.translatable("msg.module_shop.media_bad_image")
                        .withStyle(ChatFormatting.RED));
                return;
            }
        }
        ShopSavedData data = data(player.server);
        boolean editing = layoutEditing(player);
        SidebarLayout target = editing ? layoutDraft(data, player).layout : data.sidebarLayout;
        target.setBrandIconId(mediaId);
        if (!editing) {
            data.setDirty();
            saveBrandDefault(data.sidebarLayout);
        }
        pushLayoutTo(player);
        notifyPlayer(player, Component.translatable("msg.module_shop.brand_saved")
                .withStyle(ChatFormatting.GREEN));
    }

    private static void handleMediaFetch(ServerPlayer player, CompoundTag tag) {
        if (player == null || tag == null) {
            return;
        }
        String id = tag.getString("id").trim().toLowerCase(java.util.Locale.ROOT);
        CompoundTag out = new CompoundTag();
        out.putString("id", id);
        if (!ShopMediaStore.isValidId(id)) {
            out.putBoolean("ok", false);
            out.putInt("width", 0);
            out.putInt("height", 0);
            out.putString("mime", "");
            out.putByteArray("bytes", new byte[0]);
            out.putString("errorKey", "bad_image");
            sendSpecial(player, "media_data", out);
            return;
        }
        ShopMediaStore.MediaMeta meta = ShopMediaStore.readMeta(id);
        byte[] bytes = meta == null ? null : ShopMediaStore.readBytes(id);
        if (meta == null || bytes == null || bytes.length == 0) {
            out.putBoolean("ok", false);
            out.putInt("width", 0);
            out.putInt("height", 0);
            out.putString("mime", "");
            out.putByteArray("bytes", new byte[0]);
            out.putString("errorKey", "missing");
            sendSpecial(player, "media_data", out);
            return;
        }
        out.putBoolean("ok", true);
        out.putInt("width", meta.width());
        out.putInt("height", meta.height());
        out.putString("mime", meta.mime());
        out.putByteArray("bytes", bytes);
        out.putString("errorKey", "");
        sendSpecial(player, "media_data", out);
    }

    private static int parseOptionalNonNegativeInt(ServerPlayer player, CompoundTag tag, String key) {
        String raw = tag == null || !tag.contains(key) ? "" : tag.getString(key).trim();
        if (raw.isEmpty() || "0".equals(raw)) {
            return 0;
        }
        try {
            long val = Long.parseLong(raw);
            if (val < 0L || val > 1_000_000_000L) {
                notifyPlayer(player, Component.translatableWithFallback(
                        "msg.module_shop.invalid_daily_max", "每日出货上限无效：请留空或填 0 及以上的整数")
                        .withStyle(ChatFormatting.RED));
                return -1;
            }
            return (int) val;
        } catch (NumberFormatException ex) {
            notifyPlayer(player, Component.translatableWithFallback(
                    "msg.module_shop.invalid_daily_max", "每日出货上限无效：请留空或填 0 及以上的整数")
                    .withStyle(ChatFormatting.RED));
            return -1;
        }
    }

    private static boolean isPermanentLimitFlag(@Nullable CompoundTag tag) {
        if (tag == null) {
            return false;
        }
        if (tag.getBoolean("permanentLimit")) {
            return true;
        }
        String raw = tag.getString("permanent_limit").trim().toLowerCase(java.util.Locale.ROOT);
        return "1".equals(raw) || "true".equals(raw) || "yes".equals(raw) || "on".equals(raw);
    }

    private static int parseOptionalExchangeLimit(ServerPlayer player, @Nullable CompoundTag tag, String key) {
        String raw = tag == null || !tag.contains(key) ? "" : tag.getString(key).trim();
        if (raw.isEmpty() || "0".equals(raw)) {
            return 0;
        }
        try {
            long val = Long.parseLong(raw);
            if (val < 0L || val > 1_000_000_000L) {
                notifyPlayer(player, Component.translatableWithFallback(
                        "msg.module_shop.exchange_limit_invalid", "兑换每日上限无效：请留空或填 0 及以上的整数")
                        .withStyle(ChatFormatting.RED));
                return -1;
            }
            return (int) val;
        } catch (NumberFormatException ex) {
            notifyPlayer(player, Component.translatableWithFallback(
                    "msg.module_shop.exchange_limit_invalid", "兑换每日上限无效：请留空或填 0 及以上的整数")
                    .withStyle(ChatFormatting.RED));
            return -1;
        }
    }

    private static void syncShippingConfigForLevels(ShopSavedData data) {
        int levels = Config.shippingLevels(data);
        List<String> slots = new ArrayList<>(Config.INSTANCE.shippingLevelSlots.get());
        boolean dirty = false;
        while (slots.size() < levels) {
            slots.add(Integer.toString(Config.shippingSlotsFor(slots.size() + 1)));
            dirty = true;
        }
        List<String> limits = new ArrayList<>(Config.INSTANCE.shippingDailyLimits.get());
        while (limits.size() < levels) {
            limits.add("0");
            dirty = true;
        }
        if (dirty) {
            Config.INSTANCE.shippingLevelSlots.set(List.copyOf(slots));
            Config.INSTANCE.shippingDailyLimits.set(List.copyOf(limits));
            Config.SPEC.save();
        }
    }

    /**
     * 现实时间日：需求明确“每日0点”指现实时间，因此以服务器本地日历日（LocalDate）为准，
     * 在本地时间午夜切换，而不是按游戏内天数（24000 tick）。
     */
    private static long currentDay(net.minecraft.world.level.Level level) {
        return LocalDate.now().toEpochDay();
    }

    public static PlayerAccount account(ServerPlayer player) {
        ShopSavedData data = data(player.server);
        PlayerAccount account = data.account(player.getUUID());
        String name = player.getGameProfile().getName();
        if (!name.equals(account.name)) {
            account.name = name;
            data.setDirty();
        }
        return account;
    }

    // =========================================================== 每日刷新

    public static void ensureDaily(ServerPlayer player, PlayerAccount account, ShopSavedData data) {
        long day = currentDay(player.level());
        boolean dirty = false;
        boolean shopRefreshed = false;
        boolean buybackRefreshed = false;
        if (account.dailyShopDay != day) {
            account.dailyShopDay = day;
            account.dailyShop.clear();
            account.dailyFlipped.clear();
            // 新的一天：付费刷新次数重新计。
            account.dailyShopRefreshUsed = 0;
            generateDailyShop(player, account, data);
            dirty = true;
            shopRefreshed = true;
        } else if (syncDailyShopWithPool(player, account, data)) {
            dirty = true;
        }
        if (account.dailyBuybackDay != day) {
            account.dailyBuybackDay = day;
            account.dailyBuyback.clear();
            // 新的一天：全清奖励可以再拿一次，付费刷新次数重新计。
            account.dailyBuybackBonusDone = false;
            account.dailyBuybackRefreshUsed = 0;
            generateDailyBuyback(player, account, data);
            dirty = true;
            buybackRefreshed = true;
        } else if (syncDailyBuybackWithPool(player, account, data)) {
            dirty = true;
        }
        if (dirty) {
            data.setDirty();
        }
        // 需求 10：每日刷新时间到了给玩家一个提示红点；两个列表各自独立计数（不会一起亮）。
        if (shopRefreshed) {
            NotificationManager.notify(player.server, player.getUUID(), NotificationType.SHOP);
        }
        if (buybackRefreshed) {
            NotificationManager.notify(player.server, player.getUUID(), NotificationType.BUYBACK);
        }
    }

    /** 每日商店 / 每日收购 的条目数量上限（管理员可在 0 ~ 64 之间自由设置，0 = 当天不生成）。 */
    public static final int MAX_DAILY_SLOTS = 64;

    private static void generateDailyShop(ServerPlayer player, PlayerAccount account, ShopSavedData data) {
        account.dailyFlipped.clear();
        List<Rarity> available = new ArrayList<>();
        for (Rarity rarity : Rarity.values()) {
            if (hasRarity(data.shopPool, rarity)) {
                available.add(rarity);
            }
        }
        if (available.isEmpty()) {
            return;
        }
        RandomSource random = player.getRandom();
        Set<Item> chosen = new HashSet<>();
        // 各品质已出现的数量：普通档位有上限（默认最多 3 个），抽到满额时从可选品质里剔除。
        Map<Rarity, Integer> usedByRarity = new HashMap<>();
        int commonLimit = Config.INSTANCE.dailyShopCommonLimit.get();
        int slots = Math.max(0, Math.min(MAX_DAILY_SLOTS, data.shopSlotCount));
        for (int i = 0; i < slots; i++) {
            List<Rarity> usable = new ArrayList<>();
            for (Rarity rarity : available) {
                if (rarity == Rarity.COMMON && commonLimit > 0
                        && usedByRarity.getOrDefault(rarity, 0) >= commonLimit) {
                    continue;
                }
                usable.add(rarity);
            }
            // 理论上 available 里至少有一个可用档位；全满时退回原集合，保证槽位仍然填满。
            Rarity rarity = rollRarity(random, usable.isEmpty() ? available : usable, data.shopWeights);
            List<ConfiguredOffer> candidates = new ArrayList<>();
            for (ConfiguredOffer offer : data.shopPool) {
                if (offer.rarity == rarity && !offer.stack.isEmpty()) {
                    candidates.add(offer);
                }
            }
            if (candidates.isEmpty()) {
                continue;
            }
            List<ConfiguredOffer> fresh = new ArrayList<>();
            for (ConfiguredOffer offer : candidates) {
                if (!chosen.contains(offer.stack.getItem())) {
                    fresh.add(offer);
                }
            }
            ConfiguredOffer picked = pick(random, fresh.isEmpty() ? candidates : fresh);
            if (picked == null) {
                continue;
            }
            chosen.add(picked.stack.getItem());
            usedByRarity.merge(picked.rarity, 1, Integer::sum);
            DailyShopEntry entry = new DailyShopEntry(picked.stack.copy(), picked.resolvePriceSpec(), picked.rarity);
            // 需求 7/11：每个物品各自的购买上限来自管理员配置的数量。
            entry.limit = Math.max(1, picked.amountOr(picked.rarity.purchaseLimit()));
            account.dailyShop.add(entry);
        }
        // 需求 8：保底品质不足时补足（优先替换普通项）。条目数设成 0 = 当天不生成，保底也不补。
        if (slots > 0) {
            applyShopGuarantee(random, account.dailyShop, data.shopPool, data.shopGuarantee);
        }
    }

    /** 按指定权重（传说/史诗/稀有/普通）抽品质；权重全为 0 时退回配置权重。 */
    private static Rarity rollRarity(RandomSource random, List<Rarity> available, int[] weights) {
        int total = 0;
        for (Rarity rarity : available) {
            total += Math.max(0, weightOf(rarity, weights));
        }
        if (total <= 0) {
            return rollRarity(random, available);
        }
        int roll = random.nextInt(total);
        for (Rarity rarity : available) {
            roll -= Math.max(0, weightOf(rarity, weights));
            if (roll < 0) {
                return rarity;
            }
        }
        return available.get(0);
    }

    private static int weightOf(Rarity rarity, int[] weights) {
        if (weights == null || weights.length < 4) {
            return 1;
        }
        return switch (rarity) {
            case LEGENDARY -> weights[0];
            case EPIC -> weights[1];
            case RARE -> weights[2];
            case COMMON -> weights[3];
        };
    }

    /** 每日收购用：池子里出现过的品质列表。 */
    private static List<Rarity> raritiesOf(List<ConfiguredOffer> pool) {
        List<Rarity> list = new ArrayList<>();
        for (ConfiguredOffer offer : pool) {
            if (!list.contains(offer.rarity)) {
                list.add(offer.rarity);
            }
        }
        return list;
    }

    private static DailyShopEntry shopEntryOf(ConfiguredOffer picked) {
        DailyShopEntry entry = new DailyShopEntry(picked.stack.copy(), picked.resolvePriceSpec(), picked.rarity);
        entry.limit = Math.max(1, picked.amountOr(picked.rarity.purchaseLimit()));
        return entry;
    }

    /** 需求 8 保底：把不足的品质补足（优先替换普通项，其次追加，最多 12 张）。 */
    private static void applyShopGuarantee(RandomSource random, List<DailyShopEntry> entries,
            List<ConfiguredOffer> pool, int[] guarantee) {
        Rarity[] order = {Rarity.RARE, Rarity.EPIC, Rarity.LEGENDARY};
        for (int i = 0; i < order.length && i < guarantee.length; i++) {
            int need = Math.max(0, guarantee[i]);
            if (need <= 0) {
                continue;
            }
            Rarity rarity = order[i];
            long have = 0L;
            for (DailyShopEntry entry : entries) {
                if (entry.rarity == rarity) {
                    have++;
                }
            }
            while (have < need) {
                List<ConfiguredOffer> candidates = new ArrayList<>();
                for (ConfiguredOffer offer : pool) {
                    if (offer.rarity == rarity && !offer.stack.isEmpty()) {
                        candidates.add(offer);
                    }
                }
                if (candidates.isEmpty()) {
                    break;
                }
                ConfiguredOffer picked = pick(random, candidates);
                if (picked == null) {
                    break;
                }
                boolean replaced = false;
                for (int k = entries.size() - 1; k >= 0; k--) {
                    if (entries.get(k).rarity == Rarity.COMMON) {
                        entries.set(k, shopEntryOf(picked));
                        replaced = true;
                        break;
                    }
                }
                if (!replaced && entries.size() < MAX_DAILY_SLOTS) {
                    entries.add(shopEntryOf(picked));
                }
                have++;
            }
        }
    }

    private static DailyBuybackEntry buybackEntryOf(ConfiguredOffer picked) {
        DailyBuybackEntry entry = new DailyBuybackEntry(picked.stack.copy(), picked.resolvePriceSpec());
        entry.rarity = picked.rarity;
        entry.limit = Math.max(1, picked.amountOr(Config.INSTANCE.buybackCapacity.get()));
        return entry;
    }

    static void applyBuybackGuarantee(RandomSource random, List<DailyBuybackEntry> entries,
            List<ConfiguredOffer> pool, int[] guarantee) {
        Rarity[] order = {Rarity.RARE, Rarity.EPIC, Rarity.LEGENDARY};
        for (int i = 0; i < order.length && i < guarantee.length; i++) {
            int need = Math.max(0, guarantee[i]);
            if (need <= 0) {
                continue;
            }
            Rarity rarity = order[i];
            long have = 0L;
            for (DailyBuybackEntry entry : entries) {
                if (entry.rarity == rarity) {
                    have++;
                }
            }
            while (have < need) {
                List<ConfiguredOffer> candidates = new ArrayList<>();
                for (ConfiguredOffer offer : pool) {
                    if (offer.rarity == rarity && !offer.stack.isEmpty()
                            && !containsBuybackStack(entries, offer.stack)) {
                        candidates.add(offer);
                    }
                }
                if (candidates.isEmpty()) {
                    break;
                }
                ConfiguredOffer picked = pick(random, candidates);
                if (picked == null) {
                    break;
                }
                boolean replaced = false;
                for (int k = entries.size() - 1; k >= 0; k--) {
                    if (entries.get(k).rarity == Rarity.COMMON) {
                        entries.set(k, buybackEntryOf(picked));
                        replaced = true;
                        break;
                    }
                }
                if (!replaced && entries.size() < MAX_DAILY_SLOTS) {
                    entries.add(buybackEntryOf(picked));
                }
                have++;
            }
        }
    }

    private static boolean containsShopStack(List<DailyShopEntry> entries, ItemStack stack) {
        for (DailyShopEntry entry : entries) {
            if (!entry.stack.isEmpty() && ItemStack.isSameItemSameTags(entry.stack, stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsBuybackStack(List<DailyBuybackEntry> entries, ItemStack stack) {
        for (DailyBuybackEntry entry : entries) {
            if (!entry.stack.isEmpty() && ItemStack.isSameItemSameTags(entry.stack, stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 同日增量同步（每日商店）：移除已被管理员从池中删除的条目（保留其余卡牌翻面下标），
     * 并在玩家当日槽位未满（如管理员先清空池再上架、或池内新增条目且未达上限）时自动补齐。
     */
    private static boolean syncDailyShopWithPool(ServerPlayer player, PlayerAccount account, ShopSavedData data) {
        boolean changed = false;
        int targetSlots = Math.max(0, Math.min(MAX_DAILY_SLOTS, data.shopSlotCount));
        if (targetSlots == 0) {
            if (!account.dailyShop.isEmpty() || !account.dailyFlipped.isEmpty()) {
                account.dailyShop.clear();
                account.dailyFlipped.clear();
                return true;
            }
            return false;
        }
        if (!account.dailyShop.isEmpty()) {
            List<DailyShopEntry> kept = new ArrayList<>(account.dailyShop.size());
            Set<Integer> newFlipped = new HashSet<>();
            for (int i = 0; i < account.dailyShop.size(); i++) {
                DailyShopEntry entry = account.dailyShop.get(i);
                if (findOffer(data.shopPool, entry.stack) != null) {
                    if (account.dailyFlipped.contains(i)) {
                        newFlipped.add(kept.size());
                    }
                    kept.add(entry);
                } else {
                    changed = true;
                }
            }
            if (changed) {
                account.dailyShop.clear();
                account.dailyShop.addAll(kept);
                account.dailyFlipped.clear();
                account.dailyFlipped.addAll(newFlipped);
            }
        }
        if (account.dailyShop.isEmpty()) {
            if (!data.shopPool.isEmpty()) {
                generateDailyShop(player, account, data);
                if (!account.dailyShop.isEmpty()) {
                    changed = true;
                }
            }
            return changed;
        }
        if (account.dailyShop.size() < targetSlots && !data.shopPool.isEmpty()) {
            List<ConfiguredOffer> freshPool = new ArrayList<>();
            for (ConfiguredOffer offer : data.shopPool) {
                if (!offer.stack.isEmpty() && offer.resolvePriceSpec().isValid()
                        && !containsShopStack(account.dailyShop, offer.stack)) {
                    freshPool.add(offer);
                }
            }
            RandomSource random = player.getRandom();
            while (account.dailyShop.size() < targetSlots && !freshPool.isEmpty()) {
                List<Rarity> avail = raritiesOf(freshPool);
                Rarity want = rollRarity(random, avail.isEmpty() ? List.of(Rarity.COMMON) : avail, data.shopWeights);
                List<ConfiguredOffer> ofRarity = new ArrayList<>();
                for (ConfiguredOffer offer : freshPool) {
                    if (offer.rarity == want) {
                        ofRarity.add(offer);
                    }
                }
                ConfiguredOffer picked = pick(random, ofRarity.isEmpty() ? freshPool : ofRarity);
                if (picked == null) {
                    break;
                }
                freshPool.removeIf(o -> ItemStack.isSameItemSameTags(o.stack, picked.stack));
                account.dailyShop.add(shopEntryOf(picked));
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 同日增量同步（每日收购）：移除已从收购池删除或触发防套利拦截的条目，
     * 并在玩家当日收购清单未满（如空池先登后摆、或池子条目数 <= 槽位上限时新增收购品）时实时补齐给所有玩家。
     */
    private static boolean syncDailyBuybackWithPool(ServerPlayer player, PlayerAccount account, ShopSavedData data) {
        boolean changed = false;
        int maxSlots = Math.max(0, Math.min(MAX_DAILY_SLOTS, data.buybackSlotCount));
        if (maxSlots == 0) {
            if (!account.dailyBuyback.isEmpty()) {
                account.dailyBuyback.clear();
                return true;
            }
            return false;
        }
        HolderLookup.Provider regs = player.level().registryAccess();
        if (!account.dailyBuyback.isEmpty()) {
            boolean removed = account.dailyBuyback.removeIf(entry -> {
                ConfiguredOffer match = findOffer(data.buybackPool, entry.stack);
                if (match == null) {
                    return true;
                }
                PriceSpec spec = match.resolvePriceSpec();
                return !spec.isValid()
                        || CurrencySettlement.isBlockingBuybackAndShippingForSpec(entry.stack, spec, regs, true);
            });
            if (removed) {
                changed = true;
            }
        }
        if (account.dailyBuyback.isEmpty()) {
            if (!data.buybackPool.isEmpty()) {
                generateDailyBuyback(player, account, data);
                if (!account.dailyBuyback.isEmpty()) {
                    changed = true;
                }
            }
            return changed;
        }
        if (account.dailyBuyback.size() < maxSlots && !data.buybackPool.isEmpty()) {
            List<ConfiguredOffer> freshPool = new ArrayList<>();
            for (ConfiguredOffer offer : data.buybackPool) {
                PriceSpec offerSpec = offer.resolvePriceSpec();
                if (offer.stack.isEmpty() || !offerSpec.isValid()) {
                    continue;
                }
                if (CurrencySettlement.isBlockingBuybackAndShippingForSpec(offer.stack, offerSpec, regs, true)) {
                    continue;
                }
                if (!containsBuybackStack(account.dailyBuyback, offer.stack)) {
                    freshPool.add(offer);
                }
            }
            RandomSource random = player.getRandom();
            while (account.dailyBuyback.size() < maxSlots && !freshPool.isEmpty()) {
                List<Rarity> avail = raritiesOf(freshPool);
                Rarity want = rollRarity(random, avail.isEmpty() ? List.of(Rarity.COMMON) : avail, data.buybackWeights);
                List<ConfiguredOffer> ofRarity = new ArrayList<>();
                for (ConfiguredOffer offer : freshPool) {
                    if (offer.rarity == want) {
                        ofRarity.add(offer);
                    }
                }
                ConfiguredOffer picked = pick(random, ofRarity.isEmpty() ? freshPool : ofRarity);
                if (picked == null) {
                    break;
                }
                freshPool.removeIf(o -> ItemStack.isSameItemSameTags(o.stack, picked.stack));
                account.dailyBuyback.add(buybackEntryOf(picked));
                changed = true;
            }
        }
        return changed;
    }

    private static void generateDailyBuyback(ServerPlayer player, PlayerAccount account, ShopSavedData data) {
        if (data.buybackPool.isEmpty()) {
            return;
        }
        List<ConfiguredOffer> pool = new ArrayList<>();
        HolderLookup.Provider regs = player.level().registryAccess();
        for (ConfiguredOffer offer : data.buybackPool) {
            PriceSpec offerSpec = offer.resolvePriceSpec();
            if (offer.stack.isEmpty() || !offerSpec.isValid()) {
                continue;
            }
            if (CurrencySettlement.isBlockingBuybackAndShippingForSpec(offer.stack, offerSpec, regs, true)) {
                continue;
            }
            pool.add(offer);
        }
        List<ConfiguredOffer> eligiblePool = new ArrayList<>(pool);
        RandomSource random = player.getRandom();
        // 上下限由管理员设置（0 ~ 64）：0 = 今天不生成收购需求。
        int slots = Math.min(MAX_DAILY_SLOTS, Math.min(Math.max(0, data.buybackSlotCount), pool.size()));
        for (int i = 0; i < slots; i++) {
            // 需求 8：先按品质权重抽一个品质，再在该品质里随机取条目。
            List<Rarity> buybackRarities = raritiesOf(pool);
            Rarity want = rollRarity(random, buybackRarities.isEmpty() ? List.of(Rarity.COMMON) : buybackRarities,
                    data.buybackWeights);
            List<ConfiguredOffer> ofRarity = new ArrayList<>();
            for (ConfiguredOffer offer : pool) {
                if (offer.rarity == want) {
                    ofRarity.add(offer);
                }
            }
            ConfiguredOffer picked = pick(random, ofRarity.isEmpty() ? pool : ofRarity);
            if (picked == null) {
                break;
            }
            pool.removeIf(o -> ItemStack.isSameItemSameTags(o.stack, picked.stack));
            account.dailyBuyback.add(buybackEntryOf(picked));
        }
        if (slots > 0) {
            applyBuybackGuarantee(random, account.dailyBuyback, eligiblePool, data.buybackGuarantee);
        }
    }

    private static boolean hasRarity(List<ConfiguredOffer> pool, Rarity rarity) {
        for (ConfiguredOffer offer : pool) {
            if (offer.rarity == rarity && !offer.stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static Rarity rollRarity(RandomSource random, List<Rarity> available) {
        int total = 0;
        for (Rarity rarity : available) {
            total += Math.max(0, rarity.weight());
        }
        if (total <= 0) {
            return available.get(0);
        }
        int roll = random.nextInt(total);
        int cursor = 0;
        for (Rarity rarity : available) {
            cursor += Math.max(0, rarity.weight());
            if (roll < cursor) {
                return rarity;
            }
        }
        return available.get(available.size() - 1);
    }

    @Nullable
    private static ConfiguredOffer pick(RandomSource random, List<ConfiguredOffer> list) {
        if (list.isEmpty()) {
            return null;
        }
        return list.get(random.nextInt(list.size()));
    }

    /** 全局每日清理：摊位到期，未售出物品经邮箱退还。 */
    /** 邮件到期清理的最小间隔：邮件有效期按天计，分钟级精度足够，没必要每秒扫一遍全服邮箱。 */
    private static final long MAIL_PURGE_INTERVAL_MS = 60_000L;
    private static long lastMailPurgeMs;

    /**
     * 第七轮：按单封邮件的到期时间清理（未领取也删，与鸣潮邮箱一致）。
     * <p>
     * 全局兜底 {@link Config#mailExpireDays} 仍由旧逻辑处理「已领取/已查看」的邮件，互不冲突。
     */
    public static void purgeExpiredMails(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now - lastMailPurgeMs < MAIL_PURGE_INTERVAL_MS) {
            return;
        }
        lastMailPurgeMs = now;
        ShopSavedData data = data(server);
        boolean changed = false;
        for (PlayerAccount account : data.accounts().values()) {
            if (account.mailbox.isEmpty()) {
                continue;
            }
            int before = account.mailbox.size();
            account.mailbox.removeIf(mail -> mail.expireAt > 0L && mail.expireAt <= now && !mail.starred);
            if (account.mailbox.size() != before) {
                changed = true;
            }
        }
        if (changed) {
            data.setDirty();
        }
    }

    /**
     * 第七轮：用模板草稿发信（标题/正文/类型/到期/星标 + 附件一并入库）。
     * 旧调用点继续走 addMail/addMoneyMail，行为不变。
     */
    public static void addMailDraft(MinecraftServer server, @Nullable UUID owner, MailTemplates.DraftMail draft) {
        if (server == null || owner == null || draft == null || !draft.valid()) {
            return;
        }
        HolderLookup.Provider regs = server.registryAccess();
        long receivedDay = currentDay(server.overworld());
        // 第八轮：一次发放只发**一封**邮件（所有物品装在同一封里），并在 storeMail 里与同渠道未领取的邮件继续合并，
        // 避免"一件物品一封邮件"把邮箱塞满。
        List<ItemStack> draftItems = draft.items();
        if (!draftItems.isEmpty()) {
            MailEntry entry = MailEntry.item(draftItems.get(0).copy(), draft.source(), draft.sender(), null, receivedDay);
            for (int i = 1; i < draftItems.size(); i++) {
                entry.addStack(draftItems.get(i).copy());
            }
            applyDraft(entry, draft, regs);
            storeMail(server, owner, entry);
            // 事件：邮件已创建（成功后发布；监听器异常不影响发信）
            cn.autoforged.module_shop.api.event.ApiEvents.post(
                    new cn.autoforged.module_shop.api.event.MailCreatedEvent(
                            owner, draft.source(), draft.money(),
                            draft.items() == null ? 0 : draft.items().size(), draft.title()));
        }
        if (draft.money() > 0L) {
            MailEntry entry = MailEntry.money(draft.money(), draft.moneyDisplayStack(), draft.source(),
                    draft.sender(), null, receivedDay);
            applyDraft(entry, draft, regs);
            storeMail(server, owner, entry);
            // 事件：邮件已创建（成功后发布；监听器异常不影响发信）
            cn.autoforged.module_shop.api.event.ApiEvents.post(
                    new cn.autoforged.module_shop.api.event.MailCreatedEvent(
                            owner, draft.source(), draft.money(),
                            draft.items() == null ? 0 : draft.items().size(), draft.title()));
        }
    }

    private static void applyDraft(MailEntry entry, MailTemplates.DraftMail draft, HolderLookup.Provider regs) {
        entry.title = draft.title();
        entry.content = draft.content();
        entry.kind = draft.kind().name().toLowerCase(java.util.Locale.ROOT);
        entry.expireAt = draft.expireAt();
        entry.starred = draft.starred();
    }

    /**
     * 第八轮：同一渠道的邮件合并 —— 来源 / 标题 / 正文 / 类型 / 业务类型完全相同、
     * 且还**没领取**的邮件，新的物品或金额直接并进那一封，不再新开一封。
     *
     * @return 被合并进去的那封邮件；没有可合并对象时返回 null。
     */
    @Nullable
    private static MailEntry mergeInto(PlayerAccount account, MailEntry entry) {
        if (entry.type == MailType.ITEM && !entry.hasItems()) {
            return null;
        }
        if (entry.type == MailType.MONEY && entry.currencyAmount <= 0L) {
            return null;
        }
        for (MailEntry mail : account.mailbox) {
            if (mail.type != entry.type || !sameText(mail.source, entry.source) || !sameText(mail.kind, entry.kind)
                    || !sameText(mail.title, entry.title) || !sameText(mail.content, entry.content)) {
                continue;
            }
            boolean settled = mail.type == MailType.MONEY ? mail.isViewed : mail.isClaimed;
            if (settled) {
                continue;
            }
            if (mail.type == MailType.ITEM) {
                for (ItemStack stack : entry.itemStacks()) {
                    mail.addStack(stack.copy());
                }
            } else {
                long sum = mail.currencyAmount + entry.currencyAmount;
                mail.currencyAmount = sum < 0L ? Long.MAX_VALUE : sum;
            }
            return mail;
        }
        return null;
    }

    private static boolean sameText(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static void storeMail(MinecraftServer server, UUID owner, MailEntry entry) {
        PlayerAccount account = data(server).account(owner);
        ServerPlayer online = server.getPlayerList().getPlayer(owner);
        // 同一渠道且未领取 → 合并进已有邮件（合并不占新格子，所以放在容量检查之前）。
        MailEntry merged = mergeInto(account, entry);
        if (merged != null) {
            data(server).setDirty();
            if (online != null) {
                notifyPlayer(online, Component.translatable("msg.module_shop.mail_merged", merged.displayName())
                        .withStyle(ChatFormatting.GREEN));
                sendSnapshot(online, "mail");
            }
            return;
        }
        int cap = Config.INSTANCE.maxMailboxSize.get();
        if (account.mailbox.size() >= cap && !ensureMailRoom(server, account, online)) {
            // 邮箱满且没有已读邮件可清理：金额直接到账（装不下的留在邮箱），物品塞背包、再不行掉落。
            if (entry.type == MailType.MONEY) {
                long credited = credit(account, entry.currencyAmount, Config.maxBalanceLimit());
                long rest = entry.currencyAmount - credited;
                if (rest > 0L) {
                    entry.currencyAmount = rest;
                    account.mailbox.add(entry);
                }
            } else {
                // 物品：在线时先塞背包；背包放不下的（以及离线玩家的全部物品）**超额存进邮箱**。
                // 以前离线时这里什么都不做（物品凭空消失），在线时会丢在地上（可能被捡走或消失）。
                // 系统投递的资产宁可让邮箱暂时超过上限，也绝不销毁。
                MailEntry overflow = null;
                for (ItemStack stack : entry.itemStacks()) {
                    ItemStack remaining = stack.copy();
                    if (online != null) {
                        online.getInventory().add(remaining);
                    }
                    if (!remaining.isEmpty()) {
                        if (overflow == null) {
                            overflow = MailEntry.item(remaining, entry.source, entry.sender, null, entry.receivedDay);
                            overflow.title = entry.title;
                            overflow.content = entry.content;
                            overflow.kind = entry.kind;
                            overflow.starred = entry.starred;
                        } else {
                            overflow.addStack(remaining);
                        }
                    }
                }
                if (overflow != null) {
                    account.mailbox.add(overflow);
                    NotificationManager.notify(server, owner, NotificationType.MAIL);
                    if (online != null) {
                        notifyPlayer(online, Component.translatable("msg.module_shop.mailbox_overflow")
                                .withStyle(ChatFormatting.GOLD));
                    }
                }
            }
            data(server).setDirty();
            return;
        }
        account.mailbox.add(entry);
        data(server).setDirty();
        if (online != null) {
            notifyPlayer(online, Component.translatable("msg.module_shop.mail_received", entry.displayName()));
            sendSnapshot(online, "mail");
        }
    }

    /**
     * 第七轮：邮箱满时先腾位置 —— 自动删除「最早的一封已读邮件」（金额看 isViewed、物品看 isClaimed）。
     * 返回 true 表示已有空间（或本来就够）。没有任何已读邮件时返回 false，并提示玩家。
     */
    private static boolean ensureMailRoom(MinecraftServer server, PlayerAccount account, ServerPlayer online) {
        int cap = Config.INSTANCE.maxMailboxSize.get();
        if (account.mailbox.size() < cap) {
            return true;
        }
        MailEntry oldest = null;
        for (MailEntry mail : account.mailbox) {
            boolean read = mail.type == MailType.MONEY ? mail.isViewed : mail.isClaimed;
            // 星标邮件不参与自动清理（玩家手动标记的重要邮件优先保留）。
            if (!read || mail.starred) {
                continue;
            }
            if (oldest == null || mail.sentAt < oldest.sentAt) {
                oldest = mail;
            }
        }
        if (oldest == null) {
            if (online != null) {
                notifyPlayer(online, Component.translatable("msg.module_shop.mailbox_full_no_read", cap)
                        .withStyle(ChatFormatting.RED));
            }
            return false;
        }
        account.mailbox.remove(oldest);
        data(server).setDirty();
        if (online != null) {
            String name = oldest.displayName();
            notifyPlayer(online, Component.translatable("msg.module_shop.mailbox_full_auto_delete", name)
                    .withStyle(ChatFormatting.YELLOW));
            sendSnapshot(online, "mail");
        }
        return true;
    }

    /** 每日例行清理：按单封邮件到期时间 + 跨日清理已读/已领过期邮件（玩家摊位到期统一由 {@link #tickStallExpire} 按配置执行）。 */
    public static void tickDaily(MinecraftServer server) {
        if (server == null) {
            return;
        }
        purgeExpiredMails(server);
        synchronized (LOCK) {
            ShopSavedData data = data(server);
            long day = currentDay(server.overworld());
            if (data.lastStallClearDay == Long.MIN_VALUE) {
                data.lastStallClearDay = day;
                data.setDirty();
                return;
            }
            if (!ShopExpireSupport.shouldAdvanceDailyDay(day, data.lastStallClearDay)) {
                return;
            }
            data.lastStallClearDay = day;
            int expireDays = Config.INSTANCE.mailExpireDays.get();
            if (expireDays > 0) {
                for (Map.Entry<UUID, PlayerAccount> entry : new ArrayList<>(data.accounts().entrySet())) {
                    PlayerAccount account = entry.getValue();
                    // 只清理“已经领取/查看过”的邮件：未领取的物品、未查看的货款不能因为过期直接蒸发。
                    // receivedDay<=0 表示旧存档缺这个字段，按“刚收到”处理，避免一开过期功能就清空历史邮件。
                    // 标星的邮件永不自动过期（和「一键清理」「邮箱满了自动删」保持同一规则）。
                    account.mailbox.removeIf(mail -> !mail.pending()
                            && !mail.starred
                            && mail.receivedDay > 0
                            && day - mail.receivedDay >= expireDays);
                }
            }
            data.setDirty();
        }
    }

    /** 拍卖结算。 */
    public static void tickAuctions(MinecraftServer server) {
        // 到期结算、即将结束提醒、增量推送都在 AuctionService（结算幂等，重启后到期未结算的拍卖照常结算）。
        cn.autoforged.module_shop.shop.auction.AuctionService.tick(server);
    }

    // =========================================================== 快照与输入界面

    public static void sendSnapshot(ServerPlayer player, @Nullable String tab) {
        // ⚠ 默认**不自动打开界面**：以前是"客户端没开界面就弹出来"，于是关掉商店后，
        // 任何一次数据推送（午夜刷新、邮件合并、自动签到发奖…）都会把界面又弹回来，玩家会以为"关不掉"。
        sendSnapshot(player, tab, false, false);
    }

    /** 只有**玩家自己主动请求**打开界面时才用它（/shop 等指令）——会真的把界面弹出来。 */
    public static void sendSnapshotOpen(ServerPlayer player, @Nullable String tab) {
        sendSnapshot(player, tab, false, true);
    }

    /**
     * 下发快照。silent=true 时客户端只更新缓存；open=true 才会在客户端没开界面时自动弹出商店。
     */
    public static void sendSnapshot(ServerPlayer player, @Nullable String tab, boolean silent) {
        sendSnapshot(player, tab, silent, false);
    }

    /** 已发给各在线玩家的购物城货架指纹：未变化时快照直接带 mallUnchanged=true，避免每次开商店/动钱重传上万件商品。 */
    private static final Map<UUID, Long> LAST_SENT_MALL_HASH = new java.util.concurrent.ConcurrentHashMap<>();

    private static long mallViewHash(ShopSavedData data, UUID viewer, boolean personalStock) {
        long h = 1125899906842597L;
        h = 31L * h + (personalStock ? 1L : 0L);
        h = 31L * h + data.mallPool.size();
        for (MallItem item : data.mallPool) {
            h = 31L * h + System.identityHashCode(item);
            h = 31L * h + System.identityHashCode(item.stack);
            h = 31L * h + item.stack.getCount();
            h = 31L * h + item.stock;
            h = 31L * h + item.stockMax;
            h = 31L * h + item.sort;
            h = 31L * h + item.rarity.ordinal();
            h = 31L * h + (item.permanentLimit ? 1L : 0L);
            h = 31L * h + item.resolvePriceSpec().hashCode();
            if (personalStock || item.permanentLimit) {
                h = 31L * h + item.remainingFor(viewer);
            }
        }
        return h == 0L ? 1L : h;
    }

    private static void sendSnapshot(ServerPlayer player, @Nullable String tab, boolean silent, boolean open) {
        ConfigMigration.runOnce();
        logBalanceCapOnce();
        PlayerAccount account = account(player);
        ShopSavedData data = data(player.server);
        loadBrandDefaultIfBlank(data.sidebarLayout);
        ensureDaily(player, account, data);
        // 需求 3：价格始终以服务端商品池为权威，下发前用最新池数据刷新每日报价。
        refreshDailyPrices(account, data);
        HolderLookup.Provider regs = player.level().registryAccess();
        boolean ownedBatch = ShopNbt.beginBatchIfAbsent(regs);
        CompoundTag tag;
        try {
        tag = new CompoundTag();
        tag.putString("kind", "snapshot");
        tag.putBoolean("silent", silent);
        // open=true 才允许客户端"自动弹出商店界面"（默认 false：只更新数据，绝不再抢夺玩家的界面）
        tag.putBoolean("open", open);
        tag.putLong("balance", account.balance);
        // 第九项：把余额上限也下发，客户端余额到顶时可显示「已满」。
        tag.putLong("maxBalance", Config.maxBalanceForClient());
        // 编辑布局时：货币名用草稿里的（还没发布），其他玩家仍看正式值。
        String currencyText = currencyFor(data, player);
        tag.putString("currency", currencyText);
        tag.putString("symbol", Config.INSTANCE.currencySymbol.get());
        // 需求 6：下发统一金钱后缀（如“星币”），客户端所有金额显示都用它。
        tag.putString("moneySuffix", currencyText);
        boolean snapshotAdmin = player.hasPermissions(2);
        tag.putBoolean("canAdmin", snapshotAdmin);
        tag.putBoolean("adminLockEnabled", AdminAuthService.isLockEnabled());
        tag.putBoolean("adminUnlocked", AdminAuthService.canEditAdmin(player));
        tag.putInt("adminSessionTimeout", AdminAuthService.sessionTimeoutMinutes());
        // 需求 11：下发服务端当前记录的编辑模块，界面据此高亮“每日商店/每日收购”并显示当前模式。
        tag.putString("adminEditType",
                ADMIN_EDIT_TYPE.getOrDefault(player.getUUID(), AdminEditType.DAILY_SHOP).id());
        tag.putString("defaultTab", (tab == null || tab.isBlank()) ? "home" : tab);
        // 主界面左上角需要显示玩家名称
        tag.putString("playerName", player.getGameProfile().getName());
        // 需求 4：交易界面需要展示在线玩家名片
        ListTag online = new ListTag();
        for (ServerPlayer other : player.server.getPlayerList().getPlayers()) {
            CompoundTag c = new CompoundTag();
            c.putString("uuid", other.getUUID().toString());
            c.putString("name", other.getGameProfile().getName());
            online.add(c);
        }
        tag.put("onlinePlayers", online);

        ListTag daily = new ListTag();
        for (DailyShopEntry entry : account.dailyShop) {
            CompoundTag c = ShopNbt.saveStack(entry.stack, regs);
            entry.resolvePriceSpec().writeToParent(c, "priceSpec", "price");
            c.putString("rarity", entry.rarity.id());
            c.putInt("bought", entry.bought);
            // 需求 11：上限按物品下发，不再使用品质公用上限。
            c.putInt("limit", entry.limit);
            daily.add(c);
        }
        tag.put("dailyShop", daily);

        // 付费刷新每日商店：refreshCost = -1 表示今日次数已用完（按钮变灰）。
        List<PriceSpec> shopRefreshList = data.resolveDailyRefreshSpecs();
        PriceSpec nextShopRefresh = data.nextRefreshSpec(account, true);
        tag.putInt("refreshUsed", account.dailyShopRefreshUsed);
        tag.putInt("refreshMax", shopRefreshList.size());
        tag.putLong("refreshCost", nextShopRefresh == null ? -1L
                : (nextShopRefresh.supportsStar() ? nextShopRefresh.starPrice : 0L));
        if (nextShopRefresh != null) {
            tag.put("refreshSpec", nextShopRefresh.toTag());
        }
        tag.put("dailyRefreshSpecs", writeSpecList(shopRefreshList));
        // 付费刷新每日收购：与商店**完全分开**的次数与价格。
        List<PriceSpec> bbRefreshList = data.resolveBuybackRefreshSpecs();
        PriceSpec nextBbRefresh = data.nextRefreshSpec(account, false);
        tag.putInt("buybackRefreshUsed", account.dailyBuybackRefreshUsed);
        tag.putInt("buybackRefreshMax", bbRefreshList.size());
        tag.putLong("buybackRefreshCost", nextBbRefresh == null ? -1L
                : (nextBbRefresh.supportsStar() ? nextBbRefresh.starPrice : 0L));
        if (nextBbRefresh != null) {
            tag.put("buybackRefreshSpec", nextBbRefresh.toTag());
        }
        tag.put("buybackRefreshSpecs", writeSpecList(bbRefreshList));

        int[] flipped = new int[account.dailyFlipped.size()];
        for (int i = 0; i < flipped.length; i++) {
            flipped[i] = account.dailyFlipped.get(i);
        }
        tag.putIntArray("dailyFlipped", flipped);

        ListTag buyback = new ListTag();
        for (DailyBuybackEntry entry : account.dailyBuyback) {
            CompoundTag c = ShopNbt.saveStack(entry.stack, regs);
            entry.resolvePriceSpec().writeToParent(c, "priceSpec", "price");
            c.putString("rarity", entry.rarity.id());
            c.putInt("sold", entry.sold);
            // 需求 6/9：按物品下发可收购数量。
            c.putInt("limit", entry.limit);
            buyback.add(c);
        }
        tag.put("dailyBuyback", buyback);

        // 收购全清奖励：界面据此显示「全部完成可再得 X（还剩 N 项）」。
        PriceSpec bonusSpec = buybackBonusSpec(data);
        bonusSpec.writeToParent(tag, "buybackBonusSpec", "buybackBonus");
        tag.putBoolean("buybackBonusEnabled", data.buybackBonusEnabled);
        tag.putBoolean("buybackBonusDone", account.dailyBuybackBonusDone);
        int buybackLeft = 0;
        for (DailyBuybackEntry entry : account.dailyBuyback) {
            if (entry.remaining() > 0) {
                buybackLeft++;
            }
        }
        tag.putInt("buybackLeft", buybackLeft);

        // 拍卖列表不再随每次快照全量下发：玩家打开拍卖页时单独拉取（auction_list），之后只收增量（auction_delta）。
        // 这里只带版本号，客户端发现版本变了且正在看拍卖页时再去拉。
        tag.putLong("auctionVersion", data.auctionVersion);

        ListTag stall = new ListTag();
        for (StallEntry entry : account.stall) {
            CompoundTag c = ShopNbt.saveStack(entry.stack, regs);
            entry.resolvePriceSpec().writeToParent(c, "priceSpec", "price");
            // 需求 6：管理界面需要显示剩余库存/已售空。
            c.putInt("stock", entry.stock);
            // 需求 9：按商品统计的累计已售出件数。
            c.putInt("sold", entry.sold);
            // 商品 id（改价 / 补货 / 下架都按它定位，不受下标变化影响）
            c.putLong("order", entry.order);
            if (entry.listedGame > 0L) {
                c.putLong("listedGame", entry.listedGame);
            }
            if (entry.listedReal > 0L) {
                c.putLong("listedReal", entry.listedReal);
            }
            stall.add(c);
        }
        tag.put("stall", stall);
        // 玩家商店：大厅数据的版本号（变化了才重新拉取）、库存上限与上架费率（上架界面预览用，以服务端校验为准）
        tag.putLong("stallVersion", cn.autoforged.module_shop.shop.stall.StallService.marketHash(data));
        tag.putInt("stallMaxStock", Config.INSTANCE.stallMaxStock.get());
        tag.putInt("stallSlots", Config.INSTANCE.stallSlots.get());
        tag.putDouble("stallFeePercent", Config.INSTANCE.stallListingFeePercent.get());

        ListTag stallPlayers = new ListTag();
        List<Map.Entry<UUID, PlayerAccount>> stalls = new ArrayList<>();
        for (Map.Entry<UUID, PlayerAccount> entry : data.accounts().entrySet()) {
            if (!entry.getValue().stall.isEmpty()) {
                stalls.add(entry);
            }
        }
        stalls.sort(Comparator.comparingLong((Map.Entry<UUID, PlayerAccount> e) -> e.getValue().stall.get(0).order));
        for (Map.Entry<UUID, PlayerAccount> entry : stalls) {
            CompoundTag c = new CompoundTag();
            c.putString("uuid", entry.getKey().toString());
            c.putString("name", entry.getValue().name.isEmpty() ? "Unknown" : entry.getValue().name);
            c.putInt("count", entry.getValue().stall.size());
            stallPlayers.add(c);
        }
        tag.put("stallPlayers", stallPlayers);

        ListTag mail = new ListTag();
        // 需求：按时间戳倒序，最新的邮件排在最前。
        List<MailEntry> mails = new ArrayList<>(account.mailbox);
        mails.sort(Comparator.comparingLong((MailEntry m) -> m.sentAt).reversed());
        for (MailEntry entry : mails) {
            CompoundTag c = ShopNbt.saveStack(entry.stack, regs);
            c.putString("mailId", entry.mailId);
            c.putString("type", entry.type.id());
            c.putString("source", entry.source);
            // 需求 5：邮件需要显示发送者与时间戳。
            c.putString("sender", entry.sender);
            if (entry.senderUUID != null) {
                c.putUUID("senderUUID", entry.senderUUID);
            }
            c.putLong("currencyAmount", entry.currencyAmount);
            c.putBoolean("isClaimed", entry.isClaimed);
            c.putBoolean("isViewed", entry.isViewed);
            c.putLong("sentAt", entry.sentAt);
            c.putString("title", entry.title);
            c.putString("content", entry.content);
            c.putString("kind", entry.kind);
            c.putLong("expireAt", entry.expireAt);
            c.putBoolean("starred", entry.starred);
            // 第八轮：附件列表必须一起下发，否则客户端只看到第一件（旧的手写序列化漏了它）。
            ListTag stackList = new ListTag();
            for (ItemStack stack : entry.stacks) {
                if (stack != null && !stack.isEmpty()) {
                    stackList.add(ShopNbt.saveStack(stack, regs));
                }
            }
            c.put("stacks", stackList);
            mail.add(c);
        }
        tag.put("mail", mail);

        // 管理端列表只有管理员需要 —— 普通玩家不发（管理页他们打不开），
        // 顺带把快照体积砍掉一大半（购物城整池物品在 mall 与 adminMall 里本来是重复下发的）。
        if (snapshotAdmin) {
            tag.put("adminShop", offerList(data.shopPool, regs));
            tag.put("adminBuyback", offerList(data.buybackPool, regs));
        }

        // ---- 购物城（新增模块）：按指纹增量下发 + 复用单份列表，彻底消除上万件商品开商店/动钱卡顿 ----
        boolean personalStock = Config.mallStockPersonal();
        long mallHash = mallViewHash(data, player.getUUID(), personalStock);
        Long prevMallHash = LAST_SENT_MALL_HASH.get(player.getUUID());
        tag.putBoolean("mallStockPersonal", personalStock);
        if (prevMallHash != null && prevMallHash == mallHash) {
            tag.putBoolean("mallUnchanged", true);
        } else {
            ListTag mallList = new ListTag();
            for (int i = 0; i < data.mallPool.size(); i++) {
                MallItem item = data.mallPool.get(i);
                CompoundTag c = item.saveForView(regs, player.getUUID(), personalStock);
                c.putInt("index", i);
                mallList.add(c);
            }
            tag.put("mall", mallList);
            tag.putBoolean("adminMallFromMall", true);
            LAST_SENT_MALL_HASH.put(player.getUUID(), mallHash);
        }
        // 管理页的「出货箱」模块：把**兑换规则**按条目下发（代表物品 + 单价 + 每日份数），
        // 格式错误的那几行也在列表里（物品为空 + 原文与原因），界面把它们标红，右键即可删掉。
        ListTag adminShipList = new ListTag();
        ListTag adminShipRaw = new ListTag();
        ListTag adminShipErrors = new ListTag();
        List<ShippingRules.Entry> shipEntries = ShippingRules.entries();
        List<ItemStack> shipStacks = ShippingRules.ruleStacks();
        for (int i = 0; i < shipEntries.size(); i++) {
            ShippingRules.Entry entry = shipEntries.get(i);
            ItemStack stack = i < shipStacks.size() ? shipStacks.get(i) : ItemStack.EMPTY;
            CompoundTag c = ShopNbt.saveStack(stack, regs);
            PriceSpec ruleSpec = entry.rule() == null ? PriceSpec.ZERO : entry.rule().resolvePriceSpec();
            ruleSpec.writeToParent(c, "priceSpec", "price");
            c.putString("rarity", Rarity.COMMON.id());
            c.putInt("amount", entry.rule() == null ? 0 : entry.rule().dailyMax());
            c.putString("rule", entry.raw());
            adminShipList.add(c);
            adminShipRaw.add(StringTag.valueOf(entry.raw()));
            adminShipErrors.add(StringTag.valueOf(entry.error() == null ? "" : entry.error()));
        }
        tag.put("adminShipping", adminShipList);
        tag.put("adminShippingRaw", adminShipRaw);
        tag.put("adminShippingErrors", adminShipErrors);
        // 最近购买：最新在前，最多下发 MALL_RECENT_SEND 条（存档最多留 200 条）。
        ListTag recentList = new ListTag();
        int from = Math.max(0, data.mallRecent.size() - MALL_RECENT_SEND);
        for (int i = data.mallRecent.size() - 1; i >= from; i--) {
            recentList.add(data.mallRecent.get(i).save(regs));
        }
        tag.put("mallRecent", recentList);

        // ---- 出货箱（新增模块）：等级 / 格数 / 本次结算金额 / 今日额度 / 规则状态 ----
        sendShippingInfo(player, tag, data);

        // 货币兑换：所有玩家都能看到汇率、图标、持有量与每日限额进度，仅管理员能改配置。
        PlayerAccount exAccount = account(player);
        long exDay = realDay();
        ListTag exchange = new ListTag();
        for (ExchangeEntry entry : data.exchangePool) {
            CompoundTag c = ShopNbt.saveStack(entry.stack, regs);
            c.putLong("value", entry.value);
            c.putInt("owned", countMatching(player, entry.stack));
            String qKey = entry.quotaKey(regs);
            c.putInt("dailyDepositLimit", entry.dailyDepositLimit);
            c.putInt("depositUsedToday", exAccount.depositUsedFor(qKey, exDay));
            c.putInt("dailyWithdrawLimit", entry.dailyWithdrawLimit);
            c.putInt("withdrawUsedToday", exAccount.withdrawUsedFor(qKey, exDay));
            exchange.add(c);
        }
        tag.put("exchange", exchange);

        // 签到（需求 7）：玩家自己的签到状态 + 全局设置（界面据此画进度条/日历）。
        SignInData sign = data.signIn(player.getUUID());
        long realDay = realDay();
        if (sign.signedDays.isEmpty() && sign.lastSignDay <= 0L) {
            sign.lastSignDay = 0L;
        }
        sign.trim();
        tag.putBoolean("signInEnabled", data.signInEnabled);
        tag.putBoolean("signInAuto", data.signInAuto);
        tag.putBoolean("signInCumulative", data.signInCumulative);
        data.resolveSignInBaseSpec().writeToParent(tag, "signInBaseSpec", "signInBase");
        data.resolveSignInMakeupSpec().writeToParent(tag, "signInMakeupSpec", "signInMakeupCost");
        tag.putInt("signInMakeupDays", data.signInMakeupDays);
        tag.putLong("signInToday", realDay);
        tag.putBoolean("signInDone", sign.signedOn(realDay));
        tag.putInt("signInStreak", sign.streak);
        tag.putInt("signInBest", sign.bestStreak);
        tag.putLongArray("signInDays", sign.recentDays(realDay));
        tag.putIntArray("signInTierDays", data.signInTierDays);
        tag.putLongArray("signInTierBonus", data.signInTierBonus);
        List<PriceSpec> resolvedTierSpecs = new ArrayList<>(data.signInTierBonusSpecs.length);
        for (int i = 0; i < data.signInTierBonusSpecs.length; i++) {
            resolvedTierSpecs.add(data.resolveSignInTierSpec(i));
        }
        tag.put("signInTierBonusSpecs", writeSpecList(resolvedTierSpecs));
        tag.putIntArray("signInMileDays", data.signInMileDays);
        tag.putLongArray("signInMileMoney", data.signInMileMoney);
        List<PriceSpec> resolvedMileSpecs = new ArrayList<>(data.signInMileSpecs.length);
        for (int i = 0; i < data.signInMileSpecs.length; i++) {
            resolvedMileSpecs.add(data.resolveSignInMileSpec(i));
        }
        tag.put("signInMileSpecs", writeSpecList(resolvedMileSpecs));
        // 里程轮次长度：客户端据此把进度条按「本轮第几天」画，而不是按总连续天数（那样到 7 天就永远满格）。
        tag.putInt("signInCycleDays", SIGNIN_CYCLE_DAYS);
        ListTag actList = new ListTag();
        for (ActivityDay act : data.activityDays.values()) {
            CompoundTag c = new CompoundTag();
            c.putLong("day", act.day);
            c.putString("title", act.title);
            c.putString("note", act.note);
            act.resolveMoneySpec().writeToParent(c, "moneySpec", "money");
            c.putBoolean("forOffline", act.forOffline);
            c.putBoolean("granted", act.granted.contains(player.getUUID()));
            c.putBoolean("catchUp", act.catchUp);
            // 第八轮：奖励物品改成列表，item 字段直接下发「全部奖励物品」的文本（界面显示用），
            // itemIds 下发可解析的 id 列表，客户端用来画物品图标。
            c.putString("item", MailTemplates.itemsText(act.rewardStacks()));
            c.putString("itemIds", rewardIdsText(act.rewardStacks()));
            c.putInt("color", act.color);
            c.putInt("hour", act.hour);
            c.putString("gift", "");
            actList.add(c);
        }
        tag.put("activityDays", actList);
        // 侧栏布局：管理员拖过之后的所有玩家都用这套坐标（服务端权威 + 热同步）。
        // 编辑布局中的管理员拿到的是**草稿**布局，其他玩家仍是已发布的那份。
        SidebarLayout activeLayout = layoutFor(data, player.getUUID());
        tag.put("sidebarLayout", activeLayout.save());
        tag.putString("brandIconId", activeLayout.brandIconId());
        // 需求 8：下发刷新参数（管理员设置界面据此回填）。
        tag.putIntArray("shopWeights", data.shopWeights);
        tag.putIntArray("shopGuarantee", data.shopGuarantee);
        tag.putInt("shopSlotCount", data.shopSlotCount);
        tag.putIntArray("buybackWeights", data.buybackWeights);
        tag.putIntArray("buybackGuarantee", data.buybackGuarantee);
        tag.putInt("buybackSlotCount", data.buybackSlotCount);
        // 购物城定时补货设置（管理页「补货设置」弹窗据此回填；普通玩家用来显示"每日补货"字样）。
        tag.putBoolean("mallRestockOn", Config.mallRestockEnabled());
        tag.putString("mallRestockMode", Config.mallRestockGameTime() ? "game" : "real");
        tag.putString("mallRestockTime", Config.mallRestockTime());
        tag.putInt("mallRestockAmount", Config.mallRestockAmount());
        tag.putString("mallRefreshMode", Config.mallRefreshMode());
        tag.putInt("mallRefreshAmount", Config.mallRefreshAmount());
        tag.putBoolean("mallStockPersonal", Config.mallStockPersonal());
        // 摊位下架时间（客户端据此显示每个商品的剩余时间 + 管理弹窗回填）
        tag.putString("stallExpireMode", Config.stallExpireMode());
        tag.putInt("stallExpireAmount", Config.stallExpireAmount());
        // 玩家求购：配置 + 全部求购单（列表 / 详情 / 倒计时 / 管理页都用它）
        tag.putBoolean("demandEnabled", Config.demandEnabled());
        tag.putInt("demandSlots", Config.demandSlots());
        tag.putDouble("demandFeePercent", Config.demandFeePercent());
        tag.putLong("demandMaxReward", Config.demandMaxReward());
        tag.putString("demandExpireMode", Config.demandExpireMode());
        tag.putInt("demandExpireAmount", Config.demandExpireAmount());
        ListTag demandList = new ListTag();
        for (DemandEntry entry : data.demands) {
            demandList.add(entry.save(regs));
        }
        tag.put("demands", demandList);
        // 个人主页（在线时长 / LuckPerms 称号 / 公告）与分层实体货币配置/背包持币
        cn.autoforged.module_shop.shop.home.ProfileStatsService.writeSnapshot(player, tag);
        cn.autoforged.module_shop.shop.home.AnnouncementConfig.get().writeSnapshot(tag);
        cn.autoforged.module_shop.shop.currency.CurrencySettlement.writeSnapshot(player, tag, regs);
        } finally {
            if (ownedBatch) {
                ShopNbt.endBatch();
            }
        }

        ClientboundShopSyncPayload.send(player, tag);
    }

    private static ListTag writeSpecList(List<PriceSpec> specs) {
        ListTag list = new ListTag();
        if (specs != null) {
            for (PriceSpec spec : specs) {
                list.add((spec == null ? PriceSpec.ZERO : spec).toTag());
            }
        }
        return list;
    }

    private static ListTag writeStarSpecArray(long[] amounts) {
        ListTag list = new ListTag();
        if (amounts != null) {
            for (long amount : amounts) {
                list.add(PriceSpec.star(Math.max(0L, amount)).toTag());
            }
        }
        return list;
    }

    private static ListTag offerList(List<ConfiguredOffer> pool, HolderLookup.Provider regs) {
        ListTag list = new ListTag();
        for (ConfiguredOffer offer : pool) {
            CompoundTag c = ShopNbt.saveStack(offer.stack, regs);
            offer.resolvePriceSpec().writeToParent(c, "priceSpec", "price");
            c.putString("rarity", offer.rarity.id());
            // 需求 6/7：管理员配置界面上要能看到并修改数量/品质/价值。
            c.putInt("amount", offer.amount);
            list.add(c);
        }
        return list;
    }

    /**
     * 需求 1/3：每日商店与收购的价格、品质、数量始终跟随管理员配置的服务器商品池，
     * 管理员在界面改完（每日商店同样可自定义）后无需重启即可同步。
     */
    private static void refreshDailyPrices(PlayerAccount account, ShopSavedData data) {
        for (DailyShopEntry entry : account.dailyShop) {
            ConfiguredOffer match = findOffer(data.shopPool, entry.stack);
            if (match != null) {
                entry.setPriceSpec(match.resolvePriceSpec());
                entry.rarity = match.rarity;
                entry.limit = Math.max(1, match.amountOr(match.rarity.purchaseLimit()));
            }
        }
        for (DailyBuybackEntry entry : account.dailyBuyback) {
            ConfiguredOffer match = findOffer(data.buybackPool, entry.stack);
            if (match != null) {
                entry.setPriceSpec(match.resolvePriceSpec());
                entry.rarity = match.rarity;
                entry.limit = Math.max(1, match.amountOr(Config.INSTANCE.buybackCapacity.get()));
            }
        }
    }

    @Nullable
    private static ConfiguredOffer findOffer(List<ConfiguredOffer> pool, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        for (ConfiguredOffer offer : pool) {
            if (!offer.stack.isEmpty() && ItemStack.isSameItemSameTags(offer.stack, stack)) {
                return offer;
            }
        }
        return null;
    }

    /**
     * 需求 2：管理员在本地改完商品后执行 /shop reload，即可让服务器与所有在线玩家同步，
     * 无需重启。会按最新商品池重新生成每日列表并静默推送快照（不打断玩家当前界面）。
     */
    /**
     * 重置全服所有已知玩家（含在线与离线账户）的当日商店 / 收购生成标记，并广播快照使在线玩家立即重抽生效。
     */
    public static void resetAccountsDaily(java.util.Collection<PlayerAccount> accounts,
                                          boolean resetShop, boolean resetBuyback) {
        if (accounts == null || (!resetShop && !resetBuyback)) {
            return;
        }
        for (PlayerAccount acc : accounts) {
            if (resetShop) {
                acc.dailyShopDay = Long.MIN_VALUE;
                acc.dailyShop.clear();
                acc.dailyFlipped.clear();
            }
            if (resetBuyback) {
                acc.dailyBuybackDay = Long.MIN_VALUE;
                acc.dailyBuyback.clear();
            }
        }
    }

    public static void rerollDailyForAll(@Nullable MinecraftServer server, @Nullable ShopSavedData data,
                                         boolean resetShop, boolean resetBuyback) {
        if (data == null || (!resetShop && !resetBuyback)) {
            return;
        }
        if (server != null) {
            for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                account(online);
            }
        }
        resetAccountsDaily(data.accounts().values(), resetShop, resetBuyback);
        data.setDirty();
        if (server != null) {
            broadcastSnapshots(server);
        }
    }

    public static void reload(MinecraftServer server) {
        LAST_SENT_MALL_HASH.clear();
        cn.autoforged.module_shop.shop.home.AnnouncementConfig.reload();
        cn.autoforged.module_shop.shop.currency.PhysicalCurrencyConfig.reload();
        AdminAuthService.reload();
        ShippingRules.refresh();
        ShopSavedData data = data(server);
        loadBrandDefaultIfBlank(data.sidebarLayout);
        rerollDailyForAll(server, data, true, true);
        flushBroadcastSnapshots(server);
    }

    /** 本 tick 内是否有人请求过全服快照（见 {@link #broadcastSnapshots}）。只在服务端主线程读写。 */
    private static boolean broadcastPending;

    /**
     * 静默地把最新快照推送给所有在线玩家，保证摊位等展示数据实时同步。
     *
     * <p>⚠ 不再立刻发送，只打一个标记，由 {@link #flushBroadcastSnapshots} 在本 tick 结束时统一发一次：
     * 一份完整快照包含整个购物城货架（可达数千件物品），每个在线玩家都要单独序列化 + gzip，
     * 同一 tick 里多次调用（例如摊位到期、求购到期、库存刷新恰好同时触发，或一次操作里连调两次）
     * 以前会把这笔开销翻几倍。客户端收到静默快照只更新数据、不切界面，延后不超过 1 tick，玩家无感。
     */
    public static void broadcastSnapshots(MinecraftServer server) {
        if (server == null) {
            return;
        }
        broadcastPending = true;
    }

    /** 由 {@code ShopServerEvents} 在每个服务端 tick 末尾调用：把本 tick 合并起来的全服快照一次性发出。 */
    public static void flushBroadcastSnapshots(MinecraftServer server) {
        if (!broadcastPending || server == null) {
            return;
        }
        broadcastPending = false;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }
        // 同一次广播里，货架 / 求购 / 拍卖等共享物品只编码一次（见 ShopNbt.beginBatch）。
        ShopNbt.beginBatch(server.registryAccess());
        try {
            for (ServerPlayer player : new ArrayList<>(players)) {
                sendSnapshot(player, null, true);
            }
        } finally {
            ShopNbt.endBatch();
        }
    }

    /** 全服聊天公告（求购发布这类要让所有人看到的消息）。 */
    static void broadcastChat(MinecraftServer server, Component message) {
        if (server == null || message == null) {
            return;
        }
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            online.sendSystemMessage(message);
        }
    }

    public static void openInput(ServerPlayer player, String action, String titleKey, @Nullable CompoundTag extra, String... fields) {
        openInput(player, action, titleKey, extra, null, fields);
    }

    /**
     * 打开输入界面（可带标题参数）。
     * titleArgs 会作为 {@code Component.translatable(titleKey, args...)} 的参数在客户端本地化，
     * 例如「取款数量（星币 → 银币）」里的货币名就用它传。
     */
    public static void openInput(ServerPlayer player, String action, String titleKey, @Nullable CompoundTag extra,
            @Nullable String[] titleArgs, String... fields) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "input");
        tag.putString("action", action);
        tag.putString("title", titleKey);
        ListTag fieldList = new ListTag();
        for (String field : fields) {
            fieldList.add(StringTag.valueOf(field));
        }
        tag.put("fields", fieldList);
        if (titleArgs != null && titleArgs.length > 0) {
            ListTag argList = new ListTag();
            for (String arg : titleArgs) {
                argList.add(StringTag.valueOf(arg == null ? "" : arg));
            }
            tag.put("title_args", argList);
        }
        if (extra != null) {
            tag.merge(extra);
        }
        ClientboundShopSyncPayload.send(player, tag);
    }

    private static void openDeposit(ServerPlayer player, String context) {
        // 玩家行为（上架摊位 / 拍卖 / 交易放物品），**不能**要求管理员权限。
        if (player == null) {
            return;
        }
        openDeposit(player, context, false);
    }

    private static void openDeposit(ServerPlayer player, String context, boolean ghost) {
        DEPOSIT_CONTEXT.put(player.getUUID(), context);
        // 需求 2：交易槽位已满时锁定放入槽位，防止网络延迟下进入界面后仍能塞入物品。
        boolean locked = "trade".equals(context) && TradeManager.itemsFull(player);
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new DepositMenu(id, inventory, new SimpleContainer(1), ghost, locked),
                Component.translatable("gui.module_shop.deposit")));
    }

    /**
     * 管理页「添加条目」：打开**批量**幽灵槽界面（3×9 = 最多 27 种物品）。
     * 与单件添加一样，槽里只是模板副本，关闭时会原样退还。
     */
    private static void openBatchAdd(ServerPlayer player) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!requireUnlockedAdmin(player)) {
            return;
        }
        DEPOSIT_CONTEXT.put(player.getUUID(), "admin");
        AdminEditType type = ADMIN_EDIT_TYPE.getOrDefault(player.getUUID(), AdminEditType.DAILY_SHOP);
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new BatchAddMenu(id, inventory,
                        new SimpleContainer(BatchAddMenu.GHOST_SLOTS)),
                Component.translatable("gui.module_shop.batch.title",
                        Component.translatable(adminTypeNameKey(type)))));
    }

    /** 管理模块的显示名 lang key（与管理页下拉里的名字一致）。 */
    private static String adminTypeNameKey(AdminEditType type) {
        return switch (type) {
            case DAILY_SHOP -> "gui.module_shop.admin_shop";
            case BUYBACK -> "gui.module_shop.admin_buyback";
            case MALL -> "gui.module_shop.admin_mall";
            case SHIPPING -> "gui.module_shop.admin_shipping";
        };
    }

    /**
     * 批量添加第一步：把幽灵槽里的物品**复制成模板**存起来，然后关掉容器
     * （关掉时 {@link BatchAddMenu#removed} 会把原物退还），再打开统一设置价格 / 品质 / 数量的输入窗口。
     * <p>
     * 之所以先把模板存下来、而不是等保存时再读容器：打开输入窗口会换掉当前界面、容器随时可能被关掉，
     * 存成模板后就不依赖它是否还开着（与单件添加的 ADMIN_TEMPLATE 同一套思路）。
     */
    private static void adminBatchPrepare(ServerPlayer player) {
        if (!canEditAdmin(player)) {
            return;
        }
        if (!(player.containerMenu instanceof BatchAddMenu menu)) {
            return;
        }
        List<ItemStack> templates = new ArrayList<>();
        for (int i = 0; i < BatchAddMenu.GHOST_SLOTS; i++) {
            ItemStack ghost = menu.ghosts().getItem(i);
            if (!ghost.isEmpty()) {
                templates.add(ghost.copyWithCount(1));
            }
        }
        if (templates.isEmpty()) {
            notifyPlayer(player, Component.translatable("msg.module_shop.batch_none")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        BATCH_TEMPLATE.put(player.getUUID(), templates);
        // 关容器：幽灵槽里的原物由此退回玩家背包
        player.closeContainer();
        AdminEditType type = editType(player, null);
        CompoundTag extra = new CompoundTag();
        extra.putString("mode", type.id());
        if (type == AdminEditType.SHIPPING) {
            openInput(player, "admin_batch_apply", "gui.module_shop.input.shipping_rule_new", extra,
                    "price", "daily_max");
        } else if (type == AdminEditType.MALL) {
            ListTag permChoices = new ListTag();
            permChoices.add(StringTag.valueOf("0"));
            permChoices.add(StringTag.valueOf("1"));
            extra.put("choices_permanent_limit", permChoices);
            extra.putString("default_permanent_limit", "0");
            openInput(player, "admin_batch_apply", "gui.module_shop.input.admin_offer", extra,
                    "price", "rarity", "quantity", "permanent_limit");
        } else {
            openInput(player, "admin_batch_apply", "gui.module_shop.input.admin_offer", extra,
                    "price", "rarity", "quantity");
        }
    }

    /** 批量添加的模板（第一步存下，第二步统一设置数值时取用）。 */
    private static final Map<UUID, List<ItemStack>> BATCH_TEMPLATE = new HashMap<>();

    /**
     * 批量添加第二步：把模板里的每种物品按**统一的价格 / 品质 / 数量**加进当前模块的清单。
     * 出货箱支持单价与每日出货上限（没有品质）。
     */
    private static void adminBatchApply(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        List<ItemStack> templates = BATCH_TEMPLATE.remove(player.getUUID());
        if (templates == null || templates.isEmpty()) {
            notifyPlayer(player, Component.translatable("msg.module_shop.batch_none")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        AdminEditType type = editType(player, tag);
        PriceSpec priceSpec = parseAdminSpec(player, tag);
        if (priceSpec == null) {
            return;
        }
        int shippingDailyMax = 0;
        if (type == AdminEditType.SHIPPING) {
            shippingDailyMax = parseOptionalNonNegativeInt(player, tag, "daily_max");
            if (shippingDailyMax < 0) {
                return;
            }
        }
        long rarityHint = priceSpec.supportsStar() ? priceSpec.starPrice : Math.max(1L, priceSpec.physCount);
        Rarity rarity = parseRarity(tag.getString("rarity"), rarityHint);
        int quantity = parseInt(tag.getString("quantity"));
        ShopSavedData data = data(player.server);
        List<ItemStack> shippingRules = new ArrayList<>();
        int added = 0;
        for (ItemStack template : templates) {
            if (template.isEmpty()) {
                continue;
            }
            switch (type) {
                case MALL -> {
                    MallItem item = new MallItem(template, priceSpec, rarity, parseMallStock(tag.getString("quantity")));
                    item.sort = data.mallPool.size();
                    item.permanentLimit = isPermanentLimitFlag(tag);
                    data.mallPool.add(item);
                }
                case SHIPPING -> shippingRules.add(template);
                case DAILY_SHOP -> {
                    ConfiguredOffer offer = new ConfiguredOffer(template, priceSpec, rarity);
                    offer.amount = quantity;
                    data.shopPool.add(offer);
                }
                case BUYBACK -> {
                    ConfiguredOffer offer = new ConfiguredOffer(template, priceSpec, rarity);
                    offer.amount = quantity;
                    data.buybackPool.add(offer);
                }
            }
            added++;
        }
        if (type == AdminEditType.SHIPPING && !shippingRules.isEmpty()) {
            // 规则一次写完再落盘（避免 27 次重写配置文件）
            ShippingRules.addRules(shippingRules, priceSpec, shippingDailyMax);
        }
        if (added == 0) {
            notifyPlayer(player, Component.translatable("msg.module_shop.batch_none")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        data.setDirty();
        sendSnapshot(player, type == AdminEditType.MALL ? "mall" : "admin");
        broadcastSnapshots(player.server);
        notifyPlayer(player, Component.translatable("msg.module_shop.batch_added", added)
                .withStyle(ChatFormatting.GREEN));
    }

    // =========================================================== 动作分发

    public static void handleAction(ServerPlayer player, CompoundTag tag) {
        // 兜底：任何一个分支抛异常都不应该让玩家"点了没反应"。
        // 这里统一捕获并记录（含动作名），同时给玩家一条提示，便于排查。
        try {
            handleActionInner(player, tag);
        } catch (Throwable error) {
            String action = tag == null ? "?" : tag.getString("action");
            org.slf4j.LoggerFactory.getLogger("module_shop").error("处理动作 {} 失败（player={}）", action,
                    player == null ? "null" : player.getGameProfile().getName(), error);
            if (player != null) {
                notifyPlayer(player, Component.translatable("msg.module_shop.action_failed")
                        .withStyle(ChatFormatting.RED));
            }
        }
    }

    private static void handleActionInner(ServerPlayer player, CompoundTag tag) {
        String action = tag.getString("action");
        if ("admin_auth_unlock".equals(action)) {
            adminAuthUnlock(player, tag);
            return;
        }
        if ("admin_auth_lock".equals(action)) {
            adminAuthLock(player);
            return;
        }
        if ("admin_auth_configure".equals(action)) {
            adminAuthConfigure(player, tag);
            return;
        }
        if ("media_fetch".equals(action)) {
            handleMediaFetch(player, tag);
            return;
        }
        if (action.startsWith("cfg_")) {
            if (!requireUnlockedAdmin(player)) {
                return;
            }
            if (TemplateTransferManager.handleServerAction(
                    player.server, player, data(player.server), action, tag)) {
                return;
            }
        }
        // 服务端权威：管理操作必须校验权限与密码锁，客户端不再可信（canAdmin 只是显示开关）。
        if (action.startsWith("admin_") && !requireUnlockedAdmin(player)) {
            return;
        }
        // 拍卖行（上架 / 出价 / 一口价 / 撤销 / 列表 / 详情 / 管理）统一由 AuctionService 处理。
        if (cn.autoforged.module_shop.shop.auction.AuctionService.handle(player, action, tag)
                // 玩家商店新增：商店大厅 / 从背包上架 / 补货（原有摊位动作仍在下面的 switch 里）
                || cn.autoforged.module_shop.shop.stall.StallService.handle(player, action, tag)) {
            return;
        }
        switch (action) {
            case "request_data" -> {
                returnPending(player);
                sendSnapshotOpen(player, tag.getString("tab"));
            }
            case "create_auction" -> openDeposit(player, "auction");
            case "create_stall" -> openDeposit(player, "stall");
            case "deposit_confirm" -> handleDepositConfirm(player);
            case "buy_shop" -> buyShop(player, tag.getInt("index"), parseInt(tag.getString("quantity")),
                    tag.getString("pay_channel"));
            // ---- 购物城（新增模块）----
            case "buy_mall" -> buyMall(player, tag.getInt("index"), parseInt(tag.getString("quantity")),
                    tag.getString("pay_channel"));
            case "admin_mall_import" -> adminMallImport(player, tag);
            case "admin_mall_remove_mod" -> adminMallRemoveMod(player, tag);
            case "admin_mall_restock" -> adminMallRestock(player, tag);
            // 商店设置 → 购物城：库存周期刷新（多久整店刷新一次）
            case "admin_mall_refresh" -> adminMallRefresh(player, tag);
            // ---- 出货箱（新增模块，参考星露谷售货箱）----
            case "shipping_open" -> openShippingBin(player);
            case "shipping_upgrade" -> upgradeShippingBin(player, tag.getString("pay_channel"));
            case "admin_shipping_limits" -> adminShippingLimits(player, tag);
            case "flip_shop" -> flipShop(player, tag.getInt("index"));
            // 付费刷新每日商店（价格与次数见 Config.dailyRefreshCosts）。
            case "refresh_daily_shop" -> refreshDailyShopPaid(player, tag.getString("pay_channel"));
            case "refresh_daily_buyback" -> refreshDailyBuybackPaid(player, tag.getString("pay_channel"));
            // 账单：玩家点主界面余额打开账单界面时请求一次明细。
            case "bill_request" -> sendBill(player);
            // 侧栏布局：管理员拖拽排序后保存顺序 / 恢复默认。
            case "admin_sidebar_order" -> adminSidebarOrder(player, tag);
            case "admin_sidebar_reset" -> adminSidebarReset(player);
            // 编辑布局的开关：开始 = 拉草稿（之后只改草稿）；完成 = 一次性发布给全服。
            case "admin_layout_begin" -> adminLayoutBegin(player);
            case "admin_layout_commit" -> adminLayoutCommit(player);
            // 左上角主标题改名（空 = 恢复默认「模块商店」）—— 现在**唯一**的改名入口
            case "admin_shop_title_set" -> adminShopTitleSet(player, tag);
            case "admin_shop_icon_set" -> adminShopIconSet(player, tag);
            case "admin_media_upload" -> adminMediaUpload(player, tag);
            case "cfg_open" -> cfgOpenUi(player);
            // 编辑布局里右键余额 → 改货币名称（空 = 恢复配置默认）
            case "admin_currency_set" -> adminCurrencySet(player, tag);
            // 商店设置 → 摊位：自动下架时间（永久 / 游戏时间 / 现实时间）
            case "admin_stall_expire" -> adminStallExpire(player, tag);
            // ---- 玩家求购（悬赏）----
            case "demand_create" -> demandCreate(player, tag);
            case "demand_cancel" -> demandCancel(player, tag);
            case "demand_fulfill" -> demandFulfill(player, tag);
            // 商店设置 → 求购：基本设置 / 下架时间 / 强制撤销某一条
            case "admin_demand_set" -> adminDemandSet(player, tag);
            case "admin_demand_expire" -> adminDemandExpire(player, tag);
            case "admin_demand_cancel" -> demandCancel(player, tag);
            case "sell_buyback" -> sellBuyback(player, tag.getInt("index"), parseInt(tag.getString("quantity")),
                    tag.getString("pay_channel"));
            case "bid" -> placeBid(player, tag);
            case "claim_mail" -> claimMail(player, tag.getString("mail_id"));
            // 第七轮：星标（置顶显示）
            case "mail_star" -> toggleMailStar(player, tag.getString("mail_id"));
            // 第七轮：兑换码（玩家）
            case "redeem_code" -> redeemCode(player, tag.getString("code"));
            // 第七轮：兑换码生成器（管理员）
            case "admin_redeem_create" -> adminRedeemCreate(player, tag);
            case "admin_redeem_delete" -> adminRedeemDelete(player, tag);
            case "admin_redeem_list" -> sendRedeemList(player);
            // 第八轮：兑换码奖励配置（复用物品发放容器，结果写进服务端草稿或直接改已有兑换码）。
            case "admin_redeem_reward_open" -> adminRedeemRewardOpen(player, tag);
            case "admin_redeem_reward_save" -> adminRedeemRewardSave(player, tag);
            case "admin_redeem_reward_cancel" -> adminRedeemRewardCancel(player);
            case "admin_redeem_draft_request" -> sendRedeemDraft(player);
            // 放弃本次兑换码编辑（关闭编辑器 / 取消）：草稿丢弃，兑换码保持不变。
            case "admin_redeem_draft_discard" -> adminRedeemDraftDiscard(player);
            case "admin_redeem_update" -> adminRedeemUpdate(player, tag);
            case "admin_redeem_claimed" -> sendRedeemClaimed(player, tag);
            case "admin_redeem_preview" -> adminRedeemPreview(player, tag);
            case "view_mail" -> viewMail(player, tag.getString("mail_id"));
            case "claim_all_mail" -> claimAllMail(player);
            case "delete_mail" -> deleteMail(player, tag.getString("mail_id"), tag.getBoolean("confirmed"));
            // 需求四：一键清理已经领取/查看过的邮件。
            case "delete_read_mail" -> deleteReadMail(player);
            case "do_transfer" -> doTransfer(player, tag);
            case "open_stall" -> openStallBrowse(player, tag.getString("owner"));
            case "stall_buy" -> stallBuy(player, tag);
            case "stall_apply_price" -> stallApplyPrice(player, tag);
            case "remove_stall" -> removeStall(player, tag);
            // 需求五：已取消补货，售完的商品只能下架后重新上架。
            // 需求 10：客户端打开对应界面后请求清除该类型红点（服务端权威清零并回传）。
            case "notif_clear" -> NotificationManager.clear(player, NotificationType.byId(tag.getString("type")));
            case "auction_set_start" -> auctionSetStart(player, tag);
            case "stall_set_price_new" -> stallSetPriceNew(player, tag);
            // ---- 玩家交易 ----
            case "trade_request" -> TradeManager.request(player, tag.getString("target"));
            case "trade_add_item" -> openDeposit(player, "trade");
            // 需求 3：从自己的交易区域删除已放入的物品并返还背包/邮箱。
            case "trade_remove_item" -> TradeManager.removeItem(player, tag.getInt("index"));
            case "trade_money_gui" -> {
                // 需求 3：交易金额界面显示玩家可用星币余额（纯星币账本）。
                CompoundTag moneyExtra = new CompoundTag();
                moneyExtra.putLong("balance", Math.max(0L, account(player).balance));
                moneyExtra.putBoolean("show_balance", true);
                openInput(player, "trade_set_money", "gui.module_shop.input.trade_money", moneyExtra, "amount");
            }
            case "trade_set_money" -> TradeManager.setMoney(player, parsePositive(tag.getString("amount")));
            case "trade_agree" -> TradeManager.toggleAgree(player);
            case "trade_confirm" -> TradeManager.confirm(player);
            case "trade_cancel" -> TradeManager.cancel(player, true);
            // 需求 1/2：交易请求列表与接受/拒绝。
            case "trade_requests_open" -> TradeManager.sendRequestList(player, true);
            case "trade_accept" -> TradeManager.acceptRequest(player, tag.getLong("id"));
            case "trade_decline" -> TradeManager.declineRequest(player, tag.getLong("id"));
            // 需求 11：服务端记录管理员当前编辑的模块，物品放入时据此归属列表。
            case "admin_set_edit_type" -> setAdminEditType(player, tag.getString("type"));
            // 需求 11：管理员添加物品必须使用幽灵槽位，防止刷物品。
            // （现在是**批量**界面：一次最多放 27 种，确认后统一设置价格 / 品质 / 数量。）
            case "admin_add" -> {
                if (tag.contains("mode")) {
                    setAdminEditType(player, tag.getString("mode"));
                }
                openBatchAdd(player);
            }
            case "admin_batch_prepare" -> adminBatchPrepare(player);
            case "admin_batch_apply" -> adminBatchApply(player, tag);
            case "admin_apply_price" -> adminApplyPrice(player, tag);
            case "admin_remove" -> adminRemove(player, tag);
            case "admin_set_price_new" -> adminSetPriceNew(player, tag);
            case "admin_reroll_daily", "admin_reroll_daily_go" -> adminRerollDaily(player, tag);
            // ---- 管理员发放（发钱 / 发物品）----
            case "admin_grant_open" -> GrantManager.open(player);
            case "admin_grant_request" -> GrantManager.sendTargets(player);
            case "admin_grant_send" -> GrantManager.send(player, tag);
            // ---- 管理员：查看某玩家交易流水（设置页“交易记录”按钮）----
            case "admin_history" -> sendHistory(player, tag.getString("target"), tag.getInt("page"));
            // ---- 货币兑换（所有玩家可用；配置仅管理员可改）----
            case "exchange_deposit_gui" -> exchangeDepositGui(player, tag.getInt("index"));
            case "exchange_deposit" -> exchangeDeposit(player, tag);
            case "exchange_withdraw_gui" -> exchangeWithdrawGui(player, tag.getInt("index"));
            case "exchange_withdraw" -> exchangeWithdraw(player, tag);
            case "admin_exchange_add" -> openDeposit(player, "exchange", true);
            case "admin_exchange_set" -> adminExchangeSet(player, tag);
            case "admin_exchange_edit" -> adminExchangeEdit(player, tag);
            case "admin_exchange_remove" -> adminExchangeRemove(player, tag);
            // ---- 收购全清奖励（开关 + 改金额，仅管理员）----
            case "admin_buyback_bonus_toggle" -> adminBuybackBonusToggle(player);
            case "admin_buyback_bonus_set" -> adminBuybackBonusSet(player, tag);
            // ---- 签到（需求 7）----
            case "sign_in" -> doSignIn(player);
            case "sign_makeup" -> doMakeupSign(player, tag.getLong("day"), tag.getString("pay_channel"));
            case "admin_signin_auto_toggle" -> adminSignInAutoToggle(player);
            case "admin_signin_cumulative_toggle" -> adminSignInCumulativeToggle(player);
            case "admin_signin_set" -> adminSignInSet(player, tag);
            case "admin_signin_day_set" -> adminActivityDaySet(player, tag);
            case "admin_signin_day_clear" -> adminActivityDayClear(player, tag);
            case "admin_signin_draft_open" -> adminActivityDraftOpen(player, tag);
            case "admin_signin_draft_set" -> adminActivityDraftSet(player, tag);
            case "admin_signin_day_discard" -> adminActivityDraftDiscard(player);
            case "admin_signin_reward_open" -> adminActivityRewardOpen(player, tag);
            case "admin_signin_reward_save" -> adminActivityRewardSave(player, tag);
            case "admin_signin_reward_cancel" -> adminActivityRewardCancel(player);
            case "admin_shop_gen_set" -> adminShopGenSet(player, tag);
            // ---- 个人主页公告 & 分层实体货币 & 系统定额费用 ----
            case "admin_announcements_save" -> adminAnnouncementsSave(player, tag);
            case "admin_phys_currency_save" -> adminPhysCurrencySave(player, tag);
            case "admin_system_costs_set" -> adminSystemCostsSet(player, tag);
            case "phys_currency_convert" -> {
                synchronized (LOCK) {
                    cn.autoforged.module_shop.shop.currency.PhysicalCurrencyEngine.executeConvert(
                            player, tag.getInt("tierIndex"), tag.getString("direction"), tag.getInt("batches"));
                }
            }
            default -> {
            }
        }
    }

    private static void handleDepositConfirm(ServerPlayer player) {
        if (!(player.containerMenu instanceof DepositMenu menu)) {
            return;
        }
        ItemStack stack = menu.deposit().getItem(0);
        if (stack.isEmpty()) {
            notifyPlayer(player, Component.translatable("msg.module_shop.deposit_empty").withStyle(ChatFormatting.RED));
            return;
        }
        ItemStack taken = stack.copy();
        String context = DEPOSIT_CONTEXT.getOrDefault(player.getUUID(), "");
        // 需求 11：幽灵槽不真正取走物品，关闭界面时会把原物品返还给管理员。
        if (!menu.isGhost()) {
            menu.deposit().setItem(0, ItemStack.EMPTY);
        }
        player.closeContainer();
        returnPending(player);
        // 交易放入的物品直接进入交易会话，不能走 PENDING_ITEM（否则会悬空）。
        if ("trade".equals(context)) {
            TradeManager.addItem(player, taken);
            return;
        }
        // 需求 11：管理员用幽灵槽，模板单独保存，不进入 PENDING_ITEM，避免与返还的原物重复。
        if ("exchange".equals(context)) {
            if (!requireUnlockedAdmin(player)) {
                return;
            }
            ADMIN_TEMPLATE.put(player.getUUID(), taken);
            CompoundTag extra = new CompoundTag();
            extra.putString("default_daily_deposit_limit", "0");
            extra.putString("default_daily_withdraw_limit", "0");
            extra.putBoolean("allow_zero_daily_deposit_limit", true);
            extra.putBoolean("allow_zero_daily_withdraw_limit", true);
            openInput(player, "admin_exchange_set", "gui.module_shop.input.exchange_value", extra,
                    "value", "daily_deposit_limit", "daily_withdraw_limit");
            return;
        }
        if ("admin".equals(context)) {
            if (!requireUnlockedAdmin(player)) {
                return;
            }
            ADMIN_TEMPLATE.put(player.getUUID(), taken);
            AdminEditType type = ADMIN_EDIT_TYPE.getOrDefault(player.getUUID(), AdminEditType.DAILY_SHOP);
            CompoundTag extra = new CompoundTag();
            extra.putString("mode", type.id());
            // 需求 6/7：价值、品质、数量一次配置完成。
            // 出货箱的「兑换规则」支持物品 + 单价 + 每日上限（空 = 无限）。
            if (type == AdminEditType.SHIPPING) {
                openInput(player, "admin_set_price_new", "gui.module_shop.input.shipping_rule_new", extra,
                        "price", "daily_max");
            } else if (type == AdminEditType.MALL) {
                ListTag permChoices = new ListTag();
                permChoices.add(StringTag.valueOf("0"));
                permChoices.add(StringTag.valueOf("1"));
                extra.put("choices_permanent_limit", permChoices);
                extra.putString("default_permanent_limit", "0");
                openInput(player, "admin_set_price_new", "gui.module_shop.input.admin_offer", extra,
                        "price", "rarity", "quantity", "permanent_limit");
            } else {
                openInput(player, "admin_set_price_new", "gui.module_shop.input.admin_offer", extra,
                        "price", "rarity", "quantity");
            }
            return;
        }
        PENDING_ITEM.put(player.getUUID(), taken);
        switch (context) {
            case "auction" -> openInput(player, "auction_set_start", "gui.module_shop.input.start_price",
                    null, "price");
            case "stall" -> {
                // 需求 4：上架时同时输入单件价格与库存数量，默认带出放入的物品数量（上限 64）。
                CompoundTag extra = new CompoundTag();
                extra.putString("default_quantity", Integer.toString(Math.max(1, Math.min(64, taken.getCount()))));
                openInput(player, "stall_set_price_new", "gui.module_shop.input.stall_price", extra,
                        "price", "quantity");
            }
            default -> returnPending(player);
        }
    }

    /** 需求 11：记录管理员当前编辑的模块（只有管理员能设，否则改价/删除会打到别人的上下文里）。 */
    public static void setAdminEditType(ServerPlayer player, String type) {
        if (!canEditAdmin(player)) {
            return;
        }
        ADMIN_EDIT_TYPE.put(player.getUUID(), AdminEditType.byId(type));
    }

    /**
     * 是「每日商店」还是「每日收购」。
     *
     * <p>以报文里的 {@code mode} 为准（客户端每次都会带当前页面），
     * 只有没带时才退回服务端记录的编辑模块 —— 否则管理员先开过购物城/出货箱的设置，
     * 记录里还是 MALL，再改每日商店的刷新参数就会被当成收购写进另一份数组。
     */
    private static boolean isShopMode(ServerPlayer player, CompoundTag tag) {
        String mode = tag == null ? "" : tag.getString("mode").trim();
        if ("buyback".equals(mode)) {
            return false;
        }
        if ("daily".equals(mode) || "shop".equals(mode)) {
            return true;
        }
        AdminEditType stored = ADMIN_EDIT_TYPE.get(player.getUUID());
        return stored == null || stored == AdminEditType.DAILY_SHOP;
    }

    /**
     * 管理员当前编辑的模块（购物城也走同一套）。
     * <p>每日商店 / 每日收购以报文 mode 为准，其余模块以服务端记录为准。
     */
    private static AdminEditType editType(ServerPlayer player, CompoundTag tag) {
        String mode = tag == null ? "" : tag.getString("mode").trim();
        if ("buyback".equals(mode)) {
            return AdminEditType.BUYBACK;
        }
        if ("daily".equals(mode) || "shop".equals(mode)) {
            return AdminEditType.DAILY_SHOP;
        }
        AdminEditType stored = ADMIN_EDIT_TYPE.get(player.getUUID());
        return stored == null ? AdminEditType.byId("") : stored;
    }

    /** 把悬而未决的上架物品安全退还给玩家，避免取消输入界面导致物品丢失。 */
    private static void returnPending(ServerPlayer player) {
        ItemStack pending = PENDING_ITEM.remove(player.getUUID());
        if (pending != null && !pending.isEmpty()) {
            addItemOrMail(player, pending, SOURCE_ADMIN);
        }
        // 幽灵槽模板不再作为真实物品返还（原物已由幽灵槽退回），直接丢弃防止刷物品。
        ADMIN_TEMPLATE.remove(player.getUUID());
    }

    // =========================================================== 每日商店 / 收购

    /** 需求 11：翻开每日商店卡牌，持久化翻开状态并按稀有度播放对应音效。 */
    private static void flipShop(ServerPlayer player, int index) {
        PlayerAccount account = account(player);
        ShopSavedData data = data(player.server);
        ensureDaily(player, account, data);
        if (index < 0 || index >= account.dailyShop.size() || account.dailyFlipped.contains(index)) {
            return;
        }
        account.dailyFlipped.add(index);
        data.setDirty();
        DailyShopEntry entry = account.dailyShop.get(index);
        player.playNotifySound(ModSounds.forRarity(entry.rarity), SoundSource.PLAYERS, 1.0F, 1.0F);
        sendSnapshot(player, "daily");
    }

    /**
     * 第 N 次付费刷新的花费；返回 -1 表示次数已用完。
     * {@code shop=true} 用每日商店的价格表与次数，{@code false} 用每日收购的（两者完全独立）。
     */
    public static long nextRefreshCost(PlayerAccount account, boolean shop) {
        List<PriceSpec> specs = shop ? Config.dailyRefreshSpecList() : Config.buybackRefreshSpecList();
        int used = Math.max(0, shop ? account.dailyShopRefreshUsed : account.dailyBuybackRefreshUsed);
        if (used >= specs.size()) {
            return -1L;
        }
        PriceSpec spec = specs.get(used);
        return spec.supportsStar() ? spec.starPrice : 0L;
    }

    private static PriceSpec paidSpecOf(CurrencySettlement.PayResult payRes, PriceSpec fallback) {
        if (payRes == null) {
            return fallback == null ? PriceSpec.ZERO : fallback;
        }
        return switch (payRes.executedMode()) {
            case PHYS_ONLY -> PriceSpec.phys(payRes.physTierKey(), payRes.physPaidCount());
            case BOTH -> PriceSpec.both(payRes.starPaid(), payRes.physTierKey(), payRes.physPaidCount());
            default -> PriceSpec.star(payRes.starPaid());
        };
    }

    /**
     * 付费刷新**每日商店**（只重抽卡牌，不动每日收购）：支持四态 PriceSpec 阶梯收费（可配置 0 免费）。
     */
    private static void refreshDailyShopPaid(ServerPlayer player, @Nullable String payChannel) {
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            ShopSavedData data = data(player.server);
            ensureDaily(player, account, data);
            PriceSpec costSpec = data.nextRefreshSpec(account, true);
            if (costSpec == null) {
                notifyPlayer(player, Component.translatable("msg.module_shop.refresh_no_uses")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            if (data.shopPool.isEmpty()) {
                notifyPlayer(player, Component.translatable("msg.module_shop.refresh_empty")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            var payRes = CurrencySettlement.paySpec(
                    player, account, data, costSpec, 1, payChannel, "purchase", SOURCE_EXCHANGE, false);
            if (!payRes.ok()) {
                CurrencySettlement.notifyPayFailure(player, payRes);
                return;
            }
            account.dailyShopRefreshUsed++;
            account.dailyShop.clear();
            account.dailyFlipped.clear();
            generateDailyShop(player, account, data);
            refreshDailyPrices(account, data);
            data.setDirty();
            PriceSpec paidSpec = paidSpecOf(payRes, costSpec);
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.SHOP_REFRESH, "模块商店",
                    -payRes.starPaid(), account.balance,
                    payRes.paidPhys() ? "商店刷新 " + CurrencySettlement.formatSpec(paidSpec, player.server) : "商店刷新");
            PriceSpec nextSpec = data.nextRefreshSpec(account, true);
            if (!payRes.paidPhys() && (nextSpec == null || nextSpec.mode == CurrencyMode.STAR_ONLY)) {
                long cost = payRes.starPaid();
                long next = nextSpec == null ? -1L : nextSpec.starPrice;
                if (next < 0L) {
                    notifyPlayer(player, Component.translatable("msg.module_shop.refresh_ok_last", cost)
                            .withStyle(ChatFormatting.GREEN));
                } else {
                    notifyPlayer(player, Component.translatable("msg.module_shop.refresh_ok", cost, next)
                            .withStyle(ChatFormatting.GREEN));
                }
            } else {
                String spentStr = CurrencySettlement.formatSpec(paidSpec, player.server);
                if (nextSpec == null) {
                    notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.refresh_ok_last_spec",
                            "已刷新每日商店，花费 %s（今日刷新次数已用完）", spentStr).withStyle(ChatFormatting.GREEN));
                } else {
                    notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.refresh_ok_spec",
                            "已刷新每日商店，花费 %s（下次刷新：%s）", spentStr,
                            CurrencySettlement.formatSpec(nextSpec, player.server)).withStyle(ChatFormatting.GREEN));
                }
            }
            sendSnapshot(player, "daily");
        }
    }

    /**
     * 付费刷新**每日收购**（只重抽收购清单，不动每日商店卡牌）：
     * 价格与每日次数走配置表（支持实体货币与双通道），与商店分开计数。
     */
    private static void refreshDailyBuybackPaid(ServerPlayer player, @Nullable String payChannel) {
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            ShopSavedData data = data(player.server);
            ensureDaily(player, account, data);
            PriceSpec costSpec = data.nextRefreshSpec(account, false);
            if (costSpec == null) {
                notifyPlayer(player, Component.translatable("msg.module_shop.refresh_no_uses")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            if (data.buybackPool.isEmpty()) {
                notifyPlayer(player, Component.translatable("msg.module_shop.buyback_refresh_empty")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            var payRes = CurrencySettlement.paySpec(
                    player, account, data, costSpec, 1, payChannel, "purchase", SOURCE_EXCHANGE, false);
            if (!payRes.ok()) {
                CurrencySettlement.notifyPayFailure(player, payRes);
                return;
            }
            account.dailyBuybackRefreshUsed++;
            account.dailyBuyback.clear();
            generateDailyBuyback(player, account, data);
            refreshDailyPrices(account, data);
            data.setDirty();
            PriceSpec paidSpec = paidSpecOf(payRes, costSpec);
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.SHOP_REFRESH, "模块商店",
                    -payRes.starPaid(), account.balance,
                    payRes.paidPhys() ? "收购刷新 " + CurrencySettlement.formatSpec(paidSpec, player.server) : "收购刷新");
            PriceSpec nextSpec = data.nextRefreshSpec(account, false);
            if (!payRes.paidPhys() && (nextSpec == null || nextSpec.mode == CurrencyMode.STAR_ONLY)) {
                long cost = payRes.starPaid();
                long next = nextSpec == null ? -1L : nextSpec.starPrice;
                if (next < 0L) {
                    notifyPlayer(player, Component.translatable("msg.module_shop.buyback_refresh_ok_last", cost)
                            .withStyle(ChatFormatting.GREEN));
                } else {
                    notifyPlayer(player, Component.translatable("msg.module_shop.buyback_refresh_ok", cost, next)
                            .withStyle(ChatFormatting.GREEN));
                }
            } else {
                String spentStr = CurrencySettlement.formatSpec(paidSpec, player.server);
                if (nextSpec == null) {
                    notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.buyback_refresh_ok_last_spec",
                            "已刷新每日收购，花费 %s（今日刷新次数已用完）", spentStr).withStyle(ChatFormatting.GREEN));
                } else {
                    notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.buyback_refresh_ok_spec",
                            "已刷新每日收购，花费 %s（下次刷新：%s）", spentStr,
                            CurrencySettlement.formatSpec(nextSpec, player.server)).withStyle(ChatFormatting.GREEN));
                }
            }
            sendSnapshot(player, "buyback");
        }
    }

    /** 当日收购清单全部卖完的一次性奖励（每天一次，支持星币 / 实体货币 / 双账本）。 */
    private static void maybeGrantBuybackBonus(ServerPlayer player, PlayerAccount account, ShopSavedData data) {
        PriceSpec bonusSpec = buybackBonusSpec(data);
        if (!data.buybackBonusEnabled || bonusSpec.isZero() || !bonusSpec.isValid()
                || account.dailyBuybackBonusDone || account.dailyBuyback.isEmpty()) {
            return;
        }
        for (DailyBuybackEntry entry : account.dailyBuyback) {
            if (entry.remaining() > 0) {
                return;
            }
        }
        long max = Config.maxBalanceLimit();
        long walletRoom = Math.max(0L, max - account.balance);

        String channel = null;
        if (bonusSpec.mode == CurrencyMode.EITHER) {
            boolean starPossible = bonusSpec.starPrice > 0L && walletRoom > 0L;
            if (!starPossible && bonusSpec.supportsPhys() && PhysicalCurrencyConfig.get().isActive()) {
                channel = "phys";
            } else {
                channel = "star";
            }
        }
        boolean wantStar = bonusSpec.mode == CurrencyMode.STAR_ONLY
                || bonusSpec.mode == CurrencyMode.BOTH
                || ("star".equals(channel) && bonusSpec.mode == CurrencyMode.EITHER);
        boolean wantPhys = bonusSpec.mode == CurrencyMode.PHYS_ONLY
                || bonusSpec.mode == CurrencyMode.BOTH
                || ("phys".equals(channel) && bonusSpec.mode == CurrencyMode.EITHER);

        if (wantStar && !wantPhys && walletRoom <= 0L) {
            notifyPlayer(player, Component.translatable("msg.module_shop.grant_cap_reached")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }

        var payout = CurrencySettlement.payoutSpec(player.server, player.getUUID(), account, data,
                bonusSpec, 1, channel, SOURCE_ADMIN, "每日收购", ItemStack.EMPTY, false, false);
        if (payout.starCredited() <= 0L && payout.physPaidCount() <= 0L) {
            if (wantStar && walletRoom <= 0L) {
                notifyPlayer(player, Component.translatable("msg.module_shop.grant_cap_reached")
                        .withStyle(ChatFormatting.YELLOW));
            }
            return;
        }
        account.dailyBuybackBonusDone = true;
        data.setDirty();
        PriceSpec grantedSpec = switch (payout.executedMode()) {
            case PHYS_ONLY -> PriceSpec.phys(payout.physTierKey(), payout.physPaidCount());
            case BOTH -> PriceSpec.both(payout.starCredited(), payout.physTierKey(), payout.physPaidCount());
            default -> PriceSpec.star(payout.starCredited());
        };
        TransactionLog.record(player.server, player.getUUID(), TransactionLog.BUYBACK_BONUS, "模块商店",
                payout.starCredited(), account.balance,
                payout.physPaidCount() > 0L ? CurrencySettlement.formatSpec(grantedSpec, player.server) : "");
        if (payout.physPaidCount() > 0L) {
            notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.buyback_all_bonus_spec",
                    "今日收购清单全部完成！额外奖励 %s",
                    CurrencySettlement.formatSpec(grantedSpec, player.server)).withStyle(ChatFormatting.GOLD));
        } else {
            notifyPlayer(player, Component.translatable("msg.module_shop.buyback_all_bonus", payout.starCredited())
                    .withStyle(ChatFormatting.GOLD));
        }
    }

    /** 需求 8：每日商店支持一次购买多件（兼容入口，自动按默认账本通道结算）。 */
    public static void buyShop(ServerPlayer player, int index, int requested) {
        buyShop(player, index, requested, "");
    }

    public static void buyShop(ServerPlayer player, int index, int requested, @Nullable String payChannel) {
        // 需求 8：限购、余额、发货在同一把锁内完成，防止并发抢购最后一件。
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            ShopSavedData data = data(player.server);
            ensureDaily(player, account, data);
            if (index < 0 || index >= account.dailyShop.size()) {
                return;
            }
            DailyShopEntry entry = account.dailyShop.get(index);
            // 需求 11：上限按物品计算，而不是同一品质的所有物品共用一个上限。
            int remaining = entry.limit - entry.bought;
            if (remaining <= 0) {
                notifyPlayer(player, Component.translatable("msg.module_shop.limit_reached").withStyle(ChatFormatting.RED));
                return;
            }
            int buyCount = Math.min(Math.max(1, requested), remaining);
            PriceSpec unitSpec = entry.resolvePriceSpec();
            PriceSpec totalSpec = unitSpec.scale(buyCount);
            if (!totalSpec.isValid()) {
                notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price").withStyle(ChatFormatting.RED));
                return;
            }
            var plan = CurrencySettlement.planSpec(player, account, unitSpec, buyCount, payChannel);
            if (!plan.ok()) {
                CurrencySettlement.notifyPayFailure(player,
                        CurrencySettlement.PayResult.fail(plan.failKey(), plan.failFallback()));
                return;
            }
            if (plan.resolvedMode().includesPhys()) {
                PriceSpec physCheck = PriceSpec.phys(plan.physTierKey(), plan.physCount());
                if (PhysicalCurrencyEngine.isBuyPhysBelowCurrencyValue(
                        entry.stack, buyCount, physCheck, player.level().registryAccess())) {
                    notifyPlayer(player, Component.translatableWithFallback(
                            "msg.module_shop.phys_buy_blocked_arbitrage",
                            "防套利保护：该商品属于已启用的实体货币且售价低于面值，已禁止购买")
                            .withStyle(ChatFormatting.RED));
                    return;
                }
            }
            // 需求 9：价格重新从服务端条目读取，绝不使用客户端传来的价格。
            var payRes = CurrencySettlement.paySpec(
                    player, account, data, unitSpec, buyCount, payChannel, "purchase", SOURCE_EXCHANGE, false);
            if (!payRes.ok()) {
                CurrencySettlement.notifyPayFailure(player, payRes);
                return;
            }
            entry.bought += buyCount;
            data.setDirty();
            deliver(player, entry.stack, buyCount, SOURCE_ADMIN);
            PriceSpec paidSpec = paidSpecOf(payRes, totalSpec);
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.BUY, "模块商店",
                    -payRes.starPaid(), account.balance,
                    entry.stack.getHoverName().getString() + "×" + buyCount
                            + (payRes.paidPhys() ? " [" + CurrencySettlement.formatSpec(paidSpec, player.server) + "]" : ""));
            if (!payRes.paidPhys()) {
                long total = payRes.starPaid();
                if (buyCount <= 1) {
                    notifyPlayer(player, Component.translatable("msg.module_shop.bought",
                            total, entry.stack.getHoverName()).withStyle(ChatFormatting.GREEN));
                } else {
                    notifyPlayer(player, Component.translatable("msg.module_shop.bought_count",
                            total, entry.stack.getHoverName(), buyCount).withStyle(ChatFormatting.GREEN));
                }
            } else {
                String paidText = CurrencySettlement.formatSpec(paidSpec, player.server);
                if (buyCount <= 1) {
                    notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.bought_spec",
                            "花费 %s 购买 %s", paidText, entry.stack.getHoverName()).withStyle(ChatFormatting.GREEN));
                } else {
                    notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.bought_count_spec",
                            "花费 %s 购买 %s×%s", paidText, entry.stack.getHoverName(), buyCount).withStyle(ChatFormatting.GREEN));
                }
            }
            sendSnapshot(player, "daily");
        }
    }

    // =========================================================== 购物城（新增模块）
    //
    // 常驻货架：每件商品有 价格 / 品质 / 库存（-1 = 无限），购买即扣库存并记一条"最近购买"。
    // 管理端支持三种录入方式：幽灵槽单件添加、管理页逐条编辑（价格/品质/库存）、整模组批量导入。

    /** 购物城：购买货架上的第 index 件商品（兼容入口）。 */
    public static void buyMall(ServerPlayer player, int index, int requested) {
        buyMall(player, index, requested, "");
    }

    /** 购物城：购买货架上的第 index 件商品（支持星币/实体货币/双通道结算）。 */
    public static void buyMall(ServerPlayer player, int index, int requested, @Nullable String payChannel) {
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            ShopSavedData data = data(player.server);
            if (index < 0 || index >= data.mallPool.size()) {
                return;
            }
            MallItem item = data.mallPool.get(index);
            // 「个人库存」或「每人永久限购」模式下 stock 是每个玩家各自的上限，可用量要减去这个人已经买走的。
            boolean personal = Config.mallStockPersonal();
            boolean usePersonal = personal || item.permanentLimit;
            int available = usePersonal && !item.unlimited() ? item.remainingFor(player.getUUID()) : item.available();
            if (available <= 0) {
                if (item.permanentLimit) {
                    notifyPlayer(player, Component.translatableWithFallback(
                            "msg.module_shop.mall_permanent_sold_out",
                            "已达到 %s 的每人永久限购上限", item.stack.getHoverName()).withStyle(ChatFormatting.RED));
                } else {
                    notifyPlayer(player, Component.translatable(personal
                                    ? "msg.module_shop.mall_personal_sold_out" : "msg.module_shop.mall_sold_out",
                            item.stack.getHoverName()).withStyle(ChatFormatting.RED));
                }
                return;
            }
            int buyCount = Math.min(Math.max(1, requested), available);
            PriceSpec totalSpec = item.resolvePriceSpec().scale(buyCount);
            if (!totalSpec.isValid()) {
                notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            var plan = CurrencySettlement.planSpec(player, account, totalSpec, 1, payChannel);
            if (!plan.ok()) {
                CurrencySettlement.notifyPayFailure(player,
                        CurrencySettlement.PayResult.fail(plan.failKey(), plan.failFallback()));
                return;
            }
            if (plan.resolvedMode().includesPhys()) {
                PriceSpec physCheck = PriceSpec.phys(plan.physTierKey(), plan.physCount());
                if (PhysicalCurrencyEngine.isBuyPhysBelowCurrencyValue(
                        item.stack, buyCount, physCheck, player.level().registryAccess())) {
                    notifyPlayer(player, Component.translatableWithFallback(
                            "msg.module_shop.phys_buy_blocked_arbitrage",
                            "防套利保护：该商品属于已启用的实体货币且售价低于面值，已禁止购买")
                            .withStyle(ChatFormatting.RED));
                    return;
                }
            }
            var payRes = CurrencySettlement.paySpec(
                    player, account, data, totalSpec, 1, payChannel, "purchase", SOURCE_EXCHANGE, false);
            if (!payRes.ok()) {
                CurrencySettlement.notifyPayFailure(player, payRes);
                return;
            }
            if (!item.unlimited()) {
                if (usePersonal) {
                    // 个人库存 / 永久限购：只记「这个人买了多少」，全服货架的 stock 不动。
                    item.addPersonalUsed(player.getUUID(), buyCount);
                } else {
                    item.stock = Math.max(0, item.stock - buyCount);
                }
            }
            PriceSpec paidSpec = paidSpecOf(payRes, totalSpec);
            PriceSpec avgUnitSpec = switch (payRes.executedMode()) {
                case PHYS_ONLY -> PriceSpec.phys(payRes.physTierKey(), Math.max(1L, payRes.physPaidCount() / buyCount));
                case BOTH -> PriceSpec.both(Math.max(1L, payRes.starPaid() / buyCount),
                        payRes.physTierKey(), Math.max(1L, payRes.physPaidCount() / buyCount));
                default -> PriceSpec.star(Math.max(1L, payRes.starPaid() / buyCount));
            };
            data.addMallRecent(new MallPurchase(player.getGameProfile().getName(), item.stack, buyCount,
                    avgUnitSpec, paidSpec, System.currentTimeMillis()));
            data.setDirty();
            deliver(player, item.stack, buyCount, SOURCE_ADMIN);
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.BUY, "购物城",
                    -payRes.starPaid(), account.balance,
                    item.stack.getHoverName().getString() + "×" + buyCount
                            + (payRes.paidPhys() ? " [" + CurrencySettlement.formatSpec(paidSpec, player.server) + "]" : ""));
            if (!payRes.paidPhys()) {
                long total = payRes.starPaid();
                if (buyCount <= 1) {
                    notifyPlayer(player, Component.translatable("msg.module_shop.bought",
                            total, item.stack.getHoverName()).withStyle(ChatFormatting.GREEN));
                } else {
                    notifyPlayer(player, Component.translatable("msg.module_shop.bought_count",
                            total, item.stack.getHoverName(), buyCount).withStyle(ChatFormatting.GREEN));
                }
            } else {
                String paidText = CurrencySettlement.formatSpec(paidSpec, player.server);
                if (buyCount <= 1) {
                    notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.bought_spec",
                            "花费 %s 购买 %s", paidText, item.stack.getHoverName()).withStyle(ChatFormatting.GREEN));
                } else {
                    notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.bought_count_spec",
                            "花费 %s 购买 %s×%s", paidText, item.stack.getHoverName(), buyCount).withStyle(ChatFormatting.GREEN));
                }
            }
            sendSnapshot(player, "mall");
        }
    }

    /**
     * 管理员：把某个模组的**全部物品**一次性导入购物城（统一价格 / 库存 / 品质）。
     * 已经在货架上的物品按注册名去重跳过，重复导入不会出现两行一样的商品。
     */
    private static void adminMallImport(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        String namespace = tag.getString("mod").trim();
        if (namespace.isEmpty()) {
            return;
        }
        PriceSpec priceSpec = parseAdminSpec(player, tag);
        if (priceSpec == null) {
            return;
        }
        long rarityHint = priceSpec.supportsStar() ? priceSpec.starPrice : Math.max(1L, priceSpec.physCount);
        Rarity rarity = parseRarity(tag.getString("rarity"), rarityHint);
        int stock = parseMallStock(tag.getString("quantity"));
        ShopSavedData data = data(player.server);
        Map<Item, List<ItemStack>> existingByItem = new HashMap<>();
        for (MallItem item : data.mallPool) {
            if (item != null && item.stack != null && !item.stack.isEmpty()) {
                existingByItem.computeIfAbsent(item.stack.getItem(), k -> new ArrayList<>()).add(item.stack);
            }
        }
        int added = 0;
        int skipped = 0;
        int unnamed = 0;
        boolean truncated = false;
        // 客户端用「保留哪几件」的位掩码过滤掉没有显示名的技术物品（模组内部用的图标 / 属性占位物品）。
        // 两端都按**注册表顺序**遍历同一命名空间的物品，所以第 i 位对应的就是同一件物品。
        byte[] mask = tag.getByteArray("mask");
        int maskBits = tag.getInt("mask_bits");
        int slotIndex = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null || !namespace.equals(id.getNamespace()) || item == Items.AIR) {
                continue;
            }
            boolean keep = mask.length == 0 || slotIndex >= maskBits
                    || (mask[slotIndex >> 3] & (1 << (slotIndex & 7))) != 0;
            slotIndex++;
            if (!keep) {
                unnamed++;
                continue;
            }
            ItemStack candidate = new ItemStack(item);
            List<ItemStack> sameItem = existingByItem.get(item);
            boolean duplicate = false;
            if (sameItem != null) {
                for (ItemStack existingStack : sameItem) {
                    if (ItemStack.isSameItemSameTags(existingStack, candidate)) {
                        duplicate = true;
                        break;
                    }
                }
            }
            if (duplicate) {
                skipped++;
                continue;
            }
            if (data.mallPool.size() >= MALL_IMPORT_MAX) {
                truncated = true;
                break;
            }
            // 用注册名的默认 ItemStack（含默认组件），保证和玩家实际拿到的物品一致。
            MallItem mallItem = new MallItem(candidate, priceSpec, rarity, stock);
            mallItem.sort = data.mallPool.size();
            mallItem.permanentLimit = isPermanentLimitFlag(tag);
            data.mallPool.add(mallItem);
            existingByItem.computeIfAbsent(item, k -> new ArrayList<>()).add(candidate);
            added++;
        }
        data.setDirty();
        // 导完直接回购物城页：管理员马上能看到整批新上架的商品。
        sendSnapshot(player, "mall");
        notifyPlayer(player, Component.translatable("msg.module_shop.mall_import_done",
                namespace, added, skipped, unnamed).withStyle(ChatFormatting.GREEN));
        if (truncated) {
            notifyPlayer(player, Component.translatable("msg.module_shop.mall_import_capped", MALL_IMPORT_MAX)
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    // =========================================================== 出货箱（新增模块）
    //
    // 参考星露谷的售货箱：每个玩家一个**独立**空间（类似末影箱），放入的物品会在**第二天早上 6:00**
    // （游戏刻 0，窗口见配置 shippingSettleWindow）按「兑换规则」自动结算 —— 直接换成星币到账，
    // 箱子随之清空。每天能结算的**金额**有上限（按等级配置，现实时间 0 点刷新），
    // 超出上限的那部分物品**留在箱子里**，第二天继续结算（不会凭空消失）。

    /** 出货箱：本游戏日是否已经遍历结算过（避免每 tick 遍历）。服务器启动时重置，见 {@link #resetShippingTick}。 */
    private static long shippingLoggedDay = Long.MIN_VALUE;
    /** 永昼/冻结时间自动兜底时钟（服务器启动时重置）。 */
    private static final ShippingSettleClock SHIPPING_CLOCK = new ShippingSettleClock();

    /**
     * 服务器启动时调用：清掉上一个存档留下的「今天已结算」标记。
     * 单人游戏切换存档时静态字段不会自动清空，新存档恰好处在同一个游戏日就会整天不结算。
     */
    public static void resetShippingTick() {
        shippingLoggedDay = Long.MIN_VALUE;
        SHIPPING_CLOCK.reset();
        LAST_SENT_MALL_HASH.clear();
    }

    /** 出货箱：放不进去的提醒节流（玩家 UUID → 上次提醒时刻）。 */
    private static final Map<UUID, Long> SHIPPING_REJECT_WARNED = new HashMap<>();

    /**
     * 玩家往出货箱里塞了「兑换规则」没写的物品：给一条说明（2 秒内只提醒一次，避免刷屏）。
     * 由容器菜单在槽位拒绝放置时调用。
     */
    public static void notifyShippingRejected(ServerPlayer player) {
        notifyShippingRejected(player, ItemStack.EMPTY);
    }

    public static void notifyShippingRejected(ServerPlayer player, ItemStack rejected) {
        long now = System.currentTimeMillis();
        Long last = SHIPPING_REJECT_WARNED.get(player.getUUID());
        if (last != null && now - last < 2000L) {
            return;
        }
        SHIPPING_REJECT_WARNED.put(player.getUUID(), now);
        if (rejected != null && !rejected.isEmpty()
                && CurrencySettlement.isBlockingBuybackAndShipping(
                        rejected, player.level().registryAccess())) {
            notifyPlayer(player, Component.translatableWithFallback(
                            "msg.module_shop.phys_sell_blocked_arbitrage",
                            "防套利保护：已启用的实体货币物品禁止通过出货箱出售")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        notifyPlayer(player, Component.translatable("msg.module_shop.shipping_reject")
                .withStyle(ChatFormatting.RED));
    }

    /** 玩家：打开自己的出货箱。 */
    private static void openShippingBin(ServerPlayer player) {
        if (!Config.INSTANCE.shippingEnabled.get()) {
            return;
        }
        PlayerAccount account = account(player);
        ShippingBin bin = account.shipping;
        int slots = Config.shippingSlotsFor(bin.level);
        bin.resize(slots);
        // 规则写错时先在聊天栏提醒一次（格式错的规则不会生效，对应物品放不进去）。
        warnShippingRules(player);
        // Forge 1.20.1 的 ServerPlayer#openMenu 没有额外数据重载，用 NetworkHooks.openScreen 写入。
        net.minecraftforge.network.NetworkHooks.openScreen(player, new SimpleMenuProvider(
                (id, inventory, p) -> new ShippingBinMenu(id, inventory,
                        new ShippingBinContainer(player, slots), slots),
                Component.translatable("gui.module_shop.shipping.title", bin.level, slots)),
                buf -> buf.writeVarInt(slots));
    }

    /** 规则有错时给玩家一条说明（同一条错误 30 秒内只提醒一次）。 */
    private static final Map<UUID, Long> SHIPPING_RULE_WARNED = new HashMap<>();

    private static void warnShippingRules(ServerPlayer player) {
        List<String> errors = ShippingRules.errors();
        if (errors.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = SHIPPING_RULE_WARNED.get(player.getUUID());
        if (last != null && now - last < 30_000L) {
            return;
        }
        SHIPPING_RULE_WARNED.put(player.getUUID(), now);
        notifyPlayer(player, Component.translatable("msg.module_shop.shipping_rule_bad",
                errors.size()).withStyle(ChatFormatting.RED));
        for (int i = 0; i < Math.min(3, errors.size()); i++) {
            notifyPlayer(player, Component.literal("· " + errors.get(i)).withStyle(ChatFormatting.GRAY));
        }
        notifyPlayer(player, Component.translatable("msg.module_shop.shipping_rule_bad_hint")
                .withStyle(ChatFormatting.GRAY));
    }

    /** 出货箱：升级（兼容入口，支持星币 / 实体货币 / 双通道定价）。 */
    private static void upgradeShippingBin(ServerPlayer player) {
        upgradeShippingBin(player, "");
    }

    private static void upgradeShippingBin(ServerPlayer player, @Nullable String payChannel) {
        if (!Config.INSTANCE.shippingEnabled.get()) {
            return;
        }
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            ShippingBin bin = account.shipping;
            ShopSavedData savedData = data(player.server);
            int maxLevel = Config.shippingLevels(savedData);
            if (bin.level >= maxLevel) {
                notifyPlayer(player, Component.translatable("msg.module_shop.shipping_max_level", maxLevel)
                        .withStyle(ChatFormatting.YELLOW));
                return;
            }
            PriceSpec costSpec = savedData.shippingUpgradeSpecFor(bin.level);
            if (costSpec == null) {
                notifyPlayer(player, Component.translatable("msg.module_shop.shipping_no_upgrade")
                        .withStyle(ChatFormatting.YELLOW));
                return;
            }
            var payRes = CurrencySettlement.paySpec(
                    player, account, savedData, costSpec, 1, payChannel, "purchase", SOURCE_EXCHANGE, false);
            if (!payRes.ok()) {
                CurrencySettlement.notifyPayFailure(player, payRes);
                return;
            }
            bin.level += 1;
            int slots = Config.shippingSlotsFor(bin.level);
            bin.resize(slots);
            savedData.setDirty();
            PriceSpec paidSpec = paidSpecOf(payRes, costSpec);
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.SHOP_REFRESH, "出货箱升级",
                    -payRes.starPaid(), account.balance,
                    "出货箱 → Lv." + bin.level + "（" + slots + " 格）"
                            + (payRes.paidPhys() ? " [" + CurrencySettlement.formatSpec(paidSpec, player.server) + "]" : ""));
            if (payRes.paidPhys()) {
                notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.shipping_upgraded_spec",
                        "出货箱已升级至 Lv.%s（%s 格），花费 %s",
                        bin.level, slots, CurrencySettlement.formatSpec(paidSpec, player.server))
                        .withStyle(ChatFormatting.GREEN));
            } else {
                notifyPlayer(player, Component.translatable("msg.module_shop.shipping_upgraded",
                        bin.level, slots, payRes.starPaid()).withStyle(ChatFormatting.GREEN));
            }
            sendSnapshot(player, "shipping");
        }
    }

    /**
     * 出货箱：容器与存档之间的桥。
     * <p>
     * 每次槽位变化立刻写回玩家存档（{@link #setChanged()}），所以掉线 / 崩服都不会丢物品；
     * 关闭界面时不需要额外处理。
     *
     * <p>⚠ <b>初始化 / 刷新期间必须挡住回写</b>（{@link #suppressWriteBack}）：
     * {@code SimpleContainer.setItem()} 内部会调用 {@code setChanged()}，而这里的 {@code setChanged()}
     * 是把**整个容器**写回存档 —— 构造时如果要「第 0 格填完就把还没填的格子（全空）整体写回」，
     * 箱子里第 1 格之后的物品会当场被清空。玩家反馈的「放进去、重进箱子就没了」就是这个原因。
     */
    private static final class ShippingBinContainer extends SimpleContainer {
        private final ServerPlayer player;
        /** true = 正在从存档往容器里填，此期间禁止把容器写回存档。 */
        private boolean suppressWriteBack;

        ShippingBinContainer(ServerPlayer player, int size) {
            super(size);
            this.player = player;
            this.suppressWriteBack = true;
            try {
                PlayerAccount account = account(player);
                ShippingBin bin = account.shipping;
                bin.resize(size);
                for (int i = 0; i < size; i++) {
                    ItemStack stack = bin.slots.get(i);
                    setItem(i, stack == null ? ItemStack.EMPTY : stack.copy());
                }
            } finally {
                this.suppressWriteBack = false;
            }
        }

        @Override
        public void setChanged() {
            super.setChanged();
            if (this.suppressWriteBack) {
                // 正在装载存档内容：这一刻容器还是半满的，回写会毁掉剩下的物品。
                return;
            }
            PlayerAccount account = account(this.player);
            ShippingBin bin = account.shipping;
            bin.resize(getContainerSize());
            for (int i = 0; i < getContainerSize(); i++) {
                bin.slots.set(i, getItem(i).copy());
            }
            data(this.player.server).setDirty();
        }

        /**
         * 按存档刷新容器内容（结算卖掉东西之后调用）。
         *
         * <p>方向是**存档 → 界面**，靠 {@link #suppressWriteBack} 挡住回写：
         * 否则就会把界面里那份旧内容又写回存档，等于把刚卖掉的东西变回来。
         */
        void reloadFromBin() {
            PlayerAccount account = account(this.player);
            ShippingBin bin = account.shipping;
            this.suppressWriteBack = true;
            try {
                bin.resize(getContainerSize());
                for (int i = 0; i < getContainerSize(); i++) {
                    ItemStack stack = bin.slots.get(i);
                    setItem(i, stack == null ? ItemStack.EMPTY : stack.copy());
                }
            } finally {
                this.suppressWriteBack = false;
            }
        }
    }

    /**
     * 出货箱结算：**每天早上 6:00（游戏刻 0）起的窗口内**，对所有玩家（含离线）的箱子结算一次。
     * 结算按槽位顺序卖到「今日剩余额度」用完为止，剩下的物品留在箱子里等第二天。
     */
    public static void tickShipping(MinecraftServer server) {
        if (!Config.INSTANCE.shippingEnabled.get()) {
            return;
        }
        var overworld = server.overworld();
        boolean daylightOn = overworld.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DAYLIGHT);
        ShippingSettleClock.Sample clock = SHIPPING_CLOCK.sample(daylightOn, overworld.getDayTime(), overworld.getGameTime());
        if (clock.modeChanged()) {
            shippingLoggedDay = Long.MIN_VALUE;
            org.slf4j.LoggerFactory.getLogger("module_shop").info(
                    clock.fallbackGameTime()
                            ? "出货箱：检测到永昼/昼夜时间停滞，已自动切换为运行刻定时结算（每 20 分钟一次）"
                            : "出货箱：检测到昼夜循环恢复，已切回按游戏时间定时结算");
            broadcastSnapshots(server);
        }
        long gameDay = clock.cycleDay();
        long timeOfDay = clock.timeOfDay();
        long start = Config.INSTANCE.shippingSettleTick.get();
        // 游戏时间被调回（/time set day、/time set 0 …会把 DayTime 设成绝对值，游戏日从 0 重新数）：旧标记作废。
        if (shippingLoggedDay != Long.MIN_VALUE && shippingLoggedDay > gameDay) {
            shippingLoggedDay = Long.MIN_VALUE;
        }
        if (shippingLoggedDay == gameDay) {
            return;
        }
        // 以前只在 [start, start + shippingSettleWindow] 这 9 秒窗口里结算：/time add、/time set noon、
        // 或服务器在窗口内卡顿超过 9 秒，都会把窗口整个跳过，当天就不出货了。
        // 现在改为「当天到点后的第一次 tick 结算」（每个箱子仍然每个周期只结算一次，由 lastSettleGameDay 保证）。
        if (timeOfDay < start) {
            return;
        }
        shippingLoggedDay = gameDay;
        ShopSavedData data = data(server);
        // 出货箱的「今日额度」按配置的现实重置时间归属（默认现实 0 点）
        long realDay = Config.shippingQuotaDay();
        for (Map.Entry<UUID, PlayerAccount> entry : new ArrayList<>(data.accounts().entrySet())) {
            settleShippingBin(server, data, entry.getKey(), entry.getValue(), gameDay, realDay);
        }
        data.setDirty();
    }

    /**
     * 购物城定时补货：按「补货设置」里的时刻，把货架上**卖掉的库存补回目标数量**。
     *
     * <p>两种时间口径（管理员在游戏内选，也可写配置）：
     * <ul>
     *   <li>{@code real}（默认）：按**现实时间**的 HH:mm 分界 —— 和「每日额度重置」同一套算法；</li>
     *   <li>{@code game}：按**游戏内时间**的 HH:mm —— 和出货箱结算一样，06:00 = 游戏 0 刻。</li>
     * </ul>
     * 每个「补货日」只补一次（存档里记 {@code lastMallRestockDay / lastMallRestockGameDay}），
     * 服务器重启也不会重复补；无限库存（stock &lt; 0）的商品永远不参与补货。
     */
    /**
     * 购物城**库存周期刷新**：按「商店设置 → 购物城 → 库存设置」的周期，把全店库存补回各自的「原量」。
     *
     * <p>与「补货设置」的区别：补货是**每天固定时刻**补一次；这里是**每隔 N 小时 / N 游戏天**整店刷新一次库存
     * （从上次刷新时刻算起）。第一次运行只记录基准时刻（不会一开服就刷）。
     */
    public static void tickMallRefresh(MinecraftServer server) {
        if (server == null || !Config.mallRefreshActive()) {
            return;
        }
        ShopSavedData data = data(server);
        boolean byGame = "game".equals(Config.mallRefreshMode());
        long interval = byGame ? Config.mallRefreshGameTicks() : Config.mallRefreshRealMillis();
        long now = byGame ? server.overworld().getGameTime() : System.currentTimeMillis();
        long last = byGame ? data.mallRefreshLastGame : data.mallRefreshLastReal;
        if (last <= 0L) {
            // 第一次运行：只记录基准，不立刻刷新（否则玩家一开服就白白拿到整店补货）。
            if (byGame) {
                data.mallRefreshLastGame = now;
            } else {
                data.mallRefreshLastReal = now;
            }
            data.setDirty();
            return;
        }
        if (now - last < interval) {
            return;
        }
        int restored = restockMall(data, false);
        if (byGame) {
            data.mallRefreshLastGame = now;
        } else {
            data.mallRefreshLastReal = now;
        }
        // 另一套时间戳也顺手对齐，避免玩家切换方式后立刻触发一次。
        data.mallRefreshLastReal = System.currentTimeMillis();
        data.mallRefreshLastGame = server.overworld().getGameTime();
        data.setDirty();
        broadcastSnapshots(server);
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            notifyPlayer(online, Component.translatable("msg.module_shop.mall_refreshed", restored)
                    .withStyle(ChatFormatting.GREEN));
        }
    }

    /** 管理员：购物城库存设置（库存模式：全服 / 个人；刷新方式：关闭 / 现实小时 / 游戏天数）。 */
    private static void adminMallRefresh(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        String stockText = tag.getString("stock_mode").trim().toLowerCase(java.util.Locale.ROOT);
        String stockMode = "personal".equals(stockText) ? "personal" : "global";
        boolean wasPersonal = Config.mallStockPersonal();
        String modeText = tag.getString("refresh_mode").trim().toLowerCase(java.util.Locale.ROOT);
        String mode = ("real".equals(modeText) || "game".equals(modeText)) ? modeText : "off";
        int amount;
        try {
            amount = Integer.parseInt(tag.getString("refresh_amount").trim());
        } catch (NumberFormatException ex) {
            notifyPlayer(player, Component.translatable("msg.module_shop.mall_refresh_bad")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (amount < 1 || amount > 100000) {
            notifyPlayer(player, Component.translatable("msg.module_shop.mall_refresh_bad")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        Config.INSTANCE.mallStockMode.set(stockMode);
        Config.INSTANCE.mallRefreshMode.set(mode);
        Config.INSTANCE.mallRefreshAmount.set(amount);
        Config.SPEC.save();
        // 切换库存模式 = 一次「重新上架」：全服库存回到管理员设定的上限、个人已买记录清空。
        // 否则从全服切到个人时，每人的上限会被以前的全局销量吃掉一部分（管理员设 10 件，玩家却只能买 3 件）。
        if (!stockMode.equals(wasPersonal ? "personal" : "global")) {
            ShopSavedData data = data(player.server);
            for (MallItem item : data.mallPool) {
                if (item.permanentLimit) {
                    continue;
                }
                if (item.stockMax >= 0) {
                    item.stock = item.stockMax;
                }
                item.clearPersonalUsed();
            }
            data.setDirty();
        }
        Component desc = "off".equals(mode)
                ? Component.translatable("gui.module_shop.choice.refresh_mode.off")
                : Component.translatable("gui.module_shop.mall.refresh_value",
                        Component.translatable("gui.module_shop.choice.refresh_mode." + mode), amount);
        notifyPlayer(player, Component.translatable("msg.module_shop.mall_stock_saved",
                Component.translatable("gui.module_shop.choice.stock_mode." + stockMode), desc)
                .withStyle(ChatFormatting.GREEN));
        // 个人 / 全服切换后每件商品的「剩余」含义就变了，立刻给所有人推一份新快照。
        broadcastSnapshots(player.server);
    }
    public static void tickMallRestock(MinecraftServer server) {
        if (!Config.mallRestockEnabled() || server == null) {
            return;
        }
        ShopSavedData data = data(server);
        long rawDayTime = server.overworld().getDayTime();
        long timeOfDay = Math.floorMod(rawDayTime, 24000L);
        long gameDay = Math.floorDiv(rawDayTime, 24000L);
        boolean restock;
        if (Config.mallRestockGameTime()) {
            long start = Config.mallRestockTick();
            if (data.lastMallRestockGameDay == Long.MIN_VALUE) {
                // 第一次运行只记录基准日（还没到点的话记「昨天」，这样当天的补货时刻仍会生效）
                data.lastMallRestockGameDay = timeOfDay >= start ? gameDay : gameDay - 1L;
                data.setDirty();
                return;
            }
            // /time set 调回游戏时间时自动对齐基准日，避免补货长期停摆
            if (gameDay < data.lastMallRestockGameDay) {
                data.lastMallRestockGameDay = gameDay - 1L;
                data.setDirty();
            }
            if (timeOfDay < start) {
                return;   // 还没到补货时刻
            }
            if (gameDay <= data.lastMallRestockGameDay) {
                return;   // 这个游戏日已经补过了
            }
            data.lastMallRestockGameDay = gameDay;
            restock = true;
        } else {
            long day = Config.mallRestockRealDay();
            if (data.lastMallRestockDay == Long.MIN_VALUE) {
                data.lastMallRestockDay = day;   // 第一次运行只记录基准日，不立刻补
                data.setDirty();
                return;
            }
            if (day <= data.lastMallRestockDay) {
                return;
            }
            data.lastMallRestockDay = day;
            restock = true;
        }
        if (restock) {
            int restored = restockMall(data, true);
            data.setDirty();
            if (restored > 0) {
                broadcastSnapshots(server);
                server.getPlayerList().broadcastSystemMessage(
                        Component.translatable("msg.module_shop.mall_restocked", restored)
                                .withStyle(ChatFormatting.GREEN), false);
            }
        }
    }

    /** 把货架上卖掉的库存补回目标数量，返回补过的商品件数（{@link #tickMallRestock} / {@link #tickMallRefresh} 用）。 */
    public static int restockMall(ShopSavedData data) {
        return restockMall(data, true);
    }

    /**
     * 补回购物城库存。
     *
     * @param useUnifiedAmount true=每日定时补货（允许读取 {@code mallRestockAmount} 统一目标）；false=整店周期刷新（始终恢复各自 {@code stockMax}）
     */
    public static int restockMall(ShopSavedData data, boolean useUnifiedAmount) {
        int unified = useUnifiedAmount ? Config.mallRestockAmount() : 0;
        boolean personal = Config.mallStockPersonal();
        int restored = 0;
        for (MallItem item : data.mallPool) {
            if (item.permanentLimit) {
                continue;
            }
            if (item.unlimited() || item.stockMax < 0) {
                continue; // 无限库存（stock < 0）永不改写为有限库存
            }
            // 0 = 补回各自原量（补货目标）；>0 = 全部统一补到该数量。
            int target = unified > 0 ? unified : item.stockMax;
            boolean changed = false;
            if (target >= 0 && item.stock < target) {
                item.stock = target;
                changed = true;
            }
            // 个人库存：清空「每人已买多少」= 每个玩家都补回自己那份。
            if (personal && !item.personalUsed.isEmpty()) {
                item.clearPersonalUsed();
                changed = true;
            }
            if (changed) {
                restored++;
            }
        }
        return restored;
    }

    /** 结算单个玩家的出货箱（详见 {@link #tickShipping}），支持星币 / 实体货币 / 双独立账本。 */
    private static void settleShippingBin(MinecraftServer server, ShopSavedData data, UUID owner,
            PlayerAccount account, long gameDay, long realDay) {
        ShippingBin bin = account.shipping;
        bin.resize(Config.shippingSlotsFor(bin.level));
        // 现实日期变了 → 今日额度与「每条规则的当日份数」全部归零（服务器 0 点刷新）。
        if (bin.soldDay != realDay) {
            bin.soldDay = realDay;
            bin.soldToday = 0L;
            bin.ruleUsed.clear();
        }
        // 游戏时间被调回过：箱子里记的「上次结算日」比今天还大。
        if (bin.lastSettleGameDay != Long.MIN_VALUE && bin.lastSettleGameDay > gameDay) {
            org.slf4j.LoggerFactory.getLogger("module_shop").info(
                    "出货箱：检测到游戏时间被调回（上次结算日 {} > 当前 {}），已重置结算标记（玩家 {}）",
                    bin.lastSettleGameDay, gameDay, owner);
            bin.lastSettleGameDay = gameDay - 1L;
        }
        if (bin.lastSettleGameDay >= gameDay) {
            return;
        }
        bin.lastSettleGameDay = gameDay;
        if (bin.isEmpty()) {
            return;
        }
        long limit = Config.shippingDailyLimit(bin.level);
        long remainingQuota = limit == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0L, limit - bin.soldToday);
        long maxBalance = Config.maxBalanceLimit();
        boolean bankFull = false;
        if (maxBalance != Long.MAX_VALUE) {
            long room = Math.max(0L, maxBalance - account.balance);
            if (room < remainingQuota) {
                bankFull = true;
                remainingQuota = room;
            }
        }
        PhysicalCurrencyConfig physCfg = PhysicalCurrencyConfig.get();
        boolean physActive = physCfg.isActive();

        long totalStarPayout = 0L;
        Map<String, Long> physPayoutByTier = new java.util.LinkedHashMap<>();
        int sold = 0;
        boolean blockedPhys = false;
        boolean physUnavailable = false;
        List<String> soldNames = new ArrayList<>();
        for (int i = 0; i < bin.slots.size(); i++) {
            ItemStack stack = bin.slots.get(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ShippingRules.Rule rule = ShippingRules.match(stack);
            if (rule == null) {
                // 规则被改过、原物品不再可出售：留在箱子里（下次仍会尝试），不销毁。
                continue;
            }
            PriceSpec ruleSpec = rule.resolvePriceSpec();
            if (!ruleSpec.isValid()) {
                continue;
            }
            if (server != null && CurrencySettlement.isBlockingBuybackAndShippingForSpec(
                    stack, ruleSpec, server.registryAccess(), rule.kind() == ShippingRules.Kind.ITEM)) {
                blockedPhys = true;
                continue;
            }
            int perDay = rule.dailyMax();
            int usedCount = bin.ruleUsed.getOrDefault(rule.line(), 0);
            int allowed = perDay <= 0 ? stack.getCount() : Math.max(0, perDay - usedCount);
            if (allowed <= 0) {
                continue;
            }

            boolean physReady = false;
            String canonTierKey = "";
            if (ruleSpec.supportsPhys() && server != null && physActive) {
                var resolved = physCfg.findResolvedTier(ruleSpec.physTierKey, server.registryAccess());
                if (resolved != null) {
                    canonTierKey = resolved.spec.tierKey();
                    physReady = true;
                }
            }
            if ((ruleSpec.mode == CurrencyMode.PHYS_ONLY || ruleSpec.mode == CurrencyMode.BOTH) && !physReady) {
                physUnavailable = true;
                continue;
            }

            int soldInThisSlot = 0;
            long starGainInThisSlot = 0L;
            long physGainInThisSlot = 0L;
            int tryCount = Math.min(stack.getCount(), allowed);
            for (int k = 0; k < tryCount; k++) {
                if (ruleSpec.mode == CurrencyMode.PHYS_ONLY) {
                    soldInThisSlot++;
                    physGainInThisSlot += ruleSpec.physCount;
                    continue;
                }
                long starUnit = ruleSpec.supportsStar() ? ruleSpec.starPrice : 0L;
                boolean starQuotaOk = remainingQuota == Long.MAX_VALUE || starUnit <= remainingQuota;
                boolean starAffordable = starUnit > 0L && starQuotaOk;

                if (ruleSpec.mode == CurrencyMode.EITHER) {
                    if (starAffordable) {
                        soldInThisSlot++;
                        starGainInThisSlot += starUnit;
                        if (remainingQuota != Long.MAX_VALUE) {
                            remainingQuota -= starUnit;
                        }
                        continue;
                    }
                    if (physReady) {
                        soldInThisSlot++;
                        physGainInThisSlot += ruleSpec.physCount;
                        continue;
                    }
                    break;
                }

                // STAR_ONLY or BOTH
                if (!starAffordable) {
                    break;
                }
                soldInThisSlot++;
                starGainInThisSlot += starUnit;
                if (ruleSpec.mode == CurrencyMode.BOTH) {
                    physGainInThisSlot += ruleSpec.physCount;
                }
                if (remainingQuota != Long.MAX_VALUE) {
                    remainingQuota -= starUnit;
                }
            }

            if (soldInThisSlot <= 0 || (starGainInThisSlot <= 0L && physGainInThisSlot <= 0L)) {
                continue;
            }
            ItemStack soldStack = stack.copyWithCount(soldInThisSlot);
            totalStarPayout += starGainInThisSlot;
            if (physGainInThisSlot > 0L && !canonTierKey.isEmpty()) {
                physPayoutByTier.merge(canonTierKey, physGainInThisSlot, Long::sum);
            }
            sold += soldInThisSlot;
            soldNames.add(soldStack.getHoverName().getString() + "×" + soldInThisSlot);
            if (perDay > 0) {
                bin.ruleUsed.put(rule.line(), usedCount + soldInThisSlot);
            }
            bin.slots.set(i, stack.getCount() > soldInThisSlot
                    ? stack.copyWithCount(stack.getCount() - soldInThisSlot) : ItemStack.EMPTY);
        }
        if (sold <= 0 || (totalStarPayout <= 0L && physPayoutByTier.isEmpty())) {
            ServerPlayer blocked = server.getPlayerList().getPlayer(owner);
            if (blocked != null && !bin.isEmpty()) {
                if (physUnavailable) {
                    notifyPlayer(blocked, Component.translatableWithFallback(
                            "msg.module_shop.phys_tier_unavailable",
                            "出货箱所需实体货币档位未启用或不存在，部分物品已保留")
                            .withStyle(ChatFormatting.YELLOW));
                } else if (blockedPhys && !bankFull) {
                    notifyPlayer(blocked, Component.translatableWithFallback(
                                    "msg.module_shop.phys_sell_blocked_arbitrage",
                                    "防套利保护：已启用的实体货币物品禁止通过出货箱出售")
                            .withStyle(ChatFormatting.YELLOW));
                } else {
                    notifyPlayer(blocked, Component.translatable(bankFull ? "msg.module_shop.shipping_bank_full"
                            : "msg.module_shop.shipping_capped").withStyle(ChatFormatting.YELLOW));
                }
            }
            return;
        }
        long credited = 0L;
        if (totalStarPayout > 0L) {
            credited = CurrencySettlement.payoutStarOnly(
                    server, owner, account, data, totalStarPayout, false, true,
                    SOURCE_SHIPPING_UNPAID, "", ItemStack.EMPTY);
        }
        for (Map.Entry<String, Long> pe : physPayoutByTier.entrySet()) {
            if (pe.getValue() != null && pe.getValue() > 0L) {
                CurrencySettlement.payoutPhysTier(server, owner, pe.getKey(), pe.getValue(), "shipping");
            }
        }
        String payoutText = formatShippingPayoutSummary(server, credited, physPayoutByTier);
        bin.soldToday += credited;
        bin.lastPayout = credited;
        bin.lastPhysPayout.clear();
        bin.lastPhysPayout.putAll(physPayoutByTier);
        bin.lastPayoutSummary = payoutText;
        bin.lastCount = sold;
        data.setDirty();
        String itemNote = String.join("、", soldNames.size() > 6 ? soldNames.subList(0, 6) : soldNames)
                + (soldNames.size() > 6 ? " 等" : "");
        if (!physPayoutByTier.isEmpty() && credited <= 0L) {
            itemNote = itemNote + " [" + payoutText + "]";
        }
        TransactionLog.record(server, owner, TransactionLog.SELL, "出货箱", credited, account.balance, itemNote);
        ServerPlayer online = server.getPlayerList().getPlayer(owner);
        if (online != null) {
            if (physPayoutByTier.isEmpty()) {
                notifyPlayer(online, Component.translatable("msg.module_shop.shipping_sold",
                        sold, credited).withStyle(ChatFormatting.GREEN));
            } else {
                notifyPlayer(online, Component.translatableWithFallback(
                        "msg.module_shop.shipping_sold_dual",
                        "出货箱结算完成：共卖出 %s 件，获得 %s",
                        sold, payoutText).withStyle(ChatFormatting.GREEN));
            }
            if (!bin.isEmpty()) {
                if (physUnavailable) {
                    notifyPlayer(online, Component.translatableWithFallback(
                            "msg.module_shop.phys_tier_unavailable",
                            "出货箱所需实体货币档位未启用或不存在，部分物品已保留")
                            .withStyle(ChatFormatting.YELLOW));
                } else if (blockedPhys) {
                    notifyPlayer(online, Component.translatableWithFallback(
                            "msg.module_shop.phys_sell_blocked_arbitrage",
                            "防套利保护：已启用的实体货币物品禁止通过出货箱出售")
                            .withStyle(ChatFormatting.RED));
                } else if (remainingQuota != Long.MAX_VALUE && remainingQuota <= 0L) {
                    notifyPlayer(online, Component.translatable(bankFull
                                    ? "msg.module_shop.shipping_bank_full"
                                    : "msg.module_shop.shipping_capped")
                            .withStyle(ChatFormatting.YELLOW));
                }
            }
            syncOpenBin(online);
            sendSnapshot(online, "shipping");
        } else {
            // 离线结算：登录后补一条提示，别让人不知道这笔钱是哪来的。
            bin.pendingNotice = true;
        }
    }

    private static String formatShippingPayoutSummary(MinecraftServer server, long starAmount,
                                                      Map<String, Long> physCounts) {
        List<String> parts = new ArrayList<>();
        if (starAmount > 0L) {
            parts.add(starAmount + " " + currencyName(server));
        }
        if (physCounts != null) {
            for (Map.Entry<String, Long> e : physCounts.entrySet()) {
                if (e.getValue() != null && e.getValue() > 0L) {
                    parts.add(e.getValue() + " " + CurrencySettlement.tierDisplayName(e.getKey(), server));
                }
            }
        }
        return parts.isEmpty() ? ("0 " + currencyName(server)) : String.join(" + ", parts);
    }

    /**
     * 结算之后把玩家**正开着的**出货箱界面同步成最新内容。
     *
     * <p>结算改的是存档里的 {@code bin.slots}，而打开着的容器持有的是**另一份拷贝** ——
     * 玩家此时随便动一下格子，{@code ShippingBinContainer.setChanged()} 就会把那份旧内容整体写回存档，
     * 于是已经卖掉（钱也到账）的格子又活了（相当于复制物品）。这里先按存档刷一遍界面，堵掉这条重复刷物路径。
     */
    private static void syncOpenBin(ServerPlayer player) {
        if (!(player.containerMenu instanceof cn.autoforged.module_shop.menu.ShippingBinMenu menu)) {
            return;
        }
        if (!(menu.bin() instanceof ShippingBinContainer container)) {
            return;
        }
        container.reloadFromBin();
        menu.broadcastChanges();
    }

    private record PendingShippingQuote(long starTotal, Map<String, Long> physCounts) {
    }

    /** 出货箱当前能拿到多少钱（双账本预估：按星币与各阶实体币分别汇总）。 */
    private static PendingShippingQuote shippingPendingQuote(PlayerAccount account, long realDay,
                                                             @Nullable HolderLookup.Provider regs) {
        ShippingBin bin = account.shipping;
        long limit = Config.shippingDailyLimit(bin.level);
        long soldToday = bin.soldDay == realDay ? bin.soldToday : 0L;
        long remaining = limit == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0L, limit - soldToday);
        PhysicalCurrencyConfig physCfg = PhysicalCurrencyConfig.get();
        boolean physActive = physCfg.isActive();

        long total = 0L;
        Map<String, Long> physTotals = new java.util.LinkedHashMap<>();
        Map<Integer, Integer> counted = new HashMap<>();
        for (ItemStack stack : bin.slots) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ShippingRules.Rule rule = ShippingRules.match(stack);
            if (rule == null) {
                continue;
            }
            PriceSpec ruleSpec = rule.resolvePriceSpec();
            if (!ruleSpec.isValid()) {
                continue;
            }
            if (regs != null && CurrencySettlement.isBlockingBuybackAndShippingForSpec(
                    stack, ruleSpec, regs, rule.kind() == ShippingRules.Kind.ITEM)) {
                continue;
            }
            int perDay = rule.dailyMax();
            int used = bin.soldDay == realDay ? bin.ruleUsed.getOrDefault(rule.line(), 0) : 0;
            int allowed = perDay <= 0 ? stack.getCount()
                    : Math.max(0, perDay - used - counted.getOrDefault(rule.line(), 0));
            int tryCount = Math.min(stack.getCount(), Math.max(0, allowed));
            if (tryCount <= 0) {
                continue;
            }
            boolean physReady = ruleSpec.supportsPhys() && physActive && regs != null
                    && physCfg.findResolvedTier(ruleSpec.physTierKey, regs) != null;
            String canonTierKey = physReady ? physCfg.canonicalTierKey(ruleSpec.physTierKey) : "";
            if ((ruleSpec.mode == CurrencyMode.PHYS_ONLY || ruleSpec.mode == CurrencyMode.BOTH) && !physReady) {
                continue;
            }
            int soldHere = 0;
            for (int k = 0; k < tryCount; k++) {
                if (ruleSpec.mode == CurrencyMode.PHYS_ONLY) {
                    physTotals.merge(canonTierKey, ruleSpec.physCount, Long::sum);
                    soldHere++;
                    continue;
                }
                long unit = ruleSpec.supportsStar() ? ruleSpec.starPrice : 0L;
                boolean starFit = unit > 0L && (remaining == Long.MAX_VALUE || total + unit <= remaining);
                if (ruleSpec.mode == CurrencyMode.EITHER) {
                    if (starFit) {
                        total += unit;
                        soldHere++;
                        continue;
                    }
                    if (physReady) {
                        physTotals.merge(canonTierKey, ruleSpec.physCount, Long::sum);
                        soldHere++;
                        continue;
                    }
                    break;
                }
                if (!starFit) {
                    break;
                }
                total += unit;
                if (ruleSpec.mode == CurrencyMode.BOTH) {
                    physTotals.merge(canonTierKey, ruleSpec.physCount, Long::sum);
                }
                soldHere++;
            }
            if (perDay > 0 && soldHere > 0) {
                counted.merge(rule.line(), soldHere, Integer::sum);
            }
        }
        return new PendingShippingQuote(total, physTotals);
    }

    private static void sendShippingInfo(ServerPlayer player, CompoundTag tag, ShopSavedData data) {
        PlayerAccount account = account(player);
        ShippingBin bin = account.shipping;
        int slots = Config.shippingSlotsFor(bin.level);
        bin.resize(slots);
        long realDay = Config.shippingQuotaDay();
        long limit = Config.shippingDailyLimit(bin.level);
        tag.putBoolean("shipEnabled", Config.INSTANCE.shippingEnabled.get());
        tag.putInt("shipLevel", bin.level);
        tag.putInt("shipMaxLevel", Config.shippingLevels(data));
        tag.putInt("shipSlots", slots);
        tag.putInt("shipItems", bin.itemCount());
        var pendingQuote = shippingPendingQuote(account, realDay, player.level().registryAccess());
        tag.putLong("shipValue", pendingQuote.starTotal());
        CompoundTag shipPhysTag = new CompoundTag();
        String firstPhysTier = "";
        long firstPhysCount = 0L;
        for (Map.Entry<String, Long> pe : pendingQuote.physCounts().entrySet()) {
            if (pe.getKey() != null && !pe.getKey().isBlank() && pe.getValue() != null && pe.getValue() > 0L) {
                shipPhysTag.putLong(pe.getKey(), pe.getValue());
                if (firstPhysTier.isEmpty()) {
                    firstPhysTier = pe.getKey();
                    firstPhysCount = pe.getValue();
                }
            }
        }
        if (!shipPhysTag.isEmpty()) {
            tag.put("shipPhysValue", shipPhysTag);
        }
        if (pendingQuote.starTotal() > 0L && firstPhysCount > 0L && pendingQuote.physCounts().size() == 1) {
            tag.put("shipValueSpec", PriceSpec.both(pendingQuote.starTotal(), firstPhysTier, firstPhysCount).toTag());
        } else if (pendingQuote.starTotal() > 0L) {
            tag.put("shipValueSpec", PriceSpec.star(pendingQuote.starTotal()).toTag());
        } else if (firstPhysCount > 0L && pendingQuote.physCounts().size() == 1) {
            tag.put("shipValueSpec", PriceSpec.phys(firstPhysTier, firstPhysCount).toTag());
        }
        tag.putLong("shipSoldToday", bin.soldDay == realDay ? bin.soldToday : 0L);
        tag.putLong("shipLimit", limit == Long.MAX_VALUE ? 0L : limit);
        List<PriceSpec> upgradeSpecs = data.resolveShippingUpgradeSpecs();
        PriceSpec nextUpgrade = data.shippingUpgradeSpecFor(bin.level);
        tag.putLong("shipUpgradeCost", nextUpgrade == null ? -1L
                : (nextUpgrade.supportsStar() ? nextUpgrade.starPrice : 0L));
        if (nextUpgrade != null) {
            tag.put("shipUpgradeSpec", nextUpgrade.toTag());
        }
        tag.put("shippingUpgradeSpecs", writeSpecList(upgradeSpecs));
        tag.putInt("shipSettleTick", Config.INSTANCE.shippingSettleTick.get());
        tag.putBoolean("shipUseGameTime", SHIPPING_CLOCK.isFallbackGameTime());
        tag.putString("shipResetTime", Config.INSTANCE.shippingResetTime.get());
        List<ShippingRules.Rule> rules = ShippingRules.rules();
        List<String> errors = ShippingRules.errors();
        tag.putInt("shipRules", rules.size());
        tag.putInt("shipRuleErrors", errors.size());
        tag.putString("shipRuleErrorText", errors.isEmpty() ? "" : errors.get(0));
        // 把生效的规则原文一起下发：出货箱容器界面据此**边放边算**本次结算金额（不用每次开箱都往返服务端）。
        StringBuilder ruleText = new StringBuilder();
        for (String line : Config.INSTANCE.shippingRules.get()) {
            ruleText.append(line).append('\n');
        }
        tag.putString("shipRulesText", ruleText.toString());
        // 每个等级的每日上限（管理页「每日额度」弹窗回填）
        long[] limits = new long[Config.shippingLevels(data)];
        for (int i = 0; i < limits.length; i++) {
            long value = Config.shippingDailyLimit(i + 1);
            limits[i] = value == Long.MAX_VALUE ? 0L : value;
        }
        tag.putLongArray("shipDailyLimits", limits);
        CompoundTag usedTag = new CompoundTag();
        if (bin.soldDay == realDay && !bin.ruleUsed.isEmpty()) {
            for (Map.Entry<Integer, Integer> entry : bin.ruleUsed.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
                    usedTag.putInt(Integer.toString(entry.getKey()), entry.getValue());
                }
            }
        }
        tag.put("shipRuleUsed", usedTag);
    }

    /**
     * 管理员：修改「每个等级的每日结算额度」+ **结算时间（游戏时间）** + **额度重置时间（现实时间）**
     * （出货箱管理页 → 「每日额度」按钮）。直接写回配置文件；额度 0 = 不限，时间格式 HH:mm。
     */
    private static void adminShippingLimits(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        ShopSavedData data = data(player.server);
        int maxLevels = Config.shippingLevels(data);
        List<String> limits = new ArrayList<>();
        for (int i = 1; i <= maxLevels; i++) {
            String key = "limit" + i;
            if (!tag.contains(key)) {
                break;
            }
            long value = Math.max(0L, parsePositive(tag.getString(key)));
            limits.add(Long.toString(value));
        }
        if (limits.isEmpty()) {
            return;
        }
        // 时间字段：格式不对就明确报错并且**不保存**（避免把配置写坏）
        String settleText = tag.getString("settle_time").trim();
        int settleTick = -1;
        if (!settleText.isEmpty()) {
            settleTick = Config.clockToDayTick(settleText);
            if (settleTick < 0) {
                notifyPlayer(player, Component.translatable("msg.module_shop.shipping_time_bad", settleText)
                        .withStyle(ChatFormatting.RED));
                return;
            }
        }
        String resetText = tag.getString("reset_time").trim();
        if (!resetText.isEmpty() && Config.parseClock(resetText) < 0) {
            notifyPlayer(player, Component.translatable("msg.module_shop.shipping_time_bad", resetText)
                    .withStyle(ChatFormatting.RED));
            return;
        }
        Config.INSTANCE.shippingDailyLimits.set(List.copyOf(limits));
        if (settleTick >= 0) {
            Config.INSTANCE.shippingSettleTick.set(settleTick);
        }
        if (!resetText.isEmpty()) {
            Config.INSTANCE.shippingResetTime.set(resetText);
        }
        Config.SPEC.save();
        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < limits.size(); i++) {
            if (i > 0) {
                shown.append(" / ");
            }
            shown.append("Lv.").append(i + 1).append(' ').append(
                    "0".equals(limits.get(i)) ? "不限" : limits.get(i));
        }
        notifyPlayer(player, Component.translatable("msg.module_shop.shipping_limits_saved",
                shown.toString(), Config.dayTickToClock(Config.INSTANCE.shippingSettleTick.get()),
                Config.INSTANCE.shippingResetTime.get()).withStyle(ChatFormatting.GREEN));
        sendSnapshot(player, "admin");
    }

    /**
     * 管理员：购物城「补货设置」—— 是否定时补货 / 时间口径（现实 or 游戏）/ 补货时刻 / 补货数量。
     * 直接写回配置文件，格式不对明确报错并且**不保存**（避免把配置写坏）。
     */
    private static void adminMallRestock(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        boolean enabled = "1".equals(tag.getString("restock_on").trim())
                || "true".equalsIgnoreCase(tag.getString("restock_on").trim());
        String modeText = tag.getString("restock_mode").trim();
        String mode = "game".equalsIgnoreCase(modeText) ? "game" : "real";
        String timeText = tag.getString("restock_time").trim();
        if (!timeText.isEmpty() && Config.parseClock(timeText) < 0) {
            notifyPlayer(player, Component.translatable("msg.module_shop.shipping_time_bad", timeText)
                    .withStyle(ChatFormatting.RED));
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(tag.getString("restock_amount").trim());
        } catch (NumberFormatException ex) {
            notifyPlayer(player, Component.translatable("msg.module_shop.mall_restock_bad_amount")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (amount < 0 || amount > 1000000) {
            notifyPlayer(player, Component.translatable("msg.module_shop.mall_restock_bad_amount")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        Config.INSTANCE.mallRestockEnabled.set(enabled);
        Config.INSTANCE.mallRestockMode.set(mode);
        if (!timeText.isEmpty()) {
            Config.INSTANCE.mallRestockTime.set(timeText);
        }
        Config.INSTANCE.mallRestockAmount.set(amount);
        Config.SPEC.save();
        notifyPlayer(player, Component.translatable("msg.module_shop.mall_restock_saved",
                Component.translatable(enabled ? "gui.module_shop.choice.restock_on.1"
                        : "gui.module_shop.choice.restock_on.0"),
                Component.translatable("gui.module_shop.choice.restock_mode." + mode),
                Config.mallRestockTime(),
                amount <= 0 ? Component.translatable("gui.module_shop.mall.restock_keep_each")
                        : Component.literal(Integer.toString(amount)))
                .withStyle(ChatFormatting.GREEN));
        sendSnapshot(player, "admin");
    }

    /** 管理员：一键把某个模组在购物城里的**全部物品**下架（配合「导入模组」的误操作回滚）。 */
    private static void adminMallRemoveMod(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        String namespace = tag.getString("mod").trim();
        if (namespace.isEmpty()) {
            return;
        }
        ShopSavedData data = data(player.server);
        int before = data.mallPool.size();
        data.mallPool.removeIf(item -> {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item.stack.getItem());
            return id != null && namespace.equals(id.getNamespace());
        });
        int removed = before - data.mallPool.size();
        data.setDirty();
        sendSnapshot(player, "admin");
        notifyPlayer(player, Component.translatable("msg.module_shop.mall_remove_done", namespace, removed)
                .withStyle(ChatFormatting.GREEN));
    }

    /** 需求 9：玩家可自行选择卖出数量（兼容入口，自动按默认账本通道结算）。 */
    public static void sellBuyback(ServerPlayer player, int index, int requested) {
        sellBuyback(player, index, requested, "");
    }

    /** 每日收购：按所选账本通道结算（PHYS_ONLY 绕过星币上限；EITHER 支持指定通道）。 */
    public static void sellBuyback(ServerPlayer player, int index, int requested, @Nullable String payChannel) {
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            ShopSavedData data = data(player.server);
            ensureDaily(player, account, data);
            if (index < 0 || index >= account.dailyBuyback.size()) {
                return;
            }
            DailyBuybackEntry entry = account.dailyBuyback.get(index);
            PriceSpec entrySpec = entry.resolvePriceSpec();
            if (!entrySpec.isValid()) {
                notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price").withStyle(ChatFormatting.RED));
                return;
            }
            if (CurrencySettlement.isBlockingBuybackAndShippingForSpec(
                    entry.stack, entrySpec, player.level().registryAccess(), true)) {
                notifyPlayer(player, Component.translatableWithFallback(
                        "msg.module_shop.phys_sell_blocked_arbitrage",
                        "防套利保护：已启用的实体货币物品禁止通过每日收购卖出")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            if (entry.remaining() <= 0) {
                notifyPlayer(player, Component.translatable("msg.module_shop.buyback_full").withStyle(ChatFormatting.RED));
                return;
            }
            long max = Config.maxBalanceLimit();
            long room = Math.max(0L, max - account.balance);
            String ch = payChannel == null ? "" : payChannel.trim().toLowerCase(java.util.Locale.ROOT);
            long starUnit = entrySpec.supportsStar() ? entrySpec.starPrice : 0L;

            CurrencyMode targetMode;
            switch (entrySpec.mode) {
                case STAR_ONLY -> {
                    if ("phys".equals(ch)) {
                        CurrencySettlement.notifyPayFailure(player, CurrencySettlement.PayResult.fail(
                                "msg.module_shop.channel_not_supported", "该收购项仅支持星币结算"));
                        return;
                    }
                    targetMode = CurrencyMode.STAR_ONLY;
                }
                case PHYS_ONLY -> {
                    if ("star".equals(ch)) {
                        CurrencySettlement.notifyPayFailure(player, CurrencySettlement.PayResult.fail(
                                "msg.module_shop.channel_not_supported", "该收购项仅支持实体货币结算"));
                        return;
                    }
                    targetMode = CurrencyMode.PHYS_ONLY;
                }
                case BOTH -> targetMode = CurrencyMode.BOTH;
                case EITHER -> {
                    if ("phys".equals(ch)) {
                        targetMode = CurrencyMode.PHYS_ONLY;
                    } else if ("star".equals(ch)) {
                        targetMode = CurrencyMode.STAR_ONLY;
                    } else if (ch.isEmpty()) {
                        boolean starViable = starUnit > 0L && room >= starUnit;
                        boolean physViable = PhysicalCurrencyConfig.get().isActive()
                                && PhysicalCurrencyConfig.get().findTier(entrySpec.physTierKey) != null;
                        targetMode = (!starViable && physViable) ? CurrencyMode.PHYS_ONLY : CurrencyMode.STAR_ONLY;
                    } else {
                        CurrencySettlement.notifyPayFailure(player, CurrencySettlement.PayResult.fail(
                                "msg.module_shop.channel_not_supported", "无效的支付账本通道"));
                        return;
                    }
                }
                default -> targetMode = CurrencyMode.STAR_ONLY;
            }

            if (targetMode.includesPhys()) {
                PhysicalCurrencyConfig physCfg = PhysicalCurrencyConfig.get();
                if (!physCfg.isActive() || physCfg.findTier(entrySpec.physTierKey) == null) {
                    notifyPlayer(player, Component.translatableWithFallback(
                            "msg.module_shop.phys_tier_unavailable", "所需的实体货币档位未启用或不存在")
                            .withStyle(ChatFormatting.RED));
                    return;
                }
            }

            int affordable;
            if (targetMode.includesStar()) {
                if (starUnit <= 0L) {
                    notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price").withStyle(ChatFormatting.RED));
                    return;
                }
                if (room < starUnit) {
                    notifyPlayer(player, Component.translatable("msg.module_shop.grant_cap_reached").withStyle(ChatFormatting.RED));
                    return;
                }
                affordable = (int) Math.min(Integer.MAX_VALUE, room / starUnit);
            } else {
                affordable = entrySpec.physCount <= 0L
                        ? 0
                        : (int) Math.min(Integer.MAX_VALUE, (Long.MAX_VALUE / 2L) / entrySpec.physCount);
            }

            int wanted = Math.min(requested <= 0 ? 1 : requested, Math.min(entry.remaining(), affordable));
            int taken = takeMatching(player, entry.stack.getItem(), wanted);
            if (taken <= 0) {
                notifyPlayer(player, Component.translatable("msg.module_shop.not_enough_items").withStyle(ChatFormatting.RED));
                return;
            }

            long starTotal = targetMode.includesStar() ? safeMultiply(starUnit, taken) : 0L;
            long physTotal = targetMode.includesPhys() ? safeMultiply(entrySpec.physCount, taken) : 0L;
            if ((targetMode.includesStar() && starTotal <= 0L)
                    || (targetMode.includesPhys() && physTotal <= 0L)) {
                deliver(player, entry.stack, taken, SOURCE_ADMIN);
                notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price").withStyle(ChatFormatting.RED));
                return;
            }

            long credited = 0L;
            if (targetMode.includesStar()) {
                credited = CurrencySettlement.payoutStarOnly(
                        player.server, player.getUUID(), account, data, starTotal, false, false, SOURCE_ADMIN, null, null);
                if (credited <= 0L) {
                    deliver(player, entry.stack, taken, SOURCE_ADMIN);
                    notifyPlayer(player, Component.translatable("msg.module_shop.grant_cap_reached")
                            .withStyle(ChatFormatting.YELLOW));
                    return;
                }
            }
            if (targetMode.includesPhys()) {
                CurrencySettlement.payoutPhysTier(
                        player.server, player.getUUID(), entrySpec.physTierKey, physTotal, SOURCE_ADMIN);
            }

            entry.sold += taken;
            data.setDirty();
            PriceSpec paidSpec = switch (targetMode) {
                case PHYS_ONLY -> PriceSpec.phys(entrySpec.physTierKey, physTotal);
                case BOTH -> PriceSpec.both(credited, entrySpec.physTierKey, physTotal);
                default -> PriceSpec.star(credited);
            };
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.SELL, "模块商店",
                    credited, account.balance,
                    entry.stack.getHoverName().getString() + "×" + taken
                            + (targetMode.includesPhys() ? " [" + CurrencySettlement.formatSpec(paidSpec, player.server) + "]" : ""));
            if (targetMode.includesPhys()) {
                notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.sold_count_spec",
                        "已卖出 %s×%s，获得 %s", entry.stack.getHoverName(), taken,
                        CurrencySettlement.formatSpec(paidSpec, player.server)).withStyle(ChatFormatting.GREEN));
            } else {
                notifyPlayer(player, Component.translatable("msg.module_shop.sold_count",
                        entry.stack.getHoverName(), taken, credited).withStyle(ChatFormatting.GREEN));
            }
            // 当日收购清单全部卖完 → 一次性奖励（在推送快照前结算，界面立刻能看到）。
            maybeGrantBuybackBonus(player, account, data);
            sendSnapshot(player, "buyback");
        }
    }

    /** 从背包中按数量取走匹配物品，返回实际取走的数量。 */
    private static int takeMatching(ServerPlayer player, Item item, int max) {
        Inventory inventory = player.getInventory();
        int taken = 0;
        for (int i = 0; i < inventory.getContainerSize() && taken < max; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || stack.getItem() != item) {
                continue;
            }
            int take = Math.min(max - taken, stack.getCount());
            stack.shrink(take);
            taken += take;
            if (stack.isEmpty()) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        return taken;
    }

    // =========================================================== 拍卖

    private static void auctionSetStart(ServerPlayer player, CompoundTag tag) {
        ItemStack stack = PENDING_ITEM.remove(player.getUUID());
        if (stack == null || stack.isEmpty()) {
            return;
        }
        long price = parsePrice(tag.getString("price"));
        if (price <= 0) {
            addItemOrMail(player, stack, SOURCE_ADMIN);
            notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price").withStyle(ChatFormatting.RED));
            return;
        }
        // 旧流程（存放槽 + 起拍价）保留给无 Skija 界面的客户端：规则、手续费、黑名单与新界面完全一致。
        cn.autoforged.module_shop.shop.auction.AuctionService.createLegacy(player, stack, price);
        sendSnapshot(player, "auction");
    }

    /** 出价（兼容入口：公共 API 与旧界面都走这里），实际逻辑见 AuctionService.bid。 */
    public static void placeBid(ServerPlayer player, CompoundTag tag) {
        cn.autoforged.module_shop.shop.auction.AuctionService.bid(player, tag);
    }

    // =========================================================== 摊位

    public static void stallSetPriceNew(ServerPlayer player, CompoundTag tag) {
        synchronized (LOCK) {
            ItemStack stack = PENDING_ITEM.remove(player.getUUID());
            if (stack == null || stack.isEmpty()) {
                return;
            }
            PriceSpec spec = cn.autoforged.module_shop.shop.stall.StallService.parseStallSpecFromTag(tag);
            // 需求 4：上架时可设置库存数量（单件价格），最大 64，且不能超过实际持有量。
            int requested = parseInt(tag.getString("quantity"));
            int total = Math.min(Config.INSTANCE.stallMaxStock.get(), requested <= 0 ? Math.max(1, stack.getCount()) : requested);
            total = Math.min(total, Math.max(1, stack.getCount()));
            PlayerAccount account = account(player);
            ShopSavedData data = data(player.server);
            if (spec == null || !spec.isValid()
                    || (spec.mode != CurrencyMode.STAR_ONLY && spec.mode != CurrencyMode.PHYS_ONLY)) {
                addItemOrMail(player, stack, SOURCE_ADMIN);
                notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price").withStyle(ChatFormatting.RED));
                return;
            }
            if (spec.mode == CurrencyMode.PHYS_ONLY) {
                PhysicalCurrencyConfig physCfg = PhysicalCurrencyConfig.get();
                var tier = physCfg.findTier(spec.physTierKey);
                if (!physCfg.isActive() || tier == null) {
                    addItemOrMail(player, stack, SOURCE_ADMIN);
                    notifyPlayer(player, Component.translatableWithFallback(
                            "msg.module_shop.phys_tier_unavailable", "所需的实体货币档位未启用或不存在")
                            .withStyle(ChatFormatting.RED));
                    return;
                }
                spec = spec.withPhysTierKey(tier.tierKey());
            } else {
                long cap = Config.maxBalanceLimit();
                if (cap != Long.MAX_VALUE && spec.starPrice > cap) {
                    addItemOrMail(player, stack, SOURCE_ADMIN);
                    notifyPlayer(player, Component.translatable("msg.module_shop.price_over_cap",
                            Long.toString(cap)).withStyle(ChatFormatting.RED));
                    return;
                }
            }
            if (account.stall.size() >= Config.INSTANCE.stallSlots.get()) {
                addItemOrMail(player, stack, SOURCE_ADMIN);
                notifyPlayer(player, Component.translatable("msg.module_shop.stall_full").withStyle(ChatFormatting.RED));
                return;
            }
            double feePercent = Config.INSTANCE.stallListingFeePercent.get();
            if (spec.mode == CurrencyMode.PHYS_ONLY) {
                long fee = feePercent <= 0.0D ? 0L : (long) Math.ceil(spec.physCount * feePercent / 100.0D);
                if (fee > 0L) {
                    var feePay = CurrencySettlement.payPhysTier(player, spec.physTierKey, fee, SOURCE_EXCHANGE);
                    if (!feePay.ok()) {
                        addItemOrMail(player, stack, SOURCE_ADMIN);
                        CurrencySettlement.notifyPayFailure(player, feePay);
                        return;
                    }
                }
            } else {
                long fee = feePercent <= 0.0D ? 0L : (long) Math.ceil(spec.starPrice * feePercent / 100.0D);
                if (fee > 0L) {
                    var feePay = CurrencySettlement.payStar(account, fee, "fee");
                    if (!feePay.ok()) {
                        addItemOrMail(player, stack, SOURCE_ADMIN);
                        CurrencySettlement.notifyPayFailure(player, feePay);
                        return;
                    }
                }
            }
            // 超出上架数量的部分原路退还，避免物品丢失。
            if (total < stack.getCount()) {
                ItemStack leftover = stack.copy();
                leftover.setCount(stack.getCount() - total);
                addItemOrMail(player, leftover, SOURCE_STALL_UNSOLD);
            }
            ItemStack unit = stack.copy();
            unit.setCount(1);
            StallEntry fresh = new StallEntry(unit, spec, data.listingCounter++, total);
            // 「商店设置 → 摊位下架时间」：记下上架时刻（游戏刻 + 现实毫秒各一份），限时到期由 tickStallExpire 下架。
            fresh.listedGame = player.server.overworld().getGameTime();
            fresh.listedReal = System.currentTimeMillis();
            account.stall.add(fresh);
            data.setDirty();
            if (spec.mode == CurrencyMode.PHYS_ONLY) {
                notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.stall_listed_spec",
                        "已上架 %s，单价 %s，库存 %s",
                        unit.getHoverName(), CurrencySettlement.formatSpec(spec, player.server), total)
                        .withStyle(ChatFormatting.GREEN));
            } else {
                notifyPlayer(player, Component.translatable("msg.module_shop.stall_listed",
                        unit.getHoverName(), spec.starPrice, total).withStyle(ChatFormatting.GREEN));
            }
            sendSnapshot(player, "stall");
            broadcastSnapshots(player.server);
        }
    }

    // =========================================================== 玩家求购（悬赏）

    /** 求购单托管退款的物品来源。 */
    public static final String SOURCE_DEMAND_REFUND = "demand_refund";
    /** 求购成交后付给交付者的物品来源。 */
    public static final String SOURCE_DEMAND_REWARD = "demand_reward";
    /** 求购交付的物品投递给发布者（背包 / 邮箱）。 */
    public static final String SOURCE_DEMAND_DELIVER = "demand_deliver";

    /** 按短 id 找求购单。 */
    private static DemandEntry demandOf(ShopSavedData data, String id) {
        if (data == null || id == null || id.isBlank()) {
            return null;
        }
        for (DemandEntry entry : data.demands) {
            if (id.equals(entry.id)) {
                return entry;
            }
        }
        return null;
    }

    /** 某玩家当前发布了几条求购。 */
    private static int demandCountOf(ShopSavedData data, UUID who) {
        int count = 0;
        for (DemandEntry entry : data.demands) {
            if (who != null && who.equals(entry.requester)) {
                count++;
            }
        }
        return count;
    }

    /** 生成 8 位十六进制的短单号。 */
    private static String nextDemandId(ShopSavedData data) {
        long value = data.demandCounter++;
        return String.format("%08X", value & 0xFFFFFFFFL);
    }

    /**
     * 把一条求购的**托管悬赏**退还给发布者：金币进余额（装不下的转金钱邮件），
     * 物品进背包（装不下 / 离线则进邮箱）。绝不销毁。
     */
    private static void refundDemand(MinecraftServer server, DemandEntry entry, String sender) {
        if (server == null || entry == null) {
            return;
        }
        if (entry.requester == null) {
            return;
        }
        ShopSavedData data = data(server);
        PlayerAccount account = data.account(entry.requester);
        payoutDemandCurrency(server, entry.requester, account, entry, SOURCE_DEMAND_REFUND, sender,
                "求购退款", "求购托管退回");
        if (entry.rewardItem != null && !entry.rewardItem.isEmpty()) {
            ItemStack refund = entry.rewardItem.copy();
            ServerPlayer online = server.getPlayerList().getPlayer(entry.requester);
            if (online != null) {
                addItemOrMail(online, refund, SOURCE_DEMAND_REFUND);
            } else {
                addMail(server, entry.requester, refund, SOURCE_DEMAND_REFUND, sender);
            }
        }
        data.setDirty();
    }

    private static void payoutDemandCurrency(MinecraftServer server, UUID targetId, PlayerAccount targetAccount,
                                             DemandEntry entry, String mailSource, String senderName,
                                             String txSource, String txNote) {
        PriceSpec rSpec = entry.resolveRewardSpec();
        if (rSpec.isZero()) {
            return;
        }
        // 兼容旧存档遗留的混合实体托管字段
        if (entry.physicalEscrow > 0L && rSpec.mode == CurrencyMode.STAR_ONLY) {
            long physUnits = Math.min(rSpec.starPrice, entry.physicalEscrow);
            long starPart = Math.max(0L, rSpec.starPrice - physUnits);
            deliverLegacyPhysicalEscrow(server, targetId, physUnits, mailSource);
            long starSettled = CurrencySettlement.payoutStarOnly(
                    server, targetId, targetAccount, null, starPart, false, true,
                    mailSource, senderName, entry.rewardItem);
            if (starSettled + physUnits > 0L) {
                TransactionLog.record(server, targetId, TransactionLog.TRANSFER_IN,
                        txSource, starSettled, targetAccount.balance, txNote);
            }
            return;
        }
        var res = CurrencySettlement.payoutSpec(
                server, targetId, targetAccount, null, rSpec, 1, null,
                mailSource, senderName, entry.rewardItem, true, false);
        if (res.starCredited() > 0L || res.physPaidCount() > 0L) {
            String note = res.physPaidCount() > 0L
                    ? txNote + " [" + CurrencySettlement.formatSpec(rSpec, server) + "]" : txNote;
            TransactionLog.record(server, targetId, TransactionLog.TRANSFER_IN,
                    txSource, res.starCredited(), targetAccount.balance, note);
        }
    }

    private static void deliverLegacyPhysicalEscrow(MinecraftServer server, UUID targetId, long units, String source) {
        if (server == null || targetId == null || units <= 0L) {
            return;
        }
        HolderLookup.Provider regs = server.registryAccess();
        PhysicalCurrencyConfig cfg = PhysicalCurrencyConfig.get();
        List<PhysicalCurrencyConfig.ResolvedTier> tiers = cfg.resolveAllForDisplay(regs);
        if (tiers.isEmpty()) {
            return;
        }
        long[] vals = new long[tiers.size()];
        for (int i = 0; i < tiers.size(); i++) {
            vals[i] = tiers.get(i).template.isEmpty() ? 0L : tiers.get(i).spec.value;
        }
        var plan = PhysicalCurrencyEngine.decomposePayout(units, vals);
        ServerPlayer online = server.getPlayerList().getPlayer(targetId);
        for (int i = tiers.size() - 1; i >= 0; i--) {
            int cnt = plan.counts()[i];
            if (cnt > 0 && !tiers.get(i).template.isEmpty()) {
                PhysicalCurrencyEngine.deliverPhysicalItems(server, targetId, online, tiers.get(i).template, cnt, source);
            }
        }
    }

    /** 解析求购单选账本赏金（支持 ZERO / STAR_ONLY / PHYS_ONLY）。返回 null 表示参数非法已拦截并提示。 */
    private static @Nullable PriceSpec parseDemandRewardSpec(ServerPlayer player, CompoundTag tag) {
        PriceSpec raw = null;
        if (tag.contains("rewardSpec", Tag.TAG_COMPOUND)) {
            raw = PriceSpec.fromTag(tag.getCompound("rewardSpec"), 0L);
        } else if (tag.contains("priceSpec", Tag.TAG_COMPOUND)) {
            raw = PriceSpec.fromTag(tag.getCompound("priceSpec"), 0L);
        } else if (tag.contains("currencyMode", Tag.TAG_STRING) || tag.contains("physTier", Tag.TAG_STRING)) {
            String modeId = tag.getString("currencyMode").trim();
            String tier = tag.getString("physTier").trim();
            long cnt = tag.contains("physCount", Tag.TAG_ANY_NUMERIC) ? tag.getLong("physCount")
                    : parsePositive(tag.getString("reward_money"));
            long star = tag.contains("starPrice", Tag.TAG_ANY_NUMERIC) ? tag.getLong("starPrice")
                    : parsePositive(tag.getString("reward_money"));
            if ("phys".equalsIgnoreCase(modeId) || (!tier.isEmpty() && !"star".equalsIgnoreCase(modeId))) {
                raw = cnt <= 0L ? PriceSpec.ZERO : PriceSpec.phys(tier, cnt);
            } else {
                raw = star <= 0L ? PriceSpec.ZERO : PriceSpec.star(star);
            }
        } else {
            String rawText = tag.getString("reward_money").trim();
            if (rawText.isEmpty() || "0".equals(rawText)) {
                raw = PriceSpec.ZERO;
            } else {
                raw = PriceSpec.parseRuleText(rawText);
            }
        }
        if (raw == null) {
            notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price").withStyle(ChatFormatting.RED));
            return null;
        }
        if (raw.isZero()) {
            return PriceSpec.ZERO;
        }
        if (raw.mode != CurrencyMode.STAR_ONLY && raw.mode != CurrencyMode.PHYS_ONLY) {
            notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price").withStyle(ChatFormatting.RED));
            return null;
        }
        if (raw.mode == CurrencyMode.STAR_ONLY) {
            long star = raw.starPrice;
            long maxReward = Config.demandMaxReward();
            if (maxReward > 0L && star > maxReward) {
                star = maxReward;
            }
            return PriceSpec.star(star);
        }
        PhysicalCurrencyConfig cfg = PhysicalCurrencyConfig.get();
        var tier = cfg.findTier(raw.physTierKey);
        if (!cfg.isActive() || tier == null) {
            notifyPlayer(player, Component.translatableWithFallback(
                    "msg.module_shop.phys_tier_unavailable", "所需的实体货币档位未启用或不存在")
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        if (raw.physCount <= 0L || raw.physCount > (long) Integer.MAX_VALUE * 64L) {
            notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price").withStyle(ChatFormatting.RED));
            return null;
        }
        return PriceSpec.phys(tier.tierKey(), raw.physCount);
    }

    /**
     * 发布求购（悬赏）。支持星币或某阶实体货币×数量作为托管赏金。
     */
    public static void demandCreate(ServerPlayer player, CompoundTag tag) {
        if (!Config.demandEnabled()) {
            notifyPlayer(player, Component.translatable("msg.module_shop.demand_disabled")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        // 想要清单：客户端从「物品搜索界面」发来（最多 12 条，每条 {item:{...}, count}）。
        List<DemandEntry.WantLine> wants = new ArrayList<>();
        ListTag wantTag = tag.getList("wants", 10);
        for (int i = 0; i < wantTag.size() && wants.size() < DemandEntry.MAX_LINES; i++) {
            CompoundTag lineTag = wantTag.getCompound(i);
            ItemStack stack = ShopNbt.loadStack(lineTag, player.level().registryAccess());
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack one = stack.copy();
            one.setCount(1);
            int count = Math.max(1, Math.min(64, lineTag.getInt("count")));
            boolean merged = false;
            for (DemandEntry.WantLine exists : wants) {
                if (ItemStack.isSameItemSameTags(exists.stack, one)) {
                    exists.count = Math.min(64, exists.count + count);
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                wants.add(new DemandEntry.WantLine(one, count));
            }
        }
        if (wants.isEmpty()) {
            notifyPlayer(player, Component.translatable("msg.module_shop.demand_need_hand")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        PriceSpec rewardSpec = parseDemandRewardSpec(player, tag);
        if (rewardSpec == null) {
            return;
        }
        ItemStack rewardItem = player.getOffhandItem().copy();
        if (rewardSpec.isZero() && rewardItem.isEmpty()) {
            notifyPlayer(player, Component.translatable("msg.module_shop.demand_need_reward")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        PlayerAccount account = account(player);
        ShopSavedData data = data(player.server);
        if (demandCountOf(data, player.getUUID()) >= Config.demandSlots()) {
            notifyPlayer(player, Component.translatable("msg.module_shop.demand_full", Config.demandSlots())
                    .withStyle(ChatFormatting.RED));
            return;
        }
        double feePct = Config.demandFeePercent();
        PriceSpec escrowTotalSpec = PriceSpec.ZERO;
        if (!rewardSpec.isZero()) {
            if (rewardSpec.mode == CurrencyMode.PHYS_ONLY) {
                long fee = feePct <= 0.0D ? 0L : (long) Math.ceil(rewardSpec.physCount * feePct / 100.0D);
                long sum = rewardSpec.physCount > Long.MAX_VALUE - fee ? 0L : rewardSpec.physCount + fee;
                if (sum <= 0L) {
                    notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price").withStyle(ChatFormatting.RED));
                    return;
                }
                escrowTotalSpec = PriceSpec.phys(rewardSpec.physTierKey, sum);
            } else {
                long fee = feePct <= 0.0D ? 0L : (long) Math.ceil(rewardSpec.starPrice * feePct / 100.0D);
                long sum = rewardSpec.starPrice > Long.MAX_VALUE - fee ? 0L : rewardSpec.starPrice + fee;
                if (sum <= 0L) {
                    notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price").withStyle(ChatFormatting.RED));
                    return;
                }
                escrowTotalSpec = PriceSpec.star(sum);
            }
            var plan = CurrencySettlement.planSpec(player, account, escrowTotalSpec, 1, null);
            if (!plan.ok()) {
                CurrencySettlement.notifyPayFailure(player,
                        CurrencySettlement.PayResult.fail(plan.failKey(), plan.failFallback()));
                return;
            }
        }
        // 先把「最新的那条上限」守住：超出时把最老的一条撤下并退款。
        while (data.demands.size() >= Config.demandMaxListed()) {
            DemandEntry oldest = data.demands.get(0);
            for (DemandEntry candidate : data.demands) {
                if (candidate.createdReal < oldest.createdReal) {
                    oldest = candidate;
                }
            }
            data.demands.remove(oldest);
            refundDemand(player.server, oldest, "系统");
            if (oldest.requester != null) {
                ServerPlayer onlineOwner = player.server.getPlayerList().getPlayer(oldest.requester);
                if (onlineOwner != null) {
                    notifyPlayer(onlineOwner, Component.translatable("msg.module_shop.demand_expired",
                            oldest.summary()).withStyle(ChatFormatting.YELLOW));
                }
            }
        }
        // 托管：按所选账本扣货币（含手续费）、收走副手整叠悬赏物品。
        if (!escrowTotalSpec.isZero()) {
            var escrowPay = CurrencySettlement.paySpec(
                    player, account, data, escrowTotalSpec, 1, null,
                    "demand_escrow", SOURCE_DEMAND_REFUND, false);
            if (!escrowPay.ok()) {
                CurrencySettlement.notifyPayFailure(player, escrowPay);
                return;
            }
        }
        if (!rewardItem.isEmpty()) {
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, ItemStack.EMPTY);
        }
        DemandEntry entry = new DemandEntry();
        entry.id = nextDemandId(data);
        entry.requester = player.getUUID();
        entry.requesterName = player.getGameProfile().getName();
        entry.wants.addAll(wants);
        entry.setRewardSpec(rewardSpec);
        entry.physicalEscrow = 0L;
        entry.rewardItem = rewardItem;
        entry.createdGame = player.server.overworld().getGameTime();
        entry.createdReal = System.currentTimeMillis();
        data.demands.add(entry);
        data.setDirty();
        Component rewardText = demandRewardText(entry, player.server);
        notifyPlayer(player, Component.translatable("msg.module_shop.demand_created",
                entry.summary(), rewardText)
                .withStyle(ChatFormatting.GREEN));
        // 全服公告：让所有人都知道有人挂悬赏（发布者自己也会看到一条）。
        broadcastChat(player.server, Component.translatable("msg.module_shop.demand_broadcast",
                player.getGameProfile().getName(), entry.summary(), rewardText)
                .withStyle(ChatFormatting.GOLD));
        broadcastSnapshots(player.server);
    }

    /** 「悬赏内容」的可读文本（星币或实体货币 + 物品，供聊天与界面复用）。 */
    private static Component demandRewardText(DemandEntry entry, @Nullable MinecraftServer server) {
        PriceSpec rSpec = entry.resolveRewardSpec();
        Component money = !rSpec.isZero()
                ? Component.literal(rSpec.mode == CurrencyMode.STAR_ONLY
                        ? Long.toString(rSpec.starPrice)
                        : CurrencySettlement.formatSpec(rSpec, server))
                : null;
        Component item = entry.rewardItem != null && !entry.rewardItem.isEmpty()
                ? Component.translatable("gui.module_shop.demand.reward_item",
                        entry.rewardItem.getHoverName(), entry.rewardItem.getCount())
                : null;
        if (money != null && item != null) {
            return Component.translatable("gui.module_shop.demand.reward_both", money, item);
        }
        return money != null ? money : (item != null ? item : Component.empty());
    }

    /** 撤销自己的求购（管理员可撤销任意一条）：托管原样退回。 */
    public static void demandCancel(ServerPlayer player, CompoundTag tag) {
        ShopSavedData data = data(player.server);
        DemandEntry entry = demandOf(data, tag.getString("id"));
        if (entry == null) {
            notifyPlayer(player, Component.translatable("msg.module_shop.demand_gone")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        boolean admin = canEditAdmin(player);
        if (!admin && !player.getUUID().equals(entry.requester)) {
            if (player != null && player.hasPermissions(2)) {
                notifyPlayer(player, Component.translatable("msg.module_shop.auth_required")
                        .withStyle(ChatFormatting.RED));
                promptAdminAuth(player);
                return;
            }
            notifyPlayer(player, Component.translatable("msg.module_shop.demand_not_yours")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        data.demands.remove(entry);
        refundDemand(player.server, entry, admin && !player.getUUID().equals(entry.requester)
                ? "管理员" : "系统");
        data.setDirty();
        if (admin && !player.getUUID().equals(entry.requester)) {
            ServerPlayer onlineOwner = player.server.getPlayerList().getPlayer(entry.requester);
            if (onlineOwner != null) {
                notifyPlayer(onlineOwner, Component.translatable("msg.module_shop.demand_admin_cancelled",
                        entry.summary()).withStyle(ChatFormatting.YELLOW));
            }
            notifyPlayer(player, Component.translatable("msg.module_shop.demand_admin_cancelled_self",
                    entry.summary()).withStyle(ChatFormatting.GREEN));
        } else {
            notifyPlayer(player, Component.translatable("msg.module_shop.demand_cancelled")
                    .withStyle(ChatFormatting.YELLOW));
        }
        broadcastSnapshots(player.server);
    }

    /**
     * 交付（成交）：交付者背包里必须真的有足够的物品，交出去之后悬赏当场付给他。
     * 悬赏金币按余额上限入账（装不下的转金钱邮件），悬赏物品进背包 / 邮箱，绝不私吞。
     */
    private static void demandFulfill(ServerPlayer player, CompoundTag tag) {
        ShopSavedData data = data(player.server);
        DemandEntry entry = demandOf(data, tag.getString("id"));
        if (entry == null) {
            notifyPlayer(player, Component.translatable("msg.module_shop.demand_gone")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (player.getUUID().equals(entry.requester)) {
            notifyPlayer(player, Component.translatable("msg.module_shop.demand_self")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        // 逐条清点：清单里每一种都必须够数，才允许交付（避免只交一半）。
        for (DemandEntry.WantLine line : entry.wants) {
            ItemStack template = line.stack.copy();
            template.setCount(1);
            int have = countMatching(player, template);
            if (have < line.count) {
                notifyPlayer(player, Component.translatable("msg.module_shop.demand_not_enough_line",
                        line.stack.getHoverName(), line.count, have).withStyle(ChatFormatting.RED));
                return;
            }
        }
        // 扣物品：先全部取出来记在 taken 里；只要有一条失败，**已取出的全部退回**（绝不半途吞物品）。
        List<ItemStack> takenStacks = new ArrayList<>();
        boolean takeFailed = false;
        for (DemandEntry.WantLine line : entry.wants) {
            ItemStack template = line.stack.copy();
            template.setCount(1);
            int taken = takeMatchingStack(player, template, line.count);
            if (taken <= 0) {
                takeFailed = true;
                notifyPlayer(player, Component.translatable("msg.module_shop.demand_not_enough_line",
                        line.stack.getHoverName(), line.count, 0).withStyle(ChatFormatting.RED));
                break;
            }
            ItemStack got = template.copy();
            got.setCount(taken);
            takenStacks.add(got);
            if (taken < line.count) {
                takeFailed = true;
                notifyPlayer(player, Component.translatable("msg.module_shop.demand_not_enough_line",
                        line.stack.getHoverName(), line.count, taken).withStyle(ChatFormatting.RED));
                break;
            }
        }
        if (takeFailed) {
            for (ItemStack back : takenStacks) {
                addItemOrMail(player, back, SOURCE_DEMAND_REFUND);
            }
            return;
        }
        data.demands.remove(entry);
        // ★ 把 B 交出来的物品**投递给 A**：在线直接进背包（背包满转邮箱），离线装进同一封邮件。
        // 之前这里只发了"求购已完成"的通知邮件、物品没投递，于是玩家看到"钱扣了、东西没到、邮箱里也是空的"。
        ServerPlayer demandOwner = player.server.getPlayerList().getPlayer(entry.requester);
        if (demandOwner != null) {
            for (ItemStack got : takenStacks) {
                addItemOrMail(demandOwner, got, SOURCE_DEMAND_DELIVER);
            }
        } else if (entry.requester != null && !takenStacks.isEmpty()) {
            MailTemplates.DraftMail deliverDraft = MailTemplates.build(
                    MailTemplates.Template.DEMAND_DONE, MailTemplates.map(
                            "player", nameOf(player.server, entry.requester),
                            "item", entry.summary().getString(),
                            "count", Integer.toString(entry.totalCount()),
                            "target", player.getGameProfile().getName()))
                    .source(SOURCE_DEMAND_DELIVER);
            for (ItemStack got : takenStacks) {
                deliverDraft.addItem(got);
            }
            addMailDraft(player.server, entry.requester, deliverDraft);
        }
        // 付悬赏（严格走创建时选定的账本：星币或某阶实体货币；兼容历史混合托管存档）
        PlayerAccount account = account(player);
        payoutDemandCurrency(player.server, player.getUUID(), account, entry,
                SOURCE_DEMAND_REWARD, entry.requesterName,
                "求购:" + (entry.requesterName == null ? "?" : entry.requesterName),
                "交付 " + entry.summary().getString());
        if (entry.rewardItem != null && !entry.rewardItem.isEmpty()) {
            addItemOrMail(player, entry.rewardItem.copy(), SOURCE_DEMAND_REWARD);
        }
        // 通知双方
        notifyPlayer(player, Component.translatable("msg.module_shop.demand_fulfilled",
                entry.summary(), entry.requesterName).withStyle(ChatFormatting.GREEN));
        ServerPlayer owner = player.server.getPlayerList().getPlayer(entry.requester);
        if (owner != null) {
            notifyPlayer(owner, Component.translatable("msg.module_shop.demand_done_owner",
                    entry.summary(), player.getGameProfile().getName()).withStyle(ChatFormatting.GOLD));
        }
        if (entry.requester != null) {
            // 发布者离线也要点亮邮箱红点（物品会用投递邮件送过去，见上面）
            NotificationManager.notify(player.server, entry.requester, NotificationType.MAIL);
            if (owner != null) {
                // 在线：物品已经直接进背包了，这里再发一封纯通知邮件（不重复附物品）
                addMailDraft(player.server, entry.requester, MailTemplates.build(
                        MailTemplates.Template.DEMAND_DONE, MailTemplates.map(
                                "player", player.getGameProfile().getName(),
                                "item", entry.summary().getString(),
                                "count", Integer.toString(entry.totalCount()),
                                "target", player.getGameProfile().getName())));
            }
        }
        data.setDirty();
        broadcastSnapshots(player.server);
    }

    /** 管理员：求购基本设置（开关 / 每人条数 / 手续费 / 单笔上限）。 */
    private static void adminDemandSet(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        boolean enabled = "1".equals(tag.getString("enabled").trim())
                || "true".equalsIgnoreCase(tag.getString("enabled").trim());
        int slots;
        int fee;
        long maxReward;
        try {
            slots = Integer.parseInt(tag.getString("slots").trim());
            fee = Integer.parseInt(tag.getString("fee").trim());
            maxReward = Long.parseLong(tag.getString("max_reward").trim());
        } catch (NumberFormatException ex) {
            notifyPlayer(player, Component.translatable("msg.module_shop.demand_bad_settings")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (slots < 1 || slots > 45 || fee < 0 || fee > 100 || maxReward < 0L) {
            notifyPlayer(player, Component.translatable("msg.module_shop.demand_bad_settings")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        Config.INSTANCE.demandEnabled.set(enabled);
        Config.INSTANCE.demandSlots.set(slots);
        Config.INSTANCE.demandFeePercent.set((double) fee);
        Config.INSTANCE.demandMaxReward.set((int) Math.min(Integer.MAX_VALUE, maxReward));
        Config.SPEC.save();
        notifyPlayer(player, Component.translatable("msg.module_shop.demand_settings_saved",
                Component.translatable(enabled ? "gui.module_shop.choice.enabled.1"
                        : "gui.module_shop.choice.enabled.0"),
                slots, fee, maxReward <= 0L ? Component.translatable("gui.module_shop.demand.no_limit")
                        : Component.literal(Long.toString(maxReward)))
                .withStyle(ChatFormatting.GREEN));
        broadcastSnapshots(player.server);
    }

    /** 管理员：求购自动下架时间（永久 / 游戏天数 / 现实小时）。 */
    private static void adminDemandExpire(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        String prevMode = Config.demandExpireMode();
        int prevAmount = Config.demandExpireAmount();
        String mode = ShopExpireSupport.normalizeExpireMode(tag.getString("expire_mode"));
        Integer parsed = ShopExpireSupport.parseExpireAmount(mode, tag.getString("expire_amount"), prevAmount);
        if (parsed == null) {
            notifyPlayer(player, Component.translatable("msg.module_shop.stall_expire_bad_amount")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        int amount = parsed;
        if (!saveExpireConfig("demand", Config.INSTANCE.demandExpireMode, Config.INSTANCE.demandExpireAmount,
                "demandExpireMode", mode, "demandExpireAmount", amount)) {
            notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.config_save_failed",
                    "配置写入磁盘失败，已恢复原设置，请检查配置文件权限").withStyle(ChatFormatting.RED));
            return;
        }
        if (ShopExpireSupport.shouldResetExpireTimestamps(prevMode, prevAmount, mode, amount)) {
            synchronized (LOCK) {
                ShopSavedData data = data(player.server);
                long nowGame = player.server.overworld().getGameTime();
                long nowReal = System.currentTimeMillis();
                if (!data.demands.isEmpty()) {
                    for (DemandEntry entry : data.demands) {
                        entry.createdGame = nowGame;
                        entry.createdReal = nowReal;
                    }
                    data.setDirty();
                }
            }
        }
        Component desc = !ShopExpireSupport.isExpireActive(mode, amount)
                ? Component.translatable("gui.module_shop.choice.expire_mode.permanent")
                : Component.translatable("gui.module_shop.stall.expire_value",
                        Component.translatable("gui.module_shop.choice.expire_mode." + mode), amount);
        notifyPlayer(player, Component.translatable("msg.module_shop.demand_expire_saved", desc)
                .withStyle(ChatFormatting.GREEN));
        broadcastSnapshots(player.server);
    }

    /**
     * 求购**自动下架**：到时间就把这条求购撤下，并把托管的悬赏原样退回发布者（余额 / 背包 / 邮箱）。
     * 老存档没有发布时间的条目会在第一次 tick 补打时间戳，不会被"立刻撤下"。
     */
    public static void tickDemandExpire(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ShopSavedData data = data(server);
        boolean dirty = false;
        if (Config.demandExpireActive()) {
            boolean byGame = "game".equals(Config.demandExpireMode());
            long limit = byGame ? Config.demandExpireGameTicks() : Config.demandExpireRealMillis();
            long nowGame = server.overworld().getGameTime();
            long nowReal = System.currentTimeMillis();
            for (int i = data.demands.size() - 1; i >= 0; i--) {
                DemandEntry entry = data.demands.get(i);
                if (entry.createdGame <= 0L) {
                    entry.createdGame = nowGame;
                    dirty = true;
                }
                if (entry.createdReal <= 0L) {
                    entry.createdReal = nowReal;
                    dirty = true;
                }
                long age = byGame ? nowGame - entry.createdGame : nowReal - entry.createdReal;
                if (age < limit) {
                    continue;
                }
                data.demands.remove(i);
                dirty = true;
                refundDemand(server, entry, "系统");
                ServerPlayer owner = entry.requester == null ? null
                        : server.getPlayerList().getPlayer(entry.requester);
                if (owner != null) {
                    notifyPlayer(owner, Component.translatable("msg.module_shop.demand_expired",
                            entry.summary()).withStyle(ChatFormatting.YELLOW));
                    sendSnapshot(owner, "demand");
                }
            }
        }
        if (dirty) {
            data.setDirty();
            broadcastSnapshots(server);
        }
    }

    /**
     * 摊位**自动下架**：按「商店设置 → 摊位 → 下架时间」把到期的商品撤下来。
     *
     * <p>规则：
     * <ul>
     *     <li>{@code permanent}（或时长为 0）→ 永不下架，直接返回；</li>
     *     <li>{@code game} → 上架后超过「游戏天数 × 24000 刻」就下架；</li>
     *     <li>{@code real} → 上架后超过「现实小时 × 3600000 毫秒」就下架；</li>
     * </ul>
     * 下架时**剩余库存全部退回**：在线直接进背包，背包满了 / 离线则进邮箱（绝不销毁），
     * 并给摊主一条说明。老存档里没有上架时刻的商品会在第一次 tick 时补打时间戳（给一个完整的限时窗口）。
     */
    public static void tickStallExpire(MinecraftServer server) {
        if (server == null || !Config.stallExpireActive()) {
            return;
        }
        boolean byGame = "game".equals(Config.stallExpireMode());
        long limit = byGame ? Config.stallExpireGameTicks() : Config.stallExpireRealMillis();
        if (limit <= 0L) {
            return;
        }
        long nowGame = server.overworld().getGameTime();
        long nowReal = System.currentTimeMillis();
        List<ServerPlayer> affectedOnline = new ArrayList<>();
        boolean touched = false;
        synchronized (LOCK) {
            ShopSavedData data = data(server);
            boolean dirty = false;
            for (Map.Entry<UUID, PlayerAccount> pair : data.accounts().entrySet()) {
                UUID owner = pair.getKey();
                PlayerAccount account = pair.getValue();
                if (account.stall.isEmpty()) {
                    continue;
                }
                ServerPlayer online = server.getPlayerList().getPlayer(owner);
                boolean removed = false;
                for (int i = account.stall.size() - 1; i >= 0; i--) {
                    StallEntry entry = account.stall.get(i);
                    // 老存档 / 老数据：先补打上架时刻，不立刻下架。
                    if (entry.listedGame <= 0L) {
                        entry.listedGame = nowGame;
                        dirty = true;
                    }
                    if (entry.listedReal <= 0L) {
                        entry.listedReal = nowReal;
                        dirty = true;
                    }
                    long age = byGame ? nowGame - entry.listedGame : nowReal - entry.listedReal;
                    if (age < limit) {
                        continue;
                    }
                    account.stall.remove(i);
                    cn.autoforged.module_shop.shop.stall.StallService.evictItemCache(entry.order);
                    dirty = true;
                    touched = true;
                    removed = true;
                    int refundCount = Math.max(0, entry.stock);
                    ItemStack name = entry.stack.copy();
                    if (refundCount > 0) {
                        // 按单叠上限拆分，防止高库存（>=128 引发 1.20.1 byte 溢出吞物或受损装备非法堆叠）。
                        if (online != null) {
                            addItemsOrMail(online, entry.stack, refundCount, SOURCE_STALL_EXPIRED);
                        } else {
                            List<ItemStack> refundStacks = splitStacks(entry.stack, refundCount);
                            addMailDraft(server, owner, MailTemplates.build(MailTemplates.Template.STALL_EXPIRED,
                                            MailTemplates.map("player", nameOf(server, owner),
                                                    "item", MailTemplates.itemText(name, false) + "×" + refundCount))
                                    .source(SOURCE_STALL_EXPIRED)
                                    .addItems(refundStacks));
                        }
                    }
                    if (online != null) {
                        if (refundCount > 0) {
                            notifyPlayer(online, Component.translatable("msg.module_shop.stall_expired",
                                    name.getHoverName(), refundCount).withStyle(ChatFormatting.YELLOW));
                        } else {
                            notifyPlayer(online, Component.translatableWithFallback("msg.module_shop.stall_expired_empty",
                                    "已售空的摊位商品「%s」已到期自动下架", name.getHoverName()).withStyle(ChatFormatting.YELLOW));
                        }
                    }
                }
                if (removed && online != null) {
                    affectedOnline.add(online);
                }
            }
            if (dirty) {
                data.setDirty();
            }
        }
        // 一个玩家可能有多个商品同时到期：在锁外只推一次快照，免得刷屏。
        for (ServerPlayer online : affectedOnline) {
            sendSnapshot(online, "stall");
        }
        if (touched) {
            broadcastSnapshots(server);
        }
    }

    /**
     * 自己摊位上的商品：优先按商品 id（order，全服唯一、不随下架变化）定位；
     * 旧客户端 / 旧输入框只带 index 时退回按下标。
     */
    private static int stallIndex(PlayerAccount account, CompoundTag tag) {
        if (tag.contains("id")) {
            return cn.autoforged.module_shop.shop.stall.StallService.indexOf(account, tag.getLong("id"));
        }
        int index = tag.getInt("index");
        return index >= 0 && index < account.stall.size() ? index : -1;
    }

    private static void stallApplyPrice(ServerPlayer player, CompoundTag tag) {
        PriceSpec spec = cn.autoforged.module_shop.shop.stall.StallService.parseStallSpecFromTag(tag);
        if (spec != null && spec.mode == CurrencyMode.PHYS_ONLY) {
            PhysicalCurrencyConfig physCfg = PhysicalCurrencyConfig.get();
            var tier = physCfg.findTier(spec.physTierKey);
            if (!physCfg.isActive() || tier == null) {
                notifyPlayer(player, Component.translatableWithFallback(
                        "msg.module_shop.phys_tier_unavailable", "所需的实体货币档位未启用或不存在")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            spec = spec.withPhysTierKey(tier.tierKey());
        } else if (spec != null && spec.mode == CurrencyMode.STAR_ONLY) {
            long cap = Config.maxBalanceLimit();
            if (cap != Long.MAX_VALUE && spec.starPrice > cap) {
                notifyPlayer(player, Component.translatable("msg.module_shop.price_over_cap",
                        Long.toString(cap)).withStyle(ChatFormatting.RED));
                return;
            }
        }
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            int index = stallIndex(account, tag);
            if (index < 0 || spec == null || !spec.isValid()
                    || (spec.mode != CurrencyMode.STAR_ONLY && spec.mode != CurrencyMode.PHYS_ONLY)) {
                notifyPlayer(player, Component.translatable(
                        spec == null || !spec.isValid() ? "msg.module_shop.invalid_price"
                                : "msg.module_shop.stall.err_gone").withStyle(ChatFormatting.RED));
                return;
            }
            StallEntry entry = account.stall.get(index);
            entry.setPriceSpec(spec);
            data(player.server).setDirty();
            if (spec.mode == CurrencyMode.PHYS_ONLY) {
                notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.stall.price_changed_spec",
                        "已修改 %s 单价为 %s", entry.stack.getHoverName(),
                        CurrencySettlement.formatSpec(spec, player.server)).withStyle(ChatFormatting.GREEN));
            } else {
                notifyPlayer(player, Component.translatable("msg.module_shop.stall.price_changed",
                        entry.stack.getHoverName(), spec.starPrice).withStyle(ChatFormatting.GREEN));
            }
        }
        sendSnapshot(player, "stall");
        broadcastSnapshots(player.server);
    }

    private static void removeStall(ServerPlayer player, CompoundTag tag) {
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            int index = stallIndex(account, tag);
            if (index < 0) {
                return;
            }
            StallEntry entry = account.stall.remove(index);
            cn.autoforged.module_shop.shop.stall.StallService.evictItemCache(entry.order);
            // 需求 6：下架时把剩余库存按单叠上限拆分后全部退还（背包放不下的进邮箱，不丢不复制：先移出摊位再退）。
            if (entry.stock > 0) {
                addItemsOrMail(player, entry.stack, entry.stock, SOURCE_STALL_UNSOLD);
            }
            data(player.server).setDirty();
        }
        sendSnapshot(player, "stall");
        broadcastSnapshots(player.server);
    }

    private static void openStallBrowse(ServerPlayer player, String ownerUuid) {
        UUID owner;
        try {
            owner = UUID.fromString(ownerUuid);
        } catch (IllegalArgumentException ex) {
            return;
        }
        ShopSavedData data = data(player.server);
        PlayerAccount account = data.peek(owner);
        if (account == null) {
            return;
        }
        HolderLookup.Provider regs = player.level().registryAccess();
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "stall_browse");
        tag.putString("owner", owner.toString());
        tag.putString("ownerName", account.name.isEmpty() ? "Unknown" : account.name);
        tag.putBoolean("own", owner.equals(player.getUUID()));
        tag.putLong("balance", account(player).balance);
        ListTag list = new ListTag();
        for (int i = 0; i < account.stall.size(); i++) {
            StallEntry entry = account.stall.get(i);
            CompoundTag c = ShopNbt.saveStack(entry.stack, regs);
            entry.resolvePriceSpec().writeToParent(c, "priceSpec", "price");
            c.putInt("stock", entry.stock);
            // 需求 9：浏览摊位时也显示该商品已售出件数。
            c.putInt("sold", entry.sold);
            c.putInt("index", i);
            // 商品 id：购买时按它定位，卖家下架 / 调整其它商品也不会买错
            c.putLong("id", entry.order);
            list.add(c);
        }
        tag.put("entries", list);
        ClientboundShopSyncPayload.send(player, tag);
    }

    private static void stallBuy(ServerPlayer player, CompoundTag tag) {
        UUID owner;
        try {
            owner = UUID.fromString(tag.getString("owner"));
        } catch (IllegalArgumentException ex) {
            return;
        }
        // 需求 5：购买数量由玩家选择；服务端校验必须在 1..库存 之间（超过库存直接拒绝，不偷偷少卖）。
        int requested = parseInt(tag.getString("quantity"));
        int want = requested <= 0 ? 1 : requested;
        if (owner.equals(player.getUUID())) {
            return;
        }
        synchronized (LOCK) {
            // 重复点击 / 网络重发：同一请求号只处理一次
            if (RequestGuard.duplicate(player, tag)) {
                return;
            }
            ShopSavedData data = data(player.server);
            PlayerAccount sellerAccount = data.peek(owner);
            // 从服务端真实摊位数据取物品与价格，不信任客户端；优先按商品 id 定位（下标会因卖家下架其它商品而错位）。
            int index = -1;
            if (sellerAccount != null) {
                if (tag.contains("id")) {
                    index = cn.autoforged.module_shop.shop.stall.StallService.indexOf(sellerAccount, tag.getLong("id"));
                } else {
                    int raw = tag.getInt("index");
                    index = raw >= 0 && raw < sellerAccount.stall.size() ? raw : -1;
                }
            }
            if (index < 0) {
                notifyPlayer(player, Component.translatable("msg.module_shop.stall.err_gone").withStyle(ChatFormatting.RED));
                openStallBrowse(player, owner.toString());
                return;
            }
            StallEntry entry = sellerAccount.stall.get(index);
            PriceSpec unitSpec = entry.resolvePriceSpec();
            // 卖家刚改过价：优先校验结构化 priceSpec（兼容 expectSpec / expectPriceSpec），其次校验 expect。
            if (tag.contains("expectSpec", Tag.TAG_COMPOUND) || tag.contains("expectPriceSpec", Tag.TAG_COMPOUND)) {
                CompoundTag expTag = tag.contains("expectSpec", Tag.TAG_COMPOUND)
                        ? tag.getCompound("expectSpec") : tag.getCompound("expectPriceSpec");
                PriceSpec expectedSpec = PriceSpec.fromTag(expTag, 0L);
                if (!unitSpec.equals(expectedSpec)) {
                    notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.stall.err_price_changed_spec",
                            "卖家刚刚修改了价格（现价 %s），请确认后重试",
                            CurrencySettlement.formatSpec(unitSpec, player.server)).withStyle(ChatFormatting.RED));
                    openStallBrowse(player, owner.toString());
                    return;
                }
            } else if (tag.contains("expect")) {
                long currentCmp = unitSpec.mode == CurrencyMode.PHYS_ONLY ? unitSpec.physCount : unitSpec.starPrice;
                if (tag.getLong("expect") != currentCmp) {
                    notifyPlayer(player, Component.translatable("msg.module_shop.stall.err_price_changed",
                            currentCmp).withStyle(ChatFormatting.RED));
                    openStallBrowse(player, owner.toString());
                    return;
                }
            }
            if (entry.stock <= 0) {
                notifyPlayer(player, Component.translatable("msg.module_shop.stall_sold_out").withStyle(ChatFormatting.RED));
                return;
            }
            if (want > entry.stock) {
                notifyPlayer(player, Component.translatable("msg.module_shop.stall.err_stock", entry.stock)
                        .withStyle(ChatFormatting.RED));
                openStallBrowse(player, owner.toString());
                return;
            }
            int buyCount = want;
            PriceSpec totalSpec = unitSpec.scale(buyCount);
            if (!totalSpec.isValid()
                    || (totalSpec.mode != CurrencyMode.STAR_ONLY && totalSpec.mode != CurrencyMode.PHYS_ONLY)) {
                notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price").withStyle(ChatFormatting.RED));
                return;
            }
            PlayerAccount buyer = account(player);
            var buyPay = CurrencySettlement.paySpec(
                    player, buyer, data, unitSpec, buyCount, null, "stall_buy", SOURCE_STALL_BOUGHT, false);
            if (!buyPay.ok()) {
                CurrencySettlement.notifyPayFailure(player, buyPay);
                return;
            }
            entry.stock -= buyCount;
            // 需求 9：记录该商品累计已售出件数（各商品分别统计）。
            entry.sold += buyCount;
            PlayerAccount stallSellerAcct = data.account(owner);
            ServerPlayer onlineSeller = player.server.getPlayerList().getPlayer(owner);
            Component rewardName = entry.stack.getHoverName();
            boolean mailed = deliver(player, entry.stack, buyCount, SOURCE_STALL_BOUGHT);
            String sellerName = sellerAccount.name == null || sellerAccount.name.isEmpty() ? "摊位" : sellerAccount.name;

            if (totalSpec.mode == CurrencyMode.PHYS_ONLY) {
                long gross = totalSpec.physCount;
                CurrencySettlement.payoutPhysTier(
                        player.server, owner, totalSpec.physTierKey, gross, SOURCE_STALL_SOLD);
                PriceSpec paidSpec = PriceSpec.phys(totalSpec.physTierKey, gross);
                String paidText = CurrencySettlement.formatSpec(paidSpec, player.server);
                if (onlineSeller != null) {
                    notifyPlayer(onlineSeller, Component.translatableWithFallback("msg.module_shop.stall_sold_spec",
                            "售出 %s×%s，实得 %s", rewardName, buyCount, paidText).withStyle(ChatFormatting.GREEN));
                }
                if (entry.stock <= 0) {
                    addMailDraft(player.server, owner, MailTemplates.build(MailTemplates.Template.STALL_DEPLETED,
                                    MailTemplates.map("player", nameOf(player.server, owner),
                                            "item", MailTemplates.itemText(entry.stack, false),
                                            "amount", Integer.toString(entry.sold),
                                            "price", paidText))
                            .source(SOURCE_STALL_SOLD));
                }
                data.setDirty();
                notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.bought_count_spec",
                        "花费 %s 购买 %s×%s", paidText, rewardName, buyCount).withStyle(ChatFormatting.GREEN));
                if (mailed) {
                    notifyPlayer(player, Component.translatable("msg.module_shop.inventory_full_mail").withStyle(ChatFormatting.RED));
                }
                NotificationManager.notify(player.server, owner, NotificationType.STALL);
                TransactionLog.record(player.server, player.getUUID(), TransactionLog.STALL_BUY, sellerName,
                        0L, buyer.balance, rewardName.getString() + "×" + buyCount + " [" + paidText + "]");
                TransactionLog.record(player.server, owner, TransactionLog.STALL_SELL,
                        player.getGameProfile().getName(), 0L, sellerAccount.balance,
                        rewardName.getString() + "×" + buyCount + " [" + paidText + "]");
            } else {
                long total = totalSpec.starPrice;
                CurrencySettlement.payoutStarOnly(
                        player.server, owner, stallSellerAcct, null, total, false, true,
                        SOURCE_STALL_SOLD, player.getGameProfile().getName(), entry.stack);
                if (onlineSeller != null) {
                    notifyPlayer(onlineSeller, Component.translatable("msg.module_shop.stall_sold_wallet",
                            entry.stack.getHoverName(), buyCount, total).withStyle(ChatFormatting.GREEN));
                    notifyPlayer(onlineSeller, Component.translatable("msg.module_shop.stall_sold",
                            rewardName, buyCount, total).withStyle(ChatFormatting.GREEN));
                }
                // 售空提醒：该商品库存清零时给摊主一封提醒，替代原来的「每笔金币邮件」。
                if (entry.stock <= 0) {
                    addMailDraft(player.server, owner, MailTemplates.build(MailTemplates.Template.STALL_DEPLETED,
                                    MailTemplates.map("player", nameOf(player.server, owner),
                                            "item", MailTemplates.itemText(entry.stack, false),
                                            "amount", Integer.toString(entry.sold),
                                            "price", Long.toString(total)))
                            .source(SOURCE_STALL_SOLD));
                }
                data.setDirty();
                // 需求一：摘要统一为「花费 金额 星币 购买 物品名×数量」，金额字段在前。
                notifyPlayer(player, Component.translatable("msg.module_shop.bought_count", total, rewardName, buyCount)
                        .withStyle(ChatFormatting.GREEN));
                if (mailed) {
                    notifyPlayer(player, Component.translatable("msg.module_shop.inventory_full_mail").withStyle(ChatFormatting.RED));
                }
                NotificationManager.notify(player.server, owner, NotificationType.STALL);
                TransactionLog.record(player.server, player.getUUID(), TransactionLog.STALL_BUY, sellerName,
                        -total, buyer.balance, rewardName.getString() + "×" + buyCount);
                TransactionLog.record(player.server, owner, TransactionLog.STALL_SELL,
                        player.getGameProfile().getName(), total, sellerAccount.balance,
                        rewardName.getString() + "×" + buyCount + "（货款进钱包）");
            }
        }
        // 静默刷新买家缓存，并把最新库存推回正在浏览的摊位界面（需求 5：买完还剩多少显示多少）。
        sendSnapshot(player, "stall", true);
        broadcastSnapshots(player.server);
        openStallBrowse(player, owner.toString());
    }

    // =========================================================== 邮箱

    /** 第七轮：切换邮件星标（置顶显示），成功后回推快照。 */
    private static void toggleMailStar(ServerPlayer player, String mailId) {
        PlayerAccount account = account(player);
        MailEntry entry = findMail(account, mailId);
        if (entry == null) {
            return;
        }
        entry.starred = !entry.starred;
        data(player.server).setDirty();
        notifyPlayer(player, Component.translatable(entry.starred
                ? "msg.module_shop.mail_starred" : "msg.module_shop.mail_unstarred")
                .withStyle(ChatFormatting.GOLD));
        sendSnapshot(player, "mail");
    }

    /** 按 mailId 在服务端真实邮箱里查表，绝不信任客户端传来的下标。 */
    private static MailEntry findMail(PlayerAccount account, String mailId) {
        if (mailId == null || mailId.isEmpty()) {
            return null;
        }
        for (MailEntry entry : account.mailbox) {
            if (mailId.equals(entry.mailId)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 物品类邮件：领取进背包。背包放不下的部分继续留在邮件里（绝不丢 NBT，也不会重复发放）。
     * 并发安全：整段在 LOCK 内执行，并且只有全部放入后才置 isClaimed=true。
     */
    private static void claimMail(ServerPlayer player, String mailId) {
        // 第七轮：领取前先算背包空间，装不下就拦截（不消费邮件）。
        if (!fitsInBagOrWarn(player, pendingItems(player, mailId))) {
            return;
        }
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            MailEntry entry = findMail(account, mailId);
            if (entry == null || entry.type != MailType.ITEM || entry.isClaimed || !entry.hasItems()) {
                return;
            }
            // 第八轮：一封邮件可能装多件物品，逐件尝试放入，装不下的部分留在邮件里。
            List<ItemStack> left = new ArrayList<>();
            StringBuilder names = new StringBuilder();
            for (ItemStack stack : entry.itemStacks()) {
                ItemStack remaining = stack.copy();
                player.getInventory().add(remaining);
                if (!remaining.isEmpty()) {
                    left.add(remaining);
                }
                if (names.length() > 0) {
                    names.append("、");
                }
                names.append(stack.getHoverName().getString());
            }
            entry.setItems(left);
            if (!left.isEmpty()) {
                // 邮件里的数量已经在内存里减少，必须标脏落盘：SavedData 只写 isDirty() 的数据，
                // 否则定时重启/崩溃后邮件恢复原数量，等于“部分领取 + 重启”复制物品。
                data(player.server).setDirty();
                // 部分物品没领完：明确告诉玩家剩余物品仍在邮箱，清理背包后可再次「全部领取」。
                notifyPlayer(player, Component.translatable("msg.module_shop.claim_all_partial")
                        .withStyle(ChatFormatting.RED));
                sendSnapshot(player, "mail");
                return;
            }
            entry.isClaimed = true;
            data(player.server).setDirty();
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.MAIL_ITEM, "邮箱",
                    0L, account.balance, names.toString());
            notifyPlayer(player, Component.translatable("msg.module_shop.mail_claimed", names.toString())
                    .withStyle(ChatFormatting.GREEN));
            sendSnapshot(player, "mail");
        }
    }

    /** 金额类邮件：把星币加进钱包并标记已查看。 */
    private static void viewMail(ServerPlayer player, String mailId) {
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            MailEntry entry = findMail(account, mailId);
            if (entry == null || entry.type != MailType.MONEY || entry.isViewed) {
                return;
            }
            long amount = entry.currencyAmount;
            long credited = CurrencySettlement.payoutStarOnly(
                    player.server, player.getUUID(), account, null, amount, false, false,
                    SOURCE_ADMIN, null, null);
            if (credited < amount) {
                // 余额装不下：只领能装下的部分，剩下的留在邮件里（绝不静默销毁）。
                entry.currencyAmount = amount - credited;
                data(player.server).setDirty();
                notifyPlayer(player, Component.translatable("msg.module_shop.mail_partial_money",
                        credited, entry.currencyAmount).withStyle(ChatFormatting.GOLD));
                sendSnapshot(player, "mail");
                return;
            }
            entry.isViewed = true;
            data(player.server).setDirty();
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.MAIL_MONEY, "邮箱",
                    credited, account.balance, "");
            notifyPlayer(player, Component.translatable("msg.module_shop.mail_viewed", amount)
                    .withStyle(ChatFormatting.GREEN));
            sendSnapshot(player, "mail");
        }
    }

    /** 一键领取/查看所有未处理邮件；背包放不下的物品留在邮箱并提示。 */
    private static void claimAllMail(ServerPlayer player) {
        // 第七轮优化：不再整批拦截 —— 金额先到账（金额不占背包），物品逐封尝试放入，
        // 放不下的那部分**留在邮箱**并在最后统一提示，避免"因为一件物品放不下，连钱都领不到"。
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            if (account.mailbox.isEmpty()) {
                return;
            }
            boolean changed = false;
            boolean full = false;
            for (MailEntry entry : account.mailbox) {
                if (entry.type == MailType.MONEY) {
                    if (!entry.isViewed) {
                        long amount = entry.currencyAmount;
                        long credited = CurrencySettlement.payoutStarOnly(
                                player.server, player.getUUID(), account, null, amount, false, false,
                                SOURCE_ADMIN, null, null);
                        if (credited < amount) {
                            // 装不下的部分留在邮件里，等余额有空间再领。
                            entry.currencyAmount = amount - credited;
                        } else {
                            entry.isViewed = true;
                        }
                        if (credited > 0L) {
                            TransactionLog.record(player.server, player.getUUID(), TransactionLog.MAIL_MONEY,
                                    "邮箱", credited, account.balance, "");
                        }
                        changed = true;
                    }
                    continue;
                }
                if (entry.isClaimed || !entry.hasItems()) {
                    continue;
                }
                // 第八轮：一封邮件多件物品，逐件放入；放不下的留在邮件里。
                List<ItemStack> left = new ArrayList<>();
                for (ItemStack stack : entry.itemStacks()) {
                    ItemStack remaining = stack.copy();
                    player.getInventory().add(remaining);
                    if (!remaining.isEmpty()) {
                        left.add(remaining);
                    }
                }
                entry.setItems(left);
                if (!left.isEmpty()) {
                    // 背包满：该物品留在邮箱，但继续处理后面的金额邮件（金额不占背包）。
                    full = true;
                    // 数量已在内存里减少，同样要标脏落盘（理由见 claimMail）。
                    changed = true;
                    continue;
                }
                entry.isClaimed = true;
                changed = true;
            }
            if (changed) {
                data(player.server).setDirty();
            }
            if (full) {
                // 部分物品没领完：明确告诉玩家剩余物品仍在邮箱，清理背包后可再次「全部领取」。
                notifyPlayer(player, Component.translatable("msg.module_shop.claim_all_partial")
                        .withStyle(ChatFormatting.RED));
            }
            sendSnapshot(player, "mail");
        }
    }

    /**
     * 删除一封邮件。未领取/未查看的邮件必须带 confirmed=true（客户端弹二次确认），
     * 服务端同样强制校验，防止误删重要资产。
     */
    private static void deleteMail(ServerPlayer player, String mailId, boolean confirmed) {
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            MailEntry entry = findMail(account, mailId);
            if (entry == null) {
                return;
            }
            if (entry.pending() && !confirmed) {
                notifyPlayer(player, Component.translatable("msg.module_shop.mail_delete_need_confirm")
                        .withStyle(ChatFormatting.YELLOW));
                return;
            }
            account.mailbox.remove(entry);
            data(player.server).setDirty();
            sendSnapshot(player, "mail");
        }
    }

    /** 需求四：一键清理已经领取（物品）/已查看（金额）的邮件 —— **标星的邮件永远保留**。 */
    private static void deleteReadMail(ServerPlayer player) {
        synchronized (LOCK) {
            PlayerAccount account = account(player);
            boolean removed = account.mailbox.removeIf(entry -> !entry.pending() && !entry.starred);
            if (!removed) {
                return;
            }
            data(player.server).setDirty();
            notifyPlayer(player, Component.translatable("msg.module_shop.mail_deleted_read")
                    .withStyle(ChatFormatting.GREEN));
            sendSnapshot(player, "mail");
        }
    }

    // =========================================================== 钱包 / 转账

    private static void doTransfer(ServerPlayer player, CompoundTag tag) {
        if (!Config.INSTANCE.allowPlayerTransfer.get()) {
            notifyPlayer(player, Component.translatable("msg.module_shop.transfer_disabled").withStyle(ChatFormatting.RED));
            return;
        }
        String targetName = tag.getString("target");
        long amount = parsePositive(tag.getString("amount"));
        if (targetName.isEmpty() || amount <= 0) {
            return;
        }
        ServerPlayer target = player.server.getPlayerList().getPlayerByName(targetName);
        if (target == null) {
            notifyPlayer(player, Component.translatable("msg.module_shop.target_offline").withStyle(ChatFormatting.RED));
            // 需求一备用模板：转账失败日志「向 目标玩家名 转账 金额 星币失败」。
            notifyPlayer(player, Component.translatable("msg.module_shop.transfer_failed", targetName, amount)
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (target == player) {
            return;
        }
        // 需求 7：同一 IP 的玩家之间不可转账，管理员不受限制。
        if (sameIp(player, target) && !player.hasPermissions(2) && !target.hasPermissions(2)) {
            notifyPlayer(player, Component.translatable("msg.module_shop.transfer_same_ip").withStyle(ChatFormatting.RED));
            return;
        }
        ShopSavedData data = data(player.server);
        // 需求 4/8：余额校验与扣款放在同一把锁内，防止并发转账把钱扣成负数。
        synchronized (LOCK) {
            PlayerAccount from = account(player);
            long now = player.level().getGameTime();
            if (now - from.lastTransferTick < Config.INSTANCE.transferCooldownTicks.get()) {
                notifyPlayer(player, Component.translatable("msg.module_shop.transfer_cooldown").withStyle(ChatFormatting.RED));
                return;
            }
            if (from.balance < amount) {
                CurrencySettlement.notifyPayFailure(player,
                        CurrencySettlement.PayResult.fail("msg.module_shop.no_money", "星币余额不足"));
                return;
            }
            PlayerAccount to = account(target);
            long room = Math.max(0L, Config.maxBalanceLimit() - to.balance);
            if (room < amount) {
                notifyPlayer(player, Component.translatable("msg.module_shop.target_balance_full",
                        target.getGameProfile().getName()).withStyle(ChatFormatting.RED));
                return;
            }
            var payRes = CurrencySettlement.payStar(from, amount, "transfer");
            if (!payRes.ok()) {
                CurrencySettlement.notifyPayFailure(player, payRes);
                return;
            }
            CurrencySettlement.payoutStarOnly(
                    player.server, target.getUUID(), to, null, amount, false, false,
                    SOURCE_EXCHANGE, player.getGameProfile().getName(), ItemStack.EMPTY);
            from.lastTransferTick = now;
            data.setDirty();
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.TRANSFER_OUT,
                    target.getGameProfile().getName(), -amount, from.balance, "");
            TransactionLog.record(player.server, target.getUUID(), TransactionLog.TRANSFER_IN,
                    player.getGameProfile().getName(), amount, to.balance, "");
        }
        // 需求一：转账模板固定为「已向 目标玩家名 转账 金额 星币」/「收到 目标玩家名 的转账 金额 星币」，
        // 目标名在前、金额在后，靠字段位置区分，不再靠“文本是不是数字”判断。
        notifyPlayer(player, Component.translatable("msg.module_shop.transfer_sent",
                target.getGameProfile().getName(), amount).withStyle(ChatFormatting.GREEN));
        notifyPlayer(target, Component.translatable("msg.module_shop.transfer_received",
                player.getGameProfile().getName(), amount).withStyle(ChatFormatting.GREEN));
        // 需求 4：收到转账后不再在转账按钮上显示提示（移除钱包红点），只保留聊天栏通知。
        sendSnapshot(player, "wallet");
        // 收款方的界面余额也要刷新，否则他看到的还是转账前的数字（大额转入后立刻消费会误判）。
        sendSnapshot(target, "wallet", true);
    }

    /** 需求 7：比较两名玩家的连接 IP。 */
    private static boolean sameIp(ServerPlayer a, ServerPlayer b) {
        String ipA = a.getIpAddress();
        String ipB = b.getIpAddress();
        return ipA != null && !ipA.isEmpty() && ipA.equals(ipB);
    }

    // =========================================================== 管理员配置

    /** 设置页“交易记录”按钮：把某玩家的流水发给管理员本人。 */
    private static void sendHistory(ServerPlayer admin, String targetName, int page) {
        // 只有管理员（权限等级 2）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(admin)) {
            return;
        }
        List<Component> lines = historyLines(admin.server, targetName, page <= 0 ? 1 : page);
        if (lines.isEmpty()) {
            notifyPlayer(admin, Component.translatable("msg.module_shop.grant_unknown", targetName)
                    .withStyle(ChatFormatting.RED));
            return;
        }
        for (Component line : lines) {
            notifyPlayer(admin, line);
        }
    }

    private static void adminSetPriceNew(ServerPlayer player, CompoundTag tag) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        // 需求 11：模板来自幽灵槽，不是 PENDING_ITEM。
        ItemStack stack = ADMIN_TEMPLATE.remove(player.getUUID());
        if (stack == null || stack.isEmpty()) {
            return;
        }
        // 幽灵槽只保存模板副本、原物已经退回管理员背包（见 ADMIN_TEMPLATE 注释），
        // 价格非法时直接中止，绝不能再把模板当真实物品发一次（否则输入非数字价格就能凭空复制物品）。
        PriceSpec priceSpec = parseAdminSpec(player, tag);
        if (priceSpec == null) {
            return;
        }
        AdminEditType type = editType(player, tag);
        ShopSavedData data = data(player.server);
        long rarityHint = priceSpec.supportsStar() ? priceSpec.starPrice : Math.max(1L, priceSpec.physCount);
        Rarity rarity = parseRarity(tag.getString("rarity"), rarityHint);
        // 需求 6/7：管理员可同时配置品质与数量（数量为空则退回默认上限）。
        if (type == AdminEditType.MALL) {
            // 购物城：数量字段是**库存**（留空 / -1 = 无限，0 = 售罄）。
            MallItem item = new MallItem(stack, priceSpec, rarity, parseMallStock(tag.getString("quantity")));
            item.sort = data.mallPool.size();
            item.permanentLimit = isPermanentLimitFlag(tag);
            data.mallPool.add(item);
        } else if (type == AdminEditType.SHIPPING) {
            // 出货箱：支持物品 + 单价 + 每日出货上限（0 = 无限）。
            int dailyMax = parseOptionalNonNegativeInt(player, tag, "daily_max");
            if (dailyMax < 0) {
                return;
            }
            if (!ShippingRules.addRule(stack, priceSpec, dailyMax)) {
                notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            String display = priceSpec.mode == CurrencyMode.STAR_ONLY
                    ? priceSpec.starPrice + currencyName(player.server)
                    : CurrencySettlement.formatSpec(priceSpec, player.server);
            notifyPlayer(player, Component.translatable("msg.module_shop.shipping_rule_added",
                    stack.getHoverName(), display).withStyle(ChatFormatting.GREEN));
        } else if (type == AdminEditType.DAILY_SHOP) {
            ConfiguredOffer offer = new ConfiguredOffer(stack, priceSpec, rarity);
            offer.amount = parseInt(tag.getString("quantity"));
            data.shopPool.add(offer);
        } else {
            ConfiguredOffer offer = new ConfiguredOffer(stack, priceSpec, rarity);
            offer.amount = parseInt(tag.getString("quantity"));
            data.buybackPool.add(offer);
        }
        data.setDirty();
        // 购物城加完物品直接回购物城页（马上就能看到新上架的那件）；商店/收购仍留在管理页，并同步全服快照。
        sendSnapshot(player, type == AdminEditType.MALL ? "mall" : "admin");
        broadcastSnapshots(player.server);
    }

    /** 购物城库存：留空 / 非数字 / 负数 = 无限库存（界面显示 ∞）。 */
    private static int parseMallStock(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return -1;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed < 0 ? -1 : parsed;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static void adminApplyPrice(ServerPlayer player, CompoundTag tag) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        int index = tag.getInt("index");
        // 确认后输入界面会立刻关闭：这里必须给一条**说明原因**的反馈，否则管理员会以为「确认没生效」。
        PriceSpec priceSpec = parseAdminSpec(player, tag);
        if (priceSpec == null) {
            return;
        }
        long rarityHint = priceSpec.supportsStar() ? priceSpec.starPrice : Math.max(1L, priceSpec.physCount);
        AdminEditType type = editType(player, tag);
        ShopSavedData data = data(player.server);
        if (type == AdminEditType.MALL) {
            if (index < 0 || index >= data.mallPool.size()) {
                return;
            }
            MallItem item = data.mallPool.get(index);
            item.setPriceSpec(priceSpec);
            item.rarity = parseRarity(tag.getString("rarity"), rarityHint);
            // 改库存同时更新「补货目标」：管理员填多少，定时补货以后就补回多少。
            item.setStock(parseMallStock(tag.getString("quantity")));
            if (tag.contains("permanent_limit") || tag.contains("permanentLimit")) {
                item.permanentLimit = isPermanentLimitFlag(tag);
            }
        } else if (type == AdminEditType.SHIPPING) {
            // 出货箱的"条目"就是兑换规则：同时支持改单价与每日出货上限（0 = 无限）。
            int dailyMax = parseOptionalNonNegativeInt(player, tag, "daily_max");
            if (dailyMax < 0) {
                return;
            }
            if (!ShippingRules.updateRule(index, priceSpec, dailyMax)) {
                notifyPlayer(player, Component.translatable("msg.module_shop.shipping_rule_broken")
                        .withStyle(ChatFormatting.RED));
            }
        } else {
            List<ConfiguredOffer> pool = type == AdminEditType.DAILY_SHOP ? data.shopPool : data.buybackPool;
            if (index < 0 || index >= pool.size()) {
                return;
            }
            ConfiguredOffer offer = pool.get(index);
            offer.setPriceSpec(priceSpec);
            // 需求 6/7：商店与收购都可单独设置品质、数量；品质留空时按价格推导。
            offer.rarity = parseRarity(tag.getString("rarity"), rarityHint);
            offer.amount = parseInt(tag.getString("quantity"));
        }
        data.setDirty();
        sendSnapshot(player, "admin");
        broadcastSnapshots(player.server);
    }

    private static void adminRemove(ServerPlayer player, CompoundTag tag) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        int index = tag.getInt("index");
        AdminEditType type = editType(player, tag);
        ShopSavedData data = data(player.server);
        if (type == AdminEditType.MALL) {
            if (index < 0 || index >= data.mallPool.size()) {
                return;
            }
            // 与每日商店一致：条目只是模板，原物始终留在管理员背包里，这里不再邮寄（否则等于复制物品）。
            data.mallPool.remove(index);
        } else if (type == AdminEditType.SHIPPING) {
            // 出货箱：删掉这条兑换规则（会写回配置文件）；格式错误的行同样能这样删掉。
            ShippingRules.removeEntry(index);
        } else {
            List<ConfiguredOffer> pool = type == AdminEditType.DAILY_SHOP ? data.shopPool : data.buybackPool;
            if (index < 0 || index >= pool.size()) {
                return;
            }
            pool.remove(index);
        }
        data.setDirty();
        sendSnapshot(player, "admin");
        broadcastSnapshots(player.server);
    }

    // =========================================================== 货币兑换
    //
    // 管理员配置「1 个物品 = N 星币」，玩家两个方向都能用：
    //   · 兑换（物品 → 星币）：按汇率把背包里的该货币折算成星币；
    //   · 取款（星币 → 物品）：按汇率扣星币后把货币物品发回玩家（背包放不下进邮箱）。
    // 所有汇率都用 ExchangeEntry.value 与 safeMultiply 处理，绝不出现负数或溢出。

    /** 取兑换条目并校验；下标越界或条目非法返回 null。 */
    private static ExchangeEntry exchangeEntry(ShopSavedData data, int index) {
        if (index < 0 || index >= data.exchangePool.size()) {
            return null;
        }
        ExchangeEntry entry = data.exchangePool.get(index);
        return entry != null && entry.valid() ? entry : null;
    }

    /** 背包中与该模板完全一致（同物品 + 同组件）的货币总数（用于“全部兑换”的默认数量与上限）。 */
    private static int countMatching(ServerPlayer player, ItemStack template) {
        Inventory inventory = player.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameTags(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 取走与该模板完全一致的货币，返回实际取走的数量。
     * 用「同物品 + 同组件」匹配而不是只比物品：管理员把某个带 NBT/自定义名称的货币设为兑换项时，
     * 普通同名物品不能被拿来兑换（否则等于用普通物品换特殊货币）。
     */
    private static int takeMatchingStack(ServerPlayer player, ItemStack template, int max) {
        Inventory inventory = player.getInventory();
        int taken = 0;
        for (int i = 0; i < inventory.getContainerSize() && taken < max; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !ItemStack.isSameItemSameTags(stack, template)) {
                continue;
            }
            int take = Math.min(max - taken, stack.getCount());
            stack.shrink(take);
            taken += take;
            if (stack.isEmpty()) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        return taken;
    }

    /** 「兑换」按钮：弹数量输入框，默认填满背包持有量。 */
    private static void exchangeDepositGui(ServerPlayer player, int index) {
        ExchangeEntry entry = exchangeEntry(data(player.server), index);
        if (entry == null) {
            notifyPlayer(player, Component.translatable("msg.module_shop.exchange_invalid")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (cn.autoforged.module_shop.shop.currency.PhysicalCurrencyConfig.get().isBlockingBuybackAndShipping()
                && cn.autoforged.module_shop.shop.currency.PhysicalCurrencyEngine.isCurrencyItem(
                        entry.stack, player.level().registryAccess())) {
            notifyPlayer(player, Component.translatableWithFallback(
                    "msg.module_shop.phys_exchange_blocked",
                    "防套利保护：该物品已作为实体货币启用，请在个人主页「货币兑换」进行凑整/拆零")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        int owned = countMatching(player, entry.stack);
        if (owned <= 0) {
            notifyPlayer(player, Component.translatable("msg.module_shop.exchange_no_items")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        PlayerAccount account = account(player);
        long day = realDay();
        String qKey = entry.quotaKey(player.level().registryAccess());
        int suggested = owned;
        if (entry.dailyDepositLimit > 0) {
            int used = account.depositUsedFor(qKey, day);
            int remaining = Math.max(0, entry.dailyDepositLimit - used);
            if (remaining <= 0) {
                notifyPlayer(player, Component.translatableWithFallback(
                        "msg.module_shop.exchange_daily_deposit_limit",
                        "今日该货币存入兑换已达上限（%s / %s）", used, entry.dailyDepositLimit)
                        .withStyle(ChatFormatting.RED));
                return;
            }
            suggested = Math.min(suggested, remaining);
        }
        CompoundTag extra = new CompoundTag();
        extra.putInt("index", index);
        extra.putString("default_quantity", Integer.toString(Math.max(1, suggested)));
        // 标题里带上真实货币名（客户端会用本地语言渲染，如「兑换数量（银币 → 星币）」）。
        openInput(player, "exchange_deposit", "gui.module_shop.input.exchange_deposit", extra,
                new String[]{entry.stack.getHoverName().getString(), currencyName(player.server)},
                "quantity");
    }

    /** 「取款」按钮：弹数量输入框，默认 1 个，界面显示当前余额。 */
    private static void exchangeWithdrawGui(ServerPlayer player, int index) {
        ExchangeEntry entry = exchangeEntry(data(player.server), index);
        if (entry == null) {
            notifyPlayer(player, Component.translatable("msg.module_shop.exchange_invalid")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (cn.autoforged.module_shop.shop.currency.PhysicalCurrencyConfig.get().isBlockingBuybackAndShipping()
                && cn.autoforged.module_shop.shop.currency.PhysicalCurrencyEngine.isCurrencyItem(
                        entry.stack, player.level().registryAccess())) {
            notifyPlayer(player, Component.translatableWithFallback(
                    "msg.module_shop.phys_exchange_blocked",
                    "防套利保护：该物品已作为实体货币启用，请在个人主页「货币兑换」进行凑整/拆零")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        PlayerAccount account = account(player);
        long spendable = Math.max(0L, account.balance);
        long affordable = spendable / entry.value;
        if (affordable <= 0L) {
            notifyPlayer(player, Component.translatable("msg.module_shop.exchange_withdraw_no_money", entry.value)
                    .withStyle(ChatFormatting.RED));
            return;
        }
        long day = realDay();
        String qKey = entry.quotaKey(player.level().registryAccess());
        int max = (int) Math.min(Integer.MAX_VALUE, affordable);
        if (entry.dailyWithdrawLimit > 0) {
            int used = account.withdrawUsedFor(qKey, day);
            int remaining = Math.max(0, entry.dailyWithdrawLimit - used);
            if (remaining <= 0) {
                notifyPlayer(player, Component.translatableWithFallback(
                        "msg.module_shop.exchange_daily_withdraw_limit",
                        "今日该货币取出兑换已达上限（%s / %s）", used, entry.dailyWithdrawLimit)
                        .withStyle(ChatFormatting.RED));
                return;
            }
            max = Math.min(max, remaining);
        }
        CompoundTag extra = new CompoundTag();
        extra.putInt("index", index);
        extra.putString("default_quantity", Integer.toString(Math.min(max, Math.max(1, entry.stack.getMaxStackSize()))));
        extra.putLong("balance", spendable);
        extra.putBoolean("show_balance", true);
        // 让输入界面能实时判断“填的数量买不起”，并在右侧红字提示「余额不足」。
        extra.putLong("unit_price", entry.value);
        extra.putString("total_key", "gui.module_shop.exchange.total");
        openInput(player, "exchange_withdraw", "gui.module_shop.input.exchange_withdraw", extra,
                new String[]{currencyName(player.server), entry.stack.getHoverName().getString()},
                "quantity");
    }

    /** 兑换（物品 → 星币）：余额装不下的部分不取走物品，绝不让物品凭空消失。 */
    private static void exchangeDeposit(ServerPlayer player, CompoundTag tag) {
        synchronized (LOCK) {
            ExchangeEntry entry = exchangeEntry(data(player.server), tag.getInt("index"));
            if (entry == null) {
                notifyPlayer(player, Component.translatable("msg.module_shop.exchange_invalid")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            if (PhysicalCurrencyConfig.get().isBlockingBuybackAndShipping()
                    && PhysicalCurrencyEngine.isCurrencyItem(
                            entry.stack, player.level().registryAccess())) {
                notifyPlayer(player, Component.translatableWithFallback(
                        "msg.module_shop.phys_exchange_blocked",
                        "防套利保护：该物品已作为实体货币启用，请在个人主页「货币兑换」进行凑整/拆零")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            PlayerAccount account = account(player);
            ShopSavedData data = data(player.server);
            long max = Config.maxBalanceLimit();
            long room = Math.max(0L, max - account.balance);
            if (room < entry.value) {
                notifyPlayer(player, Component.translatable("msg.module_shop.grant_cap_reached")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            int owned = countMatching(player, entry.stack);
            if (owned <= 0) {
                notifyPlayer(player, Component.translatable("msg.module_shop.exchange_no_items")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            int requested = parseInt(tag.getString("quantity"));
            int wanted = Math.min(requested <= 0 ? owned : requested, owned);
            int affordable = (int) Math.min(Integer.MAX_VALUE, room / entry.value);
            wanted = Math.min(wanted, affordable);
            long exDay = realDay();
            String qKey = entry.quotaKey(player.level().registryAccess());
            if (entry.dailyDepositLimit > 0) {
                int used = account.depositUsedFor(qKey, exDay);
                int remaining = Math.max(0, entry.dailyDepositLimit - used);
                if (remaining <= 0) {
                    notifyPlayer(player, Component.translatableWithFallback(
                            "msg.module_shop.exchange_daily_deposit_limit",
                            "今日该货币存入兑换已达上限（%s / %s）", used, entry.dailyDepositLimit)
                            .withStyle(ChatFormatting.RED));
                    return;
                }
                wanted = Math.min(wanted, remaining);
            }
            if (wanted <= 0 || safeMultiply(entry.value, wanted) <= 0L) {
                notifyPlayer(player, Component.translatable("msg.module_shop.grant_cap_reached")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            int taken = takeMatchingStack(player, entry.stack, wanted);
            if (taken <= 0) {
                notifyPlayer(player, Component.translatable("msg.module_shop.exchange_no_items")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            long gain = safeMultiply(entry.value, taken);
            long added = CurrencySettlement.payoutStarOnly(
                    player.server, player.getUUID(), account, null, gain, false, false,
                    SOURCE_EXCHANGE, null, null);
            int effectiveTaken = taken;
            if (added < gain) {
                // 兜底：上面已按余额空间夹取，这里只防配置在两次读取之间被改小，差额原样退回物品。
                int back = (int) Math.min(Integer.MAX_VALUE, (gain - added) / entry.value);
                if (back > 0) {
                    deliver(player, entry.stack, back, SOURCE_EXCHANGE);
                    effectiveTaken = Math.max(0, taken - back);
                }
            }
            account.recordDepositUsed(qKey, exDay, effectiveTaken);
            data.setDirty();
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.EXCHANGE_BUY, "货币兑换",
                    added, account.balance, entry.stack.getHoverName().getString() + "×" + effectiveTaken);
            notifyPlayer(player, Component.translatable("msg.module_shop.exchange_done",
                    entry.stack.getHoverName(), effectiveTaken, added).withStyle(ChatFormatting.GREEN));
            sendSnapshot(player, "exchange");
        }
    }

    /** 取款（星币 → 物品）：先确认余额再扣款发货，背包放不下的进邮箱。 */
    private static void exchangeWithdraw(ServerPlayer player, CompoundTag tag) {
        synchronized (LOCK) {
            ExchangeEntry entry = exchangeEntry(data(player.server), tag.getInt("index"));
            if (entry == null) {
                notifyPlayer(player, Component.translatable("msg.module_shop.exchange_invalid")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            if (PhysicalCurrencyConfig.get().isBlockingBuybackAndShipping()
                    && PhysicalCurrencyEngine.isCurrencyItem(
                            entry.stack, player.level().registryAccess())) {
                notifyPlayer(player, Component.translatableWithFallback(
                        "msg.module_shop.phys_exchange_blocked",
                        "防套利保护：该物品已作为实体货币启用，请在个人主页「货币兑换」进行凑整/拆零")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            PlayerAccount account = account(player);
            ShopSavedData data = data(player.server);
            int requested = parseInt(tag.getString("quantity"));
            long spendable = Math.max(0L, account.balance);
            int affordable = (int) Math.min(Integer.MAX_VALUE, spendable / entry.value);
            int wanted = Math.min(requested <= 0 ? 1 : requested, affordable);
            long exDay = realDay();
            String qKey = entry.quotaKey(player.level().registryAccess());
            if (entry.dailyWithdrawLimit > 0) {
                int used = account.withdrawUsedFor(qKey, exDay);
                int remaining = Math.max(0, entry.dailyWithdrawLimit - used);
                if (remaining <= 0) {
                    notifyPlayer(player, Component.translatableWithFallback(
                            "msg.module_shop.exchange_daily_withdraw_limit",
                            "今日该货币取出兑换已达上限（%s / %s）", used, entry.dailyWithdrawLimit)
                            .withStyle(ChatFormatting.RED));
                    return;
                }
                wanted = Math.min(wanted, remaining);
            }
            long cost = safeMultiply(entry.value, wanted);
            if (wanted <= 0 || cost <= 0L) {
                notifyPlayer(player, Component.translatable("msg.module_shop.exchange_withdraw_no_money",
                        Math.max(entry.value, cost)).withStyle(ChatFormatting.RED));
                return;
            }
            var payRes = CurrencySettlement.payStar(account, cost, "purchase");
            if (!payRes.ok()) {
                CurrencySettlement.notifyPayFailure(player, payRes);
                return;
            }
            boolean mailed = deliver(player, entry.stack, wanted, SOURCE_EXCHANGE);
            account.recordWithdrawUsed(qKey, exDay, wanted);
            data.setDirty();
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.EXCHANGE_WITHDRAW, "货币兑换",
                    -cost, account.balance, entry.stack.getHoverName().getString() + "×" + wanted);
            notifyPlayer(player, Component.translatable("msg.module_shop.exchange_withdraw_done",
                    cost, entry.stack.getHoverName(), wanted).withStyle(ChatFormatting.GREEN));
            if (mailed) {
                notifyPlayer(player, Component.translatable("msg.module_shop.inventory_full_mail")
                        .withStyle(ChatFormatting.YELLOW));
            }
            sendSnapshot(player, "exchange");
        }
    }

    /** 管理员：新增兑换货币（模板来自幽灵槽，价格来自输入框）；同一物品重复添加视为改价。 */
    private static void adminExchangeSet(ServerPlayer player, CompoundTag tag) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        ItemStack stack = ADMIN_TEMPLATE.remove(player.getUUID());
        if (stack == null || stack.isEmpty()) {
            return;
        }
        long value = parsePrice(tag.getString("value"));
        if (value <= 0L) {
            notifyPlayer(player, Component.translatable("msg.module_shop.exchange_invalid")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        int depLimit = parseOptionalExchangeLimit(player, tag, "daily_deposit_limit");
        int wdLimit = parseOptionalExchangeLimit(player, tag, "daily_withdraw_limit");
        if (depLimit < 0 || wdLimit < 0) {
            return;
        }
        ShopSavedData data = data(player.server);
        ExchangeEntry entry = new ExchangeEntry(stack, value, depLimit, wdLimit);
        entry.normalize();
        for (ExchangeEntry existing : data.exchangePool) {
            if (ItemStack.isSameItemSameTags(existing.stack, entry.stack)) {
                existing.value = value;
                existing.dailyDepositLimit = depLimit;
                existing.dailyWithdrawLimit = wdLimit;
                data.setDirty();
                sendSnapshot(player, "exchange");
                notifyPlayer(player, Component.translatable("msg.module_shop.exchange_updated",
                        entry.stack.getHoverName(), value).withStyle(ChatFormatting.GREEN));
                return;
            }
        }
        data.exchangePool.add(entry);
        data.setDirty();
        sendSnapshot(player, "exchange");
        notifyPlayer(player, Component.translatable("msg.module_shop.exchange_added",
                entry.stack.getHoverName(), value).withStyle(ChatFormatting.GREEN));
    }

    /** 管理员：改汇率与每日限额（界面左键点击条目）。 */
    private static void adminExchangeEdit(ServerPlayer player, CompoundTag tag) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        int index = tag.getInt("index");
        ShopSavedData data = data(player.server);
        long value = parsePrice(tag.getString("value"));
        ExchangeEntry entry = exchangeEntry(data, index);
        if (entry == null || value <= 0L) {
            notifyPlayer(player, Component.translatable("msg.module_shop.exchange_invalid")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        int depLimit = tag.contains("daily_deposit_limit")
                ? parseOptionalExchangeLimit(player, tag, "daily_deposit_limit")
                : entry.dailyDepositLimit;
        int wdLimit = tag.contains("daily_withdraw_limit")
                ? parseOptionalExchangeLimit(player, tag, "daily_withdraw_limit")
                : entry.dailyWithdrawLimit;
        if (depLimit < 0 || wdLimit < 0) {
            return;
        }
        entry.value = value;
        entry.dailyDepositLimit = depLimit;
        entry.dailyWithdrawLimit = wdLimit;
        data.setDirty();
        sendSnapshot(player, "exchange");
        notifyPlayer(player, Component.translatable("msg.module_shop.exchange_updated",
                entry.stack.getHoverName(), value).withStyle(ChatFormatting.GREEN));
    }

    /** 管理员：移除兑换货币（模板不退还，原因同商品池：退还会复制物品）。 */
    private static void adminExchangeRemove(ServerPlayer player, CompoundTag tag) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        ShopSavedData data = data(player.server);
        ExchangeEntry entry = exchangeEntry(data, tag.getInt("index"));
        if (entry == null) {
            return;
        }
        data.exchangePool.remove(entry);
        data.setDirty();
        sendSnapshot(player, "exchange");
        notifyPlayer(player, Component.translatable("msg.module_shop.exchange_removed",
                entry.stack.getHoverName()).withStyle(ChatFormatting.YELLOW));
    }

    // =========================================================== 签到（需求 7）

    /** 现实日期（本地时区当天，epochDay）：签到按现实 0 点刷新。 */
    public static long realDay() {
        return java.time.LocalDate.now().toEpochDay();
    }

    /**
     * 签到里程碑的轮次长度：每连续签满这么多天算一轮，本轮里程奖励（3/5/7 天那些档位）
     * 在新一轮里可以**重新领一次**，所以不会出现「领完就没了」。
     */
    public static final int SIGNIN_CYCLE_DAYS = 7;

    /** 连续签到第 {@code streak} 天属于第几轮（从 1 开始；没签过返回第 1 轮）。 */
    public static int signInRound(int streak) {
        return streak <= 0 ? 1 : (streak - 1) / SIGNIN_CYCLE_DAYS + 1;
    }

    /** 连续签到第 {@code streak} 天是**本轮**的第几天（1..{@link #SIGNIN_CYCLE_DAYS}；没签过返回 0）。 */
    public static int signInRoundDay(int streak) {
        return streak <= 0 ? 0 : (streak - 1) % SIGNIN_CYCLE_DAYS + 1;
    }

    /** 连续签到到某天数时，每天额外多拿多少星币（累计加成，关闭时返回 0）。 */
    private static long cumulativeBonus(ShopSavedData data, int streak) {
        PriceSpec spec = cumulativeBonusSpec(data, streak);
        return spec.supportsStar() ? spec.starPrice : 0L;
    }

    private static PriceSpec cumulativeBonusSpec(ShopSavedData data, int streak) {
        if (!data.signInCumulative) {
            return PriceSpec.ZERO;
        }
        int bestDay = -1;
        PriceSpec best = PriceSpec.ZERO;
        for (int i = 0; i < data.signInTierDays.length && i < data.signInTierBonusSpecs.length; i++) {
            int reqDay = data.signInTierDays[i];
            if (streak >= reqDay) {
                PriceSpec candidate = data.resolveSignInTierSpec(i);
                if (candidate.isZero()) {
                    continue;
                }
                if (best.isZero()) {
                    best = candidate;
                    bestDay = reqDay;
                } else if (best.mode == CurrencyMode.STAR_ONLY && candidate.mode == CurrencyMode.STAR_ONLY) {
                    if (candidate.starPrice >= best.starPrice) {
                        best = candidate;
                        bestDay = reqDay;
                    }
                } else if (reqDay >= bestDay) {
                    best = candidate;
                    bestDay = reqDay;
                }
            }
        }
        return best;
    }

    private static String formatSignSummary(long starAmount, Map<String, Long> physTotals, @Nullable MinecraftServer server) {
        StringBuilder sb = new StringBuilder();
        if (starAmount > 0L) {
            sb.append(starAmount).append(" ").append(currencyName(server));
        }
        for (Map.Entry<String, Long> e : physTotals.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0L) {
                if (sb.length() > 0) {
                    sb.append(" + ");
                }
                sb.append(CurrencySettlement.formatSpec(PriceSpec.phys(e.getKey(), e.getValue()), server));
            }
        }
        return sb.length() == 0 ? ("0 " + currencyName(server)) : sb.toString();
    }

    /** 真正结算一次签到（玩家点击或登录自动），支持双独立账本。 */
    private static void doSignIn(ServerPlayer player) {
        synchronized (LOCK) {
            ShopSavedData data = data(player.server);
            if (!data.signInEnabled) {
                return;
            }
            long today = realDay();
            SignInData sign = data.signIn(player.getUUID());
            if (sign.signedOn(today)) {
                notifyPlayer(player, Component.translatable("msg.module_shop.signin_done")
                        .withStyle(ChatFormatting.YELLOW));
                sendSnapshot(player, "signin");
                return;
            }
            sign.streak = sign.lastSignDay == today - 1L ? sign.streak + 1 : 1;
            // 里程奖励改成「每轮一次」：断签回到第 1 轮，连续签满一轮进入下一轮，
            // 两种情况都把「本轮已领」清空，于是 3/5/7 天在新一轮里能再领一次（不再是一辈子一次）。
            int round = signInRound(sign.streak);
            if (sign.lastSignDay != today - 1L || sign.claimedRound != round) {
                sign.claimedRound = round;
                sign.claimedMilestones.clear();
            }
            sign.lastSignDay = today;
            sign.signedDays.add(today);
            if (sign.streak > sign.bestStreak) {
                sign.bestStreak = sign.streak;
            }
            PlayerAccount account = account(player);
            long max = Config.maxBalanceLimit();

            long added = 0L;
            Map<String, Long> dailyPhys = new java.util.LinkedHashMap<>();
            boolean walletBlocked = false;

            PriceSpec[] baseAndCum = {data.resolveSignInBaseSpec(), cumulativeBonusSpec(data, sign.streak)};
            for (PriceSpec spec : baseAndCum) {
                if (spec == null || spec.isZero() || !spec.isValid()) {
                    continue;
                }
                long room = Math.max(0L, max - account.balance);
                boolean wantStar = spec.mode.includesStar();
                boolean wantPhys = spec.mode.includesPhys();
                if (spec.mode == CurrencyMode.EITHER) {
                    boolean starPossible = spec.starPrice > 0L && room > 0L;
                    if (!starPossible && spec.supportsPhys() && PhysicalCurrencyConfig.get().isActive()) {
                        wantStar = false;
                        wantPhys = true;
                    } else {
                        wantStar = true;
                        wantPhys = false;
                    }
                }
                if (wantStar && spec.starPrice > 0L) {
                    if (room <= 0L) {
                        walletBlocked = true;
                    } else {
                        long got = CurrencySettlement.payoutStarOnly(
                                player.server, player.getUUID(), account, data,
                                Math.min(spec.starPrice, room), false, false, SOURCE_ADMIN, null, null);
                        if (got > 0L) {
                            added += got;
                        }
                    }
                }
                if (wantPhys && spec.supportsPhys()) {
                    CurrencySettlement.payoutPhysTier(
                            player.server, player.getUUID(), spec.physTierKey, spec.physCount, SOURCE_ADMIN);
                    dailyPhys.merge(spec.physTierKey, spec.physCount, Long::sum);
                }
            }

            // 里程奖励：**每轮**连续签到达到配置天数时发一次（同一轮只发一次，下一轮可再领）。
            int roundDay = signInRoundDay(sign.streak);
            long mileAdded = 0L;
            Map<String, Long> milePhys = new java.util.LinkedHashMap<>();
            for (int i = 0; i < data.signInMileDays.length && i < data.signInMileSpecs.length; i++) {
                int need = data.signInMileDays[i];
                if (need <= 0 || need > SIGNIN_CYCLE_DAYS || roundDay < need || sign.claimedMilestones.contains(need)) {
                    continue;
                }
                PriceSpec mSpec = data.resolveSignInMileSpec(i);
                if (mSpec.isZero() || !mSpec.isValid()) {
                    sign.claimedMilestones.add(need);
                    continue;
                }
                long room = Math.max(0L, max - account.balance);
                boolean wantStar = mSpec.mode.includesStar();
                boolean wantPhys = mSpec.mode.includesPhys();
                if (mSpec.mode == CurrencyMode.EITHER) {
                    boolean starPossible = mSpec.starPrice > 0L && room > 0L;
                    if (!starPossible && mSpec.supportsPhys() && PhysicalCurrencyConfig.get().isActive()) {
                        wantStar = false;
                        wantPhys = true;
                    } else {
                        wantStar = true;
                        wantPhys = false;
                    }
                }
                long mCredited = 0L;
                long mPhysPaid = 0L;
                if (wantStar && mSpec.starPrice > 0L) {
                    if (room <= 0L) {
                        walletBlocked = true;
                    } else {
                        mCredited = CurrencySettlement.payoutStarOnly(
                                player.server, player.getUUID(), account, data,
                                Math.min(mSpec.starPrice, room), false, false, SOURCE_ADMIN, null, null);
                    }
                }
                if (wantPhys && mSpec.supportsPhys()) {
                    CurrencySettlement.payoutPhysTier(
                            player.server, player.getUUID(), mSpec.physTierKey, mSpec.physCount, SOURCE_ADMIN);
                    milePhys.merge(mSpec.physTierKey, mSpec.physCount, Long::sum);
                    mPhysPaid = mSpec.physCount;
                }
                if (mCredited > 0L || mPhysPaid > 0L) {
                    sign.claimedMilestones.add(need);
                    mileAdded += mCredited;
                }
            }

            data.setDirty();
            Map<String, Long> totalPhys = new java.util.LinkedHashMap<>(dailyPhys);
            milePhys.forEach((k, v) -> totalPhys.merge(k, v, Long::sum));
            String logDetail = Component.translatable("msg.module_shop.signin_note", sign.streak).getString()
                    + (totalPhys.isEmpty() ? "" : " [" + formatSignSummary(0L, totalPhys, player.server) + "]");
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.SIGN_IN, "每日签到",
                    added + mileAdded, account.balance, logDetail);
            // 事件：签到完成（成功后发布）
            cn.autoforged.module_shop.api.event.ApiEvents.post(
                    new cn.autoforged.module_shop.api.event.SignInCompletedEvent(
                            player.getUUID(), ShopManager.realDay(), sign.streak, added + mileAdded));
            if (dailyPhys.isEmpty()) {
                notifyPlayer(player, Component.translatable("msg.module_shop.signin_ok",
                        added, sign.streak).withStyle(ChatFormatting.GREEN));
            } else {
                notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.signin_ok_spec",
                        "签到成功！获得 %s（已连续签到 %s 天）",
                        formatSignSummary(added, dailyPhys, player.server), sign.streak)
                        .withStyle(ChatFormatting.GREEN));
            }
            if (mileAdded > 0L || !milePhys.isEmpty()) {
                if (milePhys.isEmpty()) {
                    notifyPlayer(player, Component.translatable("msg.module_shop.signin_milestone", mileAdded)
                            .withStyle(ChatFormatting.GOLD));
                } else {
                    notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.signin_milestone_spec",
                            "达成连签里程碑！额外获得 %s",
                            formatSignSummary(mileAdded, milePhys, player.server))
                            .withStyle(ChatFormatting.GOLD));
                }
            }
            if (walletBlocked && added + mileAdded <= 0L && totalPhys.isEmpty()) {
                notifyPlayer(player, Component.translatable("msg.module_shop.grant_cap_reached")
                        .withStyle(ChatFormatting.RED));
            }
            sendSnapshot(player, "signin");
        }
    }

    /**
     * 从已签到记录重算「当前连续天数」：以今天（或最后一次签到日）为终点向前数。
     * 补签把缺口补上以后，连续天数会重新连成一条，连续签到加成也就随之生效。
     */
    private static int recomputeStreak(SignInData sign, long today) {
        long cursor;
        if (sign.signedDays.contains(today)) {
            cursor = today;
        } else if (sign.signedDays.contains(today - 1L)) {
            cursor = today - 1L;
        } else {
            cursor = sign.lastSignDay;
        }
        int streak = 0;
        while (cursor > 0L && sign.signedDays.contains(cursor)) {
            streak++;
            cursor--;
        }
        return streak;
    }

    /** 补签：花星币/实体货币把过去某一天补成已签到，并**重算连续天数**（补上缺口后连续签到加成立即生效）。 */
    private static void doMakeupSign(ServerPlayer player, long day) {
        doMakeupSign(player, day, "");
    }

    private static void doMakeupSign(ServerPlayer player, long day, @Nullable String payChannel) {
        synchronized (LOCK) {
            ShopSavedData data = data(player.server);
            long today = realDay();
            SignInData sign = data.signIn(player.getUUID());
            if (!sign.canMakeup(day, today, data.signInMakeupDays)) {
                notifyPlayer(player, Component.translatable("msg.module_shop.signin_makeup_invalid")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            PlayerAccount account = account(player);
            PriceSpec costSpec = data.resolveSignInMakeupSpec();
            var payRes = CurrencySettlement.paySpec(
                    player, account, data, costSpec, 1, payChannel, "purchase", SOURCE_EXCHANGE, false);
            if (!payRes.ok()) {
                if ("msg.module_shop.no_money".equals(payRes.failKey()) && costSpec.supportsStar()) {
                    notifyPlayer(player, Component.translatable("msg.module_shop.signin_makeup_no_money",
                            costSpec.starPrice).withStyle(ChatFormatting.RED));
                } else {
                    CurrencySettlement.notifyPayFailure(player, payRes);
                }
                return;
            }
            sign.signedDays.add(day);
            // 补签后重算连续天数（只增不减，避免把原本更高的记录改小）：
            // 之前补签只补记录、不动连续天数，所以玩家补完签加成永远激活不了。
            int recomputed = recomputeStreak(sign, today);
            if (recomputed > sign.streak) {
                sign.streak = recomputed;
            }
            if (sign.streak > sign.bestStreak) {
                sign.bestStreak = sign.streak;
            }
            data.setDirty();
            PriceSpec paidSpec = paidSpecOf(payRes, costSpec);
            TransactionLog.record(player.server, player.getUUID(), TransactionLog.SIGN_MAKEUP, "补签",
                    -payRes.starPaid(), account.balance,
                    "补签 " + day + (payRes.paidPhys() ? " [" + CurrencySettlement.formatSpec(paidSpec, player.server) + "]" : ""));
            if (payRes.paidPhys()) {
                notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.signin_makeup_ok_streak_spec",
                        "补签成功！花费 %s（当前连续签到 %s 天）",
                        CurrencySettlement.formatSpec(paidSpec, player.server), sign.streak)
                        .withStyle(ChatFormatting.GREEN));
            } else {
                notifyPlayer(player, Component.translatable("msg.module_shop.signin_makeup_ok_streak",
                        payRes.starPaid(), sign.streak).withStyle(ChatFormatting.GREEN));
            }
            sendSnapshot(player, "signin");
        }
    }

    /** 管理员：登录自动签到开关。 */
    private static void adminSignInAutoToggle(ServerPlayer player) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        ShopSavedData data = data(player.server);
        data.signInAuto = !data.signInAuto;
        data.setDirty();
        sendSnapshot(player, "signin");
        notifyPlayer(player, Component.translatable(data.signInAuto
                ? "msg.module_shop.signin_auto_on" : "msg.module_shop.signin_auto_off")
                .withStyle(ChatFormatting.GREEN));
    }

    /** 管理员：累计签到加成开关（关闭时界面不显示里程进度条）。 */
    private static void adminSignInCumulativeToggle(ServerPlayer player) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        ShopSavedData data = data(player.server);
        data.signInCumulative = !data.signInCumulative;
        data.setDirty();
        sendSnapshot(player, "signin");
        notifyPlayer(player, Component.translatable(data.signInCumulative
                ? "msg.module_shop.signin_cumulative_on" : "msg.module_shop.signin_cumulative_off")
                .withStyle(ChatFormatting.GREEN));
    }

    /**
     * 管理员：一次性修改签到设置（支持四态 PriceSpec）。
     * fields：baseSpec / base=每日基础奖励，makeupSpec / makeupCost=补签花费，makeupDays=可补签天数，
     * tiers=「天数:每日加成」逗号分隔（如 3:5,5:phys:copper*2,7:10），miles=「天数:一次性奖励」逗号分隔。
     */
    private static void adminSignInSet(ServerPlayer player, CompoundTag tag) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        ShopSavedData data = data(player.server);
        PriceSpec baseSpec = parseSpecField(player, tag, "baseSpec", "base", true);
        if (baseSpec == null) {
            return;
        }
        String makeupSpecKey = tag.contains("makeupSpec", Tag.TAG_COMPOUND) ? "makeupSpec" : "makeupCostSpec";
        PriceSpec makeupSpec = parseSpecField(player, tag, makeupSpecKey, "makeupCost", true);
        if (makeupSpec == null) {
            return;
        }
        int days = parseInt(tag.getString("makeupDays"));
        int[] tiers = parsePairs(tag.getString("tiers"), data.signInTierDays);
        PriceSpec[] currentTierSpecs = new PriceSpec[data.signInTierBonusSpecs.length];
        for (int i = 0; i < currentTierSpecs.length; i++) {
            currentTierSpecs[i] = data.resolveSignInTierSpec(i);
        }
        PriceSpec[] tierSpecs = parsePairSpecs(player, tag.getString("tiers"), currentTierSpecs);
        if (tierSpecs == null) {
            return;
        }
        int[] miles = parsePairs(tag.getString("miles"), data.signInMileDays);
        PriceSpec[] currentMileSpecs = new PriceSpec[data.signInMileSpecs.length];
        for (int i = 0; i < currentMileSpecs.length; i++) {
            currentMileSpecs[i] = data.resolveSignInMileSpec(i);
        }
        PriceSpec[] mileSpecs = parsePairSpecs(player, tag.getString("miles"), currentMileSpecs);
        if (mileSpecs == null) {
            return;
        }
        if (tag.contains("baseSpec", Tag.TAG_COMPOUND) || tag.contains("base")) {
            data.setSignInBaseSpec(baseSpec);
        }
        if (tag.contains("makeupSpec", Tag.TAG_COMPOUND) || tag.contains("makeupCostSpec", Tag.TAG_COMPOUND)
                || tag.contains("makeupCost")) {
            data.setSignInMakeupSpec(makeupSpec);
        }
        if (days > 0) {
            data.signInMakeupDays = Math.min(30, days);
        }
        System.arraycopy(tiers, 0, data.signInTierDays, 0, Math.min(tiers.length, data.signInTierDays.length));
        for (int i = 0; i < Math.min(tierSpecs.length, data.signInTierBonusSpecs.length); i++) {
            data.setSignInTierSpec(i, tierSpecs[i]);
        }
        System.arraycopy(miles, 0, data.signInMileDays, 0, Math.min(miles.length, data.signInMileDays.length));
        for (int i = 0; i < Math.min(mileSpecs.length, data.signInMileSpecs.length); i++) {
            data.setSignInMileSpec(i, mileSpecs[i]);
        }
        data.signInEnabled = !data.resolveSignInBaseSpec().isZero() || !data.resolveSignInMileSpec(0).isZero();
        data.setDirty();
        sendSnapshot(player, "signin");
        notifyPlayer(player, Component.translatable("msg.module_shop.signin_set_ok")
                .withStyle(ChatFormatting.GREEN));
    }

    /** 解析「3:5,5:7,7:10」或「3:phys:copper*2,...」里的天数部分；解析失败时保留原值。 */
    private static int[] parsePairs(String text, int[] fallback) {
        int[] out = fallback.clone();
        if (text == null || text.isBlank()) {
            return out;
        }
        String[] parts = text.split(",");
        for (int i = 0; i < parts.length && i < out.length; i++) {
            String[] kv = parts[i].split(":", 2);
            if (kv.length == 2) {
                int v = parseInt(kv[0]);
                if (v > 0) {
                    out[i] = v;
                }
            }
        }
        return out;
    }

    private static @Nullable PriceSpec[] parsePairSpecs(ServerPlayer player, String text, PriceSpec[] fallback) {
        PriceSpec[] out = fallback.clone();
        if (text == null || text.isBlank()) {
            return out;
        }
        String[] parts = text.split(",");
        for (int i = 0; i < parts.length && i < out.length; i++) {
            String[] kv = parts[i].split(":", 2);
            if (kv.length != 2) {
                continue;
            }
            String val = kv[1].trim();
            PriceSpec parsed = val.isEmpty() || "0".equals(val) ? PriceSpec.ZERO : PriceSpec.parseRuleText(val);
            if (parsed == null) {
                notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price")
                        .withStyle(ChatFormatting.RED));
                return null;
            }
            PriceSpec validated = validateAdminSpec(player, parsed, true);
            if (validated == null) {
                return null;
            }
            out[i] = validated;
        }
        return out;
    }

    /** 登录时按设置自动签到。 */
    public static void autoSignInIfNeeded(ServerPlayer player) {
        ShopSavedData data = data(player.server);
        if (!data.signInEnabled || !data.signInAuto) {
            return;
        }
        if (!data.signIn(player.getUUID()).signedOn(realDay())) {
            doSignIn(player);
        }
    }

    /**
     * 管理员：设置某一天为活动日（标题、公告正文、当天登录发放的星币、是否给离线玩家补发）。
     * 不填标题且金额为 0 视为取消该活动日。
     */
    private static void adminActivityDaySet(ServerPlayer player, CompoundTag tag) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        ShopSavedData data = data(player.server);
        long day = tag.getLong("day");
        if (day <= 0L) {
            day = realDay();
        }
        String title = tag.getString("title").trim();
        String note = tag.getString("note").trim();
        // 第八轮：公告/颜色/时间由「日程编辑」界面写，规则开关由「日程奖励设置」写，奖励由「日程奖励」容器写。
        // 每个界面只写自己负责的字段，**未携带的键一律保持原值**，避免被别的界面的空值覆盖
        // （此前奖励界面会用它打开时捕获的标题回写，标题就丢了）。
        ActivityDay existing = data.activityDay(day);
        boolean hasMoney = tag.contains("moneySpec", Tag.TAG_COMPOUND) || tag.contains("money");
        if (!tag.contains("title") && !tag.contains("note") && !hasMoney
                && !tag.contains("color") && !tag.contains("hour")
                && !tag.contains("offline") && !tag.contains("catchUp")) {
            // 什么都没带：只推一次快照，不动数据。
            sendSnapshot(player, "signin");
            return;
        }
        PriceSpec moneySpec = existing == null ? PriceSpec.ZERO : existing.resolveMoneySpec();
        if (hasMoney) {
            moneySpec = parseSpecField(player, tag, "moneySpec", "money", true);
            if (moneySpec == null) {
                return;
            }
            if (moneySpec.supportsStar() && moneySpec.starPrice > Config.maxBalanceLimit()) {
                moneySpec = moneySpec.withStarPrice(Config.maxBalanceLimit());
            }
        }
        boolean offline = tag.contains("offline")
                ? ("true".equalsIgnoreCase(tag.getString("offline").trim())
                        || "1".equals(tag.getString("offline").trim()))
                : existing != null && existing.forOffline;
        // 判断「这条日程是不是空的」要把**草稿**也算上：标题留空但草稿里配了奖励，仍然是一条有效日程。
        ActivityDraft pending = ACTIVITY_DRAFTS.get(player.getUUID());
        boolean draftMatch = pending != null && pending.day == day;
        boolean hasItems = (existing != null && !existing.rewardStacks().isEmpty())
                || (draftMatch && !pending.items.isEmpty());
        if (draftMatch) {
            moneySpec = pending.moneySpec;
            offline = pending.offline;
        }
        // 清空条件：界面明确提交了空标题，且没有金额/实体货币、没有物品奖励。
        if (tag.contains("title") && title.isEmpty() && moneySpec.isZero() && !hasItems) {
            data.activityDays.remove(day);
            ACTIVITY_DRAFTS.remove(player.getUUID());
            data.setDirty();
            notifyPlayer(player, Component.translatable("msg.module_shop.activity_cleared")
                    .withStyle(ChatFormatting.YELLOW));
            // 发布后草稿已清空：让客户端的草稿视图也跟着清掉。
            sendActivityDraft(player);
        } else {
            ActivityDay act = data.setActivityDay(day);
            if (tag.contains("title")) {
                act.title = title;
            }
            if (tag.contains("note")) {
                act.note = note;
            }
            if (hasMoney) {
                act.setMoneySpec(moneySpec);
            }
            if (tag.contains("offline")) {
                act.forOffline = offline;
            }
            if (tag.contains("catchUp")) {
                // 第七项：错过即补发开关（客户端默认 true）。
                act.catchUp = !"false".equalsIgnoreCase(tag.getString("catchUp").trim());
            }
            if (tag.contains("color")) {
                act.color = parseColor(tag.getString("color"));
            }
            if (tag.contains("hour")) {
                act.hour = parseHour(tag.getString("hour"));
            }
            if (tag.contains("gift")) {
                ItemStack giftStack = parseItemText(tag.getString("gift"));
                act.gift = giftStack.isEmpty() ? ItemStack.EMPTY : giftStack;
                act.giftGranted.clear();
            }
            if (tag.contains("item")) {
                ItemStack itemStack = parseItemText(tag.getString("item"));
                act.stack = itemStack.isEmpty() ? ItemStack.EMPTY : itemStack;
                act.itemGranted.clear();
            }
            // 草稿里的「发放规则 + 奖励物品」在这里一并发布 —— 这是整个日程编辑流程里**唯一**的落库点。
            ActivityDraft draft = ACTIVITY_DRAFTS.remove(player.getUUID());
            if (draft != null && draft.day == day) {
                act.hour = draft.hour;
                act.forOffline = draft.offline;
                act.catchUp = draft.catchUp;
                act.setMoneySpec(draft.moneySpec);
                if (!sameRewards(act.rewards, draft.items)) {
                    act.rewards.clear();
                    act.rewards.addAll(draft.items);
                    // 奖励换了：之前「已领过物品」的记录清空，新奖励应该重新发一次。
                    act.rewardsGranted.clear();
                    act.itemGranted.clear();
                    act.giftGranted.clear();
                    act.stack = ItemStack.EMPTY;
                    act.gift = ItemStack.EMPTY;
                }
            }
            data.setDirty();
            notifyPlayer(player, Component.translatable("msg.module_shop.activity_set", day)
                    .withStyle(ChatFormatting.GREEN));
            // 草稿已合并进正式日程：清掉客户端的草稿视图。
            sendActivityDraft(player);
        }
        // 「保存」= 发布：这次要推给**所有在线玩家**（以前只推给操作的管理员，别人得等下一次快照）。
        broadcastSnapshots(player.server);
        sendSnapshot(player, "signin");
    }

    /** 奖励物品的「id xN」文本（客户端据此画物品图标）。 */
    private static String rewardIdsText(List<ItemStack> stacks) {
        StringBuilder sb = new StringBuilder();
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()))
                    .append(" x").append(stack.getCount());
        }
        return sb.toString();
    }

    /** 管理员：取消某天的活动日。 */
    private static void adminActivityDayClear(ServerPlayer player, CompoundTag tag) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        ShopSavedData data = data(player.server);
        data.activityDays.remove(tag.getLong("day"));
        // 删了日程就别留着对应日期的草稿，否则下次编辑会被旧草稿带偏。
        ActivityDraft draft = ACTIVITY_DRAFTS.get(player.getUUID());
        if (draft != null && draft.day == tag.getLong("day")) {
            ACTIVITY_DRAFTS.remove(player.getUUID());
        }
        data.setDirty();
        sendSnapshot(player, "signin");
        notifyPlayer(player, Component.translatable("msg.module_shop.activity_cleared")
                .withStyle(ChatFormatting.YELLOW));
    }

    // =========================================================== 日程奖励（第八轮：物品发放式界面）

    /** 正在编辑「日程奖励」的管理员 → 目标日期 + 打开时的原始槽位内容。 */
    private record RewardEdit(long day, List<ItemStack> prefill) {
    }

    private static final Map<UUID, RewardEdit> REWARD_EDIT = new java.util.concurrent.ConcurrentHashMap<>();

    // =========================================================== 日程编辑草稿
    //
    // 日程编辑分成三个界面：编辑界面（标题/正文/颜色）、奖励设置（发放规则）、奖励容器（物品+金额）。
    // 以前每个界面点「保存」都直接写正式日程 = 每步都在发布，于是「进一下奖励界面再返回」
    // 玩家就已经能看到这个日程了。现在这些操作只改**草稿**，只有编辑界面点「保存」才落库并广播。

    /** 管理员正在编辑的日程草稿（每个管理员一份）。 */
    private static final class ActivityDraft {
        long day;
        String title = "";
        String note = "";
        int color;
        int hour = -1;
        boolean offline;
        boolean catchUp = true;
        PriceSpec moneySpec = PriceSpec.ZERO;
        final List<ItemStack> items = new ArrayList<>();
    }

    private static final Map<UUID, ActivityDraft> ACTIVITY_DRAFTS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 取该管理员的草稿；没有（或换了日期）就从正式日程复制一份新的。 */
    private static ActivityDraft draftOf(ShopSavedData data, ServerPlayer player, long day) {
        ActivityDraft draft = ACTIVITY_DRAFTS.get(player.getUUID());
        if (draft != null && draft.day == day) {
            return draft;
        }
        draft = new ActivityDraft();
        draft.day = day;
        ActivityDay act = data.activityDay(day);
        if (act != null) {
            draft.title = act.title == null ? "" : act.title;
            draft.note = act.note == null ? "" : act.note;
            draft.color = act.color;
            draft.hour = act.hour;
            draft.offline = act.forOffline;
            draft.catchUp = act.catchUp;
            draft.moneySpec = act.resolveMoneySpec();
            for (ItemStack stack : act.rewardStacks()) {
                draft.items.add(stack.copy());
            }
        }
        ACTIVITY_DRAFTS.put(player.getUUID(), draft);
        return draft;
    }

    /** 把草稿推给该管理员（只有他自己能收到；别的玩家看到的仍是正式日程）。 */
    private static void sendActivityDraft(ServerPlayer player) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "activity_draft");
        ActivityDraft draft = ACTIVITY_DRAFTS.get(player.getUUID());
        if (draft == null) {
            tag.putLong("day", -1L);
        } else {
            tag.putLong("day", draft.day);
            tag.putString("title", draft.title);
            tag.putString("note", draft.note);
            draft.moneySpec.writeToParent(tag, "moneySpec", "money");
            tag.putBoolean("forOffline", draft.offline);
            tag.putBoolean("catchUp", draft.catchUp);
            tag.putString("item", MailTemplates.itemsText(draft.items));
            tag.putString("itemIds", rewardIdsText(draft.items));
            tag.putInt("color", draft.color);
            tag.putInt("hour", draft.hour);
        }
        ClientboundShopSyncPayload.send(player, tag);
    }

    /** 奖励列表内容是否相同（用于判断要不要清空「已领过」记录）。 */
    private static boolean sameRewards(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!ItemStack.matches(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** 打开日程编辑：建一份草稿（从正式日程复制）并推回客户端。 */
    private static void adminActivityDraftOpen(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        long day = tag.getLong("day");
        if (day <= 0L) {
            day = realDay();
        }
        // 重新打开 = 从头开始编辑：丢掉上一次没收尾的草稿。
        ACTIVITY_DRAFTS.remove(player.getUUID());
        draftOf(data(player.server), player, day);
        sendActivityDraft(player);
    }

    /** 日程奖励界面「保存」：只改草稿里的发放规则，**不发布**。 */
    private static void adminActivityDraftSet(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        long day = tag.getLong("day");
        if (day <= 0L) {
            day = realDay();
        }
        ActivityDraft draft = draftOf(data(player.server), player, day);
        if (tag.contains("hour")) {
            draft.hour = parseHour(tag.getString("hour"));
        }
        if (tag.contains("offline")) {
            String raw = tag.getString("offline").trim();
            draft.offline = "true".equalsIgnoreCase(raw) || "1".equals(raw);
        }
        if (tag.contains("catchUp")) {
            draft.catchUp = !"false".equalsIgnoreCase(tag.getString("catchUp").trim());
        }
        // 标题 / 公告正文 / 颜色也记进草稿：日程编辑界面在来回切界面时会被重建，
        // 输入框要能从草稿回填，刚填的内容才不会丢。
        if (tag.contains("title")) {
            draft.title = tag.getString("title").trim();
        }
        if (tag.contains("note")) {
            draft.note = tag.getString("note").trim();
        }
        if (tag.contains("color")) {
            draft.color = parseColor(tag.getString("color"));
        }
        sendActivityDraft(player);
    }

    /** 编辑界面「取消」：丢掉草稿，正式日程保持不变。 */
    private static void adminActivityDraftDiscard(ServerPlayer player) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        ACTIVITY_DRAFTS.remove(player.getUUID());
        sendActivityDraft(player);
    }

    /**
     * 管理员：打开某天的「日程奖励」界面。
     * <p>
     * 这里**直接复用物品发放的容器**（{@link GrantMenu}：3×9 真实物品槽）。
     * 打开时把该日程**当前的奖励物品预填进槽位**，管理员看到的就是现配置，直接增删即可；
     * 关闭时只退还「管理员自己加进去的那部分」，预填部分不会被退成实物（见
     * {@link #adminActivityRewardCancel}），所以既不会丢东西也不会复制物品。
     */
    private static void adminActivityRewardOpen(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        long day = tag.getLong("day");
        if (day <= 0L) {
            day = realDay();
        }
        // 从**草稿**预填（草稿不存在时 draftOf 会从正式日程复制一份），编辑过程不碰正式日程。
        ActivityDraft draft = draftOf(data(player.server), player, day);
        List<ItemStack> prefill = new ArrayList<>();
        for (ItemStack stack : draft.items) {
            if (prefill.size() >= GrantMenu.GRANT_SLOTS) {
                break;
            }
            prefill.add(stack.copy());
        }
        while (prefill.size() < GrantMenu.GRANT_SLOTS) {
            prefill.add(ItemStack.EMPTY);
        }
        SimpleContainer container = new SimpleContainer(GrantMenu.GRANT_SLOTS);
        for (int i = 0; i < GrantMenu.GRANT_SLOTS; i++) {
            container.setItem(i, prefill.get(i).copy());
        }
        REWARD_EDIT.put(player.getUUID(), new RewardEdit(day, prefill));
        // 标题由客户端自绘（面板是深色的，原版标题色在暗底上看不清），这里传空标题。
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new GrantMenu(
                        cn.autoforged.module_shop.menu.ModMenus.SCHEDULE_REWARD.get(), id, inventory,
                        container),
                Component.empty()));
    }

    /** 管理员：保存「日程奖励」界面里的物品与金额到该日程。 */
    private static void adminActivityRewardSave(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        RewardEdit edit = REWARD_EDIT.remove(player.getUUID());
        long day = edit != null ? edit.day() : tag.getLong("day");
        if (day <= 0L) {
            day = realDay();
        }
        if (!(player.containerMenu instanceof GrantMenu menu)) {
            notifyPlayer(player, Component.translatable("msg.module_shop.grant_no_menu")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        Container container = menu.grant();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                items.add(stack.copy());
            }
        }
        PriceSpec moneySpec = parseSpecField(player, tag, "moneySpec", "money", true);
        if (moneySpec == null) {
            return;
        }
        if (moneySpec.supportsStar() && moneySpec.starPrice > Config.maxBalanceLimit()) {
            moneySpec = moneySpec.withStarPrice(Config.maxBalanceLimit());
        }
        ShopSavedData data = data(player.server);
        synchronized (LOCK) {
            ActivityDraft draft = draftOf(data, player, day);
            if (draft.items.isEmpty() && items.isEmpty() && moneySpec.isZero()) {
                // 既没有物品也没有金额/实体货币：不要凭空建出一个空日程。
                clearContainer(container);
                notifyPlayer(player, Component.translatable("msg.module_shop.grant_nothing")
                        .withStyle(ChatFormatting.RED));
                sendActivityDraft(player);
                return;
            }
            // 先清空槽位再记录：物品已经记进草稿，绝不能再被 removed() 退还给管理员（否则等于复制物品）。
            clearContainer(container);
            // 只写草稿：正式日程要等编辑界面点「保存」才更新（否则一进奖励界面玩家就看到了）。
            draft.items.clear();
            draft.items.addAll(items);
            draft.moneySpec = moneySpec;
            sendActivityDraft(player);
        }
        String rewardLabel = moneySpec.mode == CurrencyMode.STAR_ONLY
                ? moneySpec.starPrice + currencyName(player.server)
                : CurrencySettlement.formatSpec(moneySpec, player.server);
        notifyPlayer(player, Component.translatable("msg.module_shop.activity_reward_set",
                LocalDate.ofEpochDay(day).toString(), items.size(), rewardLabel)
                .withStyle(ChatFormatting.GREEN));
    }

    /**
     * 管理员：取消「日程奖励」编辑。
     * <p>
     * 只把**管理员自己放进去的那部分**退还给背包（预填的旧奖励原样留在日程里）：
     * 槽位内容与打开时一致 → 不退还；只是数量变多 → 退还多出来的部分；换成别的东西 → 退还新的那件。
     */
    private static void adminActivityRewardCancel(ServerPlayer player) {
        if (!canEditAdmin(player)) {
            return;
        }
        RewardEdit edit = REWARD_EDIT.remove(player.getUUID());
        if (!(player.containerMenu instanceof GrantMenu menu)) {
            return;
        }
        Container container = menu.grant();
        List<ItemStack> prefill = edit == null ? List.of() : edit.prefill();
        synchronized (LOCK) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack current = container.getItem(i);
                if (current.isEmpty()) {
                    continue;
                }
                ItemStack original = i < prefill.size() ? prefill.get(i) : ItemStack.EMPTY;
                container.setItem(i, ItemStack.EMPTY);
                if (!original.isEmpty() && ItemStack.matches(current, original)) {
                    // 原封不动：属于日程里的旧奖励，不退还。
                    continue;
                }
                if (!original.isEmpty() && current.getItem() == original.getItem()
                        && current.getCount() > original.getCount()) {
                    // 同一种物品只是加量：只退还多加的那部分。
                    giveBack(player, current.copyWithCount(current.getCount() - original.getCount()));
                    continue;
                }
                giveBack(player, current.copy());
            }
        }
    }

    /** 把槽位全部清空（避免容器关闭时的“退还未领取物品”逻辑把已入库的物品又发给管理员）。 */
    private static void clearContainer(Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            container.setItem(i, ItemStack.EMPTY);
        }
    }

    private static boolean loggedBalanceCap;

    /**
     * 启动后第一次下发快照时，把生效的余额上限打一行日志。
     * <p>
     * 「余额后面一直显示（已满）」几乎都是配置里的上限太小（比如想写 0 表示不限，
     * 结果被旧的范围下限纠正成 1）。打这一行日志，管理员看一眼控制台就知道该改哪里。
     */
    private static void logBalanceCapOnce() {
        if (loggedBalanceCap) {
            return;
        }
        loggedBalanceCap = true;
        long limit = Config.maxBalanceLimit();
        org.slf4j.LoggerFactory.getLogger("module_shop").info(
                "[模块商店] 余额上限 maxBalance = {}（0 或负数 = 不限；过小会导致余额很快显示「已满」）",
                limit == Long.MAX_VALUE ? "不限" : Long.toString(limit));
    }

    // =========================================================== 兑换码奖励草稿（第八轮：按钮 + 物品发放式界面）
    /**
     * 正在编辑「兑换码奖励」的管理员 → 打开容器时的原始槽位内容。
     * 取消时只退还「管理员自己加进去的那部分」，草稿里的既有物品不会被退成实物。
     */
    private record RedeemRewardEdit(List<ItemStack> prefill) {
    }

    /** 兑换码奖励草稿：管理员在「奖励配置」界面里配好的物品 / 星币 / 指定玩家。 */
    private record RedeemDraft(List<ItemStack> items, PriceSpec moneySpec, List<UUID> players) {
    }

    private static final Map<UUID, RedeemRewardEdit> REDEEM_REWARD_EDIT =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, RedeemDraft> REDEEM_DRAFT = new java.util.concurrent.ConcurrentHashMap<>();
    /** 这次打开奖励配置界面是在**改哪个兑换码**（空 = 正在配新兑换码的草稿）。 */
    private static final Map<UUID, String> REDEEM_EDIT_TARGET = new java.util.concurrent.ConcurrentHashMap<>();

    private static RedeemDraft redeemDraft(ServerPlayer player) {
        return REDEEM_DRAFT.getOrDefault(player.getUUID(), new RedeemDraft(List.of(), PriceSpec.ZERO, List.of()));
    }

    /** 管理员：放弃本次兑换码编辑（关闭编辑器时调用）——草稿丢掉，兑换码本身一点没改。 */
    private static void adminRedeemDraftDiscard(ServerPlayer player) {
        if (!canEditAdmin(player)) {
            return;
        }
        REDEEM_DRAFT.remove(player.getUUID());
        REDEEM_EDIT_TARGET.remove(player.getUUID());
        sendRedeemDraft(player);
    }

    /** 把草稿推给客户端：生成器显示「物品 / 货币 / 玩家」摘要，编辑界面据此回填。 */
    private static void sendRedeemDraft(ServerPlayer player) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        RedeemDraft draft = redeemDraft(player);
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "redeem_draft");
        tag.putString("items", MailTemplates.itemsText(draft.items()));
        (draft.moneySpec() == null ? PriceSpec.ZERO : draft.moneySpec()).writeToParent(tag, "moneySpec", "money");
        ListTag names = new ListTag();
        for (UUID id : draft.players()) {
            names.add(StringTag.valueOf(playerName(player.server, id)));
        }
        tag.put("players", names);
        ClientboundShopSyncPayload.send(player, tag);
    }

    /** UUID → 显示名（在线优先，其次历史账号，都没有时显示短 UUID）。 */
    private static String playerName(MinecraftServer server, UUID id) {
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (online.getUUID().equals(id)) {
                return online.getGameProfile().getName();
            }
        }
        PlayerAccount account = data(server).accounts().get(id);
        if (account != null && account.name != null && !account.name.isEmpty()) {
            return account.name;
        }
        return id.toString().substring(0, 8);
    }

    /**
     * 管理员：打开「兑换码奖励配置」界面。
     * <p>
     * 与日程奖励一样**复用物品发放的容器**（3×9 真实物品槽）：打开时把当前草稿物品预填进槽位，
     * 管理员看到的/改的就是现配置；关闭时只退还他自己加进去的那部分。
     */
    private static void adminRedeemRewardOpen(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        // 玩家列表与草稿都先推一次，保证界面一打开就有数据（不依赖网络时序）。
        GrantManager.sendTargets(player);
        // 带 code = 改这个已有兑换码（预填它的物品/货币/玩家列表）；不带 = 配新兑换码的草稿。
        String target = tag.getString("code").trim().toUpperCase(java.util.Locale.ROOT);
        List<ItemStack> source;
        if (!target.isEmpty()) {
            RedeemCode existing = data(player.server).redeemCodes.get(target);
            if (existing == null) {
                notifyPlayer(player, Component.translatable("msg.module_shop.redeem.notfound")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            REDEEM_EDIT_TARGET.put(player.getUUID(), target);
            source = new ArrayList<>(existing.items);
            // 草稿先镜像这个码**当前**的奖励：之后在奖励界面里的改动只改草稿，
            // 点编辑界面的「保存」才落到兑换码上（和「生成」是同一套语义，不会改一半就生效）。
            PriceSpec existingSpec = existing.resolveMoneySpec();
            REDEEM_DRAFT.put(player.getUUID(), new RedeemDraft(
                    new ArrayList<>(existing.items), existingSpec,
                    existing.global ? new ArrayList<>() : new ArrayList<>(existing.players)));
            // 全体可兑换的码不推玩家名单（界面上"一个都没勾"就是全体玩家）。
            sendRedeemDraftFor(player, existing.items, existingSpec,
                    existing.global ? new ArrayList<>() : new ArrayList<>(existing.players));
        } else {
            REDEEM_EDIT_TARGET.remove(player.getUUID());
            RedeemDraft draft = redeemDraft(player);
            source = draft.items();
            sendRedeemDraft(player);
        }
        List<ItemStack> prefill = new ArrayList<>();
        for (ItemStack stack : source) {
            if (prefill.size() >= GrantMenu.GRANT_SLOTS) {
                break;
            }
            prefill.add(stack.copy());
        }
        while (prefill.size() < GrantMenu.GRANT_SLOTS) {
            prefill.add(ItemStack.EMPTY);
        }
        SimpleContainer container = new SimpleContainer(GrantMenu.GRANT_SLOTS);
        for (int i = 0; i < GrantMenu.GRANT_SLOTS; i++) {
            container.setItem(i, prefill.get(i).copy());
        }
        REDEEM_REWARD_EDIT.put(player.getUUID(), new RedeemRewardEdit(prefill));
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new GrantMenu(
                        cn.autoforged.module_shop.menu.ModMenus.REDEEM_REWARD.get(), id, inventory,
                        container),
                Component.empty()));
    }

    /** 把「某个兑换码当前的内容」推给客户端（编辑界面回填用）。 */
    private static void sendRedeemDraftFor(ServerPlayer player, List<ItemStack> items, PriceSpec moneySpec,
            List<UUID> players) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "redeem_draft");
        tag.putString("items", MailTemplates.itemsText(items));
        (moneySpec == null ? PriceSpec.ZERO : moneySpec).writeToParent(tag, "moneySpec", "money");
        ListTag names = new ListTag();
        for (UUID id : players) {
            names.add(StringTag.valueOf(playerName(player.server, id)));
        }
        tag.put("players", names);
        ClientboundShopSyncPayload.send(player, tag);
    }

    /**
     * 管理员：保存「兑换码奖励配置」界面里的物品 + 货币（星币/实体币） + 玩家。
     */
    private static void adminRedeemRewardSave(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        REDEEM_REWARD_EDIT.remove(player.getUUID());
        String target = REDEEM_EDIT_TARGET.getOrDefault(player.getUUID(), "");
        if (!(player.containerMenu instanceof GrantMenu menu)) {
            notifyPlayer(player, Component.translatable("msg.module_shop.grant_no_menu")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        PriceSpec moneySpec = parseSpecField(player, tag, "moneySpec", "money", true);
        if (moneySpec == null) {
            return;
        }
        if (moneySpec.supportsStar() && moneySpec.starPrice > Config.maxBalanceLimit()) {
            moneySpec = moneySpec.withStarPrice(Config.maxBalanceLimit());
        }
        Container container = menu.grant();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) {
                items.add(stack.copy());
            }
        }
        // 先清空槽位再落库：物品已经写进数据，绝不能再被 removed() 退还给管理员（否则等于复制物品）。
        clearContainer(container);
        List<UUID> players = new ArrayList<>();
        ListTag targets = tag.getList("targets", 8);
        for (int i = 0; i < targets.size(); i++) {
            try {
                players.add(UUID.fromString(targets.getString(i)));
            } catch (IllegalArgumentException ignored) {
                // 非法 UUID 直接跳过，服务端以自己解析出来的列表为准。
            }
        }
        // 两种情况都只写**草稿**：新码等「生成」、改已有码等「保存」才真正生效。
        REDEEM_DRAFT.put(player.getUUID(), new RedeemDraft(items, moneySpec, players));
        if (!target.isEmpty()) {
            sendRedeemList(player);
        }
        Component scope = players.isEmpty()
                ? Component.translatable("msg.module_shop.redeem_all_players")
                : Component.literal(Integer.toString(players.size()));
        String rewardLabel = moneySpec.mode == CurrencyMode.STAR_ONLY
                ? moneySpec.starPrice + currencyName(player.server)
                : CurrencySettlement.formatSpec(moneySpec, player.server);
        notifyPlayer(player, Component.translatable("msg.module_shop.redeem_reward_set",
                items.size(), rewardLabel, scope)
                .withStyle(ChatFormatting.GREEN));
        sendRedeemDraft(player);
    }

    /** 管理员：取消「兑换码奖励配置」（只退还自己加进去的物品，草稿/兑换码保持原样）。 */
    private static void adminRedeemRewardCancel(ServerPlayer player) {
        if (!canEditAdmin(player)) {
            return;
        }
        RedeemRewardEdit edit = REDEEM_REWARD_EDIT.remove(player.getUUID());
        if (!(player.containerMenu instanceof GrantMenu menu)) {
            return;
        }
        Container container = menu.grant();
        List<ItemStack> prefill = edit == null ? List.of() : edit.prefill();
        synchronized (LOCK) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack current = container.getItem(i);
                if (current.isEmpty()) {
                    continue;
                }
                ItemStack original = i < prefill.size() ? prefill.get(i) : ItemStack.EMPTY;
                container.setItem(i, ItemStack.EMPTY);
                if (!original.isEmpty() && ItemStack.matches(current, original)) {
                    continue;
                }
                if (!original.isEmpty() && current.getItem() == original.getItem()
                        && current.getCount() > original.getCount()) {
                    giveBack(player, current.copyWithCount(current.getCount() - original.getCount()));
                    continue;
                }
                giveBack(player, current.copy());
            }
        }
        // 编辑已有兑换码时把它的当前内容推回去（界面回填），否则推草稿。
        String target = REDEEM_EDIT_TARGET.getOrDefault(player.getUUID(), "");
        RedeemCode rc = target.isEmpty() ? null : data(player.server).redeemCodes.get(target);
        if (rc != null) {
            sendRedeemDraftFor(player, rc.items, rc.resolveMoneySpec(),
                    rc.global ? new ArrayList<>() : new ArrayList<>(rc.players));
        } else {
            sendRedeemDraft(player);
        }
    }

    /** 退还物品：优先进背包，装不下的原样掉在脚下。 */
    private static void giveBack(ServerPlayer player, ItemStack stack) {
        ItemStack remaining = stack.copy();
        player.getInventory().add(remaining);
        if (!remaining.isEmpty()) {
            player.drop(remaining, false);
        }
    }

    /**
     * 管理员：修改当前模块（每日商店 / 每日收购）的刷新参数。
     * fields：weights=「传说,史诗,稀有,普通」权重，guarantee=「稀有,史诗,传说」保底，slots=条目/牌数上限。
     */
    private static void adminShopGenSet(ServerPlayer player, CompoundTag tag) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        ShopSavedData data = data(player.server);
        boolean shop = isShopMode(player, tag);
        int[] weights = shop ? data.shopWeights : data.buybackWeights;
        int[] guarantee = shop ? data.shopGuarantee : data.buybackGuarantee;
        int[] newWeights = parseInts(tag.getString("weights"), weights);
        int[] newGuarantee = parseInts(tag.getString("guarantee"), guarantee);
        System.arraycopy(newWeights, 0, weights, 0, weights.length);
        System.arraycopy(newGuarantee, 0, guarantee, 0, guarantee.length);
        // 条目数量：允许 0 ~ 64（0 = 当天不生成；留空则保持原值）。
        String slotsText = tag.getString("slots").trim();
        if (!slotsText.isEmpty()) {
            int slots = Math.max(0, Math.min(MAX_DAILY_SLOTS, parseInt(slotsText)));
            if (shop) {
                data.shopSlotCount = slots;
            } else {
                data.buybackSlotCount = slots;
            }
        }
        data.setDirty();
        // 全服所有玩家（含离线）重抽当前正在设置的那个列表（改商店参数不动收购，反之亦然）。
        rerollDailyForAll(player.server, data, shop, !shop);
        sendSnapshot(player, "admin");
        notifyPlayer(player, Component.translatable("msg.module_shop.gen_set_ok")
                .withStyle(ChatFormatting.GREEN));
    }

    /** 管理员一键全服重抽当前模块（每日商店 / 每日收购），覆盖在线与离线玩家。 */
    private static void adminRerollDaily(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        ShopSavedData data = data(player.server);
        boolean shop = isShopMode(player, tag);
        rerollDailyForAll(player.server, data, shop, !shop);
        sendSnapshot(player, "admin");
        Component modName = Component.translatable(shop
                ? "gui.module_shop.admin_shop" : "gui.module_shop.admin_buyback");
        notifyPlayer(player, Component.translatableWithFallback(
                "msg.module_shop.admin_rerolled_all",
                "已按当前商品池为全服玩家重新抽取今日「%s」清单", modName)
                .withStyle(ChatFormatting.GREEN));
    }

    /** 解析「5,15,30,50」形式的整数数组（留空或不足时保留原值）。 */
    private static int[] parseInts(String text, int[] fallback) {
        int[] out = fallback.clone();
        if (text == null || text.isBlank()) {
            return out;
        }
        String[] parts = text.split(",");
        for (int i = 0; i < parts.length && i < out.length; i++) {
            String piece = parts[i].trim();
            if (piece.isEmpty()) {
                continue;
            }
            try {
                out[i] = Math.max(0, Integer.parseInt(piece));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** 解析 #RRGGBB / RRGGBB 颜色（失败返回 0 = 默认橙色）。 */
    private static int parseColor(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        String raw = text.trim();
        if (raw.startsWith("#")) {
            raw = raw.substring(1);
        }
        try {
            return 0xFF000000 | ((int) Long.parseLong(raw, 16) & 0xFFFFFF);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 解析定时小时（0-23；其它值 = -1 不定时）。 */
    private static int parseHour(String text) {
        int hour = parseInt(text);
        return hour >= 0 && hour <= 23 ? hour : -1;
    }

    /** 取玩家名（离线也能取；取不到返回 "-"），供邮件模板的 {player} 占位符使用。 */
    public static String nameOf(MinecraftServer server, @Nullable UUID id) {
        if (id == null) {
            return "-";
        }
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        PlayerAccount account = data(server).peek(id);
        return account == null || account.name == null || account.name.isEmpty() ? "-" : account.name;
    }

    /** 解析「物品ID x数量」文本（如 minecraft:diamond x2）；解析失败返回空栈。 */
    private static ItemStack parseItemText(String text) {
        if (text == null || text.isBlank()) {
            return ItemStack.EMPTY;
        }
        String raw = text.trim();
        int count = 1;
        int idx = raw.lastIndexOf('x');
        if (idx > 0) {
            String num = raw.substring(idx + 1).trim();
            String head = raw.substring(0, idx).trim();
            if (!num.isEmpty() && head.contains(":")) {
                int parsed = parseInt(num);
                if (parsed > 0) {
                    count = Math.min(64, parsed);
                    raw = head;
                }
            }
        }
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(raw);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item, count);
    }

    /** 登录/签到时的活动日奖励发放（含物品与离线补发判断）。 */
    public static void grantActivityDay(ServerPlayer player) {
        synchronized (LOCK) {
            ShopSavedData data = data(player.server);
            long today = realDay();
            ActivityDay act = data.activityDay(today);
            if (act == null) {
                return;
            }
            List<ItemStack> dayItems = act.rewardStacks();
            // 星币与物品**分别**判断是否已发：管理员中途改了奖励、清空了「已领物品」记录后，
            // 玩家下次登录仍能拿到新的物品奖励（不会因为当天星币已发就被整段跳过）。
            boolean moneyDone = act.granted.contains(player.getUUID());
            boolean itemsDone = dayItems.isEmpty() || act.itemsGrantedTo(player.getUUID());
            if (moneyDone && itemsDone) {
                return;
            }
            // 定时发放：未到设定的时间点一律等待；超过设定时间点仅当开启 catchUp 或 forOffline 时补发。
            if (!ShopExpireSupport.canGrantActivityDayAtHour(act.hour, java.time.LocalTime.now().getHour(),
                    act.catchUp, act.forOffline)) {
                return;
            }
            PriceSpec moneySpec = act.resolveMoneySpec();
            if (moneySpec.isZero() && dayItems.isEmpty()) {
                return;
            }
            PlayerAccount account = account(player);
            long added = 0L;
            long physPaid = 0L;
            String physTier = "";
            if (!moneyDone && !moneySpec.isZero()) {
                var payRes = CurrencySettlement.payoutSpec(
                        player.server, player.getUUID(), account, data, moneySpec, 1, null,
                        SOURCE_ADMIN, act.title, ItemStack.EMPTY, false, false);
                added = payRes.starCredited();
                physPaid = payRes.physPaidCount();
                physTier = payRes.physTierKey();
                act.granted.add(player.getUUID());
            }
            if (!itemsDone) {
                // 活动日物品走邮箱发放（背包满也不会丢）；第八轮起支持一次配置多件物品，并且合并进同一封邮件。
                act.rewardsGranted.add(player.getUUID());
                addMailDraft(player.server, player.getUUID(), MailTemplates.build(
                                MailTemplates.Template.ACTIVITY_REWARD, MailTemplates.map(
                                        "player", player.getGameProfile().getName(),
                                        "activityName", act.title))
                        .source(SOURCE_ADMIN)
                        .addItems(dayItems));
            }
            data.setDirty();
            if (added > 0L || physPaid > 0L) {
                String grantedText = CurrencySettlement.formatSpec(
                        physPaid > 0L && added > 0L ? PriceSpec.both(added, physTier, physPaid)
                                : (physPaid > 0L ? PriceSpec.phys(physTier, physPaid) : PriceSpec.star(added)),
                        player.server);
                TransactionLog.record(player.server, player.getUUID(), TransactionLog.SIGN_IN, "活动日",
                        added, account.balance,
                        physPaid > 0L ? act.title + " [" + grantedText + "]" : act.title);
                if (physPaid > 0L) {
                    notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.activity_granted_spec",
                            "活动日「%s」奖励已发放：%s", act.title, grantedText).withStyle(ChatFormatting.GOLD));
                } else {
                    notifyPlayer(player, Component.translatable("msg.module_shop.activity_granted",
                            act.title, added).withStyle(ChatFormatting.GOLD));
                }
            }
            sendSnapshot(player, "signin");
        }
    }

    /** 定时发放：日程到了设定的现实时间点时，给当时在线的玩家发物品/星币（每人只发一次）。 */
    private static void tickActivitySchedules(MinecraftServer server) {
        ShopSavedData data = data(server);
        ActivityDay act = data.activityDay(realDay());
        if (act == null || act.hour < 0) {
            return;
        }
        if (java.time.LocalTime.now().getHour() != act.hour) {
            return;
        }
        boolean hasMoney = !act.resolveMoneySpec().isZero();
        boolean hasItems = !act.rewardStacks().isEmpty();
        if (!hasMoney && !hasItems) {
            return;
        }
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            UUID id = online.getUUID();
            boolean needMoney = hasMoney && !act.granted.contains(id);
            boolean needItems = hasItems && !act.itemsGrantedTo(id);
            if (needMoney || needItems) {
                grantActivityDay(online);
            }
        }
    }

    /** 每日检查（挂在服务器 tick 上）：活动日定时在线发放 + 到点后给配置了离线补发的活动日补发。 */
    public static void tickActivityDayOffline(MinecraftServer server) {
        tickActivitySchedules(server);
        ShopSavedData data = data(server);
        long today = realDay();
        ActivityDay act = data.activityDay(today);
        if (act == null || !act.forOffline) {
            return;
        }
        int nowHour = java.time.LocalTime.now().getHour();
        if (!ShopExpireSupport.canGrantActivityOfflineAtHour(act.hour, nowHour)) {
            return;
        }
        int sig = 31 * act.resolveMoneySpec().hashCode() + act.rewardStacks().size();
        sig = 31 * sig + act.hour;
        sig = 31 * sig + data.accounts().size();
        if (data.lastOfflineGrantDay == today && data.lastOfflineGrantSig == sig) {
            return;
        }
        data.lastOfflineGrantDay = today;
        data.lastOfflineGrantSig = sig;
        grantActivityDayOffline(server);
    }

    /** 服务器启动/每日检查：给配置了「补发给离线玩家」的活动日补发。 */
    public static void grantActivityDayOffline(MinecraftServer server) {
        synchronized (LOCK) {
            ShopSavedData data = data(server);
            long today = realDay();
            ActivityDay act = data.activityDay(today);
            if (act == null || !act.forOffline) {
                return;
            }
            if (!ShopExpireSupport.canGrantActivityOfflineAtHour(act.hour, java.time.LocalTime.now().getHour())) {
                return;
            }
            PriceSpec moneySpec = act.resolveMoneySpec();
            if (moneySpec.isZero() && act.rewardStacks().isEmpty()) {
                return;
            }
            boolean changed = false;
            List<ItemStack> dayItems = act.rewardStacks();
            for (Map.Entry<UUID, PlayerAccount> entry : data.accounts().entrySet()) {
                // 星币与物品分别判断：当天星币已补发过、但奖励物品后来被改过时，仍然要把物品补上。
                boolean moneyDone = act.granted.contains(entry.getKey());
                boolean itemsDone = dayItems.isEmpty() || act.itemsGrantedTo(entry.getKey());
                if (moneyDone && itemsDone) {
                    continue;
                }
                long added = 0L;
                long physPaid = 0L;
                String physTier = "";
                if (!moneyDone && !moneySpec.isZero()) {
                    var res = CurrencySettlement.payoutSpec(
                            server, entry.getKey(), entry.getValue(), data, moneySpec, 1, null,
                            SOURCE_ADMIN, act.title, ItemStack.EMPTY, false, false);
                    added = res.starCredited();
                    physPaid = res.physPaidCount();
                    physTier = res.physTierKey();
                    act.granted.add(entry.getKey());
                }
                if (!itemsDone) {
                    act.rewardsGranted.add(entry.getKey());
                    addMailDraft(server, entry.getKey(), MailTemplates.build(
                                    MailTemplates.Template.ACTIVITY_REWARD, MailTemplates.map(
                                            "player", entry.getValue().name,
                                            "activityName", act.title))
                            .source(SOURCE_ADMIN)
                            .addItems(dayItems));
                }
                changed = true;
                if (added > 0L || physPaid > 0L) {
                    String note = physPaid > 0L
                            ? act.title + " [" + CurrencySettlement.formatSpec(
                                    added > 0L ? PriceSpec.both(added, physTier, physPaid) : PriceSpec.phys(physTier, physPaid),
                                    server) + "]"
                            : act.title;
                    TransactionLog.record(server, entry.getKey(), TransactionLog.SIGN_IN, "活动日（离线补发）",
                            added, entry.getValue().balance, note);
                }
            }
            if (changed) {
                data.setDirty();
            }
        }
    }

    // =========================================================== 收购全清奖励

    /**
     * 收购全清奖励的实际金额：管理员覆盖值优先；关闭时返回 0，
     * 于是界面不再显示提示行、maybeGrantBuybackBonus 里的「奖励为 0 就跳过」逻辑也不会发钱。
     */
    public static PriceSpec buybackBonusSpec(ShopSavedData data) {
        if (data == null || !data.buybackBonusEnabled) {
            return PriceSpec.ZERO;
        }
        PriceSpec resolved = data.resolveBuybackBonusSpec();
        return resolved == null ? PriceSpec.ZERO : resolved;
    }

    public static long buybackBonusAmount(ShopSavedData data) {
        PriceSpec spec = buybackBonusSpec(data);
        return spec.supportsStar() ? spec.starPrice : 0L;
    }

    /** 管理员：开关收购全清奖励。 */
    private static void adminBuybackBonusToggle(ServerPlayer player) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        ShopSavedData data = data(player.server);
        data.buybackBonusEnabled = !data.buybackBonusEnabled;
        data.setDirty();
        sendSnapshot(player, "buyback");
        notifyPlayer(player, Component.translatable(data.buybackBonusEnabled
                ? "msg.module_shop.buyback_bonus_on" : "msg.module_shop.buyback_bonus_off")
                .withStyle(ChatFormatting.GREEN));
    }

    /** 管理员：修改收购全清奖励（支持星币 / 实体货币 / 双通道；0 = 关闭奖励）。 */
    private static void adminBuybackBonusSet(ServerPlayer player, CompoundTag tag) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        PriceSpec spec = parseSpecField(player, tag, "priceSpec", "amount", true);
        if (spec == null) {
            return;
        }
        ShopSavedData data = data(player.server);
        data.setBuybackBonusSpec(spec);
        data.setDirty();
        sendSnapshot(player, "buyback");
        if (spec.isZero() || spec.mode == CurrencyMode.STAR_ONLY) {
            notifyPlayer(player, Component.translatable("msg.module_shop.buyback_bonus_set",
                    spec.isZero() ? 0L : spec.starPrice).withStyle(ChatFormatting.GREEN));
        } else {
            notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.buyback_bonus_set_spec",
                    "已设置每日收购全清奖励：%s", CurrencySettlement.formatSpec(spec, player.server))
                    .withStyle(ChatFormatting.GREEN));
        }
    }

    // =========================================================== 兑换码（第七轮）

    /**
     * 玩家兑换：统一「去首尾空格 + 转大写」后校验（存在 / 未过期 / 白名单 / 每人限领 / 总上限），
     * 通过则按模板发奖到邮箱并累计使用次数；无论成功失败都回传 {@code kind=redeem_result} 给客户端展示。
     */
    private static void redeemCode(ServerPlayer player, String rawCode) {
        ShopSavedData data = data(player.server);
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase(java.util.Locale.ROOT);
        long now = System.currentTimeMillis();
        if (code.isEmpty()) {
            sendRedeemResult(player, false, code, "empty");
            return;
        }
        RedeemCode entry = data.redeemCodes.get(code);
        if (entry == null) {
            sendRedeemResult(player, false, code, "notfound");
            return;
        }
        if (entry.expired(now)) {
            sendRedeemResult(player, false, code, "expired");
            return;
        }
        if (!entry.global && !entry.players.contains(player.getUUID())) {
            sendRedeemResult(player, false, code, "scope");
            return;
        }
        if (entry.remainingFor(player.getUUID()) <= 0) {
            sendRedeemResult(player, false, code, "used");
            return;
        }
        if (entry.exhausted()) {
            sendRedeemResult(player, false, code, "exhausted");
            return;
        }
        if (!entry.hasReward()) {
            sendRedeemResult(player, false, code, "noreward");
            return;
        }
        PriceSpec rewardSpec = entry.resolveMoneySpec();
        long starReward = rewardSpec.supportsStar() ? rewardSpec.starPrice : 0L;
        List<ItemStack> physCoinStacks = (rewardSpec.mode == CurrencyMode.PHYS_ONLY || rewardSpec.mode == CurrencyMode.BOTH)
                ? PhysicalCurrencyEngine.buildPhysicalTierStacks(
                        player.level().registryAccess(), rewardSpec.physTierKey, rewardSpec.physCount)
                : List.of();
        MailTemplates.DraftMail draft = MailTemplates.build(MailTemplates.Template.REDEEM_OK,
                        MailTemplates.map("player", player.getGameProfile().getName(), "code", code))
                .source(SOURCE_ADMIN)
                .money(starReward)
                .addItems(entry.items);
        if (!physCoinStacks.isEmpty()) {
            draft.addItems(physCoinStacks);
        }
        if (entry.title != null && !entry.title.isEmpty()) {
            draft.title(entry.title);
        }
        if (entry.note != null && !entry.note.isEmpty()) {
            draft.content(draft.content() + "\n" + entry.note);
        }
        addMailDraft(player.server, player.getUUID(), draft);
        entry.usedCount++;
        entry.usedBy.merge(player.getUUID(), 1, Integer::sum);
        data.setDirty();
        String physDetail = physCoinStacks.isEmpty() ? ""
                : " [" + CurrencySettlement.formatSpec(PriceSpec.phys(rewardSpec.physTierKey, rewardSpec.physCount),
                        player.server) + "]";
        TransactionLog.record(player.server, player.getUUID(), TransactionLog.REDEEM, "兑换码",
                starReward, account(player).balance, code + " " + MailTemplates.itemsText(entry.items) + physDetail);
        sendRedeemResult(player, true, code, "ok");
        sendSnapshot(player, "mail");
    }

    /** 管理员生成兑换码：码必填且不可重复、必须有奖励；指定玩家模式会把名字解析成 UUID。 */
    private static void adminRedeemCreate(ServerPlayer player, CompoundTag tag) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        ShopSavedData data = data(player.server);
        String code = tag.getString("code").trim().toUpperCase(java.util.Locale.ROOT);
        if (code.isEmpty()) {
            sendRedeemCreated(player, false, code, "empty");
            return;
        }
        if (data.redeemCodes.containsKey(code)) {
            sendRedeemCreated(player, false, code, "exists");
            return;
        }
        int days = parseInt(tag.getString("days"));
        RedeemCode rc = new RedeemCode();
        rc.code = code;
        rc.expireAt = days <= 0 ? 0L : System.currentTimeMillis() + days * 86_400_000L;
        rc.perPlayerLimit = Math.max(0, parseInt(tag.getString("perPlayer")));
        rc.totalLimit = Math.max(0, parseInt(tag.getString("total")));
        rc.title = tag.getString("title").trim();
        rc.note = tag.getString("note").trim();
        // 第八轮：奖励内容（物品 / 星币 / 玩家）来自「奖励配置」界面写下的草稿；
        // 兼容旧客户端：没有草稿时仍按报文里的文本字段解析。
        RedeemDraft draft = REDEEM_DRAFT.get(player.getUUID());
        if (draft != null) {
            // 草稿里没勾人 = 全体玩家可兑换（默认）；勾了人才是"指定玩家"。
            rc.global = draft.players().isEmpty();
            rc.setMoneySpec(draft.moneySpec());
            rc.items.addAll(draft.items());
            if (!rc.global) {
                rc.players.addAll(draft.players());
            }
        } else {
            rc.global = !"false".equalsIgnoreCase(tag.getString("global").trim());
            PriceSpec fallbackSpec = parseSpecField(player, tag, "moneySpec", "money", true);
            rc.setMoneySpec(fallbackSpec == null ? PriceSpec.ZERO : fallbackSpec);
            rc.items.addAll(parseItemList(tag.getString("items")));
            if (!rc.global) {
                for (String piece : tag.getString("players").split(",")) {
                    String name = piece.trim();
                    if (name.isEmpty()) {
                        continue;
                    }
                    UUID id = resolvePlayerByName(data, player.server, name);
                    if (id != null) {
                        rc.players.add(id);
                    }
                }
            }
        }
        rc.creator = player.getGameProfile().getName();
        rc.createdAt = System.currentTimeMillis();
        if (!rc.global && rc.players.isEmpty()) {
            sendRedeemCreated(player, false, code, "noplayer");
            return;
        }
        if (!rc.hasReward()) {
            sendRedeemCreated(player, false, code, "noreward");
            return;
        }
        data.redeemCodes.put(code, rc);
        data.setDirty();
        // 生成成功后清掉草稿：下一个兑换码重新配置，避免不小心把同一次奖励连发两遍。
        REDEEM_DRAFT.remove(player.getUUID());
        sendRedeemDraft(player);
        sendRedeemCreated(player, true, code, "ok");
        notifyPlayer(player, Component.translatable("msg.module_shop.redeem_created", code)
                .withStyle(ChatFormatting.GREEN));
    }

    /** 管理员：把某个兑换码当前的奖励内容推给客户端（编辑界面摘要显示用，不打开任何容器）。 */
    private static void adminRedeemPreview(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        String code = tag.getString("code").trim().toUpperCase(java.util.Locale.ROOT);
        RedeemCode rc = data(player.server).redeemCodes.get(code);
        if (rc == null) {
            return;
        }
        // 正在编辑这个码：界面上的摘要显示**草稿**（还没保存的改动），别被"当前存档值"盖回去。
        if (code.equals(REDEEM_EDIT_TARGET.get(player.getUUID())) && REDEEM_DRAFT.containsKey(player.getUUID())) {
            sendRedeemDraft(player);
            return;
        }
        sendRedeemDraftFor(player, rc.items, rc.resolveMoneySpec(), new ArrayList<>(rc.players));
    }

    /** 管理员：下发已生成的兑换码列表（客户端列表界面展示与删除）。 */
    private static void sendRedeemList(ServerPlayer player) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        ShopSavedData data = data(player.server);
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "redeem_list");
        ListTag list = new ListTag();
        for (RedeemCode rc : data.redeemCodes.values()) {
            CompoundTag c = new CompoundTag();
            c.putString("code", rc.code);
            c.putLong("expireAt", rc.expireAt);
            c.putBoolean("global", rc.global);
            c.putInt("players", rc.players.size());
            c.putInt("used", rc.usedCount);
            c.putInt("total", rc.totalLimit);
            c.putInt("perPlayer", rc.perPlayerLimit);
            rc.resolveMoneySpec().writeToParent(c, "moneySpec", "money");
            c.putString("items", MailTemplates.itemsText(rc.items));
            c.putString("creator", rc.creator);
            // 第八轮：编辑界面要回填标题/备注/指定玩家，列表页也直接显示它们。
            c.putString("title", rc.title);
            c.putString("note", rc.note);
            ListTag ids = new ListTag();
            for (UUID id : rc.players) {
                ids.add(StringTag.valueOf(id.toString()));
            }
            c.put("playerIds", ids);
            list.add(c);
        }
        tag.put("codes", list);
        ClientboundShopSyncPayload.send(player, tag);
    }

    /** 管理员：修改已有兑换码的基础设置（时效 / 限领 / 总上限 / 标题 / 备注）。 */
    private static void adminRedeemUpdate(ServerPlayer player, CompoundTag tag) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        ShopSavedData data = data(player.server);
        String code = tag.getString("code").trim().toUpperCase(java.util.Locale.ROOT);
        RedeemCode rc = data.redeemCodes.get(code);
        if (rc == null) {
            notifyPlayer(player, Component.translatable("msg.module_shop.redeem.notfound")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        int days = parseInt(tag.getString("days"));
        synchronized (LOCK) {
            rc.expireAt = days <= 0 ? 0L : System.currentTimeMillis() + days * 86_400_000L;
            rc.perPlayerLimit = Math.max(0, parseInt(tag.getString("perPlayer")));
            rc.totalLimit = Math.max(0, parseInt(tag.getString("total")));
            rc.title = tag.getString("title").trim();
            rc.note = tag.getString("note").trim();
            // 奖励内容走草稿：只有「这次编辑确实打开过奖励界面（草稿属于这个码）」才覆盖，
            // 否则保持兑换码原来的奖励 —— 不能因为改个标题就把奖励清空。
            String editTarget = REDEEM_EDIT_TARGET.remove(player.getUUID());
            RedeemDraft draft = REDEEM_DRAFT.remove(player.getUUID());
            if (draft != null && code.equals(editTarget)) {
                rc.items.clear();
                rc.items.addAll(draft.items());
                rc.setMoneySpec(draft.moneySpec());
                rc.global = draft.players().isEmpty();
                rc.players.clear();
                if (!rc.global) {
                    rc.players.addAll(draft.players());
                }
                // usedBy / usedCount 保持原样：已经领过的玩家就这么算领过了，不再补发。
            }
            data.setDirty();
        }
        notifyPlayer(player, Component.translatable("msg.module_shop.redeem_updated", code)
                .withStyle(ChatFormatting.GREEN));
        sendRedeemList(player);
    }

    /** 管理员：查看某个兑换码「谁领过、各领了几次」。 */
    private static void sendRedeemClaimed(ServerPlayer player, CompoundTag tag) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        ShopSavedData data = data(player.server);
        String code = tag.getString("code").trim().toUpperCase(java.util.Locale.ROOT);
        RedeemCode rc = data.redeemCodes.get(code);
        CompoundTag out = new CompoundTag();
        out.putString("kind", "redeem_claimed");
        out.putString("code", code);
        ListTag list = new ListTag();
        if (rc != null) {
            for (Map.Entry<UUID, Integer> entry : rc.usedBy.entrySet()) {
                CompoundTag c = new CompoundTag();
                c.putString("name", playerName(player.server, entry.getKey()));
                c.putInt("count", entry.getValue());
                list.add(c);
            }
        }
        out.put("entries", list);
        ClientboundShopSyncPayload.send(player, out);
    }

    /** 管理员删除兑换码。 */
    private static void adminRedeemDelete(ServerPlayer player, CompoundTag tag) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        ShopSavedData data = data(player.server);
        String code = tag.getString("code").trim().toUpperCase(java.util.Locale.ROOT);
        if (data.redeemCodes.remove(code) != null) {
            data.setDirty();
            notifyPlayer(player, Component.translatable("msg.module_shop.redeem_deleted", code)
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    /** 把「名字」解析成 UUID（先在线玩家，再历史账号）。 */
    private static UUID resolvePlayerByName(ShopSavedData data, MinecraftServer server, String name) {
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (online.getGameProfile().getName().equalsIgnoreCase(name)) {
                return online.getUUID();
            }
        }
        for (Map.Entry<UUID, PlayerAccount> entry : data.accounts().entrySet()) {
            if (name.equalsIgnoreCase(entry.getValue().name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** 解析多件物品：「minecraft:diamond x2,minecraft:gold_ingot x5」。 */
    private static java.util.List<ItemStack> parseItemList(String text) {
        java.util.List<ItemStack> list = new java.util.ArrayList<>();
        if (text == null || text.isBlank()) {
            return list;
        }
        for (String piece : text.split(",")) {
            ItemStack stack = parseItemText(piece);
            if (!stack.isEmpty()) {
                list.add(stack);
            }
        }
        return list;
    }

    /** 回传「生成兑换码」结果。 */
    private static void sendRedeemCreated(ServerPlayer player, boolean ok, String code, String reason) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "redeem_created");
        tag.putBoolean("ok", ok);
        tag.putString("code", code);
        tag.putString("reason", reason);
        ClientboundShopSyncPayload.send(player, tag);
    }

    /** 回传兑换结果（客户端据 ok/reason 显示红字抖动或成功展示）。 */
    private static void sendRedeemResult(ServerPlayer player, boolean ok, String code, String reason) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "redeem_result");
        tag.putBoolean("ok", ok);
        tag.putString("code", code);
        tag.putString("reason", reason);
        ClientboundShopSyncPayload.send(player, tag);
    }

    // =========================================================== 邮箱空间拦截（第七轮）

    /** 单封邮件里待领的物品（非物品类或已领取返回空列表）。 */
    private static List<ItemStack> pendingItems(ServerPlayer player, String mailId) {
        MailEntry entry = findMail(account(player), mailId);
        if (entry != null && entry.type == MailType.ITEM && !entry.isClaimed) {
            return entry.itemStacks();
        }
        return new ArrayList<>();
    }

    /** 邮箱里所有待领物品（「全部领取」用）。 */
    private static List<ItemStack> pendingItemsAll(ServerPlayer player) {
        List<ItemStack> list = new ArrayList<>();
        for (MailEntry entry : account(player).mailbox) {
            if (entry.type == MailType.ITEM && !entry.isClaimed) {
                list.addAll(entry.itemStacks());
            }
        }
        return list;
    }

    /**
     * 背包空间校验：按「同物品同组件先填未满堆叠，再占新槽位」模拟，
     * 装不下时给玩家「背包物品已达限额提醒」并返回 false（调用方必须中止领取）。
     */
    private static boolean fitsInBagOrWarn(ServerPlayer player, List<ItemStack> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return true;
        }
        Inventory inv = player.getInventory();
        int usable = Math.min(36, inv.getContainerSize());
        List<ItemStack> simulated = new ArrayList<>();
        for (int i = 0; i < usable; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                simulated.add(stack.copy());
            }
        }
        int freeSlots = usable - simulated.size();
        int needSlots = 0;
        for (ItemStack stack : incoming) {
            int remaining = stack.getCount();
            int per = Math.max(1, stack.getMaxStackSize());
            for (ItemStack existing : simulated) {
                if (remaining <= 0) {
                    break;
                }
                if (!ItemStack.isSameItemSameTags(existing, stack)) {
                    continue;
                }
                int room = per - existing.getCount();
                if (room <= 0) {
                    continue;
                }
                int put = Math.min(room, remaining);
                existing.setCount(existing.getCount() + put);
                remaining -= put;
            }
            while (remaining > 0) {
                ItemStack fresh = stack.copy();
                int put = Math.min(per, remaining);
                fresh.setCount(put);
                simulated.add(fresh);
                needSlots++;
                remaining -= put;
            }
        }
        if (needSlots <= freeSlots) {
            return true;
        }
        notifyPlayer(player, Component.translatable("msg.module_shop.bag_full_block", needSlots, freeSlots)
                .withStyle(ChatFormatting.RED));
        return false;
    }

    // =========================================================== 工具

    /**
     * 安全入账：只加“余额还能装得下”的部分，返回实际到账金额（饱和加法，永不溢出）。
     * <p>
     * 原先各处直接写 {@code Math.min(max, balance + amount)}：收款方到顶时差额被静默销毁，
     * 而且 amount 极大时还会先溢出成负数。所有入账都应走这里，差额由调用方退回或转为邮件。
     */
    public static long credit(PlayerAccount account, long amount, long max) {
        // 事件：余额变化（业务成功后发布；监听器异常不影响本次入账）
        long beforeBalance = account.balance;
        long creditedNow = creditInner(account, amount, max);
        if (creditedNow > 0L && account.uuidForEvents != null) {
            cn.autoforged.module_shop.api.event.ApiEvents.post(
                    new cn.autoforged.module_shop.api.event.BalanceChangedEvent(
                            account.uuidForEvents, beforeBalance, account.balance, creditedNow, "credit"));
        }
        return creditedNow;
    }

    /** 原来的 credit 逻辑（不含事件），供 {@link #credit} 复用。 */
    private static long creditInner(PlayerAccount account, long amount, long max) {
        long room = Math.max(0L, max - account.balance);
        long add = Math.min(Math.max(0L, amount), room);
        account.balance += add;
        return add;
    }

    /**
     * **统一扣款入口**：所有"玩家付出金币"的业务都应改用它（而不是直接 {@code account.balance -= x}）。
     *
     * <p>它会：①余额不足时**不扣**并返回 -1（调用方必须检查）；②扣完发布 {@code BalanceChangedEvent}；
     * ③不做持久化（调用方照旧负责 {@code setDirty()}）。
     *
     * @return 实际扣除金额；余额不足返回 -1
     */
    public static long debit(PlayerAccount account, long amount, String reason) {
        if (account == null || amount <= 0L) {
            return amount <= 0L ? 0L : -1L;
        }
        if (account.balance < amount) {
            return -1L;
        }
        long before = account.balance;
        account.balance -= amount;
        if (account.uuidForEvents != null) {
            cn.autoforged.module_shop.api.event.ApiEvents.post(
                    new cn.autoforged.module_shop.api.event.BalanceChangedEvent(
                            account.uuidForEvents, before, account.balance, -amount,
                            reason == null ? "debit" : reason));
        }
        return amount;
    }

    // =========================================================== 交易流水（管理员查看）

    /**
     * 取某玩家的交易流水渲染行（管理员用）。找不到该玩家时返回空列表。
     * 玩家名支持在线名与账户表里的历史名（含离线），也支持直接写 UUID。
     */
    public static List<Component> historyLines(MinecraftServer server, String targetName, int page) {
        GrantManager.ResolveResult resolved = GrantManager.resolveTargets(server, targetName);
        if (resolved.ids().isEmpty()) {
            return List.of();
        }
        UUID id = resolved.ids().get(0);
        PlayerAccount account = data(server).account(id);
        String display = account.name == null || account.name.isEmpty() ? targetName : account.name;
        return TransactionLog.format(account, display, page);
    }

    /** 管理员命令：直接发放星币（单目标，供 /shop wallet give 使用）。 */
    public static void adminGive(ServerPlayer target, long amount) {
        PlayerAccount account = account(target);
        long credited = credit(account, amount, Config.maxBalanceLimit());
        data(target.server).setDirty();
        notifyPlayer(target, Component.translatable("msg.module_shop.admin_gave", credited, account.balance)
                .withStyle(ChatFormatting.GREEN));
        TransactionLog.record(target.server, target.getUUID(), TransactionLog.ADMIN_MONEY, "管理员",
                credited, account.balance, "");
    }

    /**
     * 管理员命令：**把余额直接设为 amount**（0 = 清空），用于纠正账户而不是「加一笔」。
     * <p>
     * 与 {@link #adminGive} 的区别：那个只会往上加（受上限约束），这里是无条件覆写，
     * 因此也用于把误发的余额收回来。会写一条流水（差额），并在目标在线时立刻推送新快照，
     * 避免玩家界面还显示旧余额。
     */
    public static void adminSetBalance(ServerPlayer target, long amount, String operator) {
        long max = Config.maxBalanceLimit();
        long value = Math.max(0L, Math.min(amount, max));
        PlayerAccount account = account(target);
        long before = account.balance;
        long delta = value - before;
        account.balance = value;
        data(target.server).setDirty();
        TransactionLog.record(target.server, target.getUUID(), TransactionLog.ADMIN_MONEY,
                "管理员设置余额", delta, value, operator == null ? "" : operator);
        notifyPlayer(target, Component.translatable("msg.module_shop.admin_set_balance",
                value + currencyName(target.server)).withStyle(ChatFormatting.GREEN));
        // 在线目标立即刷新界面上的余额（快照里带 balance，客户端顶栏会跟着闪一下）。
        sendSnapshot(target, null, true);
    }

    /** 按名字（在线 / 历史账号 / UUID）设置余额；找不到返回 false。 */
    public static boolean adminSetBalanceByName(MinecraftServer server, String targetName, long amount,
            String operator) {
        GrantManager.ResolveResult resolved = GrantManager.resolveTargets(server, targetName);
        if (resolved.ids().isEmpty()) {
            return false;
        }
        UUID id = resolved.ids().get(0);
        ShopSavedData data = data(server);
        PlayerAccount account = data.account(id);
        long max = Config.maxBalanceLimit();
        long value = Math.max(0L, Math.min(amount, max));
        long delta = value - account.balance;
        account.balance = value;
        data.setDirty();
        TransactionLog.record(server, id, TransactionLog.ADMIN_MONEY, "管理员设置余额", delta, value,
                operator == null ? "" : operator);
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            notifyPlayer(online, Component.translatable("msg.module_shop.admin_set_balance",
                    value + currencyName(server)).withStyle(ChatFormatting.GREEN));
            sendSnapshot(online, null, true);
        }
        return true;
    }

    public static long balanceOf(ServerPlayer player) {
        return account(player).balance;
    }

    /** 只读余额（找不到账号返回 0，供流水记录使用）。 */
    private static long balanceOf(ShopSavedData data, UUID id) {
        PlayerAccount account = id == null ? null : data.peek(id);
        return account == null ? 0L : account.balance;
    }

    public static void onLogin(ServerPlayer player) {
        LAST_SENT_MALL_HASH.remove(player.getUUID());
        // 签到（需求 7）：开启「自动签到」时登录即签到。
        autoSignInIfNeeded(player);
        // 活动日奖励（需求 7）：当天登录即可领取管理员配置的星币。
        grantActivityDay(player);
        // 同 IP 多账号登录限制已按需求**移除**：同一个 IP 可以正常登录多个账号，不再踢人。
        PlayerAccount account = account(player);
        // 「右键重命名模块」已删除：把旧存档里改过的模块名一次性清掉，回到语言文件里的默认名。
        clearLegacyModuleNames(data(player.server));
        loadBrandDefaultIfBlank(data(player.server).sidebarLayout);
        // 需求 13：惰性刷新——登录时检查时间戳，过期才重新生成每日商店/收购，不做全局 tick 遍历。
        ensureDaily(player, account, data(player.server));
        // 需求 10：登录时把持久化的未读数量同步给客户端，红点不会因为重连丢失。
        NotificationManager.sendCounts(player, null);
        // 已领取/已查看的邮件仍保留在邮箱中，这里只统计未处理的邮件数量。
        long pendingMails = account.mailbox.stream().filter(MailEntry::pending).count();
        if (pendingMails > 0) {
            notifyPlayer(player, Component.translatable("msg.module_shop.mail_waiting", pendingMails)
                    .withStyle(ChatFormatting.GOLD));
        }
        // 出货箱：离线期间结算过 → 登录时补一条提示。
        if (account.shipping.pendingNotice) {
            account.shipping.pendingNotice = false;
            if (!account.shipping.lastPhysPayout.isEmpty()
                    && account.shipping.lastPayoutSummary != null
                    && !account.shipping.lastPayoutSummary.isBlank()) {
                notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.shipping_sold_offline_spec",
                        "离线期间出货箱已结算 %s 件物品，获得 %s",
                        account.shipping.lastCount, account.shipping.lastPayoutSummary)
                        .withStyle(ChatFormatting.GREEN));
            } else {
                notifyPlayer(player, Component.translatable("msg.module_shop.shipping_sold_offline",
                        account.shipping.lastCount, account.shipping.lastPayout)
                        .withStyle(ChatFormatting.GREEN));
            }
        }
    }

    /** 需求 12：管理员指定某玩家刷新每日商店货物与收购商品种类（离线玩家下次登录生效）。 */
    public static void refreshDaily(ServerPlayer target) {
        PlayerAccount account = account(target);
        account.dailyShopDay = Long.MIN_VALUE;
        account.dailyBuybackDay = Long.MIN_VALUE;
        account.dailyFlipped.clear();
        data(target.server).setDirty();
        ensureDaily(target, account, data(target.server));
        sendSnapshot(target, "daily", true);
    }

    /**
     * 只刷新其中一个列表：{@code shop=true} 只重抽**每日商店**，{@code false} 只重抽**每日收购**。
     *
     * <p>以前「商店设置 → 刷新参数」保存后调的是 {@link #refreshDaily(ServerPlayer)}，
     * 那个会把两个列表都标记成"今天没生成"，于是改商店的参数、收购也跟着重抽（反之亦然）。
     */
    public static void refreshDaily(ServerPlayer target, boolean shop) {
        PlayerAccount account = account(target);
        if (shop) {
            account.dailyShopDay = Long.MIN_VALUE;
            account.dailyFlipped.clear();
        } else {
            account.dailyBuybackDay = Long.MIN_VALUE;
        }
        data(target.server).setDirty();
        ensureDaily(target, account, data(target.server));
        sendSnapshot(target, "daily", true);
    }

    public static void onLogout(ServerPlayer player) {
        LAST_SENT_MALL_HASH.remove(player.getUUID());
        returnPending(player);
        TradeManager.onLogout(player);
        cn.autoforged.module_shop.shop.auction.AuctionService.onLogout(player);
        AdminAuthService.onLogout(player.getUUID());
        DEPOSIT_CONTEXT.remove(player.getUUID());
        ADMIN_EDIT_TYPE.remove(player.getUUID());
        ADMIN_TEMPLATE.remove(player.getUUID());
        BATCH_TEMPLATE.remove(player.getUUID());
        // 编辑到一半掉线：草稿直接丢掉（正式数据本来就没被改过，安全）。
        LAYOUT_DRAFTS.remove(player.getUUID());
        ACTIVITY_DRAFTS.remove(player.getUUID());
        REDEEM_DRAFT.remove(player.getUUID());
        REDEEM_EDIT_TARGET.remove(player.getUUID());
        REDEEM_REWARD_EDIT.remove(player.getUUID());
    }

    // =========================================================== 侧栏布局（拖拽排序）

    /**
     * 管理员：保存侧栏页签的**顺序列表**（面板里是固定网格，坐标由客户端按顺序算，所以只存顺序）。
     * 服务端只接受合法 id 并去重，防止客户端塞垃圾进存档。
     */
    /**
     * 「编辑布局」草稿：拖拽 / 页签改名 / 主标题都只写这里，
     * **只有点「完成」**才写进正式存档并广播给所有在线玩家。
     *
     * <p>为什么必须是真的草稿：如果只是「先存进存档、推迟广播」，别人下一条快照
     * （买东西、开界面、登录）就会把半成品布局读走 —— 等于还是提前同步了。
     *
     * <p>货币名称**不在**草稿里：它是独立设置，确认即保存并广播，避免草稿被丢弃时回滚。
     */
    private static final class LayoutDraft {
        final SidebarLayout layout;

        LayoutDraft(SidebarLayout layout) {
            this.layout = layout;
        }
    }

    private static final Map<UUID, LayoutDraft> LAYOUT_DRAFTS = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean layoutEditing(ServerPlayer player) {
        return player != null && LAYOUT_DRAFTS.containsKey(player.getUUID());
    }

    /** 该管理员当前应该看到的布局：编辑中看草稿，否则看正式存档。 */
    private static SidebarLayout layoutFor(ShopSavedData data, UUID playerId) {
        LayoutDraft draft = playerId == null ? null : LAYOUT_DRAFTS.get(playerId);
        return draft == null ? data.sidebarLayout : draft.layout;
    }

    /** 取（或从正式存档复制一份）布局草稿。 */
    private static LayoutDraft layoutDraft(ShopSavedData data, ServerPlayer player) {
        return LAYOUT_DRAFTS.computeIfAbsent(player.getUUID(),
                key -> new LayoutDraft(SidebarLayout.load(data.sidebarLayout.save())));
    }

    /** 该玩家看到的货币名：永远取正式存档（货币名不进布局草稿）。 */
    private static String currencyFor(ShopSavedData data, ServerPlayer player) {
        return currencyName(player == null ? null : player.server);
    }

    /** 布局类改动推送：编辑中只推给操作者本人（他立刻看到预览），否则广播给所有在线玩家。 */
    private static void pushLayoutTo(ServerPlayer player) {
        if (player == null) {
            return;
        }
        if (layoutEditing(player)) {
            sendSnapshot(player, null, true);
        } else {
            broadcastSidebarLayout(player.server);
        }
    }

    /** 管理员：开始编辑布局（拉一份草稿，之后只改草稿）。 */
    private static void adminLayoutBegin(ServerPlayer player) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        ShopSavedData data = data(player.server);
        LAYOUT_DRAFTS.remove(player.getUUID());
        layoutDraft(data, player);
    }

    /** 管理员：点「完成」→ 把草稿一次性写进正式存档并广播给所有人。 */
    private static void adminLayoutCommit(ServerPlayer player) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        LayoutDraft draft = LAYOUT_DRAFTS.remove(player.getUUID());
        if (draft == null) {
            return;
        }
        ShopSavedData data = data(player.server);
        SidebarLayout src = draft.layout;
        data.sidebarLayout.setOrder(new ArrayList<>(src.order()));
        data.sidebarLayout.setHidden(new ArrayList<>(src.hidden()));
        for (String id : new ArrayList<>(data.sidebarLayout.names().keySet())) {
            data.sidebarLayout.setName(id, null);
        }
        for (Map.Entry<String, String> entry : src.names().entrySet()) {
            data.sidebarLayout.setName(entry.getKey(), entry.getValue());
        }
        data.sidebarLayout.setShopTitle(src.shopTitle());
        data.sidebarLayout.setBrandIconId(src.brandIconId());
        data.setDirty();
        saveBrandDefault(data.sidebarLayout);
        broadcastSidebarLayout(player.server);
        notifyPlayer(player, Component.translatable("msg.module_shop.sidebar_saved")
                .withStyle(ChatFormatting.GREEN));
    }

    private static void adminSidebarOrder(ServerPlayer player, CompoundTag tag) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        ListTag list = tag.getList("order", 8);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < list.size() && ids.size() < 32; i++) {
            String id = list.getString(i).trim();
            if (!id.isEmpty() && !ids.contains(id)) {
                ids.add(id);
            }
        }
        // 被移除的模块（不在 order 里的那些）：只有报文字段显式包含 hidden 时才覆盖，防止拖拽排序误清空。
        boolean hasHidden = tag.contains("hidden", Tag.TAG_LIST);
        ListTag hiddenList = tag.getList("hidden", 8);
        List<String> hidden = new ArrayList<>();
        for (int i = 0; i < hiddenList.size() && hidden.size() < 32; i++) {
            String id = hiddenList.getString(i).trim();
            if (!id.isEmpty() && !hidden.contains(id) && !ids.contains(id)) {
                hidden.add(id);
            }
        }
        if (ids.isEmpty()) {
            // 至少保留一个模块：否则侧栏空了，管理员就没有入口再进「商店设置」加回来。
            notifyPlayer(player, Component.translatable("msg.module_shop.sidebar_need_one")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        ShopSavedData data = data(player.server);
        boolean editing = layoutEditing(player);
        // 编辑中只改草稿：正式存档要等「完成」才动，否则别人下一条快照就看到了。
        SidebarLayout target = editing ? layoutDraft(data, player).layout : data.sidebarLayout;
        target.setOrder(ids);
        if (hasHidden) {
            target.setHidden(hidden);
        }
        if (!editing) {
            data.setDirty();
        }
        pushLayoutTo(player);
        if (!editing) {
            notifyPlayer(player, Component.translatable("msg.module_shop.sidebar_saved")
                    .withStyle(ChatFormatting.GREEN));
        }
    }

    /**
     * 模块显示名已**不再支持重命名**：只保留左上角「模块商店」主标题可以改。
     *
     * <p>以前「右键页签/标题改模块名」那套已经删除，所以这里把旧存档里改过的名字一次性清掉，
     * 让所有模块回到语言文件里的默认名（否则改过名的模块永远改不回来）。
     */
    static void clearLegacyModuleNames(ShopSavedData data) {
        if (data.sidebarLayout.names().isEmpty()) {
            return;
        }
        for (String id : new ArrayList<>(data.sidebarLayout.names().keySet())) {
            data.sidebarLayout.setName(id, null);
        }
        data.setDirty();
    }

    /**
     * 管理员：**摊位自动下架时间**（商店设置 → 摊位）。
     *
     * <p>{@code expire_mode}: {@code permanent} / {@code game} / {@code real}；
     * {@code expire_amount}: game 按**游戏天数**、real 按**现实小时**（0 或 permanent = 永不自动下架）。
     */
    private static void adminStallExpire(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        String prevMode = Config.stallExpireMode();
        int prevAmount = Config.stallExpireAmount();
        String mode = ShopExpireSupport.normalizeExpireMode(tag.getString("expire_mode"));
        Integer parsed = ShopExpireSupport.parseExpireAmount(mode, tag.getString("expire_amount"), prevAmount);
        if (parsed == null) {
            notifyPlayer(player, Component.translatable("msg.module_shop.stall_expire_bad_amount")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        int amount = parsed;
        if (!saveExpireConfig("stall", Config.INSTANCE.stallExpireMode, Config.INSTANCE.stallExpireAmount,
                "stallExpireMode", mode, "stallExpireAmount", amount)) {
            notifyPlayer(player, Component.translatableWithFallback("msg.module_shop.config_save_failed",
                    "配置写入磁盘失败，已恢复原设置，请检查配置文件权限").withStyle(ChatFormatting.RED));
            return;
        }
        if (ShopExpireSupport.shouldResetExpireTimestamps(prevMode, prevAmount, mode, amount)) {
            synchronized (LOCK) {
                ShopSavedData data = data(player.server);
                long nowGame = player.server.overworld().getGameTime();
                long nowReal = System.currentTimeMillis();
                boolean resetAny = false;
                for (PlayerAccount account : data.accounts().values()) {
                    for (StallEntry entry : account.stall) {
                        entry.listedGame = nowGame;
                        entry.listedReal = nowReal;
                        resetAny = true;
                    }
                }
                if (resetAny) {
                    data.setDirty();
                }
            }
        }
        Component desc = !ShopExpireSupport.isExpireActive(mode, amount)
                ? Component.translatable("gui.module_shop.choice.expire_mode.permanent")
                : Component.translatable("gui.module_shop.stall.expire_value",
                        Component.translatable("gui.module_shop.choice.expire_mode." + mode), amount);
        notifyPlayer(player, Component.translatable("msg.module_shop.stall_expire_saved", desc)
                .withStyle(ChatFormatting.GREEN));
        // 让所有玩家立刻拿到新的下架方式（摊位页会显示每个商品的剩余时间）。
        broadcastSnapshots(player.server);
    }

    /** 持久化摊位 / 求购下架配置：跳过无变化字段，异常或 Forge 1.20.1 Autosave+FileWatcher 竞态时自动走 TOML 原子回写兜底，彻底失败时回滚内存值。 */
    private static boolean saveExpireConfig(String section,
            net.minecraftforge.common.ForgeConfigSpec.ConfigValue<String> modeVal,
            net.minecraftforge.common.ForgeConfigSpec.IntValue amountVal,
            String modeKey, String mode, String amountKey, int amount) {
        synchronized (LOCK) {
            String prevMode = modeVal.get();
            int prevAmount = amountVal.get();
            boolean saved = false;
            try {
                if (!mode.equals(prevMode)) {
                    modeVal.set(mode);
                }
                if (amount != prevAmount) {
                    amountVal.set(amount);
                }
                Config.SPEC.save();
                saved = true;
            } catch (Throwable ex) {
                org.slf4j.LoggerFactory.getLogger("module_shop")
                        .warn("[模块商店] 保存 [{}] 下架配置失败，尝试原子回写 TOML：{}", section, ex.toString());
            }
            boolean memoryMatches = mode.equals(modeVal.get()) && amount == amountVal.get();
            if (!saved || !memoryMatches) {
                Map<String, String> patch = new java.util.LinkedHashMap<>();
                patch.put(modeKey, "\"" + mode + "\"");
                patch.put(amountKey, Integer.toString(amount));
                Path cfgPath = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("module_shop-common.toml");
                if (ShopExpireSupport.writeTomlSectionAtomic(cfgPath, section, patch)) {
                    try {
                        if (!mode.equals(modeVal.get())) {
                            modeVal.set(mode);
                        }
                        if (amount != amountVal.get()) {
                            amountVal.set(amount);
                        }
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
                try {
                    modeVal.set(prevMode);
                    amountVal.set(prevAmount);
                } catch (Throwable ignored) {
                }
                return false;
            }
            return true;
        }
    }

    /** 管理员：把所有页签位置恢复默认（编辑中同样只改草稿，点「完成」才广播）。 */
    private static void adminSidebarReset(ServerPlayer player) {
        // 只有管理员（权限等级 2 且已解锁密码锁）能改商店设置：客户端隐藏按钮不算数，报文也必须校验。
        if (!canEditAdmin(player)) {
            return;
        }
        ShopSavedData data = data(player.server);
        boolean editing = layoutEditing(player);
        SidebarLayout target = editing ? layoutDraft(data, player).layout : data.sidebarLayout;
        target.clear();
        if (!editing) {
            data.setDirty();
            saveBrandDefault(data.sidebarLayout);
        }
        pushLayoutTo(player);
        notifyPlayer(player, Component.translatable("msg.module_shop.sidebar_reset")
                .withStyle(ChatFormatting.YELLOW));
    }

    // =========================================================== 商店模板（委托至 TemplateTransferManager）

    /** 只有已解锁的管理员能碰模板。 */
    private static boolean requireAdmin(ServerPlayer player) {
        return requireUnlockedAdmin(player);
    }

    /** 打开「配置管理」界面：客户端自己 setScreen 在某些绘制路径下会被覆盖，保留服务端下发指令兜底。 */
    private static void cfgOpenUi(ServerPlayer player) {
        if (!requireAdmin(player)) {
            return;
        }
        CompoundTag out = new CompoundTag();
        out.putString("kind", "cfg_open_ui");
        cn.autoforged.module_shop.network.payload.ClientboundShopSyncPayload.send(player, out);
    }

    private static void adminShopTitleSet(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        ShopSavedData data = data(player.server);
        boolean editing = layoutEditing(player);
        SidebarLayout target = editing ? layoutDraft(data, player).layout : data.sidebarLayout;
        String raw = tag.getString("name");
        boolean truncated = SidebarLayout.sanitizeFull(raw).length() > SidebarLayout.MAX_TITLE;
        String previous = target.shopTitle();
        if (target.setShopTitle(raw)) {
            if (!editing) {
                data.setDirty();
                saveBrandDefault(data.sidebarLayout);
            }
            pushLayoutTo(player);
            String now = target.shopTitle();
            if (now == null) {
                notifyPlayer(player, Component.translatable("msg.module_shop.shop_title_reset")
                        .withStyle(ChatFormatting.YELLOW));
            } else {
                notifyPlayer(player, Component.translatable("msg.module_shop.shop_title_renamed", now)
                        .withStyle(ChatFormatting.GREEN));
            }
        } else if (truncated && previous != null) {
            notifyPlayer(player, Component.translatable("msg.module_shop.shop_title_too_long",
                    Integer.toString(SidebarLayout.MAX_TITLE)).withStyle(ChatFormatting.YELLOW));
        }
    }
    /** 货币名称最大长度。 */
    public static final int CURRENCY_NAME_MAX = 8;

    /**
     * 管理员：改**货币名称**（全服生效；空 = 恢复默认「星币」）。
     *
     * <p>这条设置**立刻保存并广播**，不参与「编辑布局」的草稿流程：
     * 草稿里的货币名一旦因为「没点完成就离开界面」被丢掉，就会出现
     * 「改成金币 → 改回星币 → 点发放物品 → 退出又变回金币」这种回滚。
     * 货币名只有这一条路径能改，别的任何操作都不会写它。
     */
    private static void adminCurrencySet(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        ShopSavedData data = data(player.server);
        String raw = tag.getString("name");
        String clean = SidebarLayout.sanitizeFull(raw);
        if (clean.length() > CURRENCY_NAME_MAX) {
            clean = clean.substring(0, CURRENCY_NAME_MAX);
        }
        String previous = data.shopCurrency == null ? "" : data.shopCurrency;
        if (clean.equals(previous)) {
            return;
        }
        data.shopCurrency = clean;
        data.setDirty();
        // 立刻让所有在线玩家换名字（货币名在快照里下发）。
        broadcastSnapshots(player.server);
        if (clean.isBlank()) {
            notifyPlayer(player, Component.translatable("msg.module_shop.currency_reset")
                    .withStyle(ChatFormatting.YELLOW));
        } else {
            notifyPlayer(player, Component.translatable("msg.module_shop.currency_renamed", clean)
                    .withStyle(ChatFormatting.GREEN));
        }
    }
    private static void broadcastSidebarLayout(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            sendSnapshot(online, null, true);
        }
    }

    // =========================================================== 账单（玩家自己的收支明细）
    /** 账单一次下发的最大条数（再多就用滚动看旧的）。 */
    public static final int BILL_MAX_ROWS = 60;

    /**
     * 下发玩家自己的账单：明细 + 今日 / 本月收支汇总。
     * <p>
     * 按天分组由客户端做（服务端只按时间倒序给原始明细），
     * 每条含时间 / 类型 / 对方 / 金额 / 变动后余额 / 备注。
     */
    public static void sendBill(ServerPlayer player) {
        PlayerAccount account = account(player);
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "bill");
        tag.putLong("balance", account.balance);
        tag.putInt("limit", Config.INSTANCE.transactionHistorySize.get());
        List<TransactionEntry> all = account.transactions;
        tag.putInt("size", all.size());
        // 今日 / 本月汇总（现实时间）。
        java.time.LocalDate today = java.time.LocalDate.now();
        long todayIn = 0L;
        long todayOut = 0L;
        long monthIn = 0L;
        long monthOut = 0L;
        for (TransactionEntry entry : all) {
            if (entry.amount == 0L || entry.time <= 0L) {
                continue;
            }
            java.time.LocalDate date = java.time.Instant.ofEpochMilli(entry.time)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            boolean sameMonth = date.getYear() == today.getYear() && date.getMonthValue() == today.getMonthValue();
            if (entry.amount > 0L) {
                if (sameMonth) {
                    monthIn += entry.amount;
                }
                if (date.equals(today)) {
                    todayIn += entry.amount;
                }
            } else {
                if (sameMonth) {
                    monthOut += -entry.amount;
                }
                if (date.equals(today)) {
                    todayOut += -entry.amount;
                }
            }
        }
        tag.putLong("todayIn", todayIn);
        tag.putLong("todayOut", todayOut);
        tag.putLong("monthIn", monthIn);
        tag.putLong("monthOut", monthOut);
        ListTag list = new ListTag();
        for (int i = all.size() - 1; i >= 0 && list.size() < BILL_MAX_ROWS; i--) {
            list.add(all.get(i).save());
        }
        tag.put("entries", list);
        ClientboundShopSyncPayload.send(player, tag);
    }

    /**
     * 记一笔外部账目（其它插件 / 模组扣的或加的钱）：只写账单，不动本模组余额。
     * 其它插件可以在控制台执行 {@code /shop wallet log <玩家> <金额> <事由>} 触发同一条路径。
     */
    public static boolean recordExternal(MinecraftServer server, String playerName, long amount, String reason,
            String source) {
        UUID id = resolvePlayerId(server, playerName);
        if (id == null || amount == 0L) {
            return false;
        }
        TransactionLog.recordExternal(server, id, amount, reason, source);
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            sendBill(online);
        }
        return true;
    }

    /** 按玩家名解析 UUID（在线优先，其次账户表里记录过的名字）。找不到返回 null。 */
    @Nullable
    private static UUID resolvePlayerId(MinecraftServer server, String name) {
        if (server == null || name == null || name.isBlank()) {
            return null;
        }
        String target = name.trim();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (online.getGameProfile().getName().equalsIgnoreCase(target)) {
                return online.getUUID();
            }
        }
        for (Map.Entry<UUID, PlayerAccount> entry : data(server).accounts().entrySet()) {
            String accountName = entry.getValue().name;
            if (accountName != null && accountName.equalsIgnoreCase(target)) {
                return entry.getKey();
            }
        }
        try {
            return UUID.fromString(target);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** 按物品最大堆叠拆成合法单叠列表，避免超堆叠（>=128 触发 1.20.1 byte 溢出吞物或受损装备非法堆叠）。 */
    public static List<ItemStack> splitStacks(ItemStack template, int totalCount) {
        List<ItemStack> out = new ArrayList<>();
        if (template == null || template.isEmpty() || totalCount <= 0) {
            return out;
        }
        int[] counts = ShopExpireSupport.splitStackCounts(totalCount, template.getMaxStackSize());
        for (int c : counts) {
            out.add(template.copyWithCount(c));
        }
        return out;
    }

    /** 将指定数量的同种物品按单叠上限拆份后发给在线玩家：能塞背包的进背包，放不下的合并进一封背包已满邮件。 */
    public static void addItemsOrMail(ServerPlayer player, ItemStack template, int totalCount, String source) {
        List<ItemStack> parts = splitStacks(template, totalCount);
        if (parts.isEmpty()) {
            return;
        }
        List<ItemStack> leftovers = new ArrayList<>();
        int leftoverTotal = 0;
        for (ItemStack part : parts) {
            player.getInventory().add(part);
            if (!part.isEmpty()) {
                leftovers.add(part);
                leftoverTotal += part.getCount();
            }
        }
        if (leftovers.isEmpty()) {
            notifyPlayer(player, Component.translatable("msg.module_shop.received",
                    template.getHoverName(), totalCount).withStyle(ChatFormatting.GREEN));
            return;
        }
        addMailDraft(player.server, player.getUUID(), MailTemplates.build(
                        MailTemplates.Template.BAG_FULL, MailTemplates.map(
                                "player", player.getGameProfile().getName(),
                                "item", MailTemplates.itemText(template, false) + "×" + leftoverTotal))
                .source(source)
                .addItems(leftovers));
    }

    public static void addItemOrMail(ServerPlayer player, ItemStack stack, String source) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        addItemsOrMail(player, stack, stack.getCount(), source);
    }

    public static void addMail(MinecraftServer server, @Nullable UUID owner, ItemStack stack, String source) {
        addMail(server, owner, stack, source, "");
    }

    /** 需求：带发送者与发送者 UUID 的物品类邮件。 */
    public static void addMail(MinecraftServer server, @Nullable UUID owner, ItemStack stack, String source, String sender) {
        if (owner == null || stack == null || stack.isEmpty()) {
            return;
        }
        List<ItemStack> parts = splitStacks(stack, stack.getCount());
        if (parts.isEmpty()) {
            return;
        }
        ShopSavedData data = data(server);
        PlayerAccount account = data.account(owner);
        ServerPlayer online = server.getPlayerList().getPlayer(owner);
        int max = Config.INSTANCE.maxMailboxSize.get();
        if (account.mailbox.size() >= max && !ensureMailRoom(server, account, online)) {
            // 邮箱已满且没有已读邮件可清理：按合法单叠优先塞背包，仍放不下的直接掉落在玩家脚下。
            if (online != null) {
                boolean dropped = false;
                for (ItemStack part : parts) {
                    online.getInventory().add(part);
                    if (!part.isEmpty()) {
                        online.drop(part, false);
                        dropped = true;
                    }
                }
                if (!dropped) {
                    notifyPlayer(online, Component.translatable("msg.module_shop.received",
                            stack.getHoverName(), stack.getCount()).withStyle(ChatFormatting.GREEN));
                } else {
                    notifyPlayer(online, Component.translatable("msg.module_shop.mailbox_full")
                            .withStyle(ChatFormatting.RED));
                }
                data.setDirty();
                return;
            }
            // 离线玩家无法掉落，为避免系统返还的物品彻底丢失，仍然保留在邮箱（超出上限仅限系统资产）。
        }
        MailEntry newEntry = MailEntry.item(parts.get(0), source, sender, null, currentDay(server.overworld()));
        for (int i = 1; i < parts.size(); i++) {
            newEntry.addStack(parts.get(i));
        }
        // 第七轮：全服物品发放（admin_grant）保留 30 天有效期；其余邮件默认永久。
        if ("admin_grant".equals(source)) {
            newEntry.expireAt = System.currentTimeMillis() + GRANT_EXPIRE_DAYS * 86_400_000L;
        }
        account.mailbox.add(newEntry);
        data.setDirty();
        // 需求 10：收到新物品（交易返还/管理员发放等）→ 邮箱提示红点。
        NotificationManager.notify(server, owner, NotificationType.MAIL);
        if (online != null) {
            notifyPlayer(online, Component.translatable("msg.module_shop.mail_received", stack.getHoverName())
                    .withStyle(ChatFormatting.GOLD));
            online.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0F, 1.5F);
        }
    }

    /**
     * 需求：发送一封金额类邮件（拍卖成交/摊位售出所得）。
     * 邮箱满时金额不能丢，直接进账并提示，避免玩家资产凭空消失。
     */
    public static void addMoneyMail(MinecraftServer server, @Nullable UUID owner, long amount, ItemStack display,
            String source, String sender, @Nullable UUID senderUUID) {
        if (owner == null || amount <= 0L) {
            return;
        }
        ShopSavedData data = data(server);
        PlayerAccount account = data.account(owner);
        ServerPlayer online = server.getPlayerList().getPlayer(owner);
        if (account.mailbox.size() >= Config.INSTANCE.maxMailboxSize.get()
                && !ensureMailRoom(server, account, online)) {
            // 邮箱满且没有已读邮件可清理：先把能装下的直接到账，余额也装不下的部分仍然留在邮箱，绝不销毁。
            long credited = credit(account, amount, Config.maxBalanceLimit());
            long rest = amount - credited;
            if (rest > 0L) {
                account.mailbox.add(MailEntry.money(rest, display, source, sender, senderUUID,
                        currentDay(server.overworld())));
                NotificationManager.notify(server, owner, NotificationType.MAIL);
            }
            data.setDirty();
            if (online != null) {
                notifyPlayer(online, Component.translatable("msg.module_shop.mailbox_full_money", credited)
                        .withStyle(ChatFormatting.GOLD));
            }
            return;
        }
        account.mailbox.add(MailEntry.money(amount, display, source, sender, senderUUID,
                currentDay(server.overworld())));
        data.setDirty();
        NotificationManager.notify(server, owner, NotificationType.MAIL);
        if (online != null) {
            notifyPlayer(online, Component.translatable("msg.module_shop.mail_received_money", amount)
                    .withStyle(ChatFormatting.GOLD));
            online.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1.0F, 1.5F);
        }
    }

    public static void notifyPlayer(ServerPlayer player, Component message) {
        player.displayClientMessage(message, false);
    }

    /** 需求 2：拍卖手续费 = 成交价 × 配置百分比（向上取整）。 */
    private static long auctionFee(long amount) {
        double percent = Config.INSTANCE.auctionFeePercent.get();
        return percent <= 0.0D ? 0L : (long) Math.ceil(amount * percent / 100.0D);
    }

    private static long parsePositive(String text) {
        try {
            long value = Long.parseLong(text.trim());
            return Math.max(0L, value);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * 当前货币名（下发到客户端显示）。
     * <p>
     * 旧版本的默认货币名是「金币」，老配置里会显式写着它，导致升级后仍显示金币；
     * 这里把「金币」与留空都当作旧默认值统一迁移成「星币」，服主自定义的其它名字原样返回。
     * 只影响显示名：配置键名、余额数值与对外接口都不变，ModuleShopVaultBridge / Vault /
     * 领地插件（Residence 等）的联动不受影响。
     */
    /**
     * 当前货币名（**全服权威**）：优先用存档里管理员改过的名字，没改过才用配置值。
     * <p>
     * 「编辑布局 → 右键余额改名」把新名字存进 {@code ShopSavedData.shopCurrency}，
     * 但显示全走快照里的 {@code currency / moneySuffix}。以前这里只读配置，
     * 于是改名保存成功、界面上却还是默认名 —— 凡是能拿到 server 的地方都要走这个重载。
     */
    public static String currencyName(@Nullable MinecraftServer server) {
        if (server != null) {
            String custom = data(server).shopCurrency;
            if (custom != null && !custom.isBlank()) {
                return custom.trim();
            }
        }
        return currencyName();
    }

    /**
     * 配置里的货币名（**不读存档**）。只给拿不到 server 上下文的场合兜底；
     * 有 server 时请用 {@link #currencyName(MinecraftServer)}，否则管理员改过的名字不会生效。
     */
    public static String currencyName() {
        String name = Config.INSTANCE.currencyName.get();
        if (name == null || name.isBlank() || "金币".equals(name.trim())) {
            return "星币";
        }
        return name;
    }

    /**
     * 解析“价格”：非正数、或超过余额上限（谁也付不起）一律返回 0 视为非法。
     * 之所以要加上界：价格会参与 price×count 运算，天文数字会让乘法溢出成负数，
     * 进而使“余额不足”判定失效、扣款变成加钱（刷钱漏洞）。
     */
    private static long parsePrice(String text) {
        long price = parsePositive(text);
        return price > Config.maxBalanceLimit() ? 0L : price;
    }

    /**
     * 管理员填价格时的统一校验：合法返回价格（&gt;0）；非法返回 0，并**当场说明是哪一种问题**。
     * <p>
     * 以前只有一句「价格无效」，管理员完全分不清是"没填 / 填了非数字"还是"超过了余额上限"
     * （曾经因为余额上限被旧配置写成 1，所有商品都存不下来，却只看到"价格无效"）。
     */
    private static long parseAdminPrice(ServerPlayer player, String text) {
        long raw = parsePositive(text);
        if (raw <= 0L) {
            notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price")
                    .withStyle(ChatFormatting.RED));
            return 0L;
        }
        long cap = Config.maxBalanceLimit();
        if (cap != Long.MAX_VALUE && raw > cap) {
            notifyPlayer(player, Component.translatable("msg.module_shop.price_over_cap",
                    Long.toString(cap)).withStyle(ChatFormatting.RED));
            return 0L;
        }
        return raw;
    }

    private static @Nullable PriceSpec parseAdminSpec(ServerPlayer player, CompoundTag tag) {
        return parseSpecField(player, tag, "priceSpec", "price", false);
    }

    private static @Nullable PriceSpec parseSpecField(ServerPlayer player, CompoundTag tag,
                                                      String specKey, String textKey, boolean allowZero) {
        if (tag == null) {
            return allowZero ? PriceSpec.ZERO : null;
        }
        PriceSpec raw = null;
        if (tag.contains(specKey, Tag.TAG_COMPOUND)) {
            raw = PriceSpec.fromTag(tag.getCompound(specKey), 0L);
        } else if (tag.contains("currencyMode", Tag.TAG_STRING) || tag.contains("physTier", Tag.TAG_STRING)) {
            CurrencyMode mode = CurrencyMode.fromId(tag.getString("currencyMode"));
            long star = tag.contains("starPrice", Tag.TAG_ANY_NUMERIC)
                    ? tag.getLong("starPrice") : parsePositive(tag.getString(textKey));
            String tier = tag.getString("physTier").trim();
            long physCnt = tag.contains("physCount", Tag.TAG_ANY_NUMERIC)
                    ? tag.getLong("physCount") : parsePositive(tag.getString(textKey));
            if (allowZero && star <= 0L && physCnt <= 0L && (mode == CurrencyMode.STAR_ONLY || tier.isEmpty())) {
                raw = PriceSpec.ZERO;
            } else {
                raw = PriceSpec.of(mode, star, tier, physCnt);
            }
        } else {
            String text = tag.getString(textKey).trim();
            if (allowZero && (text.isEmpty() || "0".equals(text))) {
                raw = PriceSpec.ZERO;
            } else {
                raw = PriceSpec.parseRuleText(text);
            }
        }
        if (raw == null) {
            notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price")
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        return validateAdminSpec(player, raw, allowZero);
    }

    private static @Nullable PriceSpec validateAdminSpec(ServerPlayer player, @Nullable PriceSpec spec,
                                                         boolean allowZero) {
        if (spec == null) {
            notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price")
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        if (spec.isZero()) {
            if (allowZero) {
                return PriceSpec.ZERO;
            }
            notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price")
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        if (!spec.isValid()) {
            notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price")
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        if (spec.supportsStar()) {
            long cap = Config.maxBalanceLimit();
            if (cap != Long.MAX_VALUE && spec.starPrice > cap) {
                notifyPlayer(player, Component.translatable("msg.module_shop.price_over_cap",
                        Long.toString(cap)).withStyle(ChatFormatting.RED));
                return null;
            }
        }
        if (spec.supportsPhys()) {
            PhysicalCurrencyConfig physCfg = PhysicalCurrencyConfig.get();
            var tier = physCfg.findTier(spec.physTierKey);
            if (!physCfg.isActive() || tier == null) {
                notifyPlayer(player, Component.translatableWithFallback(
                        "msg.module_shop.phys_tier_unavailable", "所需的实体货币档位未启用或不存在")
                        .withStyle(ChatFormatting.RED));
                return null;
            }
            if (spec.physCount > (long) Integer.MAX_VALUE * 64L) {
                notifyPlayer(player, Component.translatable("msg.module_shop.invalid_price")
                        .withStyle(ChatFormatting.RED));
                return null;
            }
            return spec.withPhysTierKey(tier.tierKey());
        }
        return spec;
    }

    /** 乘法溢出保护：溢出或非正数统一返回 0，调用方按“价格非法”处理。 */
    private static long safeMultiply(long price, int count) {
        if (price <= 0L || count <= 0) {
            return 0L;
        }
        return price > Long.MAX_VALUE / count ? 0L : price * count;
    }

    /**
     * 交付 count 件物品：按单栈上限拆成多份，先塞背包，塞不下的进邮箱（邮箱满由 addMail 兜底）。
     *
     * @return 是否有物品进了邮箱
     */
    private static boolean deliver(ServerPlayer player, ItemStack template, int count, String source) {
        int per = Math.max(1, template.getMaxStackSize());
        int left = count;
        boolean mailed = false;
        while (left > 0) {
            ItemStack part = template.copy();
            int give = Math.min(per, left);
            part.setCount(give);
            left -= give;
            player.getInventory().add(part);
            if (!part.isEmpty()) {
                addMail(player.server, player.getUUID(), part, source);
                mailed = true;
            }
        }
        return mailed;
    }

    private static int parseInt(String text) {
        try {
            return Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 需求 6/7：解析管理员输入的品质，无法识别（含留空）时按价格推导。 */
    private static Rarity parseRarity(String text, long price) {
        Rarity rarity = Rarity.byIdOrNull(text);
        return rarity != null ? rarity : Rarity.fromPrice(price);
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(ModuleShopMod.MODID, path);
    }

    /** 管理员保存服务器个人主页公告配置并热更新全服在线玩家快照。 */
    private static void adminAnnouncementsSave(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        ListTag list = tag.getList("announcements", net.minecraft.nbt.Tag.TAG_COMPOUND);
        boolean ok = cn.autoforged.module_shop.shop.home.AnnouncementConfig.saveFromTag(list);
        if (!ok) {
            notifyPlayer(player, Component.translatableWithFallback(
                    "msg.module_shop.announcements_save_failed", "保存公告配置失败，请检查服务端日志")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        notifyPlayer(player, Component.translatableWithFallback(
                "msg.module_shop.announcements_saved", "公告已保存并热更新同步至全服")
                .withStyle(ChatFormatting.GREEN));
        broadcastSnapshots(player.server);
    }

    /** 管理员保存分层实体货币配置并热更新全服在线玩家快照。 */
    private static void adminPhysCurrencySave(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player)) {
            return;
        }
        HolderLookup.Provider regs = player.level().registryAccess();
        var parsed = cn.autoforged.module_shop.shop.currency.PhysicalCurrencyConfig.parseAndValidateFromTag(tag, regs);
        if (!parsed.ok() || parsed.config() == null) {
            notifyPlayer(player, Component.translatableWithFallback(
                    "msg.module_shop.phys_save_invalid",
                    "实体货币配置无效：%s",
                    parsed.errorMessage()).withStyle(ChatFormatting.RED));
            return;
        }
        if (!cn.autoforged.module_shop.shop.currency.PhysicalCurrencyConfig.replaceAndSave(parsed.config())) {
            notifyPlayer(player, Component.translatableWithFallback(
                    "msg.module_shop.phys_save_failed", "写入 physical_currency.json 失败，请检查文件权限")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        // 检查当前商品池/收购池是否存在与新实体货币碰撞的情况，给出贴心安全提示
        if (parsed.config().isActive()) {
            ShopSavedData data = data(player.server);
            int lowPricedOffers = 0;
            for (ConfiguredOffer offer : data.shopPool) {
                if (PhysicalCurrencyEngine.isBuyPhysBelowCurrencyValue(
                        offer.stack, 1, offer.resolvePriceSpec(), regs)) {
                    lowPricedOffers++;
                }
            }
            for (MallItem mallItem : data.mallPool) {
                if (PhysicalCurrencyEngine.isBuyPhysBelowCurrencyValue(
                        mallItem.stack, 1, mallItem.resolvePriceSpec(), regs)) {
                    lowPricedOffers++;
                }
            }
            if (lowPricedOffers > 0) {
                notifyPlayer(player, Component.translatableWithFallback(
                        "msg.module_shop.phys_arbitrage_warn",
                        "提示：检测到 %s 条商品池价格低于对应实体货币面值，运行时防套利锁已自动拦截低价买币。",
                        lowPricedOffers).withStyle(ChatFormatting.YELLOW));
            }
        }
        notifyPlayer(player, Component.translatableWithFallback(
                "msg.module_shop.phys_saved",
                parsed.config().isActive()
                        ? "实体货币配置已保存并热启用（全服已同步）"
                        : "实体货币配置已保存（当前开关：关闭）")
                .withStyle(ChatFormatting.GREEN));
        broadcastSnapshots(player.server);
    }

    /**
     * 管理员配置系统定额费用（每日商店刷新费 / 每日收购刷新费 / 出货箱升级费）。
     * 客户端发送 {@code ListTag<CompoundTag>}；空列表表示清空覆盖、回退使用 {@link Config} 默认配置。
     */
    private static void adminSystemCostsSet(ServerPlayer player, CompoundTag tag) {
        if (!canEditAdmin(player) || tag == null) {
            return;
        }
        ShopSavedData data = data(player.server);
        boolean anyApplied = false;

        if (tag.contains("dailyRefreshSpecs", Tag.TAG_LIST)) {
            List<PriceSpec> parsed = parseAdminSpecListTag(player, tag.getList("dailyRefreshSpecs", Tag.TAG_COMPOUND));
            if (parsed == null) {
                return;
            }
            data.dailyRefreshSpecs.clear();
            data.dailyRefreshSpecs.addAll(parsed);
            anyApplied = true;
        } else if (tag.contains("dailyRefreshText", Tag.TAG_STRING)) {
            List<PriceSpec> parsed = parseAdminSpecTextList(player, tag.getString("dailyRefreshText"));
            if (parsed == null) {
                return;
            }
            data.dailyRefreshSpecs.clear();
            data.dailyRefreshSpecs.addAll(parsed);
            anyApplied = true;
        }

        if (tag.contains("buybackRefreshSpecs", Tag.TAG_LIST)) {
            List<PriceSpec> parsed = parseAdminSpecListTag(player, tag.getList("buybackRefreshSpecs", Tag.TAG_COMPOUND));
            if (parsed == null) {
                return;
            }
            data.buybackRefreshSpecs.clear();
            data.buybackRefreshSpecs.addAll(parsed);
            anyApplied = true;
        } else if (tag.contains("buybackRefreshText", Tag.TAG_STRING)) {
            List<PriceSpec> parsed = parseAdminSpecTextList(player, tag.getString("buybackRefreshText"));
            if (parsed == null) {
                return;
            }
            data.buybackRefreshSpecs.clear();
            data.buybackRefreshSpecs.addAll(parsed);
            anyApplied = true;
        }

        if (tag.contains("shippingUpgradeSpecs", Tag.TAG_LIST)) {
            List<PriceSpec> parsed = parseAdminSpecListTag(player, tag.getList("shippingUpgradeSpecs", Tag.TAG_COMPOUND));
            if (parsed == null) {
                return;
            }
            data.shippingUpgradeSpecs.clear();
            data.shippingUpgradeSpecs.addAll(parsed);
            syncShippingConfigForLevels(data);
            anyApplied = true;
        } else if (tag.contains("shippingUpgradeText", Tag.TAG_STRING)) {
            List<PriceSpec> parsed = parseAdminSpecTextList(player, tag.getString("shippingUpgradeText"));
            if (parsed == null) {
                return;
            }
            data.shippingUpgradeSpecs.clear();
            data.shippingUpgradeSpecs.addAll(parsed);
            syncShippingConfigForLevels(data);
            anyApplied = true;
        }

        if (!anyApplied) {
            return;
        }
        data.setDirty();
        notifyPlayer(player, Component.translatableWithFallback(
                "msg.module_shop.system_costs_saved", "系统定额费用设置已保存并热同步全服")
                .withStyle(ChatFormatting.GREEN));
        broadcastSnapshots(player.server);
    }

    private static @Nullable List<PriceSpec> parseAdminSpecListTag(ServerPlayer player, ListTag list) {
        List<PriceSpec> out = new ArrayList<>();
        if (list == null || list.isEmpty()) {
            return out;
        }
        for (int i = 0; i < list.size(); i++) {
            PriceSpec validated = validateAdminSpec(player, PriceSpec.fromTag(list.getCompound(i), 0L), true);
            if (validated == null) {
                return null;
            }
            out.add(validated);
        }
        return out;
    }

    private static @Nullable List<PriceSpec> parseAdminSpecTextList(ServerPlayer player, String rawText) {
        List<PriceSpec> out = new ArrayList<>();
        String clean = rawText == null ? "" : rawText.trim();
        if (clean.isEmpty()) {
            return out;
        }
        for (String piece : clean.split(",")) {
            String token = piece.trim();
            if (token.isEmpty()) {
                continue;
            }
            PriceSpec parsed = "0".equals(token) ? PriceSpec.ZERO : PriceSpec.parseRuleText(token);
            PriceSpec validated = validateAdminSpec(player, parsed, true);
            if (validated == null) {
                return null;
            }
            out.add(validated);
        }
        return out;
    }
}
