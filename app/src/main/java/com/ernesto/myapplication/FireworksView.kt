package com.ernesto.myapplication

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class FireworksView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Particle(
        var x: Float,
        var y: Float,
        val vx: Float,
        val vy: Float,
        val color: Int,
        val radius: Float,
        var alpha: Int = 255,
        val gravity: Float = 1.8f
    )

    private val particles = mutableListOf<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null

    private val burstColors = listOf(
        0xFFFF5252.toInt(), 0xFFFF4081.toInt(), 0xFFE040FB.toInt(),
        0xFF7C4DFF.toInt(), 0xFF448AFF.toInt(), 0xFF18FFFF.toInt(),
        0xFF69F0AE.toInt(), 0xFFFFFF00.toInt(), 0xFFFFD740.toInt(),
        0xFFFF6E40.toInt(), 0xFF00E676.toInt(), 0xFFFFEB3B.toInt()
    )

    fun launch() {
        if (width == 0 || height == 0) {
            post { launch() }
            return
        }
        particles.clear()
        spawnBurst(width * 0.3f, height * 0.25f)
        spawnBurst(width * 0.7f, height * 0.20f)
        postDelayed({ spawnBurst(width * 0.5f, height * 0.15f) }, 200)
        postDelayed({ spawnBurst(width * 0.2f, height * 0.30f) }, 400)
        postDelayed({ spawnBurst(width * 0.8f, height * 0.28f) }, 500)
        postDelayed({ spawnBurst(width * 0.5f, height * 0.35f) }, 700)

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2500
            interpolator = AccelerateInterpolator(0.4f)
            addUpdateListener {
                updateParticles()
                invalidate()
            }
            start()
        }
    }

    private fun spawnBurst(cx: Float, cy: Float) {
        val count = Random.nextInt(35, 55)
        for (i in 0 until count) {
            val angle = Random.nextDouble(0.0, Math.PI * 2).toFloat()
            val speed = Random.nextFloat() * 8f + 3f
            val color = burstColors[Random.nextInt(burstColors.size)]
            particles.add(
                Particle(
                    x = cx,
                    y = cy,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed - 2f,
                    color = color,
                    radius = Random.nextFloat() * 4f + 2f
                )
            )
        }
    }

    private fun updateParticles() {
        val iter = particles.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.x += p.vx
            p.y += p.vy + p.gravity
            p.alpha = (p.alpha - 4).coerceAtLeast(0)
            if (p.alpha <= 0) iter.remove()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (p in particles) {
            paint.color = p.color
            paint.alpha = p.alpha
            canvas.drawCircle(p.x, p.y, p.radius, paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
