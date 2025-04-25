package com.kelvin.autopickreward.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.kelvin.autopickreward.model.AppSettings
import com.kelvin.autopickreward.model.ClickPoint
import com.kelvin.autopickreward.model.ScrollDirection
import com.kelvin.autopickreward.util.OCRHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * Data class to hold text and its screen position
 */
data class TextWithPosition(
    val text: String,
    val blocks: List<TextBlock> = emptyList()
)

/**
 * Data class to represent a text block with its screen position
 */
data class TextBlock(
    val text: String,
    val bounds: Rect
) {
    val centerX: Int
        get() = bounds.centerX()
    
    val centerY: Int
        get() = bounds.centerY()
}

/**
 * Accessibility service that performs auto-scrolling and auto-clicking based on OCR text recognition
 */
class AutoScrollClickService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoScrollClickService"
        var isRunning = false
            private set
            
        // Static reference to the service instance
        private var INSTANCE: AutoScrollClickService? = null
        
        // Get instance safely
        fun getInstance(): AutoScrollClickService? {
            return INSTANCE
        }

        private var settings: AppSettings? = null
        private var job: Job? = null
        private var clickRetryCount = 0
        private var mainHandler = Handler(Looper.getMainLooper())

        fun updateSettings(newSettings: AppSettings) {
            settings = newSettings
        }

        fun startService() {
            Log.d(TAG, "startService called, setting isRunning to true")
            isRunning = true
            
            // Try to start process loop on the instance if available
            INSTANCE?.startProcessLoop()
        }

        fun stopService() {
            Log.d(TAG, "stopService called, setting isRunning to false and canceling job")
            isRunning = false
            
            // Use a safer approach to cancel the job
            try {
                if (job?.isActive == true) {
                    // First cancel any children coroutines
                    Log.d(TAG, "Cancelling job children")
                    job?.cancelChildren()
                    
                    // Then cancel the job itself
                    Log.d(TAG, "Cancelling main job")
                    job?.cancel()
                    
                    // Wait a short time for cancellation to complete
                    mainHandler.postDelayed({
                        job = null
                        Log.d(TAG, "Job nullified after cancellation")
                    }, 500L)
                } else {
                    job = null
                    Log.d(TAG, "Job was already inactive or null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error canceling job: ${e.message}")
                e.printStackTrace()
                job = null
            }
            
            // Also try to cancel using the instance directly
            val instance = INSTANCE
            if (instance != null) {
                Log.d(TAG, "Found service instance, attempting to interrupt its operations")
                // Use a Handler to run on the main thread
                mainHandler.post {
                    try {
                        // Clear any pending tasks
                        mainHandler.removeCallbacksAndMessages(null)
                        Log.d(TAG, "Removed pending handler tasks")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error clearing handler: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var ocrHelper: OCRHelper
    private var isServiceConnected = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Service connected")
        isServiceConnected = true
        
        // Initialize OCRHelper
        ocrHelper = OCRHelper()
        
        setupOverlay()
    }

    /**
     * Sets up any overlay components needed by the service.
     * In the current implementation, this is a placeholder as the actual UI
     * is managed by FloatingToolbarService.
     */
    private fun setupOverlay() {
        Log.d(TAG, "Setting up service instance reference")
        // Store this instance as the static reference
        INSTANCE = this
    }

    /**
     * Removes any overlay view components.
     * In the current implementation, this is a placeholder as the actual UI
     * is managed by FloatingToolbarService.
     */
    private fun removeOverlayView() {
        Log.d(TAG, "Cleaning up service instance reference")
        // Clear the static reference to this instance
        if (INSTANCE === this) {
            INSTANCE = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Not handling events directly
    }

    override fun onInterrupt() {
        stopService()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
        isServiceConnected = false
        
        // Cleanup OCRHelper resources
        if (::ocrHelper.isInitialized) {
            ocrHelper.close()
        }
        
        stopService()
        removeOverlayView()
    }

    /**
     * Starts the main process loop for auto scroll and click
     */
    fun startProcessLoop() {
        if (job != null && job?.isActive == true) {
            // Already running, cancel previous job first
            Log.d(TAG, "Step 0: Canceling existing job before starting new one")
            job?.cancel()
            job = null
        }

        Log.d(TAG, "Step 0: Starting process loop with settings: ${settings?.toString()}")
        
        // Run diagnostics at startup
        val diagnosticsResult = runDiagnostics()
        if (!diagnosticsResult) {
            Log.w(TAG, "Diagnostics reported issues, but continuing anyway. Some features may not work.")
        }
        
        job = serviceScope.launch {
            Log.d(TAG, "Process loop launched in coroutine scope")
            var cycleCount = 0
            var diagnosticCycleCheck = 0
            
            try {
                while (isRunning && coroutineContext[Job] != null) {
                    try {
                        // Check if the coroutine has been canceled
                        if (coroutineContext[Job]?.isActive == false) {
                            Log.d(TAG, "Coroutine is no longer active, breaking loop")
                            break
                        }
                        
                        cycleCount++
                        diagnosticCycleCheck++
                        
                        // Run diagnostics every 10 cycles
                        if (diagnosticCycleCheck >= 10) {
                            Log.d(TAG, "Running periodic diagnostics check")
                            runDiagnostics()
                            diagnosticCycleCheck = 0
                        }
                        
                        Log.d(TAG, "===== Starting cycle #$cycleCount =====")
                        val currentSettings = settings
                        if (currentSettings == null) {
                            Log.e(TAG, "Cycle #$cycleCount: Settings are null, skipping cycle")
                            delay(1000L)
                            continue
                        }

                        // Step 1: Scroll the screen
                        Log.d(TAG, "Step 1: Scrolling the screen in direction ${currentSettings.directionForScroll}")
                        performScroll(currentSettings.directionForScroll)

                        // Step 2: Wait for screen to load
                        Log.d(TAG, "Step 2: Waiting ${currentSettings.delayForCheckScroll} seconds for screen to load")
                        delay(currentSettings.delayForCheckScroll * 1000L)

                        // Step 3-7: Capture screen, crop frame, recognize text, check conditions
                        val clickPoint = currentSettings.clickPoint
                        if (clickPoint == null) {
                            Log.e(TAG, "Step 3: No click point defined, skipping remaining steps")
                            continue
                        }
                        Log.d(TAG, "Step 3: Using click point at (${clickPoint.x}, ${clickPoint.y}) with radius ${currentSettings.radiusSearchArea}")
                        
                        Log.d(TAG, "Step 4: Capturing screen and cropping frame")
                        val recognizedText = captureAndRecognizeText(clickPoint, currentSettings.radiusSearchArea)
                        
                        Log.d(TAG, "Step 5: Text recognized: ${recognizedText.take(100)}${if (recognizedText.length > 100) "..." else ""}")

                        // If condition for scroll is met, continue to check for click condition
                        Log.d(TAG, "Step 6: Checking if text contains scroll condition: '${currentSettings.conditionForScroll}'")
                        if (recognizedText.contains(currentSettings.conditionForScroll)) {
                            Log.d(TAG, "Step 6: Scroll condition MET ✓")
                            Log.d(TAG, "Step 7: Checking click condition")
                            checkClickCondition(currentSettings, recognizedText, clickPoint)
                            delay(currentSettings.delayForCheckClick * 1000L)
                        } else {
                            Log.d(TAG, "Step 6: Scroll condition NOT met ✗, continuing to next cycle")
                        }
                        
                        Log.d(TAG, "===== Completed cycle #$cycleCount =====")
                    } catch (e: CancellationException) {
                        // This is the expected exception when a coroutine is cancelled
                        Log.d(TAG, "Process loop was cancelled, exiting gracefully")
                        break
                    } catch (e: Exception) {
                        // For other exceptions, log but continue the loop
                        Log.e(TAG, "Error in cycle #$cycleCount: ${e.message}")
                        e.printStackTrace()
                        delay(1000L) // Brief pause before continuing
                    }
                }
            } catch (e: CancellationException) {
                // This is the expected exception when a coroutine is cancelled
                Log.d(TAG, "Process loop was cancelled, exiting gracefully")
            } catch (e: Exception) {
                Log.e(TAG, "Fatal error in process loop: ${e.message}")
                e.printStackTrace()
            } finally {
                Log.d(TAG, "Process loop has ended")
            }
        }
    }

    /**
     * Checks if the click condition is met and performs click if it is
     */
    private suspend fun checkClickCondition(
        settings: AppSettings,
        initialText: String, 
        clickPoint: ClickPoint
    ) {
        try {
            Log.d(TAG, "Step 7.1: Checking if text contains click condition: '${settings.conditionForClick}'")
            if (initialText.contains(settings.conditionForClick)) {
                Log.d(TAG, "Step 7.2: Click condition MET on first check ✓, performing click")
                
                // Try to get the exact position from OCR results
                val result = captureAndRecognizeTextWithPosition(clickPoint, settings.radiusSearchArea)
                val matchedPosition = findTextPositionOnScreen(result, settings.conditionForClick)
                
                // Use matched position for click if available, otherwise fall back to predefined click point
                if (matchedPosition != null) {
                    Log.d(TAG, "Using exact matched text position for click: x=${matchedPosition.first}, y=${matchedPosition.second}")
                    performClick(matchedPosition.first.toFloat(), matchedPosition.second.toFloat())
                } else {
                    Log.d(TAG, "No exact text position found, using predefined click point: x=${clickPoint.x}, y=${clickPoint.y}")
                    performClick(clickPoint.x.toFloat(), clickPoint.y.toFloat())
                }
                
                return
            }

            // Set timeout
            val startTime = System.currentTimeMillis()
            val timeoutMillis = settings.timeoutForCheckClick * 1000L
            Log.d(TAG, "Step 7.2: Click condition NOT met on first check ✗, starting retry loop with timeout ${settings.timeoutForCheckClick}s")

            var retryCount = 0
            // Keep checking until timeout
            while (isRunning && coroutineContext[Job] != null && System.currentTimeMillis() - startTime < timeoutMillis) {
                retryCount++
                Log.d(TAG, "Step 7.3: Retry #$retryCount - Waiting ${settings.delayForCheckClick}s before checking again")
                delay(settings.delayForCheckClick * 1000L)
                
                // Check for cancellation after delay
                if (coroutineContext[Job]?.isActive == false) {
                    Log.d(TAG, "Coroutine cancelled during click check retry delay")
                    return
                }
                
                Log.d(TAG, "Step 7.4: Retry #$retryCount - Capturing screen again")
                val result = captureAndRecognizeTextWithPosition(clickPoint, settings.radiusSearchArea)
                
                Log.d(TAG, "Step 7.5: Retry #$retryCount - Checking for click condition")
                if (result.text.contains(settings.conditionForClick)) {
                    Log.d(TAG, "Step 7.6: Click condition MET on retry #$retryCount ✓, performing click")
                    
                    // Get the position of the matched text
                    val matchedPosition = findTextPositionOnScreen(result, settings.conditionForClick)
                    
                    // Use matched position for click if available, otherwise fall back to predefined click point
                    if (matchedPosition != null) {
                        Log.d(TAG, "Using exact matched text position for click: x=${matchedPosition.first}, y=${matchedPosition.second}")
                        performClick(matchedPosition.first.toFloat(), matchedPosition.second.toFloat())
                    } else {
                        Log.d(TAG, "No exact text position found, using predefined click point: x=${clickPoint.x}, y=${clickPoint.y}")
                        performClick(clickPoint.x.toFloat(), clickPoint.y.toFloat())
                    }
                    
                    return
                } else {
                    Log.d(TAG, "Step 7.6: Click condition NOT met on retry #$retryCount ✗")
                }
                
                // Check if we've hit timeout
                val elapsedTime = System.currentTimeMillis() - startTime
                val remainingTime = timeoutMillis - elapsedTime
                Log.d(TAG, "Step 7.7: Retry #$retryCount - Elapsed time: ${elapsedTime/1000}s, Remaining time: ${remainingTime/1000}s")
            }
            
            Log.d(TAG, "Step 7.8: Timeout reached after $retryCount retries, click condition was never met ✗")
        } catch (e: CancellationException) {
            Log.d(TAG, "Click condition check was cancelled")
            throw e // Propagate cancellation
        } catch (e: Exception) {
            Log.e(TAG, "Error during click condition check: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Helper method to find the position of text on screen
     * @return Pair of (x,y) coordinates if found, null otherwise
     */
    private fun findTextPositionOnScreen(result: TextWithPosition, targetText: String): Pair<Int, Int>? {
        try {
            // Find the text block that contains the target text
            val matchingBlock = result.blocks.find { it.text.contains(targetText) }
            
            if (matchingBlock != null) {
                // Get center coordinates of the matching text block
                val centerX = matchingBlock.centerX
                val centerY = matchingBlock.centerY
                
                // Log the position
                Log.d(TAG, "Exact position of text '$targetText' on screen: x=$centerX, y=$centerY")
                
                // Return the coordinates
                return Pair(centerX, centerY)
            } else {
                // Try to approximate position from string indices
                val startIndex = result.text.indexOf(targetText)
                if (startIndex >= 0) {
                    // If we found the text but don't have exact coordinates, 
                    // we can at least indicate which block might contain it
                    Log.d(TAG, "Text '$targetText' found at character position $startIndex, but exact screen coordinates unavailable")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding text position: ${e.message}")
        }
        
        return null
    }

    /**
     * Captures screen and returns text with position information
     */
    private fun captureAndRecognizeTextWithPosition(clickPoint: ClickPoint, radius: Int): TextWithPosition {
        Log.d(TAG, "Starting text recognition with position info in area around (${clickPoint.x}, ${clickPoint.y}) with radius $radius")
        
        // First check the basic parameters
        if (radius <= 0) {
            Log.e(TAG, "Invalid radius: $radius. Radius must be positive.")
            return TextWithPosition("")
        }
        
        val screenWidth = getScreenWidth()
        val screenHeight = getScreenHeight()
        
        if (clickPoint.x <= 0 || clickPoint.y <= 0 || clickPoint.x > screenWidth || clickPoint.y > screenHeight) {
            Log.e(TAG, "Click point (${clickPoint.x}, ${clickPoint.y}) is outside screen bounds (${screenWidth}x${screenHeight})")
            return TextWithPosition("")
        }
        
        val root = rootInActiveWindow
        if (root == null) {
            Log.e(TAG, "Failed to get rootInActiveWindow, cannot capture screen")
            return TextWithPosition("")
        }
        
        try {
            // Define the area to capture with boundary checking
            val rect = Rect(
                max(0, clickPoint.x.toInt() - radius),
                max(0, clickPoint.y.toInt() - radius),
                min(screenWidth, clickPoint.x.toInt() + radius),
                min(screenHeight, clickPoint.y.toInt() + radius)
            )
            
            // Create a list to store text blocks with their screen positions
            val textBlocks = mutableListOf<TextBlock>()
            
            // Collect text nodes from accessibility tree with their screen positions
            collectTextNodesWithPosition(root, rect, textBlocks)
            
            // Build the combined text string
            val combinedText = buildString {
                for (block in textBlocks) {
                    append(block.text)
                    append("\n")
                }
                
                // Also get content descriptions
                val contentDescriptions = getAllContentDescriptions(root, rect)
                if (contentDescriptions.isNotEmpty()) {
                    append(contentDescriptions)
                }
            }
            
            // Log for debugging
            Log.d(TAG, "OCR recognition completed with ${textBlocks.size} positioned text blocks")
            if (textBlocks.isEmpty()) {
                Log.w(TAG, "No positioned text blocks found - screen coordinates will be unavailable")
            }
            
            return TextWithPosition(combinedText, textBlocks)
        } catch (e: Exception) {
            Log.e(TAG, "Error during text recognition with position: ${e.message}")
            e.printStackTrace()
            return TextWithPosition("")
        } finally {
            // Safely release resources
            try {
                Log.d(TAG, "Releasing rootInActiveWindow resources")
                // The recycle() method is deprecated but still the recommended way to
                // release AccessibilityNodeInfo resources
                @Suppress("DEPRECATION")
                root.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing root window", e)
            }
        }
    }
    
    /**
     * Collects text nodes with their screen positions from accessibility tree
     */
    private fun collectTextNodesWithPosition(node: AccessibilityNodeInfo?, area: Rect, result: MutableList<TextBlock>) {
        if (node == null) return
        
        try {
            // Get node bounds
            val nodeBounds = Rect()
            node.getBoundsInScreen(nodeBounds)
            
            // Check if node intersects with our area of interest
            if (Rect.intersects(nodeBounds, area)) {
                // Add text if available
                if (!node.text.isNullOrEmpty()) {
                    // Create a text block with position
                    result.add(TextBlock(node.text.toString(), Rect(nodeBounds)))
                }
                
                // Add content description if available
                if (!node.contentDescription.isNullOrEmpty()) {
                    result.add(TextBlock(node.contentDescription.toString(), Rect(nodeBounds)))
                }
                
                // Check child nodes
                for (i in 0 until node.childCount) {
                    collectTextNodesWithPosition(node.getChild(i), area, result)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing node position: ${e.message}")
        }
    }

    /**
     * Captures the screen and recognizes text within the specified area
     */
    private fun captureAndRecognizeText(clickPoint: ClickPoint, radius: Int): String {
        val result = captureAndRecognizeTextWithPosition(clickPoint, radius)
        return result.text
    }

    /**
     * Performs a scroll gesture in the specified direction
     */
    private fun performScroll(direction: ScrollDirection) {
        try {
            Log.d(TAG, "Performing scroll gesture in direction: $direction")
            val displayMetrics = resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels
            val screenWidth = displayMetrics.widthPixels

            val path = Path()
            val startY: Float
            val endY: Float

            if (direction == ScrollDirection.DOWN) {
                startY = screenHeight * 0.9f
                endY = screenHeight * 0.2f
            } else {
                startY = screenHeight * 0.2f
                endY = screenHeight * 0.8f
            }

            path.moveTo(screenWidth / 2f, startY)
            path.lineTo(screenWidth / 2f, endY)

            val gestureBuilder = GestureDescription.Builder()
            val gesture = gestureBuilder
                .addStroke(GestureDescription.StrokeDescription(path, 10, 500))
                .build()

            // Use a callback to detect success or failure
            val scrollSuccess = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    Log.d(TAG, "Scroll gesture completed successfully")
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    Log.d(TAG, "Scroll gesture was cancelled")
                }
            }, null)

            if (scrollSuccess) {
                Log.d(TAG, "Scroll gesture dispatched from y=${startY} to y=${endY}")
            } else {
                Log.e(TAG, "Failed to dispatch scroll gesture")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error performing scroll: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Performs a click gesture at the specified position
     */
    private fun performClick(x: Float, y: Float) {
        try {
            val path = Path()
            path.moveTo(x, y)

            val gestureBuilder = GestureDescription.Builder()
            val gesture = gestureBuilder
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()

            // Use a callback to detect success or failure
            val scrollSuccess = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    Log.d(TAG, "Click gesture completed successfully")
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    Log.d(TAG, "Click gesture was cancelled")
                }
            }, null)

            if (scrollSuccess) {
                Log.d(TAG, "Click gesture dispatched at [ x=$x , y=$y ]")
            } else {
                Log.e(TAG, "Failed to dispatch Click gesture")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error performing Click: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * Tries to restart the service connection if we encounter persistent permission issues
     */
    private fun tryRestartServiceConnection() {
        try {
            Log.d(TAG, "Attempting to refresh service connection due to persistent click failures")
            
            // Reset OCR helper to clear any stale state
            if (::ocrHelper.isInitialized) {
                ocrHelper.resetRecognizer()
            }
            
            // Try to force refresh accessibility manager state
            // This is a soft restart that can sometimes resolve permission issues
            val isRunningBackup = isRunning
            
            // Simulate a brief service pause
            isRunning = false
            
            // Cancel any active jobs or operations
            job?.cancel()
            job = null
            
            // Ensure clean slate before restarting
            clickRetryCount = 0
            
            // Clear pending tasks
            mainHandler.removeCallbacksAndMessages(null)
            
            // Wait briefly and restore
            mainHandler.postDelayed({
                isRunning = isRunningBackup
                Log.d(TAG, "Service connection refresh completed. isRunning restored to: $isRunning")
                
                // Ensure we have a valid package context
                try {
                    val rootNode = rootInActiveWindow
                    if (rootNode != null) {
                        val packageName = rootNode.packageName
                        Log.d(TAG, "Current package after restart: $packageName")
                        rootNode.recycle()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get root window after restart: ${e.message}")
                }
                
                // Force garbage collection to clean up any resources
                System.gc()
            }, 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "Error trying to restart service connection: ${e.message}")
        }
    }
    
    /**
     * Finds an accessibility node at the specified screen coordinates
     */
    private fun findNodeAtPosition(node: AccessibilityNodeInfo?, x: Float, y: Float): AccessibilityNodeInfo? {
        if (node == null) return null
        
        try {
            // Get node bounds
            val nodeBounds = Rect()
            node.getBoundsInScreen(nodeBounds)
            
            // Check if the point is within this node's bounds
            if (nodeBounds.contains(x.toInt(), y.toInt())) {
                // First, check children (we want the most specific/deepest node)
                for (i in 0 until node.childCount) {
                    val childAtPosition = findNodeAtPosition(node.getChild(i), x, y)
                    if (childAtPosition != null) {
                        return childAtPosition
                    }
                }
                
                // If no child contains the point, return this node
                return node
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding node at position: ${e.message}")
        }
        
        return null
    }

    /**
     * Gets the screen width
     */
    private fun getScreenWidth(): Int {
        return resources.displayMetrics.widthPixels
    }
    
    /**
     * Gets the screen height
     */
    private fun getScreenHeight(): Int {
        return resources.displayMetrics.heightPixels
    }
    
    /**
     * Gets all content descriptions from elements in the specified area
     */
    private fun getAllContentDescriptions(root: AccessibilityNodeInfo, area: Rect): String {
        val result = StringBuilder()
        
        try {
            // Recursively collect content descriptions and text from nodes
            collectTextFromNodes(root, area, result)
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting content descriptions: ${e.message}")
        }
        
        return result.toString()
    }
    
    /**
     * Recursively collects text from accessibility nodes in the specified area
     */
    private fun collectTextFromNodes(node: AccessibilityNodeInfo?, area: Rect, result: StringBuilder) {
        if (node == null) return
        
        try {
            // Get node bounds
            val nodeBounds = Rect()
            node.getBoundsInScreen(nodeBounds)
            
            // Check if node intersects with our area of interest
            if (Rect.intersects(nodeBounds, area)) {
                // Add text if available
                if (!node.text.isNullOrEmpty()) {
                    result.append(node.text).append("\n")
                }
                
                // Add content description if available
                if (!node.contentDescription.isNullOrEmpty()) {
                    result.append(node.contentDescription).append("\n")
                }
                
                // Check child nodes
                for (i in 0 until node.childCount) {
                    collectTextFromNodes(node.getChild(i), area, result)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing node: ${e.message}")
        }
    }

    /**
     * Runs a diagnostic check of accessibility service capabilities
     * Call this method periodically to verify the service is running properly
     */
    private fun runDiagnostics(): Boolean {
        Log.d(TAG, "Running accessibility service diagnostics...")
        var allChecksPassed = true
        
        try {
            // Check 1: Can we get the root window
            val root = rootInActiveWindow
            if (root == null) {
                Log.e(TAG, "✗ FAILED: Cannot access root window - accessibility service may not have proper permissions")
                allChecksPassed = false
            } else {
                Log.d(TAG, "✓ SUCCESS: Root window accessible")
                
                // Check 2: Can we get window content
                val packageName = root.packageName
                if (packageName == null) {
                    Log.e(TAG, "✗ FAILED: Cannot get package name from root window")
                    allChecksPassed = false
                } else {
                    Log.d(TAG, "✓ SUCCESS: Current foreground app is: $packageName")
                }
                
                // Check 3: Are we able to see nodes
                val nodeCount = countNodesRecursively(root)
                if (nodeCount == 0) {
                    Log.e(TAG, "✗ FAILED: No nodes found in the accessibility tree")
                    allChecksPassed = false
                } else {
                    Log.d(TAG, "✓ SUCCESS: Found $nodeCount nodes in accessibility tree")
                }
                
                // Always recycle the root
                root.recycle()
            }
            
            // Check 4: Can we create paths for gestures
            try {
                val testPath = Path()
                testPath.moveTo(100f, 100f)
                testPath.lineTo(110f, 110f)
                
                val testGesture = GestureDescription.Builder()
                    .addStroke(StrokeDescription(testPath, 0, 100))
                    .build()
                
                Log.d(TAG, "✓ SUCCESS: Successfully created gesture path")
            } catch (e: Exception) {
                Log.e(TAG, "✗ FAILED: Cannot create gesture paths: ${e.message}")
                allChecksPassed = false
            }
            
            // Check 5: Service status
            if (!isServiceConnected) {
                Log.e(TAG, "✗ FAILED: Service reports as not connected")
                allChecksPassed = false
            } else {
                Log.d(TAG, "✓ SUCCESS: Service reports as connected")
            }
            
            // Check 6: Settings available
            if (settings == null) {
                Log.e(TAG, "✗ FAILED: No settings available")
                allChecksPassed = false
            } else {
                Log.d(TAG, "✓ SUCCESS: Settings available")
            }
            
            // Summary
            if (allChecksPassed) {
                Log.d(TAG, "✅ All diagnostic checks PASSED. Service should be working properly.")
            } else {
                Log.e(TAG, "❌ Some diagnostic checks FAILED. Service may not work correctly.")
            }
            
            return allChecksPassed
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during diagnostics: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * Counts the total number of nodes in the accessibility hierarchy
     */
    private fun countNodesRecursively(node: AccessibilityNodeInfo?): Int {
        if (node == null) return 0
        
        var count = 1 // Count the node itself
        
        for (i in 0 until node.childCount) {
            count += countNodesRecursively(node.getChild(i))
        }
        
        return count
    }
} 