package com.example.link_pi.skill

/**
 * Tool groups for context-aware injection.
 * Each agent tool belongs to exactly one group.
 */
enum class ToolGroup {
    CORE,          // get_current_time, calculate, show_toast
    MEMORY,        // memory_save, memory_search, memory_list, memory_delete, memory_update
    APP_CREATE,    // create_file, write_file, append_file, create_directory
    APP_READ,      // read_workspace_file, list_workspace_files, file_info
    APP_EDIT,      // replace_in_file, replace_lines, insert_lines, rename_file, copy_file, delete_workspace_file, delete_directory
    APP_NAVIGATE,  // list_saved_apps, open_app_workspace
    CODING,        // grep_file, grep_workspace
    DEVICE,        // get_device_info, get_battery_level, get_location, vibrate, write_clipboard
    NETWORK,       // fetch_url, save_data, load_data
    MODULE,        // create_module, add_module_endpoint, remove_module_endpoint, call_module, list_modules, update_module, delete_module
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
    // MEMORY
    "memory_save" to ToolGroup.MEMORY,
    "memory_search" to ToolGroup.MEMORY,
    "memory_list" to ToolGroup.MEMORY,
    "memory_delete" to ToolGroup.MEMORY,
    "memory_update" to ToolGroup.MEMORY,
    // APP_CREATE
    "create_file" to ToolGroup.APP_CREATE,
    "write_file" to ToolGroup.APP_CREATE,
    "append_file" to ToolGroup.APP_CREATE,
    "create_directory" to ToolGroup.APP_CREATE,
    // APP_READ
    "read_workspace_file" to ToolGroup.APP_READ,
    "list_workspace_files" to ToolGroup.APP_READ,
    "file_info" to ToolGroup.APP_READ,
    "list_snapshots" to ToolGroup.APP_READ,
    // APP_EDIT
    "replace_in_file" to ToolGroup.APP_EDIT,
    "replace_lines" to ToolGroup.APP_EDIT,
    "insert_lines" to ToolGroup.APP_EDIT,
    "rename_file" to ToolGroup.APP_EDIT,
    "copy_file" to ToolGroup.APP_EDIT,
    "delete_workspace_file" to ToolGroup.APP_EDIT,
    "delete_directory" to ToolGroup.APP_EDIT,
    "undo_file" to ToolGroup.APP_EDIT,
    // APP_NAVIGATE
    "list_saved_apps" to ToolGroup.APP_NAVIGATE,
    "open_app_workspace" to ToolGroup.APP_NAVIGATE,
    // CODING
    "grep_file" to ToolGroup.CODING,
    "grep_workspace" to ToolGroup.CODING,
    "get_runtime_errors" to ToolGroup.CODING,
    "validate_html" to ToolGroup.CODING,
    "diff_file" to ToolGroup.CODING,
    // DEVICE
    "get_device_info" to ToolGroup.DEVICE,
    "get_battery_level" to ToolGroup.DEVICE,
    "get_location" to ToolGroup.DEVICE,
    "vibrate" to ToolGroup.DEVICE,
    "write_clipboard" to ToolGroup.DEVICE,
    // NETWORK
    "fetch_url" to ToolGroup.NETWORK,
    "web_search" to ToolGroup.NETWORK,
    "save_data" to ToolGroup.NETWORK,
    "load_data" to ToolGroup.NETWORK,
    // MODULE
    "create_module" to ToolGroup.MODULE,
    "add_module_endpoint" to ToolGroup.MODULE,
    "remove_module_endpoint" to ToolGroup.MODULE,
    "call_module" to ToolGroup.MODULE,
    "list_modules" to ToolGroup.MODULE,
    "update_module" to ToolGroup.MODULE,
    "delete_module" to ToolGroup.MODULE,
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
                    groups.add(ToolGroup.MEMORY)
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
                    groups.addAll(listOf(ToolGroup.MEMORY, ToolGroup.APP_READ, ToolGroup.APP_NAVIGATE, ToolGroup.CODING))
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
