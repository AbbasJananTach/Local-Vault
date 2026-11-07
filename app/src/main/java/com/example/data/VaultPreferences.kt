package com.example.data

import android.content.Context
import android.os.Environment
import com.example.ui.theme.VaultThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class AutoLockMode(val label: String, val timeoutMs: Long) {
    IMMEDIATE("Immediately on Minimize", 0L),
    AFTER_30S("After 30 Seconds", 30_000L),
    AFTER_1M("After 1 Minute", 60_000L),
    SCREEN_OFF_ONLY("Only on Screen Off", -1L);

    companion object {
        fun fromName(name: String?): AutoLockMode {
            return entries.find { it.name == name } ?: AFTER_30S
        }
    }
}

class VaultPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("local_vault_prefs", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        VaultThemeMode.fromName(prefs.getString(KEY_THEME_MODE, VaultThemeMode.MIDNIGHT_VIOLET.name))
    )
    val themeMode: StateFlow<VaultThemeMode> = _themeMode.asStateFlow()

    private val _autoLockMode = MutableStateFlow(
        AutoLockMode.fromName(prefs.getString(KEY_AUTO_LOCK_MODE, AutoLockMode.AFTER_30S.name))
    )
    val autoLockMode: StateFlow<AutoLockMode> = _autoLockMode.asStateFlow()

    private val _bruteForceLockoutEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_BRUTE_FORCE_LOCKOUT, true)
    )
    val bruteForceLockoutEnabled: StateFlow<Boolean> = _bruteForceLockoutEnabled.asStateFlow()

    private val _defaultGridColumns = MutableStateFlow(
        prefs.getInt(KEY_DEFAULT_GRID_COLUMNS, 3).coerceIn(2, 5)
    )
    val defaultGridColumns: StateFlow<Int> = _defaultGridColumns.asStateFlow()

    private val _showFileNamesOnGrid = MutableStateFlow(
        prefs.getBoolean(KEY_SHOW_FILE_NAMES, true)
    )
    val showFileNamesOnGrid: StateFlow<Boolean> = _showFileNamesOnGrid.asStateFlow()

    private val _autoHideEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_HIDE_ENABLED, false)
    )
    val autoHideEnabled: StateFlow<Boolean> = _autoHideEnabled.asStateFlow()

    private val _watchedFolders = MutableStateFlow<Set<String>>(
        prefs.getStringSet(KEY_WATCHED_FOLDERS, defaultWatchedFolders()) ?: defaultWatchedFolders()
    )
    val watchedFolders: StateFlow<Set<String>> = _watchedFolders.asStateFlow()

    fun setThemeMode(mode: VaultThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    fun setAutoLockMode(mode: AutoLockMode) {
        prefs.edit().putString(KEY_AUTO_LOCK_MODE, mode.name).apply()
        _autoLockMode.value = mode
    }

    fun setBruteForceLockoutEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BRUTE_FORCE_LOCKOUT, enabled).apply()
        _bruteForceLockoutEnabled.value = enabled
    }

    fun setDefaultGridColumns(columns: Int) {
        val valid = columns.coerceIn(2, 5)
        prefs.edit().putInt(KEY_DEFAULT_GRID_COLUMNS, valid).apply()
        _defaultGridColumns.value = valid
    }

    fun setShowFileNamesOnGrid(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_FILE_NAMES, show).apply()
        _showFileNamesOnGrid.value = show
    }

    fun setAutoHideEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_HIDE_ENABLED, enabled).apply()
        _autoHideEnabled.value = enabled
    }

    fun toggleWatchedFolder(path: String) {
        val current = _watchedFolders.value.toMutableSet()
        if (current.contains(path)) {
            current.remove(path)
        } else {
            current.add(path)
        }
        prefs.edit().putStringSet(KEY_WATCHED_FOLDERS, current).apply()
        _watchedFolders.value = current
    }

    fun addCustomWatchedFolder(path: String) {
        val clean = path.trim()
        if (clean.isEmpty()) return
        val current = _watchedFolders.value.toMutableSet()
        current.add(clean)
        prefs.edit().putStringSet(KEY_WATCHED_FOLDERS, current).apply()
        _watchedFolders.value = current
    }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_AUTO_LOCK_MODE = "auto_lock_mode"
        private const val KEY_BRUTE_FORCE_LOCKOUT = "brute_force_lockout"
        private const val KEY_DEFAULT_GRID_COLUMNS = "default_grid_columns"
        private const val KEY_SHOW_FILE_NAMES = "show_file_names"
        private const val KEY_AUTO_HIDE_ENABLED = "auto_hide_enabled"
        private const val KEY_WATCHED_FOLDERS = "watched_folders"

        fun getPresetWatchableFolders(): List<Pair<String, String>> {
            val ext = Environment.getExternalStorageDirectory().absolutePath
            return listOf(
                "Camera (DCIM)" to "$ext/DCIM/Camera",
                "Screenshots" to "$ext/Pictures/Screenshots",
                "Downloads" to "$ext/Download",
                "WhatsApp Images" to "$ext/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
                "WhatsApp Video" to "$ext/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video"
            )
        }

        private fun defaultWatchedFolders(): Set<String> {
            val ext = Environment.getExternalStorageDirectory().absolutePath
            return setOf("$ext/DCIM/Camera")
        }
    }
}
