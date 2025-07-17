package com.example.wimudatasampler.network

import androidx.compose.ui.geometry.Offset
import com.example.wimudatasampler.DataClass.DataEntry
import com.example.wimudatasampler.DataClass.RequestData
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

    private fun parseDataEntry(wifiInput: List<String>): List<DataEntry> {
        val wifiEntries = mutableListOf<DataEntry>()
        for (line in wifiInput) {
            val indentLine = line.trimIndent()
//            Log.d("Line", indentLine)
            val parts = indentLine.split(" ")
            if (parts.size >= 5) {
                try {
                    val timestamp = parts[0].toLong()
                    val ssid = if (parts[1].isEmpty()) null else parts[1]
                    val bssid = parts[2]
                    val frequency = parts[3].toInt()
                    val rssi = parts[4].toInt()

                    wifiEntries.add(DataEntry(timestamp, bssid, ssid, frequency, rssi))
                } catch (e: Exception) {
                    continue
                }
            }
        }
        return wifiEntries
    }

    suspend fun fetchData(
        url: String,
        uuid: String,
        wifiResult: List<String>,
        imuInput: Offset,
        sysNoise: Float,
        obsNoise: Float
    ): HttpResponse {
        val wifiEntries = parseDataEntry(wifiResult)
        val request = RequestData(uuid, wifiEntries, imuInput.x, imuInput.y, sysNoise, obsNoise)
        return client.post(url + "/wimu/inference") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(request))
        }
    }

    suspend fun reset(
        url: String,
        uuid: String,
        wifiResult: List<String>,
        sysNoise: Float,
        obsNoise: Float
    ): HttpResponse {
        val wifiEntries = parseDataEntry(wifiResult)
        val request = RequestData(uuid, wifiEntries, null, null, sysNoise, obsNoise)
        return client.post(url + "/wimu/reset") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(request))
        }
    }
}