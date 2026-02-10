package com.aotuman.baobaoai.ui

import android.util.Log
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import com.aotuman.baobaoai.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aotuman.baobaoai.FloatingWindowController
import com.aotuman.baobaoai.FloatingWindowState
import kotlinx.coroutines.delay

// 状态枚举
sealed class AssistantState {
    object Idle : AssistantState()
    data class Listening(val text: String = "") : AssistantState()
    data class Processing(val text: String) : AssistantState()
    data class Success(val text: String) : AssistantState()
    data class Error(val message: String) : AssistantState()
}

data class WindowConfig(
    val icon: String,
    val status: String,
    val text: String,
    val backgroundColor: Color
)

@Composable
fun FloatingWindowBall(
    floatingWindowController: FloatingWindowController,
    onStateChange: (AssistantState) -> Unit
) {
    // Reactively collect the floating window state
    val state by floatingWindowController.stateFlow.collectAsState()

    val assistantState = when (val s = state) {
        is FloatingWindowState.Visible -> s.assistantState
        is FloatingWindowState.TaskCompleted -> AssistantState.Success(s.statusText)
        else -> AssistantState.Idle
    }

    // Extract status, isTaskRunning, and onStopCallback from current state
    val status = when (val s = state) {
        is FloatingWindowState.Visible -> s.statusText
        else -> ""
    }
    val isTaskRunning = when (val s = state) {
        is FloatingWindowState.Visible -> s.isTaskRunning
        else -> false
    }
    val onStopCallback = when (val s = state) {
        is FloatingWindowState.Visible -> s.onStopCallback
        else -> null
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Success状态维持5秒后自动变为Idle
    LaunchedEffect(assistantState) {
        if (assistantState is AssistantState.Success) {
            Log.e("FloatingWindowBall", "Success状态维持5秒后自动变为Idle")
            delay(5000)
            floatingWindowController.updateStatus("准备就绪", AssistantState.Idle)
            floatingWindowController.setTaskRunning(false, AssistantState.Idle)
            floatingWindowController.setListening(false)
        }
    }

    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(16.dp)
    ) {
        // 悬浮窗
        if (assistantState !is AssistantState.Idle) {
            FloatingWindow(
                state = assistantState,
                onDismiss = { onStateChange(AssistantState.Idle) }
            )
        }

        // 悬浮球
        FloatingBall(
            state = assistantState,
            onClick = {
            }
        )
    }
}

@Composable
fun FloatingWindow(
    state: AssistantState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val config = when (state) {
        is AssistantState.Listening -> WindowConfig("🎤", "聆听中", state.text.ifEmpty { "打开微信给张三发消息" }, Color(33, 150, 243, alpha = 240))
        is AssistantState.Processing -> WindowConfig("⏳", "执行中", state.text, Color(156, 39, 176, alpha = 240))
        is AssistantState.Success -> WindowConfig("✅", "完成", state.text, Color(76, 175, 80, alpha = 240))
        is AssistantState.Error -> WindowConfig("❌", "失败", state.message, Color(244, 67, 54, alpha = 240))
        else -> null
    }

    if (config != null) {
        // 成功/错误状态自动收起
        if (state is AssistantState.Success || state is AssistantState.Error) {
            LaunchedEffect(state) {
                delay(2000)
                onDismiss()
            }
        }

        val animatedAlpha by animateFloatAsState(
            targetValue = 1f,
            animationSpec = tween(300),
            label = "windowAlpha"
        )

        Box(
            modifier = modifier
                .widthIn(min = 160.dp, max = 240.dp)
                .alpha(animatedAlpha)
                .clip(RoundedCornerShape(12.dp))
                .background(config.backgroundColor)
//                .shadow(8.dp, RoundedCornerShape(12.dp))
                .clickable { if (state is AssistantState.Success || state is AssistantState.Error) onDismiss() }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = config.icon,
                        fontSize = 18.sp
                    )
                    Text(
                        text = config.status,
                        fontSize = 16.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                }

                Text(
                    text = config.text,
                    fontSize = 18.sp,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun FloatingBall(
    state: AssistantState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val (size, color, icon) = when (state) {
        is AssistantState.Idle -> Triple(60.dp, Color(0, 0, 0, alpha = 180), "")
        is AssistantState.Listening -> Triple(48.dp, Color(33, 150, 243, alpha = 240), "🎤")
        is AssistantState.Processing -> Triple(48.dp, Color(156, 39, 176, alpha = 240), "⏳")
        is AssistantState.Success -> Triple(48.dp, Color(76, 175, 80, alpha = 240), "✅")
        is AssistantState.Error -> Triple(48.dp, Color(244, 67, 54, alpha = 240), "❌")
    }

    // 长按计时器
    var pressStartTime by remember { mutableLongStateOf(0L) }
    val coroutineScope = rememberCoroutineScope()
    val LONG_PRESS_DURATION = 5000L // 5秒长按

    // 动画
    val infiniteTransition = rememberInfiniteTransition(label = "ballAnimation")

    val alpha = when (state) {
        is AssistantState.Idle -> infiniteTransition.animateFloat(
            initialValue = 0.1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "idleAlpha"
        ).value
        else -> 1f
    }

    val scale = when (state) {
        is AssistantState.Listening -> infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "listeningScale"
        ).value
        else -> 1f
    }

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        onClick()
                    },
                    onPress = {
                        pressStartTime = System.currentTimeMillis()
                        tryAwaitRelease()
                        val pressDuration = System.currentTimeMillis() - pressStartTime
                        if (pressDuration >= LONG_PRESS_DURATION) {
                            // 长按5秒，关闭无障碍服务
                            val serviceName = "${context.packageName}/com.aotuman.baobaoai.AutoGLMService"
                            com.aotuman.baobaoai.utils.SystemCtrlUtil.disableAccessibilityService(
                                context,
                                serviceName
                            )
                            Log.d("FloatingBall", "Accessibility service disabled by long press")
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (state is AssistantState.Idle) {
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher),
                contentDescription = "App Icon",
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop
            )
        } else if (icon.isNotEmpty()) {
            Text(
                text = icon,
                fontSize = 24.sp
            )
        }
    }
}