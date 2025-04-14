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

fun lowPass(current: Float, last: Float) = last + ALPHA * (current - last)

class CoroutineLockIndexedList<T, P> {
    private val lock = ReentrantReadWriteLock()
    private var list: MutableList<Triple<Long, T, P>> = mutableListOf()
    fun put(value: Triple<Long, T, P>) {
        lock.writeLock().lock()
        try {
            list.add(value)
        } finally {
            lock.writeLock().unlock()
        }
    }
    fun get(): Triple<Long, T, P>{
        lock.readLock().lock()
        try {
            return list.last()
        } finally {
            lock.readLock().unlock()
        }
    }
    fun get(value: Long): Triple<Long, T, P>? {
        lock.readLock().lock()
        return try {
            val closestElement = list.minByOrNull { abs(it.first - value) }
            closestElement
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