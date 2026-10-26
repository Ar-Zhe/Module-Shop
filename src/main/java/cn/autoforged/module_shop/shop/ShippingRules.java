package cn.autoforged.module_shop.shop;

import cn.autoforged.module_shop.Config;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;

/**
 * 出货箱的「兑换规则」：解析配置文件里的文本规则、缓存、匹配物品，并支持游戏内编辑。
 *
 * <h2>格式</h2>
 * <pre>
 *   minecraft:wheat=4            物品 = 单价
 *   #minecraft:logs=3            物品标签 = 单价（标签里所有物品都按这个价收）
 *   minecraft:*=2                整个模组 = 单价
 *   minecraft:diamond=100 x64    单价 100，且**每天最多结算 64 个**（可手写在配置里，界面不暴露）
 *   // 注释行                     以 // 开头整行忽略
 * </pre>
 *
 * <p>写错的行会被记进 {@link #entries()}（带行号与原因），管理页把这些条目**标红**显示，
 * 右键可以直接删掉；这些规则不参与匹配（对应物品放不进出货箱）。
 *
 * <p>游戏内编辑（管理页）会同时：① 更新运行时列表（立刻生效）② 写回配置文件
 * （优先用 NeoForge 的 {@code ConfigValue.set + SPEC.save}，失败时**直接改写 toml 文件**兜底）。
 */
public final class ShippingRules {
    /** 匹配方式。 */
    public enum Kind { ITEM, NAMESPACE, TAG }

    /** 一条规则：原始文本 / 行号 / 匹配方式 / 匹配值 / 单价 / 每日份数上限（0 = 不限）。 */
    public record Rule(String raw, int line, Kind kind, String value, long price, int dailyMax) {
        public boolean matches(ItemStack stack) {
            if (stack == null || stack.isEmpty()) {
                return false;
            }
            return switch (kind) {
                case ITEM -> {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    yield id != null && id.toString().equals(value);
                }
                case NAMESPACE -> {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    yield id != null && id.getNamespace().equals(value);
                }
                case TAG -> stack.is(TagKey.create(Registries.ITEM, ResourceLocation.parse(value)));
            };
        }
    }

    /** 管理页展示用的一行：正常规则（{@code rule != null}）或格式错误的一行（{@code error != null}）。 */
    public record Entry(int line, String raw, Rule rule, String error) {
    }

    /** 解析结果。 */
    public record Parsed(List<Rule> rules, List<Entry> entries, List<String> errors) {
    }

    private static List<Rule> rules = List.of();
    private static List<Entry> entries = List.of();
    private static List<String> errors = List.of();
    /** 运行时规则原文（首次从配置读取；管理页改过之后以它为准，避免配置文件被外部改动时"改一下又跳回去"）。 */
    private static List<String> lines;
    private static List<ItemStack> ruleSnapshot = List.of();

    private ShippingRules() {
    }

    /** 当前生效的规则原文（运行时列表；第一次调用时从配置初始化）。 */
    public static synchronized List<String> rawLines() {
        if (lines == null) {
            List<String> copy = new ArrayList<>();
            for (String line : Config.INSTANCE.shippingRules.get()) {
                copy.add(line == null ? "" : line);
            }
            lines = copy;
        }
        return lines;
    }

    /** 强制重新解析（编辑之后调用）。 */
    public static synchronized void refresh() {
        Parsed parsed = parse(rawLines());
        rules = parsed.rules();
        entries = parsed.entries();
        errors = parsed.errors();
        ruleSnapshot = List.of();
    }

    private static void ensureParsed() {
        if (rules.isEmpty() && entries.isEmpty() && errors.isEmpty()) {
            refresh();
        }
    }

    public static List<Rule> rules() {
        synchronized (ShippingRules.class) {
            ensureParsed();
            return rules;
        }
    }

    public static List<Entry> entries() {
        synchronized (ShippingRules.class) {
            ensureParsed();
            return entries;
        }
    }

    public static List<String> errors() {
        synchronized (ShippingRules.class) {
            ensureParsed();
            return errors;
        }
    }

    /**
     * 纯解析（不碰配置、不带缓存）：服务端解析规则原文，客户端解析服务端下发的那份，两边语义一致。
     */
    public static Parsed parse(List<? extends String> raw) {
        List<Rule> parsed = new ArrayList<>();
        List<Entry> all = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String line = raw.get(i) == null ? "" : raw.get(i).trim();
            int lineNo = i + 1;
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            String problem = null;
            Rule rule = null;
            int eq = line.indexOf('=');
            if (eq <= 0 || eq >= line.length() - 1) {
                problem = "缺少 = 或价格为空";
            }
            String left = eq > 0 ? line.substring(0, eq).trim() : "";
            String right = eq > 0 ? line.substring(eq + 1).trim() : "";
            long price = 0L;
            int dailyMax = 0;
            if (problem == null) {
                try {
                    String priceText = right;
                    int x = indexOfX(right);
                    if (x >= 0) {
                        priceText = right.substring(0, x).trim();
                        String maxText = right.substring(x + 1).trim().replace(" ", "");
                        dailyMax = maxText.isEmpty() ? 0 : Integer.parseInt(maxText);
                    }
                    price = Long.parseLong(priceText.trim());
                } catch (NumberFormatException ex) {
                    problem = "价格必须是整数（可写 100 或 100 x64）";
                }
            }
            if (problem == null && price <= 0L) {
                problem = "价格必须大于 0";
            }
            Kind kind = null;
            String value = null;
            if (problem == null) {
                if (left.startsWith("#")) {
                    kind = Kind.TAG;
                    value = left.substring(1).trim();
                } else if (left.endsWith(":*")) {
                    kind = Kind.NAMESPACE;
                    value = left.substring(0, left.length() - 2).trim();
                } else {
                    kind = Kind.ITEM;
                    value = left;
                }
                ResourceLocation id = ResourceLocation.tryParse(value);
                if (id == null) {
                    problem = "物品 ID 格式不对（应为 命名空间:路径）";
                } else if (kind == Kind.ITEM && !BuiltInRegistries.ITEM.containsKey(id)) {
                    problem = "找不到这个物品：" + value;
                }
            }
            if (problem != null) {
                String text = problem(lineNo, line, problem);
                problems.add(text);
                all.add(new Entry(lineNo, line, null, text));
                continue;
            }
            rule = new Rule(line, lineNo, kind, value, price, Math.max(0, dailyMax));
            parsed.add(rule);
            all.add(new Entry(lineNo, line, rule, null));
        }
        return new Parsed(List.copyOf(parsed), List.copyOf(all), List.copyOf(problems));
    }

    /** 找出价格与每日份数之间的 'x'（支持 "100 x64" / "100x64"，也兼容大小写 X）。 */
    private static int indexOfX(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == 'x' || c == 'X') && i > 0 && Character.isDigit(text.charAt(i - 1))) {
                return i;
            }
        }
        return -1;
    }

    private static String problem(int lineNo, String line, String reason) {
        String shown = line.length() > 40 ? line.substring(0, 40) + "…" : line;
        return "第 " + lineNo + " 行「" + shown + "」：" + reason;
    }

    /** 物品命中的规则（取**最具体**的那条；找不到返回 null = 不可出售）。 */
    public static Rule match(ItemStack stack) {
        return match(rules(), stack);
    }

    /** 规则的具体程度：物品 &gt; 整模组 &gt; 标签（越具体越优先）。 */
    private static int specificity(Rule rule) {
        return switch (rule.kind()) {
            case ITEM -> 3;
            case NAMESPACE -> 2;
            case TAG -> 1;
        };
    }

    /**
     * 在指定的规则列表里找**最具体**的命中规则（客户端用服务端下发的那份规则做本地判断）。
     *
     * <p>平级时写在前面的优先。为什么不是「第一条命中的赢」：管理员在管理页「添加条目」加一条
     * {@code minecraft:oak_log=6} 时，它是**追加到列表末尾**的，而默认规则里早就有 {@code #minecraft:logs=3} ——
     * 按"第一条"算的话新加的价永远不生效（橡木还是按标签价 3 收，玩家会以为"加了条目却连别的木头一起收"）。
     * 改成"具体的赢"之后：橡木走 6，其它原木仍走标签价 3，符合"我特意给这一项单独定价"的直觉。
     */
    public static Rule match(List<Rule> list, ItemStack stack) {
        if (list == null || stack == null || stack.isEmpty()) {
            return null;
        }
        Rule best = null;
        int bestScore = 0;
        for (Rule rule : list) {
            if (!rule.matches(stack)) {
                continue;
            }
            int score = specificity(rule);
            if (best == null || score > bestScore) {
                best = rule;
                bestScore = score;
                if (score >= 3) {
                    break;   // 物品规则已经是最具体的了，不用再找
                }
            }
        }
        return best;
    }

    public static boolean canSell(ItemStack stack) {
        return match(stack) != null;
    }

    // =========================================================== 游戏内编辑（管理页）

    /** 每行规则的代表物品（标签 / 整模组取第一件匹配的物品；格式错误的行为空）。 */
    public static synchronized List<ItemStack> ruleStacks() {
        List<Entry> list = entries();
        if (ruleSnapshot.size() == list.size()) {
            return ruleSnapshot;
        }
        List<ItemStack> stacks = new ArrayList<>();
        for (Entry entry : list) {
            ItemStack found = ItemStack.EMPTY;
            if (entry.rule() != null) {
                for (Item item : BuiltInRegistries.ITEM) {
                    ItemStack stack = new ItemStack(item);
                    if (!stack.isEmpty() && entry.rule().matches(stack)) {
                        found = stack;
                        break;
                    }
                }
            }
            stacks.add(found);
        }
        ruleSnapshot = stacks;
        return ruleSnapshot;
    }

    /** 修改第 index 条（<b>按 {@link #entries()} 的下标</b>）的单价；格式错误的行为不可编辑。 */
    public static synchronized boolean updatePrice(int index, long price) {
        List<Entry> list = entries();
        if (index < 0 || index >= list.size() || price <= 0L) {
            return false;
        }
        Entry entry = list.get(index);
        if (entry.rule() == null) {
            return false;
        }
        List<String> current = new ArrayList<>(rawLines());
        int rawIndex = entry.line() - 1;
        if (rawIndex < 0 || rawIndex >= current.size()) {
            return false;
        }
        String original = current.get(rawIndex);
        int eq = original.indexOf('=');
        String left = eq > 0 ? original.substring(0, eq).trim() : original.trim();
        // 手写在配置里的 "x每日份数" 保持不变
        int perDay = entry.rule().dailyMax();
        current.set(rawIndex, left + "=" + price + (perDay > 0 ? " x" + perDay : ""));
        save(current);
        return true;
    }

    /** 删除第 index 条（按 {@link #entries()} 的下标；格式错误的行也能删）。 */
    public static synchronized boolean removeEntry(int index) {
        List<Entry> list = entries();
        if (index < 0 || index >= list.size()) {
            return false;
        }
        List<String> current = new ArrayList<>(rawLines());
        int rawIndex = list.get(index).line() - 1;
        if (rawIndex < 0 || rawIndex >= current.size()) {
            return false;
        }
        current.remove(rawIndex);
        save(current);
        return true;
    }

    /** 追加一条规则（管理页幽灵槽添加：物品 + 单价）。 */
    public static synchronized boolean addRule(ItemStack stack, long price) {
        if (stack == null || stack.isEmpty() || price <= 0L) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        List<String> current = new ArrayList<>(rawLines());
        current.add(id + "=" + price);
        save(current);
        return true;
    }

    /** 批量追加规则（管理页批量添加：多个物品 + 同一个单价）——只落盘一次。 */
    public static synchronized int addRules(List<ItemStack> stacks, long price) {
        if (stacks == null || stacks.isEmpty() || price <= 0L) {
            return 0;
        }
        List<String> current = new ArrayList<>(rawLines());
        int added = 0;
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null) {
                continue;
            }
            current.add(id + "=" + price);
            added++;
        }
        if (added == 0) {
            return 0;
        }
        save(current);
        return added;
    }

    /** 保存规则：更新运行时列表 + 立刻生效 + 写回配置（API 失败时直接改写 toml）。 */
    private static synchronized void save(List<String> newLines) {
        lines = new ArrayList<>(newLines);
        refresh();
        boolean ok = false;
        try {
            Config.INSTANCE.shippingRules.set(List.copyOf(newLines));
            Config.SPEC.save();
            ok = true;
        } catch (Throwable ex) {
            org.slf4j.LoggerFactory.getLogger("module_shop")
                    .warn("[模块商店] 出货箱规则写回配置失败，改为直接改写配置文件：{}", ex.toString());
        }
        if (!writeToml(newLines)) {
            org.slf4j.LoggerFactory.getLogger("module_shop")
                    .warn("[模块商店] 出货箱规则未能写入配置文件（本次修改只在本局生效）");
        } else if (!ok) {
            org.slf4j.LoggerFactory.getLogger("module_shop")
                    .info("[模块商店] 出货箱规则已直接写入配置文件");
        }
    }

    /** 直接把配置文件的 {@code shippingRules = [...]} 行替换掉（NeoForge API 失败时的兜底，也顺手保证格式好看）。 */
    private static boolean writeToml(List<String> newLines) {
        try {
            Path path = FMLPaths.CONFIGDIR.get().resolve("module_shop-common.toml");
            if (!Files.exists(path)) {
                return false;
            }
            List<String> fileLines = Files.readAllLines(path, StandardCharsets.UTF_8);
            StringBuilder array = new StringBuilder("shippingRules = [");
            for (int i = 0; i < newLines.size(); i++) {
                if (i > 0) {
                    array.append(", ");
                }
                array.append('"').append(newLines.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
            array.append(']');
            boolean replaced = false;
            for (int i = 0; i < fileLines.size(); i++) {
                String trimmed = fileLines.get(i).trim();
                if (!trimmed.startsWith("shippingRules = ")) {
                    continue;
                }
                String indent = fileLines.get(i).substring(0, fileLines.get(i).indexOf("shippingRules"));
                fileLines.set(i, indent + array);
                // 如果原来的数组是多行的（以 [ 结尾但没有 ]），把后续行删掉
                int j = i + 1;
                if (!trimmed.endsWith("]")) {
                    while (j < fileLines.size() && !fileLines.get(j).trim().endsWith("]")) {
                        j++;
                    }
                    if (j < fileLines.size()) {
                        fileLines.remove(j);
                    }
                }
                replaced = true;
                break;
            }
            if (!replaced) {
                return false;
            }
            Files.write(path, String.join(System.lineSeparator(), fileLines).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
