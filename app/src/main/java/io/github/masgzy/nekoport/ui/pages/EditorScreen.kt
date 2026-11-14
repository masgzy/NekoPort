package io.github.masgzy.nekoport.ui.pages

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.masgzy.nekoport.NekoApp
import io.github.masgzy.nekoport.R
import io.github.masgzy.nekoport.core.ThemeMode
import io.github.masgzy.nekoport.ui.OverlayBus
import io.github.masgzy.nekoport.ui.component.NekoTextButton
import io.github.masgzy.nekoport.ui.component.animation.pressFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * A file opened in the full-screen overlay editor. Config files are editable
 * and can be reset to the generated defaults; log files open read-only.
 * Multiple targets stack as tabs (interaction model follows Acode).
 */
data class EditorTarget(
    val title: String,
    val file: File,
    val readOnly: Boolean = false,
    /** Invoked after user confirmation to drop the user-edited file. */
    val resetAction: (() -> Unit)? = null,
    val maxBytes: Int = 512 * 1024,
) {
    /** Stable tab identity: the canonical file path. */
    val id: String get() = file.canonicalPath
}

/**
 * Renders the active overlay on top of everything: the code editor and the
 * application log page (reached from Settings).
 *
 * The system back button closes the overlay instead of finishing the
 * activity (the activity is `singleTask`, so finishing it would drop the
 * user straight onto the launcher). When unsaved edits exist, back asks
 * first.
 */
@Composable
fun OverlayHost() {
    val editor by OverlayBus.editor.collectAsState()
    val appLogs by OverlayBus.appLogs.collectAsState()
    BackHandler(enabled = editor != null || appLogs) {
        if (editor != null) {
            OverlayBus.requestCloseEditor()
        } else {
            OverlayBus.dismissAppLogs()
        }
    }
    when {
        editor != null -> CodeEditorScreen()
        appLogs -> AppLogScreen()
    }
}

@Composable
private fun OverlayScaffold(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colorScheme = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The overlay draws over the scaffold (edge-to-edge), so it
                // must handle the status bar itself or the title is hidden
                // underneath it.
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss)
                        .padding(10.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Close,
                        contentDescription = stringResource(R.string.dialog_cancel),
                        tint = colorScheme.onSurface,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                ) {
                    Text(
                        text = title,
                        style = MiuixTheme.textStyles.title2,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MiuixTheme.textStyles.subtitle,
                            color = colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    }
                }
                actions()
            }
            content()
        }
    }
}

@Composable
private fun AppLogScreen() {
    OverlayScaffold(
        title = stringResource(R.string.logs_title),
        onDismiss = { OverlayBus.dismissAppLogs() },
    ) {
        LogsPage(contentPadding = PaddingValues(bottom = 16.dp))
    }
}

// ---------------------------------------------------------------------------
// Code editor (Acode-style interaction, CodeMirror core in a WebView)
// ---------------------------------------------------------------------------

private data class EditorUiState(
    val line: Int = 1,
    val col: Int = 1,
    val lines: Int = 1,
    val dirty: Boolean = false,
)

/** Per-tab native state: file content (loaded once) + load flag. */
private class EditorTabState(
    val target: EditorTarget,
    var content: String = "",
    var loaded: Boolean = false,
)

@Composable
private fun CodeEditorScreen() {
    val session by OverlayBus.editor.collectAsState()
    val config by NekoApp.get().serverManager.config.collectAsState()
    val manager = NekoApp.get().serverManager
    val colorScheme = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()

    val targets = session?.targets ?: return
    val activeIndex = session?.activeIndex?.coerceIn(0, targets.lastIndex) ?: 0
    val activeTarget = targets[activeIndex]

    val tabStates = remember(targets.map { it.id }) {
        targets.map { EditorTabState(it) }
    }
    val tabState = tabStates.firstOrNull { it.target.id == activeTarget.id } ?: tabStates[0]

    val dark = when (config.settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    var uiState by remember { mutableStateOf(EditorUiState()) }
    var dirtyByTab by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var showFind by remember { mutableStateOf(false) }
    var showGoto by remember { mutableStateOf(false) }
    var showUnsaved by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var closingTabIndex by remember { mutableIntStateOf(-1) } // -1 = closing the whole editor
    var fontSize by remember { mutableIntStateOf(config.settings.editorFontSize) }

    val pendingClose by OverlayBus.pendingClose.collectAsState()

    fun zoomTo(value: Int) {
        val clamped = value.coerceIn(10, 20)
        fontSize = clamped
        editorBridge?.eval("Editor.setFontSize($clamped)")
        manager.updateSettings(config.settings.copy(editorFontSize = clamped))
    }

    fun saveTabThen(index: Int, then: () -> Unit) {
        val id = tabStates[index].target.id
        editorBridge?.pendingAfterSave = then
        editorBridge?.eval("Editor.switchDoc(${jsString(id)}); Editor.saveActive();")
    }

    // ---- load the active tab's file once, then hand it to the editor ----
    LaunchedEffect(tabState.target.id) {
        if (!tabState.loaded) {
            tabState.content = withContext(Dispatchers.IO) {
                runCatching {
                    val file = tabState.target.file
                    when {
                        !file.exists() -> ""
                        file.length() > tabState.target.maxBytes -> {
                            val skip = file.length() - tabState.target.maxBytes
                            file.inputStream().use { input ->
                                input.skip(skip)
                                input.bufferedReader().readText()
                            }
                        }
                        else -> file.readText()
                    }
                }.getOrElse { e -> "// ${e.message}" }
            }
            tabState.loaded = true
            editorBridge?.pushDocument(
                tabState.target.id,
                tabState.content,
                modeFor(tabState.target),
                tabState.target.readOnly,
            )
        } else {
            editorBridge?.switchTo(tabState.target.id)
        }
    }

    OverlayScaffold(
        title = activeTarget.title,
        subtitle = activeTarget.file.canonicalPath,
        onDismiss = { OverlayBus.requestCloseEditor() },
        actions = {
            if (activeTarget.resetAction != null) {
                NekoTextButton(
                    text = stringResource(R.string.editor_reset),
                    onClick = { showReset = true },
                )
            }
            if (!activeTarget.readOnly) {
                NekoTextButton(
                    text = stringResource(R.string.editor_save),
                    enabled = tabState.loaded,
                    onClick = { editorBridge?.eval("Editor.saveActive()") },
                )
            }
        },
    ) {
        // ---- tab bar ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tabStates.forEachIndexed { index, ts ->
                EditorTabChip(
                    title = ts.target.title,
                    dirty = dirtyByTab[ts.target.id] ?: false,
                    selected = index == activeIndex,
                    onClick = { OverlayBus.setActiveTab(index) },
                    onClose = {
                        if (dirtyByTab[ts.target.id] == true) {
                            closingTabIndex = index
                            showUnsaved = true
                        } else {
                            editorBridge?.closeDoc(ts.target.id)
                            OverlayBus.closeTab(index)
                        }
                    },
                )
            }
        }

        // ---- action row ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            NekoTextButton(text = stringResource(R.string.editor_find), onClick = { showFind = !showFind })
            if (!activeTarget.readOnly) {
                NekoTextButton(text = stringResource(R.string.editor_undo), onClick = { editorBridge?.eval("Editor.undo()") })
                NekoTextButton(text = stringResource(R.string.editor_redo), onClick = { editorBridge?.eval("Editor.redo()") })
            }
            NekoTextButton(text = stringResource(R.string.editor_goto), onClick = { showGoto = true })
            NekoTextButton(text = "A-", onClick = { zoomTo(fontSize - 1) })
            NekoTextButton(text = "A+", onClick = { zoomTo(fontSize + 1) })
        }

        // ---- find / replace ----
        if (showFind) {
            FindReplaceBar(
                readOnly = activeTarget.readOnly,
                onFind = { q, cs -> editorBridge?.eval("Editor.find(${jsString(q)}, $cs)") },
                onNext = { editorBridge?.eval("Editor.findNext(false)") },
                onPrev = { editorBridge?.eval("Editor.findNext(true)") },
                onReplace = { r -> editorBridge?.eval("Editor.replaceOne(${jsString(r)})") },
                onReplaceAll = { q, r, cs ->
                    editorBridge?.eval("Editor.replaceAll(${jsString(q)}, ${jsString(r)}, $cs)")
                },
            )
        }

        // ---- CodeMirror core ----
        EditorWebView(
            dark = dark,
            fontSize = fontSize,
            onUiState = { state -> uiState = state },
            onDirty = { id, dirty ->
                dirtyByTab = dirtyByTab + (id to dirty)
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        // ---- symbol bar ----
        SymbolBar(
            enabled = !activeTarget.readOnly,
            symbols = symbolsFor(activeTarget),
            onInsert = { s -> editorBridge?.eval("Editor.insertText(${jsString(s)})") },
        )

        // ---- status bar ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Ln ${uiState.line}, Col ${uiState.col}",
                color = colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
            )
            Text(
                text = "UTF-8",
                color = colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
            )
            Text(
                text = stringResource(R.string.editor_lines, uiState.lines),
                color = colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
            )
            Text(
                text = stringResource(if (uiState.dirty) R.string.editor_modified else R.string.editor_saved),
                color = if (uiState.dirty) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.subtitle,
            )
        }
    }

    // ---- goto line ----
    if (showGoto) {
        var lineText by remember(showGoto) { mutableStateOf(TextFieldValue(uiState.line.toString())) }
        OverlayDialog(
            show = true,
            title = stringResource(R.string.editor_goto),
            onDismissRequest = { showGoto = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = lineText,
                    onValueChange = { lineText = it },
                    label = stringResource(R.string.editor_line_number),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NekoTextButton(
                        text = stringResource(R.string.dialog_cancel),
                        onClick = { showGoto = false },
                        modifier = Modifier.weight(1f),
                    )
                    NekoTextButton(
                        text = stringResource(R.string.dialog_ok),
                        onClick = {
                            lineText.text.toIntOrNull()?.let { editorBridge?.eval("Editor.gotoLine($it)") }
                            showGoto = false
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    // ---- unsaved changes (tab close / editor close via back or X) ----
    if (showUnsaved) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.editor_unsaved_title),
            summary = stringResource(R.string.editor_unsaved_summary),
            onDismissRequest = { showUnsaved = false },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NekoTextButton(
                    text = stringResource(R.string.editor_unsaved_discard),
                    onClick = {
                        showUnsaved = false
                        if (closingTabIndex >= 0) {
                            val id = tabStates[closingTabIndex].target.id
                            editorBridge?.closeDoc(id)
                            OverlayBus.closeTab(closingTabIndex)
                            closingTabIndex = -1
                        } else {
                            OverlayBus.closeEditor()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                NekoTextButton(
                    text = stringResource(R.string.editor_save),
                    onClick = {
                        showUnsaved = false
                        if (closingTabIndex >= 0) {
                            val index = closingTabIndex
                            closingTabIndex = -1
                            saveTabThen(index) {
                                editorBridge?.closeDoc(tabStates[index].target.id)
                                OverlayBus.closeTab(index)
                            }
                        } else {
                            saveTabThen(activeIndex) { OverlayBus.closeEditor() }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    // ---- reset to generated defaults ----
    if (showReset) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.editor_reset),
            summary = stringResource(R.string.editor_reset_confirm),
            onDismissRequest = { showReset = false },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NekoTextButton(
                    text = stringResource(R.string.dialog_cancel),
                    onClick = { showReset = false },
                    modifier = Modifier.weight(1f),
                )
                NekoTextButton(
                    text = stringResource(R.string.dialog_ok),
                    onClick = {
                        activeTarget.resetAction?.invoke()
                        tabState.loaded = false
                        showReset = false
                        OverlayBus.closeEditor()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    // ---- back / X with unsaved tabs ----
    LaunchedEffect(pendingClose, uiState.dirty, dirtyByTab) {
        if (pendingClose) {
            // Any tab's unsaved edits count, not just the active one: the old
            // uiState.dirty check silently dropped background tabs' changes.
            if (uiState.dirty || dirtyByTab.any { it.value }) showUnsaved = true else OverlayBus.closeEditor()
        }
    }
}

// ---------------------------------------------------------------------------
// WebView plumbing
// ---------------------------------------------------------------------------

private var editorBridge: EditorBridge? = null

/**
 * JS bridge: the WebView calls [save]/[onState]; native code pushes
 * documents and editor commands through [eval]. All native side effects run
 * off the main thread where they are blocking (file IO).
 */
private class EditorBridge(
    private val onStateJson: (JSONObject) -> Unit,
    private val onSave: (id: String, content: String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )
    var pendingAfterSave: (() -> Unit)? = null
    private var pageReady = false
    private val pendingDocs = mutableListOf<String>()

    @JavascriptInterface
    fun onState(json: String) {
        mainHandler.post {
            runCatching { onStateJson(JSONObject(json)) }
        }
    }

    @JavascriptInterface
    fun save(id: String, content: String) {
        ioScope.launch {
            onSave(id, content)
            mainHandler.post {
                val after = pendingAfterSave
                pendingAfterSave = null
                after?.invoke()
                eval("Editor.markSaved(${jsString(id)})")
            }
        }
    }

    /** Queues document loads until the editor page finished loading. */
    fun pushDocument(id: String, content: String, mode: String, readOnly: Boolean) {
        val js = "Editor.loadDoc(${jsString(id)}, ${jsString(content)}, ${jsString(mode)}, $readOnly)"
        mainHandler.post {
            if (pageReady) eval(js) else pendingDocs.add(js)
        }
    }

    /** Called from onPageFinished: initialize the editor and flush queued docs. */
    fun onPageReady(dark: Boolean, fontSize: Int) {
        mainHandler.post {
            pageReady = true
            eval("Editor.init($dark, $fontSize)")
            pendingDocs.forEach { eval(it) }
            pendingDocs.clear()
        }
    }

    fun switchTo(id: String) = eval("Editor.switchDoc(${jsString(id)})")

    fun closeDoc(id: String) = eval("Editor.closeDoc(${jsString(id)})")

    fun eval(js: String) {
        mainHandler.post {
            webViewRef?.evaluateJavascript(js, null)
        }
    }

    companion object {
        var webViewRef: WebView? = null
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EditorWebView(
    dark: Boolean,
    fontSize: Int,
    onUiState: (EditorUiState) -> Unit,
    onDirty: (id: String, dirty: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    val surfaceArgb = colorScheme.surface.toArgb()

    val webViewState = remember { mutableStateOf<WebView?>(null) }
    // Latest theme/font values for onPageFinished (the factory closure would
    // otherwise capture the values of the first composition forever).
    val themeState = remember { mutableStateOf(dark to fontSize) }
    themeState.value = dark to fontSize

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundColor(surfaceArgb)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val (d, f) = themeState.value
                        editorBridge?.onPageReady(d, f)
                    }
                }
                webChromeClient = WebChromeClient()
                val bridge = EditorBridge(
                    onStateJson = { o ->
                        val id = o.optString("id")
                        onDirty(id, o.optBoolean("dirty", false))
                        onUiState(
                            EditorUiState(
                                line = o.optInt("line", 1),
                                col = o.optInt("col", 1),
                                lines = o.optInt("lines", 1),
                                dirty = o.optBoolean("dirty", false),
                            ),
                        )
                    },
                    onSave = { id, content ->
                        val target = OverlayBus.editor.value?.targets?.firstOrNull { it.id == id }
                        if (target != null) {
                            runCatching {
                                target.file.parentFile?.mkdirs()
                                target.file.writeText(content)
                            }
                        }
                    },
                )
                addJavascriptInterface(bridge, "NekoEditor")
                editorBridge = bridge
                EditorBridge.webViewRef = this
                webViewState.value = this
                loadUrl("file:///android_asset/editor/index.html")
            }
        },
        // Theme/font updates are pushed by LaunchedEffect below; re-injecting
        // JS here ran on EVERY recomposition (each keystroke) and triggered a
        // cm.refresh() per keystroke.
        update = { },
    )

    LaunchedEffect(dark, fontSize) {
        editorBridge?.eval("Editor.setDark($dark)")
        editorBridge?.eval("Editor.setFontSize($fontSize)")
    }

    // Reset the singleton bridge and release the WebView when the editor
    // overlay goes away (the WebView used to leak across editor sessions).
    DisposableEffect(Unit) {
        onDispose {
            editorBridge = null
            EditorBridge.webViewRef = null
            webViewState.value?.destroy()
            webViewState.value = null
        }
    }
}

// ---------------------------------------------------------------------------
// Small composables
// ---------------------------------------------------------------------------

@Composable
private fun EditorTabChip(
    title: String,
    dirty: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val colorScheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colorScheme.secondaryContainer else colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (dirty) "$title •" else title,
            color = if (selected) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.subtitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
        Icon(
            imageVector = MiuixIcons.Close,
            contentDescription = stringResource(R.string.dialog_cancel),
            tint = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .padding(start = 6.dp)
                .size(14.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
        )
    }
}

@Composable
private fun FindReplaceBar(
    readOnly: Boolean,
    onFind: (query: String, caseSensitive: Boolean) -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onReplace: (replacement: String) -> Unit,
    onReplaceAll: (query: String, replacement: String, caseSensitive: Boolean) -> Unit,
) {
    val colorScheme = MiuixTheme.colorScheme
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var replacement by remember { mutableStateOf(TextFieldValue("")) }
    var caseSensitive by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextField(
            value = query,
            onValueChange = { query = it },
            label = stringResource(R.string.editor_find),
            useLabelAsPlaceholder = true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NekoTextButton(
                text = "Aa",
                onClick = { caseSensitive = !caseSensitive },
                modifier = Modifier.widthIn(min = 52.dp),
            )
            NekoTextButton(
                text = "↑",
                onClick = onPrev,
                modifier = Modifier.widthIn(min = 52.dp),
            )
            NekoTextButton(
                text = "↓",
                onClick = onNext,
                modifier = Modifier.widthIn(min = 52.dp),
            )
            NekoTextButton(
                text = stringResource(R.string.editor_find_apply),
                onClick = { onFind(query.text, caseSensitive) },
                modifier = Modifier.weight(1f),
            )
        }
        if (!readOnly) {
            TextField(
                value = replacement,
                onValueChange = { replacement = it },
                label = stringResource(R.string.editor_replace),
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                NekoTextButton(
                    text = stringResource(R.string.editor_replace_one),
                    onClick = { onReplace(replacement.text) },
                    modifier = Modifier.weight(1f),
                )
                NekoTextButton(
                    text = stringResource(R.string.editor_replace_all),
                    onClick = { onReplaceAll(query.text, replacement.text, caseSensitive) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            text = if (caseSensitive) {
                stringResource(R.string.editor_case_sensitive_on)
            } else {
                stringResource(R.string.editor_case_sensitive_off)
            },
            color = colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.subtitle,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun SymbolBar(
    enabled: Boolean,
    symbols: List<String>,
    onInsert: (String) -> Unit,
) {
    val colorScheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        symbols.forEach { symbol ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colorScheme.surfaceContainer)
                    .clickable(enabled = enabled) { onInsert(if (symbol == "\t") "\t" else symbol) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = if (symbol == "\t") stringResource(R.string.editor_tab_key) else symbol,
                    color = colorScheme.onSurfaceContainer,
                    style = MiuixTheme.textStyles.main,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun modeFor(target: EditorTarget): String {
    val name = target.file.name.lowercase()
    return when {
        name.endsWith(".php") -> "php"
        name.endsWith(".html") || name.endsWith(".htm") -> "htmlmixed"
        name.endsWith(".xml") -> "xml"
        name.endsWith(".css") -> "css"
        name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".json") -> "javascript"
        name.endsWith(".sh") || name.endsWith(".bash") -> "shell"
        name.endsWith(".yaml") || name.endsWith(".yml") -> "yaml"
        // INI-style files: MariaDB's my.cnf, php.ini, php-fpm.conf and friends
        // use [section] + key = value syntax, so the properties mode is the
        // correct highlighting for them (not nginx).
        name == "php-fpm.conf" || name.endsWith(".cnf") || name.endsWith(".ini") -> "properties"
        // Server configs that genuinely follow the nginx-style directive
        // grammar (nginx.conf, lighttpd.conf, httpd.conf, vhosts).
        name.endsWith(".conf") || name.endsWith(".vhost") -> "nginx"
        else -> "properties" // .log / .env / .properties / everything else
    }
}

private fun symbolsFor(target: EditorTarget): List<String> {
    val mode = modeFor(target)
    return when {
        mode == "shell" -> listOf("$", "|", "-", ">", "<", "/", "=", "\"", "\t")
        mode == "php" -> listOf("$", "->", ";", "<", ">", "{", "}", "(", ")", "\"", ":", "\t")
        mode == "javascript" || mode == "css" -> listOf("{", "}", "(", ")", ";", ":", "=", "\"", "'", ".", "\t")
        mode == "htmlmixed" || mode == "xml" -> listOf("<", ">", "/>", "</", "=", "\"", "\t")
        mode == "yaml" -> listOf(": ", "-", " ", "#", "\"", "\t")
        else -> listOf("#", "=", "[", "]", "$", "\"", ",", ":", "\t")
    }
}

private fun jsString(s: String): String = JSONObject.quote(s)
