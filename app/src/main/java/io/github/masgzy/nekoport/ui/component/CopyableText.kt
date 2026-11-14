package io.github.masgzy.nekoport.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import io.github.masgzy.nekoport.R
import top.yukonga.miuix.kmp.basic.Text

/**
 * A plain Text whose content lands in the clipboard on long press, with a
 * short "copied" toast for feedback. Error/status messages (FTP setup
 * failures, MariaDB bootstrap errors, launch-log tails...) are the primary
 * use case - users should not have to OCR a screenshot to file a bug.
 */
@Composable
fun CopyableText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val context = LocalContext.current
    Text(
        text = text,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier.pointerInput(text) {
            detectTapGestures(
                onLongPress = {
                    runCatching {
                        val cm =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("NekoPort", text))
                        Toast.makeText(
                            context,
                            context.getString(R.string.logs_copied),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        },
    )
}
