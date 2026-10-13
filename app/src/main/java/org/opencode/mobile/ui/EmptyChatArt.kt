/**
 * The empty conversation: white rain falling over a dark field, with the name
 * on top of it.
 *
 * Drawn here rather than taken from a picture: a rain that falls has to move,
 * and the project carries no image loader, so a static frame would either sit
 * still or need a library for one screen.
 *
 * Every number below is a proportion of the canvas, so the field survives any
 * screen without a single absolute pixel.
 */
@file:Suppress("MagicNumber")

package org.opencode.mobile.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.sin

/** The field the rain falls through: almost black, a touch lighter at the top. */
private const val ART_TOP = 0xFF0B0E13

private const val ART_BOTTOM = 0xFF050609

/** The rain itself is white. Not amber: over a dark field white is the only colour that reads as water. */
private const val ART_RAIN = 0xFFFFFFFF

private const val ART_ACCENT = 0xFFC79253
private const val ART_COLD = 0xFF6B5326
private const val ART_CORE = 0xFFFFF4E4

/** How many streaks are on screen. Enough to read as weather, few enough to read past. */
private const val ART_DROPS = 54

/** How bright the brightest streak is. The dimmer ones carry the depth. */
private const val ART_RAIN_ALPHA = 0.85f

/** How long one fall of the slowest drop takes. Faster drops fall two or three times in that window. */
private const val ART_RAIN_MS = 5000

private const val ART_SMALL_SP = 16
private const val ART_BIG_SP = 64

private val ART_SMALL_TRACK = 6.sp

/** Height of the dark band that keeps the rain off the letters. */
private const val ART_BAND_DP = 150

private const val ART_BAND_INK = 0.55f

/**
 * One streak.
 *
 * Every field is a fraction, not a pixel, and [offset] is where the streak
 * starts in the loop. Two streaks with the same [offset] and [speed] would
 * fall in lockstep, which is what makes drawn rain look like a grid.
 */
private data class Streak(
    val x: Float,
    val offset: Float,
    val speed: Float,
    val len: Float,
    val weight: Float,
    val bright: Float,
    val slant: Float,
)

/**
 * The empty conversation.
 *
 * Composed even when messages are on screen, so it can fade away instead of
 * blinking: a branch that appears and disappears cannot be animated.
 */
@Composable
internal fun EmptyChatArt(alpha: Float) {
    if (alpha <= 0.01f) return
    val phase = rememberInfiniteTransition(label = "empty-art").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(ART_RAIN_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "rain-phase",
    )
    val glow = rememberInfiniteTransition(label = "empty-word").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(tween(5600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "word-glow",
    )
    Box(Modifier.fillMaxSize().alpha(alpha), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(ART_TOP), Color(ART_BOTTOM)))),
        )
        RainLayer(phase.value)
        Box(
            Modifier
                .fillMaxWidth()
                .height(ART_BAND_DP.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color(ART_BOTTOM).copy(alpha = ART_BAND_INK),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "DEEP",
                color = Color(ART_ACCENT),
                fontSize = ART_SMALL_SP.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = ART_SMALL_TRACK,
            )
            Text(
                text = "HARNESS",
                color = lerpWord(glow.value),
                fontSize = ART_BIG_SP.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** The falling field. One pass, no allocation: every streak is computed in place. */
@Composable
private fun RainLayer(phase: Float) {
    Canvas(Modifier.fillMaxSize()) { drawRain(phase) }
}

/**
 * The streaks.
 *
 * A streak is drawn from its head down for its own length, and the head walks
 * from above the field to below it and wraps. Wrapping on a value that keeps
 * growing is what makes the fall endless without a single frame of flicker.
 *
 * [Streak.speed] must be a whole number of falls per period. The animation
 * hands us a phase that runs from 0 to 1 and then starts over, so a streak with
 * a fractional speed lands in a different place when the phase restarts than
 * where it was — and every streak with that fraction jumps on the same frame.
 * That is the hitch. With a whole number the wrap lands exactly where the
 * streak started, so the restart is invisible and only the falling reads.
 */
private fun DrawScope.drawRain(phase: Float) {
    val h = size.height
    val w = size.width
    for (i in 0 until ART_DROPS) {
        val s = streakOf(i.toFloat())
        val len = s.len * h
        val head = ((s.offset + phase * s.speed) % 1f) * (h + len * 2f) - len
        drawLine(
            color = Color(ART_RAIN).copy(alpha = s.bright * ART_RAIN_ALPHA),
            start = Offset(s.x * w, head),
            end = Offset(s.x * w + s.slant * len, head + len),
            strokeWidth = s.weight,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * The streak with this number.
 *
 * Its numbers come from a hash of the index, not from a random generator: a
 * random value would be re-rolled on every recomposition and the field would
 * boil instead of fall.
 */
private fun streakOf(i: Float): Streak {
    val a = hashOf(i)
    val b = hashOf(i + 11.7f)
    val c = hashOf(i + 27.3f)
    val d = hashOf(i + 41.9f)
    return Streak(
        x = a,
        offset = b,
        speed = 1f + floor(c * 2.99f),
        len = 0.02f + d * 0.055f,
        weight = 0.9f + a * 1.6f,
        bright = 0.16f + b * 0.72f,
        slant = 0.012f + c * 0.045f,
    )
}

/** A stable number in [0, 1) for any input. */
private fun hashOf(v: Float): Float {
    val s = sin(v * 127.1f) * 43758.5453f
    return s - floor(s)
}

/**
 * How bright the word is right now.
 *
 * The whole word breathes together. Lighting it letter by letter needed one
 * text per letter, and that is what pulled the word apart, so the travel is
 * gone on purpose.
 */
private fun lerpWord(glow: Float): Color = lerp(Color(ART_COLD), Color(ART_CORE), glow)
