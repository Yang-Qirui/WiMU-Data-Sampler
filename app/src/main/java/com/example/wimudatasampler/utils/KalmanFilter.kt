package com.example.wimudatasampler.utils

import kotlin.math.cos
import kotlin.math.sin

class KalmanFilter(
    private var state: DoubleArray,       // 状态向量 [x, y]
    private var covariance: Array<DoubleArray>, // 协方差矩阵 2x2
    private val processNoise: Array<DoubleArray>, // 过程噪声 Q
    private val measurementNoise: Array<DoubleArray> // 观测噪声 R
) {
    fun setInit(doubles: DoubleArray) {
        state = doubles.copyOf()
    }
    /**
     * 高频预测步骤（通过步长和航向角更新）
     * @param stride 移动步长
     * @param yaw 航向角（弧度）
     */
    fun predict(stride: Float, yaw: Float) {
        // 计算运动增量
        val deltaX = stride * cos(yaw)
        val deltaY = stride * sin(yaw)

        // 更新状态估计
        state[0] = state[0] - deltaX
        state[1] = state[1] - deltaY

        // 更新协方差矩阵: P = P + Q
        covariance = matrixAdd(covariance, processNoise)
    }

    /**
     * 低频观测更新步骤
     * @param measurement 观测值 [x, y]
     */
    fun update(measurement: DoubleArray) {
        // 计算卡尔曼增益
        val S = matrixAdd(covariance, measurementNoise)
        val K = matrixMultiply(covariance, matrixInverse(S))

        // 更新状态估计
        val innovation = doubleArrayOf(
            measurement[0] - state[0],
            measurement[1] - state[1]
        )
        val correction = matrixVectorMultiply(K, innovation)
        state[0] += correction[0]
        state[1] += correction[1]

        // 更新协方差矩阵: P = (I - K) * P
        val identity = identityMatrix(2)
        covariance = matrixMultiply(matrixSubtract(identity, K), covariance)
    }

    // 矩阵加法
    private fun matrixAdd(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        return Array(2) { i ->
            DoubleArray(2) { j ->
                a[i][j] + b[i][j]
            }
        }
    }

    // 矩阵乘法
    private fun matrixMultiply(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        return Array(2) { i ->
            DoubleArray(2) { j ->
                (0 until 2).sumOf { k -> a[i][k] * b[k][j] }
            }
        }
    }

    // 矩阵求逆（仅限 2x2）
    private fun matrixInverse(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val det = matrix[0][0] * matrix[1][1] - matrix[0][1] * matrix[1][0]
        require(det != 0.0) { "Matrix is singular" }
        return Array(2) { i ->
            DoubleArray(2) { j ->
                when {
                    i == 0 && j == 0 -> matrix[1][1] / det
                    i == 0 && j == 1 -> -matrix[0][1] / det
                    i == 1 && j == 0 -> -matrix[1][0] / det
                    else -> matrix[0][0] / det
                }
            }
        }
    }

    // 矩阵向量乘法
    private fun matrixVectorMultiply(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray {
        return DoubleArray(2) { i ->
            (0 until 2).sumOf { j -> matrix[i][j] * vector[j] }
        }
    }

    // 生成单位矩阵
    private fun identityMatrix(size: Int): Array<DoubleArray> {
        return Array(size) { i ->
            DoubleArray(size) { j ->
                if (i == j) 1.0 else 0.0
            }
        }
    }

    // 矩阵减法
    private fun matrixSubtract(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        return Array(2) { i ->
            DoubleArray(2) { j ->
                a[i][j] - b[i][j]
            }
        }
    }

    // 获取当前状态
    fun getState(): DoubleArray = state.copyOf()
}