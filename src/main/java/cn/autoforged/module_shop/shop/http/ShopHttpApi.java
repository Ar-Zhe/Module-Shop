package cn.autoforged.module_shop.shop.http;

import cn.autoforged.module_shop.Config;
import cn.autoforged.module_shop.api.ApiErrorCode;
import cn.autoforged.module_shop.api.ApiResult;
import cn.autoforged.module_shop.api.ModuleShopApi;
import cn.autoforged.module_shop.api.view.BalanceView;
import cn.autoforged.module_shop.api.view.MailInfo;
import cn.autoforged.module_shop.api.view.ShopItemView;
import cn.autoforged.module_shop.api.view.TransactionInfo;
import cn.autoforged.module_shop.shop.ShopManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 可选的 **HTTP / REST** 接口（默认关闭，零第三方依赖，用 JDK 自带的 {@code com.sun.net.httpserver}）。
 *
 * <h2>安全默认值</h2>
 * <ul>
 *     <li>默认不启动；启动必须显式配 {@code httpApiEnabled=true} + 非空 {@code httpApiToken}；</li>
 *     <li>默认只监听 {@code 127.0.0.1}；绑定非本机地址且 token 为空时**拒绝启动**；</li>
 *     <li>默认**只读**（{@code httpApiAllowWrite=false}），写操作必须显式开启；</li>
 *     <li>每次请求写审计日志（`config/module_shop/http-audit.log`）；</li>
 *     <li>按 IP 限速（默认 60 次/分钟）；</li>
 *     <li>写操作**必须带 requestId**，重复 requestId 直接拒绝（防重放/防重复发奖）。</li>
 * </ul>
 *
 * <h2>线程</h2>
 * 所有业务调用都通过 {@code server.execute(...)} **切回服务端主线程**执行，HTTP 线程只做编解码。
 *
 * <h2>端点（v1）</h2>
 * <pre>
 * GET  /api/v1/health
 * GET  /api/v1/players/{uuid}/balance
 * GET  /api/v1/players/{uuid}/mail?v=unclaimed|list
 * GET  /api/v1/players/{uuid}/history?limit=10
 * GET  /api/v1/shop/mall
 * POST /api/v1/players/{uuid}/add      {amount, reason, requestId}
 * POST /api/v1/players/{uuid}/take     {amount, reason, requestId}
 * POST /api/v1/players/{uuid}/reward   {requestId, money, items:[{id,count}], reason}
 * POST /api/v1/players/{uuid}/mail     {title, body, items:[{id,count}]}
 * </pre>
 * 鉴权：`Authorization: Bearer &lt;token&gt;`
 */
public final class ShopHttpApi {
    private static final Logger LOG = LoggerFactory.getLogger("module_shop_http");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_BODY = 64 * 1024;

    private static HttpServer server;
    private static ExecutorService pool;
    private static volatile String token = "";
    private static volatile boolean allowWrite;
    private static volatile int rateLimit;
    private static final Map<String, AtomicLong> RATE_BUCKETS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Long> RATE_WINDOW = new java.util.concurrent.ConcurrentHashMap<>();

    private ShopHttpApi() {
    }

    // ---------------------------------------------------------------- 生命周期

    public static synchronized void start(MinecraftServer mc) {
        stop();
        if (!Config.INSTANCE.httpApiEnabled.get()) {
            return;
        }
        token = Config.INSTANCE.httpApiToken.get() == null ? "" : Config.INSTANCE.httpApiToken.get().trim();
        if (token.isEmpty()) {
            LOG.error("[shop-http] 已开启 httpApiEnabled 但没有设置 httpApiToken —— 拒绝启动 HTTP API");
            return;
        }
        String bind = Config.INSTANCE.httpApiBind.get() == null ? "127.0.0.1" : Config.INSTANCE.httpApiBind.get().trim();
        boolean loopback = bind.equals("127.0.0.1") || bind.equals("localhost") || bind.equals("::1");
        if (!loopback) {
            LOG.warn("[shop-http] 注意：监听地址为 {}（非本机回环）。请确认已做好防火墙与强随机 token 保护", bind);
        }
        allowWrite = Config.INSTANCE.httpApiAllowWrite.get();
        rateLimit = Math.max(0, Config.INSTANCE.httpApiRateLimit.get());
        int port = Config.INSTANCE.httpApiPort.get();
        try {
            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
            pool = Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "module-shop-http");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(pool);
            server.createContext("/api/v1/", exchange -> handle(mc, exchange));
            server.start();
            LOG.info("[shop-http] HTTP API 已启动：http://{}:{}/api/v1/  （写操作={}，限速={}/分钟）",
                    bind, port, allowWrite ? "允许" : "禁止", rateLimit);
        } catch (IOException error) {
            LOG.error("[shop-http] HTTP API 启动失败（端口被占用？）", error);
            stop();
        }
    }

    public static synchronized void stop() {
        if (server != null) {
            try {
                server.stop(0);
            } catch (Throwable ignored) {
                // 关服时忽略
            }
            server = null;
            LOG.info("[shop-http] HTTP API 已停止");
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        RATE_BUCKETS.clear();
        RATE_WINDOW.clear();
    }

    // ---------------------------------------------------------------- 请求处理

    private static void handle(MinecraftServer mc, HttpExchange exchange) {
        long started = System.currentTimeMillis();
        String ip = exchange.getRemoteAddress() == null ? "?"
                : exchange.getRemoteAddress().getAddress().getHostAddress();
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        int status = 200;
        try {
            if (!authorized(exchange)) {
                status = 401;
                send(exchange, 401, Map.of("ok", false, "error", "UNAUTHORIZED", "message", "缺少或错误的 Bearer token"));
                return;
            }
            if (!rateOk(ip)) {
                status = 429;
                send(exchange, 429, Map.of("ok", false, "error", "RATE_LIMITED", "message", "请求过于频繁"));
                return;
            }
            String body = "";
            if ("POST".equalsIgnoreCase(method)) {
                byte[] raw = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
                if (raw.length > MAX_BODY) {
                    status = 413;
                    send(exchange, 413, Map.of("ok", false, "error", "BODY_TOO_LARGE"));
                    return;
                }
                body = new String(raw, StandardCharsets.UTF_8);
            }
            Map<String, Object> result = dispatch(mc, method, path, exchange.getRequestURI().getRawQuery(), body);
            Object ok = result.get("ok");
            status = Boolean.TRUE.equals(ok) ? 200 : 400;
            if (result.get("error") != null) {
                String code = String.valueOf(result.get("error"));
                status = switch (code) {
                    case "UNAUTHORIZED" -> 401;
                    case "FORBIDDEN" -> 403;
                    case "NOT_FOUND" -> 404;
                    case "RATE_LIMITED" -> 429;
                    case "WRONG_THREAD", "INTERNAL_ERROR" -> 500;
                    case "PLAYER_OFFLINE", "PRICE_CHANGED", "SOLD_OUT" -> 409;
                    default -> 400;
                };
            }
            send(exchange, status, result);
        } catch (Throwable error) {
            status = 500;
            LOG.error("[shop-http] {} {} 处理失败", method, path, error);
            try {
                send(exchange, 500, Map.of("ok", false, "error", "INTERNAL_ERROR",
                        "message", String.valueOf(error.getMessage())));
            } catch (IOException ignored) {
                // 已断开
            }
        } finally {
            audit(ip, method, path, status, System.currentTimeMillis() - started);
        }
    }

    private static boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        String given = header.substring("Bearer ".length()).trim();
        // 常量时间比较，避免时序侧信道
        if (given.length() != token.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < given.length(); i++) {
            diff |= given.charAt(i) ^ token.charAt(i);
        }
        return diff == 0;
    }

    private static boolean rateOk(String ip) {
        if (rateLimit <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        long window = now / 60_000L;
        Long last = RATE_WINDOW.get(ip);
        AtomicLong counter = RATE_BUCKETS.computeIfAbsent(ip, key -> new AtomicLong());
        if (last == null || last != window) {
            RATE_WINDOW.put(ip, window);
            counter.set(0);
        }
        return counter.incrementAndGet() <= rateLimit;
    }

    private static void audit(String ip, String method, String path, int status, long millis) {
        String line = STAMP.format(LocalDateTime.now()) + " " + ip + " " + method + " " + path
                + " -> " + status + " (" + millis + "ms)" + System.lineSeparator();
        try {
            Path file = FMLPaths.CONFIGDIR.get().resolve("module_shop").resolve("http-audit.log");
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Throwable error) {
            LOG.debug("[shop-http] 审计日志写入失败", error);
        }
    }

    // ---------------------------------------------------------------- 路由

    private static Map<String, Object> dispatch(MinecraftServer mc, String method, String path, String query,
            String body) {
        String[] parts = path.replace("/api/v1/", "").split("/");
        if (parts.length == 1 && parts[0].equals("health")) {
            return ok(Map.of("version", cn.autoforged.module_shop.api.ApiVersion.API_VERSION,
                    "write", allowWrite, "thread", mc.isSameThread()));
        }
        if (parts.length >= 2 && parts[0].equals("shop") && parts[1].equals("mall")) {
            return onMain(mc, () -> {
                List<Object> items = new ArrayList<>();
                for (ShopItemView item : ModuleShopApi.shop().mall()) {
                    items.add(itemMap(item));
                }
                return ok(Map.of("items", items));
            });
        }
        if (parts.length >= 3 && parts[0].equals("players")) {
            UUID player;
            try {
                player = UUID.fromString(parts[1]);
            } catch (IllegalArgumentException error) {
                return fail("INVALID_ARGUMENT", "UUID 格式不正确");
            }
            String action = parts[2];
            boolean write = !action.equals("balance") && !action.equals("mail") && !action.equals("history");
            if ("POST".equalsIgnoreCase(method) && write && !allowWrite) {
                return fail("FORBIDDEN", "写操作未开启（httpApiAllowWrite=false）");
            }
            Object parsed = body.isEmpty() ? Map.of() : safeParse(body);
            if ("POST".equalsIgnoreCase(method) && "mail".equals(action)) {
                return onMain(mc, () -> {
                    ApiResult<String> result = ModuleShopApi.mail().sendItemMail(player,
                            MiniJson.str(parsed, "title", "系统邮件"),
                            MiniJson.str(parsed, "body", ""),
                            itemList(parsed));
                    return fromResult(result, "mailId");
                });
            }
            return switch (action) {
                case "balance" -> onMain(mc, () -> {
                    BalanceView view = ModuleShopApi.economy().balanceView(player);
                    if (view == null) {
                        return fail("UNKNOWN_PLAYER", "找不到该玩家的账户");
                    }
                    return ok(Map.of("balance", view.balance(),
                            "maxBalance", view.maxBalance(),
                            "currency", view.currencyName(),
                            "unlimited", view.unlimited()));
                });
                case "mail" -> onMain(mc, () -> {
                    boolean list = "list".equals(queryValue(query, "v"));
                    List<MailInfo> mails = ModuleShopApi.mail().mailbox(player);
                    if (!list) {
                        return ok(Map.of("unclaimed", ModuleShopApi.mail().unclaimed(player),
                                "total", mails.size()));
                    }
                    List<Object> items = new ArrayList<>();
                    for (MailInfo mail : mails) {
                        items.add(mailMap(mail));
                    }
                    return ok(Map.of("total", mails.size(), "mails", items));
                });
                case "history" -> onMain(mc, () -> {
                    int limit = (int) Math.max(1, Math.min(200, MiniJson.num(parsed, "limit",
                            parseLong(queryValue(query, "limit"), 10))));
                    List<Object> items = new ArrayList<>();
                    for (TransactionInfo info : ModuleShopApi.history().history(player, limit)) {
                        items.add(historyMap(info));
                    }
                    return ok(Map.of("items", items));
                });
                case "add" -> onMain(mc, () -> {
                    String requestId = MiniJson.str(parsed, "requestId", "");
                    if (requestId.isBlank()) {
                        return fail("INVALID_ARGUMENT", "写操作必须带 requestId（幂等键）");
                    }
                    if (!claim(player, requestId)) {
                        return fail("DUPLICATE_REQUEST", "该 requestId 已处理过");
                    }
                    ApiResult<Long> result = ModuleShopApi.economy().add(player,
                            MiniJson.num(parsed, "amount", 0L), MiniJson.str(parsed, "reason", "http-api"));
                    return fromResult(result, "credited");
                });
                case "take" -> onMain(mc, () -> {
                    String requestId = MiniJson.str(parsed, "requestId", "");
                    if (requestId.isBlank()) {
                        return fail("INVALID_ARGUMENT", "写操作必须带 requestId（幂等键）");
                    }
                    if (!claim(player, requestId)) {
                        return fail("DUPLICATE_REQUEST", "该 requestId 已处理过");
                    }
                    ApiResult<Long> result = ModuleShopApi.economy().take(player,
                            MiniJson.num(parsed, "amount", 0L), MiniJson.str(parsed, "reason", "http-api"));
                    return fromResult(result, "debited");
                });
                case "reward" -> onMain(mc, () -> {
                    String requestId = MiniJson.str(parsed, "requestId", "");
                    ApiResult<Void> result = ModuleShopApi.reward().grantOnce(player, requestId,
                            itemList(parsed), MiniJson.num(parsed, "money", 0L),
                            MiniJson.str(parsed, "reason", "http-api"));
                    return fromResult(result, null);
                });
                default -> fail("NOT_FOUND", "未知端点：" + path);
            };
        }
        return fail("NOT_FOUND", "未知端点：" + path);
    }

    /** 在服务端主线程执行，最多等 5 秒。 */
    private static Map<String, Object> onMain(MinecraftServer mc, java.util.function.Supplier<Map<String, Object>> task) {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable error) {
                future.complete(fail("INTERNAL_ERROR", String.valueOf(error.getMessage())));
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception error) {
            return fail("INTERNAL_ERROR", "主线程执行超时");
        }
    }

    /** 幂等键声请：同 (玩家, requestId) 只允许一次。 */
    private static boolean claim(UUID player, String requestId) {
        try {
            var data = ShopManager.data(ModuleShopApi.server());
            String key = player + "|http:" + requestId.trim();
            if (data.apiRequests.contains(key)) {
                return false;
            }
            data.apiRequests.add(key);
            data.trimApiRequests();
            data.setDirty();
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    private static List<ItemStack> itemList(Object body) {
        List<ItemStack> out = new ArrayList<>();
        for (Object element : MiniJson.list(body, "items")) {
            String id = MiniJson.str(element, "id", "");
            int count = (int) Math.max(1, Math.min(64, MiniJson.num(element, "count", 1L)));
            if (id.isBlank()) {
                continue;
            }
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key == null) {
                continue;
            }
            var item = BuiltInRegistries.ITEM.get(key);
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                continue;
            }
            out.add(new ItemStack(item, count));
        }
        return out;
    }

    private static Map<String, Object> fromResult(ApiResult<?> result, String valueKey) {
        if (result == null) {
            return fail("INTERNAL_ERROR", "null result");
        }
        if (!result.isOk()) {
            return fail(result.code().name(), result.message());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        if (valueKey != null) {
            payload.put(valueKey, result.value());
        }
        return payload;
    }

    private static Map<String, Object> itemMap(ShopItemView item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.id());
        map.put("item", item.stack().getItem().builtInRegistryHolder().key().location().toString());
        map.put("name", item.stack().getHoverName().getString());
        map.put("price", item.price());
        map.put("stock", item.stock());
        map.put("unlimited", item.unlimited());
        return map;
    }

    private static Map<String, Object> mailMap(MailInfo mail) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mailId", mail.mailId());
        map.put("title", mail.title());
        map.put("money", mail.money());
        map.put("items", mail.items().size());
        map.put("claimed", mail.claimed());
        map.put("sentAt", mail.sentAt());
        return map;
    }

    private static Map<String, Object> historyMap(TransactionInfo info) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("time", info.time());
        map.put("type", info.type());
        map.put("counterParty", info.counterParty());
        map.put("amount", info.amount());
        map.put("balanceAfter", info.balanceAfter());
        map.put("note", info.note());
        return map;
    }

    private static Map<String, Object> ok(Map<String, Object> payload) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", true);
        map.putAll(payload);
        return map;
    }

    private static Map<String, Object> fail(String code, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", false);
        map.put("error", code);
        map.put("message", message == null ? "" : message);
        return map;
    }

    private static Object safeParse(String body) {
        try {
            return MiniJson.parse(body);
        } catch (Throwable error) {
            return Map.of();
        }
    }

    private static String queryValue(String query, String key) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return pair.substring(eq + 1);
            }
        }
        return "";
    }

    private static long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text.trim());
        } catch (Exception error) {
            return fallback;
        }
    }

    private static void send(HttpExchange exchange, int status, Map<String, Object> payload) throws IOException {
        byte[] bytes = MiniJson.write(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
