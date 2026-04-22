package com.kankan.globaltraveling.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.amap.api.services.core.AMapException
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.amap.api.services.help.Tip
import com.kankan.globaltraveling.App
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object AMapSearchManager {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun getInputtips(keyword: String, city: String = ""): List<TipInfo> =
        suspendCancellableCoroutine { continuation ->
            val query = InputtipsQuery(keyword, city)
            val inputtips = Inputtips(appContext, query)
            inputtips.setInputtipsListener(object : Inputtips.InputtipsListener {
                override fun onGetInputtips(tips: List<Tip>?, code: Int) {
                    when (code) {
                        AMapException.CODE_AMAP_SUCCESS -> {
                            val tipInfos = tips?.mapNotNull { tip ->
                                tip.point?.let { point ->
                                    TipInfo(
                                        name = tip.name,
                                        address = tip.address,
                                        lat = point.latitude,
                                        lng = point.longitude
                                    )
                                }
                            } ?: emptyList()
                            continuation.resume(tipInfos)
                        }
                        else -> {
                            val errorMsg = "搜索失败: 错误码 $code"
                            android.util.Log.e("AMapSearch", errorMsg)
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(App.instance, errorMsg, Toast.LENGTH_SHORT).show()
                            }
                            continuation.resume(emptyList())
                        }
                    }
                }
            })
            inputtips.requestInputtipsAsyn()
        }
}

data class TipInfo(
    val name: String,
    val address: String?,
    val lat: Double,
    val lng: Double
)