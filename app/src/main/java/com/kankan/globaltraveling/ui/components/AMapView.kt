package com.kankan.globaltraveling.ui.components

import android.os.Bundle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions

@Composable
fun AMapView(
    initialLat: Double,
    initialLng: Double,
    onLongPress: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
    // 新增：外部控制相机移动的触发器
    centerTo: Pair<Double, Double>? = null
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val mapReady = remember { mutableStateOf(false) }
    
    // 监听外部相机移动请求
    LaunchedEffect(centerTo) {
        if (mapReady.value && centerTo != null) {
            val aMap = mapView.map
            aMap.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(centerTo.first, centerTo.second), 
                    15f
                )
            )
            // 添加标记
            aMap.clear()
            aMap.addMarker(
                MarkerOptions()
                    .position(LatLng(centerTo.first, centerTo.second))
                    .title("选中位置")
            )
        }
    }
    
    DisposableEffect(Unit) {
        mapView.onCreate(Bundle())
        mapView.onResume()
        val aMap = mapView.map
        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(initialLat, initialLng), 15f))
        aMap.setOnMapLongClickListener { latLng ->
            aMap.clear()
            aMap.addMarker(MarkerOptions().position(latLng).title("选点"))
            onLongPress(latLng.latitude, latLng.longitude)
        }
        mapReady.value = true
        onDispose {
            mapView.onPause()
            mapView.onDestroy()
        }
    }
    
    AndroidView(
        factory = { mapView },
        modifier = modifier.fillMaxSize()
    )
}