package com.kankan.globaltraveling;

import android.bluetooth.BluetoothAdapter;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.telephony.CellInfo;
import android.telephony.CellLocation;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookMain implements IXposedHookLoadPackage {

    private static final String FILE_PATH = "/data/local/tmp/irest_loc.conf";
    private static final String BACKUP_FILE_PATH = "/data/data/com.kankan.globaltraveling/files/irest_loc.conf";

    private static double[] cachedCoord = null;
    private static String cachedMac = "02:00:00:00:00:00";
    private static String cachedSSID = "Shadow-WiFi";
    private static long lastModified = 0L;

    private static void logToFile(String msg) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter("/data/local/tmp/xposed_hook.log", true);
            fw.write(System.currentTimeMillis() + " " + msg + "\n");
            fw.close();
        } catch (Throwable ignored) {}
    }

    private static synchronized void refreshCache() {
        try {
            File targetFile = new File(FILE_PATH);
            if (!targetFile.exists() || !targetFile.canRead()) {
                targetFile = new File(BACKUP_FILE_PATH);
            }
            if (targetFile.exists() && targetFile.canRead()) {
                long modified = targetFile.lastModified();
                if (modified == lastModified && cachedCoord != null) return;
                lastModified = modified;
                String content = new String(java.nio.file.Files.readAllBytes(targetFile.toPath()));
                String[] parts = content.split(",");
                if (parts.length >= 3 && "1".equals(parts[2])) {
                    if (parts.length >= 5) {
                        cachedMac = parts[3];
                        cachedSSID = parts[4];
                    }
                    cachedCoord = new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
                    logToFile("Cache refreshed: " + cachedCoord[0] + "," + cachedCoord[1] + " | " + cachedMac + " | " + cachedSSID);
                    return;
                }
            }
            cachedCoord = null;
            logToFile("Cache cleared (no valid config)");
        } catch (Exception e) {
            logToFile("refreshCache error: " + e.getMessage());
            cachedCoord = null;
        }
    }

    private static double[] getCoord() {
        refreshCache();
        return cachedCoord;
    }

    private static double getDrift() {
        return (new Random().nextDouble() - 0.5) * 0.00002;
    }

    private static void applyFix(Location loc) {
        double[] c = getCoord();
        if (c == null) return;
        double drift = getDrift();
        try {
            XposedHelpers.setDoubleField(loc, "mLatitude", c[0] + drift);
            XposedHelpers.setDoubleField(loc, "mLongitude", c[1] + drift);
        } catch (Throwable t) {
            loc.setLatitude(c[0] + drift);
            loc.setLongitude(c[1] + drift);
        }
        loc.setAccuracy(3.0f + new Random().nextFloat());
        loc.setProvider(LocationManager.GPS_PROVIDER);
        loc.setSpeed(0.0f);
        loc.setAltitude(50.0d);
        loc.setTime(System.currentTimeMillis());
        if (Build.VERSION.SDK_INT >= 17) {
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        }
        try {
            XposedHelpers.setBooleanField(loc, "mIsFromMockProvider", false);
        } catch (Throwable ignored) {}
    }

    private static final XC_MethodHook getterHook = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            double[] c = getCoord();
            if (c == null) return;
            double drift = getDrift();
            String name = param.method.getName();
            if (name.equals("getLatitude")) param.setResult(c[0] + drift);
            else if (name.equals("getLongitude")) param.setResult(c[1] + drift);
            else if (name.equals("getAccuracy")) param.setResult(3.0f + new Random().nextFloat());
            else if (name.equals("getSpeed")) param.setResult(0.0f);
            else if (name.equals("getAltitude")) param.setResult(50.0d);
        }
    };

    private static void hookParcelable() {
        try {
            java.lang.reflect.Field creatorField = Location.class.getDeclaredField("CREATOR");
            if (java.lang.reflect.Modifier.isStatic(creatorField.getModifiers())) {
                android.os.Parcelable.Creator<Location> creator = (android.os.Parcelable.Creator<Location>) creatorField.get(null);
                XposedBridge.hookAllMethods(creator.getClass(), "createFromParcel", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Location loc = (Location) param.getResult();
                        if (loc != null) applyFix(loc);
                    }
                });
            }
        } catch (Throwable e) {
            logToFile("Parcelable hook failed: " + e.getMessage());
        }
    }

    private static List<ScanResult> getFakeScanResults() {
        List<ScanResult> list = new ArrayList<>();
        try {
            Constructor<ScanResult> ctor = ScanResult.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            ScanResult sr = ctor.newInstance();
            XposedHelpers.setObjectField(sr, "BSSID", cachedMac);
            XposedHelpers.setObjectField(sr, "SSID", cachedSSID);
            XposedHelpers.setIntField(sr, "level", -45);
            list.add(sr);
        } catch (Exception ignored) {}
        return list;
    }

    private static void setWifiField(WifiInfo info, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = WifiInfo.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(info, value);
        } catch (NoSuchFieldException e) {
            if ("mSSID".equals(fieldName)) {
                trySetField(info, "mWifiSsid", value);
            }
        } catch (Throwable ignored) {}
    }

    private static void trySetField(WifiInfo info, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = WifiInfo.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(info, value);
        } catch (Throwable ignored) {}
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 跳过自身应用
        if (lpparam.packageName.equals("com.kankan.globaltraveling")) {
            logToFile("Skipping self package: " + lpparam.packageName);
            return;
        }

        try {
            new File("/data/local/tmp/xposed_active").createNewFile();
            java.nio.file.Files.write(new File("/data/local/tmp/xposed_active").toPath(), "1".getBytes());
        } catch (Throwable ignored) {}

        // -------- Location 核心 Hook ----------
        XposedHelpers.findAndHookConstructor(Location.class, String.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                applyFix((Location) param.thisObject);
            }
        });
        XposedHelpers.findAndHookMethod(Location.class, "set", Location.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                applyFix((Location) param.thisObject);
            }
        });
        hookParcelable();
        XposedHelpers.findAndHookMethod(Location.class, "getLatitude", getterHook);
        XposedHelpers.findAndHookMethod(Location.class, "getLongitude", getterHook);
        XposedHelpers.findAndHookMethod(Location.class, "getAccuracy", getterHook);
        XposedHelpers.findAndHookMethod(Location.class, "getSpeed", getterHook);
        XposedHelpers.findAndHookMethod(Location.class, "getAltitude", getterHook);
        XposedHelpers.findAndHookMethod(Location.class, "isFromMockProvider", XC_MethodReplacement.returnConstant(false));

        // -------- LocationManager Hook ----------
        XposedBridge.hookAllMethods(LocationManager.class, "getLastKnownLocation", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Location loc = (Location) param.getResult();
                if (loc == null && getCoord() != null) {
                    loc = new Location(LocationManager.GPS_PROVIDER);
                    param.setResult(loc);
                }
                if (loc != null) applyFix(loc);
            }
        });

        XposedBridge.hookAllMethods(LocationManager.class, "requestLocationUpdates", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                for (Object arg : param.args) {
                    if (arg instanceof LocationListener) {
                        final LocationListener listener = (LocationListener) arg;
                        XposedBridge.hookAllMethods(listener.getClass(), "onLocationChanged", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                applyFix((Location) p.args[0]);
                            }
                        });
                        double[] c = getCoord();
                        if (c != null) {
                            Location fakeLoc = new Location(LocationManager.GPS_PROVIDER);
                            applyFix(fakeLoc);
                            try { listener.onLocationChanged(fakeLoc); } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        });

        XposedBridge.hookAllMethods(LocationManager.class, "requestSingleUpdate", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                for (Object arg : param.args) {
                    if (arg instanceof LocationListener) {
                        final LocationListener listener = (LocationListener) arg;
                        XposedBridge.hookAllMethods(listener.getClass(), "onLocationChanged", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                applyFix((Location) p.args[0]);
                            }
                        });
                        double[] c = getCoord();
                        if (c != null) {
                            Location fakeLoc = new Location(LocationManager.GPS_PROVIDER);
                            applyFix(fakeLoc);
                            try { listener.onLocationChanged(fakeLoc); } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        });

        XposedHelpers.findAndHookMethod(LocationManager.class, "isProviderEnabled", String.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (getCoord() != null && param.args[0].equals(LocationManager.GPS_PROVIDER)) {
                    param.setResult(true);
                }
            }
        });

        // -------- WiFi 伪造 ----------
        XposedBridge.hookAllMethods(WifiManager.class, "getScanResults", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (getCoord() != null) {
                    param.setResult(getFakeScanResults());
                    logToFile("WiFi scan results faked");
                }
            }
        });

        XposedHelpers.findAndHookMethod(WifiManager.class, "getConnectionInfo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (getCoord() != null) {
                    WifiInfo info = (WifiInfo) param.getResult();
                    if (info != null) {
                        setWifiField(info, "mBSSID", cachedMac);
                        setWifiField(info, "mSSID", "\"" + cachedSSID + "\"");
                        setWifiField(info, "mMacAddress", "02:00:00:00:00:00");
                        trySetField(info, "mRssi", -45 - new Random().nextInt(10));
                        XposedHelpers.findAndHookMethod(WifiInfo.class, "getFactoryMacAddress", XC_MethodReplacement.returnConstant("02:00:00:00:00:00"));
                        logToFile("WiFi connection info faked: " + cachedSSID + " / " + cachedMac);
                    }
                }
            }
        });

        // -------- 网络状态伪造 ----------
        try {
            XposedHelpers.findAndHookMethod(ConnectivityManager.class, "getActiveNetworkInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (getCoord() != null) {
                        NetworkInfo info = (NetworkInfo) param.getResult();
                        if (info != null) {
                            XposedHelpers.setIntField(info, "mNetworkType", ConnectivityManager.TYPE_WIFI);
                            XposedHelpers.setObjectField(info, "mTypeName", "WIFI");
                            XposedHelpers.setObjectField(info, "mState", NetworkInfo.State.CONNECTED);
                        }
                    }
                }
            });
        } catch (Throwable ignored) {}

        // -------- 基站 / 手机状态屏蔽 ----------
        XposedHelpers.findAndHookMethod(TelephonyManager.class, "getAllCellInfo", XC_MethodReplacement.returnConstant(new ArrayList<CellInfo>()));
        XposedHelpers.findAndHookMethod(TelephonyManager.class, "getNetworkOperator", XC_MethodReplacement.returnConstant(""));
        XposedHelpers.findAndHookMethod(TelephonyManager.class, "getCellLocation", XC_MethodReplacement.returnConstant(null));
        XposedHelpers.findAndHookMethod(TelephonyManager.class, "listen", PhoneStateListener.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (getCoord() != null) param.args[1] = PhoneStateListener.LISTEN_NONE;
            }
        });
        XposedHelpers.findAndHookMethod(TelephonyManager.class, "getSimState", XC_MethodReplacement.returnConstant(TelephonyManager.SIM_STATE_ABSENT));

        // -------- 屏蔽异步基站信息请求 (Android 10+) ----------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "requestCellInfoUpdate",
                        Executor.class, TelephonyManager.CellInfoCallback.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (getCoord() != null) {
                                    Executor ex = (Executor) param.args[0];
                                    TelephonyManager.CellInfoCallback cb = (TelephonyManager.CellInfoCallback) param.args[1];
                                    if (ex != null && cb != null) {
                                        ex.execute(() -> cb.onCellInfo(new ArrayList<CellInfo>()));
                                    }
                                    param.setResult(null);
                                }
                            }
                        });
                logToFile("requestCellInfoUpdate hooked (API 29+)");
            } catch (Throwable t) {
                logToFile("requestCellInfoUpdate hook failed: " + t.getMessage());
            }
        }

        // -------- GNSS 卫星数伪造 ----------
        if (Build.VERSION.SDK_INT >= 24) {
            XposedHelpers.findAndHookMethod(GnssStatus.class, "getSatelliteCount", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (getCoord() != null) param.setResult(15);
                }
            });
        }

        // -------- 传感器屏蔽 ----------
        try {
            Class<?> sensorManagerClass = XposedHelpers.findClass("android.hardware.SensorManager", lpparam.classLoader);
            XposedBridge.hookAllMethods(sensorManagerClass, "registerListener", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (getCoord() != null) param.setResult(false);
                }
            });
        } catch (Throwable ignored) {}

        // -------- 蓝牙屏蔽 ----------
        try {
            XposedHelpers.findAndHookMethod(BluetoothAdapter.class, "getBondedDevices", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (getCoord() != null) param.setResult(Collections.emptySet());
                }
            });
            XposedHelpers.findAndHookMethod(BluetoothAdapter.class, "startDiscovery", XC_MethodReplacement.returnConstant(false));
        } catch (Throwable ignored) {}

        // -------- 高德 SDK 兼容 ----------
        try {
            Class<?> amap = XposedHelpers.findClass("com.amap.api.location.AMapLocation", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(amap, "getLatitude", getterHook);
            XposedHelpers.findAndHookMethod(amap, "getLongitude", getterHook);
            XposedHelpers.findAndHookMethod(amap, "getAccuracy", getterHook);
            XposedHelpers.findAndHookMethod(amap, "getLocationType", XC_MethodReplacement.returnConstant(1));
        } catch (Throwable ignored) {}

        // -------- 腾讯 SDK 兼容 ----------
        try {
            Class<?> tencent = XposedHelpers.findClass("com.tencent.map.geolocation.TencentLocation", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(tencent, "getLatitude", getterHook);
            XposedHelpers.findAndHookMethod(tencent, "getLongitude", getterHook);
            XposedHelpers.findAndHookMethod(tencent, "getAccuracy", getterHook);
            XposedHelpers.findAndHookMethod(tencent, "getProvider", XC_MethodReplacement.returnConstant("gps"));
        } catch (Throwable ignored) {}

        // -------- 微信专向 Hook ----------
        if (lpparam.packageName.equals("com.tencent.mm")) {
            try {
                Class<?> tencentLocMgr = XposedHelpers.findClass("com.tencent.map.geolocation.TencentLocationManager", lpparam.classLoader);
                XposedBridge.hookAllMethods(tencentLocMgr, "getLocation", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        double[] c = getCoord();
                        if (c == null) return;
                        Object locationObj = param.getResult();
                        if (locationObj != null) {
                            try {
                                XposedHelpers.setDoubleField(locationObj, "latitude", c[0]);
                                XposedHelpers.setDoubleField(locationObj, "longitude", c[1]);
                                logToFile("WeChat TencentLocation hooked");
                            } catch (Throwable e) {
                                logToFile("WeChat hook failed: " + e.getMessage());
                            }
                        }
                    }
                });
            } catch (Throwable e) {
                logToFile("WeChat specific hook not applied: " + e.getMessage());
            }
        }

        logToFile("Hook installed successfully for " + lpparam.packageName);
    }
}