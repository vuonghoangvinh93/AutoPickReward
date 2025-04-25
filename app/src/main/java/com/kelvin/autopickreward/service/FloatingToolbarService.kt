package com.kelvin.autopickreward.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.kelvin.autopickreward.MainActivity
import com.kelvin.autopickreward.R
import com.kelvin.autopickreward.model.AppSettings
import com.kelvin.autopickreward.model.ClickPoint
import kotlinx.coroutines.Job
import kotlin.math.log

/**
 * Service that shows a floating toolbar over other apps
 */
class FloatingToolbarService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "FloatingToolbarChannel"
        private var isRunning = false
        private var appSettings: AppSettings? = null

        fun updateSettings(settings: AppSettings) {
            appSettings = settings
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var crosshairView: View
    private lateinit var blueDotView: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var crosshairParams: WindowManager.LayoutParams
    private lateinit var blueDotParams: WindowManager.LayoutParams
    private var isCrosshairVisible = false
    private var isBlueDotVisible = false
    private var isServiceRunning = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var crosshairX = 0
    private var crosshairY = 0
    private var radiusSearchArea = 130

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        setupFloatingWindow()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Floating Toolbar Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Auto Pick Reward")
            .setContentText("Service is running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun setupFloatingWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Setup floating toolbar
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            // Use TYPE_APPLICATION_OVERLAY if possible, otherwise fall back to an alternative
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.START or Gravity.TOP
        params.x = 0
        params.y = 100

        // Inflate the floating view layout
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_toolbar, null)

        // Setup crosshair view
        crosshairParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        crosshairParams.gravity = Gravity.START or Gravity.TOP
        crosshairParams.x = 0
        crosshairParams.y = 0

        crosshairView = LayoutInflater.from(this).inflate(R.layout.crosshair, null)

        // Setup blue dot view
        blueDotParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        blueDotParams.gravity = Gravity.START or Gravity.TOP
        blueDotParams.x = 0
        blueDotParams.y = 0

        blueDotView = LayoutInflater.from(this).inflate(R.layout.blue_dot, null)

        // Add view to window manager
        windowManager.addView(floatingView, params)

        setupUI()
    }

    private fun setupUI() {
        // Get UI elements
        val btnStartStop = floatingView.findViewById<ImageButton>(R.id.btn_start_stop)
        val btnCreatePoint = floatingView.findViewById<ImageButton>(R.id.btn_create_point)
        val btnRemovePoint = floatingView.findViewById<ImageButton>(R.id.btn_remove_point)
        val btnHidePoint = floatingView.findViewById<ImageButton>(R.id.btn_hide_point)
        val btnSetPosition = floatingView.findViewById<ImageButton>(R.id.btn_set_position)
        val btnStopApp = floatingView.findViewById<ImageButton>(R.id.btn_stop_app)
        val seekBarRadius = floatingView.findViewById<SeekBar>(R.id.seek_bar_radius)
        val tvRadius = floatingView.findViewById<TextView>(R.id.tv_radius_value)

        // Set initial values
        tvRadius.text = radiusSearchArea.toString()
        seekBarRadius.progress = radiusSearchArea
        updatePlayPauseButton(isServiceRunning)

        // Setup drag listener for the toolbar
        setupDragListener()

        // Setup click listeners
        btnStartStop.setOnClickListener {
            Log.d("FloatingToolbarService", "Start/Stop button clicked, current state: ${if (isServiceRunning) "Running" else "Stopped"}")
            
            if (isServiceRunning) {
                // Update UI state first
                isServiceRunning = false
                updatePlayPauseButton(false)
                
                // Then stop the service
                stopService()
                Log.d("FloatingToolbarService", "Stop service called, UI updated")
            } else {
                // Update UI state first
                isServiceRunning = true
                updatePlayPauseButton(true)
                
                // Then start the service
                startService()
                Log.d("FloatingToolbarService", "Start service called, UI updated")
            }
        }

        // Stop running app button
        btnStopApp.setOnClickListener {
            Log.d("FloatingToolbarService", "Stop app button clicked")
            
            // Get the current foreground app package name
            val serviceInstance = AutoScrollClickService.getInstance()
            if (serviceInstance != null) {
                try {
                    val root = serviceInstance.rootInActiveWindow
                    if (root != null) {
                        val foregroundPackage = root.packageName?.toString()
                        if (foregroundPackage != null && foregroundPackage != "com.kelvin.autopickreward") {
                            Log.d("FloatingToolbarService", "Attempting to close foreground app: $foregroundPackage")
                            
                            // First temporarily disable our event listener to prevent interference
                            val wasRunning = isServiceRunning
                            if (wasRunning) {
                                isServiceRunning = false
                                updatePlayPauseButton(false)
                            }
                            
                            // Show toast notification
                            showStatusNotification("Closing app: $foregroundPackage")
                            
                            // Use the Accessibility Service to close the app
                            // Go to recents and perform the swipe gesture
                            performCloseApp(foregroundPackage)
                        } else {
                            showStatusNotification("Cannot close our own app!")
                            Log.d("FloatingToolbarService", "Cannot close own app or null package")
                        }
                        root.recycle()
                    } else {
                        showStatusNotification("No foreground app found")
                        Log.d("FloatingToolbarService", "No root window found")
                    }
                } catch (e: Exception) {
                    Log.e("FloatingToolbarService", "Error closing app: ${e.message}")
                    showStatusNotification("Error closing app")
                }
            } else {
                showStatusNotification("Accessibility service not available")
                Log.d("FloatingToolbarService", "Service instance not available")
            }
        }

        btnCreatePoint.setOnClickListener {
            if (!isCrosshairVisible) {
                // Center the crosshair on screen initially
                val displayMetrics = DisplayMetrics()
                
                // Get display metrics using the currently recommended approach
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // For Android 11+, use the WindowManager's current metrics
                    val windowMetrics = windowManager.currentWindowMetrics
                    val bounds = windowMetrics.bounds
                    crosshairX = bounds.width() / 2
                    crosshairY = bounds.height() / 2
                } else {
                    // For older versions, use the deprecated approach
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.getMetrics(displayMetrics)
                    crosshairX = displayMetrics.widthPixels / 2
                    crosshairY = displayMetrics.heightPixels / 2
                }
                
                crosshairParams.x = crosshairX
                crosshairParams.y = crosshairY
                windowManager.addView(crosshairView, crosshairParams)
                isCrosshairVisible = true
                
                // Setup drag listener for the crosshair
                setupCrosshairDragListener()
            }
        }

        btnRemovePoint.setOnClickListener {
            if (isCrosshairVisible) {
                windowManager.removeView(crosshairView)
                isCrosshairVisible = false
                
                // Also remove the blue dot if it's visible
                if (isBlueDotVisible) {
                    windowManager.removeView(blueDotView)
                    isBlueDotVisible = false
                }
            }
        }

        btnHidePoint.setOnClickListener {
            if (isCrosshairVisible) {
                crosshairView.visibility = if (crosshairView.visibility == View.VISIBLE) {
                    View.INVISIBLE
                } else {
                    View.VISIBLE
                }
            }
        }
        
        btnSetPosition.setOnClickListener {
            if (isCrosshairVisible) {
                // Get the exact center coordinates of the crosshair
                val crosshairCenterX: Float
                val crosshairCenterY: Float
                
                // Get the size of the crosshair view
                val crosshairView = this.crosshairView
                val crosshairWidth = crosshairView.width
                val crosshairHeight = crosshairView.height
                
                 //Calculate the exact center position
                crosshairCenterX = (crosshairParams.x + crosshairWidth / 2).toFloat()
                crosshairCenterY = (crosshairParams.y + crosshairHeight / 2).toFloat()

//                crosshairCenterX = (crosshairParams.x + crosshairWidth * 0.5).toFloat()
//                crosshairCenterY = (crosshairParams.y + crosshairHeight * ).toFloat()
                Log.d("CrosshairSize", "crosshairWidth: $crosshairWidth, crosshairHeight $crosshairHeight")
                // Create a new ClickPoint with the exact center coordinates
                val exactClickPoint = ClickPoint(crosshairCenterX, crosshairCenterY, true)
                
                // Update the app settings with the exact position
                appSettings = appSettings?.copy(
                    clickPoint = exactClickPoint
                )
                
                // Show the blue dot indicator at the exact position
                showBlueDotAtPosition(crosshairCenterX.toInt(), crosshairCenterY.toInt())
                
                // Show confirmation to the user
                showStatusNotification("Exact position set at (${crosshairCenterX.toInt()}, ${crosshairCenterY.toInt()})")
                
                Log.d("FloatingToolbarService", "Exact position set at: $crosshairCenterX, $crosshairCenterY")
            } else {
                // If crosshair is not visible, show an error message
                showStatusNotification("Please create a crosshair first")
                Log.d("FloatingToolbarService", "Cannot set position: crosshair not visible")
            }
        }

        seekBarRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                radiusSearchArea = progress
                tvRadius.text = progress.toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun setupDragListener() {
        floatingView.findViewById<View>(R.id.root_container).setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    return@setOnTouchListener true
                }
            }
            false
        }
    }

    private fun setupCrosshairDragListener() {
        crosshairView.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = crosshairParams.x
                    initialY = crosshairParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_MOVE -> {
                    crosshairParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    crosshairParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    crosshairX = crosshairParams.x
                    crosshairY = crosshairParams.y
                    windowManager.updateViewLayout(crosshairView, crosshairParams)
                    return@setOnTouchListener true
                }
            }
            false
        }
    }

    private fun startService() {
        // Check if service was previously running and properly cleaned up
        if (isServiceRunning) {
            // First stop any existing processes to ensure a clean start
            stopServiceInternal(showToast = false)
        }
        
        // Get current app settings
        val currentSettings = appSettings
        
        // Determine which click point to use
        val clickPointToUse = if (isCrosshairVisible) {
            // Use the saved exact position if it exists, otherwise use current crosshair position
            currentSettings?.clickPoint ?: ClickPoint(crosshairX.toFloat(), crosshairY.toFloat(), true)
        } else {
            null
        }
        
        val settings = currentSettings?.copy(
            radiusSearchArea = radiusSearchArea,
            clickPoint = clickPointToUse
        ) ?: return

        // Update the UI state first
        isServiceRunning = true
        
        // First, update the service settings
        AutoScrollClickService.updateSettings(settings)
        
        // Then start the service
        AutoScrollClickService.startService()
        
        // Add a backup direct call after a short delay to ensure the service starts
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                // Get the service instance directly
                val serviceInstance = AutoScrollClickService.getInstance()
                
                if (serviceInstance != null) {
                    // Start the process loop directly
                    serviceInstance.startProcessLoop()
                    Log.d("FloatingToolbarService", "Service started via direct instance")
                    
                    // Show a success notification
                    val clickPointInfo = if (clickPointToUse != null) {
                        " at position (${clickPointToUse.x.toInt()}, ${clickPointToUse.y.toInt()})"
                    } else {
                        ""
                    }
                    showStatusNotification("Service started successfully$clickPointInfo")
                } else {
                    // Show a warning notification
                    Log.e("FloatingToolbarService", "Service instance not available")
                    showStatusNotification("Please enable Accessibility Service in Settings")
                    
                    // Open accessibility settings if service not found
                    val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e("FloatingToolbarService", "Error starting service: ${e.message}")
                showStatusNotification("Error starting service, check logs")
            }
        }, 500) // Short delay to ensure service is ready
    }

    private fun stopService() {
        Log.d("FloatingToolbarService", "Stop service requested")
        stopServiceInternal(showToast = true)
    }
    
    /**
     * Internal method to stop the service with option to show or suppress toast
     */
    private fun stopServiceInternal(showToast: Boolean) {
        // Forcefully stop and reset service state
        Log.d("FloatingToolbarService", "Stopping AutoScrollClickService")
        AutoScrollClickService.stopService()
        
        // Reset service to initial state
        isServiceRunning = false
        
        // Cancel any pending operations on main thread
        val handler = Handler(Looper.getMainLooper())
        handler.removeCallbacksAndMessages(null)
        
        // Remove blue dot indicator if visible
        if (isBlueDotVisible) {
            try {
                windowManager.removeView(blueDotView)
                isBlueDotVisible = false
            } catch (e: Exception) {
                Log.e("FloatingToolbarService", "Error removing blue dot when stopping: ${e.message}")
            }
        }
        
        // Forcefully interrupt any running processes
        try {
            // Try to get the service instance
            val serviceInstance = AutoScrollClickService.getInstance()
            if (serviceInstance != null) {
                Log.d("FloatingToolbarService", "Found active service instance, ensuring it's stopped")
                // Call stopService directly on the service instance
                AutoScrollClickService.stopService()
            }
        } catch (e: Exception) {
            Log.e("FloatingToolbarService", "Error stopping service instance: ${e.message}")
        }
        
        // Log state and show notification
        Log.d("FloatingToolbarService", "All processes stopped successfully")
        
        // Only show toast if explicitly requested
        if (showToast) {
            handler.post {
                try {
                    showStatusNotification("Service stopped successfully")
                    Log.d("FloatingToolbarService", "Service stop notification shown")
                } catch (e: Exception) {
                    Log.e("FloatingToolbarService", "Error showing notification: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Shows a temporary status notification instead of toast
     */
    private fun showStatusNotification(message: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val statusChannel = NotificationChannel(
                "status_channel",
                "Status Messages",
                NotificationManager.IMPORTANCE_LOW
            )
            statusChannel.setSound(null, null)
            statusChannel.enableLights(false)
            statusChannel.enableVibration(false)
            notificationManager.createNotificationChannel(statusChannel)
        }
        
        val notification = NotificationCompat.Builder(this, "status_channel")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(2, notification)
        
        // Auto-cancel after 2 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            notificationManager.cancel(2)
        }, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isCrosshairVisible) {
            windowManager.removeView(crosshairView)
        }
        if (isBlueDotVisible) {
            windowManager.removeView(blueDotView)
        }
        windowManager.removeView(floatingView)
    }

    // Add a new method to force stop from outside the service if needed
    fun forceStop() {
        Log.d("FloatingToolbarService", "Force stop requested from outside")
        // Update UI elements first
        updatePlayPauseButton(false)
        // Then stop the service
        stopServiceInternal(true)
    }
    
    // Update the play/pause button based on running state
    private fun updatePlayPauseButton(isRunning: Boolean) {
        try {
            val btnStartStop = floatingView.findViewById<ImageButton>(R.id.btn_start_stop)
            btnStartStop.setImageResource(
                if (isRunning) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
            Log.d("FloatingToolbarService", "Updated play/pause button to ${if (isRunning) "pause" else "play"}")
        } catch (e: Exception) {
            Log.e("FloatingToolbarService", "Error updating button: ${e.message}")
        }
    }

    /**
     * Shows a blue dot indicator at the specified position
     */
    private fun showBlueDotAtPosition(x: Int, y: Int) {
        try {
            // Calculate position to center the dot exactly on the coordinates
            val blueDotWidth = 4 // Width of the dot in dp
            val blueDotHeight = 4 // Height of the dot in dp
            
            // Center the dot on the coordinates
            blueDotParams.x = x - blueDotWidth / 2
            blueDotParams.y = y - blueDotHeight / 2
            
            // Remove existing dot if it's already visible
            if (isBlueDotVisible) {
                try {
                    windowManager.removeView(blueDotView)
                    isBlueDotVisible = false
                } catch (e: Exception) {
                    Log.e("FloatingToolbarService", "Error removing existing blue dot: ${e.message}")
                }
            }
            
            // Add the blue dot to the window
            windowManager.addView(blueDotView, blueDotParams)
            isBlueDotVisible = true
            
            Log.d("FloatingToolbarService", "Blue dot added at position: $x, $y")
        } catch (e: Exception) {
            Log.e("FloatingToolbarService", "Error showing blue dot: ${e.message}")
        }
    }

    /**
     * Uses the accessibility service to close the specified app
     */
    private fun performCloseApp(packageName: String) {
        try {
            val serviceInstance = AutoScrollClickService.getInstance()
            if (serviceInstance == null) {
                Log.e("FloatingToolbarService", "Service instance not available to close app")
                showStatusNotification("Service not available")
                return
            }
            
            // Use global action to open recents
            serviceInstance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            
            // Wait for recents screen to appear
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val root = serviceInstance.rootInActiveWindow ?: return@postDelayed
                    
                    // Try to find the app by package name in recents
                    val appNodes = root.findAccessibilityNodeInfosByText(packageName)
                    
                    // If we found the app card in recents
                    if (appNodes.isNotEmpty()) {
                        val targetNode = appNodes[0]
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            // For API 24+, use gesture API to swipe
                            val rect = Rect()
                            targetNode.getBoundsInScreen(rect)
                            
                            // Create swipe gesture from the center of the app card upward
                            val startX = rect.centerX().toFloat()
                            val startY = rect.centerY().toFloat()
                            val endY = 0f // Swipe to top
                            
                            val swipePath = Path()
                            swipePath.moveTo(startX, startY)
                            swipePath.lineTo(startX, endY)
                            
                            val gestureBuilder = GestureDescription.Builder()
                            gestureBuilder.addStroke(GestureDescription.StrokeDescription(swipePath, 0, 300))
                            
                            // Perform the swipe
                            serviceInstance.dispatchGesture(gestureBuilder.build(), object : AccessibilityService.GestureResultCallback() {
                                override fun onCompleted(gestureDescription: GestureDescription) {
                                    super.onCompleted(gestureDescription)
                                    Log.d("FloatingToolbarService", "Swipe to close app completed")
                                    
                                    // Go back to home after closing
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        serviceInstance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                                    }, 500)
                                }
                            }, null)
                        } else {
                            // For older API levels, try using ACTION_DISMISS on the node
                            // or fallback to simply going back to home
                            val dismissed = targetNode.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
                            Log.d("FloatingToolbarService", "Attempted to dismiss app card: $dismissed")
                            
                            // Go back to home after attempting to dismiss
                            Handler(Looper.getMainLooper()).postDelayed({
                                serviceInstance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                            }, 500)
                        }
                        
                        // Recycle node after use
                        targetNode.recycle()
                    } else {
                        // If we can't find the app, go back to home
                        Log.d("FloatingToolbarService", "Could not find app in recents")
                        serviceInstance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    }
                    
                    root.recycle()
                } catch (e: Exception) {
                    Log.e("FloatingToolbarService", "Error during app close gesture: ${e.message}")
                }
            }, 1000)
            
        } catch (e: Exception) {
            Log.e("FloatingToolbarService", "Error closing app: ${e.message}")
        }
    }
} 