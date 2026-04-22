package com.kankan.globaltraveling.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.kankan.globaltraveling.App
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object LocationHelper {

    private const val LOCATION_TIMEOUT_MS = 15000L

    private fun isGooglePlayServicesAvailable(context: Context): Boolean {
        val googleApiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(context)
        return resultCode == ConnectionResult.SUCCESS
    }

    private fun hasLocationPermissions(context: Context): Boolean {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun getCurrentLocation(): Location? = suspendCancellableCoroutine { continuation ->
        val context = App.instance
        if (!hasLocationPermissions(context)) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (!isGpsEnabled) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        if (isGooglePlayServicesAvailable(context)) {
            // 使用融合定位
            val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setWaitForAccurateLocation(false)
                .build()
            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation
                    if (location != null && continuation.isActive) {
                        continuation.resume(location)
                        fusedClient.removeLocationUpdates(this)
                    }
                }
            }
            fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                if (continuation.isActive) {
                    continuation.resume(null)
                    fusedClient.removeLocationUpdates(locationCallback)
                }
            }, LOCATION_TIMEOUT_MS)
        } else {
            // 降级使用系统 LocationManager
            val provider = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LocationManager.GPS_PROVIDER
            } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                LocationManager.NETWORK_PROVIDER
            } else {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val lastKnown = locationManager.getLastKnownLocation(provider)
            if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < 30000) {
                continuation.resume(lastKnown)
                return@suspendCancellableCoroutine
            }
            // 使用匿名对象而非 lambda 以避免 removeUpdates 重载歧义
            val locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (location != null && continuation.isActive) {
                        continuation.resume(location)
                        locationManager.removeUpdates(this)
                    }
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            locationManager.requestSingleUpdate(provider, locationListener, Looper.getMainLooper())
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                if (continuation.isActive) {
                    continuation.resume(null)
                    locationManager.removeUpdates(locationListener)
                }
            }, LOCATION_TIMEOUT_MS)
        }
    }
}