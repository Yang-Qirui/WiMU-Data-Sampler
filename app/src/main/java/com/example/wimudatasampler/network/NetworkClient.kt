package com.example.wimudatasampler.network

import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.example.wimudatasampler.DataClass.DataEntry
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

object NetworkClient {
    private val client = HttpClient(CIO)

    private fun parseDataEntry(wifiInput: List<String>, imuInput: Offset, sysNoise: Float = 1f, obsNoise: Float = 3f): List<DataEntry> {
        val wifiEntries = mutableListOf<DataEntry>()
        for (line in wifiInput) {
            val indentLine = line.trimIndent()
            Log.d("Line", indentLine)
            val parts = indentLine.split(" ")
            if (parts.size >= 5) {
                try {
                    val timestamp = parts[0].toLong()
                    val ssid = if (parts[1].isEmpty()) null else parts[1] 
                    val bssid = parts[2]
                    val frequency = parts[3].toInt()
                    val rssi = parts[4].toInt()
                    
                    wifiEntries.add(DataEntry(timestamp, bssid, ssid, frequency, rssi, imuInput.x, imuInput.y, sysNoise, obsNoise))
                } catch (e: Exception) {
                    continue
                }
            }
        }
        return wifiEntries
    }

    suspend fun fetchData(wifiResult: List<String>, imuInput: Offset): HttpResponse {
        val wifiEntries = parseDataEntry(wifiResult, imuInput)
        Log.d("monitor", wifiEntries.toString())
        return client.post("http://limcpu1.cse.ust.hk:7860/wimu/inference") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(wifiEntries))
        }
    }
}