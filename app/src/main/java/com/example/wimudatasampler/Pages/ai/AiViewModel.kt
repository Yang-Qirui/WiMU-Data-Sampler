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

                // 预处理
                val inputTensor = preprocessImage(bitmap)

                // 推理计时
                val  startTime = System.nanoTime()
                val outputs = module?.forward(EValue.from(inputTensor)) ?: throw Exception("Model not loaded")
                val endTime = System.nanoTime()

                // 后处理
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
        // 转换为 28x28 灰度图
        val resized = Bitmap.createScaledBitmap(bitmap, 28, 28, true)

        // 应用与训练一致的归一化参数
        val mean = 0.1307f
        val std = 0.3081f

        val pixels = FloatArray(28 * 28) { i ->
            val pixel = resized.getPixel(i % 28, i / 28)

            // 计算灰度值（0-255）
            val gray = (0.299f * Color.red(pixel) +
                    0.587f * Color.green(pixel) +
                    0.114f * Color.blue(pixel))

            // 反转颜色并归一化 (假设输入是白底黑字)
            val normalized = gray / 255.0f

            // 应用 MNIST 标准化
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

        // 添加时间信息到结果
        return """
            Predicted: $maxIndex
            Time: ${duration}ns
        """.trimIndent()
    }

    // 扩展函数：复制 Asset 到文件系统
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