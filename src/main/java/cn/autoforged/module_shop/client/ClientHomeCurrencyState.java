package cn.autoforged.module_shop.client;

import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.StyledText;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.shop.home.AnnouncementBlock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 客户端缓存：服务器个人主页统计、公告列表与自定义实体货币配置/持有状态。
 * 随服务端快照更新（{@link #readSnapshot(CompoundTag)}），仅作展示与发起操作请求，权威校验在服务端。
 */
public final class ClientHomeCurrencyState {
    public static final String MODE_HYBRID_PHYSICAL_FIRST = "HYBRID_PHYSICAL_FIRST";
    public static final String MODE_PHYSICAL_ONLY = "PHYSICAL_ONLY";
    public static final String MODE_HYBRID_VIRTUAL_FIRST = "HYBRID_VIRTUAL_FIRST";

    private static final int[] MC_DARK_COLORS = {
            0xFF000000, 0xFF0000AA, 0xFF00AA00, 0xFF00AAAA, 0xFFAA0000, 0xFFAA00AA, 0xFFFFAA00, 0xFFAAAAAA,
            0xFF555555, 0xFF5555FF, 0xFF55FF55, 0xFF55FFFF, 0xFFFF5555, 0xFFFF55FF, 0xFFFFFF55, 0xFFFFFFFF,
    };
    private static final int[] MC_LIGHT_COLORS = {
            0xFF000000, 0xFF0000AA, 0xFF00800F, 0xFF00808A, 0xFFAA0000, 0xFF9A1C9A, 0xFFB86E00, 0xFF6B7484,
            0xFF4A4A4A, 0xFF3446D6, 0xFF1C8F3A, 0xFF0C849C, 0xFFD13A3A, 0xFFB03AB3, 0xFFA07A00, 0xFF111620,
    };

    // ---- 个人主页统计与 LuckPerms 称号 ----
    public static long playTimeTicks;
    public static long playTimeSyncMillis;
    public static boolean lpAvailable;
    public static String lpGroup = "";
    public static String lpPrefix = "";

    // ---- 服务器公告 ----
    public static List<AnnouncementView> announcements = new ArrayList<>();

    // ---- 实体货币 ----
    public static final String CHANNEL_STAR = "star";
    public static final String CHANNEL_PHYS = "phys";
    public static volatile String preferredEitherChannel = CHANNEL_STAR;

    public static boolean physEnabled;
    public static String physMode = MODE_HYBRID_PHYSICAL_FIRST;
    public static boolean physBlockBuyback = true;
    public static int physMaxConvertItems = 2304;
    public static long physDailyConvertLimit = 0L;
    public static long physDailyConvertUsed = 0L;
    public static long physPlayerValue;
    public static String physAddonDefaultTier = "";
    public static List<PhysTierView> physTiers = new ArrayList<>();

    /** 每次成功解析快照时递增，界面据此感知服务端热同步刷新。 */
    public static int snapshotVersion;

    private ClientHomeCurrencyState() {
    }

    public static final class AnnouncementView {
        public String id = "";
        public String title = "";
        public String category = "";
        public String content = "";
        public boolean pinned;
        public int order;
        public String date = "";
        public List<AnnouncementBlock> blocks = new ArrayList<>();

        public List<AnnouncementBlock> effectiveBlocks() {
            if (this.blocks != null && !this.blocks.isEmpty()) {
                return this.blocks;
            }
            if (this.content != null && !this.content.isEmpty()) {
                return List.of(AnnouncementBlock.textBlock(this.content));
            }
            return List.of();
        }

        public String derivePlainContent() {
            if (this.blocks == null || this.blocks.isEmpty()) {
                return this.content == null ? "" : this.content;
            }
            List<String> lines = new ArrayList<>(this.blocks.size());
            for (AnnouncementBlock b : this.blocks) {
                if (b == null) {
                    continue;
                }
                if (AnnouncementBlock.TYPE_IMAGE.equals(b.type())) {
                    String cap = b.caption() == null ? "" : b.caption().trim();
                    lines.add(cap.isEmpty() ? "[图片]" : "[图片: " + cap + "]");
                } else {
                    lines.add(b.text() == null ? "" : b.text());
                }
            }
            return String.join("\n", lines);
        }

        public AnnouncementView copy() {
            AnnouncementView v = new AnnouncementView();
            v.id = this.id;
            v.title = this.title;
            v.category = this.category;
            v.content = this.content;
            v.pinned = this.pinned;
            v.order = this.order;
            v.date = this.date;
            v.blocks = this.blocks == null ? new ArrayList<>() : new ArrayList<>(this.blocks);
            return v;
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", this.id == null ? "" : this.id);
            tag.putString("title", this.title == null ? "" : this.title);
            tag.putString("category", this.category == null ? "" : this.category);
            String plain = (this.blocks != null && !this.blocks.isEmpty())
                    ? derivePlainContent()
                    : (this.content == null ? "" : this.content);
            tag.putString("content", plain);
            tag.putBoolean("pinned", this.pinned);
            tag.putInt("order", this.order);
            tag.putString("date", this.date == null ? "" : this.date);
            ListTag blockList = new ListTag();
            if (this.blocks != null) {
                for (AnnouncementBlock block : this.blocks) {
                    if (block != null) {
                        blockList.add(block.toTag());
                    }
                }
            }
            tag.put("blocks", blockList);
            return tag;
        }
    }

    public static final class PhysTierView {
        public String id = "";
        public String snapshotTierKey = "";
        public String itemId = "";
        public ItemStack itemStack = ItemStack.EMPTY;
        public String componentsSnbt = "";
        public int stepRatio = 1;
        public long value = 1L;
        public String label = "";
        public int ownedCount;

        public String uniqueItemKey() {
            String item = this.itemId == null ? "" : this.itemId.trim().toLowerCase(Locale.ROOT);
            String snbt = this.componentsSnbt == null ? "" : this.componentsSnbt.trim();
            return item + "|" + snbt;
        }

        public String tierKey() {
            if (this.id != null && !this.id.isBlank()) {
                return this.id.trim();
            }
            if (this.snapshotTierKey != null && !this.snapshotTierKey.isBlank()) {
                return this.snapshotTierKey.trim();
            }
            return uniqueItemKey();
        }

        public boolean hasCustomComponents() {
            return (this.componentsSnbt != null && !this.componentsSnbt.isBlank())
                    || (this.itemStack != null && !this.itemStack.isEmpty() && !this.itemStack.isComponentsPatchEmpty());
        }

        public String displayName() {
            if (this.label != null && !this.label.isBlank()) {
                return this.label;
            }
            if (this.itemStack != null && !this.itemStack.isEmpty()) {
                return this.itemStack.getHoverName().getString();
            }
            return this.itemId == null ? "" : this.itemId;
        }
    }

    /** 读取服务端快照中的主页统计、公告与实体货币状态。 */
    public static void readSnapshot(CompoundTag data) {
        readSnapshot(data, currentRegistries());
    }

    public static void readSnapshot(CompoundTag data, @Nullable HolderLookup.Provider registries) {
        if (data == null) {
            return;
        }
        String kind = data.getString("kind");
        if (!kind.isEmpty() && !"snapshot".equals(kind)) {
            return;
        }
        HolderLookup.Provider regs = registries != null ? registries : currentRegistries();

        playTimeTicks = Math.max(0L, data.getLong("playTimeTicks"));
        playTimeSyncMillis = System.currentTimeMillis();
        lpAvailable = data.getBoolean("lpAvailable");
        lpGroup = data.getString("lpGroup");
        lpPrefix = data.getString("lpPrefix");

        List<AnnouncementView> nextAnnouncements = new ArrayList<>();
        ListTag annList = data.getList("announcements", Tag.TAG_COMPOUND);
        for (int i = 0; i < annList.size(); i++) {
            CompoundTag c = annList.getCompound(i);
            AnnouncementView v = new AnnouncementView();
            v.id = c.getString("id");
            v.title = c.getString("title");
            v.category = c.getString("category");
            v.content = c.getString("content");
            v.pinned = c.getBoolean("pinned");
            v.order = c.getInt("order");
            v.date = c.getString("date");
            ListTag blockList = c.getList("blocks", Tag.TAG_COMPOUND);
            for (int bi = 0; bi < blockList.size(); bi++) {
                AnnouncementBlock block = AnnouncementBlock.fromTag(blockList.getCompound(bi));
                if (block != null) {
                    v.blocks.add(block);
                }
            }
            if (v.blocks.isEmpty() && v.content != null && !v.content.isEmpty()) {
                for (String para : v.content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
                    v.blocks.add(AnnouncementBlock.textBlock(para));
                }
            }
            nextAnnouncements.add(v);
        }
        nextAnnouncements.sort(Comparator
                .comparing((AnnouncementView a) -> a.pinned).reversed()
                .thenComparingInt(a -> a.order));
        announcements = nextAnnouncements;

        physEnabled = data.getBoolean("physEnabled");
        physMode = normalizeMode(data.getString("physMode"));
        physBlockBuyback = !data.contains("physBlockBuyback") || data.getBoolean("physBlockBuyback");
        physMaxConvertItems = data.contains("physMaxConvertItems")
                ? Math.max(1, Math.min(64000, data.getInt("physMaxConvertItems"))) : 2304;
        physDailyConvertLimit = Math.max(0L, data.getLong("physDailyConvertLimit"));
        physDailyConvertUsed = Math.max(0L, data.getLong("physDailyConvertUsed"));
        physPlayerValue = Math.max(0L, data.getLong("physPlayerValue"));
        physAddonDefaultTier = data.getString("physAddonDefaultTier");

        List<PhysTierView> nextTiers = new ArrayList<>();
        ListTag tierList = data.getList("physTiers", Tag.TAG_COMPOUND);
        for (int i = 0; i < tierList.size(); i++) {
            CompoundTag c = tierList.getCompound(i);
            PhysTierView tier = new PhysTierView();
            tier.id = c.getString("id");
            tier.snapshotTierKey = c.getString("tierKey");
            tier.itemId = c.getString("itemId");
            tier.componentsSnbt = c.getString("componentsSnbt");
            int rawRatio = c.getInt("stepRatio");
            tier.stepRatio = i == 0 ? Math.max(1, rawRatio) : rawRatio;
            tier.value = Math.max(1L, c.getLong("value"));
            tier.label = c.getString("label");
            tier.ownedCount = Math.max(0, c.getInt("ownedCount"));
            tier.itemStack = parseTierStack(c, tier.itemId, tier.componentsSnbt, regs);
            if (tier.itemId.isEmpty() && !tier.itemStack.isEmpty()) {
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(tier.itemStack.getItem());
                if (key != null) {
                    tier.itemId = key.toString();
                }
            }
            nextTiers.add(tier);
        }
        physTiers = nextTiers;
        snapshotVersion++;
    }

    public static @Nullable PhysTierView findTier(@Nullable String key) {
        int idx = findTierIndex(key);
        return idx >= 0 && idx < physTiers.size() ? physTiers.get(idx) : null;
    }

    public static int findTierIndex(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return -1;
        }
        String k = key.trim();
        List<PhysTierView> list = physTiers;
        for (int i = 0; i < list.size(); i++) {
            PhysTierView t = list.get(i);
            if (k.equals(t.id) || k.equals(t.tierKey())) {
                return i;
            }
        }
        for (int i = 0; i < list.size(); i++) {
            if (k.equalsIgnoreCase(list.get(i).uniqueItemKey())) {
                return i;
            }
        }
        if (!k.contains("|")) {
            for (int i = 0; i < list.size(); i++) {
                if (k.equalsIgnoreCase(list.get(i).itemId)) {
                    return i;
                }
            }
        }
        return -1;
    }

    public static long[] tierValues() {
        List<PhysTierView> list = physTiers;
        long[] out = new long[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = Math.max(1L, list.get(i).value);
        }
        return out;
    }

    public static int[] tierOwnedCounts() {
        List<PhysTierView> list = physTiers;
        int[] out = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = Math.max(0, list.get(i).ownedCount);
        }
        return out;
    }

    /** 实时扫描客户端本地背包各实体货币档位持有量；若玩家实例未就绪则回退服务端快照值。 */
    public static int[] liveOwnedCounts() {
        List<PhysTierView> list = physTiers;
        if (list.isEmpty()) {
            return new int[0];
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return tierOwnedCounts();
        }
        var inv = mc.player.getInventory();
        int[] counts = new int[list.size()];
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            if (slot >= 36 && slot < 40) {
                continue;
            }
            ItemStack st = inv.getItem(slot);
            if (st.isEmpty()) {
                continue;
            }
            for (int t = 0; t < list.size(); t++) {
                ItemStack tpl = list.get(t).itemStack;
                if (tpl != null && !tpl.isEmpty()
                        && cn.autoforged.module_shop.shop.currency.PhysicalCurrencyEngine.matchesTier(st, tpl)) {
                    counts[t] += st.getCount();
                    break;
                }
            }
        }
        return counts;
    }

    /** 若实体货币已启用且配置了有效的附属默认货币档位，返回该档位视图；否则返回 {@code null}（使用虚拟主货币）。 */
    public static @Nullable PhysTierView activeAddonPhysTier() {
        if (!physEnabled || physAddonDefaultTier == null || physAddonDefaultTier.isBlank()) {
            return null;
        }
        return findTier(physAddonDefaultTier);
    }

    public static void togglePreferredEitherChannel() {
        preferredEitherChannel = CHANNEL_PHYS.equals(preferredEitherChannel) ? CHANNEL_STAR : CHANNEL_PHYS;
    }

    public static int effectiveMaxConvertItems() {
        return physMaxConvertItems <= 0 ? 2304 : Math.max(1, Math.min(64000, physMaxConvertItems));
    }

    public static long remainingDailyConvertValue() {
        return physDailyConvertLimit <= 0L ? Long.MAX_VALUE : Math.max(0L, physDailyConvertLimit - physDailyConvertUsed);
    }

    public static void reset() {
        playTimeTicks = 0L;
        playTimeSyncMillis = 0L;
        lpAvailable = false;
        lpGroup = "";
        lpPrefix = "";
        announcements = new ArrayList<>();
        physEnabled = false;
        physMode = MODE_HYBRID_PHYSICAL_FIRST;
        physBlockBuyback = true;
        physMaxConvertItems = 2304;
        physDailyConvertLimit = 0L;
        physDailyConvertUsed = 0L;
        physPlayerValue = 0L;
        physAddonDefaultTier = "";
        physTiers = new ArrayList<>();
        snapshotVersion++;
        cn.autoforged.module_shop.client.media.ClientMediaCache.clearMemory();
        ClientAdminAuth.clearState();
    }

    /** 基于上次同步时刻实时推算当前累计在线刻数（1 秒 = 20 ticks）。 */
    public static long currentPlayTimeTicks() {
        long base = Math.max(0L, playTimeTicks);
        if (playTimeSyncMillis <= 0L) {
            return base;
        }
        long elapsedMs = Math.max(0L, System.currentTimeMillis() - playTimeSyncMillis);
        return base + (elapsedMs / 50L);
    }

    public static String formatPlayTime(long ticks) {
        long totalSeconds = Math.max(0L, ticks) / 20L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        if (hours > 0L) {
            return tr("gui.module_shop.home.playtime_hm", "%s小时 %s分", hours, minutes);
        }
        return tr("gui.module_shop.home.playtime_m", "%s分钟", minutes);
    }

    public static String normalizeMode(String raw) {
        if (MODE_PHYSICAL_ONLY.equals(raw) || MODE_HYBRID_VIRTUAL_FIRST.equals(raw)
                || MODE_HYBRID_PHYSICAL_FIRST.equals(raw)) {
            return raw;
        }
        return MODE_HYBRID_PHYSICAL_FIRST;
    }

    public static String modeLabel(String mode) {
        return switch (normalizeMode(mode)) {
            case MODE_PHYSICAL_ONLY -> tr("gui.module_shop.phys.mode.physical_only", "纯实体货币");
            case MODE_HYBRID_VIRTUAL_FIRST -> tr("gui.module_shop.phys.mode.hybrid_virt", "优先虚拟货币混付");
            default -> tr("gui.module_shop.phys.mode.hybrid_phys", "优先实体货币混付");
        };
    }

    /**
     * 优先使用语言表翻译键；当语言文件尚未合入对应 key 时回退到可读默认文本（并应用自定义货币名）。
     */
    public static String tr(String key, String fallback, Object... args) {
        Language lang = Language.getInstance();
        if (lang != null && lang.has(key)) {
            return Component.translatable(key, args).getString();
        }
        return Component.translatableWithFallback(key, fallback, args).getString();
    }

    /**
     * 解析带 {@code §} / {@code &} 颜色码以及 LuckPerms {@code &#RRGGBB} / {@code &x&r&r&g&g&b&b}
     * 十六进制色的富文本段，并做安全字符清洗。
     */
    public static List<StyledText.Run> parseStyled(String raw, int baseColor) {
        List<StyledText.Run> runs = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return runs;
        }
        int color = baseColor;
        boolean bold = false;
        StringBuilder buf = new StringBuilder();
        int len = raw.length();
        for (int i = 0; i < len; i++) {
            char c = raw.charAt(i);
            if ((c == '§' || c == '&') && i + 1 < len) {
                char next = raw.charAt(i + 1);
                if (next == '#' && i + 7 < len) {
                    int parsedHex = parseHexColor(raw, i + 2);
                    if (parsedHex != -1) {
                        flushStyledRun(runs, buf, color, bold);
                        color = parsedHex;
                        bold = false;
                        i += 7;
                        continue;
                    }
                }
                char lower = Character.toLowerCase(next);
                if (lower == 'x' && i + 13 < len) {
                    int parsedBungee = parseBungeeHex(raw, i + 2);
                    if (parsedBungee != -1) {
                        flushStyledRun(runs, buf, color, bold);
                        color = parsedBungee;
                        bold = false;
                        i += 13;
                        continue;
                    }
                }
                int idx = "0123456789abcdef".indexOf(lower);
                if (idx >= 0) {
                    flushStyledRun(runs, buf, color, bold);
                    color = themePaletteColor(idx);
                    bold = false;
                    i++;
                    continue;
                }
                if (lower == 'r') {
                    flushStyledRun(runs, buf, color, bold);
                    color = baseColor;
                    bold = false;
                    i++;
                    continue;
                }
                if (lower == 'l') {
                    flushStyledRun(runs, buf, color, bold);
                    bold = true;
                    i++;
                    continue;
                }
                if ("kmno".indexOf(lower) >= 0) {
                    i++;
                    continue;
                }
            }
            buf.append(c);
        }
        flushStyledRun(runs, buf, color, bold);
        return runs;
    }

    public static String stripColorCodes(String raw) {
        StringBuilder sb = new StringBuilder();
        for (StyledText.Run r : parseStyled(raw, 0xFFFFFFFF)) {
            sb.append(r.text());
        }
        return sb.toString();
    }

    /**
     * 规范化普通 &amp; 颜色码为 §，便于传给 {@link StyledText}。
     */
    public static String normalizeColorCodes(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        char[] chars = raw.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == '&') {
                char code = Character.toLowerCase(chars[i + 1]);
                if ("0123456789abcdefklmnorx".indexOf(code) >= 0) {
                    chars[i] = '§';
                }
            }
        }
        return new String(chars);
    }

    /** 判断物品是否为未附加任何自定义组件的原版常见物品（用于管理配置温和防刷提示）。 */
    public static boolean isVanillaWithoutComponents(String itemId, String componentsSnbt, @Nullable ItemStack stack) {
        String id = itemId == null ? "" : itemId.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            return false;
        }
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        if (!id.startsWith("minecraft:")) {
            return false;
        }
        boolean hasSnbt = componentsSnbt != null && !componentsSnbt.isBlank() && !"{}".equals(componentsSnbt.trim());
        boolean hasStackPatch = stack != null && !stack.isEmpty() && !stack.isComponentsPatchEmpty();
        return !hasSnbt && !hasStackPatch;
    }

    /** 根据 itemId 与可选组件 SNBT 解析用于预览的 ItemStack；不合法时返回 {@link ItemStack#EMPTY}。 */
    public static ItemStack resolveStack(String itemId, String componentsSnbt, @Nullable HolderLookup.Provider registries) {
        if (itemId == null || itemId.isBlank()) {
            return ItemStack.EMPTY;
        }
        ResourceLocation rl = ResourceLocation.tryParse(itemId.trim());
        if (rl == null) {
            return ItemStack.EMPTY;
        }
        Optional<Item> opt = BuiltInRegistries.ITEM.getOptional(rl);
        if (opt.isEmpty() || opt.get() == Items.AIR) {
            return ItemStack.EMPTY;
        }
        if (componentsSnbt == null || componentsSnbt.isBlank()) {
            return new ItemStack(opt.get());
        }
        HolderLookup.Provider regs = registries != null ? registries : currentRegistries();
        if (regs == null) {
            return new ItemStack(opt.get());
        }
        try {
            CompoundTag comp = TagParser.parseTag(componentsSnbt.trim());
            CompoundTag full = new CompoundTag();
            full.putString("id", rl.toString());
            full.putInt("count", 1);
            full.put("components", comp);
            ItemStack parsed = ItemStack.parseOptional(regs, full);
            return parsed.isEmpty() ? new ItemStack(opt.get()) : parsed;
        } catch (Exception ignored) {
            return new ItemStack(opt.get());
        }
    }

    public static boolean isValidComponentsSnbt(String snbt) {
        if (snbt == null || snbt.isBlank()) {
            return true;
        }
        try {
            TagParser.parseTag(snbt.trim());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String extractComponentsSnbt(ItemStack stack, @Nullable HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty() || stack.isComponentsPatchEmpty()) {
            return "";
        }
        HolderLookup.Provider regs = registries != null ? registries : currentRegistries();
        if (regs == null) {
            return "";
        }
        try {
            Tag saved = stack.saveOptional(regs);
            if (saved instanceof CompoundTag ct && ct.contains("components", Tag.TAG_COMPOUND)) {
                return ct.getCompound("components").toString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    @Nullable
    public static HolderLookup.Provider currentRegistries() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return null;
        }
        if (mc.level != null) {
            return mc.level.registryAccess();
        }
        if (mc.getConnection() != null) {
            return mc.getConnection().registryAccess();
        }
        return null;
    }

    private static ItemStack parseTierStack(CompoundTag c, String itemId, String componentsSnbt,
                                            @Nullable HolderLookup.Provider regs) {
        if (c.contains("itemStack", Tag.TAG_COMPOUND) && regs != null) {
            CompoundTag st = c.getCompound("itemStack");
            CompoundTag inner = (st.contains("item", Tag.TAG_COMPOUND) && !st.contains("id", Tag.TAG_STRING))
                    ? st.getCompound("item") : st;
            if (!inner.isEmpty()) {
                ItemStack parsed = ItemStack.parseOptional(regs, inner);
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            }
        }
        return resolveStack(itemId, componentsSnbt, regs);
    }

    private static void flushStyledRun(List<StyledText.Run> runs, StringBuilder buf, int color, boolean bold) {
        if (buf.length() > 0) {
            String clean = Text.sanitize(buf.toString());
            if (!clean.isEmpty()) {
                runs.add(new StyledText.Run(clean, color, bold));
            }
            buf.setLength(0);
        }
    }

    private static int themePaletteColor(int idx) {
        return Draw.lerp(MC_DARK_COLORS[idx], MC_LIGHT_COLORS[idx], IOStyle.themeProgress());
    }

    private static int parseHexColor(String s, int start) {
        int rgb = 0;
        for (int i = 0; i < 6; i++) {
            int digit = Character.digit(s.charAt(start + i), 16);
            if (digit < 0) {
                return -1;
            }
            rgb = (rgb << 4) | digit;
        }
        return 0xFF000000 | rgb;
    }

    private static int parseBungeeHex(String s, int start) {
        int rgb = 0;
        for (int i = 0; i < 6; i++) {
            char prefix = s.charAt(start + i * 2);
            if (prefix != '§' && prefix != '&') {
                return -1;
            }
            int digit = Character.digit(s.charAt(start + i * 2 + 1), 16);
            if (digit < 0) {
                return -1;
            }
            rgb = (rgb << 4) | digit;
        }
        return 0xFF000000 | rgb;
    }
}
