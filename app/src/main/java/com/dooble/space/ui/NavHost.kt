package com.dooble.space.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 界面路由。 */
sealed interface Screen {
    data object Spaces : Screen
    data object Settings : Screen
    data class Detail(val spaceId: String) : Screen
    data class Check(val spaceId: String) : Screen
}

/**
 * 自定义导航宿主：支持 push / pop 转场动画 + 边缘滑动手势返回。
 *
 * 设计目标接近 iOS：
 *  - push：新页面从右侧滑入，旧页面轻微左移缩小。
 *  - pop：当前页右滑退出，上一页从缩放状态复位。
 *  - 手势：在页面任意位置横向右滑，超过阈值松手即返回；否则回弹。
 *
 * 不用 Navigation 组件的原因：本项目只有 4 个目的地，用状态机 + 动画
 * 比引入 Navigation 依赖更直接，且能完全控制手势与转场。
 */
@Composable
fun ScreenNavHost(
    initial: Screen = Screen.Spaces,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(Screen, navigate: (Screen) -> Unit, pop: () -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val scope = rememberCoroutineScope()

    var stack by remember { mutableStateOf(listOf(initial)) }
    val current = stack.last()
    val previous = stack.getOrNull(stack.size - 2)

    val currentOffset = remember { Animatable(0f) }
    val previousOffset = remember { Animatable(0f) }

    fun navigate(target: Screen) {
        if (stack.last() == target) return
        scope.launch {
            stack = stack + target
            currentOffset.snapTo(screenWidthPx)
            previousOffset.snapTo(0f)
            launch { currentOffset.animateTo(0f, tween(340, easing = FastOutSlowInEasing)) }
            launch { previousOffset.animateTo(-screenWidthPx * 0.16f, tween(340, easing = FastOutSlowInEasing)) }
        }
    }

    fun pop() {
        if (stack.size <= 1) return
        scope.launch {
            launch { currentOffset.animateTo(screenWidthPx, tween(300, easing = FastOutSlowInEasing)) }
            launch { previousOffset.animateTo(0f, tween(300, easing = FastOutSlowInEasing)) }
            delay(290)
            stack = stack.dropLast(1)
            currentOffset.snapTo(0f)
            if (stack.size > 1) {
                previousOffset.snapTo(-screenWidthPx * 0.16f)
                launch { previousOffset.animateTo(0f, tween(200, easing = FastOutSlowInEasing)) }
            } else {
                previousOffset.snapTo(0f)
            }
        }
    }

    val canSwipeBack = stack.size > 1

    BackHandler(enabled = canSwipeBack) { pop() }

    Box(modifier.fillMaxSize()) {
        if (previous != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val t = (-previousOffset.value / (screenWidthPx * 0.16f)).coerceIn(0f, 1f)
                        translationX = previousOffset.value
                        scaleX = 1f - 0.06f * t
                        scaleY = 1f - 0.06f * t
                    }
            ) {
                content(previous, ::navigate, ::pop)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = (currentOffset.value / screenWidthPx).coerceIn(0f, 1f)
                    translationX = currentOffset.value
                    scaleX = 1f - 0.06f * progress
                    scaleY = 1f - 0.06f * progress
                }
                .then(
                    if (canSwipeBack) {
                        Modifier.pointerInput(stack.size) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    val shouldPop = currentOffset.value > screenWidthPx * 0.28f
                                    if (shouldPop) {
                                        pop()
                                    } else {
                                        scope.launch {
                                            launch { currentOffset.animateTo(0f, tween(240, easing = FastOutSlowInEasing)) }
                                            launch { previousOffset.animateTo(-screenWidthPx * 0.16f, tween(240, easing = FastOutSlowInEasing)) }
                                        }
                                    }
                                },
                                onDragCancel = {
                                    scope.launch {
                                        launch { currentOffset.animateTo(0f, tween(240, easing = FastOutSlowInEasing)) }
                                        launch { previousOffset.animateTo(-screenWidthPx * 0.16f, tween(240, easing = FastOutSlowInEasing)) }
                                    }
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    val newValue = (currentOffset.value + dragAmount).coerceIn(0f, screenWidthPx)
                                    scope.launch { currentOffset.snapTo(newValue) }
                                    val progress = newValue / screenWidthPx
                                    scope.launch { previousOffset.snapTo(-screenWidthPx * 0.16f * progress) }
                                },
                            )
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            content(current, ::navigate, ::pop)
        }
    }
}