package cn.autoforged.module_shop.client.screen.shop;

/**
 * 一帧的页面布局参数（GUI 像素）：内容区矩形、鼠标位置、帧时长。
 * 绘制与输入使用同一份几何，保证“画在哪就点在哪”。
 */
public record PageFrame(ShopHost host, float x, float y, float w, float h, float mouseX, float mouseY, float dtMs) {
    /** 底部操作条高度（与 ShopScreen 旧版 BAR_H 一致）。 */
    public static final float BAR_H = 18.0F;

    /** 列表区底边（底部操作条之上）。 */
    public float listBottom() {
        return y + h - BAR_H - 4.0F;
    }

    /** 底部操作条的顶边。 */
    public float barTop() {
        return y + h - BAR_H;
    }

    public boolean contains(double px, double py) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }
}
