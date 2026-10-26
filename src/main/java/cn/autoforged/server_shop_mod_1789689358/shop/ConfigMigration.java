package cn.autoforged.server_shop_mod_1789689358.shop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;

/**
 * 配置自动迁移（第七轮）：让服务端**只替换模组 jar** 就能拿到新默认值，无需手动改配置文件。
 * <p>
 * 只迁移「值与旧默认值完全相同」的键，避免覆盖服主自己的定制：
 * <ul>
 *   <li>{@code maxMailboxSize = 50} → {@code 100}（新默认：邮箱 100 封 + 超限自动清最早已读）</li>
 *   <li>{@code currencyName = "金币"} → {@code "星币"}（币种统一）</li>
 * </ul>
 * 写回时严格使用 **UTF-8 无 BOM**（带 BOM 会被 NeoForge 判定为非法并把配置重置为默认值）。
 */
public final class ConfigMigration {
    private static final String FILE_NAME = "server_shop_mod_1789689358-common.toml";
    private static boolean done;

    private ConfigMigration() {
    }

    /** 只执行一次（在第一次下发快照时调用，此时配置已由 NeoForge 生成/加载完毕）。 */
    public static synchronized void runOnce() {
        if (done) {
            return;
        }
        done = true;
        try {
            Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
            if (!Files.exists(path)) {
                return;
            }
            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            String migrated = text
                    .replace("maxMailboxSize = 50", "maxMailboxSize = 100")
                    .replace("currencyName = \"金币\"", "currencyName = \"星币\"")
                    .replace("maxBalance = 999999999", "maxBalance = 9999999999")
                    // 早期版本范围下限写成 1，NeoForge 把 maxBalance = 0（不限）纠正成了 1 并写回文件，
                    // 导致"不限"变成"上限 1 星币"（管理员存任何商品都报"价格无效"）。这里改回 0 = 不限。
                    .replace("maxBalance = 1\n", "maxBalance = 0\n")
                    .replace("maxBalance = 1\r\n", "maxBalance = 0\r\n");
            if (migrated.equals(text)) {
                return;
            }
            Files.write(path, migrated.getBytes(StandardCharsets.UTF_8));
            org.slf4j.LoggerFactory.getLogger("server_shop")
                    .info("[模块商店] 已把旧默认配置自动迁移为新默认值"
                            + "（maxMailboxSize 50→100、currencyName 金币→星币、maxBalance 1→0=不限）");
        } catch (Exception ignored) {
            // 迁移失败不影响运行：代码里的默认值与显示层迁移照常生效。
        }
    }
}
