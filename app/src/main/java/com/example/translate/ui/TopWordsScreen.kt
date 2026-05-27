package com.example.translate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.translate.data.DictionaryRepository
import com.example.translate.ui.theme.Background
import com.example.translate.ui.theme.OnMuted

@Composable
fun TopWordsScreen(repository: DictionaryRepository, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().background(Background).padding(horizontal = 20.dp)) {
        Text(
            text = "Top 50",
            style = MaterialTheme.typography.labelMedium,
            color = OnMuted,
            modifier = Modifier.padding(top = 28.dp),
        )
        // Phase 6 fills this with repository.topStream().
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {}
    }
}
