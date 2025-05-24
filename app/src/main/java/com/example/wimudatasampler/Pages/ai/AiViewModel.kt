package com.example.wimuutils.page.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.graphics.*
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wimuutils.helper.TorchHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File
import java.io.FileOutputStream
import kotlin.math.exp

class aiViewModel : ViewModel() {
    var module: Module? = null
    var processing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<String?>(null)
    var selectedImage by mutableStateOf<Bitmap?>(null)
    var inferenceTimeNs by mutableStateOf<Long?>(null)

    fun loadModel(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                processing = true
                val modelPath = context.copyAssetToFiles("dl3_xnnpack_fp32.pte")
                module = Module.load(modelPath)
                error = null
            } catch (e: Exception) {
                error = "Load failed: ${e.message}"
            } finally {
                processing = false
            }
        }
    }

    fun runInference() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                processing = true
                inferenceTimeNs = null // 重置时间
                val bitmap = selectedImage ?: throw Exception("No image selected")

                val inputTensor = preprocessImage(bitmap)

                val  startTime = System.nanoTime()
                val outputs = module?.forward(EValue.from(inputTensor)) ?: throw Exception("Model not loaded")
                val endTime = System.nanoTime()

                result = processOutput(outputs, endTime - startTime) // 传递时间参数
                error = null
            } catch (e: Exception) {
                error = "Inference failed: ${e.message}"
            } finally {
                processing = false
            }
        }
    }

    private fun preprocessImage(bitmap: Bitmap): Tensor {
        val resized = Bitmap.createScaledBitmap(bitmap, 28, 28, true)

        val mean = 0.1307f
        val std = 0.3081f

        val pixels = FloatArray(28 * 28) { i ->
            val pixel = resized.getPixel(i % 28, i / 28)
            val gray = (0.299f * Color.red(pixel) +
                    0.587f * Color.green(pixel) +
                    0.114f * Color.blue(pixel))
            val normalized = gray / 255.0f
            (normalized - mean) / std
        }

        return Tensor.fromBlob(
            pixels,
            longArrayOf(1, 1, 28, 28)  // [batch, channel, height, width]
        )
    }

    private fun processOutput(outputs: Array<EValue>, duration: Long): String {
        val outputTensor = outputs[0].toTensor()
        val scores = outputTensor.dataAsFloatArray

        val maxIndex = scores.indices.maxBy { scores[it] }
        val confidence = scores[maxIndex]

        return """
            Predicted: $maxIndex
            Time: ${duration}ns
        """.trimIndent()
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
}