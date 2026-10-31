package cn.autoforged.module_shop.client;

import java.util.Map;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

/**
 * 客户端显示层：把文案里**写死的默认货币名**换成管理员改过的名字。
 *
 * <p>为什么需要：界面与聊天里还有几十条把「星币」直接写在句子里的翻译串
 *（{@code 花费 %s星币 购买 %s}、{@code 余额：%s 星币}、{@code 星币各 %s} …）。这些是**带参数的**翻译串，
 * 参数在服务端拼好、货币名却写死在串里。逐条改成「参数带货币名」要动四十多个调用点，
 * 参数顺序错一个整句就会显示成原始 key —— 风险远大于收益。
 *
 * <p>做法：给当前的 {@link Language} 包一层，凡是取值里含默认货币名的就替换掉。
 * 于是**所有**翻译查询（界面、聊天、tooltip）都自动跟随，组件结构 / 颜色 / 点击事件全不变。
 *
 * <p>为什么不用「直接改语言表里的值」：那张表在切语言 / 资源重载时会被换掉，
 * 而且不同环境下拿到的可能是只读视图，改不动还没法报错 —— 踩过一次（聊天里仍然显示「星币」）。
 * 包一层则每次查询都实时生效，语言表被换掉后下一份快照会重新包一次。
 */
public final class CurrencyNames {
    /** 语言文件里写死的默认货币名（= 配置项 currencyName 的默认值）。 */
    private static final String DEFAULT = "星币";

    /** 当前生效的自定义货币名（空 / 默认名 = 不做替换）。 */
    private static String applied = "";
    /** 第一次包之前的那份原始语言表。 */
    private static Language base;
    /** 当前 Language.getInstance() 是不是我们自己装的包装层。 */
    private static boolean installed;

    private CurrencyNames() {
    }

    /**
     * 按当前货币名刷新（每次收到商店快照 / 每 tick 调用都可以，名字没变时什么都不做）。
     *
     * <p><b>改名必须换一个新的 {@link Language} 实例</b>：{@code TranslatableContents} 会把
     * 「解析好的整句」按 Language 实例缓存起来，实例不换的话，改回默认名之后
     * 界面上仍然显示上一次的旧名字（表现为「改回星币 → 点别的地方又变回金币」）。
     * 所以这里每次都 {@code inject} 一个新实例：自定义名 → 新的包装层；默认名 → 原始语言表。
     */
    public static void apply(String rawName) {
        try {
            String want = rawName == null ? "" : rawName.trim();
            boolean wantCustom = !want.isEmpty() && !DEFAULT.equals(want);
            if (want.equals(applied) && installed == wantCustom) {
                return;   // 名字没变：不折腾语言表
            }
            applied = want;
            Language current = Language.getInstance();
            if (current == null) {
                return;
            }
            base = current instanceof CurrencyLanguage wrapper ? wrapper.delegate : current;
            installed = wantCustom;
            Language.inject(wantCustom ? new CurrencyLanguage(base) : base);
        } catch (Throwable ignored) {
            // 只影响「货币名自动跟随」，绝不因为它把界面搞崩。
        }
    }

    /**
     * 对**不经过语言表**的纯文本（服务端拼好的邮件正文 / 标题这类）做同样的替换。
     * 必须在 {@link #apply(String)} 之后调用（快照里货币名先解析，邮件在后）。
     */
    public static String text(String value) {
        if (value == null || !custom()) {
            return value;
        }
        return value.contains(DEFAULT) ? value.replace(DEFAULT, applied) : value;
    }

    private static boolean custom() {
        return !applied.isEmpty() && !DEFAULT.equals(applied);
    }

    /** 语言表的包装层：每次取词都顺手把默认货币名换掉。 */
    private static final class CurrencyLanguage extends Language {
        private final Language delegate;

        CurrencyLanguage(Language delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getOrDefault(String key, String fallback) {
            return text(this.delegate.getOrDefault(key, fallback));
        }

        @Override
        public String getOrDefault(String key) {
            return getOrDefault(key, key);
        }

        @Override
        public boolean has(String key) {
            return this.delegate.has(key);
        }

        @Override
        public boolean isDefaultRightToLeft() {
            return this.delegate.isDefaultRightToLeft();
        }

        @Override
        public FormattedCharSequence getVisualOrder(FormattedText text) {
            return this.delegate.getVisualOrder(text);
        }

        @Override
        public Map<String, String> getLanguageData() {
            return this.delegate.getLanguageData();
        }

        @Override
        public net.minecraft.network.chat.Component getComponent(String key) {
            return this.delegate.getComponent(key);
        }
    }
}
