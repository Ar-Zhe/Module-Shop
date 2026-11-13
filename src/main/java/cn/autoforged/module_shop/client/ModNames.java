package cn.autoforged.module_shop.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Collator;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/**
 * 客户端模组显示名自动翻译与中文名包加载器。
 *
 * <p>用于购物城左侧模组页签、列表「模组」列、出货箱分类筛选、管理员导入模组列表与搜索过滤：
 * <ul>
 *     <li>中文客户端（{@code zh_*}）下自动加载内置热门模组中文名包
 *         （{@code assets/module_shop/mod_names_zh_cn.json}）；</li>
 *     <li>同时支持资源包扩展（任意命名空间下的 {@code mod_names_zh_cn.json} /
 *         {@code mod_names/zh_cn.json} 或语言键 {@code gui.module_shop.mod_name.<modid>}、
 *         {@code modmenu.nameTranslation.<modid>}）；</li>
 *     <li>并支持从配置目录（{@code config/moduleshop/mod_names_zh_cn.json}、
 *         {@code config/module_shop/mod_names_zh_cn.json}、
 *         {@code config/module_shop_eco/mod_names_zh_cn.json}）自动加载自定义覆盖，无需改代码。</li>
 * </ul>
 */
public final class ModNames {
    private static final System.Logger LOG = System.getLogger("module_shop/mod_names");
    private static final String BUNDLED_PATH = "/assets/module_shop/mod_names_zh_cn.json";
    private static final Collator ZH_COLLATOR = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);

    private static volatile boolean loaded;
    private static volatile int generation;
    private static volatile Map<String, String> packZhNames = Map.of();
    private static volatile Map<String, String> externalZhNames = Map.of();

    private ModNames() {
    }

    /** 显示用模组名：中文客户端优先返回自动翻译/中文名包结果，拿不到退回原显示名或命名空间。 */
    public static String displayName(String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            return "";
        }
        ensureLoaded();
        boolean chinese = isChineseClient();
        return resolve(namespace, chinese, externalZhNames, packZhNames,
                ModNames::rawDisplayName, ModNames::langLookup);
    }

    /** 原始英文显示名（从 NeoForge {@link ModList} 读取，拿不到退回命名空间）。 */
    public static String rawDisplayName(String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            return "";
        }
        try {
            ModList list = ModList.get();
            if (list != null) {
                return list.getModContainerById(namespace)
                        .map(c -> c.getModInfo().getDisplayName())
                        .orElse(namespace);
            }
        } catch (Throwable ignored) {
        }
        return namespace;
    }

    /** 搜索匹配：同时匹配命名空间、翻译后的显示名、以及原始英文显示名。 */
    public static boolean matchesQuery(String namespace, String lowerQuery) {
        if (lowerQuery == null || lowerQuery.isEmpty()) {
            return true;
        }
        if (namespace == null || namespace.isEmpty()) {
            return false;
        }
        if (namespace.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            return true;
        }
        if (displayName(namespace).toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            return true;
        }
        String raw = rawDisplayName(namespace).toLowerCase(Locale.ROOT);
        return raw.contains(lowerQuery);
    }

    /** 按当前语言自然排序用的比较器（中文按拼音、英文忽略大小写）。 */
    public static Comparator<String> namespaceComparator() {
        if (isChineseClient()) {
            return (a, b) -> {
                String na = displayName(a);
                String nb = displayName(b);
                int cmp = ZH_COLLATOR.compare(na, nb);
                return cmp != 0 ? cmp : na.compareToIgnoreCase(nb);
            };
        }
        return Comparator.comparing(ns -> displayName(ns).toLowerCase(Locale.ROOT));
    }

    /** 缓存失效令牌：切语言或重载资源/配置后自动变化。 */
    public static String cacheToken() {
        ensureLoaded();
        return currentLanguageCode() + "#" + generation;
    }

    /** 资源重载（含游戏启动资源加载、F3+T、切换语言/资源包）时自动刷新中文名包。 */
    public static synchronized void reload(ResourceManager manager) {
        Map<String, String> pack = new HashMap<>();
        loadFromClasspath(pack);
        if (manager != null) {
            loadFromResourceManager(manager, pack);
        }
        Map<String, String> ext = loadExternalOverrides(currentLanguageCode());
        packZhNames = Map.copyOf(pack);
        externalZhNames = Map.copyOf(ext);
        loaded = true;
        generation++;
    }

    /** 无 ResourceManager 的懒加载兜底（含测试环境或界面早期初始化）。 */
    public static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (ModNames.class) {
            if (loaded) {
                return;
            }
            Map<String, String> pack = new HashMap<>();
            loadFromClasspath(pack);
            Map<String, String> ext = loadExternalOverrides(currentLanguageCode());
            packZhNames = Map.copyOf(pack);
            externalZhNames = Map.copyOf(ext);
            loaded = true;
            generation++;
        }
    }

    /** 纯函数解析逻辑，便于单元测试直接校验优先级与回退策略。 */
    static String resolve(String namespace, boolean chinese,
                          Map<String, String> externalMap,
                          Map<String, String> packMap,
                          Function<String, String> rawLookup,
                          Function<String, String> i18nLookup) {
        if (namespace == null || namespace.isEmpty()) {
            return "";
        }
        String key = namespace.trim().toLowerCase(Locale.ROOT);
        if (chinese) {
            String ext = externalMap.get(key);
            if (nonBlank(ext)) {
                return ext;
            }
            String explicitLang = i18nLookup.apply("gui.module_shop.mod_name." + key);
            if (nonBlank(explicitLang)) {
                return explicitLang;
            }
            String modMenuLang = i18nLookup.apply("modmenu.nameTranslation." + key);
            if (nonBlank(modMenuLang) && containsCjk(modMenuLang)) {
                return modMenuLang;
            }
            String bundled = packMap.get(key);
            if (nonBlank(bundled)) {
                return bundled;
            }
            String raw = rawLookup.apply(namespace);
            if (nonBlank(raw) && !raw.equals(namespace)) {
                return raw;
            }
            if (nonBlank(modMenuLang)) {
                return modMenuLang;
            }
            return namespace;
        }
        String explicitLang = i18nLookup.apply("gui.module_shop.mod_name." + key);
        if (nonBlank(explicitLang)) {
            return explicitLang;
        }
        String modMenuLang = i18nLookup.apply("modmenu.nameTranslation." + key);
        if (nonBlank(modMenuLang)) {
            return modMenuLang;
        }
        String raw = rawLookup.apply(namespace);
        return nonBlank(raw) ? raw : namespace;
    }

    static boolean containsCjk(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String currentLanguageCode() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getLanguageManager() != null) {
                String selected = mc.getLanguageManager().getSelected();
                if (selected != null && !selected.isBlank()) {
                    return selected.toLowerCase(Locale.ROOT);
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static boolean isChineseClient() {
        String code = currentLanguageCode();
        if (!code.isEmpty()) {
            return code.startsWith("zh");
        }
        try {
            Language lang = Language.getInstance();
            if (lang != null && lang.has("tab.module_shop.mall")) {
                return containsCjk(lang.getOrDefault("tab.module_shop.mall"));
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String langLookup(String key) {
        try {
            Language lang = Language.getInstance();
            if (lang != null && lang.has(key)) {
                return lang.getOrDefault(key);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void loadFromClasspath(Map<String, String> out) {
        try (InputStream in = ModNames.class.getResourceAsStream(BUNDLED_PATH)) {
            if (in != null) {
                parseJsonMap(new InputStreamReader(in, StandardCharsets.UTF_8), out, "bundled");
            }
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to load bundled mod name pack " + BUNDLED_PATH, e);
        }
    }

    private static void loadFromResourceManager(ResourceManager manager, Map<String, String> out) {
        try {
            // 先加载本模组自身（含覆盖它的高优先级资源包），再加载其他命名空间，确保第三方汉化资源包可以覆盖内置译名
            readNamespacePack(manager, "module_shop", out);
            for (String ns : manager.getNamespaces()) {
                if (!"module_shop".equals(ns)) {
                    readNamespacePack(manager, ns, out);
                }
            }
            Map<ResourceLocation, List<Resource>> subdir = manager.listResourceStacks("mod_names",
                    loc -> "mod_names/zh_cn.json".equals(loc.getPath()));
            for (var entry : subdir.entrySet()) {
                if ("module_shop".equals(entry.getKey().getNamespace())) {
                    readResourceStack(entry.getValue(), out, entry.getKey().toString());
                }
            }
            for (var entry : subdir.entrySet()) {
                if (!"module_shop".equals(entry.getKey().getNamespace())) {
                    readResourceStack(entry.getValue(), out, entry.getKey().toString());
                }
            }
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to scan resource packs for mod_names_zh_cn.json", e);
        }
    }

    private static void readNamespacePack(ResourceManager manager, String namespace, Map<String, String> out) {
        ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(namespace, "mod_names_zh_cn.json");
        List<Resource> stack = manager.getResourceStack(loc);
        if (!stack.isEmpty()) {
            readResourceStack(stack, out, loc.toString());
        }
    }

    private static void readResourceStack(List<Resource> resources, Map<String, String> out, String source) {
        for (Resource res : resources) {
            try (Reader reader = res.openAsReader()) {
                parseJsonMap(reader, out, source);
            } catch (Throwable e) {
                LOG.log(System.Logger.Level.WARNING, "Failed to read mod name pack resource " + source, e);
            }
        }
    }

    private static Map<String, String> loadExternalOverrides(String langCode) {
        Map<String, String> out = new HashMap<>();
        Path configDir;
        try {
            configDir = FMLPaths.CONFIGDIR.get();
        } catch (Throwable ignored) {
            return out;
        }
        if (configDir == null) {
            return out;
        }
        String[] dirs = {"moduleshop", "module_shop", "module_shop_eco"};
        for (String dir : dirs) {
            Path base = configDir.resolve(dir);
            loadExternalFile(base.resolve("mod_names_zh_cn.json"), out);
            loadExternalFile(base.resolve("mod_names.json"), out);
            if (langCode != null && !langCode.isEmpty() && !"zh_cn".equals(langCode)) {
                loadExternalFile(base.resolve("mod_names_" + langCode + ".json"), out);
            }
        }
        return out;
    }

    private static void loadExternalFile(Path file, Map<String, String> out) {
        try {
            if (Files.isRegularFile(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    parseJsonMap(reader, out, file.toString());
                }
            }
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to read external mod name pack " + file, e);
        }
    }

    static void parseJsonMap(Reader reader, Map<String, String> out, String source) {
        try {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[2048];
            int n;
            while ((n = reader.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
            new JsonMapScanner(sb.toString()).readObject(out);
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.WARNING, "Invalid mod name JSON in " + source, e);
        }
    }

    /** 轻量纯 JDK JSON 对象解析器，不依赖外部库以兼容最小化单测运行环境。 */
    private static final class JsonMapScanner {
        private final String src;
        private int pos;

        JsonMapScanner(String src) {
            this.src = src;
            if (!src.isEmpty() && src.charAt(0) == '\uFEFF') {
                this.pos = 1;
            }
        }

        void readObject(Map<String, String> out) throws IOException {
            skipWs();
            if (!consume('{')) {
                return;
            }
            skipWs();
            if (peek() == '}') {
                pos++;
                return;
            }
            while (pos < src.length()) {
                skipWs();
                String key = readString();
                skipWs();
                if (!consume(':')) {
                    throw new IOException("Expected ':' at " + pos);
                }
                skipWs();
                if (peek() == '"') {
                    String val = readString().trim();
                    if (!key.isBlank() && !key.startsWith("_") && !val.isEmpty()) {
                        out.put(key.trim().toLowerCase(Locale.ROOT), val);
                    }
                } else {
                    skipValue();
                }
                skipWs();
                if (consume(',')) {
                    continue;
                }
                if (consume('}')) {
                    return;
                }
                throw new IOException("Expected ',' or '}' at " + pos);
            }
        }

        private void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        private boolean consume(char expected) {
            if (peek() == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private String readString() throws IOException {
            if (!consume('"')) {
                throw new IOException("Expected '\"' at " + pos);
            }
            StringBuilder out = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (pos >= src.length()) {
                        throw new IOException("Unterminated escape");
                    }
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> out.append(esc);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (pos + 4 > src.length()) {
                                throw new IOException("Invalid unicode escape");
                            }
                            out.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IOException("Invalid escape \\" + esc);
                    }
                } else {
                    out.append(c);
                }
            }
            throw new IOException("Unterminated string");
        }

        private void skipValue() throws IOException {
            skipWs();
            char c = peek();
            if (c == '"') {
                readString();
            } else if (c == '{' || c == '[') {
                char open = c;
                char close = (c == '{') ? '}' : ']';
                pos++;
                int depth = 1;
                while (pos < src.length() && depth > 0) {
                    char ch = src.charAt(pos);
                    if (ch == '"') {
                        readString();
                    } else {
                        if (ch == open) depth++;
                        else if (ch == close) depth--;
                        pos++;
                    }
                }
            } else {
                while (pos < src.length()) {
                    char ch = src.charAt(pos);
                    if (ch == ',' || ch == '}' || ch == ']' || Character.isWhitespace(ch)) {
                        break;
                    }
                    pos++;
                }
            }
        }
    }
}
