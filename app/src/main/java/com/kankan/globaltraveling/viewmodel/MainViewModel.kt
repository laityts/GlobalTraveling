package com.kankan.globaltraveling.viewmodel

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.kankan.globaltraveling.App
import com.kankan.globaltraveling.SimulationForegroundService
import com.kankan.globaltraveling.data.LocationData
import com.kankan.globaltraveling.data.LocationRepository
import com.kankan.globaltraveling.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.coroutines.resume

class MainViewModel : ViewModel() {
    private val repo = LocationRepository()
    private val prefs: SharedPreferences = App.instance.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE)
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(App.instance)

    private val _selectedLocation = MutableStateFlow(LocationData(39.9042, 116.4074, "天安门"))
    val selectedLocation: StateFlow<LocationData> = _selectedLocation.asStateFlow()

    private val _isSimulating = MutableStateFlow(false)
    val isSimulating: StateFlow<Boolean> = _isSimulating.asStateFlow()

    private val _toastMessage = MutableStateFlow("")
    val toastMessage: StateFlow<String> = _toastMessage.asStateFlow()

    private val _favorites = MutableStateFlow<List<com.kankan.globaltraveling.data.Favorite>>(emptyList())
    val favorites: StateFlow<List<com.kankan.globaltraveling.data.Favorite>> = _favorites.asStateFlow()

    private val _defaultLocation = MutableStateFlow<LocationData?>(null)
    val defaultLocation: StateFlow<LocationData?> = _defaultLocation.asStateFlow()

    private val _autoUpdateOnSelect = MutableStateFlow(prefs.getBoolean("auto_update", true))
    val autoUpdateOnSelect: StateFlow<Boolean> = _autoUpdateOnSelect.asStateFlow()

    // 权限请求触发器
    private val _requestPermissions = MutableStateFlow(false)
    val requestPermissions: StateFlow<Boolean> = _requestPermissions.asStateFlow()

    init {
        loadFavorites()
        loadDefault()
        checkSimulationStatus()
        // 首次启动时请求权限（如果还未授予）
        if (!hasLocationPermissions()) {
            triggerPermissionRequest()
        }
    }

    fun toggleAutoUpdate(enabled: Boolean) {
        _autoUpdateOnSelect.value = enabled
        prefs.edit().putBoolean("auto_update", enabled).apply()
        Logger.i("Auto update toggled: $enabled")
    }

    private fun checkSimulationStatus() {
        viewModelScope.launch {
            val status = repo.checkSimulationStatus()
            _isSimulating.value = status
            if (status) {
                val defaultLoc = repo.getDefaultLocation()
                defaultLoc?.let {
                    _selectedLocation.value = it
                    showToast("模拟已激活，位置: ${it.name}")
                    startForegroundService(it.name)
                }
                Logger.i("Simulation active, default location: ${defaultLoc?.name}")
            }
        }
    }

    private fun startForegroundService(locationName: String) {
        try {
            SimulationForegroundService.start(App.instance, locationName)
            Logger.d("Foreground service started for: $locationName")
        } catch (e: Exception) {
            Logger.e("Foreground service start failed", e)
            showToast("无法启动前台服务，请授予通知权限")
        }
    }

    private fun stopForegroundService() {
        try {
            SimulationForegroundService.stop(App.instance)
            Logger.d("Foreground service stopped")
        } catch (e: Exception) {
            Logger.e("Foreground service stop failed", e)
        }
    }

    fun updateSelectedLocation(lat: Double, lng: Double, name: String = "选点") {
        _selectedLocation.value = LocationData(lat, lng, name)
        Logger.i("Selected location updated: $name ($lat, $lng)")
        if (_autoUpdateOnSelect.value && _isSimulating.value) {
            restartSimulation()
        }
    }

    fun restartSimulation() {
        if (_isSimulating.value) {
            viewModelScope.launch {
                val loc = _selectedLocation.value
                val success = repo.writeLocation(loc.lat, loc.lng, true)
                if (success) {
                    showToast("模拟位置已更新：${loc.name}")
                    startForegroundService(loc.name)
                    Logger.i("Simulation restarted: ${loc.name}")
                } else {
                    showToast("更新模拟位置失败，请检查 Root 权限")
                    Logger.e("Failed to write location to /data/local/tmp/")
                }
            }
        }
    }

    fun startSimulation() {
        val loc = _selectedLocation.value
        if (loc.lat == 0.0 && loc.lng == 0.0) {
            showToast("请先选择有效位置")
            return
        }
        viewModelScope.launch {
            val success = repo.writeLocation(loc.lat, loc.lng, true)
            if (success) {
                _isSimulating.value = true
                showToast("模拟已启动：${loc.name} (${String.format("%.4f", loc.lat)}, ${String.format("%.4f", loc.lng)})")
                startForegroundService(loc.name)
                Logger.i("Simulation started: ${loc.name}")
            } else {
                showToast("模拟启动失败，请检查 Root 权限")
                Logger.e("Failed to start simulation, writeLocation returned false")
            }
        }
    }

    fun stopSimulation() {
        viewModelScope.launch {
            val success = repo.writeLocation(0.0, 0.0, false)
            if (success) {
                _isSimulating.value = false
                showToast("模拟已停止，位置恢复真实值")
                stopForegroundService()
                Logger.i("Simulation stopped")
            } else {
                showToast("停止失败，请检查权限")
                Logger.e("Failed to stop simulation")
            }
        }
    }

    fun setAsDefault() {
        val loc = _selectedLocation.value
        if (loc.lat != 0.0 || loc.lng != 0.0) {
            repo.saveDefaultLocation(loc.lat, loc.lng, loc.name)
            _defaultLocation.value = loc
            showToast("已设为默认位置：${loc.name}")
            Logger.i("Default location set: ${loc.name}")
        }
    }

    fun addFavorite(name: String, lat: Double, lng: Double) {
        viewModelScope.launch {
            val finalName = if (name in listOf("选点", "坐标点", "手动选点", "坐标编辑", "我的位置")) {
                getAddressFromLocation(lat, lng) ?: String.format("%.5f,%.5f", lat, lng)
            } else {
                name
            }
            repo.addFavorite(finalName, lat, lng)
            loadFavorites()
            showToast("已收藏：$finalName")
            Logger.i("Favorite added: $finalName")
        }
    }

    fun editFavorite(oldName: String, newName: String, lat: Double, lng: Double) {
        repo.removeFavorite(oldName)
        repo.addFavorite(newName, lat, lng)
        loadFavorites()
        showToast("已更新为：$newName")
        Logger.i("Favorite edited: $oldName -> $newName")
    }

    private suspend fun getAddressFromLocation(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val geocoder = Geocoder(App.instance, Locale.CHINA)
            val addresses: List<Address> = geocoder.getFromLocation(lat, lng, 1) ?: emptyList()
            addresses.firstOrNull()?.getAddressLine(0) ?: addresses.firstOrNull()?.featureName
        } catch (e: Exception) {
            Logger.e("Geocoder failed", e)
            null
        }
    }

    fun removeFavorite(name: String) {
        repo.removeFavorite(name)
        loadFavorites()
        showToast("已删除：$name")
        Logger.i("Favorite removed: $name")
    }

    fun moveToMyLocation() {
        if (!hasLocationPermissions()) {
            triggerPermissionRequest()
            showToast("需要位置权限才能获取真实位置")
            return
        }
        viewModelScope.launch {
            showToast("正在获取真实位置（请确保 GPS 已开启）...")
            val realLocation = getRealLocationUsingGms(15000)
            if (realLocation != null) {
                updateSelectedLocation(realLocation.latitude, realLocation.longitude, "我的位置")
                showToast("已定位到真实位置: ${realLocation.latitude}, ${realLocation.longitude}")
                Logger.i("Moved to real location: ${realLocation.latitude}, ${realLocation.longitude}")
            } else {
                showToast("无法获取真实位置。请检查：\n1. GPS 是否已开启\n2. 位置权限是否授予\n3. Google Play 服务是否可用")
                Logger.e("Failed to get real location")
            }
        }
    }

    private fun hasLocationPermissions(): Boolean {
        val fine = ActivityCompat.checkSelfPermission(App.instance, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(App.instance, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED && coarse == PackageManager.PERMISSION_GRANTED
    }

    fun triggerPermissionRequest() {
        _requestPermissions.value = true
    }

    fun onPermissionsRequestHandled() {
        _requestPermissions.value = false
    }

    // 使用 Google FusedLocationProviderClient 获取真实位置（绕过 Hook）
    private suspend fun getRealLocationUsingGms(timeoutMs: Long): Location? =
        suspendCancellableCoroutine { continuation ->
            if (!hasLocationPermissions()) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val locationManager = App.instance.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Log.e("MainViewModel", "GPS 未开启")
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null && System.currentTimeMillis() - location.time < 10000) {
                    Log.d("MainViewModel", "使用缓存的 GMS 位置: ${location.latitude}, ${location.longitude}")
                    continuation.resume(location)
                    return@addOnSuccessListener
                }
                val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
                    com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                    5000
                ).setWaitForAccurateLocation(false).build()
                val locationCallback = object : com.google.android.gms.location.LocationCallback() {
                    override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                        super.onLocationResult(result)
                        val loc = result.lastLocation
                        if (loc != null) {
                            Log.d("MainViewModel", "GMS 新位置: ${loc.latitude}, ${loc.longitude}")
                            continuation.resume(loc)
                        } else {
                            continuation.resume(null)
                        }
                        fusedLocationClient.removeLocationUpdates(this)
                    }
                }
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, App.instance.mainLooper)
                android.os.Handler(App.instance.mainLooper).postDelayed({
                    if (continuation.isActive) {
                        Log.e("MainViewModel", "GMS 定位超时")
                        continuation.resume(null)
                        fusedLocationClient.removeLocationUpdates(locationCallback)
                    }
                }, timeoutMs)
            }.addOnFailureListener { e ->
                Log.e("MainViewModel", "GMS 定位失败", e)
                continuation.resume(null)
            }
        }

    private fun loadFavorites() {
        _favorites.value = repo.getFavorites()
        Logger.d("Favorites loaded, count: ${_favorites.value.size}")
    }

    private fun loadDefault() {
        _defaultLocation.value = repo.getDefaultLocation()
        _defaultLocation.value?.let {
            _selectedLocation.value = it
            Logger.i("Default location loaded: ${it.name}")
        }
    }

    fun onPermissionsGranted() {
        repo.ensureIdentity()
        showToast("权限已授予，可以开始使用")
        Logger.i("All required permissions granted")
        // 如果权限刚刚授予，可以尝试重新获取位置（可选）
    }

    fun showToast(message: String) {
        _toastMessage.value = message
        Logger.i("Toast: $message")
    }

    fun clearToastMessage() {
        _toastMessage.value = ""
    }
}