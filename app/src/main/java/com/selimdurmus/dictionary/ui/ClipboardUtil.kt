package com.selimdurmus.dictionary.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.selimdurmus.dictionary.data.Entry

/** Copy a "source → target" entry to the clipboard and confirm with a short toast. */
fun copyEntry(clipboard: ClipboardManager, context: Context, entry: Entry) {
    clipboard.setText(AnnotatedString("${entry.sourceWord} → ${entry.targetWord}"))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
