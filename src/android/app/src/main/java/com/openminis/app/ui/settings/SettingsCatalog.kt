package com.openminis.app.ui.settings

import androidx.annotation.StringRes
import com.openminis.app.R
import com.openminis.app.navigation.Routes

/** One catalog for category navigation, restored routes and feature reachability. */
internal enum class SettingsDestination(
    @StringRes val titleRes: Int,
    @StringRes val helpRes: Int? = null,
    val route: String? = null,
    val searchRes: List<Int> = emptyList(),
) {
    PROVIDERS(R.string.settings_manage_providers, R.string.settings_manage_providers_subtitle, Routes.PROVIDER_LIST),
    CLIENT_DISGUISE(R.string.client_disguise_title, R.string.client_disguise_subtitle, Routes.CLIENT_DISGUISE),
    MODEL_GROUPS(R.string.settings_model_groups, R.string.settings_model_groups_subtitle, Routes.MODEL_GROUPS),
    USAGE(R.string.settings_token_usage, R.string.settings_token_usage_subtitle, Routes.USAGE_STATS),
    GATEWAY(R.string.provider_type_gateway, R.string.add_provider_subtitle_gateway, Routes.GATEWAY_SETTINGS),
    A2A(R.string.settings_a2a_gateway, R.string.settings_a2a_gateway_subtitle),
    RUNTIME(
        R.string.settings_section_agent_runtime,
        R.string.settings_compact_threshold_subtitle,
        Routes.AGENT_RUNTIME,
        listOf(R.string.settings_compact_threshold, R.string.settings_auto_retry,
            R.string.settings_auto_retry_attempts, R.string.settings_auto_retry_interval,
            R.string.settings_auto_new_session, R.string.settings_auto_new_session_threshold),
    ),
    MULTI_AGENT(R.string.settings_multi_agent, R.string.settings_multi_agent_subtitle, Routes.MULTI_AGENT),
    WEB_SEARCH(R.string.settings_web_search, R.string.settings_web_search_subtitle, Routes.WEB_SEARCH),
    LIFECYCLE_HOOKS(R.string.settings_lifecycle_hooks, R.string.settings_lifecycle_hooks_subtitle, Routes.LIFECYCLE_HOOKS),
    TRANSLATION(R.string.settings_translate, R.string.settings_translate_subtitle, Routes.TRANSLATE),
    SOUL(R.string.settings_soul, R.string.settings_soul_subtitle, Routes.SOUL),
    MEMORY(R.string.settings_memory, R.string.settings_memory_subtitle, Routes.MEMORY),
    TERMINAL(R.string.sessionlist_shell_terminal, route = Routes.terminal()),
    SSH_WORKSPACE(R.string.ssh_workspace_menu_title, R.string.ssh_hosts_row_subtitle),
    BROWSER(R.string.sessionlist_open_browser, route = Routes.HOME_BROWSER),
    BROWSER_SETTINGS(R.string.sessionlist_browser_settings, route = Routes.HOME_BROWSER_SETTINGS),
    SKILLS(R.string.settings_skills, R.string.settings_skills_subtitle, Routes.SKILLS),
    MCP(R.string.settings_mcp, R.string.settings_mcp_subtitle, Routes.MCP),
    PLUGINS(R.string.plugin_market_title, R.string.plugin_market_subtitle, Routes.PLUGIN_MARKET),
    NETWORK(R.string.settings_network_title, R.string.settings_network_subtitle, Routes.NETWORK),
    ENV_VARS(R.string.settings_env_vars, R.string.settings_env_vars_subtitle, Routes.ENV_VARS),
    SSH_HOSTS(R.string.ssh_hosts_title, R.string.settings_ssh_subtitle, Routes.SSH_HOSTS),
    PERMISSIONS(R.string.settings_section_permissions, R.string.settings_permissions_subtitle, Routes.PERMISSIONS),
    BACKGROUND(R.string.bg_section_header, R.string.bg_section_subtitle, Routes.BACKGROUND),
    PRIVACY_POLICY(R.string.settings_privacy_policy),
    STORAGE(R.string.settings_section_storage, R.string.settings_storage_subtitle, Routes.STORAGE),
    SHARED_FOLDERS(R.string.settings_shared_folders, R.string.settings_shared_folders_subtitle, Routes.SHARED_FOLDERS),
    MOUNTED_FOLDERS(R.string.settings_mount_external_folders, R.string.settings_mount_external_folders_subtitle, Routes.MOUNTED_FOLDERS),
    BACKUP(R.string.settings_backup_restore, R.string.settings_backup_restore_subtitle, Routes.BACKUP),
    LOGS(R.string.settings_section_logs, R.string.settings_logs_subtitle, Routes.LOGS),
    ABOUT(R.string.settings_about_minis, R.string.settings_about_subtitle, Routes.ABOUT),
    FEEDBACK(R.string.settings_feedback),
}

internal enum class SettingsCategory(
    val id: String,
    @StringRes val titleRes: Int,
    val groups: List<List<SettingsDestination>>,
) {
    MODELS("models", R.string.settings_nav_models, listOf(
        listOf(SettingsDestination.PROVIDERS, SettingsDestination.MODEL_GROUPS, SettingsDestination.USAGE),
        listOf(SettingsDestination.GATEWAY, SettingsDestination.A2A, SettingsDestination.CLIENT_DISGUISE),
    )),
    AGENT("agent", R.string.settings_nav_agent, listOf(
        listOf(SettingsDestination.RUNTIME, SettingsDestination.MULTI_AGENT, SettingsDestination.SOUL, SettingsDestination.LIFECYCLE_HOOKS),
        listOf(SettingsDestination.MEMORY, SettingsDestination.WEB_SEARCH, SettingsDestination.TRANSLATION),
    )),
    TOOLS("tools", R.string.settings_section_tools, listOf(
        listOf(SettingsDestination.TERMINAL, SettingsDestination.SSH_WORKSPACE, SettingsDestination.BROWSER, SettingsDestination.BROWSER_SETTINGS),
        listOf(SettingsDestination.SKILLS, SettingsDestination.MCP, SettingsDestination.PLUGINS),
    )),
    CONNECTIONS("connections", R.string.settings_group_connections, listOf(
        listOf(SettingsDestination.NETWORK, SettingsDestination.ENV_VARS, SettingsDestination.SSH_HOSTS),
    )),
    SECURITY("security", R.string.settings_section_permissions, listOf(
        listOf(SettingsDestination.PERMISSIONS, SettingsDestination.BACKGROUND),
        listOf(SettingsDestination.PRIVACY_POLICY),
    )),
    DATA("data", R.string.settings_nav_data, listOf(
        listOf(SettingsDestination.STORAGE, SettingsDestination.SHARED_FOLDERS, SettingsDestination.MOUNTED_FOLDERS, SettingsDestination.BACKUP),
    )),
    SYSTEM("system", R.string.settings_nav_system, listOf(
        listOf(SettingsDestination.LOGS, SettingsDestination.ABOUT, SettingsDestination.FEEDBACK),
    ));

    companion object {
        fun fromId(id: String?): SettingsCategory? = entries.firstOrNull { it.id == id }
    }
}
