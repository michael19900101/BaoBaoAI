package com.aotuman.baobaoai.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aotuman.baobaoai.R

@Composable
fun FloatingWindowUI(
    isExpanded: Boolean,
    speechText: String,
    isListening: Boolean,
    onToggleExpand: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit
) {
    val animatedWidth by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = LinearEasing),
        label = "expansion"
    )
    
    Box(
        modifier = Modifier
            .height(80.dp)
            .clip(RoundedCornerShape(40.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .clickable(onClick = onToggleExpand)
    ) {
        // 折叠状态下的图标
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(16.dp)
                    .clickable { 
                        if (isListening) {
                            onStopListening()
                        } else {
                            onStartListening()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isListening) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_pause),
                        contentDescription = "停止聆听",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_play),
                        contentDescription = "开始聆听",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
            
            // 展开内容
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 16.dp)
                        .width(420.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    // 声音波浪动画或识别文字
                    if (isListening) {
                        AdvancedSoundWaveAnimation(
                            modifier = Modifier
                                .height(30.dp)
                                .fillMaxWidth(),
                            waveColor = MaterialTheme.colorScheme.primary
                        )
                    } else if (speechText.isNotEmpty()) {
                        Text(
                            text = speechText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                            maxLines = 1
                        )
                    } else {
                        Spacer(modifier = Modifier.height(30.dp))
                    }
                    
                    // 语音转文字内容
                    Text(
                        text = if (isListening) "正在聆听..." else if (speechText.isEmpty()) "点击开始语音识别" else speechText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
