package com.bydassistantng.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bydassistantng.ui.theme.LocalNeonLook

/** What the orb shows. */
enum class OrbMode { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

fun HomeUiState.orbMode(): OrbMode = when (this) {
    HomeUiState.Idle -> OrbMode.IDLE
    HomeUiState.Listening -> OrbMode.LISTENING
    HomeUiState.Connecting, HomeUiState.Thinking, is HomeUiState.Dispatching -> OrbMode.THINKING
    is HomeUiState.Speaking -> OrbMode.SPEAKING
    is HomeUiState.Error -> OrbMode.ERROR
}

/**
 * The talk button as a glowing orb, the centre of Home. It says what the assistant is doing without words:
 *  - idle: a slow breath;
 *  - listening: rings spreading outwards, like sound coming in;
 *  - thinking (connecting, understanding, doing something on the car): an arc running round the edge;
 *  - speaking: a quicker pulse;
 *  - error: the same orb, in red, still.
 * With motion switched off in Settings every state is drawn still, told apart by its colour and icon only.
 * Only the drawing changes; the press itself is a plain button (scale 0.96 while held).
 */
@Composable
fun VoiceOrb(
    mode: OrbMode,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
) {
    val look = LocalNeonLook.current
    val accent = if (mode == OrbMode.ERROR) Color(0xFFFF4D5E) else look.accent
    val accent2 = if (mode == OrbMode.ERROR) Color(0xFFFF8A5E) else look.accent2
    val moving = look.motion && mode != OrbMode.ERROR

    val t = rememberInfiniteTransition(label = "orb")
    val breath by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (mode == OrbMode.SPEAKING) 520 else 3200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val ripple by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "ripple",
    )
    val spin by t.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = 900f), label = "press")

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val r = this.size.minDimension / 2
            val core = r * 0.52f
            val b = if (moving) breath else 0.5f
            val pulse = when (mode) {
                OrbMode.SPEAKING -> 1f + 0.07f * b
                OrbMode.IDLE -> 1f + 0.03f * b
                else -> 1f
            }

            // The glow around it, wider while it is busy.
            val glow = when (mode) { OrbMode.IDLE -> 0.85f; OrbMode.ERROR -> 0.7f; else -> 1f }
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.34f * glow), accent2.copy(alpha = 0.10f * glow), Color.Transparent),
                    center = c, radius = r,
                ),
                radius = r, center = c,
            )

            // Listening: two rings spreading outwards, half a beat apart.
            if (mode == OrbMode.LISTENING) {
                for (k in 0..1) {
                    val p = if (moving) (ripple + k * 0.5f) % 1f else 0.35f + k * 0.3f
                    drawCircle(
                        color = accent.copy(alpha = (1f - p) * 0.55f),
                        radius = core * (1.05f + p * 0.85f), center = c,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }

            // The quiet ring that is always there, and the running arc while thinking.
            drawCircle(color = accent.copy(alpha = 0.22f), radius = core * 1.22f, center = c, style = Stroke(width = 1.5.dp.toPx()))
            if (mode == OrbMode.THINKING) {
                val sweep = 90f
                rotate(if (moving) spin else 0f, pivot = c) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Color.Transparent, accent, accent2), center = c),
                        startAngle = -sweep, sweepAngle = sweep, useCenter = false,
                        topLeft = Offset(c.x - core * 1.22f, c.y - core * 1.22f),
                        size = Size(core * 2.44f, core * 2.44f),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }

            // The orb itself: a sphere lit from the top left, accent into the second colour.
            val cr = core * pulse
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.95f), accent2.copy(alpha = 0.85f), Color(0xFF0B0F18)),
                    center = Offset(c.x - cr * 0.35f, c.y - cr * 0.4f), radius = cr * 1.6f,
                ),
                radius = cr, center = c,
            )
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent),
                    center = Offset(c.x - cr * 0.35f, c.y - cr * 0.45f), radius = cr * 0.55f),
                radius = cr, center = c,
            )
            drawCircle(color = Color.White.copy(alpha = 0.18f), radius = cr, center = c, style = Stroke(width = 1.dp.toPx()))
        }
        // The tap target is the orb, not the glow around it.
        Box(
            modifier = Modifier
                .size(size * 0.56f)
                .scale(press)
                .clip(CircleShape)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (mode == OrbMode.IDLE || mode == OrbMode.ERROR) Icons.Filled.Mic else Icons.Filled.Stop,
                contentDescription = contentDescription,
                tint = Color(0xFF02040A),
                modifier = Modifier.size(size * 0.17f),
            )
        }
    }
}
