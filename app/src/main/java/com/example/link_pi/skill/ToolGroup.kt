package com.example.link_pi.skill

/**
 * Tool groups for context-aware injection.
 * Each agent tool belongs to exactly one group.
 */
enum class ToolGroup {
    CORE,          // get_current_time, calculate, show_toast
    MEMORY,        // memory_save, memory_search, memory_list, memory_delete, memory_update, save_data, load_data
    APP_CREATE,    // write_file
    APP_READ,      // read_file, list_files, read_truncated_output
    APP_EDIT,      // edit_file, rename_file, delete_path, undo_file
    APP_NAVIGATE,  // list_saved_apps, open_app_workspace
    CODING,        // search, validate, get_runtime_errors, diff_file, inspect_workspace, read_plan
    DEVICE,        // get_device_info, get_battery_level, get_location, vibrate, write_clipboard
    NETWORK,       // fetch_url, web_search
    MODULE,        // create_module, start_module, stop_module, call_module, list_modules, update_module, delete_module, write_module_script, read_module_script, test_module_script, list_module_templates
    SSH            // ssh_connect, ssh_exec, ssh_disconnect, ssh_upload, ssh_download, ssh_list_remote, ssh_list_sessions, ssh_port_forward
}

/** NativeBridge API documentation groups (for generated app code, not agent tools). */
enum class BridgeGroup {
    STORAGE,       // saveData, loadData, removeData, clearData, listKeys, getAppId
    UI_FEEDBACK,   // showToast, vibrate, writeClipboard, sendToApp
    SENSOR,        // getDeviceInfo, getBatteryLevel, getLocation
    NETWORK,       // nativeFetch, callModule, listModules
    REALTIME       // WebSocket server, getLocalIp — LAN real-time communication
}

/** CDN library groups. */
enum class CdnGroup {
    FRAMEWORK,     // Vue 2/3, React, ReactDOM
    CHART,         // Chart.js
    THREE_D,       // Three.js
    UTILS          // Axios, Animate.css
}

/** User intent — determined by AI pre-classification. */
enum class UserIntent {
    CONVERSATION,
    CREATE_APP,
    MODIFY_APP,
    MODULE_MGMT,
    MEMORY_OPS;

    fun needsApp(): Boolean = this == CREATE_APP || this == MODIFY_APP
}

/** Agent execution phase. */
enum class AgentPhase {
    PLANNING,
    GENERATION,
    REFINEMENT
}

/** Canonical mapping: tool name → ToolGroup. */
val TOOL_GROUP_MAP: Map<String, ToolGroup> = mapOf(
    // CORE
    "get_current_time" to ToolGroup.CORE,
    "calculate" to ToolGroup.CORE,
    "show_toast" to ToolGroup.CORE,
    "launch_workbench" to ToolGroup.CORE,
    // MEMORY (includes persistent key-value storage)
    "memory_save" to ToolGroup.MEMORY,
    "memory_search" to ToolGroup.MEMORY,
    "memory_list" to ToolGroup.MEMORY,
    "memory_delete" to ToolGroup.MEMORY,
    "memory_update" to ToolGroup.MEMORY,
    "save_data" to ToolGroup.MEMORY,
    "load_data" to ToolGroup.MEMORY,
    // APP_CREATE
    "write_file" to ToolGroup.APP_CREATE,
    // APP_READ
    "read_file" to ToolGroup.APP_READ,
    "list_files" to ToolGroup.APP_READ,
    "read_truncated_output" to ToolGroup.APP_READ,
    // APP_EDIT
    "edit_file" to ToolGroup.APP_EDIT,
    "rename_file" to ToolGroup.APP_EDIT,
    "delete_path" to ToolGroup.APP_EDIT,
    "undo_file" to ToolGroup.APP_EDIT,
    // APP_NAVIGATE
    "list_saved_apps" to ToolGroup.APP_NAVIGATE,
    "open_app_workspace" to ToolGroup.APP_NAVIGATE,
    // CODING
    "search" to ToolGroup.CODING,
    "validate" to ToolGroup.CODING,
    "get_runtime_errors" to ToolGroup.CODING,
    "diff_file" to ToolGroup.CODING,
    "inspect_workspace" to ToolGroup.CODING,
    "read_plan" to ToolGroup.CODING,
    // DEVICE
    "get_device_info" to ToolGroup.DEVICE,
    "get_battery_level" to ToolGroup.DEVICE,
    "get_location" to ToolGroup.DEVICE,
    "vibrate" to ToolGroup.DEVICE,
    "write_clipboard" to ToolGroup.DEVICE,
    // NETWORK
    "fetch_url" to ToolGroup.NETWORK,
    "web_search" to ToolGroup.NETWORK,
    // MODULE
    "create_module" to ToolGroup.MODULE,
    "start_module" to ToolGroup.MODULE,
    "stop_module" to ToolGroup.MODULE,
    "call_module" to ToolGroup.MODULE,
    "list_modules" to ToolGroup.MODULE,
    "update_module" to ToolGroup.MODULE,
    "delete_module" to ToolGroup.MODULE,
    "write_module_script" to ToolGroup.MODULE,
    "read_module_script" to ToolGroup.MODULE,
    "test_module_script" to ToolGroup.MODULE,
    "list_module_templates" to ToolGroup.MODULE,
    // SSH
    "ssh_connect" to ToolGroup.SSH,
    "ssh_exec" to ToolGroup.SSH,
    "ssh_disconnect" to ToolGroup.SSH,
    "ssh_upload" to ToolGroup.SSH,
    "ssh_download" to ToolGroup.SSH,
    "ssh_list_remote" to ToolGroup.SSH,
    "ssh_list_sessions" to ToolGroup.SSH,
    "ssh_port_forward" to ToolGroup.SSH
)

/**
 * Resolve which ToolGroups are visible for a given Intent × Phase × Skill combination.
 */
fun resolveToolGroups(intent: UserIntent, phase: AgentPhase, extraGroups: Set<ToolGroup>): Set<ToolGroup> {
    val groups = mutableSetOf(ToolGroup.CORE)

    when (intent) {
        UserIntent.CONVERSATION, UserIntent.MEMORY_OPS -> {
            groups.addAll(listOf(ToolGroup.MEMORY, ToolGroup.DEVICE, ToolGroup.NETWORK))
        }
        UserIntent.CREATE_APP -> {
            when (phase) {
                AgentPhase.PLANNING -> {
                    groups.addAll(listOf(ToolGroup.MEMORY, ToolGroup.MODULE))
                }
                AgentPhase.GENERATION -> {
                    groups.addAll(listOf(ToolGroup.APP_CREATE, ToolGroup.APP_READ, ToolGroup.APP_EDIT, ToolGroup.CODING, ToolGroup.NETWORK))
                }
                AgentPhase.REFINEMENT -> {
                    groups.addAll(listOf(ToolGroup.APP_READ, ToolGroup.APP_EDIT, ToolGroup.CODING))
                }
            }
        }
        UserIntent.MODIFY_APP -> {
            when (phase) {
                AgentPhase.PLANNING -> {
                    // Planning is read-only: explore workspace, read code, plan modifications
                    groups.addAll(listOf(ToolGroup.MEMORY, ToolGroup.APP_READ, ToolGroup.APP_NAVIGATE, ToolGroup.CODING, ToolGroup.MODULE))
                }
                AgentPhase.GENERATION -> {
                    groups.addAll(listOf(ToolGroup.APP_CREATE, ToolGroup.APP_READ, ToolGroup.APP_EDIT, ToolGroup.APP_NAVIGATE, ToolGroup.CODING, ToolGroup.NETWORK))
                }
                AgentPhase.REFINEMENT -> {
                    groups.addAll(listOf(ToolGroup.APP_READ, ToolGroup.APP_EDIT, ToolGroup.CODING))
                }
            }
        }
        UserIntent.MODULE_MGMT -> {
            groups.addAll(listOf(ToolGroup.MEMORY, ToolGroup.MODULE, ToolGroup.NETWORK))
        }
    }

    // Add Skill-level extra groups (skip during Planning to keep prompt focused)
    if (!(phase == AgentPhase.PLANNING && (intent == UserIntent.CREATE_APP || intent == UserIntent.MODIFY_APP))) {
        groups.addAll(extraGroups)
    }

    return groups
}
