package com.kankan.globaltraveling.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kankan.globaltraveling.ui.components.AMapView
import com.kankan.globaltraveling.ui.components.SearchBar
import com.kankan.globaltraveling.ui.components.SideMenu
import com.kankan.globaltraveling.utils.showCenteredToast
import com.kankan.globaltraveling.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectedLocation by viewModel.selectedLocation.collectAsStateWithLifecycle()
    val isSimulating by viewModel.isSimulating.collectAsStateWithLifecycle()
    val toastMessage by viewModel.toastMessage.collectAsStateWithLifecycle()

    var mapCenter by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var latText by remember { mutableStateOf(selectedLocation.lat.toString()) }
    var lngText by remember { mutableStateOf(selectedLocation.lng.toString()) }

    LaunchedEffect(toastMessage) {
        if (toastMessage.isNotEmpty()) {
            context.showCenteredToast(toastMessage)
            viewModel.clearToastMessage()
        }
    }

    LaunchedEffect(selectedLocation) {
        latText = selectedLocation.lat.toString()
        lngText = selectedLocation.lng.toString()
        mapCenter = selectedLocation.lat to selectedLocation.lng
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SideMenu(
                viewModel = viewModel,
                isSimulating = isSimulating,
                onCloseDrawer = { scope.launch { drawerState.close() } }
            )
        },
        gesturesEnabled = true
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("位置模拟器") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "菜单")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (isSimulating)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.primaryContainer
                    ),
                    actions = {
                        Icon(
                            if (isSimulating) Icons.Default.Warning else Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                SearchBar(
                    onLocationSelected = { lat, lng, name ->
                        viewModel.updateSelectedLocation(lat, lng, name)
                        if (isSimulating) viewModel.restartSimulation()
                        viewModel.showToast("已定位到：$name")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )

                // 地图区域
                Box(
                    modifier = Modifier
                        .weight(0.65f)
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    AMapView(
                        initialLat = selectedLocation.lat,
                        initialLng = selectedLocation.lng,
                        onLongPress = { lat, lng ->
                            viewModel.updateSelectedLocation(lat, lng, "手动选点")
                            if (isSimulating) viewModel.restartSimulation()
                            viewModel.showToast("已选点：${String.format("%.4f", lat)}, ${String.format("%.4f", lng)}")
                        },
                        centerTo = mapCenter
                    )
                }

                // 底部控制面板
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 坐标输入行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = latText,
                                onValueChange = { latText = it },
                                label = { Text("纬度") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                            OutlinedTextField(
                                value = lngText,
                                onValueChange = { lngText = it },
                                label = { Text("经度") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                            Button(
                                onClick = {
                                    val lat = latText.toDoubleOrNull()
                                    val lng = lngText.toDoubleOrNull()
                                    if (lat != null && lng != null) {
                                        viewModel.updateSelectedLocation(lat, lng, "坐标编辑")
                                        if (isSimulating) viewModel.restartSimulation()
                                        viewModel.showToast("坐标已更新")
                                    } else {
                                        viewModel.showToast("请输入有效数字")
                                    }
                                },
                                modifier = Modifier.wrapContentWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("应用")
                            }
                        }

                        // 主要操作按钮行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (isSimulating) viewModel.stopSimulation()
                                    else viewModel.startSimulation()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSimulating) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    if (isSimulating) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (isSimulating) "停止模拟" else "开始模拟")
                            }

                            OutlinedButton(
                                onClick = { viewModel.moveToMyLocation() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("我的位置")
                            }
                        }

                        // 辅助操作行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.setAsDefault() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("设为默认")
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.addFavorite(
                                        selectedLocation.name,
                                        selectedLocation.lat,
                                        selectedLocation.lng
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("收藏")
                            }
                        }

                        // 提示文字
                        Text(
                            text = "长按地图可自由选点",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}