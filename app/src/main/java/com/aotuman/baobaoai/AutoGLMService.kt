package com.aotuman.baobaoai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.aotuman.baobaoai.action.Action
import com.aotuman.baobaoai.action.ActionDescriber
import com.aotuman.baobaoai.action.ActionExecutor
import com.aotuman.baobaoai.action.ActionParser
import com.aotuman.baobaoai.data.ImageStorage
import com.aotuman.baobaoai.data.TaskEndState
import com.aotuman.baobaoai.network.ContentItem
import com.aotuman.baobaoai.network.ImageUrl
import com.aotuman.baobaoai.network.Message
import com.aotuman.baobaoai.network.ModelClient
import com.aotuman.baobaoai.ui.AssistantState
import com.aotuman.baobaoai.utils.AppMapper
import com.aotuman.baobaoai.utils.AppStateTracker
import com.aotuman.baobaoai.utils.DisplayUtils
import com.aotuman.baobaoai.utils.VoiceAssistantManager
import com.sidhu.autoinput.GestureAnimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AutoGLMService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoGLMService"
        private val _serviceInstance = MutableStateFlow<AutoGLMService?>(null)
        val serviceInstance = _serviceInstance.asStateFlow()
        
        fun getInstance(): AutoGLMService? = _serviceInstance.value
    }
    
    private val _currentApp = MutableStateFlow<String?>(null)
    val currentApp = _currentApp.asStateFlow()

    private var _floatingWindowController: FloatingWindowController? = null

    /** Expose floating window controller for external access */
    val floatingWindowController: FloatingWindowController? get() = this._floatingWindowController

    /** Animation controller for gesture visual feedback */
    private var _animationController: AnimationController? = null

    private var speechText = ""
    private var isListening = false

    // ModelClient instance for API calls
    private var modelClient: ModelClient? = null

    // Coroutine scope for service operations
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var lastImeVisible: Boolean = false
    private var lastImeCheckMs: Long = 0
    private var lastMoveWindowToTopMs: Long = 0


    val apiKey = BuildConfig.DEFAULT_API_KEY
    // Debug Mode Flag - set to true to bypass permission checks and service requirements
    private val DEBUG_MODE = false

    // Job to manage the current task lifecycle - allows cancellation
    private var currentTaskJob: kotlinx.coroutines.Job? = null

    // Conversation history for the API
    private val apiHistory = mutableListOf<Message>()

    // Image storage for saving screenshots
    private val imageStorage by lazy { ImageStorage(this) }

    // Dynamic accessor for ActionExecutor
    private val actionExecutor: ActionExecutor?
        get() = AutoGLMService.getInstance()?.let { ActionExecutor(it) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AutoGLMService", "Service connected")
        val apiKey = BuildConfig.DEFAULT_API_KEY
        _serviceInstance.value = this

        // Initialize controllers
        _floatingWindowController = FloatingWindowController(this)
        _animationController = AnimationController(this)

        // 初始化ModelClient
        modelClient = ModelClient(
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            apiKey = apiKey, // Will be set by user
            modelName = "autoglm-phone"
        )
        
        // 启动悬浮窗
        serviceScope.launch {
            _floatingWindowController?.showAndWaitForLayout(
                onStop = { stopListening() },
                isRunning = true
            )
        }

        startAssistant()
    }

    private fun testSendMessage(text: String) {
        Log.d("AutoGLM_Trace", "testSendMessage called with text: $text")
        if (text.isBlank()) return

        val service = AutoGLMService.getInstance()
        currentTaskJob = kotlinx.coroutines.Job()

        serviceScope.launch(Dispatchers.IO + currentTaskJob!!) {
            Log.d("AutoGLM_Debug", "Test coroutine started")

            AppMapper.refreshLauncherApps()

            Log.d("AutoGLM_Debug", "Starting new conversation history")
            apiHistory.clear()
            val dateFormat = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.getDefault())
            val dateStr = this@AutoGLMService.getString(R.string.prompt_date_prefix) + dateFormat.format(Date())
            apiHistory.add(Message("system", dateStr + "\n" + ModelClient.SYSTEM_PROMPT))

            var currentPrompt = "帮我打开美团"
            var step = 0
            val maxSteps = 20

            val isAppInForeground = if (DEBUG_MODE) false else AppStateTracker.isAppInForeground(application)
            Log.d("AutoGLM_Trace", "App in foreground: $isAppInForeground")

            if (!DEBUG_MODE && service != null) {
                service.resetFloatingWindowForNewTask()

                withContext(Dispatchers.Main) {
                    if (isAppInForeground) {
                        Log.d("AutoGLM_Trace", "App is in foreground, executing goHome()")
                        service.goHome()
                    } else {
                        Log.d("AutoGLM_Trace", "App not in foreground, skipping goHome()")
                    }
                }

                Log.d("AutoGLM_Trace", "Showing floating window and waiting for layout")
                service.showFloatingWindowAndWait(
                    onStop = { stopTask() },
                    isRunning = true
                )
            }

            var isFinished = false

            try {
                while (isActive && step < maxSteps) {
                    step++
                    Log.d("AutoGLM_Debug", "Test Step: $step")

                    if (!DEBUG_MODE && service != null) {
                        service.updateFloatingStatus(
                            this@AutoGLMService.getString(R.string.status_thinking),
                            AssistantState.Processing(this@AutoGLMService.getString(R.string.status_thinking)))
                    }

                    val screenshot = if (step == 1 && isAppInForeground) {
                        Log.d("AutoGLM_Debug", "Step 1: Skipping screenshot (app in foreground)")
                        null
                    } else {
                        Log.d("AutoGLM_Debug", "Taking screenshot for step $step...")
                        if (DEBUG_MODE) {
                            Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
                        } else {
                            service?.takeScreenshot()
                        }
                    }

                    if (screenshot == null && !(step == 1 && isAppInForeground)) {
                        Log.e("AutoGLM_Debug", "Screenshot failed")
                        postError(application.getString(R.string.error_screenshot_failed))
                        break
                    }

                    if (screenshot != null) {
                        Log.d("ChatViewModel", "Screenshot size: ${screenshot.width}x${screenshot.height}")
                    }

                    val screenWidth = if (DEBUG_MODE) 1080 else DisplayUtils.getScreenWidth(getApplication())
                    val screenHeight = if (DEBUG_MODE) 2400 else DisplayUtils.getScreenHeight(getApplication())
                    Log.d("ChatViewModel", "Screen size: ${screenWidth}x${screenHeight}")

                    val currentApp = if (DEBUG_MODE) "DebugApp" else (service?.currentApp?.value ?: "Unknown")
                    val screenInfo = "{\"current_app\": \"$currentApp\"}"

                    val textPrompt = if (step == 1) {
                        "$currentPrompt\n\n$screenInfo"
                    } else {
                        "** Screen Info **\n\n$screenInfo"
                    }

                    val userContentItems = mutableListOf<ContentItem>()
                    if (screenshot != null) {
                        userContentItems.add(ContentItem("image_url", imageUrl = ImageUrl("data:image/jpeg;base64,${ModelClient.bitmapToBase64(screenshot)}")))
                    }
                    userContentItems.add(ContentItem("text", text = textPrompt))

                    val userMessage = Message("user", userContentItems)
                    apiHistory.add(userMessage)

                    Log.d("AutoGLM_Debug", "Simulating API response...")
                    val responseText = simulateApiResponse(text, step)
                    val unescapedResponseText = unescapeResponse(responseText)
                    Log.d("AutoGLM_Debug", "Simulated response received: $unescapedResponseText")

                    if (unescapedResponseText.startsWith("Error")) {
                        Log.e("AutoGLM_Debug", "API Error: $unescapedResponseText")
                        postError(unescapedResponseText)
                        break
                    }

                    val (thinking, _) = ActionParser.parseResponsePartsToParsedAction(unescapedResponseText)
                    val actionStr = ActionParser.extractActionString(unescapedResponseText)

                    Log.i("AutoGLM_Log", "\n==================================================")
                    Log.i("AutoGLM_Log", "💭 思考过程:")
                    Log.i("AutoGLM_Log", thinking)
                    Log.i("AutoGLM_Log", "🎯 执行动作:")
                    Log.i("AutoGLM_Log", actionStr)
                    Log.i("AutoGLM_Log", "==================================================")

                    apiHistory.add(Message("assistant", buildAssistantContent(thinking, actionStr)))

                    val screenshotPath = if (screenshot != null) {
                        imageStorage.saveImage(screenshot)
                    } else {
                        null
                    }
                    Log.d("AutoGLM_Debug", "Saved screenshot to $screenshotPath")

                    if (DEBUG_MODE) {
                        Log.d("AutoGLM_Debug", "DEBUG_MODE enabled, stopping after one round")
                        break
                    }

                    val action = ActionParser.parseAction(actionStr, screenWidth, screenHeight)

                    service?.updateFloatingStatus(getActionDescription(action),
                        AssistantState.Processing(getActionDescription(action)))

                    val executor = actionExecutor
                    if (executor == null) {
                        postError(application.getString(R.string.error_executor_null))
                        break
                    }

                    ensureActive()

                    val success = executor.execute(action)

                    if (action is Action.Finish) {
                        isFinished = true
                        service?.updateFloatingStatus(
                            application.getString(R.string.action_finish),
                            AssistantState.Success(application.getString(R.string.action_finish)))

                        val floatingWindow = AutoGLMService.getInstance()?.floatingWindowController
                        floatingWindow?.markTaskCompleted()

                        updateTaskState(TaskEndState.COMPLETED, step)
                        break
                    }

                    if (!success) {
                        apiHistory.add(Message("user", application.getString(R.string.error_last_action_failed)))
                    }

                    removeImagesFromHistory()

                    delay(2000)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d("ChatViewModel", "Task was cancelled by user")
                updateTaskState(TaskEndState.USER_STOPPED, step)
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("AutoGLM_Debug", "Exception in testSendMessage loop: ${e.message}", e)
                postError(application.getString(R.string.error_runtime_exception, e.message))
            } finally {

            }

            if (!isFinished && isActive) {
                if (!DEBUG_MODE) {
                    if (step >= maxSteps) {
                        service?.updateFloatingStatus(
                            application.getString(R.string.error_task_terminated_max_steps),
                            AssistantState.Error(application.getString(R.string.error_task_terminated_max_steps)))

                        val floatingWindow = AutoGLMService.getInstance()?.floatingWindowController
                        floatingWindow?.markTaskCompleted()

                        updateTaskState(TaskEndState.MAX_STEPS_REACHED, step)
                    }
                }
            }
        }
    }

    private fun simulateApiResponse(userText: String, step: Int): String {
        return when {
            userText.contains("美团") && step == 1 -> {
                """
                用户想要打开美团应用。我需要在屏幕上找到美团应用的图标并点击它。
                
                tap(540, 1200)
                """.trimIndent()
            }
            step == 1 -> {
                """
                我理解了用户的需求。让我尝试执行相应的操作。
                
                tap(540, 1200)
                """.trimIndent()
            }
            else -> {
                """
                操作已完成。
                
                finish("任务完成")
                """.trimIndent()
            }
        }
    }

    private fun startAssistant() {
        serviceScope.launch {
            // 开始语音助手
            VoiceAssistantManager.startAssistant(
                context = this@AutoGLMService,
                onWakeUpCallback = {
                    Log.i(TAG, "=== 语音助手唤醒 ===")
                    // 这里可以添加唤醒后的UI反馈
                    _floatingWindowController?.updateStatus("正在聆听...", AssistantState.Listening("正在聆听..."))
                    _floatingWindowController?.setTaskRunning(true, AssistantState.Listening("正在聆听..."))
                    _floatingWindowController?.setListening(true)
                },
                onListeningCallback = { result ->
                    _floatingWindowController?.updateStatus(result, AssistantState.Listening(result))
                    _floatingWindowController?.setTaskRunning(true, AssistantState.Listening(result))
                    _floatingWindowController?.setListening(true)
                },
                onCommandCallback = { result ->
                    Log.i(TAG, "=== 接收到命令: $result ===")
                    // 这里可以添加命令处理逻辑
                    _floatingWindowController?.updateStatus(result, AssistantState.Processing(result))
                    _floatingWindowController?.updateSpeechText(result)
                    _floatingWindowController?.setTaskRunning(true, AssistantState.Processing(result))
                    _floatingWindowController?.setListening(false)


                    var voiceResultText = result
                    // 执行核心功能：获取截图->发送给模型->解析响应->执行操作指令
                    serviceScope.launch(Dispatchers.IO) {
                        try{
                            testSendMessage(voiceResultText)
//                            sendMessage(text = voiceResultText)
                        } catch (e: Exception) {
                            Log.e("AutoGLMService", "Error processing request: ${e.message}", e)
                            withContext(Dispatchers.Main) {
                                _floatingWindowController?.updateStatus("处理请求时出错: ${e.message}", AssistantState.Error("处理请求时出错: ${e.message}"))
                            }
                        } finally {
                            withContext(Dispatchers.Main) {
                                _floatingWindowController?.setTaskRunning(false, AssistantState.Idle)
                            }
                        }
                    }
                },
                onSleepCallback = {
                    Log.i(TAG, "=== 语音助手休眠 ===")
                    // 这里可以添加休眠后的UI反馈
                },
                onErrorCallback = {
                    Log.e(TAG, "=== 语音助手错误: $it ===")
                    // 这里可以添加错误处理逻辑
                    speechText = "识别失败，请重试"
                    isListening = false
                    _floatingWindowController?.updateStatus("识别失败，请重试", AssistantState.Error("识别失败，请重试"))
                    _floatingWindowController?.setTaskRunning(false, AssistantState.Error("识别失败，请重试"))
                    _floatingWindowController?.setListening(false)
                }
            )

            Log.i(TAG, "语音助手启动成功")
            Log.i(TAG, "请说 '你好,包包' 来唤醒语音助手")
            Log.i(TAG, "唤醒后请说出您的命令")
        }
    }

    private fun stopListening() {
        isListening = false
        _floatingWindowController?.setTaskRunning(false, AssistantState.Idle)
        _floatingWindowController?.setListening(false)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        _floatingWindowController?.removeAndHide()
        _floatingWindowController = null
        _animationController = null
        _serviceInstance.value = null
        stopListening()
        return super.onUnbind(intent)
    }
    
    /**
     * Shows the floating window and waits for layout to complete.
     * This is more reliable than blind delay for ensuring window is ready for operations like screenshot.
     *
     * @param onStop Optional callback when stop button is clicked
     * @param isRunning Whether the task is currently running (affects UI display)
     */
    suspend fun showFloatingWindowAndWait(onStop: () -> Unit, isRunning: Boolean = true) {
        withContext(Dispatchers.Main) {
            if (_floatingWindowController == null) {
                _floatingWindowController = FloatingWindowController(this@AutoGLMService)
            }
            Log.d("AutoGLMService", "showFloatingWindowAndWait called with isRunning=$isRunning")
            _floatingWindowController?.showAndWaitForLayout(onStop, isRunning)
        }
    }

    /**
     * Resets the floating window dismissed state for a new task.
     * Should be called when a new task starts.
     */
    fun resetFloatingWindowForNewTask() {
        serviceScope.launch {
            if (_floatingWindowController == null) {
                _floatingWindowController = FloatingWindowController(this@AutoGLMService)
            }
            _floatingWindowController?.resetForNewTask()
            Log.d("AutoGLMService", "Reset floating window for new task")
        }
    }

    /**
     * Hides and removes the floating window completely.
     * For temporary hiding during gestures/screenshots, use the useWindowSuspension() helper.
     */
    fun hideFloatingWindow() {
        serviceScope.launch {
            _floatingWindowController?.removeAndHide()
        }
    }

    /**
     * Dismisses the floating window and marks it as user-dismissed.
     * The window will not auto-show on app background until resetFloatingWindowForNewTask() is called.
     */
    fun dismissFloatingWindow() {
        serviceScope.launch {
            _floatingWindowController?.dismiss()
        }
    }

    fun updateFloatingStatus(text: String, assistantState: AssistantState) {
        serviceScope.launch {
            _floatingWindowController?.updateStatus(text, assistantState)
        }
    }

    fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun isImeVisible(): Boolean {
        return try {
            val currentWindows = windows
            for (window in currentWindows) {
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    val bounds = Rect()
                    window.getBoundsInScreen(bounds)
                    if (bounds.width() > 0 && bounds.height() > 0) return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun handleImeVisibility() {
        val now = System.currentTimeMillis()
        val throttleMs = 250L
        if (now - lastImeCheckMs < throttleMs) return
        lastImeCheckMs = now

        val visible = isImeVisible()
        if (visible) {
            val debounceMs = 800L
            val isRisingEdge = !lastImeVisible
            val canRepeat = now - lastMoveWindowToTopMs >= debounceMs
            if (isRisingEdge || canRepeat) {
                lastMoveWindowToTopMs = now
                _floatingWindowController?.moveWindowToTop()
            }
        }
        lastImeVisible = visible
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        handleImeVisibility()
        event?.packageName?.let {
            val pkg = it.toString()
            if (_currentApp.value != pkg) {
                Log.d("AutoGLM_Trace", "Current App changed to: $pkg")
                _currentApp.value = pkg
            }
        }
    }

    override fun onInterrupt() {
        Log.w("AutoGLMService", "Service interrupted")
    }
    
    fun getScreenHeight(): Int = DisplayUtils.getScreenHeight(this)

    fun getScreenWidth(): Int = DisplayUtils.getScreenWidth(this)

    /**
     * Takes a screenshot with callback-based completion and timeout.
     * Uses useWindowSuspension helper for floating window state management.
     *
     * @param timeoutMs Timeout for the screenshot operation in milliseconds (default: 5000ms)
     * @return The screenshot bitmap, or null if failed/timeout
     */
    suspend fun takeScreenshot(timeoutMs: Long = 5000): Bitmap? {
        return _floatingWindowController?.useWindowSuspension {
            GestureAnimator.hideAllOverlays()
            delay(60)
            try {
                // Use withTimeout to handle screenshot operation timeout
                withTimeout(timeoutMs) {
                    // Take Screenshot with callback
                    suspendCoroutine<Bitmap?> { continuation ->
                        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                        val displayId = windowManager.defaultDisplay.displayId

                        takeScreenshot(
                            displayId,
                            mainExecutor,
                            object : TakeScreenshotCallback {
                                override fun onSuccess(screenshot: ScreenshotResult) {
                                    try {
                                        val bitmap = Bitmap.wrapHardwareBuffer(
                                            screenshot.hardwareBuffer,
                                            screenshot.colorSpace
                                        )
                                        // Copy to software bitmap for processing
                                        val softwareBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                        screenshot.hardwareBuffer.close()
                                        continuation.resume(softwareBitmap)
                                    } catch (e: Exception) {
                                        Log.e("AutoGLMService", "Error processing screenshot", e)
                                        continuation.resume(null)
                                    }
                                }

                                override fun onFailure(errorCode: Int) {
                                    val errorMsg = when(errorCode) {
                                        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "INTERNAL_ERROR"
                                        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "NO_ACCESSIBILITY_ACCESS"
                                        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "INTERVAL_TIME_SHORT"
                                        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "INVALID_DISPLAY"
                                        else -> "UNKNOWN($errorCode)"
                                    }
                                    Log.e("AutoGLMService", "Screenshot failed: $errorMsg")
                                    continuation.resume(null)
                                }
                            }
                        )
                    }
                }
            } finally {
                GestureAnimator.restoreAllOverlays()
            }
        } ?: run {
            Log.e("AutoGLMService", "FloatingWindowController not available for screenshot")
            null
        }
    }

    suspend fun performTap(x: Float, y: Float): Boolean {
        val serviceWidth = getScreenWidth()
        val serviceHeight = getScreenHeight()
        Log.d("AutoGLMService", "performTap: Request($x, $y) vs Screen($serviceWidth, $serviceHeight)")

        if (x < 0 || x > serviceWidth || y < 0 || y > serviceHeight) {
            Log.w("AutoGLMService", "Tap coordinates ($x, $y) out of bounds")
            return false
        }

        Log.d("AutoGLMService", "Dispatching Gesture: Tap at ($x, $y)")

        // Check overlap and move if necessary
        serviceScope.launch {
            _floatingWindowController?.avoidArea(x, y)
        }

        return _floatingWindowController?.useWindowSuspension {
            // Show visual indicator
            _animationController?.showTapAnimation(x, y)

            // Dispatch gesture and wait for completion
            suspendCoroutine { continuation ->
                val path = Path().apply {
                    moveTo(x, y)
                    lineTo(x, y)
                }
                val builder = GestureDescription.Builder()
                builder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))

                val dispatchResult = dispatchGesture(builder.build(), object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d("AutoGLMService", "Gesture Completed: Tap at ($x, $y)")
                        continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w("AutoGLMService", "Gesture Cancelled: Tap at ($x, $y)")
                        continuation.resume(false)
                    }
                }, null)

                if (!dispatchResult) {
                    continuation.resume(false)
                }
            }
        } ?: false
    }

    suspend fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 1000): Boolean {
        // Check overlap and move if necessary (using start position)
        serviceScope.launch {
            _floatingWindowController?.avoidArea(startX, startY)
        }

        // Show visual indicator
        _animationController?.showSwipeAnimation(startX, startY, endX, endY, duration)

        return _floatingWindowController?.useWindowSuspension {
            // Dispatch gesture and wait for completion
            suspendCoroutine { continuation ->
                val path = Path().apply {
                    moveTo(startX, startY)
                    lineTo(endX, endY)
                }
                val builder = GestureDescription.Builder()
                // Use a fixed shorter duration (500ms) for the actual gesture to ensure it registers as a fling/scroll
                // The animation will play slower (duration) to be visible to the user
                builder.addStroke(GestureDescription.StrokeDescription(path, 0, 500))

                val dispatchResult = dispatchGesture(builder.build(), object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        continuation.resume(false)
                    }
                }, null)

                if (!dispatchResult) {
                    continuation.resume(false)
                }
            }
        } ?: false
    }

    suspend fun performLongPress(x: Float, y: Float, duration: Long = 1000): Boolean {
        Log.d("AutoGLMService", "Dispatching Gesture: Long Press at ($x, $y)")
        // Show visual indicator
        _animationController?.showTapAnimation(x, y, duration)
        // Long press is effectively a swipe from x,y to x,y with long duration
        return performSwipe(x, y, x, y, duration)
    }
    
    fun performGlobalBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }
    
    fun performGlobalHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Unescapes escape sequences in the response text.
     * Converts literal escape sequences like \n, \t, \" to their actual characters.
     */
    private fun unescapeResponse(text: String): String {
        return text
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\r", "\r")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun updateTaskState(state: TaskEndState, stepCount: Int) {

    }

    private fun postError(msg: String) {
        val service = AutoGLMService.getInstance()

        val currentPkg = service?.currentApp?.value
        val myPkg = application.packageName
        if (currentPkg == myPkg) {
            service?.hideFloatingWindow()
        } else {
            service?.updateFloatingStatus(
                application.getString(R.string.action_error, msg),
                AssistantState.Error(application.getString(R.string.action_error, msg)))
        }
    }

    fun stopTask() {
        // Cancel the current task job - this will propagate cancellation to all coroutines
        currentTaskJob?.cancel()
        currentTaskJob = null

        // Update UI state - explicitly clear error to avoid showing cancellation as error
//        _uiState.value = _uiState.value.copy(isRunning = false, isLoading = false, error = null)

        // Notify floating window controller that task is no longer running
        // This ensures isTaskRunning flag is properly synchronized before dismissal
        val service = AutoGLMService.getInstance()
        service?.floatingWindowController?.setTaskRunning(false, AssistantState.Idle)

        // Note: The floating window will be dismissed by the UI layer (FloatingWindowContent.kt)
        // which also launches the main app after the window is fully hidden
    }

    /**
     * Generates a description for an action using ActionDescriber.
     * This replaces the old getActionDescription() method.
     */
    private fun getActionDescription(action: Action): String {
        return ActionDescriber.describe(action,this@AutoGLMService)
    }

    /**
     * Builds assistant content for API history and database storage.
     * Format: {thinking}{action}
     * Example: "I need to launch the app.do(action=\"Launch\", app=\"美团\")"
     */
    private fun buildAssistantContent(thinking: String, action: String): String {
        return if (action.isNotEmpty()) {
            "$thinking$action"
        } else {
            thinking
        }
    }

    private fun removeImagesFromHistory() {
        // Python logic: Remove images from the last user message to save context space
        // The history is: [..., User(Image+Text), Assistant(Text)]
        // So we look at the second to last item.
        if (apiHistory.size < 2) return

        val lastUserIndex = apiHistory.size - 2
        if (lastUserIndex < 0) return

        val lastUserMsg = apiHistory[lastUserIndex]
        if (lastUserMsg.role == "user" && lastUserMsg.content is List<*>) {
            try {
                @Suppress("UNCHECKED_CAST")
                val contentList = lastUserMsg.content as List<*>

                // Filter items keeping only text
                val textOnlyList = contentList.filter { item ->
                    (item as? ContentItem)?.type == "text"
                }

                // Replace the message in history with the text-only version
                apiHistory[lastUserIndex] = lastUserMsg.copy(content = textOnlyList)
                // Log.d("ChatViewModel", "Removed image from history at index $lastUserIndex")
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to remove image from history", e)
            }
        }
    }

    fun sendMessage(text: String) {
        Log.d("AutoGLM_Trace", "sendMessage called with text: $text")
        // Skip blank check
        if (text.isBlank()) return

        if (modelClient == null) {
            Log.d("AutoGLM_Trace", "modelClient is null, initializing...")
            // Try to init with current state if not init
            modelClient = ModelClient(
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                apiKey = apiKey, // Will be set by user
                modelName = "autoglm-phone"
            )
            Log.d("AutoGLM_Debug", "modelClient initialized.")
        } else {
            Log.d("AutoGLM_Debug", "modelClient already initialized")
        }


        val service = AutoGLMService.getInstance()


        // Create a new Job for this task - allows cancellation via stopTask()
        currentTaskJob = kotlinx.coroutines.Job()

        val userTimestamp = System.currentTimeMillis()

        serviceScope.launch(Dispatchers.IO + currentTaskJob!!) {
            Log.d("AutoGLM_Debug", "Coroutine started")

            // Refresh app mapping before each request
            AppMapper.refreshLauncherApps()


            // Start new conversation with system prompt
            Log.d("AutoGLM_Debug", "Starting new conversation history")
            apiHistory.clear()
            // Add System Prompt with Date matching Python logic
            val dateFormat = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.getDefault())
            val dateStr = this@AutoGLMService.getString(R.string.prompt_date_prefix) + dateFormat.format(Date())
            apiHistory.add(Message("system", dateStr + "\n" + ModelClient.SYSTEM_PROMPT))

            var currentPrompt = text
            var step = 0
            val maxSteps = 20

            // Check if app is in foreground (used for both goHome and screenshot decisions)
            val isAppInForeground = if (DEBUG_MODE) false else AppStateTracker.isAppInForeground(
                application
            )
            Log.d("AutoGLM_Trace", "App in foreground: $isAppInForeground")

            if (!DEBUG_MODE && service != null) {
                // Reset floating window state for new task
                service.resetFloatingWindowForNewTask()

                withContext(Dispatchers.Main) {
                    // Only go home if this app is in the foreground
                    if (isAppInForeground) {
                        Log.d("AutoGLM_Trace", "App is in foreground, executing goHome()")
                        service.goHome()
                    } else {
                        Log.d("AutoGLM_Trace", "App not in foreground, skipping goHome()")
                    }
                }

                // Show floating window and wait for layout completion
                // This is more reliable than blind delay - uses OnGlobalLayoutListener callback
                Log.d("AutoGLM_Trace", "Showing floating window and waiting for layout")
                service.showFloatingWindowAndWait(
                    onStop = { stopTask() },
                    isRunning = true
                )
            }

            var isFinished = false

            try {
                while (isActive && step < maxSteps) {
                    step++
                    Log.d("AutoGLM_Debug", "Step: $step")

                    if (!DEBUG_MODE && service != null) {
                        service.updateFloatingStatus(
                            this@AutoGLMService.getString(R.string.status_thinking),
                            AssistantState.Processing(this@AutoGLMService.getString(R.string.status_thinking)))
                    }

                    // 1. Take Screenshot
                    // Skip screenshot on first step if the request was initiated from our own app
                    val screenshot = if (step == 1 && isAppInForeground) {
                        Log.d("AutoGLM_Debug", "Step 1: Skipping screenshot (app in foreground)")
                        null
                    } else {
                        Log.d("AutoGLM_Debug", "Taking screenshot for step $step...")
                        if (DEBUG_MODE) {
                            Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
                        } else {
                            service?.takeScreenshot()
                        }
                    }

                    // Check screenshot failure (only if we expected to take one)
                    if (screenshot == null && !(step == 1 && isAppInForeground)) {
                        Log.e("AutoGLM_Debug", "Screenshot failed")
                        postError(application.getString(R.string.error_screenshot_failed))
                        break
                    }

                    // Log screenshot success
                    if (screenshot != null) {
                        Log.d("ChatViewModel", "Screenshot size: ${screenshot.width}x${screenshot.height}")
                    }

                    // 2. Get screen dimensions for ActionParser coordinate system
                    val screenWidth = if (DEBUG_MODE) 1080 else DisplayUtils.getScreenWidth(getApplication())
                    val screenHeight = if (DEBUG_MODE) 2400 else DisplayUtils.getScreenHeight(getApplication())
                    Log.d("ChatViewModel", "Screen size: ${screenWidth}x${screenHeight}")

                    // 3. Build User Message
                    val currentApp = if (DEBUG_MODE) "DebugApp" else (service?.currentApp?.value ?: "Unknown")
                    val screenInfo = "{\"current_app\": \"$currentApp\"}"

                    val textPrompt = if (step == 1) {
                        "$currentPrompt\n\n$screenInfo"
                    } else {
                        "** Screen Info **\n\n$screenInfo"
                    }

                    val userContentItems = mutableListOf<ContentItem>()
                    // Only add image if we have a screenshot (subsequent steps)
                    if (screenshot != null) {
                        // Doubao/OpenAI vision models often prefer Image first, then Text
                        userContentItems.add(ContentItem("image_url", imageUrl = ImageUrl("data:image/jpeg;base64,${ModelClient.bitmapToBase64(screenshot)}")))
                    }
                    userContentItems.add(ContentItem("text", text = textPrompt))

                    val userMessage = Message("user", userContentItems)
                    apiHistory.add(userMessage)

                    // 3. Call API
                    Log.d("AutoGLM_Debug", "Sending request to ModelClient...")
                    val responseText = modelClient?.sendRequest(apiHistory, screenshot) ?: "Error: Client null"
                    // Unescape escape sequences like \n, \t, etc.
                    val unescapedResponseText = unescapeResponse(responseText)
                    Log.d("AutoGLM_Debug", "Response received: $unescapedResponseText")

                    if (unescapedResponseText.startsWith("Error")) {
                        Log.e("AutoGLM_Debug", "API Error: $unescapedResponseText")
                        postError(unescapedResponseText)
                        break
                    }

                    // Parse response parts for display
                    val (thinking, _) = ActionParser.parseResponsePartsToParsedAction(unescapedResponseText)

                    // Extract raw action string for logging and storage
                    val actionStr = ActionParser.extractActionString(unescapedResponseText)

                    Log.i("AutoGLM_Log", "\n==================================================")
                    Log.i("AutoGLM_Log", "💭 思考过程:")
                    Log.i("AutoGLM_Log", thinking)
                    Log.i("AutoGLM_Log", "🎯 执行动作:")
                    Log.i("AutoGLM_Log", actionStr)
                    Log.i("AutoGLM_Log", "==================================================")

                    // Add Assistant response to history
                    apiHistory.add(Message("assistant", buildAssistantContent(thinking, actionStr)))

                    // Save screenshot if available
                    val screenshotPath = if (screenshot != null) {
                        imageStorage.saveImage(screenshot)
                    } else {
                        null
                    }
                    Log.d("AutoGLM_Debug", "Saved screenshot to $screenshotPath")
                    
                    // Save assistant message to database with screenshot


                    // If DEBUG_MODE, stop here after one round
                    if (DEBUG_MODE) {
                        Log.d("AutoGLM_Debug", "DEBUG_MODE enabled, stopping after one round")
                        break
                    }

                    // 4. Parse Action from the extracted action string (not the full response)
                    val action = ActionParser.parseAction(actionStr, screenWidth, screenHeight)

                    // Update Floating Window Status with friendly description
                    service?.updateFloatingStatus(getActionDescription(action),
                        AssistantState.Processing(getActionDescription(action)))

                    // 5. Execute Action
                    val executor = actionExecutor
                    if (executor == null) {
                        postError(application.getString(R.string.error_executor_null))
                        break
                    }

                    // ensureActive() will throw CancellationException if job was cancelled
                    ensureActive()

                    val success = executor.execute(action)

                    if (action is Action.Finish) {
                        isFinished = true
                        service?.updateFloatingStatus(
                            application.getString(R.string.action_finish),
                            AssistantState.Success(application.getString(R.string.action_finish)))

                        // Mark task as completed in FloatingWindowController
                        val floatingWindow = AutoGLMService.getInstance()?.floatingWindowController
                        floatingWindow?.markTaskCompleted()

                        updateTaskState(TaskEndState.COMPLETED, step)
                        break
                    }

                    if (!success) {
                        apiHistory.add(Message("user", application.getString(R.string.error_last_action_failed)))
                    }

                    removeImagesFromHistory()

                    // delay() is cancellable - will respond to job cancellation
                    delay(2000)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Task was cancelled by user - this is expected behavior
                // DO NOT show as error - clear any error state
                Log.d("ChatViewModel", "Task was cancelled by user")
                // Note: Do NOT call updateFloatingStatus() here as it creates a race condition
                // where isTaskRunning stays true while status shows "stopped", preventing
                // the window from hiding when app resumes. The window dismissal is handled
                // by the FloatingWindowContent.kt stop button onClick handler.
                updateTaskState(TaskEndState.USER_STOPPED, step)
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("AutoGLM_Debug", "Exception in sendMessage loop: ${e.message}", e)
                postError(application.getString(R.string.error_runtime_exception, e.message))
            } finally {

            }

            if (!isFinished && isActive) {
                if (!DEBUG_MODE) {
                    if (step >= maxSteps) {
                        service?.updateFloatingStatus(
                            application.getString(R.string.error_task_terminated_max_steps),
                            AssistantState.Error(application.getString(R.string.error_task_terminated_max_steps)))

                        // Mark task as completed in FloatingWindowController
                        val floatingWindow = AutoGLMService.getInstance()?.floatingWindowController
                        floatingWindow?.markTaskCompleted()

                        updateTaskState(TaskEndState.MAX_STEPS_REACHED, step)
                    }
                }
            }
        }
    }
}