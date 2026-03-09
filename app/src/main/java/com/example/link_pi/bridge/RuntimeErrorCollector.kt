package com.example.link_pi.bridge

/**
 * Collects JS runtime errors from WebView for AI to consume via get_runtime_errors tool.
 * Thread-safe singleton, errors are per-appId.
 */
object RuntimeErrorCollector {
    private val errors = mutableMapOf<String, MutableList<String>>()
    private const val MAX_ERRORS_PER_APP = 50

    @Synchronized
    fun report(appId: String, error: String) {
        val list = errors.getOrPut(appId) { mutableListOf() }
        if (list.size < MAX_ERRORS_PER_APP) {
            list.add(error)
        }
    }

    @Synchronized
    fun getErrors(appId: String): List<String> {
        return errors[appId]?.toList() ?: emptyList()
    }

    @Synchronized
    fun clear(appId: String) {
        errors.remove(appId)
    }
}
