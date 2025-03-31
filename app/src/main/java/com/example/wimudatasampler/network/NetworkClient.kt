package com.example.wimudatasampler.network

import android.util.Log
import com.example.wimudatasampler.DataClass.WifiEntry
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object NetworkClient {
    private val client = HttpClient(CIO)

    fun parseWifiData(input: String): List<WifiEntry> {
        return input.lines().mapNotNull { line ->
            val parts = line.split(" ")
            if (parts.size >= 5) { // 确保有足够的部分
                try {
                    val timestamp = parts[0].toLong() // 第一个是时间戳
                    val bssid = parts[2] // 第三个是BSSID
                    val frequency = parts[3].toInt() // 第四个是频率
                    val rssi = parts[4].toInt() // 第五个是RSSI

                    // 将ssid设置为parts[1]，如果它为空，则为null
                    val ssid = if (parts[1].isEmpty()) null else parts[1]

                    WifiEntry(timestamp, bssid, ssid, frequency, rssi)
                } catch (e: Exception) {
                    null // 解析错误，返回null
                }
            } else {
                null // 如果部分不足，返回null
            }
        }
    }

    suspend fun fetchData(wifiResult: String): HttpResponse {
        val wifiEntries = parseWifiData(wifiResult.trimIndent())
        Log.d("monitor", wifiEntries.toString())
        return client.post("http://limcpu1.cse.ust.hk:7860/wimu/echo") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(wifiEntries))
        }
    }


}