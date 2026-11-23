package cn.autoforged.module_shop.api.client;

/** 一帧的内容区几何（GUI 像素）：绘制与输入共用同一份，保证「画在哪就点在哪」。 */
public record AddonFrame(AddonPageHost host, float x, float y, float w, float h,
                         float mouseX, float mouseY, float dtMs) {
    /** 底部操作条高度（与内置页面一致）。 */
    public static final float BAR_H = 18.0F;

    /** 列表区底边（底部操作条之上）。 */
    public float listBottom() {
        return y + h - BAR_H - 4.0F;
    }

    /** 底部操作条顶边。 */
    public float barTop() {
        return y + h - BAR_H;
    }

    public boolean contains(double px, double py) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }
}
