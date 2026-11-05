package cn.autoforged.module_shop.client.screen.shop;

import cn.autoforged.module_shop.client.ClientShopData;
import cn.autoforged.module_shop.client.screen.IOStyle;
import cn.autoforged.module_shop.client.ui.AtomUi;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import com.atom.lib.design.atomchat.SkiaDraw;
import io.github.humbleui.skija.Canvas;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** 各页面共用的小工具：卡片、物品槽、空状态、底部渐隐、物品纯名称、剩余时间文字。 */
public final class PageKit {
    private PageKit() {
    }

    /** 普通卡片：卡片底色 + 暗边框。 */
    public static void card(Canvas canvas, float x, float y, float w, float h) {
        Draw.fill(canvas, x, y, w, h, 3.0F, IOStyle.CARD);
        Draw.stroke(canvas, x, y, w, h, 3.0F, 1.0F, IOStyle.BORDER_DIM);
    }

    /** 可交互卡片：悬停时底色与边框变亮（hover 为 0..1 的动画值）。 */
    public static void hoverCard(Canvas canvas, float x, float y, float w, float h, float hover) {
        Draw.fill(canvas, x, y, w, h, 3.0F, Draw.lerp(IOStyle.CARD, IOStyle.ROW_HOVER, hover));
        Draw.stroke(canvas, x, y, w, h, 3.0F, 1.0F, Draw.lerp(IOStyle.BORDER_DIM, IOStyle.BORDER_HOVER, hover));
    }

    /** 18×18 物品槽，并把物品登记到物品层（物品画在槽内 1px 处）。 */
    public static void slot(Canvas canvas, ShopHost host, ItemStack stack, float x, float y) {
        Draw.fill(canvas, x, y, 18.0F, 18.0F, 2.0F, IOStyle.SLOT_INNER);
        Draw.stroke(canvas, x, y, 18.0F, 18.0F, 2.0F, 1.0F, IOStyle.SLOT_BORDER);
        if (stack != null && !stack.isEmpty()) {
            host.items().add(stack, x + 1.0F, y + 1.0F);
        }
    }

    /** 空状态 / 说明文字：居中、次要色。 */
    public static void emptyText(Canvas canvas, String text, float x, float centerY, float w) {
        Text.drawLine(canvas, text, AtomUi.REGULAR, Text.BODY, IOStyle.TEXT_2, x, centerY, w, Text.Align.CENTER);
    }

    /** 列表底部的“下面还有”渐隐（bg 为列表所在区域的底色）。 */
    public static void fadeBottom(Canvas canvas, float x, float bottom, float w, int bg) {
        SkiaDraw.drawVerticalGradient(canvas, x, bottom - 10.0F, w, 10.0F, 0.0F, Draw.alpha(bg, 0.0F), bg);
    }

    /** 物品名（只要名字本身）：去掉 § 格式符与私用区字符（资源包常把它们映射成图标）。 */
    public static String plainName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        String raw = stack.getHoverName().getString();
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§') {
                i++;
                continue;
            }
            if ((c >= '' && c <= '') || Character.isISOControl(c)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /** 倒计时文字：h:mm:ss 或 m:ss。 */
    public static String duration(long millis) {
        long total = Math.max(0L, millis) / 1000L;
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        return h > 0L ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    /**
     * 限时上架的剩余时间文字（摊位 / 求购共用；与旧界面同一算法）。
     * 游戏时间用客户端同步的世界时间，现实时间用本机时钟；只是显示，真正的下架判定在服务端。
     *
     * @return 永久上架或缺少时间戳时返回 null
     */
    public static String remainText(String mode, int amount, long createdGame, long createdReal) {
        if ("permanent".equals(mode) || amount <= 0) {
            return null;
        }
        boolean game = "game".equals(mode);
        long limit;
        long age;
        if (game) {
            if (createdGame <= 0L) {
                return null;
            }
            var level = Minecraft.getInstance().level;
            long now = level != null ? level.getGameTime() : createdGame;
            limit = amount * 24_000L;
            age = now - createdGame;
        } else {
            if (createdReal <= 0L) {
                return null;
            }
            limit = amount * 3_600_000L;
            age = System.currentTimeMillis() - createdReal;
        }
        long left = Math.max(0L, limit - age);
        if (left <= 0L) {
            return Component.translatable("gui.module_shop.stall.remain_soon").getString();
        }
        if (game) {
            return left >= 24_000L
                    ? Component.translatable("gui.module_shop.stall.remain_days", left / 24_000L).getString()
                    : Component.translatable("gui.module_shop.stall.remain_hours", Math.max(1L, left / 1_000L))
                            .getString();
        }
        return left >= 3_600_000L
                ? Component.translatable("gui.module_shop.stall.remain_hours", left / 3_600_000L).getString()
                : Component.translatable("gui.module_shop.stall.remain_minutes", Math.max(1L, left / 60_000L))
                        .getString();
    }

    /** 金额文字（带当前货币名）。 */
    public static String money(long amount) {
        return ClientShopData.money(amount);
    }
}
