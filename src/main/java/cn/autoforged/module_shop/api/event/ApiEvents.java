package cn.autoforged.module_shop.api.event;

import cn.autoforged.module_shop.api.ModuleShopApi;
import net.neoforged.bus.api.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 事件发布工具（内部使用）。
 *
 * <p>所有发布都**不得**把异常抛回业务流程：监听器抛错只会写日志。
 * 发布时机统一为「业务已经成功、状态已确定」之后。
 */
public final class ApiEvents {
    private static final Logger LOG = LoggerFactory.getLogger("module_shop_api/events");

    private ApiEvents() {
    }

    /** 安全发布：吞掉监听器异常，绝不让它破坏已完成的事务。 */
    public static void post(Event event) {
        if (event == null) {
            return;
        }
        try {
            ModuleShopApi.events().post(event);
            LOG.info("[API-event] {}", event);
        } catch (Throwable error) {
            LOG.error("[API-event] 发布 {} 失败（已忽略，不影响业务）", event.getClass().getSimpleName(), error);
        }
    }
}