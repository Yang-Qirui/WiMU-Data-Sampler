package com.example.wimudatasampler.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.Serializable
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class Quadruple<A, B, C, D> (
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
const val ALPHA = 0.1f

fun lowPassFilter(input: FloatArray, output: FloatArray): FloatArray {
    if (output.isEmpty()) return input
    return FloatArray(input.size).apply {
        for (i in input.indices) {
            this[i] = output[i] + ALPHA * (input[i] - output[i])
        }
    }
}

class CoroutineLockIndexedList<T, P> {
    private val lock = ReentrantReadWriteLock()
    var list: MutableList<Quadruple<Long, T, P, Boolean>> = mutableListOf()
    fun put(value: Quadruple<Long, T, P, Boolean>) {
        lock.writeLock().lock()
        try {
            list.add(value)
        } finally {
            lock.writeLock().unlock()
        }
    }
    fun get(): Quadruple<Long, T, P, Boolean>{
        lock.readLock().lock()
        try {
            return list.last()
        } finally {
            lock.readLock().unlock()
        }
    }
    fun get(value: Long): Quadruple<Long, T, P, Boolean>? {
        lock.readLock().lock()
        return try {
            val closestElement = list.minByOrNull { abs(it.first - value) }
            if (closestElement != null && abs(closestElement.first - value) < 1000) {
                closestElement
            } else {
                null
            }
        } finally {
            lock.readLock().unlock()
        }
    }
    fun clear() {
        lock.writeLock().lock()
        try {
            list.clear()
        } finally {
            lock.writeLock().unlock()
        }
    }
}

fun validPostureCheck(pitch: Float, roll: Float): Boolean {
    return abs(pitch) < 60 && abs(roll) < 30
}

/**
 * 循环数组（环形缓冲区）实现
 * @param capacity 数组容量（固定大小）
 * @param allowOverwrite 是否允许覆盖旧数据（默认不允许）
 */
class CircularArray<T>(private val capacity: Int, private val allowOverwrite: Boolean = false) : Iterable<T> {
    private val array = arrayOfNulls<Any?>(capacity)
    private var head = 0  // 当前头部索引
    private var tail = 0  // 下一个写入位置
    private var size = 0  // 当前元素数量

    val isEmpty: Boolean get() = size == 0
    val isFull: Boolean get() = size == capacity

    /**
     * 添加元素到数组尾部
     */
    fun add(element: T) {
        if (isFull) {
            if (!allowOverwrite) {
                throw IllegalStateException("Circular array is full")
            }
            // 覆盖头部元素
            head = (head + 1) % capacity
            size--
        }

        array[tail] = element
        tail = (tail + 1) % capacity
        size++
    }

    fun getSize(): Int { return size }

    /**
     * 移除并返回头部元素
     */
    fun remove(): T {
        if (isEmpty) throw NoSuchElementException("Circular array is empty")

        val element = array[head] as T
        array[head] = null  // 帮助GC
        head = (head + 1) % capacity
        size--
        return element
    }

    /**
     * 查看头部元素（不移除）
     */
    fun peek(): T {
        if (isEmpty) throw NoSuchElementException("Circular array is empty")
        return array[head] as T
    }

    /**
     * 清空数组
     */
    fun clear() {
        array.fill(null)
        head = 0
        tail = 0
        size = 0
    }

    /**
     * 转换为列表（按顺序从 head 到 tail）
     */
    fun toList(): List<T> {
        val list = ArrayList<T>(size)
        for (i in 0 until size) {
            val index = (head + i) % capacity
            list.add(array[index] as T)
        }
        return list
    }

    fun checkAll(): Int {
        var count = 0
        for (i in 0 until size) {
            val index = (head + i) % capacity
            val element = array[index] as Triple<Float, Float, Float>
            if (!validPostureCheck(element.second, element.third)) {
                count += 1
            }
        }
        return count
    }

    /**
     * 迭代器实现
     */
    override fun iterator(): Iterator<T> {
        return object : Iterator<T> {
            private var count = 0
            private var currentIndex = head

            override fun hasNext(): Boolean = count < size

            override fun next(): T {
                if (!hasNext()) throw NoSuchElementException()
                val element = array[currentIndex] as T
                currentIndex = (currentIndex + 1) % capacity
                count++
                return element
            }
        }
    }

    override fun toString(): String = toList().toString()
}

@SuppressLint("HardwareIds")
fun getDeviceId(context: Context): String =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

@RequiresApi(Build.VERSION_CODES.N_MR1)
@SuppressLint("HardwareIds")
fun getDeviceName(context: Context): String {
    // 1. 尝试获取用户在设置中定义的设备名称
    try {
        val userDeviceName = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        if (!userDeviceName.isNullOrEmpty()) {
            return userDeviceName
        }
    } catch (e: Exception) {
        // 在某些设备上可能会有权限问题，安全地忽略
    }

    // 2. 如果获取不到，尝试获取蓝牙名称
    try {
        val bluetoothName = Settings.Secure.getString(context.contentResolver, "bluetooth_name")
        if (!bluetoothName.isNullOrEmpty()) {
            return bluetoothName
        }
    } catch (e: Exception) {
        // 同样，安全地忽略异常
    }

    // 3. 如果以上都失败，返回制造商和型号作为兜底方案
    val manufacturer = Build.MANUFACTURER
    val model = Build.MODEL

    return if (model.startsWith(manufacturer, ignoreCase = true)) {
        model.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } // 首字母大写
    } else {
        "${manufacturer.replaceFirstChar { it.titlecase() }} $model"
    }
}

@Serializable
data class UploadBatchManifest(
    val batch_id: String,
    val total_files: Int,
    val device_id: String,
    val path_name: String,
    val data_type: String
)

fun calculateTotalDisplacement(directions: List<Float>, strideLength: Float): Offset {
    var totalDx = 0.0
    var totalDy = 0.0

    for (angleRadians in directions) {
        // 将角度转换为弧度，因为三角函数需要用弧度
        // 根据三角函数计算这一步的dx和dy
        // dx = L * sin(θ)  (东西方向)
        // dy = L * cos(θ)  (南北方向)
        val dx = -strideLength * cos(angleRadians.toDouble())
        val dy = -strideLength * sin(angleRadians.toDouble())

        // 累加总位移
        totalDx += dx
        totalDy += dy
    }

    return Offset(totalDx.toFloat(), totalDy.toFloat())
}