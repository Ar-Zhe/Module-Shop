package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.Config;
import cn.autoforged.module_shop.client.ClientNotifications;
import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.MallFavorites;
import cn.autoforged.module_shop.client.UITheme;
import cn.autoforged.module_shop.data.ConfiguredOffer;
import cn.autoforged.module_shop.data.NotificationType;
import cn.autoforged.module_shop.data.Rarity;
import cn.autoforged.module_shop.data.SidebarLayout;
import cn.autoforged.module_shop.network.payload.ServerboundShopActionPayload;
import cn.autoforged.module_shop.shop.ShopManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** 单界面、标签页切换的商店主界面。没有真实槽位，所有操作通过操作包发给服务端。 */
public class ShopScreen extends Screen implements JeiHoverSource {
    /** JEI 桥接用：本帧鼠标悬停的物品（换页签/离开图标即清空）。 */
    private net.minecraft.world.item.ItemStack jeiHover = net.minecraft.world.item.ItemStack.EMPTY;

    @Override
    public net.minecraft.world.item.ItemStack jeiHoveredStack(double mouseX, double mouseY) {
        return this.jeiHover == null ? net.minecraft.world.item.ItemStack.EMPTY : this.jeiHover;
    }

    // ---- OneConfig 风格版式（扁平直角 · 尺寸已整体缩小） ----
    /** 窗口：左栏（导航列表）+ 右栏（内容），整体一块纯色直角面板。 */
    private static final int PANEL_W = 400;
    private static final int PANEL_H = 218;
    /** 内容区顶部标题行高度（标题 + 分类药丸 + 关闭）。 */
    private static final int TOP_BAR_H = 28;
    /** 侧栏列表项高度（网格单元高度，与 GRID_GAP_Y 一起决定行距）。 */
    private static final int TAB_H = 17;
    /** 拖拽判定阈值（像素）：位移不超过它就算点击。 */
    private static final int DRAG_THRESHOLD = 3;
    private static final int SIDEBAR_W = 104;
    /** 侧栏一屏最多显示几个模块（再多就靠滚轮上下滑，底部会显示滚动条与剩余数量）。 */
    private static final int SIDEBAR_ROWS = 8;
    /** 侧栏网格：1 列 N 行（把 TAB_COLS 改成 2 就是 2 列 N 行，槽位坐标会自动重算）。 */
    private static final int TAB_COLS = 1;
    private static final int TAB_W = SIDEBAR_W - 12;
    private static final int GRID_X = 6;
    /** 列表起点：品牌行之后。 */
    private static final int GRID_Y = 30;
    private static final int GRID_GAP_X = 6;
    private static final int GRID_GAP_Y = 1;
    /** 分组标题高度（分组标题已按需求取消，保留常量以免槽位公式改动）。 */
    private static final int LABEL_H = 0;
    /** 左下角玩家信息块高度。 */
    private static final int PROFILE_H = 34;
    /**
     * 文字颜色：不是常量，每帧由 {@link Theme} 按日间/暗色主题刷新（见 render 开头），
     * 这样切换主题后原有全部绘制代码无需改动即可跟随换色。
     */
    private static int COLOR_TEXT = IOStyle.TEXT;
    private static int COLOR_MUTED = IOStyle.TEXT_2;
    private static int COLOR_GOLD = IOStyle.ORANGE;
    private static final int GEAR_TEX_SIZE = 128;
    /** 需求 2：齿轮入口纹理（源文件 user-assets/577940.png，128×128）。 */
    private static final net.minecraft.resources.ResourceLocation GEAR_ICON =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "module_shop", "textures/gui/gear.png");

    // 需求：模块放在左侧从上往下排；钱包格去掉。管理页签不再占用侧栏（见左下角小按钮）。
    //
    // ⚠ 这里是**所有模块的唯一来源**：以后新增模块时，只在这一个数组里加 id，并且：
    //   ① 在 tabKey(id) 里补一条显示名（tab.module_shop.<id>）；② 在中英 lang 里补同名键；
    //   ③ 在 render 的内容区 switch 里补一个分支。
    // 左侧菜单（visibleTabs）与编辑模式下的「模块管理」（所有模块 = 已启用 + 已移除）都由这个数组推导，
    // **不要再另写一份模块清单**，否则新模块会「侧栏有、模块管理里没有」。
    private static final String[] TAB_IDS = {"daily", "buyback", "mall", "shipping", "auction", "stall", "demand", "mail",
            "exchange", "signin", "trade", "transfer"};

    /** 侧栏里**固定置顶**的模块（顺序即显示顺序）。 */
    private static final List<String> PINNED_TOP = List.of("signin", "trade", "transfer");

    /** 全部模块 id（侧栏、模块管理、服务端校验共用同一份）。 */
    static List<String> allModuleIds() {
        return List.of(TAB_IDS);
    }

    /**
     * 管理页里**可以编辑的模块**（顶部「选择模块编辑」下拉的内容）。
     *
     * <p>⚠ 以后新增可编辑模块：在这里加一行（{@code id} 用服务端 {@code adminMode} 认的值，
     * {@code nameKey} 是显示名），再在 {@link #drawAdmin} 里补上它自己的数据源 / 字段分支即可 ——
     * 下拉列表会自动带上它，不需要再动界面代码。
     */
    private record AdminModule(String id, String nameKey) {
    }

    /** 管理页可编辑模块清单（顺序即下拉里的顺序）。 */
    private static final List<AdminModule> ADMIN_MODULES = List.of(
            new AdminModule("shop", "gui.module_shop.admin_shop"),
            new AdminModule("buyback", "gui.module_shop.admin_buyback"),
            new AdminModule("mall", "gui.module_shop.admin_mall"),
            new AdminModule("shipping", "gui.module_shop.admin_shipping"),
            // 摊位：玩家自己上架，管理员这里只放「下架时间」设置（没有可编辑的条目列表）。
            new AdminModule("stall", "gui.module_shop.admin_stall"),
            // 求购：玩家自己发布悬赏；管理员这里可以调设置、看全部求购并强制撤销。
            new AdminModule("demand", "gui.module_shop.admin_demand"),
            // 签到：奖励、补签、累计档位、里程、累计加成 / 自动签到 —— 全部集中在这里设置。
            new AdminModule("signin", "gui.module_shop.admin_signin"));

    // ================= 购物城（新增模块）=================
    /** 货架 2 行 6 列 = 一页 12 件。 */
    private static final int MALL_COLS = 6;
    private static final int MALL_ROWS = 2;
    private static final int MALL_PAGE_SIZE = MALL_COLS * MALL_ROWS;
    /** 顶部两行控件的高度与间距。 */
    private static final int MALL_CTRL_H = 16;
    private static final int MALL_CTRL_GAP = 4;
    /** 网格顶端（相对内容区）。 */
    private static final int MALL_GRID_TOP = 38;
    /** 卡片间距。 */
    private static final int MALL_CARD_GAP = 3;
    /** 卡片里的小字缩放（三行：名称 / 价格 / 库存）。 */
    private static final float MALL_TEXT = 0.7F;
    /** 分类下拉最多显示几行。 */
    private static final int MALL_CAT_ROWS = 8;

    // ================= 内容区底部操作条 =================
    // 所有模块的「刷新按钮 / 奖励提示 / 管理员开关 / 页码」统一排在这一条里，由右侧往左依次摆放，
    // 保证任何组合下都不会互相遮挡（这是第八轮「界面整理」的核心约定）。
    /** 底栏高度。 */
    private static final int BAR_H = 18;
    /** 底栏与内容区右/下边缘的内边距。 */
    private static final int BAR_PAD = 4;
    /** 「刷新」按钮的固定尺寸（各模块一致）。 */
    private static final int REFRESH_W = 52;
    private static final int REFRESH_H = 12;
    /** 底栏左侧文字与右侧控件之间至少保留的间距。 */
    private static final int BAR_GAP = 8;
    /** 邮箱面板的文字缩放：整体比默认小一号（列表 7 行 + 正文更从容）。 */
    private static final float MAIL_TEXT = 0.8F;
    /** 左下角信息块里「余额」一行的缩放：小一号，保证再长的数字也不会顶到边框。 */
    private static final float BALANCE_TEXT = 0.8F;

    /** 底栏顶边（内容区坐标）。 */
    private static int barTop(int cy, int ch) {
        return cy + ch - BAR_H;
    }

    /** 让高度为 h 的控件在底栏里垂直居中。 */
    private static int barMid(int cy, int ch, int h) {
        return barTop(cy, ch) + (BAR_H - h) / 2;
    }

    /** 0.8 倍字号文字在底栏里的 y（视觉上与 barMid(...,8) 居中）。 */
    private static int barTextY(int cy, int ch) {
        return barMid(cy, ch, 8) + 1;
    }

    /** 「刷新」按钮的左上角坐标（始终右下角对齐）。 */
    private static int refreshBtnX(int cx, int cw) {
        return cx + cw - BAR_PAD - REFRESH_W;
    }

    private static int refreshBtnY(int cy, int ch) {
        return barMid(cy, ch, REFRESH_H);
    }

    private final List<Region> regions = new ArrayList<>();
    private final java.util.Map<Integer, Integer> flipStart = new java.util.HashMap<>();
    /** 本次翻牌的「旧面」（点击瞬间记录）：动画前半段画它、后半段画新面，两者在缩放最小时切换。 */
    private final java.util.Map<Integer, Boolean> flipFrom = new java.util.HashMap<>();
    /** 一次翻牌的时长（tick）：10 tick = 0.5s，接近真实纸牌翻转的节奏。 */
    private static final float FLIP_TICKS = 10.0F;
    private String currentTab;
    private int tickCounter;
    private int auctionPage;
    private int adminPage;
    private int mailPage;
    private String adminMode = "shop";
    /** 需求 ④：当前页签的列表还有内容没显示完（渲染时置位），据此在底部画浮动提示。 */
    private boolean moreBelow;
    /** 渐隐提示贴合的底边 y（由各模块上报自己的列表底边，保证提示只画在列表内部，绝不进底栏）。 */
    private int scrollFadeBottom = Integer.MIN_VALUE;
    // ---- 侧栏拖拽（管理员编辑模式）----
    /** 是否处于布局编辑模式（从「商店设置」页的「编辑布局」进入）。 */
    private boolean layoutEdit;
    /** 签到页：管理员「编辑日程」态（点日历上的日期 = 新建 / 修改那天的日程）。 */
    private boolean signInEdit;
    /** 签到页本帧要画的悬浮提示：单个 Tooltip 与日程多行 Tooltip（都留到最后统一画，避免被后画的格子盖住）。 */
    private Component signInTip;
    private List<Component> signInTipLines;
    /** 本帧要画的物品悬浮框（留到整个界面画完再画，避免被后面的行盖住）。 */
    private ItemStack pendingItemTip = ItemStack.EMPTY;
    /** 正在按住 / 拖动中的页签 id。 */
    private String dragTab;
    /** 本次按住是否已经超过阈值（true = 拖拽，false = 点击）。 */
    private boolean dragMoved;
    private int dragPressX;
    private int dragPressY;
    private int dragOffX;
    private int dragOffY;
    /** 本帧各页签的命中区（编辑模式拖拽用）。 */
    private final List<TabRect> tabRects = new ArrayList<>();
    /**
     * 页签的**槽位动画值**（单位 = 行号，可以是小数）：换位 / 增删模块时逐帧靠拢目标行，
     * 所以被挤开的页签会「滑」过去。
     *
     * <p>注意它只负责**换位**动画：上下滚动由 {@link #sidebarScrollPx} 单独负责，
     * 这样滚轮滚动时整列是**平滑位移**（而不是每行各自插值，看起来发飘）。
     */
    private final Map<String, Float> tabSlotAnim = new HashMap<>();
    /** 侧栏滚动的**像素**位置（逐帧指数逼近 {@code sidebarScroll * 行距}；-1 = 还没初始化）。 */
    private float sidebarScrollPx = -1.0F;
    /** 滚轮累积量（触控板会发小数，攒够一格才滚一行）。 */
    private double sidebarWheelAccum;
    /** 拖拽中的页签在面板内的自由 x（拖动时脱离网格跟着鼠标）。 */
    private float dragFreeX = Float.NaN;
    /** 上一帧的时间戳，用来算帧间隔（动画与帧率无关）。 */
    private long lastFrameNanos;
    /** 上一次显示的余额（用于金额变化时闪烁）。 */
    private long lastShownBalance = Long.MIN_VALUE;
    /** 第八轮：邮箱附件格子的滚轮行（多于一屏时上下滑动）与其命中区域。 */
    private int attachRow;
    private int attachRows = 2;
    private String attachMailId = "";
    private final int[] attachRect = new int[4];
    /** 邮件正文的滚轮行（正文过长时上下滑动）与其命中区域、可滚动上限。 */
    private int mailBodyScroll;
    private final int[] bodyRect = new int[4];
    private int bodyMaxScroll;
    private String bodyMailId = "";
    /** 当前鼠标位置（每帧记录一次），供按钮/页签绘制判断悬停状态。 */
    private int pointerX;
    private int pointerY;
    /** 编辑模式：模块库面板是否展开（列出可添加的模块）。 */
    private boolean poolOpen;
    /** 编辑模式：正在从模块库往外拖的模块 id（拖到左侧列表即加入）。 */
    private String poolDrag;
    /** 待确认删除的模块（二次确认弹卡用）。 */
    private String pendingDelete;
    /** 拖拽模块时的「删除区」矩形（顶部工具条卡片的位置）。 */
    private final int[] trashZone = new int[4];
    /** 模块管理列表的面板矩形（滚轮命中判定用）。 */
    private final int[] managerRect = new int[4];
    /** 模块管理列表的滚动行（模块多于一屏时滚轮往下翻）。 */
    private int managerScroll;
    /** 左侧模块列表的滚动行（模块太多时滚轮往下翻）。 */
    private int sidebarScroll;
    /** 上一次「把选中模块滚进可视区」时处理的页签（只有选中项变化时才调整，避免和玩家滚轮打架）。 */
    private String sidebarEnsuredTab;
    /** 管理页：顶部「选择模块编辑」下拉是否展开。 */
    private boolean adminModuleOpen;

    // ---- 购物城界面状态 ----
    /** 搜索框（只在购物城页显示；聚焦时数字键不再触发切页快捷键）。 */
    private net.minecraft.client.gui.components.EditBox mallSearchBox;
    /** 交易 / 转账页的玩家搜索框（同样只有这两页显示）。 */
    private net.minecraft.client.gui.components.EditBox tradeSearchBox;
    /** 交易 / 转账页的玩家列表页码。 */
    private int tradePage;
    /** 排序 / 分类下拉是否展开（展开时不下画网格，避免物品图标穿透面板）。 */
    private boolean mallSortOpen;
    private boolean mallCatOpen;
    /** 分类下拉的滚动行。 */
    private int mallCatScroll;
    /** 分类下拉面板矩形（滚轮命中判定用）。 */
    private final int[] mallCatRect = new int[4];
    /** 排序下拉里每一项的矩形。 */
    private final List<int[]> mallSortRects = new ArrayList<>();
    /** 分类下拉里每一项的矩形。 */
    private final List<int[]> mallCatRects = new ArrayList<>();
    /** 分类缓存：货架上出现过的模组命名空间（按显示名首字母排序）。 */
    private static List<String> mallModCache = List.of();
    private static int mallModCacheSize = -1;
    /** 过滤结果缓存（避免每帧对上千件商品重排）。 */
    private static List<ClientShopData.MallView> mallFilterCache = List.of();
    private static String mallFilterKey = "";
    private int poolPressX;
    private int poolPressY;
    private boolean poolMoved;

    /** 一次翻牌的动画状态：横向缩放、这一帧画哪一面、当前进度（0~1，用于扫光）。 */
    private record Flip(float scale, boolean front, float progress) {
    }

    /**
     * 翻牌动画：**以卡牌 / 长条的左右中点为轴**横向翻转，做出真正「牌转过去」的观感。
     *
     * <p>要点：
     * <ol>
     *   <li>前半段画**旧面**、后半段画**新面**，切换点正好落在缩放趋近 0 的那一帧，
     *       所以看起来是「牌转过去、露出另一面」，而不是「缩一下再张开」（旧版就是后者，没有翻牌感）；</li>
     *   <li>方向来自点击瞬间记录的 {@link #flipFrom}（点背面只会翻成正面），
     *       因此服务端快照晚到一两帧也不会出现「翻到一半又翻回来」；</li>
     *   <li>进度用 smoothstep 缓动，起停不生硬；</li>
     *   <li>返回的缩放供调用方做透视压缩（横向缩放 + 轻微纵向收窄），
     *       配合转向压暗 {@link #flipShade} 与翻面后的扫光 {@link #drawFlipSheen} 就是完整的翻牌。</li>
     * </ol>
     */
    private Flip flipState(int index, boolean serverFlipped, float partialTick) {
        Integer start = this.flipStart.get(index);
        if (start == null) {
            return new Flip(1.0F, serverFlipped, 1.0F);
        }
        float raw = Mth.clamp((this.tickCounter + partialTick - start) / FLIP_TICKS, 0.0F, 1.0F);
        if (raw >= 1.0F) {
            return new Flip(1.0F, serverFlipped, 1.0F);
        }
        boolean from = this.flipFrom.getOrDefault(index, serverFlipped);
        boolean to = !from;
        float eased = raw * raw * (3.0F - 2.0F * raw);
        float scale = Math.max(0.04F, Math.abs((float) Math.cos(eased * Math.PI)));
        return new Flip(scale, raw < 0.5F ? from : to, raw);
    }

    /** 翻牌时的透视压缩：牌越接近侧面，纵向也稍稍收窄一点（纯横向压缩看起来像被挤扁，不是转动）。 */
    private static float flipSquash(float scale) {
        return 1.0F - 0.10F * (1.0F - scale);
    }

    /** 翻牌转向时的压暗色：牌越接近侧面越暗，模拟纸牌离开光源，翻起来更有实体感。 */
    private static int flipShade(float scale) {
        int shade = (int) (0x66 * (1.0F - scale));
        return shade <= 6 ? 0 : IOStyle.withAlpha(0x000000, Math.min(0x78, shade));
    }

    /**
     * 翻面后半段扫过的一道光：从一侧扫到另一侧并逐渐变淡，
     * 观感就是「牌翻过来了、反了一下光」，是翻牌最有辨识度的一笔。
     */
    private void drawFlipSheen(GuiGraphics g, int x, int y, int w, int h, float progress) {
        if (progress < 0.5F || progress >= 1.0F) {
            return;
        }
        float t = (progress - 0.5F) * 2.0F;
        int peak = (int) ((1.0F - t) * 0.30F * 255.0F);
        if (peak <= 6) {
            return;
        }
        int band = Math.max(16, w / 6);
        int startX = x - band + (int) ((w + band * 2) * t);
        int half = band / 2;
        for (int i = 0; i < band; i++) {
            int px = startX + i;
            if (px < x || px >= x + w) {
                continue;
            }
            float k = 1.0F - Math.abs((i - half) / (float) Math.max(1, half));
            int alpha = (int) (peak * k * k);
            if (alpha > 3) {
                g.fill(px, y + 3, px + 1, y + h - 3, IOStyle.withAlpha(0xFFFFFF, alpha));
            }
        }
    }

    public ShopScreen(String initialTab) {
        super(Component.translatable("gui.module_shop.title"));
        // 服务端没指定页签时沿用上次打开的页签，避免被弹回“每日商店”。
        this.currentTab = initialTab == null || initialTab.isEmpty() ? ClientShopData.lastTab : initialTab;
    }

    public void onDataUpdated() {
        // 翻牌状态现在由服务端持久化（需求 11），这里保留本地动画进度即可。
    }

    @Override
    protected void init() {
        super.init();
        // 读取客户端保存的日间 / 暗色主题（config/module_shop_ui_theme.txt）。
        UITheme.ensureLoaded();
        // 还原上次的页数与编辑模块：服务端每次操作后会重建界面（编辑条目、买卖等），
        // 不还原的话管理员改完一条就被弹回第一页，翻页很痛苦。
        this.auctionPage = ClientShopData.lastAuctionPage;
        this.adminPage = ClientShopData.lastAdminPage;
        this.mailPage = ClientShopData.lastMailPage;
        this.adminMode = ClientShopData.lastAdminMode;
        // 管理页只能由 /shop admin 指令打开，不作为可见页签，但打开后不能被重置掉。
        // 当前页签已被移除（例如把「每日商店」删掉）时，退回到左侧第一个可见模块，避免停在一个不存在的页面上。
        if (!"admin".equals(currentTab) && !visibleTabs().contains(currentTab)) {
            List<String> tabs = visibleTabs();
            currentTab = tabs.isEmpty() ? TAB_IDS[0] : tabs.get(0);
        }
        // 需求 11：进入管理页时把当前编辑模块同步给服务端，物品放入时据此归属列表。
        if ("admin".equals(currentTab)) {
            sendAdminType();
        }
        // 需求 10：界面已打开即视为已读，清除当前模块红点。
        clearTabNotification(currentTab);
        // 购物城：搜索框（真正的输入框，只有这一页显示；位置与可见性每帧在 render 里刷新）。
        this.mallSearchBox = new net.minecraft.client.gui.components.EditBox(this.font, 0, 0, 140, MALL_CTRL_H,
                Component.translatable("gui.module_shop.mall.search_hint"));
        this.mallSearchBox.setBordered(false);
        this.mallSearchBox.setMaxLength(32);
        this.mallSearchBox.setHint(Component.translatable("gui.module_shop.mall.search_hint"));
        this.mallSearchBox.setValue(ClientShopData.mallSearch == null ? "" : ClientShopData.mallSearch);
        this.mallSearchBox.setResponder(text -> {
            ClientShopData.mallSearch = text;
            ClientShopData.mallPage = 0;
        });
        this.mallSearchBox.setVisible(false);
        this.addRenderableWidget(this.mallSearchBox);
        // 交易 / 转账：玩家搜索框（同一个框两页共用；这两页的内容区就是「在线玩家列表」）。
        this.tradeSearchBox = new net.minecraft.client.gui.components.EditBox(this.font, 0, 0, 268, 13,
                Component.translatable("gui.module_shop.trade.search_hint"));
        this.tradeSearchBox.setBordered(false);
        this.tradeSearchBox.setMaxLength(32);
        this.tradeSearchBox.setHint(Component.translatable("gui.module_shop.trade.search_hint"));
        this.tradeSearchBox.setResponder(text -> this.tradePage = 0);
        this.tradeSearchBox.setVisible(false);
        this.addRenderableWidget(this.tradeSearchBox);
    }

    private void sendAdminType() {
        CompoundTag tag = new CompoundTag();
        tag.putString("action", "admin_set_edit_type");
        tag.putString("type", adminMode);
        ServerboundShopActionPayload.send(tag);
    }

    @Override
    public void tick() {
        super.tick();
        tickCounter++;
        // 把界面状态记到客户端缓存：服务端重建界面时按这里恢复页数与页签。
        ClientShopData.lastTab = currentTab;
        ClientShopData.lastAuctionPage = auctionPage;
        ClientShopData.lastAdminPage = adminPage;
        ClientShopData.lastMailPage = mailPage;
        ClientShopData.lastAdminMode = adminMode;
        ClientShopData.adminModeLabel = tabLabelWithOriginal(adminMode).getString();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private List<String> visibleTabs() {
        List<String> all = allModuleIds();
        List<String> ordered = new ArrayList<>();
        for (String id : ClientShopData.sidebarOrder) {
            if (all.contains(id) && !ordered.contains(id) && !ClientShopData.sidebarHidden.contains(id)) {
                ordered.add(id);
            }
        }
        // 自定义顺序里没有的（新增页签 / 老存档 / 后续版本加的新模块）按默认顺序补在后面；
        // 只有被管理员**显式移除**过的才不显示（见 hidden）。
        for (String id : all) {
            if (!ordered.contains(id) && !ClientShopData.sidebarHidden.contains(id)) {
                ordered.add(id);
            }
        }
        if (ordered.isEmpty()) {
            ordered.add(TAB_IDS[0]);
        }
        return ordered;
    }

    private static String tabKey(String id) {
        return switch (id) {
            case "shop" -> "tab.module_shop.daily";
            case "daily" -> "tab.module_shop.daily";
            case "buyback" -> "tab.module_shop.buyback";
            case "mall" -> "tab.module_shop.mall";
            case "shipping" -> "tab.module_shop.shipping";
            case "auction" -> "tab.module_shop.auction";
            case "stall" -> "tab.module_shop.stall";
            case "demand" -> "tab.module_shop.demand";
            case "mail" -> "tab.module_shop.mail";
            case "exchange" -> "tab.module_shop.exchange";
            case "signin" -> "tab.module_shop.signin";
            case "trade" -> "tab.module_shop.trade";
            case "transfer" -> "tab.module_shop.transfer";
            case "admin" -> "tab.module_shop.admin";
            default -> id;
        };
    }

    /** 模块的**原始**名字（lang 里的默认翻译名，改名后仍然用它做括号里的说明）。 */
    private static Component tabOriginal(String id) {
        return Component.translatable(tabKey(id));
    }

    /**
     * 模块显示名：一律用语言文件里的默认名。
     *
     * <p>「右键重命名模块」这个功能已经去掉了：模块名和商店主标题不再混在一起，
     * 只保留左上角「模块商店」主标题可以改（编辑布局 → 右键主标题）。
     */
    private static Component tabLabel(String id) {
        return tabOriginal(id);
    }

    /** 管理语境下的模块名（同样只用默认名）。 */
    private static Component tabLabelWithOriginal(String id) {
        return tabOriginal(id);
    }

    private int left() {
        return (this.width - PANEL_W) / 2;
    }

    private int top() {
        return (this.height - PANEL_H) / 2;
    }

    /** 侧栏列表可用到的最大 y（玩家信息块上方留 4px）。 */
    private int profileTopRowY() {
        return PANEL_H - PROFILE_H - 10;
    }

    /**
     * 左侧菜单一屏能放几行。
     *
     * <p>最多 {@link #SIDEBAR_ROWS} 行（需求：同时显示 6 个模块），装不下的靠滚轮上下滑 ——
     * 滚轮上限见 {@link #sidebarMaxScroll()}，渲染、滚轮、拖拽重排三处都用它，保证「画在这里、点在别处」不会发生。
     * 管理员的「商店设置」入口在**右上角药丸**里（不再占侧栏一行），所以这里不需要预留。
     */
    private static int sidebarRowsFit() {
        int fit = (PANEL_H - PROFILE_H - 10 - GRID_Y) / (TAB_H + GRID_GAP_Y);
        return Math.max(1, Math.min(SIDEBAR_ROWS, fit));
    }

    /**
     * 侧栏可滚动的上限（行数）。
     *
     * <p>普通模式：模块数 - 一屏行数；编辑模式末尾还要留一个空槽（新模块的落点），所以多算一行。
     */
    private int sidebarMaxScroll() {
        int extra = (this.layoutEdit && ClientShopData.canAdmin) ? 1 : 0;
        return Math.max(0, visibleTabs().size() + extra - sidebarRowsFit());
    }

    /**
     * 选中的模块必须露在窗口里：切换页签（点击 / 数字键 / 商店设置）时把它滚进可视区。
     *
     * <p>只在**选中项变化的那一帧**调整，所以玩家自己滚轮看别的模块时不会被强行拉回来。
     */
    private void ensureSidebarVisible(List<String> tabs) {
        if (java.util.Objects.equals(this.sidebarEnsuredTab, currentTab)) {
            return;
        }
        this.sidebarEnsuredTab = currentTab;
        int index = tabs.indexOf(currentTab);
        if (index < 0) {
            return;
        }
        int rowsFit = sidebarRowsFit();
        if (index < this.sidebarScroll) {
            this.sidebarScroll = index;
        } else if (index >= this.sidebarScroll + rowsFit) {
            this.sidebarScroll = index - rowsFit + 1;
        }
        this.sidebarScroll = Mth.clamp(this.sidebarScroll, 0, sidebarMaxScroll());
    }

    /** 内容区几何（渲染与编辑模式命中判定共用同一套，避免「画在这里、点在别处」）。 */
    private int contentX() {
        return left() + SIDEBAR_W + 8;
    }

    private int contentY() {
        return top() + TOP_BAR_H + 2;
    }

    private int contentW() {
        return PANEL_W - SIDEBAR_W - 16;
    }

    private int contentH() {
        return PANEL_H - TOP_BAR_H - 8;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.regions.clear();
        this.moreBelow = false;
        this.scrollFadeBottom = Integer.MIN_VALUE;
        // JEI 桥接：每帧先清空，只有真的画了物品 tooltip 的地方才会重新赋值。
        this.jeiHover = net.minecraft.world.item.ItemStack.EMPTY;
        this.pendingItemTip = ItemStack.EMPTY;
        this.pointerX = mouseX;
        this.pointerY = mouseY;
        // 每帧兜一层：当前页签被移除（自己删的，或别的管理员热同步过来）时，
        // 立刻退到左侧第一个可见模块，绝不停在一个侧栏里已经不存在的页面上。
        if (!"admin".equals(currentTab) && !visibleTabs().contains(currentTab)) {
            List<String> tabs = visibleTabs();
            currentTab = tabs.isEmpty() ? TAB_IDS[0] : tabs.get(0);
        }
        // 主题取色：每帧刷新，切换日间/暗色后本界面所有文字立即跟随。
        COLOR_TEXT = Theme.text();
        COLOR_MUTED = Theme.muted();
        COLOR_GOLD = Theme.gold();
        // 购物城搜索框：位置跟着面板走；只有购物城页、非编辑模式、非「最近购买」视图时显示。
        if (this.mallSearchBox != null) {
            this.mallSearchBox.setPosition(contentX() + 5, contentY() + 4);
            this.mallSearchBox.setVisible("mall".equals(currentTab) && !this.layoutEdit
                    && !ClientShopData.mallViewRecent);
        }
        if (this.tradeSearchBox != null) {
            this.tradeSearchBox.setPosition(contentX() + 5, contentY() + 2);
            this.tradeSearchBox.setVisible(("trade".equals(currentTab) || "transfer".equals(currentTab))
                    && !this.layoutEdit);
        }
        super.render(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        boolean editing = this.layoutEdit && ClientShopData.canAdmin;
        // 扁平直角版式：整块面板，左栏（导航）比右栏（内容）深一档。
        Bricks.panel(g, x, y, PANEL_W, PANEL_H);
        IOStyle.sections(g, x, y, PANEL_W, PANEL_H, SIDEBAR_W);
        drawSidebar(g, x, y, mouseX, mouseY);
        drawProfileBlock(g, x, y, mouseX, mouseY);
        int contentX = contentX();
        int contentY = contentY();
        int contentW = contentW();
        int contentH = contentH();
        if (editing) {
            // 编辑模式：**不画内容区与表头**（否则物品图标走的是另一套渲染批次，会浮在毛玻璃之上），
            // 整块毛玻璃 + 工具条 + 模块管理占满右侧，做到真正的「界面分级」。
            drawLayoutOverlay(g, x, y, contentX, contentY, contentW, contentH, mouseX, mouseY);
            if (this.dragTab != null && this.dragMoved) {
                float[] free = dragTabPixel();
                drawOneTab(g, this.dragTab, x + Math.round(free[0]), y + Math.round(free[1]), TAB_W, TAB_H,
                        false, false, false, true, true, mouseX, mouseY);
            }
            return;
        }
        drawHeader(g, x, y, contentX, contentY, contentW, mouseX, mouseY);
        switch (currentTab) {
            case "daily" -> drawDaily(g, contentX, contentY, contentW, contentH, mouseX, mouseY, partialTick);
            case "buyback" -> drawBuyback(g, contentX, contentY, contentW, contentH, mouseX, mouseY);
            case "mall" -> drawMall(g, contentX, contentY, contentW, contentH, mouseX, mouseY);
            case "shipping" -> drawShipping(g, contentX, contentY, contentW, contentH, mouseX, mouseY);
            case "auction" -> drawAuction(g, contentX, contentY, contentW, contentH, mouseX, mouseY);
            case "stall" -> drawStall(g, contentX, contentY, contentW, contentH, mouseX, mouseY);
            case "demand" -> drawDemand(g, contentX, contentY, contentW, contentH, mouseX, mouseY);
            case "mail" -> drawMail(g, contentX, contentY, contentW, contentH, mouseX, mouseY);
            case "exchange" -> drawExchange(g, contentX, contentY, contentW, contentH, mouseX, mouseY);
            case "signin" -> drawSignIn(g, contentX, contentY, contentW, contentH, mouseX, mouseY);
            case "trade" -> drawPlayerPicker(g, contentX, contentY, contentW, contentH, mouseX, mouseY, false);
            case "transfer" -> drawPlayerPicker(g, contentX, contentY, contentW, contentH, mouseX, mouseY, true);
            case "admin" -> drawAdmin(g, contentX, contentY, contentW, contentH, mouseX, mouseY);
            default -> {
            }
        }
        if (false) {
            int[] slot = new int[]{0, 0};
        }
        // 需求 ④：还有内容没显示完时，在列表下方那条空档里画「直边倒三角」提示。
        // 各模块上报自己的列表底边，再加 10px 落到空档里；上限是底栏顶边，所以永远不会压到按钮。
        if (this.moreBelow && !this.layoutEdit) {
            int bandBottom = this.scrollFadeBottom == Integer.MIN_VALUE
                    ? barTop(contentY, contentH) : this.scrollFadeBottom + 10;
            drawScrollHint(g, contentX, contentW, Math.min(bandBottom, barTop(contentY, contentH)));
        }
        // 物品悬浮框：留到整个界面画完再画（原版样式与 JEI 快捷键都基于当前悬停的物品）。
        if (!this.pendingItemTip.isEmpty()) {
            g.renderTooltip(this.font, this.pendingItemTip, mouseX, mouseY);
            this.pendingItemTip = ItemStack.EMPTY;
        }
    }

    /**
     * 左下角玩家信息块（OneConfig 的 profile 角标）：头像 + 名字 + 余额。
     * <p>
     * 点余额 = 打开账单；提醒音效与「商店设置」都在右上角标题行（本块右侧不再放按钮，文字用满整块宽度）。
     */
    private void drawProfileBlock(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        int bx = x + 7;
        int by = y + PANEL_H - PROFILE_H - 6;
        int bw = SIDEBAR_W - 12;
        IOStyle.rounded(g, bx, by, bw, PROFILE_H, 0, IOStyle.CARD);
        IOStyle.ring(g, bx, by, bw, PROFILE_H, 0, IOStyle.BORDER_DIM);
        // 头像：往下挪 2px，和右侧「名字 + 余额」两行**垂直居中**对齐（原来偏上 1px，看着像没对齐）。
        int avatarSize = 18;
        int avatarX = bx + 5;
        int avatarY = by + 7;
        iosAvatar(g, avatarX, avatarY, avatarSize);
        int textX = avatarX + avatarSize + 5;
        // 右侧不再放按钮（齿轮已移到右上角标题行），文字可以用满整块宽度。
        int textMax = bx + bw - 5 - textX;
        String name = ClientShopData.playerName.isEmpty() ? "-" : ClientShopData.playerName;
        g.drawString(this.font, IOStyle.ellipsize(this.font, Component.literal(name), textMax).getString(),
                textX, by + 6, COLOR_TEXT, false);
        // 余额（第二行，强调色）：点它进账单。
        // 用 **0.8 倍字号**绘制：数字再长也稳稳在信息块边框以内（以前 1.0 倍字号 + 省略号，
        // 看着像是顶到了边框上）；可用宽度也按缩放折算，能显示的数字更多。
        Component balanceText = Component.translatable("gui.module_shop.balance_prefix",
                ClientShopData.money(ClientShopData.balance));
        int balanceY = by + 19;
        int balanceMax = (int) (textMax / BALANCE_TEXT);
        String shown = IOStyle.ellipsize(this.font, balanceText, Math.max(20, balanceMax)).getString();
        int balanceW = Math.max(10, (int) (this.font.width(shown) * BALANCE_TEXT));
        boolean balanceHover = IOStyle.inside(textX - 2, balanceY - 2, balanceW + 4, 11, mouseX, mouseY);
        if (ClientShopData.balance != this.lastShownBalance) {
            if (this.lastShownBalance != Long.MIN_VALUE) {
                IOStyle.flash("balance");
            }
            this.lastShownBalance = ClientShopData.balance;
        }
        IOStyle.flashGlowScaled(g, this.font, Component.literal(shown), textX, balanceY, BALANCE_TEXT,
                IOStyle.BLUE, "balance");
        if (balanceHover) {
            // 按需求：余额悬停**只显示**「点击查看账单明细」一行。
            // 唯一例外：编辑布局模式（管理员）多一行「右键修改货币名称」，否则这个入口没人能发现。
            if (this.layoutEdit && ClientShopData.canAdmin) {
                java.util.List<Component> tipLines = new java.util.ArrayList<>();
                tipLines.add(Component.translatable("gui.module_shop.bill.open_tip"));
                tipLines.add(Component.translatable("gui.module_shop.currency_hint"));
                g.renderComponentTooltip(this.font, tipLines, mouseX, mouseY);
            } else {
                g.renderTooltip(this.font, Component.translatable("gui.module_shop.bill.open_tip"),
                        mouseX, mouseY);
            }
        }
        if (this.layoutEdit && ClientShopData.canAdmin) {
            addRegion(textX - 2, balanceY - 2, balanceW + 4, 11, null, "rename_currency", null);
        } else {
            addRegion(textX - 2, balanceY - 2, balanceW + 4, 11, "open_bill", null, null);
        }
    }

    /** 头像：圆角方框 + 玩家皮肤（拿不到皮肤时退回纯色块）。 */
    private void iosAvatar(GuiGraphics g, int avatarX, int avatarY, int avatarSize) {
        IOStyle.rounded(g, avatarX - 1, avatarY - 1, avatarSize + 2, avatarSize + 2, 6, 0x33FFFFFF);
        var player = Minecraft.getInstance().player;
        if (player != null) {
            net.minecraft.client.gui.components.PlayerFaceRenderer.draw(g, player.getSkin(), avatarX, avatarY,
                    avatarSize);
        } else {
            IOStyle.rounded(g, avatarX, avatarY, avatarSize, avatarSize, 4, IOStyle.GRAY_BTN);
        }
    }

    /**
     * 侧栏页签用的名字：改名后可能超宽，这里按页签宽度截断（超出用 …），绝不画到边框外面。
     */
    private Component fitTabLabel(String id, int tabW) {
        Component label = tabLabel(id);
        String plain = label.getString();
        int room = tabW - 17;   // 左边距 + 右手小空隙（编辑模式还有抓手）
        if (this.font.width(plain) <= room) {
            return label;
        }
        return Component.literal(this.font.plainSubstrByWidth(plain, Math.max(1, room - 5)) + "…");
    }

    /** 管理员（编辑布局中）：改**货币名称**（全服生效，留空 = 恢复配置里的默认值）。 */
    private void openCurrencyRename() {
        CompoundTag req = new CompoundTag();
        req.putString("default_name", ClientShopData.currencyName());
        req.putString("info", Component.translatable("gui.module_shop.currency_info",
                Integer.toString(ShopManager.CURRENCY_NAME_MAX)).getString());
        openInput("admin_currency_set", "gui.module_shop.input.currency", req, "name");
    }
    /** 管理员：改左上角**主标题**（留空 = 恢复默认「模块商店」）。 */
    private void openShopTitleRename() {
        CompoundTag req = new CompoundTag();
        String current = ClientShopData.shopTitle == null ? "" : ClientShopData.shopTitle;
        req.putString("default_name", current);
        req.putString("info", Component.translatable("gui.module_shop.shop_title_info",
                Integer.toString(SidebarLayout.MAX_TITLE)).getString());
        openInput("admin_shop_title_set", "gui.module_shop.input.shop_title", req, "name");
    }

    /** 内容区顶部标题行：当前页标题 + 右侧「商店设置（管理员）/ 签到 / 交易 / 转账」药丸 + 提醒齿轮 + 关闭。 */
    private void drawHeader(GuiGraphics g, int x, int y, int contentX, int contentY, int contentW,
            int mouseX, int mouseY) {
        int titleY = y + 10;
        // 管理页的标题带上"当前编辑的模块"，这样顶部那一行就能腾给功能按钮（发放物品 / 交易记录）。
        Component headerTitle = "admin".equals(currentTab)
                ? Component.translatable("gui.module_shop.admin_title",
                        tabLabelWithOriginal(adminMode))
                : tabLabel(currentTab);
        g.drawString(this.font, headerTitle, contentX, titleY, COLOR_TEXT, false);
        // 关闭（右上角的 ×）：直角方块 + 1px 硬边框
        int closeX = x + PANEL_W - 22;
        int closeY = y + 7;
        boolean closeHover = IOStyle.inside(closeX, closeY, 14, 14, mouseX, mouseY);
        IOStyle.rounded(g, closeX, closeY, 14, 14, 0, closeHover ? IOStyle.GRAY_BTN_HOVER : IOStyle.GRAY_BTN);
        IOStyle.ring(g, closeX, closeY, 14, 14, 0, IOStyle.BORDER_DIM);
        int cc = closeHover ? COLOR_TEXT : COLOR_MUTED;
        for (int i = 0; i < 6; i++) {
            g.fill(closeX + 4 + i, closeY + 4 + i, closeX + 5 + i, closeY + 5 + i, cc);
            g.fill(closeX + 4 + i, closeY + 9 - i, closeX + 5 + i, closeY + 10 - i, cc);
        }
        addRegion(closeX, closeY, 14, 14, "close_ui", null, null);
        // 右上角齿轮：提醒音效设置（从右往左排，与药丸区不会重叠）
        int gearBtnX = closeX - 20;
        boolean gearHover = IOStyle.inside(gearBtnX, closeY, 14, 14, mouseX, mouseY);
        IOStyle.rounded(g, gearBtnX, closeY, 14, 14, 0, gearHover ? IOStyle.GRAY_BTN_HOVER : IOStyle.GRAY_BTN);
        IOStyle.ring(g, gearBtnX, closeY, 14, 14, 0, IOStyle.BORDER_DIM);
        Icons.bell(g, gearBtnX + 1, closeY + 1, gearHover ? IOStyle.BLUE : IOStyle.TEXT);
        addRegion(gearBtnX, closeY, 14, 14, "notif_settings", null, null);
        if (gearHover) {
            g.renderTooltip(this.font, Component.translatable("gui.module_shop.notify.settings_title"),
                    mouseX, mouseY);
        }
        // 右侧分类药丸：管理员「编辑布局」+「商店设置」—— 签到/交易/转账 已改为左侧模块，不再占顶部。
        int pillH = 16;
        int pillY = y + 6;
        int px = closeX - 20 - 6;
        java.util.List<String> actions = new java.util.ArrayList<>();
        if (ClientShopData.canAdmin) {
            actions.add("sidebar_edit");
            actions.add("admin_open");
        }
        for (String action : actions) {
            String key = switch (action) {
                case "sidebar_edit" -> "gui.module_shop.layout_edit";
                case "admin_open" -> "gui.module_shop.admin_open";
                default -> "gui.module_shop.admin_open";
            };
            int w = this.font.width(Component.translatable(key)) + 14;
            px -= w;
            boolean hover = IOStyle.inside(px, pillY, w, pillH, mouseX, mouseY);
            boolean selected = "sidebar_edit".equals(action)
                    ? this.layoutEdit
                    : ("admin".equals(currentTab) && "admin_open".equals(action));
            IOStyle.pill(g, this.font, px, pillY, w, pillH, Component.translatable(key), selected, hover, true);
            addRegion(px, pillY, w, pillH, action, null, null);
            if ("admin_open".equals(action)) {
                ClientNotifications.drawBadge(g, this.font, NotificationType.MAIL, px + w, pillY);
            }
            px -= 4;
        }
    }

    /**
     * 旧写法：用 128×128 的 textures/gui/gear.png 贴图画齿轮。
     *
     * <p>⚠ 这张贴图**并没有随模组发布**，所以当年右上角那个按钮一直是空白的。
     * 现在齿轮统一用 {@link Icons#gear} 的像素矩阵手绘，本方法已无人调用，保留仅为对照。
     */
    @SuppressWarnings("unused")
    private void drawGear(GuiGraphics g, int x, int y) {
        g.blit(GEAR_ICON, x, y, 12, 12, 0.0F, 0.0F, GEAR_TEX_SIZE, GEAR_TEX_SIZE,
                GEAR_TEX_SIZE, GEAR_TEX_SIZE);
    }

    /**
     * 侧栏（OneConfig 的左栏列表）：品牌行 + 分组标题 + 「图标 + 文字」条目。
     * <p>
     * 顺序来自服务端（{@code ClientShopData.sidebarOrder}），坐标由下标 + **它上方的分组标题数**推导 ——
     * 所以永远落在网格槽位上，没有像素级偏移；拖动时按「最近槽位」实时重排，被挤开的条目用 {@link Mth#lerp} 平滑滑动。
     */
    private void drawSidebar(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        // 品牌行：纯文字（按需求不要前缀图标）
        // 品牌行：纯文字，**在侧栏宽度内水平居中**；管理员可**右键改名**（全服生效的主标题）。
        Component brand = ClientShopData.shopTitle == null || ClientShopData.shopTitle.isBlank()
                ? Component.translatable("gui.module_shop.title")
                : Component.literal(ClientShopData.shopTitle);
        int brandX = x + (SIDEBAR_W - this.font.width(brand)) / 2;
        g.drawString(this.font, brand, brandX, y + 8, IOStyle.BLUE, false);
        if (this.layoutEdit && ClientShopData.canAdmin) {
            // 主标题改名只在「编辑布局」里可点：平时右键标题什么都不发生，避免误触把全服标题改掉。
            addRegion(brandX - 2, y + 6, this.font.width(brand) + 4, 12, null, "rename_shop_title", null);
            if (IOStyle.inside(brandX - 2, y + 6, this.font.width(brand) + 4, 12, mouseX, mouseY)) {
                g.renderTooltip(this.font, Component.translatable("gui.module_shop.shop_title_hint", brand),
                        mouseX, mouseY);
            }
        }

        this.tabRects.clear();
        List<String> tabs = visibleTabs();
        boolean editing = this.layoutEdit && ClientShopData.canAdmin;
        int rowsFit = sidebarRowsFit();
        int pitch = TAB_H + GRID_GAP_Y;
        ensureSidebarVisible(tabs);
        this.sidebarScroll = Mth.clamp(this.sidebarScroll, 0, sidebarMaxScroll());
        float dt = this.frameDeltaSeconds();
        float k = approach(dt, 20.0F);
        // 滚动位置：目标永远落在整行上，中间过程用像素补间 —— 滚起来是「整列滑动」，停下时又严丝合缝。
        float targetPx = this.sidebarScroll * (float) pitch;
        if (this.sidebarScrollPx < 0.0F) {
            this.sidebarScrollPx = targetPx;
        }
        float distance = targetPx - this.sidebarScrollPx;
        // 滚得多就滑得快一点（同样的"到达时间"，但不会一格一格慢慢挪），小位移保留一点拖尾。
        float scrollRate = 26.0F * (1.0F + Math.min(0.6F, Math.abs(distance) / pitch * 0.12F));
        this.sidebarScrollPx += distance * approach(dt, scrollRate);
        if (Math.abs(targetPx - this.sidebarScrollPx) < 0.02F) {
            this.sidebarScrollPx = targetPx;
        }
        // 滚动条先画（在条目下面），这样通知红点 / 编辑描边不会被它压掉。
        drawSidebarScrollBar(g, x, y, tabs, rowsFit, editing);
        // 列表带：第 0 行的顶边到第 rowsFit-1 行的底边；滑进来的行在这里被裁掉，不会蹭到玩家信息块。
        int bandTop = y + GRID_Y;
        int bandBottom = bandTop + rowsFit * pitch - GRID_GAP_Y;
        boolean scissor = false;
        // 上下各多画一行：滚动过程中它们是「正要滑进来 / 正在滑出去」的行。
        int first = Math.max(0, (int) Math.floor(this.sidebarScrollPx / pitch) - 1);
        int last = Math.min(tabs.size() - 1,
                (int) Math.ceil((this.sidebarScrollPx + rowsFit * (float) pitch) / pitch));
        for (int i = first; i <= last; i++) {
            String id = tabs.get(i);
            float slot = easeSlot(id, i, k);
            float px = x + slotX(0);
            float py = bandTop + slot * pitch - this.sidebarScrollPx;
            if (id.equals(this.dragTab) && this.dragMoved && !Float.isNaN(this.dragFreeX)) {
                px = x + this.dragFreeX;   // 拖拽中：横向跟着鼠标（纵向已在 easeSlot 里换成行号了）
            }
            int tabX = Math.round(px);
            int ty = Math.round(py);
            if (ty > bandBottom || ty + TAB_H < bandTop) {
                continue;   // 完全在带子外面，不必画（也不用开裁剪）
            }
            if (!scissor) {
                // 严格裁到列表带：上下都不放宽，滚动过程中不会露出"半条"旧行的边。
                // （通知红点画在条目内部，所以不需要为它放宽裁剪区。）
                g.enableScissor(x + 1, bandTop, x + SIDEBAR_W - 1, bandBottom + 1);
                scissor = true;
            }
            boolean selected = id.equals(currentTab);
            boolean hover = !selected && !editing && IOStyle.inside(tabX, ty, TAB_W, TAB_H, mouseX, mouseY);
            boolean dragging = id.equals(this.dragTab) && this.dragMoved;
            drawOneTab(g, id, tabX, ty, TAB_W, TAB_H, selected, hover, !dragging, editing, dragging, mouseX, mouseY);
        }
        // 列表末尾预留的空槽（新模块默认落到这里），画一个虚线感的空框。
        // 只有它正好落在可视窗口里才画——否则会盖住第 6 个模块（滚到底部时才露出来）。
        if (editing) {
            float emptyY = bandTop + tabs.size() * (float) pitch - this.sidebarScrollPx;
            int emptyIY = Math.round(emptyY);
            if (emptyIY >= bandTop && emptyIY + TAB_H <= bandBottom + 1) {
                if (!scissor) {
                    g.enableScissor(x + 1, bandTop, x + SIDEBAR_W - 1, bandBottom + 1);
                    scissor = true;
                }
                IOStyle.rounded(g, x + GRID_X, emptyIY, TAB_W, TAB_H, 0, IOStyle.SIDEBAR);
                IOStyle.ring(g, x + GRID_X, emptyIY, TAB_W, TAB_H, 0, IOStyle.BORDER_DIM);
                IOStyle.text(g, this.font, Component.translatable("gui.module_shop.layout_empty_slot"),
                        x + GRID_X + 9, emptyIY + (TAB_H - 8) / 2, IOStyle.TEXT_3);
            }
        }
        if (scissor) {
            g.disableScissor();
        }
        // 管理页入口只保留右上角那枚「商店设置」药丸（管理员可见）：
        // 侧栏这一行删掉了——模块多起来时它会和最后一个模块画在同一行、挡住对方。
    }

    /** 本帧时长（秒，已夹到 0.1s 以内）：所有动画都用它算，保证帧率无关。 */
    private float frameDeltaSeconds() {
        long now = System.nanoTime();
        float dt = this.lastFrameNanos == 0L ? 0.016F
                : Math.min(0.1F, (now - this.lastFrameNanos) / 1_000_000_000.0F);
        this.lastFrameNanos = now;
        return dt;
    }

    /** 指数逼近系数：{@code 1 - e^(-dt*rate)} —— 帧率无关，30 FPS 和高刷屏手感一致。 */
    private static float approach(float dt, float rate) {
        return 1.0F - (float) Math.exp(-dt * rate);
    }

    /** 某个页签当前所在的「行号」（小数）：逐帧朝它在列表里的真实下标靠拢。 */
    private float easeSlot(String id, int index, float k) {
        Float current = this.tabSlotAnim.get(id);
        if (current == null) {
            this.tabSlotAnim.put(id, (float) index);
            return index;
        }
        if (id.equals(this.dragTab) && this.dragMoved) {
            return current;   // 拖拽中：位置由 mouseDragged 直接写（换算成行号）
        }
        float next = current + (index - current) * k;
        if (Math.abs(next - index) < 0.002F) {
            next = index;
        }
        this.tabSlotAnim.put(id, next);
        return next;
    }

    /**
     * 侧栏滚动条 + 「还有几个模块」小字提示（只在模块多于一屏时出现）。
     *
     * <p>模块上限 6 行，第 7 个起要靠滚轮 —— 光靠滚轮玩家不知道下面还有东西，所以给一条 2px 细条。
     * 滑块位置用**补间后的像素位置**（{@link #sidebarScrollPx}），所以滚动动画期间滑块也是连续移动的。
     */
    private void drawSidebarScrollBar(GuiGraphics g, int x, int y, List<String> tabs, int rowsFit, boolean editing) {
        int maxScroll = sidebarMaxScroll();
        if (maxScroll <= 0) {
            return;
        }
        int pitch = TAB_H + GRID_GAP_Y;
        int trackX = x + SIDEBAR_W - 5;
        int trackY = y + GRID_Y;
        int trackH = rowsFit * pitch - GRID_GAP_Y;
        g.fill(trackX, trackY, trackX + 2, trackY + trackH, IOStyle.withAlpha(IOStyle.BORDER_DIM, 190));
        int rows = tabs.size() + (editing ? 1 : 0);
        int thumbH = Math.max(12, trackH * rowsFit / Math.max(1, rows));
        float progress = Mth.clamp(this.sidebarScrollPx / (maxScroll * (float) pitch), 0.0F, 1.0F);
        int thumbY = trackY + Math.round((trackH - thumbH) * progress);
        g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, IOStyle.BLUE);
    }


    /** 侧栏分组（顺序由管理员拖拽决定，标题跟着走）。 */
    private static String tabGroup(String id) {
        return switch (id) {
            case "daily", "buyback" -> "gui.module_shop.sidebar_group_shop";
            case "auction", "stall", "demand" -> "gui.module_shop.sidebar_group_market";
            case "mail", "exchange" -> "gui.module_shop.sidebar_group_mine";
            default -> "";
        };
    }

    /** 第 index 个槽位的 y：网格 + 它上方出现过的分组标题高度（标题数只算每个分组的第一次）。 */
    private int slotYAt(List<String> order, int index) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        int labels = 0;
        for (int i = 0; i < index && i < order.size(); i++) {
            String group = tabGroup(order.get(i));
            if (!group.isEmpty() && seen.add(group)) {
                labels++;
            }
        }
        return GRID_Y + labels * LABEL_H + index * (TAB_H + GRID_GAP_Y);
    }

    /** 网格：第 i 个槽位的面板内 x（1 列 N 行；想改成 2 列只要把 TAB_COLS 改成 2）。 */
    private static int slotX(int index) {
        return GRID_X + (index % TAB_COLS) * (TAB_W + GRID_GAP_X);
    }

    /**
     * 鼠标位置对应的槽位下标（拖动重排 / 拖入加入用）。
     *
     * <p>直接由「补间后的像素滚动量」反算：{@code (鼠标y + 滚动px - 列表顶 - 半行高) / 行距}，
     * 取最近的一行再夹到合法范围 —— 滚动到一半时也永远落在离鼠标最近的那一行上。
     */
    private int slotIndexAt(double mouseX, double mouseY) {
        List<String> tabs = visibleTabs();
        if (tabs.isEmpty()) {
            return 0;
        }
        int pitch = TAB_H + GRID_GAP_Y;
        float scrolled = this.sidebarScrollPx < 0.0F ? this.sidebarScroll * (float) pitch : this.sidebarScrollPx;
        float row = (float) (mouseY - GRID_Y - TAB_H / 2.0D + scrolled) / pitch;
        int best = Math.round(row);
        return Mth.clamp(best, 0, tabs.size() - 1);
    }

    /**
     * 拖拽中的页签当前在**面板内**的像素位置（编辑模式把它单独重绘一次，浮在毛玻璃之上）。
     *
     * <p>纵向挂在 {@link #tabSlotAnim} 上（拖拽时 {@code mouseDragged} 把鼠标位置换算成行号写进去），
     * 所以松手后它会自动从当前位置「滑」回真实槽位，不需要另写一套回弹动画。
     */
    private float[] dragTabPixel() {
        int pitch = TAB_H + GRID_GAP_Y;
        float scrolled = Math.max(0.0F, this.sidebarScrollPx);
        float slot = this.tabSlotAnim.getOrDefault(this.dragTab, 0.0F);
        float px = Float.isNaN(this.dragFreeX) ? GRID_X : this.dragFreeX;
        return new float[]{px, GRID_Y + slot * pitch - scrolled};
    }

    /** 画一个侧栏条目（OneConfig 列表项）：图标 + 文字；选中是整条强调色实心，编辑模式带抓手与描边。 */
    private void drawOneTab(GuiGraphics g, String id, int tabX, int ty, int tabW, int tabH,
            boolean selected, boolean hover, boolean track, boolean editing, boolean dragging, int mouseX, int mouseY) {
        if (dragging) {
            // 拿起状态：放大一点点 + 投影，像手机桌面拖动中的图标。
            float cx = tabX + tabW / 2.0F;
            float cy = ty + tabH / 2.0F;
            g.pose().pushPose();
            g.pose().translate(cx, cy, 0.0F);
            g.pose().scale(1.05F, 1.05F, 1.0F);
            g.pose().translate(-cx, -cy, 0.0F);
            IOStyle.rounded(g, tabX - 2, ty + 3, tabW + 4, tabH, 8, 0x66000000);
        }
        IOStyle.navItem(g, tabX, ty, tabW, tabH, fitTabLabel(id, tabW), selected, hover, dragging);
        NotificationType badge = switch (id) {
            // 每日商店与每日收购分开计数：以前共用 SHOP，刷新时两个页签的红点会一起亮。
            case "daily" -> NotificationType.SHOP;
            case "buyback" -> NotificationType.BUYBACK;
            case "auction" -> NotificationType.AUCTION;
            case "stall" -> NotificationType.STALL;
            case "mail" -> NotificationType.MAIL;
            default -> null;
        };
        if (badge != null) {
            // 红点画在条目**内部**的上右角：列表带的裁剪现在严格对齐格子，不会把红点切掉一条边。
            ClientNotifications.drawBadge(g, this.font, badge, tabX + tabW - 2, ty + 1);
        }
        if (editing) {
            // 编辑模式：蓝色描边 + 右侧小抓手，明确"这个可以拖"。
            boolean active = hover || dragging;
            IOStyle.ring(g, tabX - 1, ty - 1, tabW + 2, tabH + 2, 8,
                    active ? IOStyle.BLUE : IOStyle.withAlpha(IOStyle.BLUE, 120));
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 2; c++) {
                    int dotX = tabX + tabW - 13 + c * 4;
                    int dotY = ty + 6 + r * 4;
                    g.fill(dotX, dotY, dotX + 2, dotY + 2, active ? IOStyle.BLUE : IOStyle.TEXT_3);
                }
            }
        }
        if (dragging) {
            g.pose().popPose();
        }
        if (track) {
            this.tabRects.add(new TabRect(id, tabX, ty, tabW, tabH));
            if (!editing) {
                CompoundTag extra = new CompoundTag();
                extra.putString("tab", id);
                addRegion(tabX, ty, tabW, tabH, "set_tab", null, extra);
            }
        }
    }

    /**
     * 编辑模式：整块毛玻璃盖住右侧 + 顶部工具条（同时是「拖到这里删除」的丢弃区）+ 下方模块管理。
     *
     * <p>几何约定（与 {@link #handleLayoutClick} **必须一致**）：
     * 工具条卡片从**面板顶边下方 4px** 起算（编辑模式不画表头，这一条区域是空的），
     * 这样下面的「模块管理」能拿到足够高度，当前 6 个模块一屏全能看见；
     * 以后模块变多也不用改布局——模块管理自带滚轮。
     */
    private void drawLayoutOverlay(GuiGraphics g, int x, int y, int contentX, int contentY, int contentW,
            int contentH, int mouseX, int mouseY) {
        // ① 毛玻璃面板：整块盖住右侧（连表头那一条也盖住，避免漏出上一层的痕迹）。
        int coverTop = y + 2;
        int coverBottom = contentY + contentH + 4;
        IOStyle.frosted(g, contentX - 6, coverTop, contentW + 12, coverBottom - coverTop);
        // ② 工具条卡片：水平居中于内容区，垂直贴着面板顶部（同时是「拖到这里删除」的丢弃区）。
        int cardW = Math.min(300, contentW - 16);
        int cardH = 50;
        int cardX = contentX + (contentW - cardW) / 2;
        int cardY = y + 4;
        // 拖拽模块悬停在这块区域上时，整块变成红色半透明的「删除区」（垃圾桶 + 提示）。
        boolean trashMode = this.dragTab != null && this.dragMoved
                && IOStyle.inside(cardX, cardY, cardW, cardH, mouseX, mouseY);
        this.trashZone[0] = cardX;
        this.trashZone[1] = cardY;
        this.trashZone[2] = cardW;
        this.trashZone[3] = cardH;
        if (trashMode) {
            IOStyle.rounded(g, cardX, cardY, cardW, cardH, 0, 0x99C0392B);
            IOStyle.ring(g, cardX, cardY, cardW, cardH, 0, IOStyle.RED);
            Icons.trash(g, cardX + cardW / 2 - 6, cardY + 8, 0xFFFFFFFF);
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.layout_delete_module"),
                    cardX + cardW / 2, cardY + 24, 0xFFFFFFFF);
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.layout_delete_hint"),
                    cardX + cardW / 2, cardY + 38, 0xFFFFD9D5);
            return;
        }
        IOStyle.card(g, cardX, cardY, cardW, cardH, 0);
        IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.layout_title"),
                cardX + cardW / 2, cardY + 6, IOStyle.TEXT);
        int btnW = 92;
        int btnH = 16;
        int gap = 8;
        int bx = cardX + (cardW - (btnW * 2 + gap)) / 2;
        // 卡片里没有说明文字了（按需求删掉），按钮往下挪一点，卡片上下留白对称。
        int by = cardY + 24;
        IOStyle.button(g, bx, by, btnW, btnH, Component.translatable("gui.module_shop.layout_done"), this.font,
                mouseX, mouseY, true, IOStyle.Btn.PRIMARY);
        IOStyle.button(g, bx + btnW + gap, by, btnW, btnH,
                Component.translatable("gui.module_shop.layout_reset"), this.font, mouseX, mouseY, true,
                IOStyle.Btn.DANGER_TINT);
        // ③ 模块管理：一张列表装下所有模块，右侧按钮「移除 / 添加」；拖到左侧列表也能加入。
        drawModuleManager(g, contentX, contentY, contentW, contentH, cardY + cardH + 6, mouseX, mouseY);
        // ④ 二次确认：删除模块前再问一次（就地弹卡，不切界面）。
        if (this.pendingDelete != null) {
            int w = 210;
            int h = 54;
            int px2 = contentX + (contentW - w) / 2;
            int py2 = contentY + (contentH - h) / 2;
            IOStyle.rounded(g, contentX - 6, coverTop, contentW + 12, coverBottom - coverTop, 0, 0x88000000);
            IOStyle.card(g, px2, py2, w, h, 0);
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.layout_delete_confirm",
                    tabLabelWithOriginal(this.pendingDelete)), px2 + w / 2, py2 + 8, IOStyle.TEXT);
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.layout_delete_warn"),
                    px2 + w / 2, py2 + 20, IOStyle.TEXT_3);
            int bw = 84;
            int bh = 16;
            int bx2 = px2 + (w - (bw * 2 + 10)) / 2;
            int by2 = py2 + h - bh - 8;
            IOStyle.button(g, bx2, by2, bw, bh, Component.translatable("gui.module_shop.layout_delete_yes"),
                    this.font, mouseX, mouseY, true, IOStyle.Btn.DANGER_TINT);
            addRegion(bx2, by2, bw, bh, "delete_yes", null, null);
            IOStyle.button(g, bx2 + bw + 10, by2, bw, bh,
                    Component.translatable("gui.module_shop.layout_delete_no"), this.font, mouseX, mouseY, true,
                    IOStyle.Btn.GRAY);
            addRegion(bx2 + bw + 10, by2, bw, bh, "delete_no", null, null);
        }
    }

    /**
     * 编辑模式下的模块管理：**所有模块一张列表**（已启用在上、已移除在下），右侧按钮切换「移除 / 加入」，
     * 未启用的行也可以**按住拖进左侧菜单**。
     *
     * <p>列表只画得下一部分时**支持滚轮下滑**（鼠标停在这块面板上滚），右侧有滚动条 + 右上角显示「当前区间 / 总数」。
     */
    private void drawModuleManager(GuiGraphics g, int contentX, int contentY, int contentW, int contentH,
            int top, int mouseX, int mouseY) {
        int panelW = contentW - 12;
        int px = contentX + 6;
        int py = top;
        int panelH = contentY + contentH - 8 - py;
        if (panelH < 50) {
            return;
        }
        IOStyle.card(g, px, py, panelW, panelH, 0);
        List<String> visible = visibleTabs();
        List<String> available = hiddenTabs();
        // 所有模块 = 已启用（按左侧顺序）+ 已移除 —— 两份都从 allModuleIds() 推导，
        // 所以**以后新增的模块会自动出现在这张列表里**，不会出现「侧栏有、模块管理里没有」。
        List<String> all = new ArrayList<>(visible);
        all.addAll(available);
        // 只剩一个模块时禁止再移除（否则侧栏空了就没有入口再加回来，服务端也会拒收空列表）。
        boolean canRemove = visible.size() > 1;
        // ---- 先算行布局，再画文字，避免右上角的区间提示与左侧说明重叠 ----
        int rowH = 16;
        int step = rowH + 1;
        // 说明文字已按需求删掉，列表直接贴着标题开始（只在「只剩一个模块」时留一行红字警告）。
        int rowY = py + (canRemove ? 16 : 26);
        int rowsBottom = py + panelH - 6;
        int rowsFit = Math.max(1, (rowsBottom - rowY) / step);
        int maxScroll = Math.max(0, all.size() - rowsFit);
        this.managerScroll = Mth.clamp(this.managerScroll, 0, maxScroll);
        this.managerRect[0] = px;
        this.managerRect[1] = py;
        this.managerRect[2] = panelW;
        this.managerRect[3] = panelH;
        int last = Math.min(all.size(), this.managerScroll + rowsFit);
        IOStyle.text(g, this.font, Component.translatable("gui.module_shop.layout_modules"),
                px + 6, py + 4, IOStyle.TEXT);
        // 右上角：当前区间 / 总数（还有更多时才显示），滚轮可翻。
        String range = (this.managerScroll + 1) + "-" + last + "/" + all.size();
        int rangeW = this.font.width(range);
        if (maxScroll > 0) {
            IOStyle.text(g, this.font, Component.literal(range), px + panelW - 8 - rangeW, py + 4, IOStyle.TEXT_3);
        }
        // 只在「只剩一个模块」时留一行红字警告：再移除侧栏就空了，没有入口加回来。
        if (!canRemove) {
            IOStyle.text(g, this.font, Component.translatable("gui.module_shop.layout_keep_one"),
                    px + 6, py + 15, IOStyle.RED);
        }
        for (int i = this.managerScroll; i < last; i++) {
            String id = all.get(i);
            int cy2 = rowY + (i - this.managerScroll) * step;
            boolean rowEnabled = visible.contains(id);
            boolean hover = IOStyle.inside(px + 6, cy2, panelW - 12, rowH, mouseX, mouseY);
            boolean dragging = id.equals(this.poolDrag);
            IOStyle.rounded(g, px + 6, cy2, panelW - 12, rowH, 0,
                    dragging ? IOStyle.BLUE : (hover ? IOStyle.ROW_HOVER : IOStyle.SIDEBAR));
            IOStyle.ring(g, px + 6, cy2, panelW - 12, rowH, 0,
                    dragging ? IOStyle.BORDER_HOVER : IOStyle.BORDER_DIM);
            IOStyle.text(g, this.font, tabLabelWithOriginal(id), px + 12, cy2 + 4,
                    dragging ? 0xFFFFFFFF : IOStyle.TEXT);
            // 右侧按钮：已在左侧 = 「移除」（红，最后一个模块时置灰）；不在左侧 = 「加入」（强调色）。
            boolean canToggle = !rowEnabled || canRemove;
            int btnW2 = 36;
            int btnX = px + panelW - 12 - btnW2;
            IOStyle.button(g, btnX, cy2 + 1, btnW2, rowH - 2,
                    Component.translatable(rowEnabled
                            ? "gui.module_shop.layout_remove" : "gui.module_shop.layout_join"),
                    this.font, mouseX, mouseY, canToggle, rowEnabled ? IOStyle.Btn.DANGER_TINT : IOStyle.Btn.PRIMARY);
            if (canToggle) {
                addRegion(btnX, cy2 + 1, btnW2, rowH - 2,
                        (rowEnabled ? "pool_remove:" : "pool_join:") + id, null, null);
            }
            if (!rowEnabled) {
                // 未启用的行：按住可以拖到左侧列表（拖到哪一格就插到哪一格）。
                addRegion(px + 6, cy2, panelW - 12 - btnW2 - 4, rowH, "pool_add:" + id, null, null);
            }
        }
        // 一屏放不下时右侧画细滚动条（与其它列表一致）。
        if (maxScroll > 0) {
            int viewH = rowsFit * step - 2;
            int trackX = px + panelW - 4;
            g.fill(trackX, rowY, trackX + 1, rowY + viewH, IOStyle.HAIR);
            int thumbH = Math.max(12, viewH * rowsFit / Math.max(1, all.size()));
            int thumbY = rowY + (viewH - thumbH) * this.managerScroll / Math.max(1, maxScroll);
            g.fill(trackX, thumbY, trackX + 1, thumbY + thumbH, IOStyle.BLUE);
        }
    }

    /** 可添加（当前被移除 / 未启用）的模块。 */
    private List<String> hiddenTabs() {
        List<String> hidden = new ArrayList<>(ClientShopData.sidebarHidden);
        List<String> out = new ArrayList<>();
        for (String id : allModuleIds()) {
            if (hidden.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** 当前渲染帧里某个页签的命中区。 */
    private TabRect tabRectOf(String id) {
        for (TabRect rect : this.tabRects) {
            if (rect.id().equals(id)) {
                return rect;
            }
        }
        return null;
    }

    private record TabRect(String id, int x, int y, int w, int h) {
    }

    /** 编辑模式下接管鼠标按下：工具条按钮 + 模块管理器 + 侧栏页签（按下先不切换，等松开时再判定点击/拖拽）。 */
    private boolean handleLayoutClick(double mouseX, double mouseY) {
        int x = left();
        int y = top();
        int contentX = contentX();
        int contentY = contentY();
        int contentW = contentW();
        int contentH = contentH();
        // 二次确认弹卡优先接管点击
        if (this.pendingDelete != null) {
            int w = 210;
            int h = 54;
            int px2 = contentX + (contentW - w) / 2;
            int py2 = contentY + (contentH - h) / 2;
            int bw = 84;
            int bh = 16;
            int bx2 = px2 + (w - (bw * 2 + 10)) / 2;
            int by2 = py2 + h - bh - 8;
            if (IOStyle.inside(bx2, by2, bw, bh, mouseX, mouseY)) {
                Bricks.click();
                removeModule(this.pendingDelete);
                this.pendingDelete = null;
                return true;
            }
            if (IOStyle.inside(bx2 + bw + 10, by2, bw, bh, mouseX, mouseY)) {
                Bricks.click();
                this.pendingDelete = null;
                return true;
            }
            return true;
        }
        // 工具条卡片的几何必须与 drawLayoutOverlay 完全一致（画在哪就点在哪）。
        int cardW = Math.min(300, contentW - 16);
        int cardH = 50;
        int cardX = contentX + (contentW - cardW) / 2;
        int cardY = y + 4;
        int btnW = 92;
        int btnH = 16;
        int gap = 8;
        int bx = cardX + (cardW - (btnW * 2 + gap)) / 2;
        int by = cardY + 24;
        if (IOStyle.inside(bx, by, btnW, btnH, mouseX, mouseY)) {
            Bricks.click();
            this.layoutEdit = false;
            return true;
        }
        if (IOStyle.inside(bx + btnW + gap, by, btnW, btnH, mouseX, mouseY)) {
            Bricks.click();
            send("admin_sidebar_reset", null);
            return true;
        }
        // 模块管理器：直接按本帧已注册的命中区判定（几何只写一份，画在哪就点在哪）。
        for (Region region : this.regions) {
            String action = region.action();
            if (action == null || !IOStyle.inside(region.x(), region.y(), region.w(), region.h(), mouseX, mouseY)) {
                continue;
            }
            if (action.startsWith("pool_join:")) {
                Bricks.click();
                addModule(action.substring("pool_join:".length()), visibleTabs().size());
                return true;
            }
            if (action.startsWith("pool_remove:")) {
                Bricks.click();
                String id = action.substring("pool_remove:".length());
                // 任何模块（含每日商店）都能移除，只要求「至少留一个」——否则侧栏空了就没入口再加回来。
                if (visibleTabs().size() > 1) {
                    removeModule(id);
                }
                return true;
            }
            if (action.startsWith("pool_add:")) {
                Bricks.click();
                this.poolDrag = action.substring("pool_add:".length());
                this.poolMoved = false;
                this.poolPressX = (int) Math.round(mouseX);
                this.poolPressY = (int) Math.round(mouseY);
                return true;
            }
        }
        for (TabRect rect : this.tabRects) {
            if (IOStyle.inside(rect.x(), rect.y(), rect.w(), rect.h(), mouseX, mouseY)) {
                Bricks.click();
                this.dragTab = rect.id();
                this.dragMoved = false;
                this.dragPressX = (int) Math.round(mouseX);
                this.dragPressY = (int) Math.round(mouseY);
                this.dragOffX = this.dragPressX - rect.x();
                this.dragOffY = this.dragPressY - rect.y();
                return true;
            }
        }
        // 编辑模式下点击别处一律吞掉，避免误触买卖/领取。
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // 从「模块库」拖模块：拖到左侧列表任意位置松手即加入。
        if (this.poolDrag != null && this.layoutEdit) {
            if (!this.poolMoved
                    && Math.abs(mouseX - this.poolPressX) + Math.abs(mouseY - this.poolPressY) > DRAG_THRESHOLD) {
                this.poolMoved = true;
            }
            return true;
        }
        if (this.dragTab != null && this.layoutEdit) {
            // 超过阈值才进入拖拽状态：这样"按住不动 / 轻微抖动"仍然算点击。
            if (!this.dragMoved
                    && Math.abs(mouseX - this.dragPressX) + Math.abs(mouseY - this.dragPressY) > DRAG_THRESHOLD) {
                this.dragMoved = true;
            }
            if (this.dragMoved) {
                // ① 被拖的页签跟着鼠标（抓取偏移保证不回跳）：横向是自由像素，纵向换算成「行号」
                //    写进槽位动画表，这样它和其它页签共用同一套坐标，松手后会自己滑回真实槽位。
                int pitch = TAB_H + GRID_GAP_Y;
                float scrolled = Math.max(0.0F, this.sidebarScrollPx);
                this.dragFreeX = (float) (mouseX - left() - this.dragOffX);
                this.tabSlotAnim.put(this.dragTab,
                        (float) (mouseY - top() - this.dragOffY - GRID_Y + scrolled) / pitch);
                // ② 离哪个槽位最近就插到哪；顺序一变，其它页签会在渲染时平滑让位（手机桌面那种效果）。
                List<String> order = visibleTabs();
                int target = Math.max(0, Math.min(order.size() - 1, slotIndexAt(mouseX - left(), mouseY - top())));
                if (target < order.size() && !order.get(target).equals(this.dragTab)) {
                    order.remove(this.dragTab);
                    order.add(Math.min(target, order.size()), this.dragTab);
                    ClientShopData.sidebarOrder = new ArrayList<>(order);
                }
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // 模块库拖拽结束：落在左侧列表里 → 按最近的槽位加入；只是点一下 → 直接加到末尾。
        if (this.poolDrag != null) {
            String id = this.poolDrag;
            boolean moved = this.poolMoved;
            this.poolDrag = null;
            this.poolMoved = false;
            if (!moved) {
                addModule(id, visibleTabs().size());
            } else if (mouseX < left() + SIDEBAR_W) {
                addModule(id, slotIndexAt(mouseX - left(), mouseY - top()));
            }
            return true;
        }
        if (this.dragTab != null) {
            String id = this.dragTab;
            boolean moved = this.dragMoved;
            this.dragTab = null;
            this.dragMoved = false;
            this.dragFreeX = Float.NaN;   // 松手：横向回到网格列，纵向由槽位动画滑回去
            boolean droppedOnTrash = moved && IOStyle.inside(this.trashZone[0], this.trashZone[1],
                    this.trashZone[2], this.trashZone[3], mouseX, mouseY);
            if (droppedOnTrash) {
                // 丢进「删除区」：先弹二次确认，确认后才真的移除（最后一个模块不允许删）。
                if (visibleTabs().size() > 1) {
                    this.pendingDelete = id;
                    this.layoutEdit = true;
                }
                return true;
            }
            if (moved) {
                // 松手：把顺序列表交给服务端保存并热同步（被拖的页签会自己 lerp 回网格槽位）。
                ListTag list = new ListTag();
                for (String tab : visibleTabs()) {
                    list.add(StringTag.valueOf(tab));
                }
                CompoundTag tag = new CompoundTag();
                tag.put("order", list);
                send("admin_sidebar_order", tag);
            } else if (!this.layoutEdit) {
                // 没超过阈值 = 普通点击：照常切换右侧界面（编辑模式下禁用一切跳转）。
                selectTab(id);
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 从模块库加入一个模块（插到指定槽位），并把改动交给服务端保存 + 热同步。 */
    private void addModule(String id, int slot) {
        if (id == null || id.isEmpty()) {
            return;
        }
        List<String> order = new ArrayList<>(visibleTabs());
        if (order.contains(id)) {
            return;
        }
        order.add(Math.max(0, Math.min(order.size(), slot)), id);
        List<String> hidden = new ArrayList<>(ClientShopData.sidebarHidden);
        hidden.remove(id);
        applyLayout(order, hidden);
    }

    /** 从侧栏移除一个模块（放进 hidden，而不是只从顺序里删掉，避免下次又被自动补回来）。 */
    private void removeModule(String id) {
        List<String> order = new ArrayList<>(visibleTabs());
        order.remove(id);
        if (order.isEmpty()) {
            return;
        }
        List<String> hidden = new ArrayList<>(ClientShopData.sidebarHidden);
        if (!hidden.contains(id)) {
            hidden.add(id);
        }
        applyLayout(order, hidden);
    }

    /** 本地先按新布局刷新，再把「顺序 + 已移除」两份列表发给服务端（服务端会再校验并广播给所有人）。 */
    private void applyLayout(List<String> order, List<String> hidden) {
        ClientShopData.sidebarOrder = new ArrayList<>(order);
        ClientShopData.sidebarHidden = new ArrayList<>(hidden);
        ListTag orderTag = new ListTag();
        for (String tab : order) {
            orderTag.add(StringTag.valueOf(tab));
        }
        ListTag hiddenTag = new ListTag();
        for (String tab : hidden) {
            hiddenTag.add(StringTag.valueOf(tab));
        }
        CompoundTag tag = new CompoundTag();
        tag.put("order", orderTag);
        tag.put("hidden", hiddenTag);
        send("admin_sidebar_order", tag);
    }

    /** 切换页签（普通点击与快捷键共用）。 */
    private void selectTab(String tab) {        if (tab == null || tab.isEmpty()) {
            return;
        }
        this.adminModuleOpen = false;
        ClientShopData.mallPage = 0;
        this.mallSortOpen = false;
        this.mallCatOpen = false;
        currentTab = tab;
        ClientShopData.lastTab = tab;
        auctionPage = 0;
        adminPage = 0;
        mailPage = 0;
        clearTabNotification(tab);
    }

    // =========================================================== 每日商店

    private void drawDaily(GuiGraphics g, int cx, int cy, int cw, int ch, int mx, int my, float partialTick) {
        int count = ClientShopData.dailyShop.size();
        if (count == 0) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.empty"), cx + cw / 2, cy + 40, COLOR_MUTED);
            return;
        }
        // 需求 8：牌数超过 6 张时改为每行 1 个横向长条（滚轮整行滚动、内容自适应）。
        if (count > 6) {
            drawDailyList(g, cx, cy, cw, ch, mx, my, partialTick);
            drawDailyRefresh(g, cx, cy, cw, ch, mx, my);
            return;
        }
        // 需求 11：牌面加大，排列为 2 行 3 列。
        int cols = 3;
        int cardW = 84;
        int cardH = 70;
        int gapX = 8;
        int gapY = 10;
        int rows = (count + cols - 1) / cols;
        int totalW = cols * cardW + (cols - 1) * gapX;
        int totalH = rows * cardH + (rows - 1) * gapY;
        int startX = cx + Math.max(0, (cw - totalW) / 2);
        // 底部要留出操作条（刷新按钮），卡牌整体在「内容区 - 底栏」里居中，绝不会压到按钮。
        int startY = cy + Math.max(0, (ch - BAR_H - totalH) / 2);
        for (int i = 0; i < count; i++) {
            var entry = ClientShopData.dailyShop.get(i);
            int col = i % cols;
            int row = i / cols;
            int x = startX + col * (cardW + gapX);
            int cardY = startY + row * (cardH + gapY);
            // 翻开状态来自服务端并持久化（需求 11），本地 flipStart / flipFrom 只负责翻牌动画。
            boolean flipped = ClientShopData.dailyFlipped.contains(i);
            Flip flip = flipState(i, flipped, partialTick);
            boolean front = flip.front();
            // 以卡牌**自身左右中点**为轴横向翻转（标准翻牌观感）。
            float hinge = x + cardW / 2.0F;
            g.pose().pushPose();
            g.pose().translate(hinge, cardY + cardH / 2.0F, 0.0F);
            g.pose().scale(flip.scale(), flipSquash(flip.scale()), 1.0F);
            g.pose().translate(-hinge, -(cardY + cardH / 2.0F), 0.0F);
            // 原版 iOS 风格卡牌：圆角卡片 + 品质色条；背面用品质淡色底 + 品质描边。
            int border = IOStyle.rarity(entry.rarity);
            boolean hoverCard = IOStyle.inside(x, cardY, cardW, cardH, mx, my);
            if (front) {
                // OneConfig 卡片：半透明方块 + 上方居中图标 + 底部一整条强调色标题条。
                IOStyle.cardInteractive(g, x, cardY, cardW, cardH, 8, hoverCard);
                int itemX = x + (cardW - 16) / 2;
                g.renderItem(entry.stack, itemX, cardY + 6);
                g.renderItemDecorations(this.font, entry.stack, itemX, cardY + 6);
                IOStyle.centerAt(g, this.font, Component.literal(ClientShopData.money(entry.price)),
                        x + cardW / 2, cardY + 24, COLOR_TEXT);
                // 剩余数量（小字）+ 品质（品质色）
                int remaining = entry.remaining();
                g.pose().pushPose();
                g.pose().translate(x + cardW / 2.0F, cardY + 34, 0.0F);
                g.pose().scale(0.8F, 0.8F, 1.0F);
                IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.remaining", remaining),
                        0, 0, remaining > 0 ? COLOR_MUTED : IOStyle.RED);
                g.pose().popPose();
                IOStyle.centerAt(g, this.font, Component.translatable(entry.rarity.translationKey()),
                        x + cardW / 2, cardY + 42, entry.rarity.color());
                // 底部强调色标题条（物品名）
                IOStyle.labelBar(g, this.font, x + 3, cardY + cardH - 15, cardW - 6, 13,
                        IOStyle.ellipsize(this.font, plainNameComponent(entry.stack), cardW - 12), true);
                IOStyle.rounded(g, x + 5, cardY + 4, 3, 3, 0, border);
            } else {
                // 牌背面用品质配色，让玩家不用翻开就能看出品质。
                IOStyle.rounded(g, x, cardY, cardW, cardH, 8, IOStyle.CARD);
                IOStyle.rounded(g, x + 2, cardY + 2, cardW - 4, cardH - 4, 7, IOStyle.rarityTint(entry.rarity));
                IOStyle.ring(g, x, cardY, cardW, cardH, 8, IOStyle.withAlpha(border, 102));
                IOStyle.centerAt(g, this.font, Component.literal("?"), x + cardW / 2, cardY + 26, border);
                IOStyle.centerAt(g, this.font, Component.translatable(entry.rarity.translationKey()),
                        x + cardW / 2, cardY + 54, border);
            }
            // 转到侧面时压暗（牌离开光源），翻牌更有实体感。
            int shade = flipShade(flip.scale());
            if (shade != 0) {
                IOStyle.rounded(g, x, cardY, cardW, cardH, 8, shade);
            }
            drawFlipSheen(g, x, cardY, cardW, cardH, flip.progress());
            g.pose().popPose();
            CompoundTag extra = new CompoundTag();
            extra.putInt("index", i);
            addRegion(x, cardY, cardW, cardH, flipped ? "buy_shop" : "flip_shop", null, extra);
            if (flipped && mx >= x && mx < x + cardW && my >= cardY && my < cardY + cardH) {
                this.jeiHover = entry.stack;
                this.pendingItemTip = entry.stack;
            }
        }
        // 付费刷新按钮：小尺寸，悬停显示本次花费与今日剩余次数（统一摆在底栏右下角）。
        int refreshX = refreshBtnX(cx, cw);
        int refreshY = refreshBtnY(cy, ch);
        boolean canRefresh = ClientShopData.refreshCost >= 0L;
        addRegion(refreshX, refreshY, REFRESH_W, REFRESH_H, "refresh_daily_shop", null, null);
        drawButton(g, refreshX, refreshY, REFRESH_W, REFRESH_H,
                Component.translatable("gui.module_shop.refresh_daily"), canRefresh);
        if (mx >= refreshX && mx < refreshX + REFRESH_W && my >= refreshY && my < refreshY + REFRESH_H) {
            int left = Math.max(0, ClientShopData.refreshMax - ClientShopData.refreshUsed);
            if (canRefresh) {
                g.renderTooltip(this.font, Component.translatable("gui.module_shop.refresh_tooltip",
                        ClientShopData.money(ClientShopData.refreshCost), left, ClientShopData.refreshMax), mx, my);
            } else {
                g.renderTooltip(this.font, Component.translatable("gui.module_shop.refresh_tooltip_none",
                        ClientShopData.refreshMax), mx, my);
            }
        }
    }

    /** 每日商店长条列表一屏能放几行（与 {@code drawDailyList} 的绘制循环同一套算法）。 */
    private static int dailyRowsPerScreen(int contentH) {
        int viewH = Math.max(30, contentH - BAR_H - 10);
        int rowH = 30;
        int gap = 4;
        int rows = 0;
        int y = 2;   // 绘制时从 cy+2 开始
        while (y + rowH <= viewH) {
            y += rowH + gap;
            rows++;
        }
        return Math.max(1, rows);
    }

    /** 每日收购列表一屏能放几行（与 {@code drawBuyback} 的排版一致）。 */
    private static int buybackRowsPerScreen(int contentH) {
        int listH = Math.max(60, contentH - BAR_H - 10 - 6);
        int rowH = Math.min(28, Math.max(20, listH / 6));
        return Math.max(1, listH / rowH);
    }

    /** 牌数超过 6 张时的横向长条列表：每行 1 个，滚轮整行滚动。 */
    private void drawDailyList(GuiGraphics g, int cx, int cy, int cw, int ch, int mx, int my, float partialTick) {
        int count = ClientShopData.dailyShop.size();
        int rowH = 30;
        int gap = 4;
        int viewH = Math.max(30, ch - BAR_H - 10);
        // 一屏能放几行（与下面的绘制循环同一套算法）：滚到最后一屏时最后一条正好贴着底边，
        // 不能再往下滚出一片空白。
        int perScreen = dailyRowsPerScreen(ch);
        int first = Mth.clamp(ClientShopData.dailyListRow, 0, Math.max(0, count - perScreen));
        ClientShopData.dailyListRow = first;
        int y = cy + 2;
        int drawn = 0;
        for (int i = first; i < count; i++) {
            if (y + rowH > cy + viewH) {
                break;
            }
            drawDailyBar(g, cx, y, cw, rowH, i, ClientShopData.dailyShop.get(i), mx, my, partialTick);
            y += rowH + gap;
            drawn++;
        }
        // 第五项：长条模式显示「当前序号 / 总数」——放在底栏里、刷新按钮**左侧**，不会再被按钮盖住。
        IOStyle.right(g, this.font, Component.translatable("gui.module_shop.daily_pos",
                Math.min(count, first + Math.max(1, drawn)), count),
                refreshBtnX(cx, cw) - BAR_GAP, barTextY(cy, ch), IOStyle.TEXT_2);
        // 「下面还有」箭头只在**确实还有**的时候画（以前只看 drawn < count，滚到底也还在闪）。
        if (first + drawn < count) {
            markMoreBelow(cy + viewH);
        }
        // 滚动条：轨道从第一行顶边到最后一行底边（和列表严格对齐），滚到底时滑块贴底。
        int rowsBottom = drawn <= 0 ? cy + 2 : y - gap;
        drawScrollBar(g, cx + cw - 2, cy + 2, Math.max(1, rowsBottom - (cy + 2)), first, perScreen, count);
    }

    /** 一条横向长条：品质色条 + 图标 + 名称 + 品质 + 价格 + 剩余（按宽度自适应）。 */
    private void drawDailyBar(GuiGraphics g, int cx, int y, int cw, int rowH, int index,
            cn.autoforged.module_shop.data.DailyShopEntry entry, int mx, int my,
            float partialTick) {
        boolean flipped = ClientShopData.dailyFlipped.contains(index);
        Flip flip = flipState(index, flipped, partialTick);
        boolean front = flip.front();
        // 第八轮修正：长条翻牌改成**以长条左右中点为轴**横向翻转（原来是以下边缘为轴做纵向压缩，
        // 看起来只是“扁一下”，完全没有翻牌感）。横向翻转 + 中点半程换面 = 纸牌翻过去的观感。
        int barX = cx + 2;
        int barW = cw - 4;
        float hinge = barX + barW / 2.0F;
        g.pose().pushPose();
        g.pose().translate(hinge, y + rowH / 2.0F, 0.0F);
        g.pose().scale(flip.scale(), flipSquash(flip.scale()), 1.0F);
        g.pose().translate(-hinge, -(y + rowH / 2.0F), 0.0F);
        int rarity = IOStyle.rarity(entry.rarity);
        boolean hover = IOStyle.inside(cx + 2, y, cw - 4, rowH, mx, my);
        if (front) {
            IOStyle.cardInteractive(g, cx + 2, y, cw - 4, rowH, 8, hover);
            IOStyle.rounded(g, cx + 8, y + 6, 3, rowH - 12, 1, rarity);
            IOStyle.slot(g, cx + 16, y + 7);
            g.renderItem(entry.stack, cx + 16, y + 7);
            g.renderItemDecorations(this.font, entry.stack, cx + 16, y + 7);
            int rightW = Math.min(120, Math.max(80, cw / 3));
            int priceX = cx + cw - rightW;
            Component name = IOStyle.ellipsize(this.font, plainNameComponent(entry.stack), priceX - (cx + 40) - 6);
            IOStyle.text(g, this.font, name, cx + 40, y + 6, IOStyle.TEXT);
            IOStyle.text(g, this.font, Component.translatable(entry.rarity.translationKey()), cx + 40, y + 17, rarity);
            IOStyle.text(g, this.font, Component.literal(ClientShopData.money(entry.price)), priceX, y + 6,
                    IOStyle.ORANGE);
            int remaining = entry.remaining();
            IOStyle.text(g, this.font, Component.translatable("gui.module_shop.remaining", remaining),
                    priceX, y + 17, remaining > 0 ? IOStyle.TEXT_2 : IOStyle.RED);
        } else {
            IOStyle.rounded(g, cx + 2, y, cw - 4, rowH, 8, IOStyle.CARD);
            IOStyle.rounded(g, cx + 4, y + 2, cw - 8, rowH - 4, 7, IOStyle.rarityTint(entry.rarity));
            IOStyle.ring(g, cx + 2, y, cw - 4, rowH, 8, IOStyle.withAlpha(rarity, 102));
            IOStyle.text(g, this.font, Component.literal("?"), cx + 16, y + 6, rarity);
            IOStyle.text(g, this.font, Component.translatable(entry.rarity.translationKey()), cx + 34, y + 6, rarity);
        }
        // 转到侧面时压暗（牌离开光源），长条翻起来也有实体感。
        int shade = flipShade(flip.scale());
        if (shade != 0) {
            IOStyle.rounded(g, cx + 2, y, cw - 4, rowH, 8, shade);
        }
        drawFlipSheen(g, cx + 2, y, cw - 4, rowH, flip.progress());
        g.pose().popPose();
        CompoundTag extra = new CompoundTag();
        extra.putInt("index", index);
        addRegion(cx + 2, y, cw - 4, rowH, flipped ? "buy_shop" : "flip_shop", null, extra);
        if (flipped && hover) {
            this.jeiHover = entry.stack;
            this.pendingItemTip = entry.stack;
        }
    }

    /** 每日商店的付费刷新按钮（长条模式也要画，逻辑与原网格模式一致）。 */
    private void drawDailyRefresh(GuiGraphics g, int cx, int cy, int cw, int ch, int mx, int my) {
        int refreshX = refreshBtnX(cx, cw);
        int refreshY = refreshBtnY(cy, ch);
        boolean canRefresh = ClientShopData.refreshCost >= 0L;
        addRegion(refreshX, refreshY, REFRESH_W, REFRESH_H, "refresh_daily_shop", null, null);
        drawButton(g, refreshX, refreshY, REFRESH_W, REFRESH_H,
                Component.translatable("gui.module_shop.refresh_daily"), canRefresh);
    }

    // =========================================================== 每日收购

    private void drawBuyback(GuiGraphics g, int cx, int cy, int cw, int ch, int mx, int my) {
        var list = ClientShopData.dailyBuyback;
        if (list.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.empty"), cx + cw / 2, cy + 40, COLOR_MUTED);
            return;
        }
        // 需求 5：一行一个共 6 行横条，最左物品图标、中间收购价、右侧还能收购的数量。
        // 列表底边 = 内容区底边 - 底栏(18) - 渐隐提示带(10)，所以最后一行永远不会压到「刷新收购」按钮。
        int listH = Math.max(60, ch - BAR_H - 10 - 6);
        int rowH = Math.min(28, Math.max(20, listH / 6));
        int perScreen = Math.max(1, listH / rowH);
        int first = Mth.clamp(ClientShopData.buybackListRow, 0, Math.max(0, list.size() - perScreen));
        ClientShopData.buybackListRow = first;
        int startY = cy + 6;
        for (int i = first; i < list.size(); i++) {
            int slot = i - first;
            if (slot >= perScreen) {
                break;
            }
            var entry = list.get(i);
            int y = startY + slot * rowH;
            int cardH = rowH - 4;
            // 右侧「收购价 / 还能收购」两行必须**始终落在行框内**：
            // 以前固定画在 y+3 / y+13，窗口矮或 GUI 缩放偏大（rowH 小）时第二行会压到行框底线上
            //（截图里那个「还能收购 16」的 16 就横跨了边框）。现在按行高挑字号、两行整体垂直居中。
            float numScale = cardH >= 20 ? 1.0F : 0.8F;
            int lineH = Math.round(10.0F * numScale);
            int blockTop = y + Math.max(1, (cardH - (lineH * 2 - 2)) / 2);
            int textY = y + (cardH - 8) / 2;
            // 版式：左侧品质色条 + 物品图标 + 居中物品名，右侧价格 / 剩余两行；品质只用色条表示（不写品质文字）。
            IOStyle.card(g, cx + 4, y, cw - 8, cardH, 0);
            IOStyle.rounded(g, cx + 8, y + 2, 3, rowH - 8, 0, IOStyle.rarity(entry.rarity));
            Bricks.slot(g, cx + 14, y + (rowH - 4 - 16) / 2);
            int iconY = y + (rowH - 4 - 16) / 2;
            g.renderItem(entry.stack, cx + 14, iconY);
            g.renderItemDecorations(this.font, entry.stack, cx + 14, iconY);
            String price = Component.translatable("gui.module_shop.buyback_price",
                    ClientShopData.money(entry.price)).getString();
            // 品质只用左侧色条表示，不再写品质文字（原来那一行会显得很挤）；物品名超宽自动省略，避免压到右侧价格。
            int priceLeft = cx + cw - 12 - Math.round(this.font.width(price) * numScale);
            Component itemName = IOStyle.ellipsize(this.font, plainNameComponent(entry.stack),
                    Math.max(24, priceLeft - 6 - (cx + 36)));
            g.drawString(this.font, itemName, cx + 36, textY, COLOR_TEXT, false);
            drawLabelRight(g, cx + cw - 12, blockTop, Component.literal(price), COLOR_GOLD, numScale);
            // 需求 9：剩余可收购数量来自该物品自己的上限。
            int remaining = entry.remaining();
            String remainText = Component.translatable("gui.module_shop.buyback_remaining", remaining).getString();
            drawLabelRight(g, cx + cw - 12, blockTop + lineH, Component.literal(remainText),
                    remaining > 0 ? COLOR_MUTED : IOStyle.RED, numScale);
            CompoundTag extra = new CompoundTag();
            extra.putInt("index", i);
            // 需求 9：点击不立即出售，而是弹出数量输入，让玩家自己调节卖出的数量。
            addRegion(cx + 4, y, cw - 8, rowH - 4, "sell_buyback_gui", null, extra);
            // 整行悬停都显示原版物品悬浮框（不再只有图标那一小块），JEI 的 R / U 也跟着生效。
            if (IOStyle.inside(cx + 4, y, cw - 8, rowH - 4, mx, my)) {
                this.jeiHover = entry.stack;
                this.pendingItemTip = entry.stack;
            }
        }
        if (list.size() > first + perScreen) {
            markMoreBelow(startY + Math.min(perScreen, list.size() - first) * rowH);
        }
        // 需求 8：条目多于一屏时右侧显示滚动条。
        if (list.size() > perScreen) {
            // 轨道 = 最后一张卡片的底边（每行卡片本身比行距矮 4px）。
            drawScrollBar(g, cx + cw - 2, startY, perScreen * rowH - 4, first, perScreen, list.size());
        }
        // ---- 底栏：右侧「刷新收购」，管理员控件从右往左依次摆在它左边，提示文字再往左靠 ----
        int barLabelY = barTextY(cy, ch);
        int byX = refreshBtnX(cx, cw);
        int byY = refreshBtnY(cy, ch);
        // 管理员控件（奖励开关 + 设置）先占位，返回它们最左边的边界，供提示文字避让。
        int adminLeft = drawBuybackBonusAdmin(g, cx, cy, cw, ch, byX);
        // 收购全清奖励进度：全部卖完可额外获得 X 星币（每天一次）；超宽自动截断，不会压到右侧控件。
        if (ClientShopData.buybackBonusEnabled && ClientShopData.buybackBonus > 0L) {
            Component bonusLine = ClientShopData.buybackBonusDone
                    ? Component.translatable("gui.module_shop.buyback_bonus_done",
                            ClientShopData.money(ClientShopData.buybackBonus))
                    : Component.translatable("gui.module_shop.buyback_bonus_progress",
                            ClientShopData.money(ClientShopData.buybackBonus), ClientShopData.buybackLeft);
            drawBarLabel(g, cx + 4, barLabelY, adminLeft - BAR_GAP, bonusLine,
                    ClientShopData.buybackBonusDone ? COLOR_MUTED : COLOR_GOLD);
        }
        // 付费刷新每日收购：与商店的刷新**完全分开**（各自的次数与价格）。
        boolean canBuybackRefresh = ClientShopData.buybackRefreshCost >= 0L;
        addRegion(byX, byY, REFRESH_W, REFRESH_H, "refresh_daily_buyback", null, null);
        drawButton(g, byX, byY, REFRESH_W, REFRESH_H,
                Component.translatable("gui.module_shop.refresh_buyback"), canBuybackRefresh);
        if (mx >= byX && mx < byX + REFRESH_W && my >= byY && my < byY + REFRESH_H) {
            int left = Math.max(0, ClientShopData.buybackRefreshMax - ClientShopData.buybackRefreshUsed);
            g.renderTooltip(this.font, canBuybackRefresh
                    ? Component.translatable("gui.module_shop.refresh_buyback_tooltip",
                            ClientShopData.money(ClientShopData.buybackRefreshCost), left,
                            ClientShopData.buybackRefreshMax)
                    : Component.translatable("gui.module_shop.refresh_buyback_tooltip_none",
                            ClientShopData.buybackRefreshMax), mx, my);
        }
    }

    /**
     * 每日收购页底栏里的管理员控件：小开关滑块（点击切换收购全清奖励）+「设置」按钮（改奖励金额）。
     * 两者从 {@code rightLimit}（= 刷新按钮左边界）往左依次摆放，彼此以及与刷新按钮都留 {@link #BAR_GAP}，
     * 返回它们最左边的 x，供左侧提示文字避让；非管理员返回 {@code rightLimit}。
     */
    private int drawBuybackBonusAdmin(GuiGraphics g, int cx, int cy, int cw, int ch, int rightLimit) {
        if (!ClientShopData.canAdmin) {
            return rightLimit;
        }
        int swH = 12;
        int swW = 24;
        int setW = 34;
        int setX = rightLimit - BAR_GAP - setW;
        int swX = setX - BAR_GAP - swW;
        int swY = barMid(cy, ch, swH);
        boolean on = ClientShopData.buybackBonusEnabled;
        IOStyle.rounded(g, swX, swY, swW, swH, swH / 2, on ? IOStyle.GREEN : IOStyle.TOGGLE_OFF);
        if (!on) {
            IOStyle.ring(g, swX, swY, swW, swH, swH / 2, IOStyle.TOGGLE_OFF_RING);
        }
        int knob = swH - 4;
        IOStyle.rounded(g, on ? swX + swW - 2 - knob : swX + 2, swY + 2, knob, knob, knob / 2, IOStyle.KNOB);
        addRegion(swX, swY, swW, swH, "admin_buyback_bonus_toggle", null, null);
        int setY = barMid(cy, ch, swH + 2);
        addRegion(setX, setY, setW, swH + 2, "buyback_bonus_set", null, null);
        drawSmallLabelButton(g, setX, setY, setW, swH + 2,
                Component.translatable("gui.module_shop.buyback_bonus_set"), false);
        return swX;
    }

    // =========================================================== 拍卖

    private void drawAuction(GuiGraphics g, int cx, int cy, int cw, int ch, int mx, int my) {
        List<ClientShopData.AuctionView> auctions = ClientShopData.auctions;
        int pageSize = 5;
        int pages = Math.max(1, (auctions.size() + pageSize - 1) / pageSize);
        auctionPage = Mth.clamp(auctionPage, 0, pages - 1);
        for (int i = 0; i < pageSize; i++) {
            int index = auctionPage * pageSize + i;
            if (index >= auctions.size()) {
                break;
            }
            ClientShopData.AuctionView view = auctions.get(index);
            int y = cy + 10 + i * 30;
            Bricks.slot(g, cx + 8, y + 5);
            g.renderItem(view.stack, cx + 8, y + 5);
            g.renderItemDecorations(this.font, view.stack, cx + 8, y + 5);
            g.drawString(this.font, view.stack.getHoverName(), cx + 32, y + 4, COLOR_TEXT, false);
            String sellerText = Component.translatable("gui.module_shop.auction_seller", view.seller).getString();
            if (view.own) {
                sellerText = sellerText + " " + Component.translatable("gui.module_shop.auction_mine").getString();
            }
            g.drawString(this.font, sellerText, cx + 32, y + 15, view.own ? COLOR_GOLD : COLOR_MUTED, false);
            // 最高出价者：玩家一眼看出自己是不是还在最高价（出价的钱是否还处于冻结中）。
            if (view.currentBid > 0L && view.topBidder != null && !view.topBidder.isEmpty()) {
                String top = Component.translatable(view.topMe
                        ? "gui.module_shop.auction_top_me"
                        : "gui.module_shop.auction_top_bidder", view.topBidder).getString();
                int topX = cx + cw - 6 - this.font.width(top);
                if (topX > cx + 32 + this.font.width(sellerText) + 4) {
                    g.drawString(this.font, top, topX, y + 15, view.topMe ? COLOR_GOLD : COLOR_MUTED, false);
                }
            }
            // 需求三：竞拍倒计时实时递减（快照剩余时间减去快照到达后的本地流逝时间）。
            long remainMs = view.receivedAt > 0L
                    ? Math.max(0L, view.remaining - (System.currentTimeMillis() - view.receivedAt))
                    : view.remaining;
            String time = formatDuration(remainMs);
            int centerX = cx + cw / 2;
            // 需求三：价格展示居中，同时显示上一轮出价（划掉）与当前最高出价。
            if (view.currentBid > 0) {
                String current = Component.translatable("gui.module_shop.auction_current_bid",
                        ClientShopData.money(view.currentBid)).getString();
                g.drawString(this.font, current, centerX - this.font.width(current) / 2, y + 4, COLOR_GOLD, false);
                if (view.lastBid > 0) {
                    String last = Component.translatable("gui.module_shop.auction_last_bid",
                            ClientShopData.money(view.lastBid)).getString();
                    int lastX = centerX - this.font.width(last) / 2;
                    g.drawString(this.font, last, lastX, y + 15, 0x8A8A9A, false);
                    g.fill(lastX, y + 19, lastX + this.font.width(last), y + 20, 0x8A8A9A);
                }
            } else {
                String start = Component.translatable("gui.module_shop.auction_start",
                        ClientShopData.money(view.startPrice)).getString();
                g.drawString(this.font, start, centerX - this.font.width(start) / 2, y + 4, COLOR_GOLD, false);
                g.drawString(this.font, time, centerX - this.font.width(time) / 2, y + 15, COLOR_MUTED, false);
            }
            // 剩余时间固定右上角显示，避免与居中的价格文字重叠。
            if (view.currentBid > 0) {
                g.drawString(this.font, time, cx + cw - 6 - this.font.width(time), y + 4, COLOR_MUTED, false);
            }
            // 需求 3：悬停物品图标时显示名称/附魔/属性/NBT/标签等完整信息。
            if (mx >= cx + 8 && mx < cx + 24 && my >= y + 5 && my < y + 21) {
                this.jeiHover = view.stack;
                g.renderTooltip(this.font, view.stack, mx, my);
            }
            // 自己的拍卖不注册“出价”入口：原来点进去填完金额才会被服务端以 bid_own 拒绝，白填一次。
            if (!view.own) {
                CompoundTag extra = new CompoundTag();
                extra.putLong("id", view.id);
                addRegion(cx + 4, y, cw - 8, 28, "bid_gui", null, extra);
            }
        }
        if (auctions.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.auction_empty"), cx + cw / 2, cy + 50, COLOR_MUTED);
        }
        // 上架按钮
        CompoundTag add = new CompoundTag();
        int barY = barMid(cy, ch, 16);
        addRegion(cx + cw - 76, barY, 72, 16, "create_auction", null, add);
        drawButton(g, cx + cw - 76, barY, 72, 16, Component.translatable("gui.module_shop.auction_create"));
        // 翻页：上一页 / 页码 / 下一页（需求 1）
        if (pages > 1) {
            drawButton(g, cx + 4, barY, 44, 16, Component.translatable("gui.module_shop.page_prev"));
            drawButton(g, cx + 52, barY, 44, 16, Component.translatable("gui.module_shop.page_next"));
            addRegion(cx + 4, barY, 44, 16, "auction_prev", null, null);
            addRegion(cx + 52, barY, 44, 16, "auction_next", null, null);
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.page", auctionPage + 1, pages),
                    cx + cw / 2, barMid(cy, ch, 9), COLOR_MUTED);
        }
    }

    // =========================================================== 摊位

    private void drawStall(GuiGraphics g, int cx, int cy, int cw, int ch, int mx, int my) {
        var stall = ClientShopData.stall;
        // 需求 8：上架物品界面改成 6 行 2 列。
        int rows = 6;
        int cols = 2;
        int gapX = 8;
        int gapY = 4;
        int cellW = (cw - gapX * (cols - 1)) / cols;
        int cellH = Math.min(30, Math.max(18, (ch - 26 - gapY * (rows - 1)) / rows));
        int startX = cx + 2;
        int startY = cy + 6;
        for (int i = 0; i < rows * cols; i++) {
            int col = i % cols;
            int row = i / cols;
            int sx = startX + col * (cellW + gapX);
            int sy = startY + row * (cellH + gapY);
            IOStyle.card(g, sx, sy, cellW, cellH, 6);
            int slotY = sy + (cellH - 16) / 2;
            Bricks.slot(g, sx + 4, slotY);
            if (i < stall.size()) {
                var entry = stall.get(i);
                g.renderItem(entry.stack, sx + 4, slotY);
                g.renderItemDecorations(this.font, entry.stack, sx + 4, slotY);
                // 两行文字（价格 / 库存·已售出）以前固定写在 sy+4 / sy+15，格子一矮第二行就压到框线上
                //（截图里「库存 1 已售出 0 件」横跨边框）。现在按格高挑字号、两行整体垂直居中，
                // 右边界统一留出「X」按钮的位置，长文案自动省略。
                float numScale = cellH >= 20 ? 1.0F : 0.8F;
                int lineH = Math.round(10.0F * numScale);
                int blockTop = sy + Math.max(1, (cellH - (lineH * 2 - 2)) / 2);
                int textLeft = sx + 26;
                int textRight = sx + cellW - 26;
                Component priceText = Component.literal(ClientShopData.money(entry.price));
                drawLabel(g, textLeft, blockTop, priceText, COLOR_GOLD, numScale);
                // 「下架时间」限时时，在价格右侧显示剩余时间（右对齐到 X 按钮左边，放不下就不画）。
                Component remain = stallRemainText(entry);
                if (remain != null) {
                    int remainX = textRight - Math.round(this.font.width(remain) * numScale);
                    int priceRight = textLeft + Math.round(this.font.width(priceText) * numScale);
                    if (remainX > priceRight + 3) {
                        drawLabel(g, remainX, blockTop, remain, 0xE0A34A, numScale);
                    }
                }
                String stockText;
                int stockColor;
                if (entry.stock <= 0) {
                    stockText = Component.translatable("gui.module_shop.stall_sold_out").getString();
                    stockColor = IOStyle.RED;
                } else {
                    stockText = Component.translatable("gui.module_shop.stall_stock", entry.stock).getString();
                    stockColor = COLOR_MUTED;
                }
                int stockY = blockTop + lineH;
                drawLabel(g, textLeft, stockY, Component.literal(stockText), stockColor, numScale);
                // 需求 9：在商品旁空白处显示该商品累计已售出件数（放不下就省略，绝不压到「X」按钮）。
                int soldX = textLeft + Math.round(this.font.width(stockText) * numScale) + Math.round(8 * numScale);
                Component sold = IOStyle.ellipsize(this.font,
                        Component.translatable("gui.module_shop.stall_sold_count", entry.sold),
                        Math.max(12, (int) ((textRight - soldX) / numScale)));
                drawLabel(g, soldX, stockY, sold, 0x88BBFF, numScale);
                CompoundTag extra = new CompoundTag();
                extra.putInt("index", i);
                // 右侧小按钮先注册，保证点击优先命中“下架”而不是整格改价。
                addRegion(sx + cellW - 22, sy + (cellH - 14) / 2, 20, 14, "remove_stall", null, extra);
                drawSmallButton(g, sx + cellW - 22, sy + (cellH - 14) / 2, Component.literal("X"));
                // 需求五：已取消补货；售空的商品整格不再响应点击，只能点右侧“X”下架后重新上架。
                if (entry.stock > 0) {
                    addRegion(sx + 2, sy, cellW - 26, cellH, "stall_edit_gui", null, extra);
                }
                if (mx >= sx + 4 && mx < sx + 20 && my >= sy && my < sy + cellH) {
                    this.jeiHover = entry.stack;
                g.renderTooltip(this.font, entry.stack, mx, my);
                }
            }
        }
        int buttonY = barMid(cy, ch, 16);
        addRegion(cx + 4, buttonY, 80, 16, "create_stall", null, null);
        drawButton(g, cx + 4, buttonY, 80, 16, Component.translatable("gui.module_shop.stall_create"));
        addRegion(cx + 90, buttonY, 80, 16, "open_avatar", null, null);
        drawButton(g, cx + 90, buttonY, 80, 16, Component.translatable("gui.module_shop.stall_browse"));
    }

    /**
     * 摊位页（商店设置）里的说明块：告诉管理员摊位内容由玩家自己管理，并显示当前的下架设置。
     */
    private void drawStallExpireHint(GuiGraphics g, int cx, int cy, int cw) {
        Component title = Component.translatable("gui.module_shop.admin_stall");
        Component line1 = Component.translatable("gui.module_shop.admin_stall_hint");
        Component current = "permanent".equals(ClientShopData.stallExpireMode) || ClientShopData.stallExpireAmount <= 0
                ? Component.translatable("gui.module_shop.choice.expire_mode.permanent")
                : Component.translatable("gui.module_shop.stall.expire_value",
                        Component.translatable("gui.module_shop.choice.expire_mode." + ClientShopData.stallExpireMode),
                        ClientShopData.stallExpireAmount);
        Component line2 = Component.translatable("gui.module_shop.stall.expire_current", current);
        drawSmallLabel(g, cx + 8, cy + 26, title, IOStyle.TEXT);
        drawBarLabel(g, cx + 8, cy + 42, cx + cw - 8, line1, COLOR_MUTED);
        drawBarLabel(g, cx + 8, cy + 56, cx + cw - 8, line2, COLOR_GOLD);
    }

    /** 打开「摊位下架时间」设置：永久 / 游戏时间 / 现实时间 + 时长。 */
    private void openStallExpire() {
        CompoundTag req = new CompoundTag();
        ListTag mode = new ListTag();
        mode.add(StringTag.valueOf("permanent"));
        mode.add(StringTag.valueOf("game"));
        mode.add(StringTag.valueOf("real"));
        req.put("choices_expire_mode", mode);
        req.putString("default_expire_mode", ClientShopData.stallExpireMode);
        req.putString("default_expire_amount", Integer.toString(Math.max(0, ClientShopData.stallExpireAmount)));
        req.putString("info", Component.translatable("gui.module_shop.stall.expire_info").getString());
        openInput("admin_stall_expire", "gui.module_shop.input.stall_expire", req,
                "expire_mode", "expire_amount");
    }

    /**
     * 摊位商品的剩余时间文字（只在本服开启限时时显示）。
     *
     * <p>游戏时间用客户端同步到的 {@code level.getGameTime()}（与服务器同一个值），
     * 现实时间用本机时钟；两者都只是**显示**，真正的下架判定在服务端。
     */
    private Component stallRemainText(cn.autoforged.module_shop.data.StallEntry entry) {
        if (entry == null || "permanent".equals(ClientShopData.stallExpireMode) || ClientShopData.stallExpireAmount <= 0) {
            return null;
        }
        long limit;
        long age;
        if ("game".equals(ClientShopData.stallExpireMode)) {
            if (entry.listedGame <= 0L) {
                return null;   // 服务端还没打时间戳
            }
            long nowGame = this.minecraft != null && this.minecraft.level != null
                    ? this.minecraft.level.getGameTime() : entry.listedGame;
            limit = ClientShopData.stallExpireAmount * 24_000L;
            age = nowGame - entry.listedGame;
        } else {
            if (entry.listedReal <= 0L) {
                return null;
            }
            limit = ClientShopData.stallExpireAmount * 3_600_000L;
            age = System.currentTimeMillis() - entry.listedReal;
        }
        long left = Math.max(0L, limit - age);
        if (left <= 0L) {
            return Component.translatable("gui.module_shop.stall.remain_soon");
        }
        if ("game".equals(ClientShopData.stallExpireMode)) {
            // 游戏时间：24000 刻 = 1 天
            if (left >= 24_000L) {
                return Component.translatable("gui.module_shop.stall.remain_days", left / 24_000L);
            }
            return Component.translatable("gui.module_shop.stall.remain_hours", Math.max(1L, left / 1_000L));
        }
        if (left >= 3_600_000L) {
            return Component.translatable("gui.module_shop.stall.remain_hours", left / 3_600_000L);
        }
        return Component.translatable("gui.module_shop.stall.remain_minutes", Math.max(1L, left / 60_000L));
    }

    // =========================================================== 玩家求购（悬赏）

    /** 求购列表：每页 6 行（行高 22）。 */
    private static final int DEMAND_ROWS = 6;
    private static final int DEMAND_ROW_H = 22;

    /** 求购页当前的「只看我的」过滤开关。 */
    private boolean demandOnlyMine;
    /** 当前展开的求购单 id（点一行展开/收起，同一时间只展开一条）。 */
    private String demandExpandedId;

    /** 当前显示的求购单（按过滤条件收好）。 */
    private List<cn.autoforged.module_shop.data.DemandEntry> visibleDemands() {
        List<cn.autoforged.module_shop.data.DemandEntry> list = new ArrayList<>();
        var self = this.minecraft == null || this.minecraft.player == null ? null : this.minecraft.player.getUUID();
        for (var entry : ClientShopData.demands) {
            if (this.demandOnlyMine && (self == null || !self.equals(entry.requester))) {
                continue;
            }
            list.add(entry);
        }
        return list;
    }

    /** 求购单的剩余时间文字（只在服务端开启限时时显示）。 */
    private Component demandRemainText(cn.autoforged.module_shop.data.DemandEntry entry) {
        if (entry == null || "permanent".equals(ClientShopData.demandExpireMode)
                || ClientShopData.demandExpireAmount <= 0) {
            return null;
        }
        long limit;
        long age;
        if ("game".equals(ClientShopData.demandExpireMode)) {
            if (entry.createdGame <= 0L) {
                return null;
            }
            long nowGame = this.minecraft != null && this.minecraft.level != null
                    ? this.minecraft.level.getGameTime() : entry.createdGame;
            limit = ClientShopData.demandExpireAmount * 24_000L;
            age = nowGame - entry.createdGame;
        } else {
            if (entry.createdReal <= 0L) {
                return null;
            }
            limit = ClientShopData.demandExpireAmount * 3_600_000L;
            age = System.currentTimeMillis() - entry.createdReal;
        }
        long left = Math.max(0L, limit - age);
        if (left <= 0L) {
            return Component.translatable("gui.module_shop.stall.remain_soon");
        }
        if ("game".equals(ClientShopData.demandExpireMode)) {
            if (left >= 24_000L) {
                return Component.translatable("gui.module_shop.stall.remain_days", left / 24_000L);
            }
            return Component.translatable("gui.module_shop.stall.remain_hours", Math.max(1L, left / 1_000L));
        }
        if (left >= 3_600_000L) {
            return Component.translatable("gui.module_shop.stall.remain_hours", left / 3_600_000L);
        }
        return Component.translatable("gui.module_shop.stall.remain_minutes", Math.max(1L, left / 60_000L));
    }

    /** 悬赏内容的一行文字（金币 + 物品）。 */
    private Component demandRewardLine(cn.autoforged.module_shop.data.DemandEntry entry) {
        String money = entry.rewardMoney > 0L ? ClientShopData.money(entry.rewardMoney) : null;
        String item = entry.rewardItem != null && !entry.rewardItem.isEmpty()
                ? entry.rewardItem.getHoverName().getString() + " x" + entry.rewardItem.getCount() : null;
        if (money != null && item != null) {
            return Component.literal(money + " + " + item);
        }
        return Component.literal(money != null ? money : (item != null ? item : "-"));
    }

    /**
     * 求购页：玩家发起悬赏（主手 = 想要的物品、副手 = 愿意付出的物品），其他玩家点一行即可交付。
     */
    private void drawDemand(GuiGraphics g, int cx, int cy, int cw, int ch, int mx, int my) {
        List<cn.autoforged.module_shop.data.DemandEntry> list = visibleDemands();
        int listTop = cy + 2;
        int rowW = cw - 8;
        if (!ClientShopData.demandEnabled) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.demand.disabled"),
                    cx + cw / 2, cy + 50, COLOR_MUTED);
        } else if (list.isEmpty()) {
            IOStyle.centerAt(g, this.font,
                    Component.translatable(this.demandOnlyMine ? "gui.module_shop.demand.empty_mine"
                            : "gui.module_shop.demand.empty"),
                    cx + cw / 2, cy + 50, COLOR_MUTED);
        }
        var self = this.minecraft == null || this.minecraft.player == null ? null : this.minecraft.player.getUUID();
        int bottom = cy + ch - 20;   // 底栏留给「发起求购 / 只看我的」
        int y = listTop;
        for (var entry : list) {
            boolean mine = self != null && self.equals(entry.requester);
            boolean expanded = entry.id != null && entry.id.equals(this.demandExpandedId);
            int headH = DEMAND_ROW_H - 2;
            int detailH = expanded ? demandDetailHeight(entry) : 0;
            if (y + headH + detailH > bottom) {
                break;   // 放不下就不画（展开时优先保证这一条完整显示）
            }
            boolean hover = IOStyle.inside(cx + 4, y, rowW, headH, mx, my);
            IOStyle.rounded(g, cx + 4, y, rowW, headH, 0,
                    hover || expanded ? IOStyle.ROW_HOVER : IOStyle.SIDEBAR);
            IOStyle.ring(g, cx + 4, y, rowW, headH, 0, IOStyle.BORDER_DIM);
            // 想要的物品图标 + 数量
            ItemStack want = entry.wants.isEmpty() ? ItemStack.EMPTY : entry.wants.get(0).display();
            g.renderItem(want, cx + 6, y + 2);
            g.renderItemDecorations(this.font, want, cx + 6, y + 2);
            drawSmallLabel(g, cx + 26, y + 3, entry.summary(), COLOR_TEXT);
            // 悬赏
            drawSmallLabel(g, cx + 26, y + 12, Component.translatable("gui.module_shop.demand.reward",
                    demandRewardLine(entry)), COLOR_GOLD);
            // 发布者 + 剩余时间（右侧对齐）
            String who = entry.requesterName == null || entry.requesterName.isBlank() ? "?" : entry.requesterName;
            Component remain = demandRemainText(entry);
            Component right = remain == null ? Component.literal(who)
                    : Component.literal(who + "  " + remain.getString());
            int rightX = cx + 4 + rowW - 12 - (int) (this.font.width(right) * 0.8F);
            drawSmallLabel(g, rightX, y + 7, right, COLOR_MUTED);
            // 展开/收起指示（▾ 展开中，▸ 收起）
            drawSmallLabel(g, cx + 4 + rowW - 10, y + 6,
                    Component.literal(expanded ? "▾" : "▸"), COLOR_MUTED);
            CompoundTag extra = new CompoundTag();
            extra.putString("id", entry.id);
            addRegion(cx + 4, y, rowW - 2, headH, "demand_toggle", null, extra);
            if (hover) {
                this.jeiHover = want;
            }
            if (expanded) {
                drawDemandDetail(g, entry, cx + 4, y + headH, rowW - 2, mine, mx, my);
            }
            y += headH + detailH + 2;
        }
        // 底栏按钮：发起求购 / 只看我的 / 提示
        int buttonY = barMid(cy, ch, 16);
        addRegion(cx + 4, buttonY, 76, 16, "demand_create", null, null);
        drawButton(g, cx + 4, buttonY, 76, 16, Component.translatable("gui.module_shop.demand.create"));
        addRegion(cx + 86, buttonY, 76, 16, "demand_filter", null, null);
        drawButton(g, cx + 86, buttonY, 76, 16,
                Component.translatable(this.demandOnlyMine ? "gui.module_shop.demand.filter_all"
                        : "gui.module_shop.demand.filter_mine"));
        drawBarLabel(g, cx + 168, buttonY + 4, cx + cw - 6,
                Component.translatable("gui.module_shop.demand.hint"), COLOR_MUTED);
    }

    /** 展开区域高度：每行物品 16px（两列），底部留一行按钮。 */
    private int demandDetailHeight(cn.autoforged.module_shop.data.DemandEntry entry) {
        int rows = (Math.max(1, entry.wants.size()) + 1) / 2;
        return 4 + rows * 16 + 18;
    }

    /**
     * 展开的求购清单：逐条列出「想要的物品 ×N」+「需要 / 背包里有」+ √×。
     *
     * <p>物品按两列排（最多 12 条），右侧空白处放「交付」按钮 —— 数量全够才能点，
     * 点了由服务端扣物品、发悬赏（客户端不做任何扣减，避免和服务端不一致）。
     */
    private void drawDemandDetail(GuiGraphics g, cn.autoforged.module_shop.data.DemandEntry entry,
            int x, int y, int w, boolean mine, int mx, int my) {
        IOStyle.rounded(g, x, y, w, demandDetailHeight(entry), 0, IOStyle.SLOT_INNER);
        IOStyle.ring(g, x, y, w, demandDetailHeight(entry), 0, IOStyle.HAIR);
        int colW = (w - 8) / 2;
        boolean allEnough = true;
        for (int i = 0; i < entry.wants.size(); i++) {
            var line = entry.wants.get(i);
            ItemStack template = line.stack.copy();
            template.setCount(1);
            int have = countInBag(template);
            boolean enough = have >= line.count;
            if (!enough) {
                allEnough = false;
            }
            int col = i % 2;
            int row = i / 2;
            int cellX = x + 6 + col * colW;
            int cellY = y + 4 + row * 16;
            ItemStack icon = line.stack.copy();
            icon.setCount(1);
            g.renderItem(icon, cellX, cellY);
            g.renderItemDecorations(this.font, icon, cellX, cellY);
            // 名称 ×N（截断到列宽的一半，右边留给 需要/拥有）
            Component name = IOStyle.ellipsize(this.font, plainNameComponent(line.stack),
                    Math.max(24, colW - 18 - 56));
            drawSmallLabel(g, cellX + 18, cellY + 3, name, COLOR_TEXT);
            Component counts = Component.translatable("gui.module_shop.demand.need_have",
                    line.count, have);
            Component mark = Component.literal(" " + (enough ? "√" : "×"));
            Component tail = Component.empty().append(counts).append(mark);
            int tailX = cellX + colW - 6 - (int) (this.font.width(tail) * 0.8F);
            drawSmallLabel(g, tailX, cellY + 3, tail, enough ? IOStyle.GREEN : IOStyle.RED);
            if (IOStyle.inside(cellX, cellY, colW - 4, 16, mx, my)) {
                this.jeiHover = icon;
                this.pendingItemTip = line.stack;   // 悬停看原版说明（含数量）
            }
        }
        // 右下角的操作按钮：自己的 → 撤销；别人的 → 交付（数量不够时置灰不可点）
        int by = y + demandDetailHeight(entry) - 17;
        if (mine) {
            addRegion(x + w - 8 - 60, by, 60, 15, "demand_cancel_go", null, demandIdTag(entry));
            drawButton(g, x + w - 8 - 60, by, 60, 15, Component.translatable("gui.module_shop.demand.cancel_action"),
                    true);
        } else {
            addRegion(x + w - 8 - 60, by, 60, 15, "demand_fulfill_go", null, demandIdTag(entry));
            drawButton(g, x + w - 8 - 60, by, 60, 15,
                    Component.translatable("gui.module_shop.demand.fulfill_action"), allEnough);
        }
    }

    private static CompoundTag demandIdTag(cn.autoforged.module_shop.data.DemandEntry entry) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", entry.id);
        return tag;
    }

    /** 背包里有多少个「同物品同组件」的堆（与服务器清点方式一致）。 */
    private int countInBag(ItemStack template) {
        if (this.minecraft == null || this.minecraft.player == null || template.isEmpty()) {
            return 0;
        }
        int total = 0;
        var inventory = this.minecraft.player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 发起求购：主手放「想要的物品」，副手放「愿意付出的物品」，这里填数量与金币。 */
    /** 发起求购：打开「物品搜索」选物界面（最多 12 种，各自填数量）。 */
    private void openDemandCreate() {
        Nav.open(new DemandCreateScreen());
    }

    /** 复制一份动作包并写入 action（客户端发请求用）。 */
    private static CompoundTag withAction(String action, CompoundTag extra) {
        CompoundTag tag = extra == null ? new CompoundTag() : extra.copy();
        tag.putString("action", action);
        return tag;
    }

    // =========================================================== 求购：管理员页

    /** 管理员页的求购列表（最多 5 行，点一行可强制撤销）。 */
    private void drawDemandAdminList(GuiGraphics g, int cx, int cy, int cw, int ch, int mx, int my) {
        var list = ClientShopData.demands;
        Component summary = Component.translatable("gui.module_shop.demand.admin_summary",
                list.size(), ClientShopData.demandSlots, (int) Math.round(ClientShopData.demandFeePercent),
                ClientShopData.demandMaxReward <= 0L ? Component.translatable("gui.module_shop.demand.no_limit")
                        : Component.literal(ClientShopData.money(ClientShopData.demandMaxReward)),
                "permanent".equals(ClientShopData.demandExpireMode) || ClientShopData.demandExpireAmount <= 0
                        ? Component.translatable("gui.module_shop.choice.expire_mode.permanent")
                        : Component.translatable("gui.module_shop.stall.expire_value",
                                Component.translatable("gui.module_shop.choice.expire_mode."
                                        + ClientShopData.demandExpireMode),
                                ClientShopData.demandExpireAmount));
        drawBarLabel(g, cx + 6, cy + 20, cx + cw - 6, summary, COLOR_GOLD);
        if (list.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.demand.empty"),
                    cx + cw / 2, cy + 50, COLOR_MUTED);
            return;
        }
        int rowW = cw - 8;
        int rows = Math.min(5, list.size());
        for (int i = 0; i < rows; i++) {
            var entry = list.get(i);
            int ry = cy + 34 + i * 20;
            boolean hover = IOStyle.inside(cx + 4, ry, rowW, 18, mx, my);
            IOStyle.rounded(g, cx + 4, ry, rowW, 18, 0, hover ? IOStyle.ROW_HOVER : IOStyle.SIDEBAR);
            IOStyle.ring(g, cx + 4, ry, rowW, 18, 0, IOStyle.BORDER_DIM);
            ItemStack want = entry.wants.isEmpty() ? ItemStack.EMPTY : entry.wants.get(0).display();
            g.renderItem(want, cx + 6, ry + 1);
            drawSmallLabel(g, cx + 26, ry + 4, entry.summary(), COLOR_TEXT);
            Component reward = Component.translatable("gui.module_shop.demand.reward",
                    demandRewardLine(entry));
            drawSmallLabel(g, cx + 130, ry + 4, reward, COLOR_GOLD);
            String who = entry.requesterName == null || entry.requesterName.isBlank() ? "?" : entry.requesterName;
            int whoX = cx + 4 + rowW - 8 - this.font.width(who);
            drawSmallLabel(g, whoX, ry + 4, Component.literal(who), COLOR_MUTED);
            CompoundTag extra = new CompoundTag();
            extra.putString("id", entry.id);
            addRegion(cx + 4, ry, rowW - 2, 18, "demand_admin_cancel", null, extra);
        }
    }

    /** 商店设置 → 求购：基本设置（开关 / 每人条数 / 手续费 / 单笔上限）。 */
    private void openDemandSettings() {
        CompoundTag req = new CompoundTag();
        ListTag enabled = new ListTag();
        enabled.add(StringTag.valueOf("0"));
        enabled.add(StringTag.valueOf("1"));
        req.put("choices_enabled", enabled);
        req.putString("default_enabled", ClientShopData.demandEnabled ? "1" : "0");
        req.putString("default_slots", Integer.toString(ClientShopData.demandSlots));
        req.putString("default_fee", Integer.toString((int) Math.round(ClientShopData.demandFeePercent)));
        req.putString("default_max_reward", Long.toString(Math.max(0L, ClientShopData.demandMaxReward)));
        req.putString("info", Component.translatable("gui.module_shop.demand.settings_info").getString());
        openInput("admin_demand_set", "gui.module_shop.input.demand_settings", req,
                "enabled", "slots", "fee", "max_reward");
    }

    /** 商店设置 → 求购：自动下架时间。 */
    private void openDemandExpire() {
        CompoundTag req = new CompoundTag();
        ListTag mode = new ListTag();
        mode.add(StringTag.valueOf("permanent"));
        mode.add(StringTag.valueOf("game"));
        mode.add(StringTag.valueOf("real"));
        req.put("choices_expire_mode", mode);
        req.putString("default_expire_mode", ClientShopData.demandExpireMode);
        req.putString("default_expire_amount", Integer.toString(Math.max(0, ClientShopData.demandExpireAmount)));
        req.putString("info", Component.translatable("gui.module_shop.demand.expire_info").getString());
        openInput("admin_demand_expire", "gui.module_shop.input.demand_expire", req,
                "expire_mode", "expire_amount");
    }

    /** 管理员点求购：确认强制撤销（托管悬赏会退回发布者）。 */
    private void openDemandAdminCancel(String id) {
        cn.autoforged.module_shop.data.DemandEntry entry = null;
        for (var candidate : ClientShopData.demands) {
            if (candidate.id != null && candidate.id.equals(id)) {
                entry = candidate;
                break;
            }
        }
        if (entry == null) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("id", entry.id);
        Component message = Component.translatable("gui.module_shop.demand.admin_cancel_message",
                entry.summary(), demandRewardLine(entry),
                entry.requesterName == null ? "?" : entry.requesterName);
        Nav.open(new ShopConfirmScreen(Component.translatable("gui.module_shop.demand.admin_cancel_title"),
                message, Component.translatable("gui.module_shop.demand.cancel_action"),
                Component.translatable("gui.module_shop.cancel"), confirmed -> {
                    if (confirmed) {
                        ServerboundShopActionPayload.send(withAction("admin_demand_cancel", tag));
                    }
                }));
    }

    private void drawSmallButton(GuiGraphics g, int x, int y, Component label) {
        IOStyle.rounded(g, x, y, 20, 14, 5, IOStyle.RED_TINT);
        IOStyle.ring(g, x, y, 20, 14, 5, IOStyle.withAlpha(IOStyle.RED, 90));
        IOStyle.centerAt(g, this.font, label, x + 10, y + 3, IOStyle.RED);
    }

    // =========================================================== 邮箱

    /**
     * 第七轮：鸣潮风格邮箱 —— 左侧邮件列表（容量/状态/星标），右侧详情（标题/发件人/时间/正文/附件）。
     * 底栏保留「全部领取 / 删除已读」与翻页；选中项存在 {@code ClientShopData.mailSelected} 跨界面保留。
     */
    private void drawMail(GuiGraphics g, int cx, int cy, int cw, int ch, int mx, int my) {
        List<cn.autoforged.module_shop.data.MailEntry> mails = sortedMails();
        int listW = 140;
        // 左右两栏的底边 = 内容区底边 - 底栏(18) - 空档(10)：底栏按钮与「还有内容」提示都不会压到卡片。
        int paneH = ch - BAR_H - 10;
        // 第八轮：邮箱整体字号缩到 0.8 倍（原来正文/列表和按钮一样大，小面板里很挤），行高同步收窄，一屏多显示一封。
        int rowH = 21;
        int headerH = 18;
        int perScreen = Math.max(1, (paneH - headerH) / rowH);
        int pages = Math.max(1, (mails.size() + perScreen - 1) / perScreen);
        this.mailPage = Mth.clamp(this.mailPage, 0, pages - 1);
        if (ClientShopData.mailSelected.isEmpty() && !mails.isEmpty()) {
            ClientShopData.mailSelected = mails.get(0).mailId;
        }
        // ---------- 左：邮件列表 ----------
        IOStyle.card(g, cx, cy, listW, paneH, 8);
        textScaled(g, cx + 8, cy + 6, MAIL_TEXT, Component.translatable("gui.module_shop.mail_all",
                mails.size(), Config.INSTANCE.maxMailboxSize.get()), IOStyle.ORANGE);
        int startIndex = this.mailPage * perScreen;
        for (int i = 0; i < perScreen; i++) {
            int idx = startIndex + i;
            if (idx >= mails.size()) {
                break;
            }
            var entry = mails.get(idx);
            int ry = cy + headerH + i * rowH;
            boolean selected = entry.mailId.equals(ClientShopData.mailSelected);
            boolean hover = IOStyle.inside(cx + 2, ry, listW - 4, rowH - 2, mx, my);
            if (selected) {
                IOStyle.rounded(g, cx + 2, ry, listW - 4, rowH - 2, 6, IOStyle.BLUE_TINT);
                IOStyle.ring(g, cx + 2, ry, listW - 4, rowH - 2, 6, IOStyle.TAB_SEL_BORDER);
            } else if (hover) {
                IOStyle.rounded(g, cx + 2, ry, listW - 4, rowH - 2, 6, IOStyle.ROW_HOVER);
            }
            boolean unread = entry.type == cn.autoforged.module_shop.data.MailType.MONEY
                    ? !entry.isViewed : !entry.isClaimed;
            g.fill(cx + 7, ry + 7, cx + 11, ry + 11, unread ? kindColor(entry) : IOStyle.HAIR);
            if (entry.starred) {
                textScaled(g, cx + listW - 13, ry + 4, MAIL_TEXT, Component.literal("★"), IOStyle.ORANGE);
            }
            String title = IOStyle.ellipsize(this.font, Component.literal(colorize(mailTitle(entry))),
                    (int) ((listW - 40) / MAIL_TEXT)).getString();
            textScaled(g, cx + 16, ry + 3, MAIL_TEXT, Component.literal(title),
                    unread ? kindColor(entry) : IOStyle.TEXT_2);
            String sender = entry.sender == null || entry.sender.isEmpty() ? "-" : entry.sender;
            textScaled(g, cx + 16, ry + 12, MAIL_TEXT, Component.literal(
                    IOStyle.ellipsize(this.font, Component.literal(sender),
                            (int) ((listW - 30) / MAIL_TEXT)).getString()), IOStyle.TEXT_2);
            CompoundTag listExtra = new CompoundTag();
            listExtra.putString("mail_id", entry.mailId);
            addRegion(cx + 2, ry, listW - 4, rowH - 2, "mail_select", "mail_star", listExtra);
        }
        if (startIndex + perScreen < mails.size()) {
            markMoreBelow(cy + paneH);
        }
        // 第七轮：兑换码入口（邮箱右上角）
        addRegion(cx + listW - 58, cy + 4, 56, 14, "open_redeem", null, null);
        drawSmallLabelButton(g, cx + listW - 58, cy + 4, 56, 14,
                Component.translatable("gui.module_shop.redeem.button"), false);
        // ---------- 右：邮件详情 ----------
        int dx = cx + listW + 6;
        int dw = cw - listW - 6;
        IOStyle.card(g, dx, cy, dw, paneH, 8);
        var sel = selectedMail(mails);
        if (sel == null) {
            textScaledCenter(g, dx + dw / 2, cy + paneH / 2, MAIL_TEXT,
                    Component.translatable("gui.module_shop.mail_empty"), IOStyle.TEXT_2);
        } else {
            textScaled(g, dx + 8, cy + 7, MAIL_TEXT, Component.literal(IOStyle.ellipsize(this.font,
                    Component.literal(colorize(mailTitle(sel))), (int) ((dw - 34) / MAIL_TEXT)).getString()),
                    IOStyle.TEXT);
            CompoundTag starExtra = new CompoundTag();
            starExtra.putString("mail_id", sel.mailId);
            IOStyle.rounded(g, dx + dw - 22, cy + 5, 16, 16, 5,
                    sel.starred ? IOStyle.BLUE_TINT : IOStyle.GRAY_BTN);
            IOStyle.centerAt(g, this.font, Component.literal("★"), dx + dw - 14, cy + 9,
                    sel.starred ? IOStyle.ORANGE : IOStyle.TEXT_2);
            addRegion(dx + dw - 22, cy + 5, 16, 16, "mail_star", null, starExtra);
            String sender = sel.sender == null || sel.sender.isEmpty() ? "-" : sel.sender;
            textScaled(g, dx + 8, cy + 21, MAIL_TEXT,
                    Component.translatable("gui.module_shop.mail.from", sender), IOStyle.TEXT_2);
            textScaled(g, dx + 8, cy + 30, MAIL_TEXT,
                    Component.translatable("gui.module_shop.mail.time", mailTime(sel)), IOStyle.TEXT_2);
            String body = sel.content == null || sel.content.isEmpty() ? mailDescription(sel) : colorize(sel.content);
            // 附件区：两排格子（只放物品图标，不再写物品名），物品多于一屏时滚轮上下滑动。
            int attH = 46;
            int attY = cy + paneH - 26 - attH - 6;
            int bodyTop = cy + 42;
            int bodyH = Math.max(10, attY - 13 - bodyTop);
            // 正文：中文按字断行、超出高度时用滚轮/滑块上下看（换邮件时回到顶部）。
            if (!sel.mailId.equals(this.bodyMailId)) {
                this.bodyMailId = sel.mailId;
                this.mailBodyScroll = 0;
            }
            int bodyLineH = Math.max(6, (int) Math.ceil(10.0F * MAIL_TEXT));
            List<String> bodyLines = wrapLines(body, Math.max(8, (int) ((dw - 16) / MAIL_TEXT)));
            int bodyVisible = Math.max(1, bodyH / bodyLineH);
            this.bodyMaxScroll = Math.max(0, bodyLines.size() - bodyVisible);
            this.mailBodyScroll = Mth.clamp(this.mailBodyScroll, 0, this.bodyMaxScroll);
            for (int i = 0; i < bodyVisible; i++) {
                int lineIndex = i + this.mailBodyScroll;
                if (lineIndex >= bodyLines.size()) {
                    break;
                }
                textScaled(g, dx + 8, bodyTop + i * bodyLineH, MAIL_TEXT,
                        Component.literal(bodyLines.get(lineIndex)), IOStyle.TEXT);
            }
            this.bodyRect[0] = dx + 8;
            this.bodyRect[1] = bodyTop;
            this.bodyRect[2] = dw - 16;
            this.bodyRect[3] = bodyH;
            if (this.bodyMaxScroll > 0) {
                // 正文右侧细滚动条（滑块可看出还有多少没读到）。
                int trackX = dx + dw - 5;
                g.fill(trackX, bodyTop, trackX + 1, bodyTop + bodyH, IOStyle.HAIR);
                int thumbH = Math.max(8, bodyH * bodyVisible / Math.max(1, bodyLines.size()));
                int thumbY = bodyTop + (bodyH - thumbH) * this.mailBodyScroll / this.bodyMaxScroll;
                g.fill(trackX, thumbY, trackX + 1, thumbY + thumbH, IOStyle.BLUE);
            }
            // 附件
            textScaled(g, dx + 8, attY - 10, MAIL_TEXT,
                    Component.translatable("gui.module_shop.mail.attachment"), IOStyle.TEXT_2);
            // 到期提示右对齐放在「附件」同一行的右侧：之前它和「附件」画在同一坐标上，看起来就是字体重叠。
            if (sel.expireAt > 0L) {
                long days = Math.max(0L, (sel.expireAt - System.currentTimeMillis()) / 86_400_000L);
                textScaledRight(g, dx + dw - 10, attY - 10, MAIL_TEXT,
                        Component.translatable("gui.module_shop.mail.expire", days), IOStyle.RED);
            }
            IOStyle.rounded(g, dx + 6, attY, dw - 12, attH, 6, IOStyle.SLOT_INNER);
            java.util.List<net.minecraft.world.item.ItemStack> attItems = sel.itemStacks();
            if (!sel.mailId.equals(this.attachMailId)) {
                this.attachMailId = sel.mailId;
                this.attachRow = 0;
            }
            this.attachRect[0] = 0;
            this.attachRows = 2;
            if (!attItems.isEmpty()) {
                int cell = 20;
                int cols = Math.max(1, (dw - 20) / cell);
                int rows = Math.max(1, (attItems.size() + cols - 1) / cols);
                this.attachRows = rows;
                this.attachRow = Mth.clamp(this.attachRow, 0, Math.max(0, rows - 2));
                int startX = dx + 10;
                int startY = attY + 3;
                for (int r = 0; r < 2; r++) {
                    for (int c = 0; c < cols; c++) {
                        int index = (this.attachRow + r) * cols + c;
                        if (index >= attItems.size()) {
                            break;
                        }
                        net.minecraft.world.item.ItemStack stack = attItems.get(index);
                        int ix = startX + c * cell;
                        int iy = startY + r * cell;
                        Bricks.slot(g, ix, iy);
                        g.renderItem(stack, ix, iy);
                        // 数量角标（>1 才显示），不再额外写物品名
                        g.renderItemDecorations(this.font, stack, ix, iy);
                        if (mx >= ix && mx < ix + 18 && my >= iy && my < iy + 18) {
                            this.jeiHover = stack;
                            g.renderTooltip(this.font, stack, mx, my);
                        }
                    }
                }
                // 还有更多：右侧画上下小箭头（滚轮可滑动）
                if (rows > 2) {
                    int arx = dx + dw - 14;
                    if (this.attachRow > 0) {
                        g.fill(arx - 4, attY + 8, arx + 4, attY + 9, IOStyle.BLUE);
                        g.fill(arx - 2, attY + 6, arx + 2, attY + 7, IOStyle.BLUE);
                    }
                    if (this.attachRow < rows - 2) {
                        g.fill(arx - 4, attY + attH - 9, arx + 4, attY + attH - 8, IOStyle.BLUE);
                        g.fill(arx - 2, attY + attH - 7, arx + 2, attY + attH - 6, IOStyle.BLUE);
                    }
                    this.attachRect[0] = dx + 6;
                    this.attachRect[1] = attY;
                    this.attachRect[2] = dw - 12;
                    this.attachRect[3] = attH;
                }
            } else if (sel.currencyAmount > 0L) {
                textScaled(g, dx + 12, attY + 18, MAIL_TEXT,
                        Component.literal(ClientShopData.money(sel.currencyAmount)), IOStyle.ORANGE);
            } else {
                textScaled(g, dx + 12, attY + 18, MAIL_TEXT,
                        Component.translatable("gui.module_shop.mail.no_body"), IOStyle.TEXT_2);
            }
            // 领取 / 删除（按钮文字也用 0.8 倍字号：按钮只有 54px 宽，长一点的语言会溢出）
            boolean done = sel.type == cn.autoforged.module_shop.data.MailType.MONEY
                    ? sel.isViewed : sel.isClaimed;
            String claimKey = done ? "gui.module_shop.mail.claimed_done"
                    : (sel.type == cn.autoforged.module_shop.data.MailType.MONEY
                            ? "gui.module_shop.mail.view" : "gui.module_shop.mail.claim");
            int cbY = cy + paneH - 20;
            IOStyle.button(g, dx + dw - 62, cbY, 54, 14, Component.empty(), this.font,
                    this.pointerX, this.pointerY, !done, done ? IOStyle.Btn.GRAY : IOStyle.Btn.PRIMARY);
            textScaledCenter(g, dx + dw - 35, cbY + 4, MAIL_TEXT, Component.translatable(claimKey),
                    done ? IOStyle.TEXT_2 : IOStyle.TEXT);
            if (!done) {
                addRegion(dx + dw - 62, cbY, 54, 14, sel.type
                        == cn.autoforged.module_shop.data.MailType.MONEY ? "view_mail" : "claim_mail",
                        null, starExtra);
            }
            addRegion(dx + 6, cbY, 46, 14, "mail_delete", null, starExtra);
            drawSmallLabelButton(g, dx + 6, cbY, 46, 14,
                    Component.translatable("gui.module_shop.mail.delete"), false, false, true);
        }
        // ---------- 底栏 ----------
        int buttonY = barMid(cy, ch, 16);
        addRegion(cx + 4, buttonY, 96, 16, "claim_all_mail", null, null);
        drawButton(g, cx + 4, buttonY, 96, 16, Component.translatable("gui.module_shop.mail_claim_all"));
        addRegion(cx + 104, buttonY, 88, 16, "delete_read_mail", null, null);
        drawButton(g, cx + 104, buttonY, 88, 16, Component.translatable("gui.module_shop.mail_delete_read"));
        if (!mails.isEmpty()) {
            int prevX = cx + cw - 130;
            int nextX = cx + cw - 44;
            if (pages > 1) {
                addRegion(prevX, buttonY, 40, 16, "mail_prev", null, null);
                addRegion(nextX, buttonY, 40, 16, "mail_next", null, null);
                drawButton(g, prevX, buttonY, 40, 16, Component.translatable("gui.module_shop.page_prev"));
                drawButton(g, nextX, buttonY, 40, 16, Component.translatable("gui.module_shop.page_next"));
            }
            IOStyle.centerAt(g, this.font,
                    Component.translatable("gui.module_shop.page", this.mailPage + 1, pages),
                    cx + cw - 67, buttonY + 4, IOStyle.TEXT_2);
        }
    }

    /** 第七轮：按邮件业务类型取配色（列表状态点与标题色）。 */
    private static int kindColor(cn.autoforged.module_shop.data.MailEntry entry) {
        String kind = entry.kind == null ? "" : entry.kind;
        return switch (kind) {
            case "auction" -> 0xFFB388FF;
            case "stall" -> IOStyle.BLUE;
            case "trade" -> 0xFF4DD0E1;
            case "redeem" -> IOStyle.ORANGE;
            case "activity" -> IOStyle.GREEN;
            case "refund" -> IOStyle.TEXT_2;
            default -> IOStyle.ORANGE;
        };
    }

    /** 邮箱排序：星标优先，其次按时间倒序（最新在上）。 */
    private static List<cn.autoforged.module_shop.data.MailEntry> sortedMails() {
        List<cn.autoforged.module_shop.data.MailEntry> list =
                new ArrayList<>(ClientShopData.mail);
        list.sort((a, b) -> {
            if (a.starred != b.starred) {
                return a.starred ? -1 : 1;
            }
            return Long.compare(b.sentAt, a.sentAt);
        });
        return list;
    }

    /** 当前选中的邮件（没有则返回 null）。 */
    private static cn.autoforged.module_shop.data.MailEntry selectedMail(
            List<cn.autoforged.module_shop.data.MailEntry> mails) {
        for (var entry : mails) {
            if (entry.mailId.equals(ClientShopData.mailSelected)) {
                return entry;
            }
        }
        return mails.isEmpty() ? null : mails.get(0);
    }

    /** 邮件标题：优先模板标题（自动转颜色代码），否则按来源生成说明。 */
    private static String mailTitle(cn.autoforged.module_shop.data.MailEntry entry) {
        if (entry.title != null && !entry.title.isEmpty()) {
            return entry.title;
        }
        return mailDescription(entry);
    }

    /** 把 & 颜色代码转成 §（客户端渲染用）。 */
    private static String colorize(String text) {
        return text == null ? "" : text.replace('&', '\u00a7');
    }

    /** 邮件时间文本。 */
    private static String mailTime(cn.autoforged.module_shop.data.MailEntry entry) {
        if (entry.sentAt <= 0L) {
            return "-";
        }
        return java.time.Instant.ofEpochMilli(entry.sentAt)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }

    /** 按宽度自动换行绘制正文（支持 \n 与 § 颜色代码），最多画 5 行。 */
    private void drawWrapped(GuiGraphics g, int x, int y, int width, String text, int color) {
        drawWrapped(g, x, y, width, text, color, 50);
    }

    /** 同上，但最多画到 {@code maxHeight} 像素高（避免正文压到下方固定区域）。 */
    private void drawWrapped(GuiGraphics g, int x, int y, int width, String text, int color, int maxHeight) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int lineY = y;
        for (String paragraph : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (this.font.width(candidate.replace('\u00a7', '&')) > width && line.length() > 0) {
                    IOStyle.text(g, this.font, Component.literal(line.toString()), x, lineY, color);
                    lineY += 10;
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            IOStyle.text(g, this.font, Component.literal(line.toString()), x, lineY, color);
            lineY += 10;
            if (lineY > y + maxHeight) {
                return;
            }
        }
    }

    /** 需求：根据邮件来源生成说明文字（物品退还 / 星币到账 / 管理员发放）。 */
    private static String mailDescription(cn.autoforged.module_shop.data.MailEntry entry) {
        // 第八轮：一封邮件可带多件物品，说明文字用第一件代表（多件时加「等 N 件」）。
        java.util.List<net.minecraft.world.item.ItemStack> items = entry.itemStacks();
        String item = items.isEmpty() ? "-" : items.get(0).getHoverName().getString();
        String itemWithCount = items.isEmpty() ? "-"
                : items.get(0).getHoverName().getString() + "×" + items.get(0).getCount()
                        + (items.size() > 1 ? " 等 " + items.size() + " 件" : "");
        String sender = entry.sender == null || entry.sender.isEmpty() ? "-" : entry.sender;
        // 管理员发放的邮件单独说明：物品类显示物品名，金额类显示星币数。
        if ("admin_grant".equals(entry.source)) {
            return entry.type == cn.autoforged.module_shop.data.MailType.MONEY
                    ? Component.translatable("gui.module_shop.mail.desc.admin_grant_money", sender,
                            ClientShopData.money(entry.currencyAmount)).getString()
                    : Component.translatable("gui.module_shop.mail.desc.admin_grant_item", sender, itemWithCount)
                            .getString();
        }
        String key = switch (entry.source == null ? "" : entry.source) {
            case "stall_unsold" -> "gui.module_shop.mail.desc.stall_unsold";
            case "stall_bought" -> "gui.module_shop.mail.desc.stall_bought";
            case "auction_refund" -> "gui.module_shop.mail.desc.auction_refund";
            case "auction_unsold" -> "gui.module_shop.mail.desc.auction_unsold";
            case "auction_won" -> "gui.module_shop.mail.desc.auction_won";
            case "stall_sold" -> "gui.module_shop.mail.desc.stall_sold";
            case "auction_sold" -> "gui.module_shop.mail.desc.auction_sold";
            default -> "gui.module_shop.mail.desc.default";
        };
        if (entry.type == cn.autoforged.module_shop.data.MailType.MONEY) {
            return Component.translatable(key, sender, item, ClientShopData.money(entry.currencyAmount)).getString();
        }
        return Component.translatable(key, item).getString();
    }

    // =========================================================== 管理员配置（仅 /shop admin 打开）

    /** 某个管理页模块的显示名 lang key（找不到时退回「每日商店」）。 */
    private static String adminModuleName(String id) {
        for (AdminModule m : ADMIN_MODULES) {
            if (m.id().equals(id)) {
                return m.nameKey();
            }
        }
        return ADMIN_MODULES.get(0).nameKey();
    }

    // =========================================================== 签到（模块页）
    /** 签到日历一格的高度（比原来那个独立界面矮一档，正好塞进内容区）。 */
    private static final int SIGNIN_CELL_H = 16;
    /** 交易 / 转账页一屏显示几个在线玩家（一行 24px，5 行刚好不顶到底栏）。 */
    private static final int TRADE_PAGE_SIZE = 5;

    /**
     * 签到页：原来要「点按钮再弹一个窗口」的独立签到界面，现在直接画在内容区里（功能完全一样）。
     *
     * <p>自上而下：连续签到里程进度条（关闭累计算时整条隐藏）→ 年月导航 → 星期 → 日历 → 底栏「今日签到」。
     * 日历格子：已签到变绿、今天描蓝框、未来变灰、可补签的能点（二次确认后扣费）；
     * 管理员在「商店设置 → 签到」点「编辑日程」后，点日期即可新建 / 修改那天的日程。
     */
    private void drawSignIn(GuiGraphics g, int cx, int cy, int cw, int ch, int mx, int my) {
        this.signInTip = null;
        this.signInTipLines = null;
        if (ClientShopData.signInMonth == 0) {
            java.time.LocalDate now = java.time.LocalDate.now();
            ClientShopData.signInMonth = now.getYear() * 12 + now.getMonthValue() - 1;
        }
        int topY = cy + 2;
        // 进度条：关了「累计加成」也要画 —— 里程奖励和累计加成是两回事，
        // 没这条玩家就看不到「本轮第几天 / 还有几天到下一个档位」。
        if (ClientShopData.signInCumulative || hasSignInMilestone()) {
            drawSignInProgress(g, cx, topY, cw, mx, my);
            topY += 19;
        }
        drawSignInCalendar(g, cx, topY, cw, false, true, mx, my);
        drawSignInFooter(g, cx, cy, cw, ch, mx, my);
    }

    /**
     * 签到日历（**签到页和商店设置 → 签到共用同一份代码**，所以两处永远同步）。
     *
     * @param editable    管理员编辑态：点日期 = 新建 / 修改那天的日程（此时不注册补签点击）
     * @param allowMakeup 允许点过去日期补签（只有玩家签到页开；管理页点了会误扣费，所以关掉）
     * @return 日历底边 y
     */
    private int drawSignInCalendar(GuiGraphics g, int cx, int topY, int cw, boolean editable, boolean allowMakeup,
            int mx, int my) {
        if (ClientShopData.signInMonth == 0) {
            java.time.LocalDate now = java.time.LocalDate.now();
            ClientShopData.signInMonth = now.getYear() * 12 + now.getMonthValue() - 1;
        }
        int gridX = cx + 4;
        int cellW = Math.max(22, (cw - 8) / 7);
        int gridW = cellW * 7;
        java.time.YearMonth ym = java.time.YearMonth.of(ClientShopData.signInMonth / 12,
                ClientShopData.signInMonth % 12 + 1);
        java.time.LocalDate today = java.time.LocalDate.now();
        java.util.Locale locale = Minecraft.getInstance().getLanguageManager().getJavaLocale();

        // ---- 年月导航：年份在左、月份在右，各自带翻页 ----
        drawSignInArrow(g, gridX, topY, "<<", "signin_year_prev", mx, my);
        Component yearText = Component.literal(Integer.toString(ym.getYear()));
        g.drawString(this.font, yearText, gridX + 14, topY + 2, IOStyle.TEXT, false);
        drawSignInArrow(g, gridX + 14 + this.font.width(yearText) + 2, topY, ">>", "signin_year_next", mx, my);

        String monthText = ym.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, locale);
        int monthTail = 14 + this.font.width(monthText) + 2 + 12;
        int monthX = Math.max(gridX + 88, gridX + gridW - monthTail);
        drawSignInArrow(g, monthX, topY, "<", "signin_month_prev", mx, my);
        g.drawString(this.font, monthText, monthX + 14, topY + 2, IOStyle.TEXT, false);
        drawSignInArrow(g, monthX + 14 + this.font.width(monthText) + 2, topY, ">", "signin_month_next", mx, my);

        // ---- 星期 ----
        int weekY = topY + 14;
        for (int c = 0; c < 7; c++) {
            String dow = java.time.DayOfWeek.of(c + 1).getDisplayName(java.time.format.TextStyle.NARROW, locale);
            drawSmallLabel(g, gridX + c * cellW + cellW / 2 - (int) (this.font.width(dow) * 0.4F), weekY,
                    Component.literal(dow), IOStyle.TEXT_2);
        }

        // ---- 日历 ----
        int cellY = weekY + 10;
        int firstOffset = ym.atDay(1).getDayOfWeek().getValue() - 1;
        int length = ym.lengthOfMonth();
        for (int d = 1; d <= length; d++) {
            java.time.LocalDate date = ym.atDay(d);
            int idx = firstOffset + d - 1;
            int cellX = gridX + (idx % 7) * cellW;
            int cellYY = cellY + (idx / 7) * SIGNIN_CELL_H;
            boolean isToday = date.equals(today);
            boolean signed = ClientShopData.signInDays.contains(date.toEpochDay());
            boolean future = date.isAfter(today);
            boolean canMakeup = !signed && date.isBefore(today)
                    && date.toEpochDay() >= today.toEpochDay() - ClientShopData.signInMakeupDays;
            List<ClientShopData.ActivityView> acts = signInActivitiesOf(date.toEpochDay());
            IOStyle.rounded(g, cellX + 1, cellYY + 1, cellW - 2, SIGNIN_CELL_H - 2, 3,
                    signed ? IOStyle.GREEN_TINT : future ? IOStyle.DISABLED_BG : IOStyle.CARD);
            if (isToday) {
                IOStyle.ring(g, cellX + 1, cellYY + 1, cellW - 2, SIGNIN_CELL_H - 2, 3, IOStyle.BLUE);
            }
            String dayText = Integer.toString(d);
            g.drawString(this.font, dayText, cellX + (cellW - this.font.width(dayText)) / 2, cellYY + 3,
                    signed ? IOStyle.GREEN_DARK : future ? IOStyle.DISABLED_TEXT : IOStyle.TEXT, false);
            // 日程：格子底部一条彩色短线（最多 3 条），颜色由管理员在日程编辑里定。
            for (int k = 0; k < acts.size() && k < 3; k++) {
                int barW = Math.max(3, (cellW - 8) / Math.min(3, Math.max(1, acts.size())));
                IOStyle.rounded(g, cellX + 4 + k * barW, cellYY + SIGNIN_CELL_H - 5, barW - 1, 2, 1,
                        acts.get(k).color == 0 ? IOStyle.ORANGE : acts.get(k).color);
            }
            if (IOStyle.inside(cellX + 1, cellYY + 1, cellW - 2, SIGNIN_CELL_H - 2, mx, my)) {
                if (!acts.isEmpty()) {
                    List<Component> lines = new ArrayList<>();
                    for (int k = 0; k < acts.size(); k++) {
                        ClientShopData.ActivityView act = acts.get(k);
                        if (k > 0) {
                            lines.add(Component.literal(" "));
                        }
                        lines.add(Component.literal(act.title)
                                .withStyle(net.minecraft.ChatFormatting.BOLD));
                        if (act.note != null && !act.note.isEmpty()) {
                            addSignInWrapped(lines, act.note, net.minecraft.ChatFormatting.GRAY);
                        }
                        if (act.money > 0L) {
                            lines.add(Component.translatable("gui.module_shop.signin.activity_money",
                                    ClientShopData.money(act.money)).withStyle(net.minecraft.ChatFormatting.GOLD));
                        }
                        String itemNames = signInItemNames(act.itemText);
                        if (!itemNames.isEmpty()) {
                            addSignInWrapped(lines, Component.translatable(
                                    "gui.module_shop.signin.activity_items", itemNames).getString(),
                                    net.minecraft.ChatFormatting.GRAY);
                        }
                    }
                    this.signInTipLines = lines;
                } else if (allowMakeup && canMakeup) {
                    this.signInTip = Component.translatable("gui.module_shop.signin.makeup_tip",
                            ClientShopData.money(ClientShopData.signInMakeupCost), date.toString());
                } else if (isToday) {
                    this.signInTip = Component.translatable(signed
                            ? "gui.module_shop.signin.done" : "gui.module_shop.signin.do");
                }
            }
            if (ClientShopData.canAdmin && editable) {
                CompoundTag extra = new CompoundTag();
                extra.putLong("day", date.toEpochDay());
                // 右上角小方块＝右键清空那天。必须**先注册**：点击派发命中第一个区域就吃掉整次点击，
                // 后注册的话右键会被下面那个整格区域先接走，清空永远点不出来。
                if (!acts.isEmpty()) {
                    addRegion(cellX + cellW - 8, cellYY + 1, 6, 6, "signin_activity_edit",
                            "signin_activity_clear", extra);
                }
                addRegion(cellX + 1, cellYY + 1, cellW - 2, SIGNIN_CELL_H - 2, "signin_activity_edit", null, extra);
            } else if (allowMakeup && canMakeup) {
                CompoundTag extra = new CompoundTag();
                extra.putLong("day", date.toEpochDay());
                addRegion(cellX + 1, cellYY + 1, cellW - 2, SIGNIN_CELL_H - 2, "signin_makeup", null, extra);
            }
        }
        // 日历里的悬停提示统一在这里画：画在格子里会被后画的格子盖住。
        if (this.signInTipLines != null && !this.signInTipLines.isEmpty()) {
            g.renderComponentTooltip(this.font, this.signInTipLines, mx, my);
            this.signInTipLines = null;
        } else if (this.signInTip != null) {
            g.renderTooltip(this.font, this.signInTip, mx, my);
            this.signInTip = null;
        }
        return cellY + (firstOffset + length + 6) / 7 * SIGNIN_CELL_H;
    }

    /** 签到页底栏：今日签到按钮 + 状态文字（商店设置里的日历不画这一条）。 */
    private void drawSignInFooter(GuiGraphics g, int cx, int cy, int cw, int ch, int mx, int my) {
        // ---- 底栏：今日签到按钮 + 状态 ----
        boolean done = ClientShopData.signInDone;
        int btnY = barMid(cy, ch, 14);
        int btnW = 76;
        IOStyle.button(g, cx + 4, btnY, btnW, 14,
                Component.translatable(done ? "gui.module_shop.signin.done" : "gui.module_shop.signin.do"),
                this.font, mx, my, !done && ClientShopData.signInEnabled,
                done ? IOStyle.Btn.GRAY : IOStyle.Btn.PRIMARY);
        if (!done && ClientShopData.signInEnabled) {
            addRegion(cx + 4, btnY, btnW, 14, "sign_in", null, null);
        }
        if (IOStyle.inside(cx + 4, btnY, btnW, 14, mx, my)) {
            this.signInTip = Component.translatable("gui.module_shop.signin.gain_tip",
                    ClientShopData.money(ClientShopData.signInBase + signInTierBonus()));
        }
        int statusX = cx + 4 + btnW + 8;
        drawBarLabel(g, statusX, barTextY(cy, ch), cx + cw - 6,
                Component.translatable(done ? "gui.module_shop.signin.status_done"
                        : "gui.module_shop.signin.status", ClientShopData.signInStreak,
                        ClientShopData.money(ClientShopData.signInBase + signInTierBonus())), COLOR_MUTED);

        // 悬浮提示统一留到最后画：画在格子里会被后画的格子盖住。
        if (this.signInTipLines != null && !this.signInTipLines.isEmpty()) {
            g.renderComponentTooltip(this.font, this.signInTipLines, mx, my);
            this.signInTipLines = null;
        } else if (this.signInTip != null) {
            g.renderTooltip(this.font, this.signInTip, mx, my);
            this.signInTip = null;
        }
    }

    /**
     * 签到里程进度条（做细做小）：一条 4px 细轨 + 每个里程一个 5px 圆点，到达的点变绿，
     * 下面用 0.7 倍小字标「3d」，点太密时自动跳过标签避免压字；悬停圆点看该里程的奖励。
     */
    private void drawSignInProgress(GuiGraphics g, int cx, int y, int cw, int mx, int my) {
        int cycle = Math.max(1, ClientShopData.signInCycleDays);
        int streak = ClientShopData.signInStreak;
        int roundDay = streak <= 0 ? 0 : (streak - 1) % cycle + 1;
        int round = streak <= 0 ? 1 : (streak - 1) / cycle + 1;
        // 右端留出「第 N 轮」的位置：里程是每轮重来的，不写轮次玩家会以为 7 天之后就到头了。
        Component roundText = Component.translatable("gui.module_shop.signin.round", round);
        int badgeW = Math.round(this.font.width(roundText) * 0.7F) + 8;
        int px = cx + 6;
        int pw = Math.max(40, cw - 12 - badgeW);
        int barY = y + 3;
        int barH = 4;
        float ratio = Math.min(1F, roundDay / (float) cycle);
        IOStyle.rounded(g, px, barY, pw, barH, 2, IOStyle.SEG_TRACK);
        if (ratio > 0F) {
            IOStyle.rounded(g, px, barY, Math.max(barH, Math.round(pw * ratio)), barH, 2, IOStyle.GREEN);
        }
        int lastRight = Integer.MIN_VALUE;
        int[] days = ClientShopData.signInMileDays;
        for (int i = 0; i < days.length; i++) {
            // 档位天数大于一轮长度的永远到不了（例如每轮 7 天却配了 14 天档），不画。
            if (days[i] <= 0 || days[i] > cycle) {
                continue;
            }
            int dx = px + Math.round(pw * days[i] / (float) cycle);
            dx = Mth.clamp(dx, px + 5, px + pw - 5);
            boolean reached = roundDay >= days[i];
            int dotY = barY + (barH - 5) / 2;
            IOStyle.rounded(g, dx - 2, dotY, 5, 5, 2, reached ? IOStyle.GREEN : IOStyle.TOGGLE_OFF);
            if (!reached) {
                IOStyle.ring(g, dx - 2, dotY, 5, 5, 2, IOStyle.HAIR);
            }
            Component label = Component.literal(days[i] + "d");
            int lw = Math.round(this.font.width(label) * 0.7F);
            int lx = Mth.clamp(dx - lw / 2, cx + 2, px + pw - lw);
            if (lx > lastRight + 3) {
                g.pose().pushPose();
                g.pose().translate(lx, barY + 8, 0.0F);
                g.pose().scale(0.7F, 0.7F, 1.0F);
                g.drawString(this.font, label, 0, 0, reached ? IOStyle.GREEN : IOStyle.TEXT_2, false);
                g.pose().popPose();
                lastRight = lx + lw;
            }
            if (IOStyle.inside(dx - 6, dotY - 2, 12, 9, mx, my)) {
                long money = i < ClientShopData.signInMileMoney.length ? ClientShopData.signInMileMoney[i] : 0L;
                this.signInTip = Component.translatable("gui.module_shop.signin.mile_tip", days[i],
                        ClientShopData.money(money), ClientShopData.money(signInTierBonusAt(days[i])));
            }
        }
        // 右端「第 N 轮」：每满一轮，里程档位重新可领。
        drawSmallLabel(g, cx + cw - 4 - Math.round(this.font.width(roundText) * 0.7F), barY - 2,
                roundText, IOStyle.BLUE);
    }

    /** 有没有「本轮内能达成、且有奖励」的里程档位（有就说明进度条值得画）。 */
    private static boolean hasSignInMilestone() {
        int cycle = Math.max(1, ClientShopData.signInCycleDays);
        for (int i = 0; i < ClientShopData.signInMileDays.length; i++) {
            int day = ClientShopData.signInMileDays[i];
            long money = i < ClientShopData.signInMileMoney.length ? ClientShopData.signInMileMoney[i] : 0L;
            if (day > 0 && day <= cycle && money > 0L) {
                return true;
            }
        }
        return false;
    }

    /** 签到页的翻页小箭头（12×12）。 */
    private void drawSignInArrow(GuiGraphics g, int x, int y, String label, String action, int mx, int my) {
        boolean hover = IOStyle.inside(x, y, 12, 12, mx, my);
        IOStyle.smallButton(g, x, y, 12, 12, Component.literal(label), this.font, hover, IOStyle.GRAY_BTN,
                IOStyle.GRAY_BTN_HOVER, IOStyle.TEXT, mx, my);
        addRegion(x, y, 12, 12, action, null, null);
    }

    /** 把一段可能很长的正文按内容区宽度折行，逐行加进 tooltip。 */
    private void addSignInWrapped(List<Component> lines, String text, net.minecraft.ChatFormatting style) {
        String rest = text;
        int maxW = 148;
        while (!rest.isEmpty()) {
            String cut = this.font.plainSubstrByWidth(rest, maxW);
            if (cut.isEmpty()) {
                cut = rest.substring(0, 1);
            }
            lines.add(Component.literal(cut).withStyle(style));
            rest = rest.substring(cut.length());
        }
    }

    /** 把「id xN,id2 xM」解析成「物品名×N、…」文本（日程悬浮窗用；解析不出来的直接跳过）。 */
    private static String signInItemNames(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String piece : text.split(",")) {
            String raw = piece.trim();
            if (raw.isEmpty()) {
                continue;
            }
            int count = 1;
            int idx = raw.lastIndexOf('x');
            if (idx > 0) {
                try {
                    count = Math.max(1, Math.min(64, Integer.parseInt(raw.substring(idx + 1).trim())));
                    raw = raw.substring(0, idx).trim();
                } catch (NumberFormatException ignored) {
                }
            }
            net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(raw);
            if (id == null) {
                continue;
            }
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(new ItemStack(item).getHoverName().getString()).append("×").append(count);
        }
        return sb.toString();
    }

    /** 某一天的全部日程。 */
    private static List<ClientShopData.ActivityView> signInActivitiesOf(long day) {        List<ClientShopData.ActivityView> list = new ArrayList<>();
        for (ClientShopData.ActivityView view : ClientShopData.activityDays) {
            if (view.day == day) {
                list.add(view);
            }
        }
        return list;
    }

    /** 当前连续天数对应的累计签到加成（关闭累计签到时为 0）。 */
    private static long signInTierBonus() {
        return signInTierBonusAt(ClientShopData.signInStreak);
    }

    /** 指定天数对应的累计签到加成（取不超过该天数的最高档位）。 */
    private static long signInTierBonusAt(int day) {
        if (!ClientShopData.signInCumulative) {
            return 0L;
        }
        long bonus = 0L;
        for (int i = 0; i < ClientShopData.signInTierDays.length; i++) {
            if (day >= ClientShopData.signInTierDays[i]) {
                bonus = Math.max(bonus, i < ClientShopData.signInTierBonus.length
                        ? ClientShopData.signInTierBonus[i] : 0L);
            }
        }
        return bonus;
    }

    /** 交易 / 转账：在线玩家选择（同一个渲染，{@code transfer} 只影响文案和点击后的行为）。 */
    private void drawPlayerPicker(GuiGraphics g, int cx, int cy, int cw, int ch, int mx, int my, boolean transfer) {
        // 搜索框底：真正的输入框是 EditBox（文字由它在 super.render 里画，会自动盖在这层底上）。
        IOStyle.rounded(g, cx + 4, cy, cw - 8, 17, 3, IOStyle.FIELD_BG);
        if (this.tradeSearchBox != null && this.tradeSearchBox.isFocused()) {
            IOStyle.ring(g, cx + 4, cy, cw - 8, 17, 3, IOStyle.BORDER_HOVER);
        }
        List<ClientShopData.OnlinePlayer> players = tradePlayers();
        int pages = Math.max(1, (players.size() + TRADE_PAGE_SIZE - 1) / TRADE_PAGE_SIZE);
        this.tradePage = Mth.clamp(this.tradePage, 0, pages - 1);
        int rowH = 24;
        int top = cy + 20;
        for (int i = 0; i < TRADE_PAGE_SIZE; i++) {
            int index = this.tradePage * TRADE_PAGE_SIZE + i;
            if (index >= players.size()) {
                break;
            }
            ClientShopData.OnlinePlayer player = players.get(index);
            int ry = top + i * rowH;
            boolean hover = IOStyle.inside(cx + 4, ry, cw - 8, rowH - 2, mx, my);
            IOStyle.card(g, cx + 4, ry, cw - 8, rowH - 2, 3);
            if (hover) {
                IOStyle.ring(g, cx + 4, ry, cw - 8, rowH - 2, 3, IOStyle.BORDER_HOVER);
            }
            // 左侧 3px 色条：交易=金、转账=蓝，一眼区分两页。
            IOStyle.rounded(g, cx + 8, ry + 4, 3, rowH - 10, 1, transfer ? IOStyle.BLUE : IOStyle.ORANGE);
            if (player.uuid != null) {
                Skins.drawHead(g, player.uuid, cx + 13, ry + 3, 16);
            }
            g.drawString(this.font, player.name, cx + 33, ry + 7, IOStyle.TEXT, false);
            drawSmallLabelRight(g, cx + cw - 10, ry + 8,
                    Component.translatable(transfer ? "gui.module_shop.transfer.click_to_transfer"
                            : "gui.module_shop.trade.click_to_trade"), IOStyle.TEXT_3);
            CompoundTag extra = new CompoundTag();
            extra.putString("name", player.name);
            addRegion(cx + 4, ry, cw - 8, rowH - 2, "trade_pick", null, extra);
        }
        if (players.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.trade.no_online"),
                    cx + cw / 2, top + 44, COLOR_MUTED);
        }
        // 底栏：交易页有「交易请求」入口（带红点）；有分页时右侧放翻页 + 页码。
        int barY = barMid(cy, ch, 14);
        if (!transfer) {
            int bw = 66;
            addRegion(cx + 4, barY, bw, 14, "trade_requests_open", null, null);
            drawSmallLabelButton(g, cx + 4, barY, bw, 14,
                    Component.translatable("gui.module_shop.trade.requests"),
                    IOStyle.inside(cx + 4, barY, bw, 14, mx, my), false, false);
            ClientNotifications.drawBadge(g, this.font, NotificationType.TRADE, cx + 4 + bw, barY);
        }
        if (pages > 1) {
            int nextX = cx + cw - 4 - 42;
            int prevX = nextX - 4 - 42;
            addRegion(prevX, barY, 42, 14, "trade_page_prev", null, null);
            drawSmallLabelButton(g, prevX, barY, 42, 14, Component.translatable("gui.module_shop.page_prev"),
                    IOStyle.inside(prevX, barY, 42, 14, mx, my), false, false);
            addRegion(nextX, barY, 42, 14, "trade_page_next", null, null);
            drawSmallLabelButton(g, nextX, barY, 42, 14, Component.translatable("gui.module_shop.page_next"),
                    IOStyle.inside(nextX, barY, 42, 14, mx, my), false, false);
            Component pageLabel = Component.translatable("gui.module_shop.page", this.tradePage + 1, pages);
            drawSmallLabel(g, prevX - 8 - Math.round(this.font.width(pageLabel) * 0.8F), barTextY(cy, ch),
                    pageLabel, COLOR_MUTED);
        }
    }

    /** 交易 / 转账页的玩家列表：去掉自己 + 按搜索框过滤。 */
    private List<ClientShopData.OnlinePlayer> tradePlayers() {
        String self = ClientShopData.playerName;
        String query = this.tradeSearchBox == null ? "" : this.tradeSearchBox.getValue().trim().toLowerCase();
        List<ClientShopData.OnlinePlayer> result = new ArrayList<>();
        for (ClientShopData.OnlinePlayer player : ClientShopData.onlinePlayers) {
            if (player.name.equals(self)) {
                continue;
            }
            if (!query.isEmpty() && !player.name.toLowerCase().contains(query)) {
                continue;
            }
            result.add(player);
        }
        return result;
    }

    /** 物品名（只要名字本身）：去掉 § 格式符和「私用区字符」——资源包/模组常把私用区映射成图标，
     *  在列表里会渲染成一个个小贴图（看起来像名字后面跟着几张小图），这里直接剔掉。 */
    private static String plainName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        String raw = stack.getHoverName().getString();
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\u00a7') {
                i++;                      // 跳过 § 后面的格式码
                continue;
            }
            if (c >= '\ue000' && c <= '\uf8ff') {
                continue;                 // 私用区（字体图标）
            }
            if (Character.isISOControl(c)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /** 列表里的物品名：纯文本 + 统一颜色（不继承物品自定义名字的颜色/样式）。 */
    private Component plainNameComponent(ItemStack stack) {
        return Component.literal(plainName(stack));
    }

    private void drawAdmin(GuiGraphics g, int cx, int cy, int cw, int ch, int mx, int my) {
        boolean mall = "mall".equals(adminMode);
        boolean ship = "shipping".equals(adminMode);
        boolean stalls = "stall".equals(adminMode);
        boolean demands = "demand".equals(adminMode);
        boolean signin = "signin".equals(adminMode);
        boolean shop = !mall && !ship && !stalls && !demands && !signin && !"buyback".equals(adminMode);
        // 以后新增可编辑模块，只要往 ADMIN_MODULES 里加一行，这里会自动多出一项。
        Component pickLabel = Component.translatable("gui.module_shop.admin_select_module");
        int ddW = (int) (this.font.width(pickLabel) * 0.8F) + 24;
        int ddListW = ddW;
        for (AdminModule m : ADMIN_MODULES) {
            ddListW = Math.max(ddListW,
                    (int) (this.font.width(tabLabelWithOriginal(m.id())) * 0.8F) + 24);
        }
        boolean ddHover = IOStyle.inside(cx + 4, cy + 2, ddW, 14, mx, my);
        drawSmallLabelButton(g, cx + 4, cy + 2, ddW, 14, pickLabel, ddHover, this.adminModuleOpen, false);
        IOStyle.triangleDown(g, cx + 4 + ddW - 9, cy + 8, 3, 3,
                this.adminModuleOpen ? IOStyle.BLUE : IOStyle.TEXT_2);
        addRegion(cx + 4, cy + 2, ddW, 14, "admin_module_menu", null, null);
        // 顶部右半边：全局功能按钮「发放物品 / 交易记录」搬到上面来（与编辑模块无关，放一起更好找）。
        int topBtnW = 62;
        int topBtnX2 = cx + cw - 4 - topBtnW;
        addRegion(topBtnX2, cy + 2, topBtnW, 14, "admin_history_gui", null, null);
        drawSmallLabelButton(g, topBtnX2, cy + 2, topBtnW, 14,
                Component.translatable("gui.module_shop.admin_history"),
                IOStyle.inside(topBtnX2, cy + 2, topBtnW, 14, mx, my));
        int topBtnX1 = topBtnX2 - topBtnW - 4;
        addRegion(topBtnX1, cy + 2, topBtnW, 14, "admin_grant_open", null, null);
        drawSmallLabelButton(g, topBtnX1, cy + 2, topBtnW, 14,
                Component.translatable("gui.module_shop.admin_grant_open"),
                IOStyle.inside(topBtnX1, cy + 2, topBtnW, 14, mx, my));
        // 「当前编辑：X」写在两者之间（标题行里也有一份，这里只在放得下时显示）
        int currentX = cx + 4 + ddW + 8;
        int currentRight = topBtnX1 - 6;
        if (currentRight - currentX > 40) {
            drawSmallLabel07(g, currentX, cy + 6, IOStyle.ellipsize(this.font,
                            Component.translatable("gui.module_shop.admin_current",
                                    tabLabelWithOriginal(adminMode)),
                            currentRight - currentX),
                    COLOR_GOLD);
        }
        // 下拉面板的位置：紧贴按钮下方。**被它盖住的那几行商品不画**——物品图标属于另一批渲染，
        // 会穿透面板浮在上面（编辑布局那边踩过同一个坑）。
        int ddListY = cy + 18;
        int ddRowH = 14;
        int ddListH = ADMIN_MODULES.size() * ddRowH + 4;
        int ddListBottom = ddListY + ddListH;

        List<ConfiguredOffer> pool = (stalls || demands || signin) ? List.of()
                : (mall ? ClientShopData.adminMall
                        : (ship ? ClientShopData.adminShipping
                                : (shop ? ClientShopData.adminShop : ClientShopData.adminBuyback)));
        int pageSize = ADMIN_PAGE_SIZE;
        int pages = Math.max(1, (pool.size() + pageSize - 1) / pageSize);
        adminPage = Mth.clamp(adminPage, 0, pages - 1);
        for (int i = 0; i < pageSize; i++) {
            int index = adminPage * pageSize + i;
            if (index >= pool.size()) {
                break;
            }
            ConfiguredOffer offer = pool.get(index);
            // 出货箱的规则行：格式错误的那条**整行标红**，并显示它的原文（一眼就知道该删哪一条）。
            String ruleError = ship && index < ClientShopData.adminShippingErrors.size()
                    ? ClientShopData.adminShippingErrors.get(index) : "";
            boolean broken = ship && !ruleError.isEmpty();
            String ruleRaw = ship && index < ClientShopData.adminShippingRaw.size()
                    ? ClientShopData.adminShippingRaw.get(index) : "";
            int y = cy + 22 + i * 22;
            if (this.adminModuleOpen && y - 1 < ddListBottom) {
                continue;
            }
            if (broken) {
                IOStyle.rounded(g, cx + 4, y - 1, cw - 8, 20, 0, 0x33C0392B);
                IOStyle.ring(g, cx + 4, y - 1, cw - 8, 20, 0, IOStyle.RED);
                g.drawString(this.font, IOStyle.ellipsize(this.font, Component.literal(ruleRaw), cw - 40).getString(),
                        cx + 10, y + 1, IOStyle.RED, false);
                drawSmallLabel(g, cx + 10, y + 12, Component.translatable(
                        "gui.module_shop.shipping.row_broken"), IOStyle.RED);
                CompoundTag brokenExtra = new CompoundTag();
                brokenExtra.putString("mode", adminMode);
                brokenExtra.putInt("index", index);
                addRegion(cx + 4, y - 1, cw - 8, 20, "admin_remove", "admin_remove_broken", brokenExtra);
                continue;
            }
            IOStyle.card(g, cx + 4, y - 1, cw - 8, 20, 6);
            g.fill(cx + 6, y, cx + 12, y + 12, 0xFF000000 | offer.rarity.color());
            Bricks.slot(g, cx + 16, y);
            g.renderItem(offer.stack, cx + 16, y);
            g.renderItemDecorations(this.font, offer.stack, cx + 16, y);
            g.drawString(this.font, offer.stack.getHoverName(), cx + 40, y + 1, COLOR_TEXT, false);
            if (!ship) {
                // 出货箱的"条目"是兑换规则，没有品质可言，这一列留空。
                g.drawString(this.font, Component.translatable(offer.rarity.translationKey()), cx + 130, y + 1,
                        offer.rarity.color(), true);
            }
            g.drawString(this.font, ClientShopData.money(offer.price), cx + 176, y + 1, COLOR_GOLD, false);
            // 需求 6/7：显示管理员配置的数量（未配置时展示实际生效的默认上限）。
            // 购物城的数量字段是**库存**（-1 = 无限）、出货箱的是**每日份数**（0 = 不限），语义不同所以分开显示。
            Component amountText;
            if (mall) {
                amountText = offer.amount < 0
                        ? Component.translatable("gui.module_shop.mall.stock_unlimited")
                        : Component.translatable("gui.module_shop.admin_stock", offer.amount);
            } else if (ship) {
                // 规则条目没有"每日不限"这种占位文字：设置了才显示「每日 N」，没设置就留空。
                amountText = offer.amount <= 0 ? Component.empty()
                        : Component.translatable("gui.module_shop.admin_daily", offer.amount);
            } else {
                int effectiveAmount = shop ? offer.amountOr(offer.rarity.purchaseLimit())
                        : offer.amountOr(Config.INSTANCE.buybackCapacity.get());
                amountText = Component.translatable("gui.module_shop.admin_amount", effectiveAmount);
            }
            g.drawString(this.font, amountText, cx + 244, y + 1, COLOR_MUTED, true);
            CompoundTag extra = new CompoundTag();
            extra.putString("mode", adminMode);
            extra.putInt("index", index);
            addRegion(cx + 14, y - 1, cw - 18, 20, "admin_edit_gui", "admin_remove", extra);
            if (mx >= cx + 16 && mx < cx + 32 && my >= y && my < y + 16) {
                this.jeiHover = offer.stack;
                g.renderTooltip(this.font, offer.stack, mx, my);
            }
        }
        if (pool.isEmpty() && !signin && !this.adminModuleOpen) {
            // 摊位页没有"管理员条目"：这里换成设置说明，避免显示成「没有条目」让人以为坏了。
            // 「选择模块」下拉展开时整块跳过：物品图标是另一批渲染，会**穿透**下拉面板浮在上面
            //（玩家求购页之前就是图标压在「每日收购」那行字上），所以先不画，等面板收起再画。
            if (stalls) {
                drawStallExpireHint(g, cx, cy, cw);
            } else if (demands) {
                drawDemandAdminList(g, cx, cy, cw, ch, mx, my);
            } else {
                IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.admin_empty"), cx + cw / 2, cy + 60, COLOR_MUTED);
            }
        }
        // 「选择模块编辑」下拉：最后画，盖在商品行之上；点一项即切到该模块的编辑界面。
        if (this.adminModuleOpen) {
            IOStyle.card(g, cx + 4, ddListY, ddListW, ddListH, 0);
            for (int i = 0; i < ADMIN_MODULES.size(); i++) {
                AdminModule m = ADMIN_MODULES.get(i);
                int ry = ddListY + 2 + i * ddRowH;
                boolean current = m.id().equals(adminMode);
                boolean rowHover = IOStyle.inside(cx + 6, ry, ddListW - 4, ddRowH - 1, mx, my);
                IOStyle.rounded(g, cx + 6, ry, ddListW - 4, ddRowH - 1, 0,
                        current ? IOStyle.BLUE_TINT : (rowHover ? IOStyle.ROW_HOVER : IOStyle.SIDEBAR));
                IOStyle.ring(g, cx + 6, ry, ddListW - 4, ddRowH - 1, 0,
                        current ? IOStyle.BORDER_HOVER : IOStyle.BORDER_DIM);
                drawSmallLabel(g, cx + 12, ry + 3, tabLabelWithOriginal(m.id()),
                        current ? IOStyle.BLUE : IOStyle.TEXT);
                if (current) {
                    // 当前模块的勾选方块
                    g.fill(cx + ddListW - 13, ry + 4, cx + ddListW - 9, ry + 8, IOStyle.BLUE);
                }
                CompoundTag pick = new CompoundTag();
                pick.putString("id", m.id());
                addRegion(cx + 6, ry, ddListW - 4, ddRowH - 1, "admin_module_pick", null, pick);
            }
        }
        // 底部两行等宽小按钮，互不遮挡：第一行功能按钮（底栏上方 16px），第二行翻页 + 操作提示（底栏内）。
        int rowA = barTop(cy, ch) - 16;
        int rowB = barTop(cy, ch) + 1;
        // 第一行按钮：添加条目 / （模块专属：刷新参数 | 导入模组 | 每日额度 | 下架时间）/ 编辑布局；
        // 购物城多一个「补货设置」，所以那一行是 4 个按钮（等宽，文字都短，不会挤）。
        // 摊位页没有「添加条目」（玩家自己上架），只有「下架时间」+「编辑布局」两个按钮。
        int columns = mall ? 5 : (stalls ? 2 : (demands ? 3 : (signin ? 4 : 3)));
        int btnW = Math.max(44, (cw - 8 - (columns - 1) * 6) / columns);
        int btnGap = 6;
        if (signin) {
            // 签到：所有设置都集中在这里（选择模块 → 签到）。
            int s1 = cx + 4;
            addRegion(s1, rowA, btnW, 14, "admin_signin_set", null, null);
            drawSmallLabelButton(g, s1, rowA, btnW, 14,
                    Component.translatable("gui.module_shop.signin.setting_short"),
                    IOStyle.inside(s1, rowA, btnW, 14, mx, my), false, false);
            int s2 = s1 + btnW + btnGap;
            addRegion(s2, rowA, btnW, 14, "admin_signin_cumulative_toggle", null, null);
            drawSmallLabelButton(g, s2, rowA, btnW, 14,
                    Component.translatable("gui.module_shop.signin.cumulative"),
                    IOStyle.inside(s2, rowA, btnW, 14, mx, my), ClientShopData.signInCumulative, false);
            int s3 = s2 + btnW + btnGap;
            addRegion(s3, rowA, btnW, 14, "admin_signin_auto_toggle", null, null);
            drawSmallLabelButton(g, s3, rowA, btnW, 14,
                    Component.translatable("gui.module_shop.signin.auto"),
                    IOStyle.inside(s3, rowA, btnW, 14, mx, my), ClientShopData.signInAuto, false);
            // 日程编辑也集中到这里：点它跳到签到页并进入「编辑日程」态，点日历上的日期即可新建 / 修改。
            int s4 = s3 + btnW + btnGap;
            addRegion(s4, rowA, btnW, 14, "admin_signin_schedule", null, null);
            drawSmallLabelButton(g, s4, rowA, btnW, 14,
                    Component.translatable("gui.module_shop.signin.schedule"),
                    IOStyle.inside(s4, rowA, btnW, 14, mx, my), this.signInEdit, false);
        } else {
            if (!stalls && !demands) {
                CompoundTag addExtra = new CompoundTag();
                addExtra.putString("mode", adminMode);
                addRegion(cx + 4, rowA, btnW, 14, "admin_add", null, addExtra);
                drawSmallLabelButton(g, cx + 4, rowA, btnW, 14,
                        Component.translatable("gui.module_shop.admin_add"), false);
            }
            int genX = cx + 4 + (btnW + btnGap);
            if (demands) {
                int setX = cx + 4;
                addRegion(setX, rowA, btnW, 14, "demand_settings", null, null);
                drawSmallLabelButton(g, setX, rowA, btnW, 14,
                        Component.translatable("gui.module_shop.demand.settings"),
                        IOStyle.inside(setX, rowA, btnW, 14, mx, my), ClientShopData.demandEnabled, false);
                int dExpireX = setX + btnW + btnGap;
                addRegion(dExpireX, rowA, btnW, 14, "demand_expire", null, null);
                drawSmallLabelButton(g, dExpireX, rowA, btnW, 14,
                        Component.translatable("gui.module_shop.demand.expire"),
                        IOStyle.inside(dExpireX, rowA, btnW, 14, mx, my),
                        !"permanent".equals(ClientShopData.demandExpireMode), false);
            } else if (stalls) {
                int expireX = cx + 4;
                addRegion(expireX, rowA, btnW, 14, "stall_expire", null, null);
                drawSmallLabelButton(g, expireX, rowA, btnW, 14,
                        Component.translatable("gui.module_shop.stall.expire"),
                        IOStyle.inside(expireX, rowA, btnW, 14, mx, my),
                        !"permanent".equals(ClientShopData.stallExpireMode), false);
            } else if (mall) {
                addRegion(genX, rowA, btnW, 14, "mall_import", null, null);
                drawSmallLabelButton(g, genX, rowA, btnW, 14,
                        Component.translatable("gui.module_shop.mall.import_mod"),
                        IOStyle.inside(genX, rowA, btnW, 14, mx, my), false, false);
                int restockX = genX + btnW + btnGap;
                addRegion(restockX, rowA, btnW, 14, "mall_restock", null, null);
                drawSmallLabelButton(g, restockX, rowA, btnW, 14,
                        Component.translatable("gui.module_shop.mall.restock"),
                        IOStyle.inside(restockX, rowA, btnW, 14, mx, my), ClientShopData.mallRestockOn, false);
                int refreshX = restockX + btnW + btnGap;
                addRegion(refreshX, rowA, btnW, 14, "mall_refresh", null, null);
                drawSmallLabelButton(g, refreshX, rowA, btnW, 14,
                        Component.translatable("gui.module_shop.mall.refresh_btn"),
                        IOStyle.inside(refreshX, rowA, btnW, 14, mx, my),
                        ClientShopData.mallStockPersonal || !"off".equals(ClientShopData.mallRefreshMode), false);
            } else if (ship) {
                addRegion(genX, rowA, btnW, 14, "shipping_limits", null, null);
                drawSmallLabelButton(g, genX, rowA, btnW, 14,
                        Component.translatable("gui.module_shop.shipping.daily_limits"),
                        IOStyle.inside(genX, rowA, btnW, 14, mx, my), false, false);
            } else {
                addRegion(genX, rowA, btnW, 14, "admin_gen_set", null, null);
                drawSmallLabelButton(g, genX, rowA, btnW, 14,
                        Component.translatable("gui.module_shop.admin_gen"), false);
            }
        }
        // 底栏第二行：翻页 → 页码 → 当前奖励金额 → 操作提示，一律**从左往右依次排**（按实际文字宽度推进），
        // 不再使用固定坐标，所以任何语言 / 任何金额下都不会互相压字。
        // 签到模式：内容区顶部给一行「当前设置」摘要（所有签到设置都在这个模块里）。
        if (signin) {
            // 签到模块没有「条目池」——这里画日历（**和签到页同一份代码**，改完两边自动同步）。
            // 点「编辑日程」切到编辑态后，点日期就能新建 / 修改那天的日程；保存走服务端快照，两个页面一起变。
            // 「选择模块」下拉展开时先不画日历，否则日历会盖住下拉面板（原条目列表也是这么避让的）。
            if (!this.adminModuleOpen) {
                drawSignInCalendar(g, cx, cy + 20, cw, this.signInEdit, false, mx, my);
            }
            Component line1 = Component.translatable("gui.module_shop.signin.admin_line1",
                    ClientShopData.money(ClientShopData.signInBase),
                    ClientShopData.money(ClientShopData.signInMakeupCost),
                    ClientShopData.signInMakeupDays);
            drawBarLabel(g, cx + 4, rowB + 3, cx + cw - 6,
                    this.signInEdit ? Component.translatable("gui.module_shop.signin.edit_mode") : line1,
                    this.signInEdit ? IOStyle.ORANGE : COLOR_GOLD);
            return;
        }
        int labelY = rowB + 3;
        int cursorX = cx + 4;
        if (pages > 1) {
            if (adminPage < pages - 1) {
                // 提示箭头画在列表与第一行按钮之间的空档里（不能贴在按钮上）。
                markMoreBelow(rowA - 12);
            }
            addRegion(cx + 4, rowB, 42, 14, "admin_prev", null, null);
            addRegion(cx + 50, rowB, 42, 14, "admin_next", null, null);
            drawSmallLabelButton(g, cx + 4, rowB, 42, 14, Component.translatable("gui.module_shop.page_prev"), false);
            drawSmallLabelButton(g, cx + 50, rowB, 42, 14, Component.translatable("gui.module_shop.page_next"), false);
            Component pageLabel = Component.translatable("gui.module_shop.page", adminPage + 1, pages);
            cursorX = cx + 98;
            drawSmallLabel(g, cursorX, labelY, pageLabel, COLOR_MUTED);
            cursorX += (int) (this.font.width(pageLabel) * 0.8F) + 12;
        }
        // 第八项：管理员界面直接显示当前收购全清奖励金额。
        Component bonusValue = Component.translatable("gui.module_shop.buyback_bonus_value",
                ClientShopData.money(ClientShopData.buybackBonus));
        drawSmallLabel(g, cursorX, labelY, bonusValue, COLOR_GOLD);
        cursorX += (int) (this.font.width(bonusValue) * 0.8F) + 12;
        // 操作提示接在后面，超宽自动截断，永远不会压到左边的页码 / 金额。
        drawBarLabel(g, cursorX, labelY, cx + cw - 6,
                Component.translatable("gui.module_shop.admin_hint"), COLOR_MUTED);
    }

    /**
     * 需求 ④：列表下方「还有内容」的提示箭头（正弦上下轻微浮动，限制在 10px 空档内）。
     * 画在列表与底栏之间的空档里，所以既不会被底栏按钮盖住，也不会压到列表最后一行。
     */
    private void drawScrollHint(GuiGraphics g, int cx, int cw, int bottomY) {
        // 直边倒置等腰三角形（像素直边），并且是整个 render 的最后一层 —— 永远盖在内容与遮罩之上。
        int offset = (int) Math.round(Math.sin(this.tickCounter / 6.0D) * 1.5D);
        IOStyle.triangleDown(g, cx + cw / 2, bottomY - 8 + offset, 6, 5, IOStyle.BLUE);
    }

    /**
     * 列表右侧的竖向滚动条（统一实现，避免每个列表各算一套）：
     * 滑块高度 = 一屏占比，滑块位置按「已滚到最大值的多少」插值 ——
     * 所以滚到最底时滑块底边**正好贴住轨道底边**，不会停在中途。
     *
     * @param trackX  轨道左边（宽 2px）
     * @param trackY  轨道顶边（一般对齐列表第一行的顶边）
     * @param trackH  轨道高度（一般 = 一屏行数 × 行高）
     * @param first   第一行可见行号
     * @param perScreen 一屏行数
     * @param total   总行数
     */
    private void drawScrollBar(GuiGraphics g, int trackX, int trackY, int trackH, int first, int perScreen,
            int total) {
        if (total <= perScreen || trackH <= 0) {
            return;
        }
        g.fill(trackX, trackY, trackX + 2, trackY + trackH, IOStyle.HAIR);
        int thumbH = Math.max(14, trackH * perScreen / Math.max(1, total));
        thumbH = Math.min(trackH, thumbH);
        int maxFirst = Math.max(1, total - perScreen);
        int f = Mth.clamp(first, 0, maxFirst);
        int thumbY = trackY + (trackH - thumbH) * f / maxFirst;
        g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, IOStyle.BLUE);
    }

    /** 标记「下面还有内容」，并记录列表自身的底边（渐隐提示会画在它下面 10px 的空档里）。 */
    private void markMoreBelow(int listBottomY) {
        this.moreBelow = true;
        this.scrollFadeBottom = Math.max(this.scrollFadeBottom == Integer.MIN_VALUE ? listBottomY
                : this.scrollFadeBottom, listBottomY);
    }

    /** 底栏里的一行小字（0.8 倍字号、左对齐、超宽自动截断），保证不会压到右侧控件。 */
    private void drawBarLabel(GuiGraphics g, int x, int y, int maxRight, Component text, int color) {
        int room = Math.max(8, (int) ((maxRight - x) / 0.8F));
        Component shown = this.font.width(text) > room
                ? Component.literal(this.font.plainSubstrByWidth(text.getString(), Math.max(1, room - 6)) + "…")
                : text;
        drawSmallLabel(g, x, y, shown, color);
    }

    /** 0.8 倍字号的小号文字（左对齐），用于管理员页面的紧凑排版。 */
    private void drawSmallLabel(GuiGraphics g, int x, int y, Component text, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(0.8F, 0.8F, 1.0F);
        g.drawString(this.font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    /** 0.7 倍字号（更小一号）：顶部「当前编辑：X」用，能多显示几个字且不挤到右侧按钮。 */
    private void drawSmallLabel07(GuiGraphics g, int x, int y, Component text, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(0.7F, 0.7F, 1.0F);
        g.drawString(this.font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    // ---- 按比例缩放的通用文字（邮箱等紧凑面板用：字号小一号、行距同步缩） ----

    /** 按 {@code scale} 倍字号在 (x, y) 左对齐绘制文字。 */
    private void textScaled(GuiGraphics g, int x, int y, float scale, Component text, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(scale, scale, 1.0F);
        g.drawString(this.font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    /** 按 {@code scale} 倍字号右对齐到 {@code right}。 */
    private void textScaledRight(GuiGraphics g, int right, int y, float scale, Component text, int color) {
        textScaled(g, right - (int) (this.font.width(text) * scale), y, scale, text, color);
    }

    /** 按 {@code scale} 倍字号水平居中到 {@code centerX}。 */
    private void textScaledCenter(GuiGraphics g, int centerX, int y, float scale, Component text, int color) {
        textScaled(g, centerX - (int) (this.font.width(text) * scale / 2.0F), y, scale, text, color);
    }

    /** 按比例缩放的自动换行正文：换行宽度与行距都按比例换算，最多画到 {@code maxHeight} 像素高。 */
    private void wrappedScaled(GuiGraphics g, int x, int y, int width, String text, int color,
            int maxHeight, float scale) {
        int lineH = Math.max(6, (int) Math.ceil(10.0F * scale));
        List<String> lines = wrapLines(text, Math.max(8, (int) (width / scale)));
        for (int i = 0; i < lines.size(); i++) {
            int lineY = y + i * lineH;
            if (lineY + lineH > y + maxHeight) {
                return;
            }
            textScaled(g, x, lineY, scale, Component.literal(lines.get(i)), color);
        }
    }

    /**
     * 把一段文本按可用像素宽度折行。
     * <p>
     * 关键是**中文没有空格**：旧实现按空格切词，一段没有空格的中文会被当成一个「词」整行画出去，
     * 直接冲出面板（邮件正文越界就是这么来的）。这里改成按最小单元贪心拼行：
     * 中文/全角字符逐字可断、空格单独成 token、拉丁单词整体不断（超长单词也会被硬断）。
     */
    private List<String> wrapLines(String text, int usableWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String token : splitTokens(paragraph)) {
                String candidate = line + token;
                if (line.length() > 0
                        && this.font.width(candidate.replace('\u00a7', '&')) > usableWidth) {
                    lines.add(line.toString());
                    line = new StringBuilder(" ".equals(token) ? "" : token);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /** 折行用的最小单元：§ 颜色代码、中文/全角单字、空格、拉丁单词。 */
    private static List<String> splitTokens(String paragraph) {
        List<String> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < paragraph.length(); i++) {
            char ch = paragraph.charAt(i);
            if (ch == '\u00a7' && i + 1 < paragraph.length()) {
                word.append(ch).append(paragraph.charAt(++i));
                continue;
            }
            if (ch == ' ' || isCjk(ch)) {
                if (word.length() > 0) {
                    tokens.add(word.toString());
                    word.setLength(0);
                }
                tokens.add(String.valueOf(ch));
            } else {
                word.append(ch);
            }
        }
        if (word.length() > 0) {
            tokens.add(word.toString());
        }
        return tokens;
    }

    /** 中文 / 全角字符（逐字断行），其余按词处理。 */
    private static boolean isCjk(char ch) {
        return (ch >= 0x2E80 && ch <= 0x9FFF)
                || (ch >= 0xF900 && ch <= 0xFAFF)
                || (ch >= 0xFE30 && ch <= 0xFE4F)
                || (ch >= 0xFF00 && ch <= 0xFF60)
                || (ch >= 0x3000 && ch <= 0x303F);
    }

    // =========================================================== 货币兑换

    /** 管理员配置列表每页条数（界面高度有限：6 行会压到翻页按钮行）。 */
    private static final int ADMIN_PAGE_SIZE = 5;
    /** 收起状态的行高（一屏放得下 6 行）。 */
    private static final int EXCH_ROW_H = 22;
    /** 展开状态的行高（多出按钮行）。 */
    private static final int EXCH_OPEN_H = 46;
    /** 行间距。 */
    private static final int EXCH_GAP = 4;

    /**
     * 货币兑换：一行一种货币，长方形条里显示“该货币 = 多少星币”；
     * 点击展开后出现「兑换（物品→星币）」「取款（星币→物品）」按钮（管理员另有「改价」「删除」）。
     * 列表可滚动（滚轮），超过可视区域时右侧显示滚动条。
     */
    private void drawExchange(GuiGraphics g, int cx, int cy, int cw, int ch, int mx, int my) {
        List<ClientShopData.ExchangeView> list = ClientShopData.exchange;
        int viewH = Math.max(20, ch - BAR_H - 10);
        if (list.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.exchange.empty"),
                    cx + cw / 2, cy + 40, COLOR_MUTED);
        } else {
            int first = Mth.clamp(ClientShopData.lastExchangeScrollRow, 0, Math.max(0, list.size() - 1));
            ClientShopData.lastExchangeScrollRow = first;
            int y = cy + 2;
            int drawn = 0;
            for (int i = first; i < list.size(); i++) {
                boolean open = ClientShopData.lastExchangeOpen == i;
                int rowH = open ? EXCH_OPEN_H : EXCH_ROW_H;
                // 放不下就整行不画，避免出现被裁掉一半的条目（列表底部不会再有半截行）。
                if (y + rowH - 2 > cy + viewH) {
                    break;
                }
                drawExchangeRow(g, cx, y, cw, rowH, i, list.get(i), open, mx, my, y, rowH);
                y += rowH + EXCH_GAP;
                drawn++;
            }
            // 需求 ④：还有条目没显示完 → 列表底部渐隐提示。
            if (drawn < list.size()) {
                markMoreBelow(cy + viewH);
            }
            // 滚动条：内容超过一屏时靠右显示，提示这里可以滚轮下滑。
            if (drawn < list.size() || first > 0) {
                int trackH = viewH;
                g.fill(cx + cw - 2, cy, cx + cw, cy + trackH, Theme.separator());
                int thumbH = Math.max(14, trackH * Math.min(drawn, list.size()) / Math.max(1, list.size()));
                int maxFirst = Math.max(1, list.size() - 1);
                int thumbY = cy + (trackH - thumbH) * first / maxFirst;
                g.fill(cx + cw - 2, thumbY, cx + cw, thumbY + thumbH, IOStyle.BLUE);
            }
        }
        // 底部：管理员「添加货币」按钮（底栏左侧）+ 操作提示（底栏里、按钮右侧，超宽自动截断）。
        int hintX = cx + 6;
        if (ClientShopData.canAdmin) {
            int addY = barMid(cy, ch, 14);
            addRegion(cx + 4, addY, 78, 14, "admin_exchange_add", null, null);
            drawSmallLabelButton(g, cx + 4, addY, 78, 14,
                    Component.translatable("gui.module_shop.exchange.add"), false);
            hintX = cx + 4 + 78 + BAR_GAP;
        }
        drawBarLabel(g, hintX, barTextY(cy, ch), cx + cw - 6,
                Component.translatable("gui.module_shop.exchange.hint"), COLOR_MUTED);
    }

    /** 单行兑换货币条。 */
    private void drawExchangeRow(GuiGraphics g, int cx, int y, int cw, int rowH, int index,
            ClientShopData.ExchangeView entry, boolean open, int mx, int my, int regionY, int regionH) {
        boolean hovered = mx >= cx + 2 && mx < cx + cw - 2 && my >= y && my < y + rowH;
        // 原版 iOS 风格：圆角卡片，悬停淡蓝底，展开时整行高亮。
        if (open) {
            IOStyle.rounded(g, cx + 2, y, cw - 4, rowH - 2, 8, IOStyle.BLUE_TINT);
            IOStyle.ring(g, cx + 2, y, cw - 4, rowH - 2, 8, IOStyle.TAB_SEL_BORDER);
            IOStyle.rounded(g, cx + 6, y + 6, 3, rowH - 14, 1, IOStyle.BLUE);
        } else {
            IOStyle.cardInteractive(g, cx + 2, y, cw - 4, rowH - 2, 8, hovered);
        }
        Bricks.slot(g, cx + 8, y + 3);
        g.renderItem(entry.stack, cx + 8, y + 3);
        g.renderItemDecorations(this.font, entry.stack, cx + 8, y + 3);
        // 名称过长时截断，避免压到右侧汇率。
        String name = this.font.plainSubstrByWidth(entry.stack.getHoverName().getString(), open ? 120 : 96);
        g.drawString(this.font, name, cx + 30, y + 7, COLOR_TEXT, false);
        // “该货币 = 多少星币”
        String rate = Component.translatable("gui.module_shop.exchange.rate",
                ClientShopData.money(entry.value)).getString();
        if (open) {
            drawSmallLabel(g, cx + 30 + this.font.width(name) + 8, y + 8, Component.literal(rate), COLOR_GOLD);
        } else {
            g.drawString(this.font, rate, cx + cw - 24 - this.font.width(rate), y + 7, COLOR_GOLD, false);
            g.drawString(this.font, "v", cx + cw - 16, y + 7, COLOR_MUTED, false);
        }
        if (open) {
            // 展开行：左边显示持有量与可取出上限，右边是操作按钮。
            drawSmallLabel(g, cx + 8, y + 32,
                    Component.translatable("gui.module_shop.exchange.owned", entry.owned), COLOR_MUTED);
            long affordable = entry.value > 0L ? ClientShopData.balance / entry.value : 0L;
            drawSmallLabel(g, cx + 60, y + 32,
                    Component.translatable("gui.module_shop.exchange.can_withdraw", affordable), COLOR_MUTED);

            // 按钮改小一号（0.7 倍字号 + 38×12），右侧四个按钮一共 164px，
            // 左边「背包 / 可取」那两行小字因此绝不会被压到。
            int btnY = y + 28;
            int btnH = 12;
            int btnW4 = 38;
            // 从右往左排按钮，每个按钮之间固定 4px 间隙；按钮先注册，保证点击优先命中按钮。
            int right = cx + cw - 6;
            if (ClientShopData.canAdmin) {
                right -= btnW4;
                CompoundTag removeExtra = new CompoundTag();
                removeExtra.putInt("index", index);
                addRegion(right, btnY, btnW4, btnH, "admin_exchange_remove", null, removeExtra);
                drawSmallLabelButton(g, right, btnY, btnW4, btnH,
                        Component.translatable("gui.module_shop.exchange.remove"), false, false, true, 0.7F);
                right -= btnW4 + 4;
                CompoundTag editExtra = new CompoundTag();
                editExtra.putInt("index", index);
                addRegion(right, btnY, btnW4, btnH, "exchange_edit", null, editExtra);
                drawSmallLabelButton(g, right, btnY, btnW4, btnH,
                        Component.translatable("gui.module_shop.exchange.edit"), false, false, false, 0.7F);
            }
            right -= btnW4 + 4;
            CompoundTag withdrawExtra = new CompoundTag();
            withdrawExtra.putInt("index", index);
            addRegion(right, btnY, btnW4, btnH, "exchange_withdraw_gui", null, withdrawExtra);
            drawSmallLabelButton(g, right, btnY, btnW4, btnH,
                    Component.translatable("gui.module_shop.exchange.withdraw"), false, false, false, 0.7F);
            right -= btnW4 + 4;
            CompoundTag depositExtra = new CompoundTag();
            depositExtra.putInt("index", index);
            addRegion(right, btnY, btnW4, btnH, "exchange_deposit_gui", null, depositExtra);
            drawSmallLabelButton(g, right, btnY, btnW4, btnH,
                    Component.translatable("gui.module_shop.exchange.deposit"), false, false, false, 0.7F);
        }
        // 整行（表头部分）负责展开/收起。
        CompoundTag openExtra = new CompoundTag();
        openExtra.putInt("index", index);
        addRegion(cx + 2, regionY, cw - 4, Math.min(regionH, 20), "exchange_open", null, openExtra);
        if (mx >= cx + 8 && mx < cx + 24 && my >= y + 3 && my < y + 19) {
            this.jeiHover = entry.stack;
                g.renderTooltip(this.font, entry.stack, mx, my);
        }
    }

    // =========================================================== 购物城（新增模块）

    /** 一件商品的注册名（收藏 key）。 */
    private static String mallItemId(ClientShopData.MallView view) {
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(view.stack.getItem());
        return id == null ? "" : id.toString();
    }

    /** 模组的显示名（拿不到就退回命名空间）。 */
    private static String mallModName(String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            return "";
        }
        try {
            return net.neoforged.fml.ModList.get().getModContainerById(namespace)
                    .map(c -> c.getModInfo().getDisplayName())
                    .orElse(namespace);
        } catch (Throwable ignored) {
            return namespace;
        }
    }

    /** 货架上出现过的模组（按显示名排序，缓存；货架变化时重建）。 */
    private static List<String> mallCategories() {
        if (mallModCacheSize == ClientShopData.mall.size()) {
            return mallModCache;
        }
        java.util.TreeSet<String> set = new java.util.TreeSet<>();
        for (ClientShopData.MallView view : ClientShopData.mall) {
            set.add(view.namespace());
        }
        List<String> list = new ArrayList<>(set);
        list.sort(Comparator.comparing(ns -> mallModName(ns).toLowerCase()));
        mallModCache = list;
        mallModCacheSize = ClientShopData.mall.size();
        return list;
    }

    /** 当前搜索 / 分类 / 收藏过滤 + 排序后的商品列表（带缓存：货架上万件时不必每帧重排）。 */
    private static List<ClientShopData.MallView> mallFiltered() {
        String key = ClientShopData.mallVersion + "|" + ClientShopData.mall.size() + "|" + ClientShopData.mallSearch + "|"
                + ClientShopData.mallCategory + "|" + ClientShopData.mallFavOnly + "|"
                + ClientShopData.mallSort + "|" + MallFavorites.size();
        if (key.equals(mallFilterKey)) {
            return mallFilterCache;
        }
        String query = ClientShopData.mallSearch == null ? "" : ClientShopData.mallSearch.trim().toLowerCase();
        String category = ClientShopData.mallCategory == null ? "" : ClientShopData.mallCategory;
        List<ClientShopData.MallView> result = new ArrayList<>();
        for (ClientShopData.MallView view : ClientShopData.mall) {
            if (view.stack.isEmpty()) {
                continue;
            }
            if (!category.isEmpty() && !category.equals(view.namespace())) {
                continue;
            }
            if (ClientShopData.mallFavOnly && !MallFavorites.contains(mallItemId(view))) {
                continue;
            }
            if (!query.isEmpty()) {
                String name = view.stack.getHoverName().getString().toLowerCase();
                if (!name.contains(query) && !view.namespace().toLowerCase().contains(query)
                        && !mallModName(view.namespace()).toLowerCase().contains(query)) {
                    continue;
                }
            }
            result.add(view);
        }
        switch (ClientShopData.mallSort) {
            case 1 -> result.sort(Comparator.comparingLong(v -> v.price));
            case 2 -> result.sort((a, b) -> Long.compare(b.price, a.price));
            case 3 -> result.sort(Comparator.comparingInt(
                    v -> v.unlimited() ? Integer.MAX_VALUE : v.stock));
            case 4 -> result.sort((a, b) -> Integer.compare(
                    b.unlimited() ? Integer.MAX_VALUE : b.stock, a.unlimited() ? Integer.MAX_VALUE : a.stock));
            default -> result.sort(Comparator.comparingInt(v -> v.sort));
        }
        mallFilterKey = key;
        mallFilterCache = result;
        return result;
    }

    /** 库存文字：无限 / 售罄 / 剩余。 */
    private Component mallStockText(ClientShopData.MallView view) {
        if (view.unlimited()) {
            return Component.translatable("gui.module_shop.mall.stock_unlimited");
        }
        if (view.soldOut()) {
            // 个人库存下「售罄」其实是「你自己这份买完了」，别让玩家以为被别人抢光。
            return Component.translatable(ClientShopData.mallStockPersonal
                    ? "gui.module_shop.mall.personal_sold_out" : "gui.module_shop.mall.sold_out");
        }
        return Component.translatable("gui.module_shop.mall.stock", view.stock);
    }

    /** 排序项的名称（0 默认 / 1 价格升 …）。 */
    private static Component mallSortName(int sort) {
        return Component.translatable(switch (sort) {
            case 1 -> "gui.module_shop.mall.sort_price_asc";
            case 2 -> "gui.module_shop.mall.sort_price_desc";
            case 3 -> "gui.module_shop.mall.sort_stock_asc";
            case 4 -> "gui.module_shop.mall.sort_stock_desc";
            default -> "gui.module_shop.mall.sort_default";
        });
    }

    /** 按 0.7 倍字号居中画一行小字。 */
    private void mallCenter(GuiGraphics g, int centerX, int y, Component text, int color) {
        g.pose().pushPose();
        g.pose().translate(centerX, y, 0.0F);
        g.pose().scale(MALL_TEXT, MALL_TEXT, 1.0F);
        IOStyle.centerAt(g, this.font, text, 0, 0, color);
        g.pose().popPose();
    }

    /** 搜索框底框（输入框本体由 EditBox 画，这里只补背景与描边）。 */
    private void drawMallField(GuiGraphics g, int x, int y, int w, int h, boolean focused) {
        IOStyle.rounded(g, x, y, w, h, 0, IOStyle.FIELD_BG);
        IOStyle.ring(g, x, y, w, h, 0, focused ? IOStyle.BORDER_HOVER : IOStyle.BORDER_DIM);
    }

    /**
     * 购物城：两行六列的常驻货架（搜索 / 排序 / 按模组分类 / 只看收藏 / 最近购买 / 翻页）。
     */
    private void drawMall(GuiGraphics g, int cx, int cy, int cw, int ch, int mx, int my) {
        MallFavorites.ensureLoaded();
        this.mallSortRects.clear();
        this.mallCatRects.clear();
        int barTop = barTop(cy, ch);
        boolean recentView = ClientShopData.mallViewRecent;
        // 「最近购买」视图只留一个返回货架的按钮（搜索 / 排序 / 分类对购买记录没意义），
        // 所以内容区可以往上提一行，列表更长。
        int gridTop = cy + (recentView ? MALL_CTRL_H + MALL_CTRL_GAP : MALL_GRID_TOP);
        int gridH = Math.max(40, barTop - 4 - gridTop);
        int cardW = (cw - (MALL_COLS - 1) * MALL_CARD_GAP) / MALL_COLS;
        int cardH = (gridH - (MALL_ROWS - 1) * MALL_CARD_GAP) / MALL_ROWS;
        // 控件行几何：两个视图共用（下拉面板画在方法末尾，也要用到这些坐标）。
        int y2 = cy + MALL_CTRL_H + MALL_CTRL_GAP;
        int sortW = 74;
        int catW = 118;
        int catX = cx + sortW + MALL_CTRL_GAP;
        int recentW = cw - sortW - catW - MALL_CTRL_GAP * 2;

        if (recentView) {
            boolean backHover = IOStyle.inside(cx, cy, 104, MALL_CTRL_H, mx, my);
            drawSmallLabelButton(g, cx, cy, 104, MALL_CTRL_H,
                    Component.translatable("gui.module_shop.mall.back_shelf"), backHover, true, false);
            addRegion(cx, cy, 104, MALL_CTRL_H, "mall_recent_toggle", null, null);
        } else {

        // ---------- 第一行：搜索框 + 搜索按钮 + 只看收藏 ----------
        int searchW = 140;
        drawMallField(g, cx, cy, searchW, MALL_CTRL_H, this.mallSearchBox != null && this.mallSearchBox.isFocused());
        int searchBtnX = cx + searchW + MALL_CTRL_GAP;
        boolean searchHover = IOStyle.inside(searchBtnX, cy, 40, MALL_CTRL_H, mx, my);
        drawSmallLabelButton(g, searchBtnX, cy, 40, MALL_CTRL_H,
                Component.translatable("gui.module_shop.mall.search"), searchHover);
        addRegion(searchBtnX, cy, 40, MALL_CTRL_H, "mall_search", null, null);
        int favX = searchBtnX + 40 + MALL_CTRL_GAP;
        int favW = cx + cw - favX;
        boolean favHover = IOStyle.inside(favX, cy, favW, MALL_CTRL_H, mx, my);
        drawSmallLabelButton(g, favX, cy, favW, MALL_CTRL_H,
                Component.translatable("gui.module_shop.mall.fav_only"), favHover,
                ClientShopData.mallFavOnly, false);
        addRegion(favX, cy, favW, MALL_CTRL_H, "mall_fav_toggle", null, null);

        // ---------- 第二行：排序 / 分类 / 最近购买 ----------
        boolean sortHover = IOStyle.inside(cx, y2, sortW, MALL_CTRL_H, mx, my);
        drawSmallLabelButton(g, cx, y2, sortW, MALL_CTRL_H, Component.translatable(
                "gui.module_shop.mall.sort_prefix", mallSortName(ClientShopData.mallSort)),
                sortHover, this.mallSortOpen, false);
        addRegion(cx, y2, sortW, MALL_CTRL_H, "mall_sort_menu", null, null);
        String catName = ClientShopData.mallCategory.isEmpty()
                ? Component.translatable("gui.module_shop.mall.cat_all").getString()
                : mallModName(ClientShopData.mallCategory);
        int catTextMax = (int) ((catW - 16) / 0.8F);
        boolean catHover = IOStyle.inside(catX, y2, catW, MALL_CTRL_H, mx, my);
        drawSmallLabelButton(g, catX, y2, catW, MALL_CTRL_H, Component.translatable(
                "gui.module_shop.mall.cat_prefix",
                this.font.width(catName) > catTextMax
                        ? this.font.plainSubstrByWidth(catName, Math.max(1, catTextMax - 6)) + "…"
                        : catName),
                catHover, this.mallCatOpen, false);
        addRegion(catX, y2, catW, MALL_CTRL_H, "mall_cat_menu", null, null);
        int recentX = catX + catW + MALL_CTRL_GAP;
        boolean recentHover = IOStyle.inside(recentX, y2, recentW, MALL_CTRL_H, mx, my);
        drawSmallLabelButton(g, recentX, y2, recentW, MALL_CTRL_H,
                Component.translatable("gui.module_shop.mall.recent"), recentHover, recentView, false);
        addRegion(recentX, y2, recentW, MALL_CTRL_H, "mall_recent_toggle", null, null);
        }   // ← recentView 分支结束（货架视图才有搜索 / 排序 / 分类 / 最近购买四个控件）

        // ---------- 内容：最近购买 或 货架网格 ----------
        // 下拉展开时**不画内容**：物品图标属于另一批渲染，会穿透面板浮在上面（编辑布局踩过同一个坑）。
        if (!this.mallSortOpen && !this.mallCatOpen) {
            if (recentView) {
                drawMallRecent(g, cx, cy, ch, gridTop, cw, gridH, mx, my);
            } else {
                List<ClientShopData.MallView> list = mallFiltered();
                int pages = Math.max(1, (list.size() + MALL_PAGE_SIZE - 1) / MALL_PAGE_SIZE);
                ClientShopData.mallPage = Mth.clamp(ClientShopData.mallPage, 0, pages - 1);
                int drawn = 0;
                for (int i = 0; i < MALL_PAGE_SIZE; i++) {
                    int index = ClientShopData.mallPage * MALL_PAGE_SIZE + i;
                    if (index >= list.size()) {
                        break;
                    }
                    int col = i % MALL_COLS;
                    int row = i / MALL_COLS;
                    int x = cx + col * (cardW + MALL_CARD_GAP);
                    int y = gridTop + row * (cardH + MALL_CARD_GAP);
                    drawMallCard(g, x, y, cardW, cardH, list.get(index), index, mx, my);
                    drawn++;
                }
                if (list.isEmpty()) {
                    IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.mall.empty"),
                            cx + cw / 2, gridTop + gridH / 2, COLOR_MUTED);
                }
                // 底栏：页码 + 翻页 + 管理员按钮
                int pageLabelY = barTextY(cy, ch);
                int right = cx + cw - BAR_PAD;
                if (pages > 1) {
                    right -= 40;
                    addRegion(right, barMid(cy, ch, 12), 40, 12, "mall_next", null, null);
                    drawSmallLabelButton(g, right, barMid(cy, ch, 12), 40, 12,
                            Component.translatable("gui.module_shop.page_next"),
                            IOStyle.inside(right, barMid(cy, ch, 12), 40, 12, mx, my));
                    right -= 44;
                    addRegion(right, barMid(cy, ch, 12), 40, 12, "mall_prev", null, null);
                    drawSmallLabelButton(g, right, barMid(cy, ch, 12), 40, 12,
                            Component.translatable("gui.module_shop.page_prev"),
                            IOStyle.inside(right, barMid(cy, ch, 12), 40, 12, mx, my));
                }
                if (ClientShopData.canAdmin) {
                    // 上架入口统一放在「商店设置」页里（那页有「添加」与「导入模组」），
                    // 购物城自己的底栏只留翻页，免得按钮挤在底栏、也免得挡住列表。
                    right -= 0;
                }
                Component status = Component.translatable("gui.module_shop.mall.status",
                        ClientShopData.mallPage + 1, pages, list.size());
                if (ClientShopData.mallStockPersonal) {
                    // 个人库存：先说清楚「这个剩余量是你自己的」，否则玩家会以为在跟别人抢同一份库存。
                    status = Component.translatable("gui.module_shop.mall.personal_stock")
                            .append(Component.literal("  ·  ")).append(status);
                }
                if (ClientShopData.mallRestockOn) {
                    // 开了定时补货：底栏前面先写清楚「每天几点补货」，玩家就知道售罄只是暂时的。
                    status = Component.translatable("gui.module_shop.mall.restock_status",
                                    ClientShopData.mallRestockTimeText,
                                    Component.translatable("gui.module_shop.choice.restock_mode."
                                            + ("game".equals(ClientShopData.mallRestockMode) ? "game" : "real")))
                            .append(Component.literal("  ·  ")).append(status);
                }
                drawBarLabel(g, cx + 4, pageLabelY, right, status, COLOR_MUTED);
            }
        }

        // ---------- 下拉面板（最后画，盖在所有内容之上）----------
        if (this.mallSortOpen) {
            int py = y2 + MALL_CTRL_H + 2;
            int pw = 104;
            IOStyle.card(g, cx, py, pw, 5 * 14 + 4, 0);
            for (int i = 0; i < 5; i++) {
                int ry = py + 2 + i * 14;
                boolean current = ClientShopData.mallSort == i;
                boolean hover = IOStyle.inside(cx + 2, ry, pw - 4, 14, mx, my);
                IOStyle.rounded(g, cx + 2, ry, pw - 4, 14, 0,
                        current ? IOStyle.BLUE_TINT : (hover ? IOStyle.ROW_HOVER : IOStyle.SIDEBAR));
                IOStyle.ring(g, cx + 2, ry, pw - 4, 14, 0, current ? IOStyle.BORDER_HOVER : IOStyle.BORDER_DIM);
                drawSmallLabel(g, cx + 8, ry + 3, mallSortName(i), current ? IOStyle.BLUE : IOStyle.TEXT);
                this.mallSortRects.add(new int[]{cx + 2, ry, pw - 4, 14, i});
                CompoundTag extra = new CompoundTag();
                extra.putInt("sort", i);
                addRegion(cx + 2, ry, pw - 4, 14, "mall_sort_pick", null, extra);
            }
        }
        if (this.mallCatOpen) {
            int py = y2 + MALL_CTRL_H + 2;
            int pw = 168;
            List<String> mods = mallCategories();
            int total = mods.size() + 1;
            int rows = Math.min(MALL_CAT_ROWS, total);
            int ph = rows * 14 + 4;
            this.mallCatRect[0] = catX;
            this.mallCatRect[1] = py;
            this.mallCatRect[2] = pw;
            this.mallCatRect[3] = ph;
            int maxScroll = Math.max(0, total - rows);
            this.mallCatScroll = Mth.clamp(this.mallCatScroll, 0, maxScroll);
            IOStyle.card(g, catX, py, pw, ph, 0);
            int last = Math.min(total, this.mallCatScroll + rows);
            for (int i = this.mallCatScroll; i < last; i++) {
                int ry = py + 2 + (i - this.mallCatScroll) * 14;
                String ns = i == 0 ? "" : mods.get(i - 1);
                String name = i == 0
                        ? Component.translatable("gui.module_shop.mall.cat_all").getString()
                        : mallModName(ns);
                boolean current = ns.equals(ClientShopData.mallCategory);
                boolean hover = IOStyle.inside(catX + 2, ry, pw - 4, 14, mx, my);
                IOStyle.rounded(g, catX + 2, ry, pw - 4, 14, 0,
                        current ? IOStyle.BLUE_TINT : (hover ? IOStyle.ROW_HOVER : IOStyle.SIDEBAR));
                IOStyle.ring(g, catX + 2, ry, pw - 4, 14, 0, current ? IOStyle.BORDER_HOVER : IOStyle.BORDER_DIM);
                int nameMax = (int) ((pw - 20) / 0.8F);
                drawSmallLabel(g, catX + 8, ry + 3, Component.literal(
                                this.font.width(name) > nameMax
                                        ? this.font.plainSubstrByWidth(name, Math.max(1, nameMax - 6)) + "…"
                                        : name),
                        current ? IOStyle.BLUE : IOStyle.TEXT);
                this.mallCatRects.add(new int[]{catX + 2, ry, pw - 4, 14, i});
                CompoundTag extra = new CompoundTag();
                extra.putString("category", ns);
                addRegion(catX + 2, ry, pw - 4, 14, "mall_cat_pick", null, extra);
            }
            if (maxScroll > 0) {
                int viewH = rows * 14;
                int trackX = catX + pw - 4;
                g.fill(trackX, py + 2, trackX + 1, py + 2 + viewH, IOStyle.HAIR);
                int thumbH = Math.max(12, viewH * rows / Math.max(1, total));
                int thumbY = py + 2 + (viewH - thumbH) * this.mallCatScroll / Math.max(1, maxScroll);
                g.fill(trackX, thumbY, trackX + 1, thumbY + thumbH, IOStyle.BLUE);
            }
        }
    }

    /** 货架上的一张卡片：品质只用 1px 细边框表示，内容为图标 / 名称 / 价格 / 库存（+ 收藏星标）。 */
    private void drawMallCard(GuiGraphics g, int x, int y, int w, int h, ClientShopData.MallView view, int index,
            int mx, int my) {
        int quality = IOStyle.rarity(view.rarity);
        boolean hover = IOStyle.inside(x, y, w, h, mx, my);
        IOStyle.rounded(g, x, y, w, h, 0, hover ? IOStyle.ROW_HOVER : IOStyle.CARD);
        IOStyle.ring(g, x, y, w, h, 0, view.soldOut() ? IOStyle.withAlpha(quality, 90) : quality);
        Bricks.slot(g, x + w / 2 - 8, y + 4);
        g.renderItem(view.stack, x + w / 2 - 8, y + 4);
        g.renderItemDecorations(this.font, view.stack, x + w / 2 - 8, y + 4);
        mallCenter(g, x + w / 2, y + 23,
                IOStyle.ellipsize(this.font, view.stack.getHoverName(), (int) ((w - 4) / MALL_TEXT)),
                view.soldOut() ? IOStyle.TEXT_3 : IOStyle.TEXT);
        mallCenter(g, x + w / 2, y + 33, Component.literal(ClientShopData.money(view.price)), IOStyle.ORANGE);
        mallCenter(g, x + w / 2, y + 43, mallStockText(view),
                view.soldOut() ? IOStyle.RED : (view.unlimited() ? IOStyle.TEXT_2 : IOStyle.GREEN));
        // 收藏星标：先注册它的命中区，保证点在星上时优先于「购买」。
        // 两种动作都用**货架下标**（= 服务端 mallPool 的下标），不依赖当前过滤结果。
        int shelfIndex = view.shelf;
        boolean fav = MallFavorites.contains(mallItemId(view));
        int starX = x + w - 11;
        int starY = y + 2;
        CompoundTag starExtra = new CompoundTag();
        starExtra.putInt("index", shelfIndex);
        addRegion(starX, starY, 10, 10, "mall_star", null, starExtra);
        g.drawString(this.font, fav ? "★" : "☆", starX, starY, fav ? IOStyle.ORANGE : IOStyle.TEXT_3, false);
        CompoundTag buyExtra = new CompoundTag();
        buyExtra.putInt("index", shelfIndex);
        addRegion(x, y, w, h, "mall_buy", "mall_buy_many", buyExtra);
        if (hover) {
            // JEI 联动：鼠标停在图标上按 R / U 可以查配方（和其它模块一致）。
            if (mx >= x + w / 2 - 8 && mx < x + w / 2 + 8 && my >= y + 4 && my < y + 20) {
                this.jeiHover = view.stack;
            }
            g.renderTooltip(this.font, view.stack, mx, my);
        }
    }

    /** 最近购买：两行一条（第一行 物品×数量 + 金额，第二行 玩家 + 时间），最新的在最上面。 */
    private void drawMallRecent(GuiGraphics g, int cx, int cy, int ch, int top, int cw, int h, int mx, int my) {
        List<ClientShopData.MallPurchaseView> list = ClientShopData.mallRecent;
        int rowH = 24;
        int per = Math.max(1, h / rowH);
        if (list.isEmpty()) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.mall.recent_empty"),
                    cx + cw / 2, top + h / 2, COLOR_MUTED);
            return;
        }
        int maxScroll = Math.max(0, list.size() - per);
        ClientShopData.mallRecentScroll = Mth.clamp(ClientShopData.mallRecentScroll, 0, maxScroll);
        int first = ClientShopData.mallRecentScroll;
        for (int i = first; i < list.size() && i - first < per; i++) {
            ClientShopData.MallPurchaseView record = list.get(i);
            int y = top + (i - first) * rowH;
            IOStyle.card(g, cx, y, cw, rowH - 2, 0);
            Bricks.slot(g, cx + 3, y + 3);
            g.renderItem(record.stack, cx + 3, y + 3);
            g.renderItemDecorations(this.font, record.stack, cx + 3, y + 3);
            Component name = IOStyle.ellipsize(this.font, record.stack.getHoverName(), 120)
                    .copy().append(" ×" + record.count);
            drawSmallLabel(g, cx + 24, y + 3, name, IOStyle.TEXT);
            drawSmallLabelRight(g, cx + cw - 6, y + 3,
                    Component.literal(ClientShopData.money(record.total)), IOStyle.ORANGE);
            drawSmallLabel(g, cx + 24, y + 13, Component.literal(record.playerName), IOStyle.TEXT_2);
            drawSmallLabelRight(g, cx + cw - 6, y + 13,
                    Component.literal(mallTimeText(record.time)), IOStyle.TEXT_3);
            if (mx >= cx + 24 && mx < cx + 40 && my >= y + 3 && my < y + 19) {
                this.jeiHover = record.stack;
            }
            if (IOStyle.inside(cx, y, cw, rowH - 2, mx, my)) {
                g.renderTooltip(this.font, record.stack, mx, my);
            }
        }
        if (list.size() > first + per) {
            markMoreBelow(top + Math.min(per, list.size() - first) * rowH);
        }
        if (maxScroll > 0) {
            int viewH = per * rowH;
            g.fill(cx + cw - 2, top, cx + cw, top + viewH, IOStyle.HAIR);
            int thumbH = Math.max(14, viewH * per / Math.max(1, list.size()));
            int thumbY = top + (viewH - thumbH) * first / Math.max(1, maxScroll);
            g.fill(cx + cw - 2, thumbY, cx + cw, thumbY + thumbH, IOStyle.BLUE);
        }
        // 底栏：记录条数提示
        drawBarLabel(g, cx + 4, barTextY(cy, ch), cx + cw - 6,
                Component.translatable("gui.module_shop.mall.recent_status", list.size()), COLOR_MUTED);
    }

    /** 0.8 倍字号右对齐小字。 */
    private void drawSmallLabelRight(GuiGraphics g, int right, int y, Component text, int color) {
        drawLabelRight(g, right, y, text, color, 0.8F);
    }

    /**
     * 右对齐的一行文字（{@code scale} = 1.0 即原尺寸）。
     * <p>横向宽度按缩放后的实际宽度算，所以右边缘永远落在 {@code right} 上，不会因为缩放而偏移。
     */
    private void drawLabelRight(GuiGraphics g, int right, int y, Component text, int color, float scale) {
        int w = Math.round(this.font.width(text) * scale);
        g.pose().pushPose();
        g.pose().translate(right - w, y, 0.0F);
        if (scale != 1.0F) {
            g.pose().scale(scale, scale, 1.0F);
        }
        g.drawString(this.font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    /** 按比例缩放、左对齐的一行文字（{@code scale} = 1.0 即原尺寸）。 */
    private void drawLabel(GuiGraphics g, int x, int y, Component text, int color, float scale) {
        if (scale == 1.0F) {
            g.drawString(this.font, text, x, y, color, false);
            return;
        }
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(scale, scale, 1.0F);
        g.drawString(this.font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    /** 最近购买的时间：MM-dd HH:mm。 */
    private static String mallTimeText(long millis) {
        return java.time.Instant.ofEpochMilli(millis)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }

    // =========================================================== 出货箱（新增模块）

    /** 客户端估算：距离下一次结算还有多少**游戏内**时间（用客户端自己的世界时间，不用服务端下发）。 */
    private static String shippingNextText() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return "";
        }
        long dayTime = level.getDayTime();
        long timeOfDay = dayTime % 24000L;
        long start = ClientShopData.shipSettleTick;
        long remain = timeOfDay <= start ? start - timeOfDay : 24000L - timeOfDay + start;
        long minutes = Math.max(0L, remain) * 60L / 1000L;   // 1000 刻 = 1 游戏小时
        if (remain <= 0L || minutes < 1L) {
            return Component.translatable("gui.module_shop.shipping.settling").getString();
        }
        return Component.translatable("gui.module_shop.shipping.next_in",
                minutes / 60L, minutes % 60L).getString();
    }

    /**
     * 出货箱界面：等级 / 格数 / 本次结算金额 / 今日额度 / 规则状态 + 「打开出货箱」「升级」两个按钮。
     * <p>
     * 物品本体在**容器界面**里（{@link ShippingBinScreen}），这里只做概览与入口，
     * 所以这一页永远不会有排版冲突。
     */
    private void drawShipping(GuiGraphics g, int cx, int cy, int cw, int ch, int mx, int my) {
        int barTop = barTop(cy, ch);
        if (!ClientShopData.shipEnabled) {
            IOStyle.centerAt(g, this.font, Component.translatable("gui.module_shop.shipping.disabled"),
                    cx + cw / 2, cy + 60, COLOR_MUTED);
            return;
        }
        // ---------- 卡片一：本次结算 ----------
        int cardY = cy + 2;
        IOStyle.card(g, cx + 2, cardY, cw - 4, 58, 0);
        drawSmallLabel(g, cx + 10, cardY + 6, Component.translatable("gui.module_shop.shipping.level",
                ClientShopData.shipLevel, ClientShopData.shipMaxLevel, ClientShopData.shipSlots), COLOR_GOLD);
        drawSmallLabel(g, cx + 10, cardY + 20, Component.translatable("gui.module_shop.shipping.items",
                ClientShopData.shipItems), COLOR_MUTED);
        // 本次结算金额（大一点、金色，一眼能看到）
        Component value = Component.translatable("gui.module_shop.shipping.pending",
                ClientShopData.money(ClientShopData.shipValue));
        g.drawString(this.font, value, cx + 10, cardY + 34, IOStyle.ORANGE, false);
        Component today = ClientShopData.shipLimit > 0L
                ? Component.translatable("gui.module_shop.shipping.today",
                        ClientShopData.money(ClientShopData.shipSoldToday),
                        ClientShopData.money(ClientShopData.shipLimit))
                : Component.translatable("gui.module_shop.shipping.today_unlimited",
                        ClientShopData.money(ClientShopData.shipSoldToday));
        drawSmallLabelRight(g, cx + cw - 10, cardY + 36, today, COLOR_MUTED);
        // ---------- 卡片二：结算时间（规则条数/正常与否不再占行，只有出错时才红字提醒）----------
        int card2Y = cardY + 62;
        IOStyle.card(g, cx + 2, card2Y, cw - 4, 50, 0);
        Component settleTime = Component.translatable("gui.module_shop.shipping.settle_time",
                String.format("%02d:%02d", (ClientShopData.shipSettleTick / 1000 + 6) % 24,
                        (int) ((ClientShopData.shipSettleTick % 1000) * 60 / 1000)));
        drawSmallLabel(g, cx + 10, card2Y + 8, settleTime, COLOR_MUTED);
        drawSmallLabel(g, cx + 10, card2Y + 22, Component.translatable(
                "gui.module_shop.shipping.reset_line", ClientShopData.shipResetTimeText), COLOR_MUTED);
        drawSmallLabelRight(g, cx + cw - 10, card2Y + 8, Component.literal(shippingNextText()), COLOR_GOLD);

        // ---------- 按钮 ----------
        int btnY = card2Y + 56;
        int btnW = (cw - 8 - 6) / 2;
        drawSmallLabelButton(g, cx + 2, btnY, btnW, 20,
                Component.translatable("gui.module_shop.shipping.open"),
                IOStyle.inside(cx + 2, btnY, btnW, 20, mx, my), true, false);
        addRegion(cx + 2, btnY, btnW, 20, "shipping_open", null, null);
        boolean canUpgrade = ClientShopData.shipUpgradeCost >= 0L
                && ClientShopData.shipLevel < ClientShopData.shipMaxLevel;
        int upX = cx + 2 + btnW + 6;
        Component upLabel = canUpgrade
                ? Component.translatable("gui.module_shop.shipping.upgrade_cost",
                        ClientShopData.money(ClientShopData.shipUpgradeCost))
                : Component.translatable("gui.module_shop.shipping.max");
        drawSmallLabelButton(g, upX, btnY, btnW, 20, upLabel,
                IOStyle.inside(upX, btnY, btnW, 20, mx, my), false, canUpgrade);
        if (canUpgrade) {
            addRegion(upX, btnY, btnW, 20, "shipping_upgrade", null, null);
        }
        // ---------- 底栏提示（规则错误不再在这里刷字：管理页会把出问题的规则**标红**）----------
        // 货币名用当前显示名（管理员改过就用改过的），不再在文案里写死「星币」。
        drawBarLabel(g, cx + 4, barTextY(cy, ch), cx + cw - 6,
                Component.translatable("gui.module_shop.shipping.hint", ClientShopData.currencyName()), COLOR_MUTED);
    }

    // =========================================================== 交互

    // =========================================================== 交互

    /** 鼠标滚轮：右侧内容区各自吃自己的滚轮；左侧模块列表（超过 6 个时）任何模式下都能滚。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int dir = scrollY > 0 ? -1 : 1;
        // 交易 / 转账页：滚轮翻在线玩家列表。
        if (scrollY != 0.0D && !this.layoutEdit
                && ("trade".equals(currentTab) || "transfer".equals(currentTab))
                && mouseX >= contentX() && mouseX < contentX() + contentW()
                && mouseY >= contentY() && mouseY < contentY() + contentH()) {
            int pages = Math.max(1, (tradePlayers().size() + TRADE_PAGE_SIZE - 1) / TRADE_PAGE_SIZE);
            int next = Mth.clamp(this.tradePage + dir, 0, pages - 1);
            if (next != this.tradePage) {
                this.tradePage = next;
                return true;
            }
        }
        // ① 鼠标停在左侧菜单列上 → 翻左侧模块列表（一屏固定 6 个，多余的靠滚轮）。
        if (scrollY != 0.0D && mouseX >= left() - 4 && mouseX < left() + SIDEBAR_W
                && mouseY >= top() && mouseY <= top() + PANEL_H) {
            int maxScroll = sidebarMaxScroll();
            if (maxScroll > 0) {
                // 触控板 / 高分辨率滚轮会发小数：累积到一格再动，滚起来才连续（普通鼠标每次正好一格）。
                this.sidebarWheelAccum += scrollY;
                int steps = 0;
                while (this.sidebarWheelAccum >= 1.0D) {
                    this.sidebarWheelAccum -= 1.0D;
                    steps--;
                }
                while (this.sidebarWheelAccum <= -1.0D) {
                    this.sidebarWheelAccum += 1.0D;
                    steps++;
                }
                if (steps != 0) {
                    this.sidebarScroll = Mth.clamp(this.sidebarScroll + steps, 0, maxScroll);
                }
            }
            return true;
        }
        // 编辑模式：滚轮用来翻「下方模块管理列表」（模块多的时候），不翻内容区。
        if (this.layoutEdit && ClientShopData.canAdmin && scrollY != 0.0D) {
            // 鼠标停在模块管理面板上 → 翻模块管理。
            if (IOStyle.inside(this.managerRect[0], this.managerRect[1], this.managerRect[2], this.managerRect[3],
                    mouseX, mouseY)) {
                // 上限每帧由 drawModuleManager 收敛，这里只做「不小于 0」的保护。
                this.managerScroll = Math.max(0, this.managerScroll + dir);
                return true;
            }
            // 编辑模式下滚轮不再翻内容区的列表（内容本来就没绘制）。
            return true;
        }
        // 购物城：分类下拉 / 最近购买列表 / 货架翻页，各自吃自己的滚轮。
        if (!this.layoutEdit && "mall".equals(currentTab) && scrollY != 0.0D) {
            if (this.mallCatOpen && IOStyle.inside(this.mallCatRect[0], this.mallCatRect[1], this.mallCatRect[2],
                    this.mallCatRect[3], mouseX, mouseY)) {
                this.mallCatScroll = Math.max(0, this.mallCatScroll + dir);
                return true;
            }
            if (this.mallSortOpen) {
                // 排序只有 5 项，不需要滚动，但也不要把滚轮漏给别处。
                return true;
            }
            if (ClientShopData.mallViewRecent) {
                ClientShopData.mallRecentScroll = Math.max(0, ClientShopData.mallRecentScroll + dir);
                return true;
            }
            if (IOStyle.inside(contentX(), contentY(), contentW(), contentH(), mouseX, mouseY)) {
                // 上限在绘制时按当前过滤结果夹紧（列表可能被搜索/分类缩短）。
                ClientShopData.mallPage = Math.max(0, ClientShopData.mallPage + dir);
                return true;
            }
            return true;
        }
        if (scrollY != 0.0D) {
            int contentX = left() + SIDEBAR_W + 4;
            int contentY = top() + TOP_BAR_H + 2;
            int contentW = PANEL_W - SIDEBAR_W - 10;
            int contentH = PANEL_H - TOP_BAR_H - 6;
            // 编辑布局时不滚动任何列表，避免拖动过程中列表乱跳。
            if (this.layoutEdit && ClientShopData.canAdmin) {
                return true;
            }
            if (!IOStyle.inside(contentX, contentY, contentW, contentH, mouseX, mouseY)) {
                return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
            }
            int step = scrollY > 0.0D ? -1 : 1;
            switch (currentTab) {
                case "auction" -> {
                    int pages = Math.max(1, (ClientShopData.auctions.size() + 4) / 5);
                    auctionPage = Mth.clamp(auctionPage + step, 0, pages - 1);
                    return true;
                }
                case "mail" -> {
                    // 鼠标在正文区：滚动正文（正文过长时上下看，绝不会画出框外）；
                    // 在附件格子上：滚动附件物品（按整行）；
                    // 否则按原来的逻辑翻邮件列表。
                    if (this.bodyMaxScroll > 0 && IOStyle.inside(this.bodyRect[0],
                            this.bodyRect[1], this.bodyRect[2], this.bodyRect[3], mouseX, mouseY)) {
                        this.mailBodyScroll = Mth.clamp(this.mailBodyScroll + step, 0, this.bodyMaxScroll);
                        return true;
                    }
                    if (this.attachRect[2] > 0 && this.attachRows > 2 && IOStyle.inside(this.attachRect[0],
                            this.attachRect[1], this.attachRect[2], this.attachRect[3], mouseX, mouseY)) {
                        this.attachRow = Mth.clamp(this.attachRow + step, 0, this.attachRows - 2);
                        return true;
                    }
                    // 与 drawMail 同一套行高/头部高度，翻页步长永远与「一屏几封」一致。
                    int perScreen = Math.max(1, (contentH - BAR_H - 10 - 18) / 21);
                    int pages = Math.max(1, (ClientShopData.mail.size() + perScreen - 1) / perScreen);
                    mailPage = Mth.clamp(mailPage + step, 0, pages - 1);
                    return true;
                }
                case "admin" -> {
                    // 当前编辑对象可能是每日商店 / 每日收购 / 购物城 / 出货箱：
                    // 页数必须按**对应的那份列表**算，否则购物城有 22 页也会被商店的 5 页卡住。
                    int size = "mall".equals(adminMode) ? ClientShopData.adminMall.size()
                            : ("shipping".equals(adminMode) ? ClientShopData.adminShipping.size()
                                    : ("buyback".equals(adminMode) ? ClientShopData.adminBuyback.size()
                                            : ClientShopData.adminShop.size()));
                    int pages = Math.max(1, (size + ADMIN_PAGE_SIZE - 1) / ADMIN_PAGE_SIZE);
                    adminPage = Mth.clamp(adminPage + step, 0, pages - 1);
                    return true;
                }
                case "buyback" -> {
                    // 需求 8：收购条目超过一屏时，滚轮整行滚动。
                    // 上限 = 最后一条**贴到底边**的位置，不能再滚出一片空白。
                    int max = Math.max(0, ClientShopData.dailyBuyback.size() - buybackRowsPerScreen(contentH));
                    ClientShopData.buybackListRow = Mth.clamp(ClientShopData.buybackListRow + step, 0, max);
                    return true;
                }
                case "daily" -> {
                    // 需求 8：牌数超过 6 张时，滚轮整行长条滚动。
                    if (ClientShopData.dailyShop.size() > 6) {
                        int max = Math.max(0, ClientShopData.dailyShop.size() - dailyRowsPerScreen(contentH));
                        ClientShopData.dailyListRow = Mth.clamp(ClientShopData.dailyListRow + step, 0, max);
                        return true;
                    }
                }
                case "exchange" -> {
                    // 货币兑换列表：按“整行”滚动，保证不会出现被裁掉一半的条目。
                    int max = Math.max(0, ClientShopData.exchange.size() - 1);
                    ClientShopData.lastExchangeScrollRow =
                            Mth.clamp(ClientShopData.lastExchangeScrollRow + step, 0, max);
                    return true;
                }
                default -> {
                    // 每日商店/收购没有分页，交给默认处理。
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 管理页的「选择模块编辑」下拉展开时：这次点击只归它处理 —— 点列表项就切换模块，
        // 点别处只负责收起（不会顺手触发后面的商品行编辑 / 删除）。
        if (this.adminModuleOpen && button == 0) {
            for (Region region : this.regions) {
                if (region.action != null && region.action.startsWith("admin_module_")
                        && IOStyle.inside(region.x, region.y, region.w, region.h, mouseX, mouseY)) {
                    Bricks.click();
                    onAction(region.action, region.extra);
                    return true;
                }
            }
            this.adminModuleOpen = false;
            return true;
        }
        // 购物城下拉（排序 / 分类）展开时：这次点击只归它处理 —— 点选项就应用，
        // 点别处只负责收起（不会顺手买到卡片上的东西）。
        if (button == 0 && "mall".equals(currentTab) && (this.mallSortOpen || this.mallCatOpen)) {
            for (Region region : this.regions) {
                String action = region.action();
                if (action != null && (action.startsWith("mall_sort") || action.startsWith("mall_cat"))
                        && IOStyle.inside(region.x(), region.y(), region.w(), region.h(), mouseX, mouseY)) {
                    Bricks.click();
                    onAction(action, region.extra());
                    return true;
                }
            }
            this.mallSortOpen = false;
            this.mallCatOpen = false;
            return true;
        }
        // 布局编辑模式：工具条与侧栏拖拽优先接管（拖拽判定在 mouseDragged / mouseReleased 里完成）。
        if (this.layoutEdit && ClientShopData.canAdmin && button == 0 && handleLayoutClick(mouseX, mouseY)) {
            return true;
        }
        for (Region region : this.regions) {
            if (mouseX < region.x || mouseX >= region.x + region.w || mouseY < region.y || mouseY >= region.y + region.h) {
                continue;
            }
            if (button == 0 && region.action != null) {
                Bricks.click();
                onAction(region.action, region.extra);
                return true;
            }
            if (button == 1 && region.rightAction != null) {
                Bricks.click();
                onAction(region.rightAction, region.extra);
                return true;
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 购物城搜索框聚焦时：把按键（含数字 1-9）全部交给输入框，不要触发切页快捷键。
        if (this.mallSearchBox != null && this.mallSearchBox.isVisible() && this.mallSearchBox.isFocused()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        // 交易 / 转账的玩家搜索框同理：输入时别让数字键跑去切页。
        if (this.tradeSearchBox != null && this.tradeSearchBox.isVisible() && this.tradeSearchBox.isFocused()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
            List<String> tabs = visibleTabs();
            int index = keyCode - GLFW.GLFW_KEY_1;
            if (index < tabs.size()) {
                String tab = tabs.get(index);
                // 需求 3：点击“玩家摊位”改为进入上架物品（摊位管理）界面，逛摊位走单独的按钮。
                currentTab = tab;
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void onAction(String action, CompoundTag extra) {
        try {
            onActionInner(action, extra);
        } catch (Throwable error) {
            // 兜底：界面构造 / 类加载失败（例如热替换 jar 后旧类找不到）不该把游戏崩掉。
            org.slf4j.LoggerFactory.getLogger("module_shop").error("商店界面动作 {} 执行失败", action, error);
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.displayClientMessage(Component.translatable(
                        "msg.module_shop.ui_open_failed"), false);
            }
        }
    }

    /** 真正的动作分发（外面包了 try/catch，见 {@link #onAction}）。 */
    private void onActionInner(String action, CompoundTag extra) {
        switch (action) {
            case "set_tab" -> selectTab(extra.getString("tab"));
            case "auction_prev" -> auctionPage = Math.max(0, auctionPage - 1);
            case "auction_next" -> auctionPage++;
            case "mail_prev" -> mailPage = Math.max(0, mailPage - 1);
            case "mail_next" -> mailPage++;
            case "admin_prev" -> adminPage = Math.max(0, adminPage - 1);
            case "admin_next" -> adminPage++;
            // 日夜主题切换（原版太阳 / 月亮按钮）：写入客户端主题文件，所有界面立即跟随。
            // 打开每日签到界面（需求 7）。
            case "open_signin" -> Nav.open(new SignInScreen());
            // 签到/交易/转账 现在是左侧模块，页内按钮打开原界面;)
            case "open_signin_screen" -> Nav.open(new SignInScreen());
            case "open_trade_screen" -> Nav.open(new TradeSelectScreen(false));
            case "open_transfer_screen" -> {
                clearNotification(NotificationType.WALLET);
                Nav.open(new TradeSelectScreen(true));
            }
            // 签到详细设置（原先在签到界面右下角，现在在商店设置页）
            case "admin_signin_set" -> openSignInSettings();
            case "admin_signin_cumulative_toggle" -> send("admin_signin_cumulative_toggle", null);
            case "admin_signin_auto_toggle" -> send("admin_signin_auto_toggle", null);
            // 签到（模块页，原独立界面已并入）：签到 / 补签 / 早晚翻页 / 管理员编辑日程
            case "sign_in" -> send("sign_in", null);
            case "signin_makeup" -> {
                long makeupDay = extra == null ? 0L : extra.getLong("day");
                if (makeupDay > 0L) {
                    // 补签要花钱，先二次确认再说（和原来独立界面里的流程一致）。
                    Nav.open(new ShopConfirmScreen(
                            Component.translatable("gui.module_shop.signin.makeup_title"),
                            Component.translatable("gui.module_shop.signin.makeup_tip",
                                    ClientShopData.money(ClientShopData.signInMakeupCost),
                                    java.time.LocalDate.ofEpochDay(makeupDay).toString()),
                            ok -> {
                                if (ok) {
                                    CompoundTag tag = new CompoundTag();
                                    tag.putLong("day", makeupDay);
                                    send("sign_makeup", tag);
                                }
                            }));
                }
            }
            case "signin_activity_edit" -> {
                long editDay = extra == null ? ClientShopData.signInToday : extra.getLong("day");
                Nav.open(new ScheduleEditScreen(editDay));
            }
            case "signin_activity_clear" -> send("admin_signin_day_clear", extra);
            case "signin_edit_toggle" -> this.signInEdit = !this.signInEdit;
            case "signin_month_prev" -> ClientShopData.signInMonth--;
            case "signin_month_next" -> ClientShopData.signInMonth++;
            case "signin_year_prev" -> ClientShopData.signInMonth -= 12;
            case "signin_year_next" -> ClientShopData.signInMonth += 12;
            // ---- 交易 / 转账（模块页）：点玩家 = 发起交易 / 进入转账金额输入 ----
            case "trade_pick" -> {
                String target = extra == null ? "" : extra.getString("name");
                if (target.isEmpty()) {
                    return;
                }
                if ("transfer".equals(currentTab)) {
                    // 转账：先选玩家，再弹金额输入框（金额要手打，所以仍然是独立输入界面）。
                    CompoundTag request = new CompoundTag();
                    request.putString("kind", "input");
                    request.putString("action", "do_transfer");
                    request.putString("title", "gui.module_shop.input.transfer_amount");
                    request.putString("target", target);
                    ListTag fields = new ListTag();
                    fields.add(StringTag.valueOf("amount"));
                    request.put("fields", fields);
                    Nav.open(new ShopInputScreen(request));
                } else {
                    // 交易：服务端收到请求后会推交易请求 / 交易界面，先把它们从「已关闭」名单里放出来。
                    Nav.rearm(TradeScreen.class);
                    Nav.rearm(TradeRequestScreen.class);
                    CompoundTag tag = new CompoundTag();
                    tag.putString("target", target);
                    send("trade_request", tag);
                }
            }
            case "trade_page_prev" -> tradePage = Math.max(0, tradePage - 1);
            case "trade_page_next" -> tradePage++;
            case "trade_requests_open" -> send("trade_requests_open", null);
            // 商店设置 → 签到：就地切换「编辑日程」态，点下面日历上的日期即可新建 / 修改。
            case "admin_signin_schedule" -> this.signInEdit = !this.signInEdit;
            // 需求 8：修改当前模块的刷新参数。
            case "admin_gen_set" -> openGenSet();
            // 侧栏布局：进入编辑模式（工具条里的"完成"退出，"重置布局"恢复默认）。
            case "sidebar_edit" -> {
                this.layoutEdit = !this.layoutEdit;
                // 进入编辑 = 服务端拉一份草稿（之后拖拽 / 改名 / 改标题都只影响自己）；
                // 点「完成」= 把草稿一次性发布给所有玩家。
                send(this.layoutEdit ? "admin_layout_begin" : "admin_layout_commit", null);
            }
            // 左上角主标题改名（唯一的改名入口）
            case "rename_shop_title" -> openShopTitleRename();
            // 编辑布局下右键余额 → 改货币名（全服生效）
            case "rename_currency" -> openCurrencyRename();
            // 第七轮：邮箱选中 / 星标
            case "mail_select" -> ClientShopData.mailSelected = extra == null ? "" : extra.getString("mail_id");
            case "open_redeem" -> Nav.open(new RedeemCodeScreen());
            case "mail_star" -> send("mail_star", extra);
            // 收购全清奖励：管理员开关 + 改金额（金额 0 = 关闭）。
            case "admin_buyback_bonus_toggle" -> send("admin_buyback_bonus_toggle", null);
            case "buyback_bonus_set" -> {
                CompoundTag bonusReq = new CompoundTag();
                bonusReq.putString("default_amount", Long.toString(Math.max(0L, ClientShopData.buybackBonus)));
                openInput("admin_buyback_bonus_set", "gui.module_shop.input.buyback_bonus", bonusReq, "amount");
            }
            // 右下角小按钮：只有管理员能看到，服务端仍然会对 admin_* 操作再次校验权限。
            case "admin_open" -> {
                currentTab = "admin";
                sendAdminType();
                clearTabNotification("admin");
            }
            // 内容区右上角的 ×（OneConfig 同款）：等价于 ESC —— 关掉整个商店界面。
            case "close_ui" -> onClose();
            // 货币兑换：点击一行展开/收起（同时只展开一行）。
            case "exchange_open" -> {
                int idx = extra == null ? -1 : extra.getInt("index");
                ClientShopData.lastExchangeOpen = ClientShopData.lastExchangeOpen == idx ? -1 : idx;
            }
            // 兑换 / 取款的数量输入框：客户端直接打开，标题里带上真实货币名
            // （例如「取款数量（星币 → 银币）」）；数据不全时退回服务端打开（由服务端给出提示）。
            case "exchange_deposit_gui" -> openExchangeInput(extra, true);
            case "exchange_withdraw_gui" -> openExchangeInput(extra, false);
            // 管理员改汇率：带出当前汇率，方便微调。
            case "exchange_edit" -> {
                int idx = extra == null ? -1 : extra.getInt("index");
                if (idx >= 0 && idx < ClientShopData.exchange.size()) {
                    CompoundTag req = extra.copy();
                    req.putString("default_value", Long.toString(ClientShopData.exchange.get(idx).value));
                    openInput("admin_exchange_edit", "gui.module_shop.input.exchange_value", req, "value");
                }
            }
            // 管理页顶部「选择模块编辑」：点按钮展开 / 收起，点列表里的一项切到该模块的编辑界面。
            // （旧写法是两个固定页签按钮 + "admin_mode" 动作，已由下拉取代。）
            // ---- 购物城（新增模块）----
            case "mall_search" -> {
                // 搜索是实时过滤（每敲一个字就刷新），这里只把焦点收回，方便之后用滚轮翻页。
                if (this.mallSearchBox != null) {
                    this.mallSearchBox.setFocused(false);
                }
            }
            case "mall_fav_toggle" -> {
                ClientShopData.mallFavOnly = !ClientShopData.mallFavOnly;
                ClientShopData.mallPage = 0;
            }
            case "mall_sort_menu" -> {
                this.mallSortOpen = !this.mallSortOpen;
                this.mallCatOpen = false;
            }
            case "mall_cat_menu" -> {
                this.mallCatOpen = !this.mallCatOpen;
                this.mallSortOpen = false;
                this.mallCatScroll = 0;
            }
            case "mall_recent_toggle" -> {
                ClientShopData.mallViewRecent = !ClientShopData.mallViewRecent;
                ClientShopData.mallRecentScroll = 0;
                if (this.mallSearchBox != null && ClientShopData.mallViewRecent) {
                    this.mallSearchBox.setFocused(false);
                }
            }
            case "mall_sort_pick" -> ClientShopData.mallSort = extra == null ? 0 : extra.getInt("sort");
            case "mall_cat_pick" -> {
                ClientShopData.mallCategory = extra == null ? "" : extra.getString("category");
                ClientShopData.mallPage = 0;
            }
            case "mall_prev" -> ClientShopData.mallPage = Math.max(0, ClientShopData.mallPage - 1);
            case "mall_next" -> ClientShopData.mallPage++;
            // 收藏：点卡片右上角的小星星
            case "mall_star" -> {
                int shelfIndex = extra == null ? -1 : extra.getInt("index");
                if (shelfIndex >= 0 && shelfIndex < ClientShopData.mall.size()) {
                    Bricks.click();
                    MallFavorites.toggle(mallItemId(ClientShopData.mall.get(shelfIndex)));
                }
            }
            // 购买：弹数量输入（与每日商店同一套：显示单价 / 总量 / 购买后余额）
            // 购买：点商品 → 弹「买几个」窗口（默认 1，可用 Shift/右键也行，行为一致）。
            case "mall_buy", "mall_buy_many" -> {
                int shelfIndex = extra == null ? -1 : extra.getInt("index");
                if (shelfIndex < 0 || shelfIndex >= ClientShopData.mall.size()) {
                    return;
                }
                ClientShopData.MallView view = ClientShopData.mall.get(shelfIndex);
                if (view.soldOut()) {
                    return;
                }
                CompoundTag req = new CompoundTag();
                req.putInt("index", shelfIndex);
                req.putString("default_quantity", "1");
                req.putLong("unit_price", view.price);
                req.putLong("balance", ClientShopData.balance);
                req.putString("info", Component.translatable("gui.module_shop.mall.buy_info",
                        view.stack.getHoverName(), ClientShopData.money(view.price), mallStockText(view))
                        .getString());
                openInput("buy_mall", "gui.module_shop.input.buy_quantity", req, "quantity");
            }
            // 管理员：每日额度（出货箱）/ 整模组导入（购物城）
            case "mall_import" -> Nav.open(new MallImportScreen());
            case "mall_restock" -> openMallRestock();
            case "mall_refresh" -> openMallRefresh();
            case "shipping_limits" -> openShippingLimits();
            // 商店设置 → 摊位：自动下架时间（永久 / 游戏时间 / 现实时间）
            case "stall_expire" -> openStallExpire();
            // ---- 玩家求购（悬赏）----
            case "demand_create" -> openDemandCreate();
            case "demand_filter" -> this.demandOnlyMine = !this.demandOnlyMine;
            // 点一行 = 展开/收起它的清单（展开后右侧有交付/撤销按钮）
            case "demand_toggle" -> {
                String id = extra == null ? "" : extra.getString("id");
                this.demandExpandedId = id.equals(this.demandExpandedId) ? null : id;
            }
            // 交付：扣清单物品、发悬赏（服务端校验并结算）
            case "demand_fulfill_go" -> send("demand_fulfill", extra);
            case "demand_cancel_go" -> send("demand_cancel", extra);
            case "demand_settings" -> openDemandSettings();
            case "demand_expire" -> openDemandExpire();
            case "demand_admin_cancel" -> openDemandAdminCancel(extra == null ? "" : extra.getString("id"));
            // ---- 出货箱（新增模块）----
            case "shipping_open" -> send("shipping_open", null);
            case "shipping_upgrade" -> send("shipping_upgrade", null);            case "mall_add" -> {
                // 先把"当前编辑模块"切成购物城，服务端才知道新物品该进哪个列表。
                adminMode = "mall";
                sendAdminType();
                send("admin_add", null);
            }
            case "admin_module_menu" -> this.adminModuleOpen = !this.adminModuleOpen;
            case "admin_module_pick" -> {
                this.adminModuleOpen = false;
                String picked = extra == null ? "" : extra.getString("id");
                if (!picked.isEmpty() && !picked.equals(adminMode)) {
                    adminMode = picked;
                    adminPage = 0;
                    // 与旧的两个页签按钮完全一致：通知服务端后续放入的物品归属哪个列表。
                    sendAdminType();
                }
            }
            case "flip_shop" -> {
                if (extra != null) {
                    int idx = extra.getInt("index");
                    // 记下「旧面」（此刻还没翻开 = 背面）与起始 tick：动画前半段画背面、后半段画正面。
                    this.flipFrom.put(idx, ClientShopData.dailyFlipped.contains(idx));
                    this.flipStart.put(idx, tickCounter);
                }
                // 需求 11：通知服务端持久化翻开状态并播放对应稀有度音效。
                send("flip_shop", extra);
            }
            // 付费刷新每日商店：先二次确认（花星币的操作不让人手滑），确认后服务端再校验次数与余额。
            case "refresh_daily_shop" -> confirmRefresh();
            // 账单：点顶部余额进入。
            case "open_bill" -> {
                Nav.open(new BillScreen());
                send("bill_request", null);
            }
            // 付费刷新每日收购：与商店分开的次数 / 价格，同样先二次确认。
            case "refresh_daily_buyback" -> confirmBuybackRefresh();
            // 需求 8：每日商店购买前弹出数量输入（可多件），底部左侧确认/右侧取消。
            case "buy_shop" -> {
                int idx = extra == null ? -1 : extra.getInt("index");
                if (idx >= 0 && idx < ClientShopData.dailyShop.size()) {
                    var entry = ClientShopData.dailyShop.get(idx);
                    CompoundTag req = new CompoundTag();
                    req.putInt("index", idx);
                    req.putString("default_quantity", "1");
                    req.putLong("unit_price", entry.price);
                    req.putLong("balance", ClientShopData.balance);
                    req.putString("info", Component.translatable("gui.module_shop.buy_shop_info",
                            entry.stack.getHoverName(), ClientShopData.money(entry.price), entry.remaining()).getString());
                    req.putBoolean("confirm_right", false);
                    openInput("buy_shop", "gui.module_shop.input.buy_quantity", req, "quantity");
                }
            }
            // 需求 9：收购前弹出数量输入，玩家自行调节卖出数量（并提示该物品的收购上限）。
            case "sell_buyback_gui" -> {
                CompoundTag req = extra == null ? new CompoundTag() : extra.copy();
                int idx = req.getInt("index");
                if (idx >= 0 && idx < ClientShopData.dailyBuyback.size()) {
                    req.putString("info", Component.translatable("gui.module_shop.buyback_max",
                            ClientShopData.dailyBuyback.get(idx).remaining()).getString());
                }
                openInput("sell_buyback", "gui.module_shop.input.sell_quantity", req, "quantity");
            }
            // 需求 2：竞拍界面改为带物品图标、快捷加减价的专用界面。
            case "bid_gui" -> {
                ClientShopData.AuctionView view = findAuction(extra == null ? 0L : extra.getLong("id"));
                if (view != null) {
                    Nav.open(new AuctionBidScreen(view));
                }
            }
            // 需求：删除邮件；未领取/未查看时必须先二次确认，防止误删资产。
            case "mail_delete" -> {
                String mailId = extra == null ? "" : extra.getString("mail_id");
                cn.autoforged.module_shop.data.MailEntry entry = findMail(mailId);
                if (entry == null) {
                    return;
                }
                if (entry.pending()) {
                    confirmDeleteMail(entry);
                } else {
                    send("delete_mail", extra);
                }
            }
            // 需求 4：转账先打开在线玩家列表（带搜索框），点选玩家后再输入金额。
            case "transfer_gui" -> {
                clearNotification(NotificationType.WALLET);
                Nav.open(new TradeSelectScreen(true));
            }
            case "trade_select" -> Nav.open(new TradeSelectScreen(false));
            case "trade_gui" -> openInput("trade_request", "gui.module_shop.input.trade_target", null, "target");
            case "stall_edit_gui" -> openInput("stall_apply_price", "gui.module_shop.input.stall_price", extra, "price");
            // 需求 6/7：管理界面可编辑价值(price)、品质(rarity)、数量(quantity)，并带出当前值。
            case "admin_edit_gui" -> openAdminEdit(extra);
            // 右键删除条目（每日商店 / 收购 / 购物城 / 出货箱规则都走这里）。
            // ⚠ 之前漏了这个分支：界面提示写着"右键删除"，但点了其实什么都不发。
            case "admin_remove" -> send("admin_remove", extra);
            // 出货箱里格式错误的那一条：左右键都直接删掉它（没别的可编辑）
            case "admin_remove_broken" -> send("admin_remove", extra);
            // 管理员发放界面由服务端打开容器界面（物品放服务端槽位里，客户端不可伪造）。
            case "admin_grant_open" -> send("admin_grant_open", null);
            // 交易记录：复用输入弹窗收集玩家名，服务端把流水回传到聊天栏。
            case "admin_history_gui" -> openInput("admin_history", "gui.module_shop.input.history", null, "target");
            // 需求 10-5：左下角齿轮打开提醒音效设置。
            case "notif_settings" -> {
                Bricks.click();
                Nav.open(new NotificationSettingsScreen());
            }
            case "open_avatar" -> {
                clearNotification(NotificationType.STALL);
                Nav.open(new AvatarScreen());
            }
            default -> send(action, extra);
        }
    }

    /** 需求：按 mailId 取本地缓存的邮件（仅用于渲染/二次确认，不作为权威数据）。 */
    private static cn.autoforged.module_shop.data.MailEntry findMail(String mailId) {
        if (mailId == null || mailId.isEmpty()) {
            return null;
        }
        for (cn.autoforged.module_shop.data.MailEntry entry : ClientShopData.mail) {
            if (mailId.equals(entry.mailId)) {
                return entry;
            }
        }
        return null;
    }

    /** 需求：删除未领取/未查看邮件前的二次确认弹窗。 */
    private void confirmDeleteMail(cn.autoforged.module_shop.data.MailEntry entry) {
        Nav.open(new ShopConfirmScreen(
                Component.translatable("gui.module_shop.mail.delete_title"),
                Component.literal(mailDescription(entry)),
                confirmed -> {
                    if (confirmed) {
                        CompoundTag tag = new CompoundTag();
                        tag.putString("mail_id", entry.mailId);
                        tag.putBoolean("confirmed", true);
                        send("delete_mail", tag);
                    }
                }));
    }

    /**
     * 货币兑换的数量输入框（客户端直接打开）。
     * 标题用「兑换数量（银币 → 星币）」/「取款数量（星币 → 银币）」的形式带上真实货币名，
     * 取款框还会把单价与余额传下去，让输入界面能在右侧实时提示「余额不足」。
     */
    private void openExchangeInput(CompoundTag extra, boolean deposit) {
        int idx = extra == null ? -1 : extra.getInt("index");
        if (idx < 0 || idx >= ClientShopData.exchange.size()) {
            // 本地快照缺失（例如刚到服务器）→ 交给服务端打开，由服务端给出提示。
            send(deposit ? "exchange_deposit_gui" : "exchange_withdraw_gui", extra);
            return;
        }
        ClientShopData.ExchangeView entry = ClientShopData.exchange.get(idx);
        String itemName = entry.stack.getHoverName().getString();
        String currency = ClientShopData.currencyName();
        CompoundTag req = new CompoundTag();
        req.putInt("index", idx);
        if (deposit) {
            if (entry.owned <= 0) {
                send("exchange_deposit_gui", extra);
                return;
            }
            req.putString("default_quantity", Integer.toString(entry.owned));
            openInputWithArgs("exchange_deposit", "gui.module_shop.input.exchange_deposit", req,
                    new String[]{itemName, currency}, "quantity");
        } else {
            long affordable = entry.value > 0L ? ClientShopData.balance / entry.value : 0L;
            if (affordable <= 0L) {
                send("exchange_withdraw_gui", extra);
                return;
            }
            int max = (int) Math.min(Integer.MAX_VALUE, affordable);
            req.putString("default_quantity",
                    Integer.toString(Math.min(max, Math.max(1, entry.stack.getMaxStackSize()))));
            req.putLong("balance", ClientShopData.balance);
            req.putBoolean("show_balance", true);
            req.putLong("unit_price", entry.value);
            req.putString("total_key", "gui.module_shop.exchange.total");
            openInputWithArgs("exchange_withdraw", "gui.module_shop.input.exchange_withdraw", req,
                    new String[]{currency, itemName}, "quantity");
        }
    }

    /** 管理员：签到详细设置（每日奖励 / 补签花费与范围 / 累计档位 / 里程奖励）。 */
    private void openSignInSettings() {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("kind", "input");
        tag.putString("action", "admin_signin_set");
        tag.putString("title", "gui.module_shop.signin.settings");
        net.minecraft.nbt.ListTag fields = new net.minecraft.nbt.ListTag();
        for (String f : new String[]{"base", "makeupCost", "makeupDays", "tiers", "miles"}) {
            fields.add(net.minecraft.nbt.StringTag.valueOf(f));
        }
        tag.put("fields", fields);
        tag.putString("default_base", Long.toString(ClientShopData.signInBase));
        tag.putString("default_makeupCost", Long.toString(ClientShopData.signInMakeupCost));
        tag.putString("default_makeupDays", Integer.toString(ClientShopData.signInMakeupDays));
        tag.putString("default_tiers", signInPairs(ClientShopData.signInTierDays, ClientShopData.signInTierBonus));
        tag.putString("default_miles", signInPairs(ClientShopData.signInMileDays, ClientShopData.signInMileMoney));
        Nav.open(new ShopInputScreen(tag));
    }

    /** 把「天数:奖励」拼成 {@code 3:5,5:7,7:10}（与签到界面的格式一致）。 */
    private static String signInPairs(int[] days, long[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < days.length && i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(days[i]).append(':').append(values[i]);
        }
        return sb.toString();
    }

    /** 管理员：修改当前编辑模块（每日商店 / 每日收购）的权重、保底与条目上限。 */
    private void openGenSet() {
        boolean shop = !"buyback".equals(adminMode);
        int[] weights = shop ? ClientShopData.shopWeights : ClientShopData.buybackWeights;
        int[] guarantee = shop ? ClientShopData.shopGuarantee : ClientShopData.buybackGuarantee;
        int slots = shop ? ClientShopData.shopSlotCount : ClientShopData.buybackSlotCount;
        CompoundTag req = new CompoundTag();
        req.putString("mode", shop ? "shop" : "buyback");
        req.putString("default_weights", joinInts(weights));
        req.putString("default_guarantee", joinInts(guarantee));
        req.putString("default_slots", Integer.toString(slots));
        req.putString("info", Component.translatable(shop
                ? "gui.module_shop.admin_shop" : "gui.module_shop.admin_buyback").getString());
        openInput("admin_shop_gen_set", "gui.module_shop.input.admin_gen", req,
                "weights", "guarantee", "slots");
    }

    private static String joinInts(int[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

    /** 需求 6/7：管理界面编辑条目时带出当前价值/品质/数量。 */
    /** 出货箱管理页：「每日额度」按钮 —— 分档填写每个等级的每日结算上限（写回配置文件）。 */
    private void openShippingLimits() {
        CompoundTag req = new CompoundTag();
        int levels = Math.max(1, ClientShopData.shipMaxLevel);
        String[] fields = new String[levels];
        for (int i = 0; i < levels; i++) {
            fields[i] = "limit" + (i + 1);
            long value = i < ClientShopData.shipDailyLimits.size() ? ClientShopData.shipDailyLimits.get(i) : 0L;
            req.putString("default_limit" + (i + 1), Long.toString(Math.max(0L, value)));
        }
        // 结算时间（游戏时间，默认 06:00）与额度重置时间（现实时间，默认 00:00）：都是 HH:mm 文本输入
        req.putString("default_settle_time", Config.dayTickToClock(Config.INSTANCE.shippingSettleTick.get()));
        req.putString("default_reset_time", ClientShopData.shipResetTimeText);
        req.putString("info", Component.translatable("gui.module_shop.shipping.limits_info").getString());
        String[] all = java.util.Arrays.copyOf(fields, fields.length + 2);
        all[fields.length] = "settle_time";
        all[fields.length + 1] = "reset_time";
        openInput("admin_shipping_limits", "gui.module_shop.input.shipping_limits", req, all);
    }

    /** 购物城管理页：「补货设置」按钮 —— 定时补货开关 / 时间口径 / 补货时刻 / 补货数量（写回配置文件）。 */
    /** 商店设置 → 购物城：库存设置（库存模式：全服 / 个人；以及多久整店刷新一次库存）。 */
    private void openMallRefresh() {
        CompoundTag req = new CompoundTag();
        ListTag stockMode = new ListTag();
        stockMode.add(StringTag.valueOf("global"));
        stockMode.add(StringTag.valueOf("personal"));
        req.put("choices_stock_mode", stockMode);
        req.putString("default_stock_mode", ClientShopData.mallStockPersonal ? "personal" : "global");
        ListTag mode = new ListTag();
        mode.add(StringTag.valueOf("off"));
        mode.add(StringTag.valueOf("real"));
        mode.add(StringTag.valueOf("game"));
        req.put("choices_refresh_mode", mode);
        req.putString("default_refresh_mode", ClientShopData.mallRefreshMode);
        req.putString("default_refresh_amount", Integer.toString(Math.max(1, ClientShopData.mallRefreshAmount)));
        req.putString("info", Component.translatable("gui.module_shop.mall.stock_info").getString());
        openInput("admin_mall_refresh", "gui.module_shop.input.mall_stock", req,
                "stock_mode", "refresh_mode", "refresh_amount");
    }

    private void openMallRestock() {
        CompoundTag req = new CompoundTag();
        ListTag on = new ListTag();
        on.add(StringTag.valueOf("0"));
        on.add(StringTag.valueOf("1"));
        req.put("choices_restock_on", on);
        req.putString("default_restock_on", ClientShopData.mallRestockOn ? "1" : "0");
        ListTag mode = new ListTag();
        mode.add(StringTag.valueOf("real"));
        mode.add(StringTag.valueOf("game"));
        req.put("choices_restock_mode", mode);
        req.putString("default_restock_mode", ClientShopData.mallRestockMode);
        req.putString("default_restock_time", ClientShopData.mallRestockTimeText);
        req.putString("default_restock_amount", Integer.toString(Math.max(0, ClientShopData.mallRestockAmount)));
        req.putString("info", Component.translatable("gui.module_shop.mall.restock_info").getString());
        openInput("admin_mall_restock", "gui.module_shop.input.mall_restock", req,
                "restock_on", "restock_mode", "restock_time", "restock_amount");
    }

    private void openAdminEdit(CompoundTag extra) {
        if (extra == null) {
            return;
        }
        String mode = extra.getString("mode");
        int index = extra.getInt("index");
        // 编辑对象必须按 mode 取对列表：购物城是货架、出货箱是兑换规则
        // （以前 mall 会被当成"每日商店"，弹窗里带出的是商店另一件商品的价格 / 品质 / 数量）。
        List<ConfiguredOffer> pool = "mall".equals(mode) ? ClientShopData.adminMall
                : ("shipping".equals(mode) ? ClientShopData.adminShipping
                        : ("buyback".equals(mode) ? ClientShopData.adminBuyback : ClientShopData.adminShop));
        CompoundTag req = extra.copy();
        if (index >= 0 && index < pool.size()) {
            ConfiguredOffer offer = pool.get(index);
            req.putString("default_price", Long.toString(offer.price));
            req.putString("default_rarity", offer.rarity.id());
            req.putString("default_quantity", Integer.toString(offer.amount));
        }
        // 出货箱的规则没有"品质"，只需要价格与每日份数两个字段。
        if ("shipping".equals(mode)) {
            openInput("admin_apply_price", "gui.module_shop.input.shipping_rule", req, "price");
            return;
        }
        openInput("admin_apply_price", "gui.module_shop.input.admin_offer", req, "price", "rarity", "quantity");
    }

    private static ClientShopData.AuctionView findAuction(long id) {
        for (ClientShopData.AuctionView view : ClientShopData.auctions) {
            if (view.id == id) {
                return view;
            }
        }
        return null;
    }

    private void send(String action, CompoundTag extra) {
        // 玩家主动操作 → 允许服务端随后推送的「数量/金额输入」界面（不然刚取消过一次就再也弹不出来）。
        Nav.rearm(ShopInputScreen.class);
        CompoundTag tag = extra == null ? new CompoundTag() : extra.copy();
        tag.putString("action", action);
        ServerboundShopActionPayload.send(tag);
    }

    /**
     * 付费刷新每日商店前的二次确认：显示本次花费与今日剩余次数，确认后才真正扣费刷新。
     * 取消（或按 ESC）原样回到商店界面，什么都不扣。
     */
    private void confirmRefresh() {
        if (ClientShopData.refreshCost < 0L) {
            // 今日次数已用完：按钮本来是灰的，这里直接放行让服务端提示一次即可。
            send("refresh_daily_shop", null);
            return;
        }
        int left = Math.max(0, ClientShopData.refreshMax - ClientShopData.refreshUsed);
        Nav.open(new ShopConfirmScreen(
                Component.translatable("gui.module_shop.refresh_confirm_title"),
                Component.translatable("gui.module_shop.refresh_confirm_body",
                        ClientShopData.money(ClientShopData.refreshCost), left, ClientShopData.refreshMax),
                ok -> {
                    if (ok) {
                        send("refresh_daily_shop", null);
                    }
                }));
    }

    /** 付费刷新每日收购前的二次确认（与商店分开的次数与价格）。 */
    private void confirmBuybackRefresh() {
        if (ClientShopData.buybackRefreshCost < 0L) {
            send("refresh_daily_buyback", null);
            return;
        }
        int left = Math.max(0, ClientShopData.buybackRefreshMax - ClientShopData.buybackRefreshUsed);
        Nav.open(new ShopConfirmScreen(
                Component.translatable("gui.module_shop.refresh_buyback_confirm_title"),
                Component.translatable("gui.module_shop.refresh_buyback_confirm_body",
                        ClientShopData.money(ClientShopData.buybackRefreshCost), left,
                        ClientShopData.buybackRefreshMax),
                ok -> {
                    if (ok) {
                        send("refresh_daily_buyback", null);
                    }
                }));
    }

    /** 需求 10：本地先清红点，并请求服务端权威清除对应类型未读。 */
    private void clearNotification(NotificationType type) {
        if (type == null) {
            return;
        }
        ClientNotifications.clearLocal(type);
        CompoundTag tag = new CompoundTag();
        tag.putString("action", "notif_clear");
        tag.putString("type", type.id());
        ServerboundShopActionPayload.send(tag);
    }

    private void clearTabNotification(String tab) {
        switch (tab) {
            case "daily" -> clearNotification(NotificationType.SHOP);
            case "buyback" -> clearNotification(NotificationType.BUYBACK);
            case "auction" -> clearNotification(NotificationType.AUCTION);
            case "stall" -> clearNotification(NotificationType.STALL);
            case "mail" -> clearNotification(NotificationType.MAIL);
            default -> {
            }
        }
    }

    private void openInput(String action, String titleKey, CompoundTag extra, String... fields) {
        openInputWithArgs(action, titleKey, extra, null, fields);
    }

    /** 打开输入界面；titleArgs 会作为 Component.translatable(titleKey, args...) 的参数在客户端本地化。 */
    private void openInputWithArgs(String action, String titleKey, CompoundTag extra, String[] titleArgs,
            String... fields) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "input");
        tag.putString("action", action);
        tag.putString("title", titleKey);
        ListTag list = new ListTag();
        for (String field : fields) {
            list.add(StringTag.valueOf(field));
        }
        tag.put("fields", list);
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
        Nav.open(new ShopInputScreen(tag));
    }

    private void addRegion(int x, int y, int w, int h, String action, String rightAction, CompoundTag extra) {
        this.regions.add(new Region(x, y, w, h, action, rightAction, extra));
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, Component label) {
        IOStyle.button(g, x, y, w, h, label, this.font, this.pointerX, this.pointerY, true, IOStyle.Btn.TINT);
    }

    /** 需求 11：选中的编辑模块用主色（蓝）按钮。 */
    private void drawButton(GuiGraphics g, int x, int y, int w, int h, Component label, boolean selected) {
        IOStyle.button(g, x, y, w, h, label, this.font, this.pointerX, this.pointerY, true,
                selected ? IOStyle.Btn.PRIMARY : IOStyle.Btn.TINT);
    }

    /** 小尺寸按钮：IOStyle 圆角底 + 0.8 倍字号文字，用于右下角管理员入口与货币兑换行内按钮。 */
    private void drawSmallLabelButton(GuiGraphics g, int x, int y, int w, int h, Component label, boolean hovered) {
        drawSmallLabelButton(g, x, y, w, h, label, hovered, false, false);
    }

    /** 小尺寸按钮：新 UI 皮肤（渐变 + 内高光）+ 悬停上浮 / 按下缩放 / 点击水波。 */
    private void drawSmallLabelButton(GuiGraphics g, int x, int y, int w, int h, Component label, boolean hovered,
            boolean accent, boolean danger) {
        drawSmallLabelButton(g, x, y, w, h, label, hovered, accent, danger, 0.8F);
    }

    /**
     * 小尺寸按钮的完整版：{@code textScale} 控制文字大小（默认 0.8，货币兑换行内按钮用 0.7 再小一号）。
     */
    private void drawSmallLabelButton(GuiGraphics g, int x, int y, int w, int h, Component label, boolean hovered,
            boolean accent, boolean danger, float textScale) {
        int base;
        int fg;
        if (danger) {
            base = hovered ? IOStyle.RED_TINT_HOVER : IOStyle.RED_TINT;
            fg = IOStyle.RED;
        } else if (accent) {
            base = hovered ? IOStyle.BLUE_TINT_HOVER : IOStyle.BLUE_TINT;
            fg = IOStyle.BLUE;
        } else {
            base = hovered ? IOStyle.GRAY_BTN_HOVER : IOStyle.GRAY_BTN;
            fg = IOStyle.TEXT;
        }
        int r = Math.min(6, h / 2);
        String key = "small:" + x + "," + y + "," + w + "," + h;
        boolean pressedNow = hovered && IOStyle.mouseDown();
        if (pressedNow && IOStyle.markPressed(key)) {
            IOStyle.ripple(key, this.pointerX, this.pointerY);
        }
        float hp = IOStyle.hoverProgress(key, hovered);
        float pp = IOStyle.pressProgress(key, pressedNow);
        int lift = -Math.round(hp);
        g.pose().pushPose();
        if (pp > 0.005F) {
            float cx = x + w / 2.0F;
            float cy = y + h / 2.0F;
            float scale = 1.0F - 0.05F * pp;
            g.pose().translate(cx, cy, 0.0F);
            g.pose().scale(scale, scale, 1.0F);
            g.pose().translate(-cx, -cy, 0.0F);
        }
        int dy = y + lift;
        if (hp > 0.01F) {
            IOStyle.rounded(g, x, dy + 1, w, h, r, IOStyle.withAlpha(base, Math.round(70.0F * hp)));
        }
        IOStyle.rounded(g, x, dy, w, h, r, base);
        IOStyle.topHighlight(g, x, dy, w, Math.min(3, r / 2), 10 + Math.round(26.0F * hp));
        IOStyle.ring(g, x, dy, w, h, r,
                IOStyle.mix(IOStyle.withAlpha(0xFF000000, 40), IOStyle.withAlpha(fg, 150), hp));
        IOStyle.drawRipple(g, key, x, dy, w, h, r, 0xFFFFFFFF);
        g.pose().pushPose();
        g.pose().translate(x + w / 2.0F, dy + (h - 8) / 2.0F + Math.round(pp), 0.0F);
        g.pose().scale(textScale, textScale, 1.0F);
        IOStyle.centerAt(g, this.font, label, 0, 0, fg);
        g.pose().popPose();
        g.pose().popPose();
    }

    private static String symbol() {
        return ClientShopData.symbol;
    }

    /** 剩余时间以现实时间毫秒计（需求 1）。 */
    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    private record Region(int x, int y, int w, int h, String action, String rightAction, CompoundTag extra) {
    }

    @Override
    public void onClose() {
        if (this.layoutEdit) {
            // 编辑布局时直接关界面 = 视同完成，避免改动只在草稿里、谁都没同步到。
            this.layoutEdit = false;
            send("admin_layout_commit", null);
        }
        // ESC 返回上一级界面（Nav 返回栈），没有上一级时才真正关闭。
        Nav.back();
    }
}
