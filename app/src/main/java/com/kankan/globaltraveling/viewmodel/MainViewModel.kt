package com.kankan.globaltraveling.viewmodel

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.kankan.globaltraveling.App
import com.kankan.globaltraveling.SimulationForegroundService
import com.kankan.globaltraveling.data.LocationData
import com.kankan.globaltraveling.data.LocationRepository
import com.kankan.globaltraveling.utils.LocationHelper
import com.kankan.globaltraveling.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

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

    private val _requestPermissions = MutableStateFlow(false)
    val requestPermissions: StateFlow<Boolean> = _requestPermissions.asStateFlow()

    init {
        loadFavorites()
        loadDefault()
        checkSimulationStatus()
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
        if (loc.lat == 0.0 || loc.lng == 0.0) {
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

    /**
     * WGS-84 转 GCJ-02 火星坐标系 (中国标准)
     * 来源: https://github.com/wandergis/coordTransform_py/blob/master/coordTransform_utils.py
     */
    private fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        val a = 6378245.0
        val ee = 0.00669342162296594323

        fun transformLat(x: Double, y: Double): Double {
            var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
            ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
            ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
            ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
            return ret
        }

        fun transformLng(x: Double, y: Double): Double {
            var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
            ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
            ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
            ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
            return ret
        }

        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = Math.sin(radLat)
        magic = 1 - ee * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * Math.PI)
        dLng = (dLng * 180.0) / (a / sqrtMagic * Math.cos(radLat) * Math.PI)
        val mgLat = lat + dLat
        val mgLng = lng + dLng
        return Pair(mgLat, mgLng)
    }

    fun moveToMyLocation() {
        if (!hasLocationPermissions()) {
            triggerPermissionRequest()
            showToast("需要位置权限才能获取真实位置，请授权后重试")
            return
        }
        viewModelScope.launch {
            showToast("正在获取真实位置（请确保 GPS 已开启）...")
            val realLocation = LocationHelper.getCurrentLocation()
            if (realLocation != null) {
                // 将 WGS-84 坐标转换为高德使用的 GCJ-02 坐标，避免偏移
                val (gcjLat, gcjLng) = wgs84ToGcj02(realLocation.latitude, realLocation.longitude)
                updateSelectedLocation(gcjLat, gcjLng, "我的位置")
                showToast("已定位到真实位置: ${String.format("%.6f", gcjLat)}, ${String.format("%.6f", gcjLng)}")
                Logger.i("Moved to real location (WGS84→GCJ02): $gcjLat, $gcjLng")
            } else {
                showToast("无法获取真实位置。请检查：\n1. GPS 是否已开启\n2. 位置权限是否授予\n3. Google Play 服务是否可用（或设备支持GPS）")
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
    }

    fun showToast(message: String) {
        _toastMessage.value = message
        Logger.i("Toast: $message")
    }

    fun clearToastMessage() {
        _toastMessage.value = ""
    }
}