package cn.autoforged.module_shop.shop;

import cn.autoforged.module_shop.data.PlayerAccount;
import cn.autoforged.module_shop.data.ShopSavedData;
import cn.autoforged.module_shop.data.TransactionEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;

/**
 * 账单导出：把玩家交易流水写成 **CSV 文件**（Excel 可直接打开，带 UTF-8 BOM，中文不乱码）。
 *
 * <h2>为什么做</h2>
 * 游戏里只能「玩家自己看账单」或管理员在聊天栏翻页看某人的流水，没法拿去做对账。
 * 这里把流水落到服务器目录 `config/module_shop/bills/`：
 * <ul>
 *     <li>单玩家：`账单-&lt;玩家&gt;-&lt;导出时间&gt;.csv`，一行一笔，含**对手方**；</li>
 *     <li>全服：`账单-全服-&lt;导出时间&gt;.csv` + `对账-玩家间-&lt;导出时间&gt;.csv`
 *     （后者按「A ⇄ B」聚合成净额，一眼看出谁该给谁多少钱）。</li>
 * </ul>
 * 只写文件、不改任何存档数据；目录不存在会自动创建。
 */
public final class BillExporter {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private BillExporter() {
    }

    /** 导出结果：写到了哪个文件、多少行。 */
    public record Result(Path path, int rows, String message) {
    }

    /** 交易类型 → 中文名（账单里比 `trade_in` 好读）。 */
    private static String typeName(String type) {
        if (type == null || type.isBlank()) {
            return "其它";
        }
        return switch (type) {
            case TransactionLog.BUY -> "商店购买";
            case TransactionLog.SELL -> "商店出售";
            case TransactionLog.STALL_BUY -> "摊位买入";
            case TransactionLog.STALL_SELL -> "摊位卖出";
            case TransactionLog.AUCTION_BID -> "拍卖出价";
            case TransactionLog.AUCTION_REFUND -> "拍卖退还";
            case TransactionLog.AUCTION_WIN -> "拍卖成交";
            case TransactionLog.AUCTION_SELL -> "拍卖收入";
            case TransactionLog.TRANSFER_IN -> "转账收入";
            case TransactionLog.TRANSFER_OUT -> "转账支出";
            case TransactionLog.TRADE_IN -> "交易收入";
            case TransactionLog.TRADE_OUT -> "交易支出";
            case TransactionLog.MAIL_ITEM -> "邮件物品";
            case TransactionLog.MAIL_MONEY -> "邮件金币";
            case TransactionLog.ADMIN_MONEY -> "管理员发放";
            case TransactionLog.ADMIN_ITEM -> "管理员物品";
            case TransactionLog.SHOP_REFRESH -> "商店刷新";
            case TransactionLog.BUYBACK_BONUS -> "收购全清奖励";
            case TransactionLog.EXCHANGE_BUY -> "货币兑换";
            case TransactionLog.EXCHANGE_WITHDRAW -> "货币取出";
            case TransactionLog.SIGN_IN -> "签到";
            case TransactionLog.SIGN_MAKEUP -> "补签";
            case TransactionLog.REDEEM -> "兑换码";
            case TransactionLog.EXTERNAL -> "外部";
            case ShopManager.SOURCE_DEMAND_REWARD -> "求购悬赏";
            case ShopManager.SOURCE_DEMAND_REFUND -> "求购退款";
            case ShopManager.SOURCE_DEMAND_DELIVER -> "求购到货";
            default -> type;
        };
    }

    private static Path billsDir() {
        return FMLPaths.CONFIGDIR.get().resolve("module_shop").resolve("bills");
    }

    private static String safeName(String raw) {
        String name = raw == null ? "unknown" : raw.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return name.isEmpty() ? "unknown" : name;
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    /** 解析 `2026-10-01`；格式不对返回 null（表示不限制）。 */
    public static Long parseDay(String text) {
        if (text == null || text.isBlank() || "all".equalsIgnoreCase(text.trim())) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim(), DAY).toEpochDay();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static String dayOf(long millis) {
        return DAY.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()));
    }

    private static String timeOf(long millis) {
        return TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()));
    }

    private static boolean inRange(TransactionEntry entry, Long fromDay, Long toDay) {
        if (fromDay == null && toDay == null) {
            return true;
        }
        String day = dayOf(entry.time);
        if (fromDay != null && day.compareTo(DAY.format(LocalDate.ofEpochDay(fromDay))) < 0) {
            return false;
        }
        if (toDay != null && day.compareTo(DAY.format(LocalDate.ofEpochDay(toDay))) > 0) {
            return false;
        }
        return true;
    }

    /**
     * 导出**单个玩家**的账单。
     *
     * @param targetName 玩家名（大小写不敏感，按存档里的 name 匹配）
     */
    public static Result exportPlayer(MinecraftServer server, String targetName, Long fromDay, Long toDay)
            throws IOException {
        ShopSavedData data = ShopManager.data(server);
        Map.Entry<UUID, PlayerAccount> found = null;
        for (Map.Entry<UUID, PlayerAccount> entry : data.accounts().entrySet()) {
            if (entry.getValue().name != null && entry.getValue().name.equalsIgnoreCase(targetName)) {
                found = entry;
                break;
            }
        }
        if (found == null) {
            return new Result(null, 0, "找不到玩家：" + targetName);
        }
        PlayerAccount account = found.getValue();
        List<TransactionEntry> list = new ArrayList<>();
        for (TransactionEntry entry : account.transactions) {
            if (inRange(entry, fromDay, toDay)) {
                list.add(entry);
            }
        }
        list.sort(Comparator.comparingLong(e -> e.time));

        StringBuilder sb = new StringBuilder();
        sb.append("玩家,").append(csv(account.name)).append('\n');
        sb.append("UID,").append(found.getKey()).append('\n');
        sb.append("当前余额,").append(account.balance).append('\n');
        sb.append("导出时间,").append(timeOf(System.currentTimeMillis())).append('\n');
        sb.append('\n');
        sb.append("时间,类型,对手方,金额,交易后余额,备注\n");
        long in = 0L;
        long out = 0L;
        for (TransactionEntry entry : list) {
            if (entry.amount > 0) {
                in += entry.amount;
            } else {
                out += entry.amount;
            }
            sb.append(csv(timeOf(entry.time))).append(',')
                    .append(csv(typeName(entry.type))).append(',')
                    .append(csv(entry.counterParty)).append(',')
                    .append(entry.amount).append(',')
                    .append(entry.balanceAfter).append(',')
                    .append(csv(entry.note)).append('\n');
        }
        sb.append('\n');
        sb.append("合计收入,").append(in).append('\n');
        sb.append("合计支出,").append(out).append('\n');
        sb.append("净额,").append(in + out).append('\n');

        Path file = billsDir().resolve("账单-" + safeName(account.name) + "-"
                + STAMP.format(LocalDateTime.now()) + ".csv");
        writeCsv(file, sb.toString());
        return new Result(file, list.size(), "已导出 " + list.size() + " 条流水");
    }

    /**
     * 导出**全服**账单：一份原始流水 + 一份「玩家间对账」（按 A⇄B 聚合净额）。
     */
    public static Result exportAll(MinecraftServer server, Long fromDay, Long toDay) throws IOException {
        ShopSavedData data = ShopManager.data(server);
        // 全服所有流水，按时间排序
        record Row(String who, TransactionEntry entry) {
        }
        List<Row> rows = new ArrayList<>();
        Map<UUID, String> names = new LinkedHashMap<>();
        for (Map.Entry<UUID, PlayerAccount> accountEntry : data.accounts().entrySet()) {
            PlayerAccount account = accountEntry.getValue();
            String name = account.name == null || account.name.isBlank() ? accountEntry.getKey().toString()
                    : account.name;
            names.put(accountEntry.getKey(), name);
            for (TransactionEntry entry : account.transactions) {
                if (inRange(entry, fromDay, toDay)) {
                    rows.add(new Row(name, entry));
                }
            }
        }
        rows.sort(Comparator.comparingLong(r -> r.entry().time));

        StringBuilder sb = new StringBuilder();
        sb.append("全服账单导出时间,").append(timeOf(System.currentTimeMillis())).append('\n');
        sb.append("玩家数,").append(data.accounts().size()).append('\n');
        sb.append('\n');
        sb.append("时间,玩家,类型,对手方,金额,交易后余额,备注\n");
        for (Row row : rows) {
            sb.append(csv(timeOf(row.entry().time))).append(',')
                    .append(csv(row.who())).append(',')
                    .append(csv(typeName(row.entry().type))).append(',')
                    .append(csv(row.entry().counterParty)).append(',')
                    .append(row.entry().amount).append(',')
                    .append(row.entry().balanceAfter).append(',')
                    .append(csv(row.entry().note)).append('\n');
        }
        String stamp = STAMP.format(LocalDateTime.now());
        Path main = billsDir().resolve("账单-全服-" + stamp + ".csv");
        writeCsv(main, sb.toString());

        // ---- 玩家间对账：只统计"对手方也是玩家"的流水，按 (我, 他) 聚合 ----
        record Pair(String from, String to, long amount, int count) {
        }
        Map<String, long[]> pairMap = new LinkedHashMap<>();
        Map<String, Integer> pairCount = new LinkedHashMap<>();
        for (Row row : rows) {
            String other = row.entry().counterParty;
            if (other == null || other.isBlank() || isSystemSide(other)) {
                continue;   // 只做"玩家 ↔ 玩家"的对账，系统/管理员/外部来源不算
            }
            // 「我 → 他」：我支出为正、收入为负，便于算净额
            String key = row.who() + "\u0001" + other;
            long delta = row.entry().amount < 0 ? -row.entry().amount : -0L;
            if (row.entry().amount < 0) {
                delta = -row.entry().amount;   // 我付出去
            } else {
                delta = -row.entry().amount;   // 我收进来（记成负，方便两两抵消）
            }
            pairMap.computeIfAbsent(key, k -> new long[1])[0] += delta;
            pairCount.merge(key, 1, Integer::sum);
        }
        StringBuilder recon = new StringBuilder();
        recon.append("玩家间对账（净额为正 = 前者应给后者；已按双向流水相抵）\n");
        recon.append("导出时间,").append(timeOf(System.currentTimeMillis())).append('\n');
        recon.append('\n');
        recon.append("玩家A,玩家B,净额,A→B笔数\n");
        List<String> keys = new ArrayList<>(pairMap.keySet());
        keys.sort(Comparator.naturalOrder());
        for (String key : keys) {
            String[] parts = key.split("\u0001", 2);
            long net = pairMap.get(key)[0];
            if (net == 0L) {
                continue;
            }
            recon.append(csv(parts[0])).append(',')
                    .append(csv(parts[1])).append(',')
                    .append(net).append(',')
                    .append(pairCount.getOrDefault(key, 0)).append('\n');
        }
        Path reconFile = billsDir().resolve("对账-玩家间-" + stamp + ".csv");
        writeCsv(reconFile, recon.toString());
        return new Result(main, rows.size(),
                "已导出全服 " + rows.size() + " 条流水；玩家间对账见 " + reconFile.getFileName());
    }

    /** 写 CSV：UTF-8 + BOM（Excel 打开中文不乱码），目录不存在自动创建。 */
    /** 这些"对手方"不是玩家（系统/管理员/外部），不计入玩家间对账。 */
    private static boolean isSystemSide(String counterParty) {
        String name = counterParty.trim();
        return name.equals("管理员") || name.equals("系统") || name.equals("外部插件")
                || name.equals("求购系统") || name.equals("商店系统") || name.equals("摊位系统")
                || name.equals("拍卖会系统") || name.equals("兑换码系统") || name.startsWith("求购:");
    }

    private static void writeCsv(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(body, 0, all, bom.length, body.length);
        Files.write(file, all);
    }

    /** 给指令用的简短类型名（避免 unused 警告时也能复用）。 */
    static String describe(String type) {
        return typeName(type).toLowerCase(Locale.ROOT);
    }
}
