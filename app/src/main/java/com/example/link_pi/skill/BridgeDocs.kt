package com.example.link_pi.skill

/**
 * Grouped NativeBridge API documentation.
 * Only relevant groups are injected based on the active Skill.
 */
object BridgeDocs {

    private val STORAGE = """
- NativeBridge.saveData(key, value) — Save data persistently (isolated per app)
- NativeBridge.loadData(key) — Load saved data (returns string or empty)
- NativeBridge.removeData(key) — Remove a stored key
- NativeBridge.clearData() — Clear all stored data for this app
- NativeBridge.listKeys() — Returns comma-separated list of all stored keys
- NativeBridge.getAppId() — Returns the current app's unique ID
""".trimIndent()

    private val UI_FEEDBACK = """
- NativeBridge.showToast(message) — Show a native toast notification
- NativeBridge.vibrate(milliseconds) — Vibrate device (max 5000ms)
- NativeBridge.writeClipboard(text) — Copy text to clipboard
- NativeBridge.sendToApp(jsonString) — Send data back to the host app
""".trimIndent()

    private val SENSOR = """
- NativeBridge.getDeviceInfo() — Returns JSON string: {model, brand, manufacturer, sdkVersion, release}
- NativeBridge.getBatteryLevel() — Returns battery percentage (0-100)
- NativeBridge.getLocation() — Returns JSON string: {latitude, longitude, accuracy} or empty string
""".trimIndent()

    private val NETWORK = """
- nativeFetch(url, options) — HTTP request (bypasses CORS). Returns Promise like fetch API
  Usage: nativeFetch('https://api.example.com/data', {method:'GET',headers:{},body:''}).then(r=>r.json()).then(data=>...)
  Response: {status, statusText, headers, body, ok, json(), text()}
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
### NativeBridge API (use in generated app code via window.NativeBridge)

$apis

Each mini app has its own isolated storage space. Data saved by one app cannot be accessed by another.
Always check availability before using: if (window.NativeBridge) { ... }
""".trimIndent()
    }
}
