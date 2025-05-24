import android.content.Context
import androidx.compose.ui.geometry.Offset
import kotlin.math.exp
import kotlin.random.Random

class ParticleFilter(numParticles: Int = 1000) {
    private val particles = mutableListOf<Particle>()
    private var numParticles = numParticles
        set(value) {
            field = value
            lastInitObservation?.let { reset(it, lastObsNoiseScale) }
        }

    private var lastInitObservation: FloatArray? = null
    private var lastObsNoiseScale = 3f

    data class Particle(
        var x: Float,
        var y: Float,
        var weight: Float
    )

    init {
        initializeParticles()
    }

    private fun initializeParticles() {
        particles.clear()
        repeat(numParticles) {
            particles.add(Particle(0f, 0f, 1f / numParticles))
        }
    }

    fun reset(initObservation: FloatArray, obsNoiseScale: Float = 3f) {
        require(initObservation.size == 2) { "Initial observation must have x and y" }
        lastInitObservation = initObservation
        lastObsNoiseScale = obsNoiseScale

        particles.forEach {
            it.x = Random.nextFloat() * obsNoiseScale + initObservation[0]
            it.y = Random.nextFloat() * obsNoiseScale + initObservation[1]
            it.weight = 1f / numParticles
        }
    }

    fun update(
        observation: Offset,
        systemInput: Offset,
        systemNoiseScale: Float = 1f,
        obsNoiseScale: Float = 3f
    ) {
        // Prediction step
        particles.forEach {
            it.x += systemInput.x + Random.nextFloat() * systemNoiseScale
            it.y += systemInput.y + Random.nextFloat() * systemNoiseScale
        }

        // Update weights
        var totalWeight = 0f
        particles.forEach {
            val dx = it.x - observation.x
            val dy = it.y - observation.y
            val squaredDist = dx * dx + dy * dy
            it.weight = exp(-squaredDist / (2 * obsNoiseScale * obsNoiseScale)).toFloat()
            totalWeight += it.weight
        }

        // Normalize weights
        if (totalWeight < 1e-10) {
            val uniformWeight = 1f / numParticles
            particles.forEach { it.weight = uniformWeight }
        } else {
            particles.forEach { it.weight /= totalWeight }
        }

        // Resample
        resample()
    }

    private fun resample() {
        val cumulativeWeights = mutableListOf<Float>()
        var sum = 0f

        particles.forEach {
            sum += it.weight
            cumulativeWeights.add(sum)
        }

        val newParticles = mutableListOf<Particle>()
        val step = 1f / numParticles
        val u = Random.nextFloat() * step

        repeat(numParticles) { i ->
            val threshold = u + i * step
            var index = cumulativeWeights.binarySearch(threshold)
            if (index < 0) index = -index - 1
            index = index.coerceAtMost(particles.lastIndex)

            newParticles.add(
                Particle(
                    particles[index].x,
                    particles[index].y,
                    1f / numParticles
                )
            )
        }

        particles.clear()
        particles.addAll(newParticles)
    }

    fun estimate(): FloatArray {
        var sumX = 0f
        var sumY = 0f
        var totalWeight = 0f

        particles.forEach {
            sumX += it.x * it.weight
            sumY += it.y * it.weight
            totalWeight += it.weight
        }

        return if (totalWeight == 0f) floatArrayOf(0f, 0f)
        else floatArrayOf(sumX / totalWeight, sumY / totalWeight)
    }
}