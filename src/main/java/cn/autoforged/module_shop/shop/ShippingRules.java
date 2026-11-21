package cn.autoforged.module_shop.shop;

import cn.autoforged.module_shop.Config;
import cn.autoforged.module_shop.data.PriceSpec;
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

    /** 一条规则：原始文本 / 行号 / 匹配方式 / 匹配值 / 单价 / 双独立账本定价规格 / 每日份数上限（0 = 不限）。 */
    public record Rule(String raw, int line, Kind kind, String value, long price, PriceSpec priceSpec, int dailyMax) {
        public Rule(String raw, int line, Kind kind, String value, long price, int dailyMax) {
            this(raw, line, kind, value, price, PriceSpec.star(price), dailyMax);
        }

        public PriceSpec resolvePriceSpec() {
            if (priceSpec == null) {
                return PriceSpec.star(price);
            }
            return priceSpec;
        }

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
            PriceSpec priceSpec = PriceSpec.ZERO;
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
                    PriceSpec parsedSpec = PriceSpec.parseRuleText(priceText.trim());
                    if (parsedSpec == null) {
                        problem = "价格格式错误（可写 100 / phys:档位*数量 / 100|phys:档位*数量 / 100+phys:档位*数量，可附 x64）";
                    } else {
                        priceSpec = parsedSpec;
                    }
                } catch (NumberFormatException ex) {
                    problem = "每日份数必须是整数（如 x64）";
                }
            }
            if (problem == null && !priceSpec.isValid()) {
                problem = "价格必须大于 0 且实体货币档位有效";
            }
            long price = priceSpec.supportsStar() ? priceSpec.starPrice : 0L;
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
                if (id == null && kind != Kind.NAMESPACE) {
                    problem = "物品 ID 格式不对（应为 命名空间:路径）";
                } else if (kind == Kind.NAMESPACE) {
                    if (value.isEmpty() || ResourceLocation.tryParse(value + ":air") == null) {
                        problem = "命名空间格式不对：" + value;
                    } else if (!"minecraft".equals(value)
                            && net.neoforged.fml.ModList.get() != null
                            && !net.neoforged.fml.ModList.get().isLoaded(value)) {
                        // 软联动支持：对应模组未安装时静默忽略该行，保留配置原文且不报红字错误
                        continue;
                    }
                } else if (kind == Kind.ITEM && !BuiltInRegistries.ITEM.containsKey(id)) {
                    if (!"minecraft".equals(id.getNamespace())
                            && net.neoforged.fml.ModList.get() != null
                            && !net.neoforged.fml.ModList.get().isLoaded(id.getNamespace())) {
                        // 软联动支持：第三方可选模组未安装时静默跳过，安装后自动激活
                        continue;
                    }
                    problem = "找不到这个物品：" + value;
                }
            }
            if (problem != null) {
                String text = problem(lineNo, line, problem);
                problems.add(text);
                all.add(new Entry(lineNo, line, null, text));
                continue;
            }
            rule = new Rule(line, lineNo, kind, value, price, priceSpec, Math.max(0, dailyMax));
            parsed.add(rule);
            all.add(new Entry(lineNo, line, rule, null));
        }
        return new Parsed(List.copyOf(parsed), List.copyOf(all), List.copyOf(problems));
    }

    /** 找出价格与每日份数之间的 'x'（支持 "100 x64" / "100x64" 与 "phys:...*3 x64"，不误伤物品 ID 里的 x）。 */
    private static int indexOfX(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        int end = text.length() - 1;
        while (end >= 0 && Character.isWhitespace(text.charAt(end))) {
            end--;
        }
        int digitEnd = end;
        while (end >= 0 && Character.isDigit(text.charAt(end))) {
            end--;
        }
        if (end == digitEnd) {
            return -1;
        }
        while (end >= 0 && Character.isWhitespace(text.charAt(end))) {
            end--;
        }
        if (end <= 0) {
            return -1;
        }
        char xChar = text.charAt(end);
        if (xChar != 'x' && xChar != 'X') {
            return -1;
        }
        int prev = end - 1;
        boolean hasSpaceBeforeX = Character.isWhitespace(text.charAt(prev));
        while (prev >= 0 && Character.isWhitespace(text.charAt(prev))) {
            prev--;
        }
        if (prev < 0 || !Character.isDigit(text.charAt(prev))) {
            return -1;
        }
        if (!hasSpaceBeforeX && text.toLowerCase(java.util.Locale.ROOT).contains("phys:")) {
            return -1;
        }
        return end;
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
        Rule r = match(stack);
        if (r == null) {
            return false;
        }
        if (r.kind() == Kind.ITEM && r.resolvePriceSpec().supportsPhys()) {
            return true;
        }
        return !cn.autoforged.module_shop.shop.currency.CurrencySettlement.isBlockingBuybackAndShipping(stack);
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
        return updatePrice(index, PriceSpec.star(price));
    }

    public static synchronized boolean updatePrice(int index, PriceSpec spec) {
        List<Entry> list = entries();
        if (index < 0 || index >= list.size() || spec == null || !spec.isValid()) {
            return false;
        }
        Entry entry = list.get(index);
        if (entry.rule() == null) {
            return false;
        }
        return updateRule(index, spec, entry.rule().dailyMax());
    }

    /** 修改第 index 条规则的单价与每日上限（{@code dailyMax <= 0} 表示无限）。 */
    public static synchronized boolean updateRule(int index, PriceSpec spec, int dailyMax) {
        List<Entry> list = entries();
        if (index < 0 || index >= list.size() || spec == null || !spec.isValid()) {
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
        int perDay = Math.max(0, dailyMax);
        current.set(rawIndex, left + "=" + spec.toRuleText() + (perDay > 0 ? " x" + perDay : ""));
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
        return addRule(stack, PriceSpec.star(price));
    }

    public static synchronized boolean addRule(ItemStack stack, PriceSpec spec) {
        return addRule(stack, spec, 0);
    }

    public static synchronized boolean addRule(ItemStack stack, PriceSpec spec, int dailyMax) {
        if (stack == null || stack.isEmpty() || spec == null || !spec.isValid()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        int perDay = Math.max(0, dailyMax);
        List<String> current = new ArrayList<>(rawLines());
        current.add(id + "=" + spec.toRuleText() + (perDay > 0 ? " x" + perDay : ""));
        save(current);
        return true;
    }

    /** 批量追加规则（管理页批量添加：多个物品 + 同一个单价）——只落盘一次。 */
    public static synchronized int addRules(List<ItemStack> stacks, long price) {
        return addRules(stacks, PriceSpec.star(price));
    }

    public static synchronized int addRules(List<ItemStack> stacks, PriceSpec spec) {
        return addRules(stacks, spec, 0);
    }

    public static synchronized int addRules(List<ItemStack> stacks, PriceSpec spec, int dailyMax) {
        if (stacks == null || stacks.isEmpty() || spec == null || !spec.isValid()) {
            return 0;
        }
        List<String> current = new ArrayList<>(rawLines());
        int perDay = Math.max(0, dailyMax);
        String suffix = perDay > 0 ? " x" + perDay : "";
        String ruleVal = spec.toRuleText() + suffix;
        int added = 0;
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null) {
                continue;
            }
            current.add(id + "=" + ruleVal);
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
