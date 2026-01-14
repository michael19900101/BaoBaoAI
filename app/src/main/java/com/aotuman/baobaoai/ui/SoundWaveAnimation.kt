package com.aotuman.baobaoai.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.sin

@Composable
fun AdvancedSoundWaveAnimation(
    modifier: Modifier = Modifier,
    waveColor: Color = Color(0xFF2196F3)
) {
    val waveCount = 3
    val waveAmplitude = 15f
    val waveFrequency = 0.05f
    
    // 创建多个波浪的动画进度
    val animationProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveProgress"
    )
    
    Canvas(modifier = modifier.fillMaxSize()) {
        val centerY = size.height / 2
        val width = size.width
        
        for (waveIndex in 0 until waveCount) {
            val path = Path()
            path.moveTo(0f, centerY)
            
            for (x in 0 until width.toInt() step 4) {
                val progress = (x.toFloat() / width + animationProgress + waveIndex * 0.2f) % 1f
                val angle = progress * 2 * kotlin.math.PI
                val y = centerY + sin(angle) * 
                    waveAmplitude * (1 - waveIndex * 0.2f)
                path.lineTo(x.toFloat(), y.toFloat())
            }
            
            drawPath(
                path = path,
                color = waveColor.copy(alpha = 0.8f - waveIndex * 0.2f),
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }
}

@Composable
fun SoundBarAnimation(
    modifier: Modifier = Modifier,
    barCount: Int = 15,
    barColor: Color = Color(0xFF2196F3),
    barWidth: Float = 6f,
    barSpacing: Float = 8f
) {
    // 简化版本的音频条动画
    Canvas(modifier = modifier.fillMaxSize()) {
        val centerY = size.height / 2
        val totalWidth = barCount * (barWidth + barSpacing) - barSpacing
        val startX = (size.width - totalWidth) / 2
        
        for (barIndex in 0 until barCount) {
            // 使用正弦函数创建简单的波动效果
            val time = System.currentTimeMillis() % 1000 / 1000f
            val barHeight = (size.height * 0.3f) * 
                (0.5f + 0.5f * sin(time * 2 * kotlin.math.PI + barIndex * 0.5f))
            
            val x = (startX + barIndex * (barWidth + barSpacing)).toFloat()
            val y = (centerY - barHeight / 2).toFloat()
            val barHeightFloat = barHeight.toFloat()
            
            drawRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeightFloat),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}
