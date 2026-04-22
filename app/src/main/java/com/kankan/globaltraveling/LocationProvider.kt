package com.kankan.globaltraveling

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri

class LocationProvider : ContentProvider() {
    companion object {
        private var lat = 0.0
        private var lng = 0.0
        private var enabled = false
        private var mac = ""
        private var ssid = ""

        fun updateLocation(latitude: Double, longitude: Double, isEnabled: Boolean, deviceMac: String, deviceSsid: String) {
            lat = latitude
            lng = longitude
            enabled = isEnabled
            mac = deviceMac
            ssid = deviceSsid
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor? {
        val cursor = MatrixCursor(arrayOf("lat", "lng", "enabled", "mac", "ssid"))
        cursor.addRow(arrayOf(lat, lng, if (enabled) 1 else 0, mac, ssid))
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}