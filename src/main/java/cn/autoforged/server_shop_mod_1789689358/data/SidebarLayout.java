package cn.autoforged.server_shop_mod_1789689358.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

/**
 * 侧栏（左侧菜单列）的自定义布局 —— **只保存顺序列表**，不保存散乱坐标。
 *
 * <h2>为什么是顺序而不是 X/Y</h2>
 * 菜单固定排布在一套**网格**里（1 列 N 行，见客户端 {@code ShopScreen} 的网格常量），
 * 所以只要知道顺序，就能唯一算出每个按钮的槽位坐标：
 * <pre>
 *   slotX(i) = GRID_X + (i % COLS) * (TAB_W + GAP_X)
 *   slotY(i) = GRID_Y + (i / COLS) * (TAB_H + GAP_Y)
 * </pre>
 * 好处：<br>
 * ① 永远不会出现"像素级偏移"或按钮互相压住（网格天然不重叠）；<br>
 * ② 分辨率 / GUI 缩放 / 面板布局变化都不影响；<br>
 * ③ 存档里只有一小串 id，加新页签不会让老存档错位。
 */
public final class SidebarLayout {
    /** 客户端最多接受这么多个页签 id（防御性上限，正常只有 6~8 个）。 */
    private static final int MAX_ORDER = 32;

    /** 页签顺序（空 = 默认顺序）。 */
    private final List<String> order = new ArrayList<>();

    /**
     * 被管理员**移除**的模块 id。
     * <p>
     * 单独存一份而不是只靠 order：这样「以后新增的模块」依然会自动出现在侧栏里
     * （order 里没有、hidden 里也没有 → 视为新模块，自动补上），只有被显式移除过的才不显示。
     */
    private final List<String> hidden = new ArrayList<>();

    /**
     * 模块**自定义名称**（id → 名字），管理员在编辑模式里右键模块改名时写入。
     * <p>
     * 只存「改过的那些」：没改过的模块用 lang 里的默认翻译名。
     * 名字为空 = 恢复默认（直接从 map 里删掉）。
     */
    private final Map<String, String> names = new LinkedHashMap<>();

    /** 自定义名称的最大长度（侧栏一行放得下；再长会省略号截断）。 */
    public static final int MAX_NAME = 12;

    public List<String> order() {
        return this.order;
    }

    public List<String> hidden() {
        return this.hidden;
    }

    public Map<String, String> names() {
        return this.names;
    }

    /** 某个模块的自定义名；没改过返回 {@code null}。 */
    public String nameOf(String id) {
        if (id == null) {
            return null;
        }
        String name = this.names.get(id);
        return name == null || name.isBlank() ? null : name;
    }

    /** 设置自定义名；{@code null} / 空白 = 恢复默认。返回是否真的改了。 */
    public boolean setName(String id, String name) {
        if (id == null || id.isBlank()) {
            return false;
        }
        String clean = sanitize(name);
        if (clean.isEmpty()) {
            return this.names.remove(id.trim()) != null;
        }
        String previous = this.names.put(id.trim(), clean);
        return !clean.equals(previous);
    }

    /** 去掉 § 格式符 / 控制字符，压缩空白（**不截断**，服务端用它判断是否超长）。 */
    public static String sanitizeFull(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\u00a7') {
                i++;   // 跳过 § 后面的格式字符
                continue;
            }
            if (c < 0x20) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim().replaceAll("\\s{2,}", " ");
    }

    /** 去掉 § 格式符 / 控制字符，压缩空白，并截断到 {@link #MAX_NAME}。 */
    public static String sanitize(String raw) {
        String text = sanitizeFull(raw);
        return text.length() > MAX_NAME ? text.substring(0, MAX_NAME) : text;
    }

    public boolean isEmpty() {
        return this.order.isEmpty() && this.hidden.isEmpty() && this.names.isEmpty();
    }

    public void clear() {
        this.order.clear();
        this.hidden.clear();
        this.names.clear();
    }

    /** 用给定顺序整体替换（去重、去空、截断，服务端也会再过滤一次未知 id）。 */
    public void setOrder(List<String> ids) {
        this.order.clear();
        if (ids == null) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            seen.add(id.trim());
            if (seen.size() >= MAX_ORDER) {
                break;
            }
        }
        this.order.addAll(seen);
    }

    /** 设置被移除的模块（去重、去掉仍在 order 里的 id）。 */
    public void setHidden(List<String> ids) {
        this.hidden.clear();
        if (ids == null) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            String clean = id.trim();
            if (this.order.contains(clean)) {
                continue;
            }
            seen.add(clean);
            if (seen.size() >= MAX_ORDER) {
                break;
            }
        }
        this.hidden.addAll(seen);
    }

    /** 存档 / 快照用的 NBT：{@code {order:[...], hidden:[...], names:{id: name}}}。 */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (String id : this.order) {
            list.add(StringTag.valueOf(id));
        }
        tag.put("order", list);
        ListTag hiddenList = new ListTag();
        for (String id : this.hidden) {
            hiddenList.add(StringTag.valueOf(id));
        }
        tag.put("hidden", hiddenList);
        CompoundTag nameTag = new CompoundTag();
        for (Map.Entry<String, String> entry : this.names.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isBlank()) {
                nameTag.putString(entry.getKey(), entry.getValue());
            }
        }
        tag.put("names", nameTag);
        return tag;
    }

    public static SidebarLayout load(CompoundTag tag) {
        SidebarLayout layout = new SidebarLayout();
        if (tag == null) {
            return layout;
        }
        ListTag list = tag.getList("order", 8);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            ids.add(list.getString(i));
        }
        layout.setOrder(ids);
        ListTag hiddenList = tag.getList("hidden", 8);
        List<String> hiddenIds = new ArrayList<>();
        for (int i = 0; i < hiddenList.size(); i++) {
            hiddenIds.add(hiddenList.getString(i));
        }
        layout.setHidden(hiddenIds);
        CompoundTag nameTag = tag.getCompound("names");
        for (String id : nameTag.getAllKeys()) {
            layout.setName(id, nameTag.getString(id));
        }
        return layout;
    }
}
