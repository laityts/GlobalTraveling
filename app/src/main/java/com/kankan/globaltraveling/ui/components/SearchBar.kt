package com.kankan.globaltraveling.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kankan.globaltraveling.utils.AMapSearchManager
import com.kankan.globaltraveling.utils.TipInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBar(
    onLocationSelected: (Double, Double, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchText by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<TipInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    // 初始化已在 App 中完成，此处无需重复
    Column(modifier = modifier) {
        SearchBar(
            query = searchText,
            onQueryChange = { text ->
                searchText = text
                debounceJob?.cancel()
                if (text.isNotBlank()) {
                    isLoading = true
                    debounceJob = scope.launch {
                        delay(300)
                        val results = AMapSearchManager.getInputtips(text)
                        suggestions = results
                        isLoading = false
                    }
                } else {
                    suggestions = emptyList()
                    isLoading = false
                }
            },
            onSearch = { text ->
                if (text.isNotBlank()) {
                    isLoading = true
                    scope.launch {
                        val results = AMapSearchManager.getInputtips(text)
                        suggestions = results
                        isLoading = false
                    }
                }
            },
            active = isExpanded,
            onActiveChange = { isExpanded = it },
            placeholder = { Text("搜索地点（高德地图）") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchText.isNotEmpty()) {
                    IconButton(onClick = {
                        searchText = ""
                        suggestions = emptyList()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "清除")
                    }
                }
            }
        ) {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
                suggestions.isNotEmpty() -> {
                    LazyColumn {
                        items(suggestions) { tip ->
                            SuggestionItem(
                                tip = tip,
                                onClick = {
                                    onLocationSelected(tip.lat, tip.lng, tip.name)
                                    searchText = ""
                                    suggestions = emptyList()
                                    isExpanded = false
                                }
                            )
                        }
                    }
                }
                searchText.isNotEmpty() && !isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("未找到结果", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun SuggestionItem(
    tip: TipInfo,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(tip.name) },
        supportingContent = {
            Text(
                text = tip.address ?: "",
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall
            )
        },
        leadingContent = { Icon(Icons.Default.Search, contentDescription = null) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    )
}