// ---------------------------------------------------------------------------
// Module Shop 公共 API · KubeJS 助手脚本（可选）
// 放到 <服务器>/kubejs/server_scripts/module_shop_api.js
// 前提：module_shop jar 是用 -PkubejsJar=<kubejs jar> 构建的（自带 ModuleShop 全局）
//       如果没有该全局，也可以用 Java.loadClass 直接调 Java API（见文件末尾）
// ---------------------------------------------------------------------------

// 1) 查余额 / 上限
ServerEvents.loaded(event => {
    event.server.players.forEach(p => {
        console.info(`[商店] ${p.username} 余额 ${ModuleShop.balanceText(p.uuid)}`)
    })
})

// 2) 每日任务结算（幂等：同一个 requestId 只发一次）
PlayerEvents.loggedIn(event => {
    const p = event.player
    // 例：今天首次登录发 10 星币（requestId 用日期保证一天一次）
    const today = `${event.server.overworld().getDayTime()}`  // 换成你自己的日期口径
    const r = ModuleShop.reward(p.uuid, `login-bonus:${today}`, 10, [], '登录奖励')
    if (r !== true) console.info(`[商店] 登录奖励未发放：${r}`)
})

// 3) 给玩家发物品 + 邮件（离线也能收）
export function giveReward(player, itemId, count, title) {
    const Component = Java.loadClass('net.minecraft.network.chat.Component')
    const p = player
    ModuleShop.mail(p.uuid, title, '感谢参与，附件请查收。', [Item.of(itemId, count)])
}

// 4) 只记账（不动余额）：把外部商店的收入写进玩家账单
export function recordExternal(player, amount, note) {
    ModuleShop.record(player.uuid, 'external_shop', '我的商店', amount, note)
}

// 5) 受校验的购买（会自动复核价格）
export function buyDailyItem(player, index) {
    const lines = ModuleShop.shopLines(false, player.uuid)
    if (lines.length === 0) return '没有商品'
    const r = ModuleShop.buyDaily(player.uuid, index, 1, -1)  // -1 = 不校验价格
    return r === true ? '购买成功' : r
}

// 6) 没有 ModuleShop 全局时（未启用桥接）：直接调 Java API
export function fallbackExample(player) {
    const Api = Java.loadClass('cn.autoforged.module_shop.api.ModuleShopApi')
    return Api.economy().balance(player.uuid)
}