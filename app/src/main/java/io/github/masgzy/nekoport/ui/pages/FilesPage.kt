package io.github.masgzy.nekoport.ui.pages

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.masgzy.nekoport.NekoApp
import io.github.masgzy.nekoport.R
import io.github.masgzy.nekoport.core.AppLog
import io.github.masgzy.nekoport.core.ConfigStore
import io.github.masgzy.nekoport.core.ServerManager
import io.github.masgzy.nekoport.core.MimeTypes
import io.github.masgzy.nekoport.ui.NekoNav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import io.github.masgzy.nekoport.ui.component.NekoTextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Create
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.File as FileIcon
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.masgzy.nekoport.ui.component.animation.pressFeedback
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import java.io.File

@Composable
fun FilesPage(contentPadding: PaddingValues) {
    val manager = NekoApp.get().serverManager
    val config by manager.config.collectAsState()
    val colorScheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedSiteIndex by remember { mutableStateOf(0) }
    var currentDir by remember { mutableStateOf(manager.sitesRoot) }
    var dirVersion by remember { mutableStateOf(0) }
    var actionFile by remember { mutableStateOf<File?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var createIsFolder by remember { mutableStateOf(true) }
    var toast by remember { mutableStateOf<String?>(null) }

    // Dashboard "open folder / edit files" requests land here.
    val targetDir by NekoNav.filesTargetDir.collectAsState()
    androidx.compose.runtime.LaunchedEffect(targetDir) {
        val dir = targetDir ?: return@LaunchedEffect
        if (dir.isDirectory || dir.mkdirs()) {
            currentDir = dir
            dirVersion++
        }
        NekoNav.consumeFilesTargetDir()
    }

    val sites = config.sites
    val safeIndex = selectedSiteIndex.coerceIn(0, (sites.size - 1).coerceAtLeast(0))

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var ok = 0
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    runCatching {
                        // Display names come from the source app and can carry
                        // slashes or even ".."; flatten them to a plain name
                        // so the upload can never escape the current directory.
                        val rawName = queryFileName(context, uri)
                            ?: "upload-${System.currentTimeMillis()}"
                        val name = rawName.substringAfterLast('/').substringAfterLast('\\')
                            .takeIf { it.isNotBlank() && it != "." && it != ".." }
                            ?: "upload-${System.currentTimeMillis()}"
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            File(currentDir, name).outputStream().use { input.copyTo(it) }
                        }
                        ok++
                    }
                }
            }
            dirVersion++
            toast = if (ok > 0) context.getString(R.string.files_uploaded)
            else context.getString(R.string.files_upload_failed)
        }
    }

    // Directory listing used to be read synchronously inside remember {} -
    // on the MAIN thread; large folders (e.g. /sdcard) froze the UI.
    val listing by produceState<List<File>>(emptyList(), dirVersion, currentDir) {
        value = withContext(Dispatchers.IO) {
            currentDir.listFiles()?.sortedWith(
                compareBy({ !it.isDirectory }, { it.name.lowercase() })
            ) ?: emptyList()
        }
    }
    val parentDir by produceState<File?>(null, dirVersion, currentDir) {
        value = withContext(Dispatchers.IO) {
            runCatching { currentDir.canonicalFile.parentFile }.getOrNull()
        }
    }

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Site selector + toolbar
        item {
            Card {
                OverlaySpinnerPreference(
                    items = (listOf(stringResource(R.string.files_sites_root)) + sites.map { it.name })
                        .map { DropdownItem(text = it, onClick = {}) },
                    selectedIndex = safeIndex + 1,
                    title = stringResource(R.string.files_pick_site),
                    onSelectedIndexChange = { idx ->
                        if (idx == 0) {
                            currentDir = manager.sitesRoot
                        } else {
                            val site = sites.getOrNull(idx - 1) ?: return@OverlaySpinnerPreference
                            currentDir = manager.siteDocRoot(site).apply { mkdirs() }
                        }
                        selectedSiteIndex = (idx - 1).coerceAtLeast(0)
                        dirVersion++
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NekoTextButton(
                        text = stringResource(R.string.files_internal_storage),
                        onClick = {
                            scope.launch {
                                val granted = SiteStorage.ensureStoragePermission(context)
                                if (granted) {
                                    currentDir = java.io.File("/sdcard")
                                    dirVersion++
                                } else {
                                    toast = context.getString(R.string.files_storage_denied)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    NekoTextButton(
                        text = stringResource(R.string.files_upload),
                        onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f),
                    )
                    NekoTextButton(
                        text = stringResource(R.string.files_new_folder),
                        onClick = {
                            createIsFolder = true
                            showCreateDialog = true
                        },
                        modifier = Modifier.weight(1f),
                    )
                    NekoTextButton(
                        text = stringResource(R.string.files_new_file),
                        onClick = {
                            createIsFolder = false
                            showCreateDialog = true
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = currentDir.canonicalPath,
                    color = colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.subtitle,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }

        // Parent directory entry ("..") - browsing may leave the sites root.
        val parent = parentDir
        if (parent != null && parent.canonicalPath != currentDir.canonicalFile.canonicalPath) {
            item {
                Card(
                    modifier = Modifier.pressFeedback(cornerRadius = 16.dp),
                    pressFeedbackType = PressFeedbackType.None,
                    onClick = {
                        currentDir = parent
                        dirVersion++
                    },
                ) {
                    Text(
                        text = "../",
                        color = colorScheme.primary,
                        style = MiuixTheme.textStyles.main,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }
        }

        // Directory content
        if (listing.isEmpty()) {
            item {
                Card {
                    Text(
                        text = if (currentDir.exists()) stringResource(R.string.files_empty_dir)
                        else stringResource(R.string.files_no_access),
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            items(listing, key = { it.absolutePath }) { file ->
                Card(
                    modifier = Modifier.pressFeedback(cornerRadius = 16.dp),
                    pressFeedbackType = PressFeedbackType.None,
                    onClick = {
                        if (file.isDirectory) {
                            currentDir = file
                            dirVersion++
                        } else {
                            actionFile = file
                        }
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = if (file.isDirectory) MiuixIcons.Folder else MiuixIcons.FileIcon,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = file.name,
                                color = colorScheme.onSurface,
                                style = MiuixTheme.textStyles.main,
                            )
                            Text(
                                text = if (file.isDirectory) {
                                    "${file.listFiles()?.size ?: 0} items"
                                } else {
                                    formatSize(file.length())
                                },
                                color = colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.subtitle,
                            )
                        }
                    }
                }
            }
        }
    }

    toast?.let { msg ->
        OverlayDialog(
            show = true,
            title = msg,
            onDismissRequest = { toast = null },
        ) {
            NekoTextButton(text = stringResource(R.string.dialog_ok), onClick = { toast = null })
        }
    }
    if (showCreateDialog) {
        CreateDialog(
            isFolder = createIsFolder,
            targetDir = currentDir,
            onDismiss = { showCreateDialog = false },
            onCreated = { dirVersion++ },
        )
    }

    actionFile?.let { file ->
        FileActionDialog(
            file = file,
            onDismiss = { actionFile = null },
            onChanged = { dirVersion++ },
        )
    }
}

private fun queryFileName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    else -> "%.1f GB".format(bytes / 1073741824.0)
}

@Composable
private fun CreateDialog(
    isFolder: Boolean,
    targetDir: File,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    var name by remember { mutableStateOf(TextFieldValue("")) }
    OverlayDialog(
        show = true,
        title = if (isFolder) stringResource(R.string.files_new_folder) else stringResource(R.string.files_new_file),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.files_name),
                useLabelAsPlaceholder = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NekoTextButton(
                    text = stringResource(R.string.dialog_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                NekoTextButton(
                    text = stringResource(R.string.dialog_ok),
                    onClick = {
                        val clean = name.text.trim()
                        if (clean.isNotEmpty() && !clean.contains('/') && !clean.contains('\\') && clean != "." && clean != "..") {
                            val target = File(targetDir, clean)
                            if (isFolder) target.mkdirs() else runCatching { target.createNewFile() }
                            onCreated()
                        }
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FileActionDialog(
    file: File,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()
    val isText = file.isFile && file.length() < 256 * 1024 &&
        MimeTypes.isTextLike(MimeTypes.of(file.name.substringAfterLast('.', "")))
    var showEditor by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var editorContent by remember(file) { mutableStateOf(TextFieldValue(file.readTextSafely())) }

    if (showEditor) {
        OverlayDialog(
            show = true,
            title = file.name,
            summary = stringResource(R.string.files_content),
            onDismissRequest = {
                showEditor = false
                onDismiss()
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = editorContent,
                    onValueChange = { editorContent = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NekoTextButton(
                        text = stringResource(R.string.dialog_cancel),
                        onClick = {
                            showEditor = false
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    NekoTextButton(
                        text = stringResource(R.string.site_confirm),
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { file.writeText(editorContent.text) }
                                onChanged()
                                showEditor = false
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showRename) {
        var newName by remember(file.name) { mutableStateOf(TextFieldValue(file.name)) }
        var renameError by remember(file.name) { mutableStateOf(false) }
        OverlayDialog(
            show = true,
            title = stringResource(R.string.files_rename),
            onDismissRequest = {
                showRename = false
                onDismiss()
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = newName,
                    onValueChange = { newName = it; renameError = false },
                    label = stringResource(R.string.files_name),
                    useLabelAsPlaceholder = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (renameError) {
                    Text(
                        text = stringResource(R.string.files_name_exists),
                        color = colorScheme.primary,
                        style = MiuixTheme.textStyles.subtitle,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NekoTextButton(
                        text = stringResource(R.string.dialog_cancel),
                        onClick = {
                            showRename = false
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    NekoTextButton(
                        text = stringResource(R.string.dialog_ok),
                        onClick = {
                            val clean = newName.text.trim()
                            if (clean.isNotEmpty() && clean != "." && clean != ".." &&
                                !clean.contains('/') && !clean.contains('\\')
                            ) {
                                val target = File(file.parentFile, clean)
                                // Refuse to clobber an existing entry (the old
                                // renameTo silently failed or overwrote).
                                if (!target.exists() && file.renameTo(target)) {
                                    renameError = false
                                    onChanged()
                                } else {
                                    renameError = true
                                }
                            }
                            if (!renameError) {
                                showRename = false
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.dialog_delete),
            summary = stringResource(R.string.files_confirm_delete, file.name),
            onDismissRequest = {
                showDeleteConfirm = false
                onDismiss()
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NekoTextButton(
                    text = stringResource(R.string.dialog_cancel),
                    onClick = {
                        showDeleteConfirm = false
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
                NekoTextButton(
                    text = stringResource(R.string.dialog_delete),
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (file.isDirectory) file.deleteRecursively() else file.delete()
                            }
                            onChanged()
                            showDeleteConfirm = false
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (!showEditor && !showRename && !showDeleteConfirm) {
        OverlayDialog(
            show = true,
            title = file.name,
            onDismissRequest = onDismiss,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.files_share),
                    onClick = {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "io.github.masgzy.nekoport.fileprovider",
                            file,
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = MimeTypes.of(file.name.substringAfterLast('.', ""))
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                        onDismiss()
                    },
                )
                if (isText) {
                    ArrowPreference(
                        title = stringResource(R.string.files_edit),
                        onClick = { showEditor = true },
                    )
                } else if (file.isFile && file.length() >= 256 * 1024) {
                    Text(
                        text = stringResource(R.string.files_too_large),
                        color = colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.subtitle,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                ArrowPreference(
                    title = stringResource(R.string.files_rename),
                    onClick = { showRename = true },
                )
                ArrowPreference(
                    title = stringResource(R.string.files_delete),
                    onClick = { showDeleteConfirm = true },
                )
            }
        }
    }
}

private fun File.readTextSafely(): String = runCatching { readText() }.getOrDefault("")
