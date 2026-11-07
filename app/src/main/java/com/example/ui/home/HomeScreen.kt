package com.example.ui.home

import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Unpublished
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ThumbnailCacheManager
import com.example.data.db.FolderItem
import com.example.data.db.VaultItem
import kotlinx.coroutines.launch
import java.io.File
import javax.crypto.SecretKey

enum class NavTab {
    HOME, FAVORITES, FOLDERS, SEARCH
}

enum class SortMode {
    NEWEST, OLDEST, NAME_AZ, NAME_ZA, SIZE
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    items: List<VaultItem>,
    folders: List<FolderItem>,
    vaultBlobsDir: File,
    masterKey: SecretKey,
    isDecoyMode: Boolean,
    defaultGridColumns: Int,
    showFileNames: Boolean,
    isImporting: Boolean,
    importProgressText: String,
    onImportFilesSelected: (targetFolderId: Long?) -> Unit,
    onCreateFolder: (name: String, isLocked: Boolean, lockType: String, customPin: String?) -> Unit,
    onDeleteFolder: (FolderItem) -> Unit,
    onVerifyFolderAccess: (FolderItem, String) -> Boolean,
    onVerifyRecoveryPhrase: (String) -> Boolean,
    onItemClick: (VaultItem) -> Unit,
    onDeleteSelectedItems: (List<VaultItem>) -> Unit,
    onUnhideSelectedItems: (List<VaultItem>) -> Unit,
    onToggleFavoriteSelected: (List<VaultItem>) -> Unit,
    onMoveSelectedItemsToFolder: (List<VaultItem>, Long?) -> Unit,
    onOpenSettings: () -> Unit,
    onColumnCountChanged: (Int) -> Unit,
    usedStorageBytes: Long,
    totalStorageBytes: Long
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var currentTab by remember { mutableStateOf(NavTab.HOME) }
    var sortMode by remember { mutableStateOf(SortMode.NEWEST) }
    var columnCount by remember(defaultGridColumns) { mutableIntStateOf(defaultGridColumns) }
    var showPlusBottomSheet by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showMoveToFolderDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Folder Navigation & PIN Gate State
    var openedFolder by remember { mutableStateOf<FolderItem?>(null) }
    var pendingPinFolder by remember { mutableStateOf<FolderItem?>(null) }
    var pendingActionAfterPin by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Multi-Selection State
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedItemIds = remember { mutableStateListOf<Long>() }

    // Handle system Back button when inside a Folder or Selection Mode
    BackHandler(enabled = isSelectionMode || openedFolder != null) {
        if (isSelectionMode) {
            isSelectionMode = false
            selectedItemIds.clear()
        } else if (openedFolder != null) {
            openedFolder = null
        }
    }

    // Set of locked folder IDs to exclude from public Home / Favorites / Search grids
    val lockedFolderIds = remember(folders) {
        folders.filter { it.isLocked }.map { it.id }.toSet()
    }

    // Filter & Sort Items based on active view (inside a folder vs general tabs)
    val displayedItems = remember(items, currentTab, searchQuery, sortMode, openedFolder, lockedFolderIds) {
        val baseList = if (openedFolder != null) {
            items.filter { it.folderId == openedFolder!!.id }
        } else {
            val unlockedItems = items.filter { it.folderId == null || it.folderId !in lockedFolderIds }
            when (currentTab) {
                NavTab.HOME -> unlockedItems
                NavTab.FAVORITES -> unlockedItems.filter { it.isFavorite }
                NavTab.SEARCH -> unlockedItems.filter {
                    it.fileName.contains(searchQuery, ignoreCase = true) ||
                        it.mimeType.contains(searchQuery, ignoreCase = true)
                }
                NavTab.FOLDERS -> emptyList()
            }
        }

        when (sortMode) {
            SortMode.NEWEST -> baseList.sortedByDescending { it.dateAdded }
            SortMode.OLDEST -> baseList.sortedBy { it.dateAdded }
            SortMode.NAME_AZ -> baseList.sortedBy { it.fileName.lowercase() }
            SortMode.NAME_ZA -> baseList.sortedByDescending { it.fileName.lowercase() }
            SortMode.SIZE -> baseList.sortedByDescending { it.fileSize }
        }
    }

    val requestFolderPinGate: (FolderItem, () -> Unit) -> Unit = { folder, onSuccess ->
        if (folder.isLocked) {
            pendingPinFolder = folder
            pendingActionAfterPin = onSuccess
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
                            Text(
                                text = if (isDecoyMode) "Decoy Gallery" else "Local Vault",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "AES-256-CTR • Offline",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // Storage Information Widget
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Encrypted Storage",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val ratio = if (totalStorageBytes > 0) {
                                (usedStorageBytes.toFloat() / (usedStorageBytes + totalStorageBytes).toFloat()).coerceIn(0.02f, 1f)
                            } else 0.05f
                            LinearProgressIndicator(
                                progress = { ratio },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Vault: ${formatBytes(usedStorageBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Free: ${formatBytes(totalStorageBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${items.size} encrypted files • ${folders.size} folders",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Card(
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenSettings()
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
                                    "PIN, Themes, Auto-Lock, Backup",
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
                                    imageVector = if (openedFolder!!.isLocked) Icons.Default.Lock else Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = openedFolder!!.folderName,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            Text(
                                text = when (currentTab) {
                                    NavTab.HOME -> if (isDecoyMode) "Decoy Gallery" else "Local Vault"
                                    NavTab.FAVORITES -> "Favorites"
                                    NavTab.FOLDERS -> "Folders (${folders.size})"
                                    NavTab.SEARCH -> "Search Vault"
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
                                onClick = { openedFolder = null },
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
                            if (openedFolder != null) {
                                // Add media directly to this open folder
                                IconButton(
                                    onClick = { onImportFilesSelected(openedFolder!!.id) },
                                    modifier = Modifier.testTag("folder_add_media_button")
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Add Media to Folder",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                // Delete this folder
                                IconButton(
                                    onClick = {
                                        val folderToDelete = openedFolder!!
                                        requestFolderPinGate(folderToDelete) {
                                            openedFolder = null
                                            onDeleteFolder(folderToDelete)
                                        }
                                    },
                                    modifier = Modifier.testTag("delete_folder_button")
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete Folder",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            // Column Resizer (2, 3, 4, 5)
                            IconButton(onClick = {
                                val next = if (columnCount >= 5) 2 else columnCount + 1
                                columnCount = next
                                onColumnCountChanged(next)
                            }) {
                                Icon(Icons.Default.GridView, "Toggle Grid Columns ($columnCount)")
                            }

                            // Sort Menu
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(Icons.Default.Sort, "Sort Options")
                                }
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Newest First") },
                                        onClick = { sortMode = SortMode.NEWEST; showSortMenu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Oldest First") },
                                        onClick = { sortMode = SortMode.OLDEST; showSortMenu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Name (A-Z)") },
                                        onClick = { sortMode = SortMode.NAME_AZ; showSortMenu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Name (Z-A)") },
                                        onClick = { sortMode = SortMode.NAME_ZA; showSortMenu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("File Size") },
                                        onClick = { sortMode = SortMode.SIZE; showSortMenu = false }
                                    )
                                }
                            }

                            IconButton(
                                onClick = onOpenSettings,
                                modifier = Modifier.testTag("topbar_settings_button")
                            ) {
                                Icon(Icons.Default.Settings, "Settings")
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (isSelectionMode) {
                    Surface(
                        shadowElevation = 12.dp,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        val selectedList = items.filter { it.id in selectedItemIds }
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
                                    .clickable { showMoveToFolderDialog = true }
                                    .padding(8.dp)
                                    .testTag("selection_move")
                            ) {
                                Icon(Icons.Default.DriveFileMove, "Move to Folder", tint = MaterialTheme.colorScheme.primary)
                                Text("Move", fontSize = 11.sp)
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable {
                                        val action = {
                                            onUnhideSelectedItems(selectedList)
                                            isSelectionMode = false
                                            selectedItemIds.clear()
                                        }
                                        if (openedFolder != null && openedFolder!!.isLocked) {
                                            requestFolderPinGate(openedFolder!!, action)
                                        } else {
                                            action()
                                        }
                                    }
                                    .padding(8.dp)
                                    .testTag("selection_unhide")
                            ) {
                                Icon(Icons.Default.Unpublished, "Unhide Selected", tint = MaterialTheme.colorScheme.secondary)
                                Text("Unhide", fontSize = 11.sp)
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable {
                                        onToggleFavoriteSelected(selectedList)
                                        isSelectionMode = false
                                        selectedItemIds.clear()
                                    }
                                    .padding(8.dp)
                                    .testTag("selection_favorite")
                            ) {
                                Icon(Icons.Default.Favorite, "Favorite Selected", tint = Color(0xFFFF4757))
                                Text("Favorite", fontSize = 11.sp)
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable {
                                        val action = {
                                            onDeleteSelectedItems(selectedList)
                                            isSelectionMode = false
                                            selectedItemIds.clear()
                                        }
                                        if (openedFolder != null && openedFolder!!.isLocked) {
                                            requestFolderPinGate(openedFolder!!, action)
                                        } else {
                                            action()
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
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        NavigationBarItem(
                            selected = currentTab == NavTab.HOME && openedFolder == null,
                            onClick = {
                                openedFolder = null
                                currentTab = NavTab.HOME
                            },
                            icon = { Icon(Icons.Default.Home, "Home") },
                            label = { Text("Home") },
                            modifier = Modifier.testTag("nav_home")
                        )
                        NavigationBarItem(
                            selected = currentTab == NavTab.FAVORITES && openedFolder == null,
                            onClick = {
                                openedFolder = null
                                currentTab = NavTab.FAVORITES
                            },
                            icon = { Icon(Icons.Default.Favorite, "Favorites") },
                            label = { Text("Favorites") },
                            modifier = Modifier.testTag("nav_favorites")
                        )
                        NavigationBarItem(
                            selected = false,
                            onClick = { showPlusBottomSheet = true },
                            icon = {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.linearGradient(
                                                listOf(
                                                    MaterialTheme.colorScheme.primary,
                                                    MaterialTheme.colorScheme.secondary
                                                )
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Add, "Import / Add", tint = Color.White)
                                }
                            },
                            label = { Text("Add") },
                            modifier = Modifier.testTag("nav_add_center")
                        )
                        NavigationBarItem(
                            selected = currentTab == NavTab.FOLDERS || openedFolder != null,
                            onClick = {
                                openedFolder = null
                                currentTab = NavTab.FOLDERS
                            },
                            icon = { Icon(Icons.Default.Folder, "Folders") },
                            label = { Text("Folders") },
                            modifier = Modifier.testTag("nav_folders")
                        )
                        NavigationBarItem(
                            selected = currentTab == NavTab.SEARCH && openedFolder == null,
                            onClick = {
                                openedFolder = null
                                currentTab = NavTab.SEARCH
                            },
                            icon = { Icon(Icons.Default.Search, "Search") },
                            label = { Text("Search") },
                            modifier = Modifier.testTag("nav_search")
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
                // Search Bar when in Search tab
                if (currentTab == NavTab.SEARCH && openedFolder == null) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search by file name or type...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("search_input_field"),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) }
                    )
                }

                // Quick Folder Strip on Home Tab when folders exist
                if (currentTab == NavTab.HOME && openedFolder == null && folders.isNotEmpty()) {
                    Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Folders",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "View All (${folders.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { currentTab = NavTab.FOLDERS }
                            )
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(folders, key = { it.id }) { folder ->
                                val count = items.count { it.folderId == folder.id }
                                CompactFolderChip(
                                    folder = folder,
                                    itemCount = count,
                                    onClick = {
                                        requestFolderPinGate(folder) {
                                            openedFolder = folder
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

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
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(folders, key = { it.id }) { folder ->
                                val folderItemCount = items.count { it.folderId == folder.id }
                                FolderCard(
                                    folder = folder,
                                    itemCount = folderItemCount,
                                    onClick = {
                                        requestFolderPinGate(folder) {
                                            openedFolder = folder
                                        }
                                    },
                                    onLongClick = {
                                        requestFolderPinGate(folder) {
                                            onDeleteFolder(folder)
                                        }
                                    }
                                )
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
                                    openedFolder != null -> "'${openedFolder!!.folderName}' is Empty"
                                    currentTab == NavTab.FAVORITES -> "No Favorite Media Yet"
                                    currentTab == NavTab.SEARCH -> "No Matching Media"
                                    else -> "Your Vault is Empty"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (openedFolder != null) {
                                    "Import photos or videos directly into this encrypted folder."
                                } else {
                                    "Import photos and videos from your device to encrypt them with AES-256."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = { onImportFilesSelected(openedFolder?.id) },
                                modifier = Modifier.testTag("empty_add_media_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (openedFolder != null) "Add Media to Folder" else "Add Media")
                            }
                        }
                    }
                } else {
                    // MAIN MEDIA GRID (or Folder Detail Grid)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columnCount),
                        contentPadding = PaddingValues(6.dp),
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
                                        onItemClick(vaultItem)
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

    // Plus Center Button BottomSheet
    if (showPlusBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPlusBottomSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text(
                    text = if (openedFolder != null) "Add to '${openedFolder!!.folderName}'" else "Add to Local Vault",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    onClick = {
                        showPlusBottomSheet = false
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
                        Icon(Icons.Default.Photo, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Import Photos & Videos", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(
                                if (openedFolder != null) "Encrypt directly into '${openedFolder!!.folderName}'"
                                else "Select single or multiple media files from device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    onClick = {
                        showPlusBottomSheet = false
                        showCreateFolderDialog = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_folder_option"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CreateNewFolder, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Create New Folder", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(
                                "Organize media & optionally protect with a PIN lock",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Create New Folder Dialog (with Main PIN vs Custom PIN support)
    if (showCreateFolderDialog) {
        var folderNameText by remember { mutableStateOf("") }
        var isLockedFolder by remember { mutableStateOf(false) }
        var useCustomPin by remember { mutableStateOf(false) }
        var customFolderPin by remember { mutableStateOf("") }
        var confirmFolderPin by remember { mutableStateOf("") }
        var folderError by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create New Folder", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = folderNameText,
                        onValueChange = { folderNameText = it },
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = !useCustomPin,
                                onClick = { useCustomPin = false }
                            )
                            Text("Use Main App PIN", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = useCustomPin,
                                onClick = { useCustomPin = true }
                            )
                            Text("Set Custom Folder PIN", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (useCustomPin) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = customFolderPin,
                                onValueChange = { if (it.length <= 10 && it.all { c -> c.isDigit() }) customFolderPin = it },
                                label = { Text("Custom Folder PIN (1-10 digits)") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = confirmFolderPin,
                                onValueChange = { if (it.length <= 10 && it.all { c -> c.isDigit() }) confirmFolderPin = it },
                                label = { Text("Confirm Folder PIN") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
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
                        if (isLockedFolder && useCustomPin) {
                            if (customFolderPin.isEmpty()) {
                                folderError = "Enter a custom numeric PIN"
                                return@Button
                            }
                            if (customFolderPin != confirmFolderPin) {
                                folderError = "Folder PINs do not match"
                                return@Button
                            }
                        }
                        onCreateFolder(
                            trimmed,
                            isLockedFolder,
                            if (isLockedFolder && useCustomPin) "CUSTOM_PIN" else "MAIN_PIN",
                            if (isLockedFolder && useCustomPin) customFolderPin else null
                        )
                        showCreateFolderDialog = false
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Locked Folder PIN Gate Dialog
    if (pendingPinFolder != null) {
        val targetFolder = pendingPinFolder!!
        var enteredPin by remember { mutableStateOf("") }
        var recoveryMode by remember { mutableStateOf(false) }
        var recoveryInput by remember { mutableStateOf("") }
        var pinError by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = {
                pendingPinFolder = null
                pendingActionAfterPin = null
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (recoveryMode) "Recover Folder Access" else "Unlock '${targetFolder.folderName}'",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column {
                    if (recoveryMode) {
                        Text(
                            "Enter your Vault Recovery Phrase to unlock this folder:",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = recoveryInput,
                            onValueChange = { recoveryInput = it },
                            label = { Text("Recovery Phrase") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            if (targetFolder.lockType == "CUSTOM_PIN") "Enter this folder's Custom PIN:"
                            else "Enter your Main Vault PIN to access this locked folder:",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = enteredPin,
                            onValueChange = { if (it.length <= 10 && it.all { c -> c.isDigit() }) enteredPin = it },
                            label = { Text("Folder PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(onClick = { recoveryMode = true; pinError = "" }) {
                            Text("Forgot Folder PIN?", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (pinError.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(pinError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val verified = if (recoveryMode) {
                            onVerifyRecoveryPhrase(recoveryInput)
                        } else {
                            onVerifyFolderAccess(targetFolder, enteredPin)
                        }
                        if (verified) {
                            val action = pendingActionAfterPin
                            pendingPinFolder = null
                            pendingActionAfterPin = null
                            action?.invoke()
                        } else {
                            pinError = if (recoveryMode) "Incorrect Recovery Phrase" else "Incorrect PIN"
                        }
                    }
                ) {
                    Text("Unlock")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingPinFolder = null
                    pendingActionAfterPin = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Move Selected Items to Folder Dialog
    if (showMoveToFolderDialog) {
        val selectedList = items.filter { it.id in selectedItemIds }
        AlertDialog(
            onDismissRequest = { showMoveToFolderDialog = false },
            title = { Text("Move ${selectedList.size} Item(s) To", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

                    folders.forEach { folder ->
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

    // Importing Progress Modal Dialog
    if (isImporting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Encrypting Media...", fontWeight = FontWeight.Bold) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(importProgressText.ifEmpty { "Encrypting with AES-256-CTR..." })
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
fun CompactFolderChip(
    folder: FolderItem,
    itemCount: Int,
    onClick: () -> Unit
) {
    FilterChip(
        selected = false,
        onClick = onClick,
        leadingIcon = {
            Icon(
                imageVector = if (folder.isLocked) Icons.Default.Lock else Icons.Default.Folder,
                contentDescription = null,
                tint = if (folder.isLocked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        },
        label = {
            Text(
                text = if (folder.isLocked) folder.folderName else "${folder.folderName} ($itemCount)",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
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
                                text = if (folder.lockType == "CUSTOM_PIN") "CUSTOM PIN" else "LOCKED",
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
                    text = if (folder.isLocked) "PIN Required to View" else "$itemCount encrypted items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
