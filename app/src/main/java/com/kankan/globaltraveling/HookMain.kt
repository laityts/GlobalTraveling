package com.kankan.globaltraveling

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Parcel
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.lang.reflect.Modifier
import java.util.*

class HookMain : IXposedHookLoadPackage {

    companion object {
        private const val FILE_PATH = "/data/local/tmp/irest_loc.conf"
        private const val BACKUP_FILE_PATH = "/data/data/com.kankan.globaltraveling/files/irest_loc.conf"

        private var cachedCoord: DoubleArray? = null
        private var cachedMac = "02:00:00:00:00:00"
        private var cachedSSID = "Shadow-WiFi"
        private var lastModified = 0L

        private fun logToFile(msg: String) {
            try {
                File("/data/local/tmp/xposed_hook.log").appendText("${System.currentTimeMillis()} $msg\n")
            } catch (_: Throwable) {}
        }

        private fun refreshCache() {
            try {
                var targetFile = File(FILE_PATH)
                if (!targetFile.exists() || !targetFile.canRead()) {
                    targetFile = File(BACKUP_FILE_PATH)
                }
                if (targetFile.exists() && targetFile.canRead()) {
                    val modified = targetFile.lastModified()
                    if (modified == lastModified && cachedCoord != null) return
                    lastModified = modified
                    val content = targetFile.readText()
                    val parts = content.split(",")
                    if (parts.size >= 3 && parts[2] == "1") {
                        if (parts.size >= 5) {
                            cachedMac = parts[3]
                            cachedSSID = parts[4]
                        }
                        cachedCoord = doubleArrayOf(parts[0].toDouble(), parts[1].toDouble())
                        logToFile("Cache refreshed: ${cachedCoord!![0]},${cachedCoord!![1]} | $cachedMac | $cachedSSID")
                        return
                    }
                }
                cachedCoord = null
                logToFile("Cache cleared (no valid config)")
            } catch (e: Exception) {
                logToFile("refreshCache error: ${e.message}")
                cachedCoord = null
            }
        }

        private fun getCoord(): DoubleArray? {
            refreshCache()
            return cachedCoord
        }

        private fun getOrCreateDrift(location: Location): Pair<Double, Double> {
            val tag = "shadow_drift_lat"
            var latDrift = XposedHelpers.getAdditionalInstanceField(location, tag) as? Double
            var lngDrift = XposedHelpers.getAdditionalInstanceField(location, "shadow_drift_lng") as? Double
            if (latDrift == null || lngDrift == null) {
                val random = Random()
                latDrift = (random.nextDouble() - 0.5) * 0.00002
                lngDrift = (random.nextDouble() - 0.5) * 0.00002
                XposedHelpers.setAdditionalInstanceField(location, tag, latDrift)
                XposedHelpers.setAdditionalInstanceField(location, "shadow_drift_lng", lngDrift)
            }
            return Pair(latDrift, lngDrift)
        }

        private fun applyFix(location: Location) {
            val coord = getCoord() ?: return
            val (latDrift, lngDrift) = getOrCreateDrift(location)
            try {
                XposedHelpers.setDoubleField(location, "mLatitude", coord[0] + latDrift)
                XposedHelpers.setDoubleField(location, "mLongitude", coord[1] + lngDrift)
            } catch (e: Throwable) {
                location.latitude = coord[0] + latDrift
                location.longitude = coord[1] + lngDrift
            }
            location.accuracy = 3.0f + Random().nextFloat()
            location.provider = LocationManager.GPS_PROVIDER
            location.speed = 0.0f
            location.altitude = 50.0
            location.time = System.currentTimeMillis()
            if (Build.VERSION.SDK_INT >= 17) {
                location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            try {
                XposedHelpers.setBooleanField(location, "mIsFromMockProvider", false)
                location.extras?.remove("mock")
            } catch (_: Throwable) {}
        }

        private val getterHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val coord = getCoord() ?: return
                val location = param.thisObject as Location
                val (latDrift, lngDrift) = getOrCreateDrift(location)
                when (param.method.name) {
                    "getLatitude" -> param.result = coord[0] + latDrift
                    "getLongitude" -> param.result = coord[1] + lngDrift
                    "getAccuracy" -> param.result = 3.0f + Random().nextFloat()
                    "getSpeed" -> param.result = 0.0f
                    "getAltitude" -> param.result = 50.0
                }
            }
        }

        private fun hookParcelable() {
            try {
                val creatorField = Location::class.java.getDeclaredField("CREATOR")
                if (Modifier.isStatic(creatorField.modifiers)) {
                    val creator = creatorField.get(null) as android.os.Parcelable.Creator<Location>
                    XposedBridge.hookAllMethods(creator.javaClass, "createFromParcel", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val loc = param.result as? Location ?: return
                            applyFix(loc)
                        }
                    })
                }
            } catch (e: Throwable) {
                logToFile("Parcelable hook failed: ${e.message}")
            }
        }

        private fun setWifiField(info: WifiInfo, fieldName: String, value: Any) {
            try {
                val field = WifiInfo::class.java.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(info, value)
            } catch (e: NoSuchFieldException) {
                when (fieldName) {
                    "mSSID" -> trySetField(info, "mWifiSsid", value)
                    else -> trySetField(info, fieldName, value)
                }
            }
        }

        private fun trySetField(info: WifiInfo, fieldName: String, value: Any) {
            try {
                val field = WifiInfo::class.java.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(info, value)
            } catch (_: Throwable) {}
        }

        private fun getFakeScanResults(): List<ScanResult> {
            val results = mutableListOf<ScanResult>()
            val sr = ScanResult()
            try {
                XposedHelpers.setObjectField(sr, "BSSID", cachedMac)
                XposedHelpers.setObjectField(sr, "SSID", cachedSSID)
                XposedHelpers.setIntField(sr, "level", -45)
                results.add(sr)
            } catch (_: Throwable) {}
            return results
        }

        private fun hookWeChat(lpparam: XC_LoadPackage.LoadPackageParam) {
            try {
                val tencentLocClass = XposedHelpers.findClass("com.tencent.map.geolocation.TencentLocationManager", lpparam.classLoader)
                XposedBridge.hookAllMethods(tencentLocClass, "getLocation", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val coord = getCoord() ?: return
                        val locationObj = param.result
                        if (locationObj != null) {
                            try {
                                XposedHelpers.setDoubleField(locationObj, "latitude", coord[0])
                                XposedHelpers.setDoubleField(locationObj, "longitude", coord[1])
                                logToFile("WeChat TencentLocation hooked: ${coord[0]}, ${coord[1]}")
                            } catch (e: Throwable) {
                                logToFile("WeChat TencentLocation field set failed: ${e.message}")
                            }
                        }
                    }
                })
            } catch (e: Throwable) {
                logToFile("WeChat specific hook not applied: ${e.message}")
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 关键：跳过自身应用，让 App 能获取真实位置
        if (lpparam.packageName == "com.kankan.globaltraveling") {
            logToFile("Skipping self package: ${lpparam.packageName}")
            return
        }

        try {
            File("/data/local/tmp/xposed_active").writeText("1")
        } catch (_: Throwable) {}

        if (lpparam.packageName == "com.tencent.mm") {
            hookWeChat(lpparam)
        }

        try {
            XposedHelpers.findAndHookConstructor(Location::class.java, String::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    applyFix(param.thisObject as Location)
                }
            })
            XposedHelpers.findAndHookMethod(Location::class.java, "set", Location::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    applyFix(param.thisObject as Location)
                }
            })

            hookParcelable()

            listOf("getLatitude", "getLongitude", "getAccuracy", "getSpeed", "getAltitude").forEach {
                XposedHelpers.findAndHookMethod(Location::class.java, it, getterHook)
            }
            XposedHelpers.findAndHookMethod(Location::class.java, "isFromMockProvider", XC_MethodReplacement.returnConstant(false))

            XposedBridge.hookAllMethods(LocationManager::class.java, "getLastKnownLocation", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    var loc = param.result as? Location
                    if (loc == null && getCoord() != null) {
                        loc = Location(LocationManager.GPS_PROVIDER)
                        param.result = loc
                    }
                    loc?.let { applyFix(it) }
                }
            })

            XposedBridge.hookAllMethods(LocationManager::class.java, "requestLocationUpdates", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args.forEach { arg ->
                        when (arg) {
                            is LocationListener -> {
                                XposedBridge.hookAllMethods(arg.javaClass, "onLocationChanged", object : XC_MethodHook() {
                                    override fun beforeHookedMethod(p: MethodHookParam) {
                                        applyFix(p.args[0] as Location)
                                    }
                                })
                                val coord = getCoord()
                                if (coord != null) {
                                    val fakeLoc = Location(LocationManager.GPS_PROVIDER)
                                    applyFix(fakeLoc)
                                    try { arg.onLocationChanged(fakeLoc) } catch (_: Throwable) {}
                                }
                            }
                            is android.app.PendingIntent -> {}
                        }
                    }
                }
            })

            XposedBridge.hookAllMethods(LocationManager::class.java, "requestSingleUpdate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args.forEach { arg ->
                        if (arg is LocationListener) {
                            XposedBridge.hookAllMethods(arg.javaClass, "onLocationChanged", object : XC_MethodHook() {
                                override fun beforeHookedMethod(p: MethodHookParam) {
                                    applyFix(p.args[0] as Location)
                                }
                            })
                            val coord = getCoord()
                            if (coord != null) {
                                val fakeLoc = Location(LocationManager.GPS_PROVIDER)
                                applyFix(fakeLoc)
                                try { arg.onLocationChanged(fakeLoc) } catch (_: Throwable) {}
                            }
                        }
                    }
                }
            })

            XposedBridge.hookAllMethods(WifiManager::class.java, "getScanResults", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (getCoord() != null) {
                        param.result = getFakeScanResults()
                        logToFile("WiFi scan results faked for ${param.thisObject.javaClass.name}")
                    }
                }
            })

            XposedHelpers.findAndHookMethod(WifiManager::class.java, "getConnectionInfo", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (getCoord() != null) {
                        val info = param.result as? WifiInfo ?: return
                        setWifiField(info, "mBSSID", cachedMac)
                        setWifiField(info, "mSSID", "\"$cachedSSID\"")
                        setWifiField(info, "mMacAddress", "02:00:00:00:00:00")
                        trySetField(info, "mRssi", -45 - Random().nextInt(10))
                        XposedHelpers.findAndHookMethod(WifiInfo::class.java, "getFactoryMacAddress", XC_MethodReplacement.returnConstant("02:00:00:00:00:00"))
                        logToFile("WiFi connection info faked: $cachedSSID / $cachedMac")
                    }
                }
            })

            XposedHelpers.findAndHookMethod(TelephonyManager::class.java, "getAllCellInfo", XC_MethodReplacement.returnConstant(emptyList<CellInfo>()))
            XposedHelpers.findAndHookMethod(TelephonyManager::class.java, "getNetworkOperator", XC_MethodReplacement.returnConstant(""))
            XposedHelpers.findAndHookMethod(TelephonyManager::class.java, "getCellLocation", XC_MethodReplacement.returnConstant(null))
            XposedHelpers.findAndHookMethod(TelephonyManager::class.java, "listen", PhoneStateListener::class.java, Int::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (getCoord() != null) param.args[1] = PhoneStateListener.LISTEN_NONE
                }
            })

            XposedHelpers.findAndHookMethod(LocationManager::class.java, "isProviderEnabled", String::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (getCoord() != null && param.args[0] == LocationManager.GPS_PROVIDER) {
                        param.result = true
                    }
                }
            })

            try {
                val sensorManagerClass = XposedHelpers.findClass("android.hardware.SensorManager", lpparam.classLoader)
                XposedBridge.hookAllMethods(sensorManagerClass, "registerListener", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (getCoord() != null) {
                            param.result = false
                        }
                    }
                })
            } catch (_: Throwable) {}

            try {
                val aMapLocClass = XposedHelpers.findClass("com.amap.api.location.AMapLocation", lpparam.classLoader)
                listOf("getLatitude", "getLongitude", "getAccuracy").forEach {
                    XposedHelpers.findAndHookMethod(aMapLocClass, it, getterHook)
                }
                val clientClass = XposedHelpers.findClass("com.amap.api.location.AMapLocationClient", lpparam.classLoader)
                XposedBridge.hookAllMethods(clientClass, "startLocation", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        logToFile("AMapLocationClient.startLocation called")
                    }
                })
            } catch (_: Throwable) {}

            logToFile("Hook installed successfully for ${lpparam.packageName}")
        } catch (e: Throwable) {
            logToFile("Hook failed for ${lpparam.packageName}: ${e.message}")
        }
    }
}