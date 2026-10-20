package com.rohan8660.duplicatefilescleaner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.clickable
import coil.compose.SubcomposeAsyncImage
import com.rohan8660.duplicatefilescleaner.ui.theme.DuplicateFilesCleanerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10
import kotlin.math.pow

data class SelectableFile(
    val uri: Uri,
    val name: String,
    val path: String,
    val lastModified: Long,
    val sizeBytes: Long,
    val isSelected: MutableState<Boolean> = mutableStateOf(false)
)

data class DuplicateGroup(
    val hash: String,
    val formattedSize: String,
    val files: SnapshotStateList<SelectableFile>
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val systemTheme = isSystemInDarkTheme()
            var useDarkTheme by remember { mutableStateOf(systemTheme) }

            DuplicateFilesCleanerTheme(darkTheme = useDarkTheme) {
                DuplicateCleanerApp(
                    isDarkTheme = useDarkTheme,
                    onThemeToggle = { useDarkTheme = !useDarkTheme }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateCleanerApp(isDarkTheme: Boolean, onThemeToggle: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var duplicateGroups by remember { mutableStateOf<List<DuplicateGroup>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var hasScanned by remember { mutableStateOf(false) }
    var scannedFilesCount by remember { mutableIntStateOf(0) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val hashCache = remember { ConcurrentHashMap<Uri, String>() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // Launcher for Android 11+ Manage External Storage Permission
    val manageStoragePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                // Permission granted, start full scan
                isScanning = true
                hasScanned = false
                duplicateGroups = emptyList()
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        var totalFiles = 0
                        val groups = scanFullDeviceForDuplicates(context, hashCache) { count ->
                            totalFiles = count
                        }
                        withContext(Dispatchers.Main) {
                            duplicateGroups = groups
                            scannedFilesCount = totalFiles
                            hasScanned = true
                        }
                    } catch (e: Exception) {
                        Log.e("DuplicateCleaner", "Error during full scan", e)
                    } finally {
                        withContext(Dispatchers.Main) {
                            isScanning = false
                        }
                    }
                }
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            isScanning = true
            hasScanned = false
            duplicateGroups = emptyList()
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.e("DuplicateCleaner", "Failed to take persistable permission", e)
                }

                try {
                    var totalFiles = 0
                    val groups = scanForDuplicates(context, uri, hashCache) { count ->
                        totalFiles = count
                    }
                    withContext(Dispatchers.Main) {
                        duplicateGroups = groups
                        scannedFilesCount = totalFiles
                        hasScanned = true
                    }
                } catch (e: Exception) {
                    Log.e("DuplicateCleaner", "Error during scan", e)
                } finally {
                    withContext(Dispatchers.Main) {
                        isScanning = false
                    }
                }
            }
        }
    }

    val selectedFilesCount = duplicateGroups.sumOf { group -> group.files.count { it.isSelected.value } }
    val reclaimableStorageBytes = duplicateGroups.sumOf { group ->
        group.files.filter { it.isSelected.value }.sumOf { it.sizeBytes }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Permanently Delete Files?") },
            text = { Text("This will permanently delete the selected $selectedFilesCount files and free up ${formatSize(reclaimableStorageBytes)}. This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        coroutineScope.launch(Dispatchers.IO) {
                            deleteSelectedFiles(context, duplicateGroups)
                            val updatedGroups = duplicateGroups.mapNotNull { group ->
                                val remaining = group.files.filter { !it.isSelected.value }
                                if (remaining.size > 1) {
                                    group.copy(files = SnapshotStateList<SelectableFile>().apply { addAll(remaining) })
                                } else {
                                    null
                                }
                            }
                            withContext(Dispatchers.Main) {
                                duplicateGroups = updatedGroups
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Duplicate Files Cleaner",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider()
                
                Text(
                    "User Profile (Coming Soon)",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Person, contentDescription = null) },
                    label = { Text("Sign In / Profile") },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        Toast.makeText(context, "User Profile integration coming soon!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                Text(
                    "About",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Code, contentDescription = null) },
                    label = { Text("Developer Profile") },
                    selected = false,
                    onClick = {
                        coroutineScope.launch { drawerState.close() }
                        val intent = Intent(Intent.ACTION_VIEW, "https://github.com/rohan8660".toUri())
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "No browser found", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Duplicate Files Cleaner") },
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        IconButton(onClick = onThemeToggle) {
                            Icon(
                                imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "Toggle Theme"
                            )
                        }
                    },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = duplicateGroups.isNotEmpty() && !isScanning,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Selected: $selectedFilesCount files",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Reclaimable: ${formatSize(reclaimableStorageBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Button(
                                onClick = { if (selectedFilesCount > 0) showDeleteDialog = true },
                                enabled = selectedFilesCount > 0,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Reclaim")
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            OutlinedButton(
                                onClick = { deselectAll(duplicateGroups) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Deselect All", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Button(
                                onClick = { autoSelectAllDuplicates(duplicateGroups) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Auto-Select", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (hasScanned && !isScanning) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExtendedFloatingActionButton(
                        onClick = { folderPickerLauncher.launch(null) },
                        icon = { Icon(Icons.Default.Folder, contentDescription = "Scan Folder") },
                        text = { Text("Scan Folder") },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                    ExtendedFloatingActionButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                if (Environment.isExternalStorageManager()) {
                                    isScanning = true
                                    hasScanned = false
                                    duplicateGroups = emptyList()
                                    coroutineScope.launch(Dispatchers.IO) {
                                        try {
                                            var totalFiles = 0
                                            val groups = scanFullDeviceForDuplicates(context, hashCache) { count ->
                                                totalFiles = count
                                            }
                                            withContext(Dispatchers.Main) {
                                                duplicateGroups = groups
                                                scannedFilesCount = totalFiles
                                                hasScanned = true
                                            }
                                        } catch (e: Exception) {
                                            Log.e("DuplicateCleaner", "Error during full scan", e)
                                        } finally {
                                            withContext(Dispatchers.Main) {
                                                isScanning = false
                                            }
                                        }
                                    }
                                } else {
                                    try {
                                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                        intent.data = "package:${context.packageName}".toUri()
                                        manageStoragePermissionLauncher.launch(intent)
                                    } catch (_: Exception) {
                                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                        manageStoragePermissionLauncher.launch(intent)
                                    }
                                }
                            }
                        },
                        icon = { Icon(Icons.Default.SystemUpdateAlt, contentDescription = "Full Device Scan") },
                        text = { Text("Full Device Scan") },
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (isScanning) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Scanning files...",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (!hasScanned) {
                // Empty Home State
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Folder",
                        modifier = Modifier.size(120.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Find Duplicate Files",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Scan a folder to find and remove identical files to free up storage space.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                if (Environment.isExternalStorageManager()) {
                                    isScanning = true
                                    hasScanned = false
                                    duplicateGroups = emptyList()
                                    coroutineScope.launch(Dispatchers.IO) {
                                        try {
                                            var totalFiles = 0
                                            val groups = scanFullDeviceForDuplicates(context, hashCache) { count ->
                                                totalFiles = count
                                            }
                                            withContext(Dispatchers.Main) {
                                                duplicateGroups = groups
                                                scannedFilesCount = totalFiles
                                                hasScanned = true
                                            }
                                        } catch (e: Exception) {
                                            Log.e("DuplicateCleaner", "Error during full scan", e)
                                        } finally {
                                            withContext(Dispatchers.Main) {
                                                isScanning = false
                                            }
                                        }
                                    }
                                } else {
                                    try {
                                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                        intent.data = "package:${context.packageName}".toUri()
                                        manageStoragePermissionLauncher.launch(intent)
                                    } catch (_: Exception) {
                                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                        manageStoragePermissionLauncher.launch(intent)
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.SystemUpdateAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Full Device Scan", fontSize = 18.sp)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select Specific Folder", fontSize = 18.sp)
                    }
                }
            } else if (duplicateGroups.isEmpty()) {
                // Scanned but no duplicates
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.DoneAll,
                        contentDescription = "Clean",
                        modifier = Modifier.size(120.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "No Duplicates Found!",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "We scanned $scannedFilesCount files and didn't find any exact duplicates.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Show duplicates
                Column(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Found ${duplicateGroups.size} duplicate groups among $scannedFilesCount scanned files",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 120.dp), // padding for bottom bar
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(duplicateGroups, key = { it.hash }) { group ->
                            DuplicateGroupCard(group)
                        }
                    }
                }
            }
        }
    }
    }
}

fun autoSelectAllDuplicates(groups: List<DuplicateGroup>) {
    for ((_, _, files) in groups) {
        files.forEachIndexed { index, file ->
            file.isSelected.value = index != 0
        }
    }
}

fun deselectAll(groups: List<DuplicateGroup>) {
    for ((_, _, files) in groups) {
        for (file in files) {
            file.isSelected.value = false
        }
    }
}

fun deleteSelectedFiles(context: Context, groups: List<DuplicateGroup>) {
    for ((_, _, files) in groups) {
        val selectedFiles = files.filter { it.isSelected.value }
        val filesToDelete = if (selectedFiles.size == files.size) {
            // Safety fallback if somehow all are selected: keep the oldest one
            selectedFiles.drop(1)
        } else {
            selectedFiles
        }
        
        for (file in filesToDelete) {
            try {
                if (file.uri.scheme == "file") {
                    file.uri.path?.let { File(it).delete() }
                } else {
                    DocumentsContract.deleteDocument(context.contentResolver, file.uri)
                }
            } catch (e: Exception) {
                Log.e("DuplicateCleaner", "Failed to delete ${file.uri}", e)
            }
        }
    }
}

@Composable
fun DuplicateGroupCard(group: DuplicateGroup) {
    val oldestFile = group.files.firstOrNull()
    val originalName = oldestFile?.name ?: "Unknown file"
    val context = LocalContext.current

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = originalName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = group.formattedSize,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(4.dp))

            val unselectedCount = group.files.count { !it.isSelected.value }

            group.files.forEach { file ->
                val isOriginal = file.uri == oldestFile?.uri
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = file.isSelected.value,
                        onCheckedChange = { isChecked ->
                            if (isChecked && !file.isSelected.value && unselectedCount <= 1) {
                                Toast.makeText(context, "Cannot select all items in a group", Toast.LENGTH_SHORT).show()
                                @Suppress("DEPRECATION")
                                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                            } else {
                                file.isSelected.value = isChecked
                            }
                        },
                        modifier = Modifier.scale(0.8f)
                    )

                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(file.uri)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Thumbnail",
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { openFile(context, file.uri) },
                        contentScale = ContentScale.Crop,
                        error = { _ ->
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = "File",
                                modifier = Modifier.padding(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    )

                    Column(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .weight(1f)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isOriginal) {
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Text(
                                        text = "Original",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = file.path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

fun scanForDuplicates(
    context: Context,
    treeUri: Uri,
    hashCache: MutableMap<Uri, String>,
    onTotalFilesCounted: (Int) -> Unit
): List<DuplicateGroup> {
    val documentFile = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()

    val allFiles = mutableListOf<DocumentFile>()
    fun traverse(dir: DocumentFile) {
        try {
            val files = dir.listFiles()
            for (file in files) {
                val fileName = file.name ?: ""
                if (file.isDirectory) {
                    if (fileName.startsWith(".")) continue
                    traverse(file)
                } else if (file.isFile) {
                    if (fileName.startsWith(".")) continue
                    allFiles.add(file)
                }
            }
        } catch (e: Exception) {
            Log.e("DuplicateCleaner", "Failed to list files for ${dir.uri}", e)
        }
    }
    traverse(documentFile)
    onTotalFilesCounted(allFiles.size)

    // Group by size
    val filesBySize = allFiles.groupBy { it.length() }

    // Discard unique sizes and empty files (0 bytes)
    val potentialDuplicates = filesBySize.filter { it.key > 0L && it.value.size > 1 }

    val results = mutableListOf<DuplicateGroup>()

    for ((size, files) in potentialDuplicates) {
        // Compute hashes
        val filesByHash = mutableMapOf<String, MutableList<SelectableFile>>()

        for (file in files) {
            val uri = file.uri
            val hash = try {
                hashCache.getOrPut(uri) {
                    computeSha256(context, uri) ?: ""
                }
            } catch (_: Exception) {
                ""
            }

            if (hash.isNotEmpty()) {
                val name = file.name ?: "Unknown"
                val path = try {
                    val decoded = Uri.decode(file.uri.path) ?: name
                    if (decoded.contains(":")) decoded.substringAfterLast(":") else decoded
                } catch (_: Exception) {
                    name
                }

                val selectable = SelectableFile(
                    uri = file.uri,
                    name = name,
                    path = path,
                    lastModified = file.lastModified(),
                    sizeBytes = size
                )

                filesByHash.getOrPut(hash) { mutableListOf() }.add(selectable)
            }
        }

        // Group by hash
        for ((hash, hashFiles) in filesByHash) {
            if (hashFiles.size > 1) {
                hashFiles.sortWith(compareBy({ it.lastModified }, { it.name.length }, { it.name }))
                results.add(
                    DuplicateGroup(
                        hash = hash,
                        formattedSize = formatSize(size),
                        files = SnapshotStateList<SelectableFile>().apply { addAll(hashFiles) }
                    )
                )
            }
        }
    }

    return results
}

fun scanFullDeviceForDuplicates(
    context: Context,
    hashCache: MutableMap<Uri, String>,
    onTotalFilesCounted: (Int) -> Unit
): List<DuplicateGroup> {
    val rootDir = Environment.getExternalStorageDirectory()
    val allFiles = mutableListOf<File>()

    fun traverse(dir: File) {
        try {
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (file.isDirectory) {
                    // Skip android data folder as it causes issues/delays
                    if (file.name == "Android" || file.name.startsWith(".")) continue
                    traverse(file)
                } else if (file.isFile) {
                    if (file.name.startsWith(".")) continue
                    allFiles.add(file)
                }
            }
        } catch (e: Exception) {
            Log.e("DuplicateCleaner", "Failed to list files for ${dir.absolutePath}", e)
        }
    }

    traverse(rootDir)
    onTotalFilesCounted(allFiles.size)

    // Group by size
    val filesBySize = allFiles.groupBy { it.length() }

    // Discard unique sizes and empty files (0 bytes)
    val potentialDuplicates = filesBySize.filter { it.key > 0L && it.value.size > 1 }
    val results = mutableListOf<DuplicateGroup>()

    for ((size, files) in potentialDuplicates) {
        val filesByHash = mutableMapOf<String, MutableList<SelectableFile>>()

        for (file in files) {
            val uri = Uri.fromFile(file)
            val hash = try {
                hashCache.getOrPut(uri) {
                    computeSha256(context, uri) ?: ""
                }
            } catch (_: Exception) {
                ""
            }

            if (hash.isNotEmpty()) {
                val path = file.absolutePath.replace(Environment.getExternalStorageDirectory().absolutePath, "Internal Storage")

                val selectable = SelectableFile(
                    uri = uri,
                    name = file.name,
                    path = path,
                    lastModified = file.lastModified(),
                    sizeBytes = size
                )
                filesByHash.getOrPut(hash) { mutableListOf() }.add(selectable)
            }
        }

        // Group by hash
        for ((hash, hashFiles) in filesByHash) {
            if (hashFiles.size > 1) {
                hashFiles.sortWith(compareBy({ it.lastModified }, { it.name.length }, { it.name }))
                results.add(
                    DuplicateGroup(
                        hash = hash,
                        formattedSize = formatSize(size),
                        files = SnapshotStateList<SelectableFile>().apply { addAll(hashFiles) }
                    )
                )
            }
        }
    }

    return results
}

fun computeSha256(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192) // 8KB chunks
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            val hashBytes = digest.digest()
            hashBytes.joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) {
        Log.e("DuplicateCleaner", "Failed to compute hash for $uri", e)
        null
    }
}

fun formatSize(sizeBytes: Long): String {
    if (sizeBytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(sizeBytes.toDouble()) / log10(1024.0)).toInt()
    return String.format(java.util.Locale.US, "%.2f %s", sizeBytes / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
}

fun openFile(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Open File"))
    } catch (e: Exception) {
        Log.e("DuplicateCleaner", "Failed to open file", e)
    }
}
