package com.example.wimudatasampler.utils

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.abs

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

