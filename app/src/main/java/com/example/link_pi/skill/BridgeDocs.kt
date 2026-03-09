package com.example.link_pi.skill

/**
 * Grouped NativeBridge API documentation.
 * Only relevant groups are injected based on the active Skill.
 */
object BridgeDocs {

    private val STORAGE = """
- NativeBridge.saveData(key, value) — 持久化保存数据（每个应用独立隔离）
- NativeBridge.loadData(key) — 加载已保存的数据（返回字符串或空）
- NativeBridge.removeData(key) — 删除已存储的键
- NativeBridge.clearData() — 清除此应用的所有存储数据
- NativeBridge.listKeys() — 返回所有已存储键的逗号分隔列表
- NativeBridge.getAppId() — 返回当前应用的唯一 ID
""".trimIndent()

    private val UI_FEEDBACK = """
- NativeBridge.showToast(message) — 显示原生 Toast 通知
- NativeBridge.vibrate(milliseconds) — 设备振动（最长 5000ms）
- NativeBridge.writeClipboard(text) — 复制文本到剪贴板
- NativeBridge.sendToApp(jsonString) — 向宿主应用发送数据
""".trimIndent()

    private val SENSOR = """
- NativeBridge.getDeviceInfo() — 返回 JSON 字符串：{model, brand, manufacturer, sdkVersion, release}
- NativeBridge.getBatteryLevel() — 返回电量百分比（0-100）
- NativeBridge.getLocation() — 返回 JSON 字符串：{latitude, longitude, accuracy}，或空字符串
""".trimIndent()

    private val NETWORK = """
- nativeFetch(url, options) — HTTP 请求（绕过 CORS）。返回类似 fetch API 的 Promise
  用法：nativeFetch('https://api.example.com/data', {method:'GET',headers:{},body:''}).then(r=>r.json()).then(data=>...)
  响应：{status, statusText, headers, body, ok, json(), text()}
""".trimIndent()

    private val GROUP_MAP = mapOf(
        BridgeGroup.STORAGE to STORAGE,
        BridgeGroup.UI_FEEDBACK to UI_FEEDBACK,
        BridgeGroup.SENSOR to SENSOR,
        BridgeGroup.NETWORK to NETWORK
    )

    /**
     * Build NativeBridge documentation for the given groups.
     * Returns empty string if groups is empty.
     */
    fun build(groups: Set<BridgeGroup>): String {
        if (groups.isEmpty()) return ""
        val apis = groups.mapNotNull { GROUP_MAP[it] }.joinToString("\n")
        return """
### NativeBridge API（在生成的应用代码中通过 window.NativeBridge 使用）

$apis

每个迷你应用都有独立的存储空间。一个应用保存的数据无法被另一个应用访问。
使用前始终检查可用性：if (window.NativeBridge) { ... }
""".trimIndent()
    }
}
