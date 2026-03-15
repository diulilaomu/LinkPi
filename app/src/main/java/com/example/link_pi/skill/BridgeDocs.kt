package com.example.link_pi.skill

/**
 * Grouped NativeBridge API documentation.
 * Only relevant groups are injected based on the active Skill.
 */
object BridgeDocs {

    private val STORAGE = """
- saveData(key, value) — 持久化保存数据（每个应用独立隔离）。key/value 均为字符串，value 过大时建议 JSON.stringify 压缩
- loadData(key) — 加载已保存的数据（返回字符串，不存在时返回空字符串 ""）
- removeData(key) — 删除已存储的键
- clearData() — 清除此应用的所有存储数据
- listKeys() — 返回所有已存储键的数组
- getAppId() — 返回当前应用的唯一 ID
""".trimIndent()

    private val UI_FEEDBACK = """
- showToast(message) — 显示原生 Toast 通知
- vibrate(milliseconds) — 设备振动（最长 5000ms）
- writeClipboard(text) — 复制文本到剪贴板
- sendToApp(jsonString) — 向宿主应用发送数据
""".trimIndent()

    private val SENSOR = """
- getDeviceInfo() — 返回 JSON 对象：{model, brand, manufacturer, sdkVersion, release}
- getBatteryLevel() — 返回电量百分比（0-100）
- getLocation() — 返回 JSON 对象：{latitude, longitude, accuracy}，定位失败时返回空字符串（需检查返回值）
""".trimIndent()

    private val NETWORK = """
- nativeFetch(url, options) — HTTP 请求（绕过 CORS）。返回类似 fetch API 的 Promise
  用法：nativeFetch('https://api.example.com/data', {method:'GET',headers:{},body:''}).then(r=>r.json()).then(data=>...)
  响应：{status, statusText, headers, body, ok, json(), text()}
  错误处理：网络失败时 Promise reject，务必用 .catch() 处理。超时约 30 秒
- callModule(moduleName, path, options) — 调用运行中的 Python 服务模块。返回 Promise
  用法：callModule('my_api', '/hello').then(r => console.log(r))
  带请求体：callModule('my_api', '/process', {method:'POST', body:{data:'hello'}}).then(r => console.log(r))
  options: {method?: 'GET'|'POST'|'PUT'|'DELETE', body?: object|string}
  错误处理：模块未运行或不存在时 reject，先用 listModules() 确认可用性
- listModules() — 返回所有模块及其运行状态的数组（同步）。每个元素包含 name、description、serviceType、running、port、scripts
  用法：const mods = listModules(); mods.forEach(m => console.log(m.name, m.running))
""".trimIndent()

    private val REALTIME = """
- startServer(port) — 启动 WebSocket 服务器。返回 Promise<{port, ip}>
  用法：startServer(8080).then(info => console.log('服务器运行在 ws://' + info.ip + ':' + info.port))
- onServerEvent — 设置服务器事件回调函数
  window.onServerEvent = function(event) { ... }
  event.type: "connection"（新客户端连接，含 clientId、address）
           | "message"（收到消息，含 clientId、data）
           | "close"（客户端断开，含 clientId）
           | "error"（错误，含 message）
- serverSend(clientId, message) — 向指定客户端发送消息
- serverBroadcast(message) — 向所有连接的客户端广播消息
- stopServer() — 停止 WebSocket 服务器
- getLocalIp() — 返回设备局域网 IP 地址（同步），用于客户端发现服务器

客户端连接方式（标准 WebSocket API，无需额外封装）：
  const ws = new WebSocket('ws://' + serverIp + ':' + port);
  ws.onmessage = function(e) { console.log(e.data); };
  ws.send(JSON.stringify({type:'move', x:3, y:5}));

典型用法（局域网对战）：
  设备A（主机）：调用 startServer(8080)，显示 IP 给对方
  设备B（客户端）：用 new WebSocket('ws://对方IP:8080') 连接
  双向通信通过 serverSend/serverBroadcast（主机端）和 ws.send（客户端）完成
""".trimIndent()

    private val GROUP_MAP = mapOf(
        BridgeGroup.STORAGE to STORAGE,
        BridgeGroup.UI_FEEDBACK to UI_FEEDBACK,
        BridgeGroup.SENSOR to SENSOR,
        BridgeGroup.NETWORK to NETWORK,
        BridgeGroup.REALTIME to REALTIME
    )

    /**
     * Build NativeBridge documentation for the given groups.
     * Returns empty string if groups is empty.
     */
    fun build(groups: Set<BridgeGroup>): String {
        if (groups.isEmpty()) return ""
        val apis = groups.mapNotNull { GROUP_MAP[it] }.joinToString("\n")
        return """
### NativeBridge API（在生成的应用代码中直接调用，无需前缀）

$apis

每个迷你应用都有独立的存储空间。一个应用保存的数据无法被另一个应用访问。
所有 API 均已注册为全局函数，直接调用即可（如 saveData()、showToast()、nativeFetch()）。
""".trimIndent()
    }
}
