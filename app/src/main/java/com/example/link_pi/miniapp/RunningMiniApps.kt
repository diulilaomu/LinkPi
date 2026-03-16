package com.example.link_pi.miniapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks currently running MiniApp Activities across the app.
 * Used by the main UI to show a floating "running apps" indicator.
 */
object RunningMiniApps {

    data class Entry(val appId: String, val appName: String, val slotClass: Class<*>)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun add(appId: String, appName: String, slotClass: Class<*>) {
        _entries.value = _entries.value.filter { it.appId != appId } + Entry(appId, appName, slotClass)
    }

    fun remove(appId: String) {
        _entries.value = _entries.value.filter { it.appId != appId }
    }
}
