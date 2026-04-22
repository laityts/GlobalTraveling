package com.kankan.globaltraveling.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kankan.globaltraveling.data.Favorite
import com.kankan.globaltraveling.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SideMenu(
    viewModel: MainViewModel,
    isSimulating: Boolean,
    onCloseDrawer: () -> Unit
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val autoUpdate by viewModel.autoUpdateOnSelect.collectAsStateWithLifecycle()
    var editDialogVisible by remember { mutableStateOf(false) }
    var editingFavorite by remember { mutableStateOf<Favorite?>(null) }
    var newName by remember { mutableStateOf("") }

    if (editDialogVisible && editingFavorite != null) {
        AlertDialog(
            onDismissRequest = { editDialogVisible = false },
            title = { Text("编辑收藏名称") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("新名称") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        viewModel.editFavorite(editingFavorite!!.name, newName, editingFavorite!!.lat, editingFavorite!!.lng)
                    }
                    editDialogVisible = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editDialogVisible = false }) {
                    Text("取消")
                }
            }
        )
    }

    ModalDrawerSheet(
        modifier = Modifier.width(300.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("位置助手", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 16.dp))
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = if (isSimulating) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (isSimulating) Icons.Filled.Warning else Icons.Filled.CheckCircle, null, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(if (isSimulating) "模拟中" else "未模拟", style = MaterialTheme.typography.titleMedium)
                                Text(if (isSimulating) "位置已被修改" else "位置为真实值", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("选点后自动更新模拟位置", modifier = Modifier.weight(1f))
                        Switch(checked = autoUpdate, onCheckedChange = { viewModel.toggleAutoUpdate(it) })
                    }
                }
                item {
                    HorizontalDivider()
                    Text("我的收藏", style = MaterialTheme.typography.titleMedium)
                }
                if (favorites.isEmpty()) {
                    item { Text("暂无收藏", style = MaterialTheme.typography.bodySmall) }
                } else {
                    items(favorites) { favorite ->
                        SwipeToDeleteItem(
                            favorite = favorite,
                            onItemClick = {
                                viewModel.updateSelectedLocation(favorite.lat, favorite.lng, favorite.name)
                                if (isSimulating) viewModel.restartSimulation()
                                onCloseDrawer()
                            },
                            onDelete = { viewModel.removeFavorite(favorite.name) },
                            onEdit = {
                                editingFavorite = favorite
                                newName = favorite.name
                                editDialogVisible = true
                            }
                        )
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.padding(top = 16.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("⚠️ Xposed 设置", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                            Text("请在 Xposed 模块中勾选「系统框架」(Android System) 「目标应用」以确保 Hook 生效", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text("长按地图选点", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SwipeToDeleteItem(
    favorite: Favorite,
    onItemClick: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    var offsetX by remember { mutableStateOf(0f) }
    val deleteThreshold = -150f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount).coerceIn(-200f, 0f)
                    },
                    onDragEnd = {
                        if (offsetX < deleteThreshold) {
                            onDelete()
                        }
                        offsetX = 0f
                    }
                )
            },
        onClick = onItemClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = offsetX.dp)
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(favorite.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    String.format("%.5f, %.5f", favorite.lat, favorite.lng),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
            }
        }
    }
}