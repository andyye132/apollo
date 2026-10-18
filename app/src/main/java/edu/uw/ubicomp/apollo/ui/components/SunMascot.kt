package edu.uw.ubicomp.apollo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Apollo's mascot: a small waving sun. Used on the Ask Apollo nav tab and the location
 * permission sheet. Drawn, not an image asset — keeps the app self-contained.
 */
@Composable
fun SunMascot(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 28.dp,
    color: Color,
) {
    Canvas(modifier = modifier.size(size)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val coreRadius = this.size.minDimension * 0.3f
        val rayInner = coreRadius * 1.25f
        val rayOuter = coreRadius * 1.9f
        val strokeWidth = this.size.minDimension * 0.08f

        repeat(8) { i ->
            val angle = (i * 45.0) * (Math.PI / 180.0)
            val start = Offset(
                x = center.x + (rayInner * cos(angle)).toFloat(),
                y = center.y + (rayInner * sin(angle)).toFloat(),
            )
            val end = Offset(
                x = center.x + (rayOuter * cos(angle)).toFloat(),
                y = center.y + (rayOuter * sin(angle)).toFloat(),
            )
            drawLine(color = color, start = start, end = end, strokeWidth = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }

        drawCircle(color = color, radius = coreRadius, center = center)

        // A simple smiling face so it reads as a waving character, not just a sun glyph.
        val eyeOffsetX = coreRadius * 0.4f
        val eyeOffsetY = coreRadius * 0.15f
        val eyeRadius = coreRadius * 0.1f
        val faceColor = Color.White
        drawCircle(color = faceColor, radius = eyeRadius, center = center + Offset(-eyeOffsetX, -eyeOffsetY))
        drawCircle(color = faceColor, radius = eyeRadius, center = center + Offset(eyeOffsetX, -eyeOffsetY))
        drawArc(
            color = faceColor,
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = center - Offset(coreRadius * 0.45f, coreRadius * 0.35f),
            size = androidx.compose.ui.geometry.Size(coreRadius * 0.9f, coreRadius * 0.9f),
            style = Stroke(width = strokeWidth * 0.6f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}
