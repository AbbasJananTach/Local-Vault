package com.example.ui.home

import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ThumbnailCacheManager
import com.example.data.VaultProgressState
import com.example.data.VaultRepository
import com.example.data.VaultStorage
import com.example.data.db.FolderItem
import com.example.data.db.VaultItem
import com.example.ui.components.CustomPinKeypadDialog
import com.example.ui.components.CustomPinSetupDialog
import kotlinx.coroutines.launch
import java.io.File
import javax.crypto.SecretKey

enum class NavTab {
    HOME, FAVORITES, FOLDERS
}

/**
 * Point 16: Persistent Sort Mode with 6 explicit sort orders.
 */
enum class SortMode(val label: String) {
    NEWEST("Date Newest"),
    OLDEST("Date Oldest"),
    NAME_AZ("Name A–Z"),
    NAME_ZA("Name Z–A"),
    SIZE_LARGEST("Size Largest"),
    SIZE_SMALLEST("Size Smallest");

    companion object {
        fun fromName(name: String?): SortMode {
            if (name == "SIZE") return SIZE_LARGEST
            return entries.find { it.name == name } ?: NEWEST
        }
    }
}

/**
 * Holds state for an active `CustomPinKeypadDialog` request initiated by `verifyContextualPin`
 * or folder/settings PIN gates.
 */
private data class ContextualPinPrompt(
    val title: String,
    val subtitle: String,
    val doneLabel: String = "Unlock",
    val allowRecoveryPhrase: Boolean = false,
    val onVerifyPin: (String) -> Boolean,
    val onSuccess: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    items: List<VaultItem>,
    folders: List<FolderItem>,
    vaultBlobsDir: File,
    vaultStorage: VaultStorage,
    masterKey: SecretKey,
    isDecoyMode: Boolean,
    defaultGridColumns: Int,
    sortMode: SortMode = SortMode.NEWEST,
    onSortModeChanged: (SortMode) -> Unit = {},
    currentTab: NavTab = NavTab.HOME,
    onTabChange: (NavTab) -> Unit = {},
    openedFolderId: Long? = null,
    onOpenedFolderChange: (Long?) -> Unit = {},
    homeGridState: LazyGridState = rememberLazyGridState(),
    folderGridState: LazyGridState = rememberLazyGridState(),
    foldersGridState: LazyGridState = rememberLazyGridState(),
    showFileNames: Boolean,
    progressState: VaultProgressState = VaultProgressState(),
    onImportFilesSelected: (targetFolderId: Long?) -> Unit,
    onImportDirectFiles: (paths: List<String>, targetFolderId: Long?) -> Unit,
    onCreateFolder: (name: String, isLocked: Boolean, lockType: String, customPin: String?) -> Unit,
    onRenameFolder: (FolderItem, String) -> Unit = { _, _ -> },
    onUpdateFolderPin: (folder: FolderItem, isLocked: Boolean, lockType: String, customPin: String?) -> Unit = { _, _, _, _ -> },
    onDeleteFolder: (FolderItem) -> Unit,
    onVerifyFolderAccess: (FolderItem, String) -> Boolean,
    onVerifyRecoveryPhrase: (String) -> Boolean,
    onVerifyMasterPin: (String) -> Boolean = { false },
    onItemClick: (VaultItem, List<VaultItem>) -> Unit,
    onDeleteSelectedItems: (List<VaultItem>) -> Unit,
    onUnhideSelectedItems: (List<VaultItem>) -> Unit,
    onToggleFavoriteSelected: (List<VaultItem>) -> Unit,
    onMoveSelectedItemsToFolder: (List<VaultItem>, Long?) -> Unit,
    onOpenSettings: () -> Unit,
    onColumnCountChanged: (Int) -> Unit,
    usedStorageBytes: Long
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // Point 16: Strictly 3 Thumbnail Grid Size Levels: Small (4), Medium (3), Large (2)
    var columnCount by remember(defaultGridColumns) {
        mutableIntStateOf(defaultGridColumns.coerceIn(2, 4))
    }
    var showFolderAddOptionsSheet by remember { mutableStateOf(false) }
    var showPhoneImportOptionsSheet by remember { mutableStateOf(false) }
    var showDeviceBrowserSheet by remember { mutableStateOf(false) }
    var showVaultMediaPickerSheet by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var pendingNewFolderNameForPin by remember { mutableStateOf<String?>(null) }
    var showMoveToFolderDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    // Point 15: Top-Bar Search State
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Resolve active openedFolder from hoisted openedFolderId so Back from PhotoViewer/VideoPlayer
    // always returns to the exact folder!
    val openedFolder = remember(folders, openedFolderId) {
        openedFolderId?.let { id -> folders.find { it.id == id } }
    }

    // Folder Long-Press Management State
    var folderLongPressMenuTarget by remember { mutableStateOf<FolderItem?>(null) }
    var folderPendingRename by remember { mutableStateOf<FolderItem?>(null) }
    var folderPendingPinConfig by remember { mutableStateOf<FolderItem?>(null) }
    var folderPendingCustomPinPad by remember { mutableStateOf<FolderItem?>(null) }

    // Active CustomPinKeypadDialog state (Points 7 & 8)
    var activePinPrompt by remember { mutableStateOf<ContextualPinPrompt?>(null) }

    // Multi-Selection, Delete & Unhide Confirmation States (Points 9 & 10)
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedItemIds = remember { mutableStateListOf<Long>() }
    var showDeleteSelectedConfirmDialog by remember { mutableStateOf(false) }
    var showUnhideSelectedConfirmDialog by remember { mutableStateOf(false) }
    var folderPendingConfirmDelete by remember { mutableStateOf<FolderItem?>(null) }

    // Point 1 (Step 3 & 4): Physical File Reconciliation (Truth Check) & Strict De-duplication
    LaunchedEffect(openedFolder?.id, currentTab) {
        vaultStorage.reconcilePhysicalFiles(masterKey)
    }

    val deduplicatedItems = remember(items, vaultBlobsDir) {
        val physicalNames = try {
            vaultBlobsDir.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.name.endsWith(".bin") && it.length() > 0L }
                ?.map { it.name }
                ?.toSet() ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
        items
            .filter { it.vaultFilePath.isNotBlank() && it.vaultFilePath in physicalNames }
            .distinctBy { it.vaultFilePath }
    }

    // Bind all UI file counters strictly to deduplicatedItems
    val totalPhotoCount = remember(deduplicatedItems) {
        deduplicatedItems.count { !it.mimeType.startsWith("video") }
    }
    val totalVideoCount = remember(deduplicatedItems) {
        deduplicatedItems.count { it.mimeType.startsWith("video") }
    }
    val deduplicatedUsedBytes = remember(deduplicatedItems, usedStorageBytes) {
        deduplicatedItems.sumOf { it.fileSize }
    }

    // Handle system Back button when in Selection Mode, Search Mode, or inside a Folder
    BackHandler(enabled = isSelectionMode || isSearchActive || openedFolder != null) {
        when {
            isSelectionMode -> {
                isSelectionMode = false
                selectedItemIds.clear()
            }
            isSearchActive -> {
                isSearchActive = false
                searchQuery = ""
            }
            openedFolder != null -> {
                onOpenedFolderChange(null)
            }
        }
    }

    // Set of locked folder IDs to exclude from public Home / Favorites / Global Search / Add-from-Vault grids
    val lockedFolderIds = remember(folders) {
        folders.filter { it.isLocked }.map { it.id }.toSet()
    }

    // Point 15 & Point 16: Filter & Sort Items based on active view (inside a folder vs general tabs),
    // strictly enforcing Locked Folder Privacy during search and sorting by the persistent SortMode.
    val displayedItems = remember(
        deduplicatedItems,
        currentTab,
        isSearchActive,
        searchQuery,
        sortMode,
        openedFolder,
        lockedFolderIds
    ) {
        val rawScopedList = if (openedFolder != null) {
            // Inside an unlocked folder: strictly items belonging to this open folder
            deduplicatedItems.filter { it.folderId == openedFolder.id }
        } else {
            // Outside folders (Home / Favorites): NEVER include items located inside PIN-locked folders
            val unlockedItems = deduplicatedItems.filter { it.folderId == null || it.folderId !in lockedFolderIds }
            when (currentTab) {
                NavTab.HOME -> unlockedItems
                NavTab.FAVORITES -> unlockedItems.filter { it.isFavorite }
                NavTab.FOLDERS -> emptyList()
            }
        }

        val queryTrimmed = searchQuery.trim()
        val searchFiltered = if (isSearchActive && queryTrimmed.isNotEmpty()) {
            rawScopedList.filter { item ->
                item.fileName.contains(queryTrimmed, ignoreCase = true)
            }
        } else {
            rawScopedList
        }

        val sorted = when (sortMode) {
            SortMode.NEWEST -> searchFiltered.sortedByDescending { it.dateAdded }
            SortMode.OLDEST -> searchFiltered.sortedBy { it.dateAdded }
            SortMode.NAME_AZ -> searchFiltered.sortedBy { it.fileName.lowercase() }
            SortMode.NAME_ZA -> searchFiltered.sortedByDescending { it.fileName.lowercase() }
            SortMode.SIZE_LARGEST -> searchFiltered.sortedByDescending { it.fileSize }
            SortMode.SIZE_SMALLEST -> searchFiltered.sortedBy { it.fileSize }
        }
        sorted.distinctBy { it.vaultFilePath }
    }

    /**
     * Point 8: Central Helper `verifyContextualPin(folderId: String?, onSuccess: () -> Unit)`
     */
    fun verifyContextualPin(
        folderId: String?,
        title: String = "Security Verification",
        onSuccess: () -> Unit
    ) {
        val numericFolderId = folderId?.toLongOrNull()
        val folder = numericFolderId?.let { id -> folders.find { it.id == id } }
        val usesCustomFolderPin = folder != null && folder.hasCustomPin()

        activePinPrompt = ContextualPinPrompt(
            title = if (usesCustomFolderPin) "Unlock '${folder!!.folderName}'" else title,
            subtitle = if (usesCustomFolderPin) {
                "Enter '${folder!!.folderName}' Folder PIN and tap Unlock (✓)"
            } else {
                "Enter your Vault PIN and tap Unlock (✓)"
            },
            doneLabel = "Unlock",
            allowRecoveryPhrase = !isDecoyMode,
            onVerifyPin = { inputPin ->
                if (usesCustomFolderPin) {
                    onVerifyFolderAccess(folder!!, inputPin)
                } else {
                    onVerifyMasterPin(inputPin)
                }
            },
            onSuccess = onSuccess
        )
    }

    val requestFolderPinGate: (FolderItem, () -> Unit) -> Unit = { folder, onSuccess ->
        if (folder.isLocked) {
            activePinPrompt = ContextualPinPrompt(
                title = "Unlock '${folder.folderName}'",
                subtitle = if (folder.hasCustomPin()) {
                    "Enter '${folder.folderName}' Folder PIN and tap Unlock (✓)"
                } else {
                    "Enter your Vault PIN and tap Unlock (✓)"
                },
                doneLabel = "Unlock",
                allowRecoveryPhrase = !isDecoyMode,
                onVerifyPin = { inputPin ->
                    onVerifyFolderAccess(folder, inputPin)
                },
                onSuccess = {
                    VaultRepository.unlockedFolderIds.add(folder.id)
                    onSuccess()
                }
            )
        } else {
            onSuccess()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            Surface(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.padding(24.dp)
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            // Point 12: Stealth Decoy Mode Branding — ALWAYS display "Local Vault"
                            Text(
                                text = "Local Vault",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            // Point 20: Simple everyday English (no technical jargon)
                            Text(
                                text = "Secure Vault Lock • Offline",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // Local Vault Media Summary Widget
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Vault Media Summary",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "$totalPhotoCount Photos • $totalVideoCount Videos • ${folders.size} Folders",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Total Vault Size: ${formatBytes(deduplicatedUsedBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Card(
                        onClick = {
                            scope.launch { drawerState.close() }
                            verifyContextualPin(
                                folderId = null,
                                title = "Unlock Vault Settings",
                                onSuccess = onOpenSettings
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("drawer_settings_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Control Center & Settings",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "Protected by Vault PIN • Themes, Display",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                // Point 14: Clean Top App Bar across Home and Folders with ONLY:
                // Screen/Folder Title, Search icon, Thumbnail Grid Size toggle, and Sort menu icon.
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    title = {
                        if (isSelectionMode) {
                            Text("${selectedItemIds.size} Selected", fontWeight = FontWeight.Bold)
                        } else if (openedFolder != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (openedFolder.isLocked) Icons.Default.Lock else Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = openedFolder.folderName,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            // Point 12: Stealth Decoy Mode Branding — NEVER display "Decoy Gallery" or "Decoy Mode"
                            Text(
                                text = when (currentTab) {
                                    NavTab.HOME -> "Local Vault"
                                    NavTab.FAVORITES -> "Favorites"
                                    NavTab.FOLDERS -> "Folders (${folders.size})"
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    navigationIcon = {
                        if (isSelectionMode) {
                            IconButton(onClick = {
                                isSelectionMode = false
                                selectedItemIds.clear()
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel Selection")
                            }
                        } else if (openedFolder != null) {
                            IconButton(
                                onClick = {
                                    isSearchActive = false
                                    searchQuery = ""
                                    onOpenedFolderChange(null)
                                },
                                modifier = Modifier.testTag("folder_back_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to Folders")
                            }
                        } else {
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.testTag("topbar_menu_button")
                            ) {
                                Icon(Icons.Default.Menu, "Open Navigation Drawer")
                            }
                        }
                    },
                    actions = {
                        if (isSelectionMode) {
                            TextButton(onClick = {
                                if (selectedItemIds.size == displayedItems.size) {
                                    selectedItemIds.clear()
                                } else {
                                    selectedItemIds.clear()
                                    selectedItemIds.addAll(displayedItems.map { it.id })
                                }
                            }) {
                                Text(if (selectedItemIds.size == displayedItems.size) "Deselect All" else "Select All")
                            }
                        } else {
                            // 1. Search Icon (Point 14 & Point 15: Top App Bar on Home and inside every Folder)
                            IconButton(
                                onClick = {
                                    if (currentTab == NavTab.FOLDERS && openedFolder == null) {
                                        onTabChange(NavTab.HOME)
                                        isSearchActive = true
                                    } else {
                                        isSearchActive = !isSearchActive
                                        if (!isSearchActive) {
                                            searchQuery = ""
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("topbar_search_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search",
                                    tint = if (isSearchActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }

                            // 2. Thumbnail Grid Size Toggle (Point 16: Strictly 3 levels — Small=4, Medium=3, Large=2)
                            val gridLevelLabel = when (columnCount) {
                                4 -> "Small"
                                3 -> "Medium"
                                else -> "Large"
                            }
                            IconButton(
                                onClick = {
                                    val next = when (columnCount) {
                                        3 -> 4 // Medium -> Small (4 cols)
                                        4 -> 2 // Small -> Large (2 cols)
                                        else -> 3 // Large -> Medium (3 cols)
                                    }
                                    columnCount = next
                                    onColumnCountChanged(next)
                                },
                                modifier = Modifier.testTag("topbar_grid_size_button")
                            ) {
                                Icon(
                                    Icons.Default.GridView,
                                    contentDescription = "Thumbnail Grid Size ($gridLevelLabel)"
                                )
                            }

                            // 3. Persistent Sort Menu with Checkmark (✓) (Point 16)
                            Box {
                                IconButton(
                                    onClick = { showSortMenu = true },
                                    modifier = Modifier.testTag("topbar_sort_button")
                                ) {
                                    Icon(Icons.Default.Sort, "Sort Options")
                                }
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false }
                                ) {
                                    SortMode.entries.forEach { mode ->
                                        val isCurrentSort = sortMode == mode
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = mode.label,
                                                    fontWeight = if (isCurrentSort) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isCurrentSort) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurface
                                                )
                                            },
                                            trailingIcon = {
                                                if (isCurrentSort) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = "Selected",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            },
                                            onClick = {
                                                onSortModeChanged(mode)
                                                showSortMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                // Point 14 (Step 3): Primary '+' Floating Action Button (FAB) at bottom right
                if (!isSelectionMode) {
                    FloatingActionButton(
                        onClick = {
                            if (openedFolder != null) {
                                // Inside a Folder: open the 2-option modal sheet ("Add from Vault" / "Add from Phone")
                                showFolderAddOptionsSheet = true
                            } else if (currentTab == NavTab.FOLDERS) {
                                // On the Folders tab: create a new folder
                                showCreateFolderDialog = true
                            } else {
                                // On the Home Screen: directly open device media picker to import photos/videos
                                showPhoneImportOptionsSheet = true
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier.testTag("primary_add_fab")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = if (openedFolder != null) "Add to Folder" else "Add Media"
                        )
                    }
                }
            },
            bottomBar = {
                if (isSelectionMode) {
                    Surface(
                        shadowElevation = 12.dp,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                    ) {
                        val selectedList = deduplicatedItems.filter { it.id in selectedItemIds }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp, horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable {
                                        if (selectedList.isNotEmpty()) {
                                            showMoveToFolderDialog = true
                                        }
                                    }
                                    .padding(8.dp)
                                    .testTag("selection_move")
                            ) {
                                Icon(Icons.Default.DriveFileMove, "Move to Folder", tint = MaterialTheme.colorScheme.primary)
                                Text("Move", fontSize = 11.sp)
                            }

                            // Point 10: Clean LockOpen icon for Unhide + Confirmation Dialog + Contextual PIN check
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable {
                                        if (selectedList.isNotEmpty()) {
                                            showUnhideSelectedConfirmDialog = true
                                        }
                                    }
                                    .padding(8.dp)
                                    .testTag("selection_unhide")
                            ) {
                                Icon(Icons.Default.LockOpen, "Unhide Selected", tint = MaterialTheme.colorScheme.secondary)
                                Text("Unhide", fontSize = 11.sp)
                            }

                            // Point 17: Smart Multi-Selection Favorite Toggle
                            val anyUnfavorited = selectedList.any { !it.isFavorite }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable {
                                        if (selectedList.isNotEmpty()) {
                                            onToggleFavoriteSelected(selectedList)
                                            isSelectionMode = false
                                            selectedItemIds.clear()
                                        }
                                    }
                                    .padding(8.dp)
                                    .testTag("selection_favorite")
                            ) {
                                Icon(
                                    Icons.Default.Favorite,
                                    "Favorite Selected",
                                    tint = if (anyUnfavorited) Color(0xFFFF4757) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    if (anyUnfavorited) "Favorite" else "Unfavorite",
                                    fontSize = 11.sp
                                )
                            }

                            // Point 9: Step 1 Confirmation Dialog -> Step 2 Contextual PIN -> Step 3 Permanent Delete
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable {
                                        if (selectedList.isNotEmpty()) {
                                            showDeleteSelectedConfirmDialog = true
                                        }
                                    }
                                    .padding(8.dp)
                                    .testTag("selection_delete")
                            ) {
                                Icon(Icons.Default.Delete, "Delete Selected", tint = MaterialTheme.colorScheme.error)
                                Text("Delete", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                } else {
                    // Point 15: Search removed from bottom bar; clean 3-tab bottom NavigationBar + Settings shortcut
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.navigationBarsPadding()
                    ) {
                        NavigationBarItem(
                            selected = currentTab == NavTab.HOME && openedFolder == null,
                            onClick = {
                                isSearchActive = false
                                searchQuery = ""
                                onOpenedFolderChange(null)
                                onTabChange(NavTab.HOME)
                            },
                            icon = { Icon(Icons.Default.Home, "Home") },
                            label = { Text("Home") },
                            modifier = Modifier.testTag("nav_home")
                        )
                        NavigationBarItem(
                            selected = currentTab == NavTab.FAVORITES && openedFolder == null,
                            onClick = {
                                isSearchActive = false
                                searchQuery = ""
                                onOpenedFolderChange(null)
                                onTabChange(NavTab.FAVORITES)
                            },
                            icon = { Icon(Icons.Default.Favorite, "Favorites") },
                            label = { Text("Favorites") },
                            modifier = Modifier.testTag("nav_favorites")
                        )
                        NavigationBarItem(
                            selected = currentTab == NavTab.FOLDERS || openedFolder != null,
                            onClick = {
                                isSearchActive = false
                                searchQuery = ""
                                onOpenedFolderChange(null)
                                onTabChange(NavTab.FOLDERS)
                            },
                            icon = { Icon(Icons.Default.Folder, "Folders") },
                            label = { Text("Folders") },
                            modifier = Modifier.testTag("nav_folders")
                        )
                        NavigationBarItem(
                            selected = false,
                            onClick = {
                                verifyContextualPin(
                                    folderId = null,
                                    title = "Unlock Vault Settings",
                                    onSuccess = onOpenSettings
                                )
                            },
                            icon = { Icon(Icons.Default.Settings, "Settings") },
                            label = { Text("Settings") },
                            modifier = Modifier.testTag("nav_settings")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Point 15: Top-Bar Search Input with One-Tap 'X' (Clear) Button
                AnimatedVisibility(visible = isSearchActive) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                if (openedFolder != null) "Search inside '${openedFolder.folderName}'..."
                                else "Search unlocked vault files..."
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("search_input_field"),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier.testTag("search_clear_x_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear Search"
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        isSearchActive = false
                                        searchQuery = ""
                                    },
                                    modifier = Modifier.testTag("search_close_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close Search",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    )
                }

                // Point 14 (Step 1): Quick Folder Strip is completely removed from Home Screen!
                // Folders ONLY appear inside the dedicated FOLDERS tab/section.

                // Dedicated FOLDERS Tab View (when not inside a folder)
                if (currentTab == NavTab.FOLDERS && openedFolder == null) {
                    if (folders.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "No Vault Folders Yet",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Create folders with optional individual PIN locks to organize your private media.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                                Button(
                                    onClick = { showCreateFolderDialog = true },
                                    modifier = Modifier.testTag("empty_create_folder_button")
                                ) {
                                    Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Create New Folder")
                                }
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Long-press any folder to Rename, Change PIN, or Delete",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(
                                    onClick = { showCreateFolderDialog = true },
                                    modifier = Modifier.testTag("header_create_folder_button")
                                ) {
                                    Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("New Folder")
                                }
                            }
                            LazyVerticalGrid(
                                state = foldersGridState,
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(folders, key = { it.id }) { folder ->
                                    val folderItemCount = deduplicatedItems.count { it.folderId == folder.id }
                                    FolderCard(
                                        folder = folder,
                                        itemCount = folderItemCount,
                                        onClick = {
                                            requestFolderPinGate(folder) {
                                                isSearchActive = false
                                                searchQuery = ""
                                                onOpenedFolderChange(folder.id)
                                            }
                                        },
                                        onLongClick = {
                                            val vibrator = context.getSystemService(Vibrator::class.java)
                                            vibrator?.vibrate(
                                                VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE)
                                            )
                                            folderLongPressMenuTarget = folder
                                        }
                                    )
                                }
                            }
                        }
                    }
                } else if (displayedItems.isEmpty()) {
                    // EMPTY STATE (for Home, Favorites, Search, or Inside an Open Folder)
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(84.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (openedFolder != null) Icons.Default.Folder else Icons.Default.Photo,
                                    contentDescription = null,
                                    modifier = Modifier.size(42.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = when {
                                    isSearchActive && searchQuery.isNotBlank() -> "No Matching Media"
                                    openedFolder != null -> "'${openedFolder.folderName}' is Empty"
                                    currentTab == NavTab.FAVORITES -> "No Favorite Media Yet"
                                    else -> "Your Vault is Empty"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = when {
                                    isSearchActive && searchQuery.isNotBlank() ->
                                        "No files match '$searchQuery'."
                                    openedFolder != null ->
                                        "Tap the + button to add existing Vault items or import new photos and videos from your phone."
                                    else ->
                                        "Import photos and videos from your phone to keep them protected under your Secure Vault Lock."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            if (!isSearchActive || searchQuery.isBlank()) {
                                Button(
                                    onClick = {
                                        if (openedFolder != null) {
                                            showFolderAddOptionsSheet = true
                                        } else {
                                            showPhoneImportOptionsSheet = true
                                        }
                                    },
                                    modifier = Modifier.testTag("empty_add_media_button")
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(if (openedFolder != null) "Add Media to Folder" else "Add from Phone")
                                }
                            }
                        }
                    }
                } else {
                    // MAIN MEDIA GRID (or Folder Detail Grid)
                    // Point 19: Uses distinct hoisted LazyGridState for Home vs Folder so returning
                    // from PhotoViewer or VideoPlayer preserves the exact folder and scroll position.
                    val activeGridState = if (openedFolder != null) folderGridState else homeGridState
                    LazyVerticalGrid(
                        state = activeGridState,
                        columns = GridCells.Fixed(columnCount),
                        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 88.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(displayedItems, key = { it.id }) { vaultItem ->
                            val isSelected = vaultItem.id in selectedItemIds
                            val blobFile = remember(vaultItem.encryptedRelativePath) {
                                File(vaultBlobsDir, vaultItem.encryptedRelativePath)
                            }
                            MediaThumbnailCard(
                                item = vaultItem,
                                blobFile = blobFile,
                                masterKey = masterKey,
                                showFileName = showFileNames,
                                isSelectionMode = isSelectionMode,
                                isSelected = isSelected,
                                onClick = {
                                    if (isSelectionMode) {
                                        if (isSelected) selectedItemIds.remove(vaultItem.id)
                                        else selectedItemIds.add(vaultItem.id)
                                    } else {
                                        onItemClick(vaultItem, displayedItems)
                                    }
                                },
                                onLongClick = {
                                    val vibrator = context.getSystemService(Vibrator::class.java)
                                    vibrator?.vibrate(
                                        VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
                                    )
                                    isSelectionMode = true
                                    if (vaultItem.id !in selectedItemIds) {
                                        selectedItemIds.add(vaultItem.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Point 14 (Step 3): Inside any Folder, tapping the bottom '+' FAB opens a clean 2-option modal sheet:
    // 1) "Add from Vault" (select existing media from the vault to move/add into this folder)
    // 2) "Add from Phone" (import new photos/videos directly from device storage into this folder)
    if (showFolderAddOptionsSheet && openedFolder != null) {
        val targetFolder = openedFolder
        // Point 18 (Step 2): Exclude all files that belong to any other PIN-locked folder
        val availableVaultCount = remember(deduplicatedItems, targetFolder.id, lockedFolderIds) {
            deduplicatedItems.count { item ->
                item.folderId != targetFolder.id &&
                    (item.folderId == null || item.folderId !in lockedFolderIds)
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showFolderAddOptionsSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "Add to '${targetFolder.folderName}'",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Option 1: "Add from Vault"
                Card(
                    onClick = {
                        showFolderAddOptionsSheet = false
                        showVaultMediaPickerSheet = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_from_vault_option"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.DriveFileMove, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Add from Vault",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "$availableVaultCount AVAILABLE",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                text = "Select existing media from your Vault to move into '${targetFolder.folderName}'",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Option 2: "Add from Phone"
                Card(
                    onClick = {
                        showFolderAddOptionsSheet = false
                        showPhoneImportOptionsSheet = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_from_phone_option"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PhotoLibrary, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "Add from Phone",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Import new photos and videos directly from phone storage into '${targetFolder.folderName}'",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    // Phone Import Sheet (lets user pick via In-App Phone Gallery Browser or System File Picker, jargon-free)
    if (showPhoneImportOptionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPhoneImportOptionsSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(
                    text = if (openedFolder != null) "Add from Phone to '${openedFolder.folderName}'"
                    else "Import Media from Phone",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    onClick = {
                        showPhoneImportOptionsSheet = false
                        showDeviceBrowserSheet = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("browse_device_media_option"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PhotoLibrary, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "Browse Phone Gallery Folders",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Select multiple photos and videos by folder and move them into your Vault",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    onClick = {
                        showPhoneImportOptionsSheet = false
                        onImportFilesSelected(openedFolder?.id)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_files_option"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "Select from Phone Storage",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Open your phone's file picker to select photos or videos",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    // Point 18 (Step 2): In-Vault Existing Media Picker Sheet ("Add from Vault")
    // Strictly excludes/hides all files that belong to any other PIN-locked folder!
    if (showVaultMediaPickerSheet && openedFolder != null) {
        val targetFolder = openedFolder
        val candidateVaultItems = remember(deduplicatedItems, targetFolder.id, lockedFolderIds) {
            deduplicatedItems
                .filter { item ->
                    item.folderId != targetFolder.id &&
                        (item.folderId == null || item.folderId !in lockedFolderIds)
                }
                .sortedByDescending { it.dateAdded }
        }
        VaultExistingMediaPickerSheet(
            candidateItems = candidateVaultItems,
            targetFolderName = targetFolder.folderName,
            vaultBlobsDir = vaultBlobsDir,
            masterKey = masterKey,
            onDismiss = { showVaultMediaPickerSheet = false },
            onConfirmMove = { selectedToMove ->
                showVaultMediaPickerSheet = false
                onMoveSelectedItemsToFolder(selectedToMove, targetFolder.id)
            }
        )
    }

    // In-App Device Media Browser Sheet
    if (showDeviceBrowserSheet) {
        DeviceMediaBrowserSheet(
            vaultStorage = vaultStorage,
            targetFolderName = openedFolder?.folderName,
            onDismiss = { showDeviceBrowserSheet = false },
            onImportFiles = { paths ->
                onImportDirectFiles(paths, openedFolder?.id)
            }
        )
    }

    // Create New Folder Dialog (Point 7: Uses CustomPinSetupDialog 3x4 keypad for custom PINs, never soft keyboard)
    if (showCreateFolderDialog) {
        var folderNameText by remember { mutableStateOf("") }
        var isLockedFolder by remember { mutableStateOf(false) }
        var useCustomPin by remember { mutableStateOf(false) }
        var folderError by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create New Folder", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = folderNameText,
                        onValueChange = {
                            folderNameText = it
                            folderError = ""
                        },
                        label = { Text("Folder Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isLockedFolder = !isLockedFolder }
                    ) {
                        Checkbox(
                            checked = isLockedFolder,
                            onCheckedChange = { isLockedFolder = it }
                        )
                        Text("Lock This Folder (PIN Required)")
                    }
                    if (isLockedFolder) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { useCustomPin = false }
                        ) {
                            RadioButton(
                                selected = !useCustomPin,
                                onClick = { useCustomPin = false }
                            )
                            Text("Use Main Vault PIN", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { useCustomPin = true }
                        ) {
                            RadioButton(
                                selected = useCustomPin,
                                onClick = { useCustomPin = true }
                            )
                            Text("Set Custom Folder PIN (In-App Keypad)", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (folderError.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(folderError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = folderNameText.trim()
                        if (trimmed.isEmpty()) {
                            folderError = "Please enter a folder name"
                            return@Button
                        }
                        showCreateFolderDialog = false
                        if (isLockedFolder && useCustomPin) {
                            pendingNewFolderNameForPin = trimmed
                        } else {
                            onCreateFolder(
                                trimmed,
                                isLockedFolder,
                                "MAIN_PIN",
                                null
                            )
                        }
                    }
                ) {
                    Text(if (isLockedFolder && useCustomPin) "Next: Set PIN" else "Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Step 2 of Create New Folder with Custom PIN: In-App 3x4 Custom Numeric Keypad
    if (pendingNewFolderNameForPin != null) {
        val targetFolderName = pendingNewFolderNameForPin!!
        CustomPinSetupDialog(
            title = "Folder PIN for '$targetFolderName'",
            step1Subtitle = "Enter a 1 to 10 digit Custom Folder PIN, then tap Done (✓)",
            step2Subtitle = "Re-enter the exact same Folder PIN, then tap Done (✓)",
            onPinConfirmed = { customPin ->
                onCreateFolder(
                    targetFolderName,
                    true,
                    "CUSTOM_PIN",
                    customPin
                )
                pendingNewFolderNameForPin = null
                true
            },
            onDismiss = { pendingNewFolderNameForPin = null }
        )
    }

    // Point 11: Folder Long-Press Management Menu (Rename, Change PIN, Delete Folder)
    if (folderLongPressMenuTarget != null) {
        val targetFolder = folderLongPressMenuTarget!!
        val folderItemCount = deduplicatedItems.count { it.folderId == targetFolder.id }

        ModalBottomSheet(
            onDismissRequest = { folderLongPressMenuTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (targetFolder.isLocked) Icons.Default.Lock else Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = targetFolder.folderName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = buildString {
                                append("$folderItemCount item(s)")
                                if (targetFolder.isLocked) {
                                    append(if (targetFolder.hasCustomPin()) " • Custom PIN Lock" else " • Vault PIN Lock")
                                } else {
                                    append(" • Unlocked")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Option 1: Rename Folder (First requires verifyContextualPin(folder.id))
                Card(
                    onClick = {
                        folderLongPressMenuTarget = null
                        verifyContextualPin(
                            folderId = targetFolder.id.toString(),
                            title = "Verify PIN to Rename",
                            onSuccess = {
                                folderPendingRename = targetFolder
                            }
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("folder_menu_rename"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Rename Folder", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(
                                "Verify PIN and update folder name",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Option 2: Change PIN (or Set/Remove Lock)
                Card(
                    onClick = {
                        folderLongPressMenuTarget = null
                        if (targetFolder.isLocked) {
                            activePinPrompt = ContextualPinPrompt(
                                title = "Enter Current Folder PIN",
                                subtitle = if (targetFolder.hasCustomPin()) {
                                    "Enter the current Custom PIN for '${targetFolder.folderName}' and tap Unlock (✓)"
                                } else {
                                    "Enter the current PIN for '${targetFolder.folderName}' and tap Unlock (✓)"
                                },
                                doneLabel = "Unlock",
                                allowRecoveryPhrase = false,
                                onVerifyPin = { inputPin ->
                                    onVerifyFolderAccess(targetFolder, inputPin)
                                },
                                onSuccess = {
                                    folderPendingPinConfig = targetFolder
                                }
                            )
                        } else {
                            verifyContextualPin(
                                folderId = targetFolder.id.toString(),
                                title = "Verify Vault PIN",
                                onSuccess = {
                                    folderPendingCustomPinPad = targetFolder
                                }
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("folder_menu_change_pin"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = if (targetFolder.isLocked) "Change Folder PIN / Lock" else "Set Folder PIN Lock",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = if (targetFolder.isLocked) {
                                    "Requires current Folder PIN before setting a new PIN or removing lock"
                                } else {
                                    "Protect '${targetFolder.folderName}' with its own Custom PIN"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Option 3: Delete Folder (Shows confirmation + requires verifyContextualPin(folder.id))
                Card(
                    onClick = {
                        folderLongPressMenuTarget = null
                        folderPendingConfirmDelete = targetFolder
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("folder_menu_delete"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                "Delete Folder",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                "Delete '${targetFolder.folderName}' and its $folderItemCount file(s) permanently",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    // Point 11: Rename Folder Dialog (shown after verifyContextualPin succeeds)
    if (folderPendingRename != null) {
        val targetFolder = folderPendingRename!!
        var newFolderName by remember(targetFolder) { mutableStateOf(targetFolder.folderName) }
        var renameError by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { folderPendingRename = null },
            title = { Text("Rename Folder", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = {
                            newFolderName = it
                            renameError = ""
                        },
                        label = { Text("New Folder Name") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rename_folder_input")
                    )
                    if (renameError.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(renameError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newFolderName.trim()
                        if (trimmed.isEmpty()) {
                            renameError = "Folder name cannot be empty"
                            return@Button
                        }
                        onRenameFolder(targetFolder, trimmed)
                        folderPendingRename = null
                    }
                ) {
                    Text("Save Name")
                }
            },
            dismissButton = {
                TextButton(onClick = { folderPendingRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Point 11: Folder PIN Configuration Dialog (shown ONLY after existing Folder PIN was verified)
    if (folderPendingPinConfig != null) {
        val targetFolder = folderPendingPinConfig!!
        AlertDialog(
            onDismissRequest = { folderPendingPinConfig = null },
            title = { Text("Update Lock for '${targetFolder.folderName}'", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Current Folder PIN verified. Choose how you want to update '${targetFolder.folderName}':",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            folderPendingPinConfig = null
                            folderPendingCustomPinPad = targetFolder
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Key, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enter New Custom Folder PIN")
                    }
                    OutlinedButton(
                        onClick = {
                            onUpdateFolderPin(targetFolder, false, "MAIN_PIN", null)
                            folderPendingPinConfig = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.LockOpen, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Remove Folder PIN Lock")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { folderPendingPinConfig = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Point 11 & Point 7: Set/Change Folder Custom PIN using In-App 3x4 Numeric Keypad
    if (folderPendingCustomPinPad != null) {
        val targetFolder = folderPendingCustomPinPad!!
        CustomPinSetupDialog(
            title = "New PIN for '${targetFolder.folderName}'",
            step1Subtitle = "Enter a new 1 to 10 digit Folder PIN, then tap Done (✓)",
            step2Subtitle = "Re-enter the new Folder PIN to confirm, then tap Done (✓)",
            onPinConfirmed = { newPin ->
                onUpdateFolderPin(targetFolder, true, "CUSTOM_PIN", newPin)
                folderPendingCustomPinPad = null
                true
            },
            onDismiss = { folderPendingCustomPinPad = null }
        )
    }

    // Point 18 (Step 1): Scrollable "Move to Folder" List wrapped in LazyColumn with Modifier.heightIn(max = 360.dp)
    if (showMoveToFolderDialog) {
        val selectedList = deduplicatedItems.filter { it.id in selectedItemIds }
        AlertDialog(
            onDismissRequest = { showMoveToFolderDialog = false },
            title = { Text("Move ${selectedList.size} Item(s) To", fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "root_vault") {
                        Card(
                            onClick = {
                                onMoveSelectedItemsToFolder(selectedList, null)
                                showMoveToFolderDialog = false
                                isSelectionMode = false
                                selectedItemIds.clear()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Home, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Main Vault (Home Root)", fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    items(folders, key = { it.id }) { folder ->
                        Card(
                            onClick = {
                                onMoveSelectedItemsToFolder(selectedList, folder.id)
                                showMoveToFolderDialog = false
                                isSelectionMode = false
                                selectedItemIds.clear()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (folder.isLocked) Icons.Default.Lock else Icons.Default.Folder,
                                    null,
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(folder.folderName, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMoveToFolderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Point 10: Step 1 Confirmation Dialog before Unhiding selected items -> Step 2 Contextual PIN Gate
    if (showUnhideSelectedConfirmDialog) {
        val selectedList = deduplicatedItems.filter { it.id in selectedItemIds }
        AlertDialog(
            onDismissRequest = { showUnhideSelectedConfirmDialog = false },
            title = { Text("Unhide Selected Media?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Unhide and restore ${selectedList.size} selected media item(s) to your phone?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val itemsToUnhide = selectedList.toList()
                        val contextFolderId = openedFolder?.id?.toString()
                        showUnhideSelectedConfirmDialog = false
                        verifyContextualPin(
                            folderId = contextFolderId,
                            title = "Confirm Unhide Media",
                            onSuccess = {
                                onUnhideSelectedItems(itemsToUnhide)
                                isSelectionMode = false
                                selectedItemIds.clear()
                            }
                        )
                    }
                ) {
                    Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Verify PIN & Unhide")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnhideSelectedConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Point 9: Step 1 Confirmation Dialog before Deleting selected items -> Step 2 Contextual PIN Gate -> Step 3 Permanent Delete
    if (showDeleteSelectedConfirmDialog) {
        val selectedList = deduplicatedItems.filter { it.id in selectedItemIds }
        AlertDialog(
            onDismissRequest = { showDeleteSelectedConfirmDialog = false },
            title = { Text("Delete selected item(s) permanently?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Are you sure you want to delete ${selectedList.size} selected item(s) permanently from your Vault? This action cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val itemsToDelete = selectedList.toList()
                        val contextFolderId = openedFolder?.id?.toString()
                        showDeleteSelectedConfirmDialog = false
                        verifyContextualPin(
                            folderId = contextFolderId,
                            title = "Delete Permanently",
                            onSuccess = {
                                onDeleteSelectedItems(itemsToDelete)
                                isSelectionMode = false
                                selectedItemIds.clear()
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Point 9 & Point 11: Step 1 Confirmation Dialog before Deleting a Folder -> Step 2 Contextual PIN Gate -> Step 3 Delete
    if (folderPendingConfirmDelete != null) {
        val folderToDelete = folderPendingConfirmDelete!!
        val countInFolder = deduplicatedItems.count { it.folderId == folderToDelete.id }
        AlertDialog(
            onDismissRequest = { folderPendingConfirmDelete = null },
            title = { Text("Delete Folder '${folderToDelete.folderName}' permanently?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This will permanently delete the folder '${folderToDelete.folderName}' and all $countInFolder file(s) inside it. This action cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        folderPendingConfirmDelete = null
                        verifyContextualPin(
                            folderId = folderToDelete.id.toString(),
                            title = "Confirm Delete '${folderToDelete.folderName}'",
                            onSuccess = {
                                if (openedFolder?.id == folderToDelete.id) {
                                    onOpenedFolderChange(null)
                                }
                                onDeleteFolder(folderToDelete)
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { folderPendingConfirmDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Point 7 & Point 8: Reusable In-App CustomPinKeypadDialog (no system keyboard, mandatory Unlock button)
    if (activePinPrompt != null) {
        val prompt = activePinPrompt!!
        CustomPinKeypadDialog(
            title = prompt.title,
            subtitle = prompt.subtitle,
            doneButtonLabel = prompt.doneLabel,
            onVerifyPin = prompt.onVerifyPin,
            onSuccess = {
                val callback = prompt.onSuccess
                activePinPrompt = null
                callback()
            },
            onDismiss = { activePinPrompt = null },
            onVerifyRecoveryPhrase = if (prompt.allowRecoveryPhrase) onVerifyRecoveryPhrase else null
        )
    }

    // Unified Task-Specific Progress Dialog with Live Percentage (Point 4)
    if (progressState.isVisible) {
        VaultProgressDialog(progressState = progressState)
    }
}

/**
 * Point 4 & Point 20: Unified Task-Specific Progress Dialog with Live Percentage (%) and clean language.
 */
@Composable
fun VaultProgressDialog(progressState: VaultProgressState) {
    if (!progressState.isVisible) return

    val clampedPct = progressState.percentage.coerceIn(0, 100)
    AlertDialog(
        onDismissRequest = {},
        shape = RoundedCornerShape(18.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = progressState.title.ifBlank { "Importing Media..." },
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$clampedPct%",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LinearProgressIndicator(
                    progress = { (clampedPct / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = progressState.subText.ifBlank {
                            if (progressState.totalItems > 0) {
                                "${progressState.currentItem} / ${progressState.totalItems} files"
                            } else {
                                "Processing..."
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Secure Vault Lock",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        },
        confirmButton = {}
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaThumbnailCard(
    item: VaultItem,
    blobFile: File,
    masterKey: SecretKey,
    showFileName: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var thumbnail by remember(item.id) {
        mutableStateOf<ImageBitmap?>(ThumbnailCacheManager.getCached(item.id))
    }

    LaunchedEffect(item.id, blobFile) {
        if (thumbnail == null) {
            thumbnail = ThumbnailCacheManager.loadThumbnail(item, blobFile, masterKey)
        }
    }

    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (isSelected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                else Modifier
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail!!,
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = if (item.mimeType.startsWith("video")) Icons.Default.Movie else Icons.Default.Photo,
                    contentDescription = null,
                    modifier = Modifier
                        .size(34.dp)
                        .align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
            }

            // Video Play Badge & Duration
            if (item.mimeType.startsWith("video")) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Video",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (item.durationMs > 0) formatDurationShort(item.durationMs) else "VIDEO",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Favorite Heart Indicator
            if (item.isFavorite && !isSelectionMode) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = "Favorite",
                    tint = Color(0xFFFF4757),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(16.dp)
                )
            }

            // Optional File Name Overlay at Bottom
            if (showFileName) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                            )
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = item.fileName,
                        color = Color.White,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Multi-Selection Checkbox Overlay
            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                            else Color.Transparent
                        )
                )
                Icon(
                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderCard(
    folder: FolderItem,
    itemCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(116.dp)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("folder_card_${folder.folderName}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.align(Alignment.CenterStart)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (folder.isLocked) Icons.Default.Lock else Icons.Default.Folder,
                            contentDescription = null,
                            tint = if (folder.isLocked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    if (folder.isLocked) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (folder.hasCustomPin()) "CUSTOM PIN" else "LOCKED",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = folder.folderName,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (folder.isLocked) "PIN Required • Long-press to manage" else "$itemCount items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultExistingMediaPickerSheet(
    candidateItems: List<VaultItem>,
    targetFolderName: String,
    vaultBlobsDir: File,
    masterKey: SecretKey,
    onDismiss: () -> Unit,
    onConfirmMove: (List<VaultItem>) -> Unit
) {
    val selectedIds = remember { mutableStateListOf<Long>() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(600.dp)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Add from Vault to '$targetFolderName'",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Select unlocked Vault photos and videos to move into this folder",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (candidateItems.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            if (selectedIds.size == candidateItems.size) {
                                selectedIds.clear()
                            } else {
                                selectedIds.clear()
                                selectedIds.addAll(candidateItems.map { it.id })
                            }
                        }
                    ) {
                        Text(if (selectedIds.size == candidateItems.size) "Deselect All" else "Select All")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (candidateItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No unlocked Vault media available outside '$targetFolderName'.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(candidateItems, key = { it.id }) { item ->
                        val isSelected = item.id in selectedIds
                        val blobFile = remember(item.encryptedRelativePath) {
                            File(vaultBlobsDir, item.encryptedRelativePath)
                        }
                        MediaThumbnailCard(
                            item = item,
                            blobFile = blobFile,
                            masterKey = masterKey,
                            showFileName = true,
                            isSelectionMode = true,
                            isSelected = isSelected,
                            onClick = {
                                if (isSelected) selectedIds.remove(item.id)
                                else selectedIds.add(item.id)
                            },
                            onLongClick = {
                                if (isSelected) selectedIds.remove(item.id)
                                else selectedIds.add(item.id)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    val chosen = candidateItems.filter { it.id in selectedIds }
                    if (chosen.isNotEmpty()) {
                        onConfirmMove(chosen)
                    }
                },
                enabled = selectedIds.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .height(50.dp)
                    .testTag("confirm_move_from_vault_button")
            ) {
                Icon(Icons.Default.DriveFileMove, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (selectedIds.isEmpty()) "Select Vault Media to Move"
                    else "Move ${selectedIds.size} Item(s) to '$targetFolderName'",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun formatDurationShort(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format("%02d:%02d", m, s)
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    if (mb >= 1024.0) {
        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }
    return String.format("%.1f MB", mb)
}
