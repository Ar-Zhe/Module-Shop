package cn.hsc.shoputil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Log {
    private static final Logger LOGGER = LoggerFactory.getLogger("shop_util");
    public static void info(String s) { LOGGER.info(s); }
    public static void warn(String s) { LOGGER.warn(s); }
    public static void error(String s, Throwable t) { LOGGER.error(s, t); }
}
