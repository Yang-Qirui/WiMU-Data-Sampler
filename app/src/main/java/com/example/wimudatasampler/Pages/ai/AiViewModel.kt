package com.example.wimudatasampler.Pages.ai

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.graphics.*
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wimudatasampler.DataClass.DataEntry
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.io.files.FileNotFoundException
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import kotlin.math.pow


@HiltViewModel
class AiViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
): ViewModel() {
    private val gson = Gson()
    var module: Module? = null
    var apMapping: Map<String, Int>? = null
    var normParams: Map<String, Float>? = null
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<FloatArray?>(null)
    var inferenceTimeNs by mutableStateOf<Long?>(null)
    val filterThreshold = -65
    var representationArray: FloatArray? = null

    fun loadModel(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val modelPath = context.copyAssetToFiles("use.pte")
                module = Module.load(modelPath)
                apMapping = loadApMapping()
                representationArray = readFloatArray(FileInputStream(File(context.getExternalFilesDir(null), "representation.bin")))
                normParams = loadNorm()
                error = null
            } catch (e: Exception) {
                error = "Load failed: ${e.message}"
            }
        }
    }

    fun runInference(wifiEntries: List<DataEntry>): FloatArray? {
            try {
                inferenceTimeNs = null // 重置时间

                val input = preprocess(wifiEntries)

                val  startTime = System.nanoTime()
                val outputs = module?.forward(EValue.from(Tensor.fromBlob(input, longArrayOf(128)))) ?: throw Exception("Model not loaded")
                val endTime = System.nanoTime()
                error = null

                return processOutput(outputs, endTime - startTime) // 传递时间参数
            } catch (e: Exception) {
                error = "Inference failed: ${e.message}"
                return null
            }
    }

    private fun preprocess(wifiEntries: List<DataEntry>): FloatArray? {

        val bssidRssiMap = HashMap<Int, MutableList<Pair<Int, String>>>()
        val bssidBandMap = HashMap<Int, String>()

        wifiEntries.forEach { entry ->
            val bssid = entry.bssid
            val freq = entry.frequency
            val rssi = entry.rssi

            val band = if (freq >= 5000) "5G" else "2.4G"

            apMapping?.get(bssid)?.let { unionId ->
                if (rssi > filterThreshold) {
                    bssidRssiMap.getOrPut(unionId) { mutableListOf() }.add(rssi to band)
                    bssidBandMap[unionId] = band
                }
            }
        }

        if (bssidRssiMap.isEmpty()) return null

        val inputWeights = FloatArray(representationArray!!.size / 128) { 0f }

        bssidRssiMap.forEach { (unionId, rssiBandList) ->
            val avgRssi = rssiBandList.map { it.first }.average().toFloat()
            val band = bssidBandMap[unionId] ?: "2.4G"

            val weight = 1f / (1 + LDPL(avgRssi, band))
            inputWeights[unionId] = weight.toFloat()

            Log.d("Weight", "$unionId, $avgRssi, $band, $weight")
        }
        val sumWeights = inputWeights.sum()
        if (sumWeights > 0) {
            inputWeights.map { it / sumWeights }.toFloatArray()
        } else {
            return null
        }
        val resultVector = FloatArray(128) { 0f }
        for (dim in 0 until 128) {
            // 使用协程并行处理每个维度
            var sum = 0f
            for (row in inputWeights.indices) {
                // 计算特征矩阵的行偏移量
                val offset = row * 128 + dim
                sum += inputWeights[row] * representationArray!![offset]
            }
            resultVector[dim] = sum
        }
        return resultVector
        // Normalize weights
    }

    private fun processOutput(outputs: Array<out EValue>, duration: Long): FloatArray {
        val posRange = floatArrayOf(normParams!!["pos_range_x"]!!, normParams!!["pos_range_y"]!!)
        val posMin = floatArrayOf(normParams!!["pos_min_x"]!!, normParams!!["pos_min_y"]!!)
        val outputTensor = outputs[0].toTensor()
        val normPos = outputTensor.dataAsFloatArray
        val x = normPos[0] * posRange[0] + posMin[0]
        val y = normPos[1] * posRange[1] + posMin[1]
//        val maxIndex = scores.indices.maxBy { scores[it] }
//        val confidence = scores[maxIndex]

        return floatArrayOf(x, y, duration.toFloat())
    }

    private fun Context.copyAssetToFiles(assetName: String): String {
        val file = File(getExternalFilesDir(null), assetName)
        if (!file.exists()) {
            assets.open(assetName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return file.absolutePath
    }

    private fun loadApMapping(): Map<String, Int> {
        return try {
            // 从新的存储路径读取文件
            val file = File(context.getExternalFilesDir(null), "ap_unions.json")

            if (!file.exists()) {
                // 可选：如果文件不存在时从 assets 重新拷贝（根据需求决定）
                context.copyAssetToFiles("ap_unions.json")
            }

            FileInputStream(file).use { inputStream ->
                val reader = InputStreamReader(inputStream, StandardCharsets.UTF_8)
                val type = object : TypeToken<Map<String, Int>>() {}.type
                gson.fromJson<Map<String, Int>>(reader, type)
                    ?.filterValues { true } // 确保值类型为 Int
                    ?: emptyMap()
            }
        } catch (e: FileNotFoundException) {
            Log.e("FileLoader", "AP mapping file not found", e)
            emptyMap()
        } catch (e: Exception) {
            Log.e("FileLoader", "Error loading AP mapping", e)
            emptyMap()
        }
    }

    private fun loadNorm(): Map<String, Float> {
        return try {

            val file = File(context.getExternalFilesDir(null), "norm_params.json")
            FileInputStream(file).use { inputStream ->
                val reader = InputStreamReader(inputStream, StandardCharsets.UTF_8)
                val type = object : TypeToken<Map<String, Float>>() {}.type
                gson.fromJson<Map<String, Float>>(reader, type) // 过滤非Int值
                    ?.mapValues { it.value}
                    ?: emptyMap()
            }
        } catch (e: Exception) {
            Log.e("AssetLoader", "Error loading AP mapping", e)
            emptyMap()
        }
    }

    private fun LDPL(
        rssi: Float,
        band: String = "5G",
        r0_5g: Double = 32.0,
        r0_2g: Double = 38.0,
        n_5g: Double = 2.2,
        n_2g: Double = 2.0
    ): Double {
        return if (band == "5G") {
            10.0.pow((-rssi - r0_5g) / (10 * n_5g))
        } else {
            10.0.pow((-rssi - r0_2g) / (10 * n_2g))
        }
    }

    private fun readFloatArray(
        inputStream: InputStream,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        bufferSize: Int = 8192
    ): FloatArray {
        return inputStream.use { stream ->
            // 读取完整字节流
            val byteBuffer = ByteArrayOutputStream().apply {
                val buffer = ByteArray(bufferSize)
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    write(buffer, 0, bytesRead)
                }
            }.toByteArray()

            // 校验数据完整性
            require(byteBuffer.size % 4 == 0) {
                "字节长度 ${byteBuffer.size} 不是4的倍数，无法转换为FloatArray"
            }

            // 转换为FloatArray
            ByteBuffer.wrap(byteBuffer)
                .order(byteOrder)
                .asFloatBuffer()
                .let { floatBuffer ->
                    FloatArray(floatBuffer.remaining()).apply {
                        floatBuffer.get(this)
                    }
                }
        }
    }
}