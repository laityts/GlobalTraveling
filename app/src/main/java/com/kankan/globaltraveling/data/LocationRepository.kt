package com.kankan.globaltraveling.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.kankan.globaltraveling.App
import com.kankan.globaltraveling.LocationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.util.*

class LocationRepository(private val context: Context = App.instance) {

    private val prefs: SharedPreferences = context.getSharedPreferences("shadow_data", Context.MODE_PRIVATE)
    private val FILE_PATH = "/data/local/tmp/irest_loc.conf"
    private val BACKUP_FILE_PATH = "${context.filesDir.absolutePath}/irest_loc.conf"

    suspend fun writeLocation(lat: Double, lng: Double, enable: Boolean): Boolean = withContext(Dispatchers.IO) {
        val mac = prefs.getString("mac", generateRandomMac())!!
        val ssid = prefs.getString("ssid", generateRandomSSID())!!
        val content = if (enable) "$lat,$lng,1,$mac,$ssid" else "0,0,0,0,0"

        var success = writeWithSu(content, FILE_PATH)
        if (!success) {
            try {
                File(BACKUP_FILE_PATH).writeText(content)
                File(BACKUP_FILE_PATH).setReadable(true, false)
                success = true
            } catch (e: Exception) {
                Log.e("LocationRepo", "写入备用文件失败", e)
            }
        }
        LocationProvider.updateLocation(lat, lng, enable, mac, ssid)

        val check = checkSimulationStatus()
        if (enable == check) return@withContext true
        Log.e("LocationRepo", "写入后状态验证失败: enable=$enable, check=$check")
        return@withContext false
    }

    private fun writeWithSu(content: String, path: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            DataOutputStream(process.outputStream).use { os ->
                os.writeBytes("echo \"$content\" > $path\n")
                os.writeBytes("chmod 666 $path\n")
                try { os.writeBytes("chcon u:object_r:shell_data_file:s0 $path\n") } catch (_: Throwable) {}
                os.writeBytes("sync\n")
                os.writeBytes("exit\n")
                os.flush()
            }
            process.waitFor() == 0
        } catch (e: Exception) {
            Log.e("LocationRepo", "su 写入失败", e)
            false
        }
    }

    fun checkSimulationStatus(): Boolean {
        return try {
            val file = File(FILE_PATH)
            val content = if (file.exists() && file.canRead()) {
                file.readText()
            } else {
                File(BACKUP_FILE_PATH).takeIf { it.exists() }?.readText()
            } ?: return false
            val parts = content.split(",")
            parts.size >= 3 && parts[2] == "1"
        } catch (e: Exception) {
            Log.e("LocationRepo", "检查模拟状态失败", e)
            false
        }
    }

    fun ensureIdentity() {
        if (prefs.getString("mac", null) == null) {
            prefs.edit {
                putString("mac", generateRandomMac())
                putString("ssid", generateRandomSSID())
            }
        }
    }

    fun addFavorite(name: String, lat: Double, lng: Double) {
        val list = getFavorites().toMutableList()
        list.removeAll { it.name == name && it.lat == lat && it.lng == lng }
        list.add(0, Favorite(name, lat, lng))
        if (list.size > 12) list.removeAt(list.lastIndex)
        saveFavoritesToPrefs(list)
    }

    fun getFavorites(): List<Favorite> = getFavoritesFromPrefs()

    fun removeFavorite(name: String) {
        val list = getFavorites().toMutableList()
        list.removeAll { it.name == name }
        saveFavoritesToPrefs(list)
    }

    fun saveDefaultLocation(lat: Double, lng: Double, name: String) {
        prefs.edit {
            putFloat("def_lat", lat.toFloat())
            putFloat("def_lng", lng.toFloat())
            putString("def_name", name)
        }
    }

    fun getDefaultLocation(): LocationData? {
        val lat = prefs.getFloat("def_lat", 0f)
        val lng = prefs.getFloat("def_lng", 0f)
        return if (lat != 0f || lng != 0f) LocationData(lat.toDouble(), lng.toDouble(), prefs.getString("def_name", "默认")!!)
        else null
    }

    private fun saveFavoritesToPrefs(list: List<Favorite>) {
        val str = list.joinToString(",") { "${it.name}|${it.lat}|${it.lng}" }
        prefs.edit().putString("favorites", str).apply()
    }

    private fun getFavoritesFromPrefs(): List<Favorite> {
        val raw = prefs.getString("favorites", "") ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split(",").mapNotNull { item ->
            val parts = item.split("|")
            if (parts.size == 3) Favorite(parts[0], parts[1].toDouble(), parts[2].toDouble())
            else null
        }
    }

    private fun generateRandomMac(): String {
        val sb = StringBuilder("ac")
        repeat(5) {
            sb.append(":")
            val hex = Integer.toHexString(Random().nextInt(256))
            if (hex.length < 2) sb.append("0")
            sb.append(hex)
        }
        return sb.toString().lowercase()
    }

    private fun generateRandomSSID(): String {
        val brands = arrayOf("TP-LINK", "Xiaomi", "HUAWEI", "Office-Guest", "ShadowNet")
        return "${brands[Random().nextInt(brands.size)]}_${1000 + Random().nextInt(8999)}"
    }
}

data class Favorite(val name: String, val lat: Double, val lng: Double)
data class LocationData(val lat: Double, val lng: Double, val name: String)