package com.example.translate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.translate.data.DictionaryRepository
import com.example.translate.ui.theme.Background
import com.example.translate.ui.theme.DividerSubtle
import com.example.translate.ui.theme.Gold
import com.example.translate.ui.theme.OnHigh
import com.example.translate.ui.theme.OnMuted

@Composable
fun SearchScreen(repository: DictionaryRepository, modifier: Modifier = Modifier) {
    // Phase 4 will wire this field to repository.search() with a debounced flow.
    // For now it's a styled placeholder so we can confirm theme + pager render.
    var query by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize().background(Background).padding(horizontal = 20.dp)) {
        Text(
            text = "Translate",
            style = MaterialTheme.typography.labelMedium,
            color = OnMuted,
            modifier = Modifier.padding(top = 28.dp, bottom = 16.dp),
        )

        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search", color = OnMuted, style = MaterialTheme.typography.titleMedium) },
            singleLine = true,
            textStyle = TextStyle(color = OnHigh, fontSize = MaterialTheme.typography.titleMedium.fontSize),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                cursorColor = Gold,
                focusedIndicatorColor = Gold,
                unfocusedIndicatorColor = DividerSubtle,
                focusedTextColor = OnHigh,
                unfocusedTextColor = OnHigh,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Box(modifier = Modifier.fillMaxSize()) {
            // Empty results area — Phase 4 fills this.
        }
    }
}
