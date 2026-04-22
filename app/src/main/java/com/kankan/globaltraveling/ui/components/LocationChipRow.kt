package com.kankan.globaltraveling.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kankan.globaltraveling.data.Favorite

@Composable
fun LocationChipRow(
    items: List<Favorite>,
    onChipClick: (Favorite) -> Unit,
    onDelete: (Favorite) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { item ->
            AssistChip(
                onClick = { onChipClick(item) },
                label = { Text(item.name) },
                trailingIcon = {
                    IconButton(modifier = Modifier.size(20.dp), onClick = { onDelete(item) }) {
                        Icon(Icons.Default.Close, contentDescription = "删除", modifier = Modifier.size(16.dp))
                    }
                },
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}