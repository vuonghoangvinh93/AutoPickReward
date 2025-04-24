package com.kelvin.autopickreward.util

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import android.content.Context
import android.content.pm.PackageManager

/**
 * Helper class for OCR (Optical Character Recognition) using ML Kit
 */
class OCRHelper {
    private val TAG = "OCRHelper"
    private var textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var latestScreenCapture: Bitmap? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hasSecurityError = AtomicBoolean(false)

    /**
     * Recognizes text from a bitmap using ML Kit
     */
    suspend fun recognizeText(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        if (hasSecurityError.get()) {
            // If we've already encountered security errors, skip ML Kit and use the fallback
            Log.w(TAG, "Using fallback OCR due to previous security errors")
            continuation.resume(fallbackTextRecognition(bitmap))
            return@suspendCancellableCoroutine
        }

        val image = InputImage.fromBitmap(bitmap, 0)
        
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val result = buildString {
                    for (block in visionText.textBlocks) {
                        append(block.text)
                        append("\n")
                    }
                }
                continuation.resume(result)
            }
            .addOnFailureListener { e ->
                // Check if this is a security exception from Google Play Services
                if (e is SecurityException || e.message?.contains("SecurityException") == true || 
                    e.message?.contains("Unknown calling package") == true) {
                    
                    Log.e(TAG, "Security exception in ML Kit: ${e.message}")
                    hasSecurityError.set(true)
                    
                    // Use fallback text recognition
                    Log.w(TAG, "Falling back to alternative text recognition")
                    continuation.resume(fallbackTextRecognition(bitmap))
                } else {
                    Log.e(TAG, "Text recognition failed: ${e.message}")
                    continuation.resumeWithException(e)
                }
            }
            .addOnCanceledListener {
                continuation.cancel()
            }
    }

    /**
     * Recognizes text from a specified area of the screen
     */
    fun recognizeText(area: Rect): String {
        try {
            // Get the latest screen bitmap
            val croppedBitmap = getScreenContentBitmap(area)
            
            if (croppedBitmap != null) {
                // If we've already had security errors, use fallback immediately
                if (hasSecurityError.get()) {
                    Log.w(TAG, "Using fallback text recognition due to previous security errors")
                    return fallbackTextRecognition(croppedBitmap)
                }
                
                // Run text recognition on the UI thread to avoid threading issues
                var recognizedText = ""
                val latch = java.util.concurrent.CountDownLatch(1)
                
                mainHandler.post {
                    val image = InputImage.fromBitmap(croppedBitmap, 0)
                    textRecognizer.process(image)
                        .addOnSuccessListener { visionText ->
                            recognizedText = buildString {
                                for (block in visionText.textBlocks) {
                                    append(block.text)
                                    append("\n")
                                }
                            }
                            Log.d(TAG, "Text recognized: $recognizedText")
                            latch.countDown()
                        }
                        .addOnFailureListener { e ->
                            // Check if this is a security exception from Google Play Services
                            if (e is SecurityException || e.message?.contains("SecurityException") == true || 
                                e.message?.contains("Unknown calling package") == true) {
                                
                                Log.e(TAG, "Security exception in ML Kit: ${e.message}")
                                hasSecurityError.set(true)
                                
                                // Use fallback text recognition
                                Log.w(TAG, "Falling back to alternative text recognition")
                                recognizedText = fallbackTextRecognition(croppedBitmap)
                            } else {
                                Log.e(TAG, "Text recognition failed: ${e.message}")
                            }
                            latch.countDown()
                        }
                }
                
                // Wait for the recognition to complete (with timeout)
                try {
                    latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) {
                    Log.e(TAG, "Text recognition timed out: ${e.message}")
                }
                
                return recognizedText
            } else {
                Log.e(TAG, "Failed to get screen content bitmap")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during text recognition: ${e.message}")
            e.printStackTrace()
        }
        
        return ""
    }
    
    /**
     * Gets a bitmap of the screen content within the specified area
     */
    private fun getScreenContentBitmap(area: Rect): Bitmap? {
        try {
            // For a real implementation, this would capture the actual screen
            // using MediaProjection API or AccessibilityNodeInfo
            
            val width = area.width()
            val height = area.height()
            
            if (width <= 0 || height <= 0) {
                Log.e(TAG, "Invalid area dimensions: $width x $height")
                return null
            }
            
            Log.d(TAG, "Creating bitmap with dimensions: $width x $height")
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            
            // Here you would copy the actual screen content to this bitmap
            // For example using PixelCopy API or by drawing the view hierarchy
            
            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error creating screen bitmap: ${e.message}")
            return null
        }
    }

    /**
     * Fallback method for text recognition when ML Kit is unavailable or has security issues
     */
    private fun fallbackTextRecognition(bitmap: Bitmap): String {
        try {
            Log.d(TAG, "Using fallback text recognition")
            
            // This is a simple placeholder that doesn't actually do OCR
            // In a real app, you might:
            // 1. Use a different OCR library
            // 2. Make a network call to an OCR service API
            // 3. Use the Android TextClassifier APIs which are part of the platform
            
            // For demo, we'll just return some fixed text plus information about the bitmap
            return buildString {
                append("Fallback text recognition active\n")
                append("Bitmap dimensions: ${bitmap.width}x${bitmap.height}\n")
                
                // Analyze some basic properties of the bitmap
                val hasContent = !isBitmapEmpty(bitmap)
                append("Has visible content: $hasContent\n")
                
                // Simulate finding some text based on the bitmap properties
                if (hasContent) {
                    // Generate some placeholder text
                    append(generatePlaceholderText())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in fallback text recognition: ${e.message}")
            return "Error in text recognition"
        }
    }
    
    /**
     * Checks if a bitmap is basically empty
     */
    private fun isBitmapEmpty(bitmap: Bitmap): Boolean {
        // Sample a few random pixels to see if they're all the same color
        val random = SecureRandom()
        val samples = 10
        var firstPixel = 0
        
        for (i in 0 until samples) {
            val x = random.nextInt(bitmap.width)
            val y = random.nextInt(bitmap.height)
            val pixel = bitmap.getPixel(x, y)
            
            if (i == 0) {
                firstPixel = pixel
            } else if (pixel != firstPixel) {
                return false // Found different pixels, bitmap is not empty
            }
        }
        
        return true // All sampled pixels are the same
    }
    
    /**
     * Generates placeholder text for testing purposes
     */
    private fun generatePlaceholderText(): String {
        val textOptions = listOf(
            "Click here",
            "Tap to continue",
            "Next",
            "Skip",
            "Continue",
            "Get Reward",
            "Claim",
            "Loading...",
            "Please wait",
            "Offer complete"
        )
        
        val random = SecureRandom()
        val stringBuilder = StringBuilder()
        
        // Add 2-4 random text items
        val numItems = 2 + random.nextInt(3)
        for (i in 0 until numItems) {
            stringBuilder.append(textOptions[random.nextInt(textOptions.size)])
            stringBuilder.append("\n")
        }
        
        return stringBuilder.toString()
    }

    /**
     * Update the latest screen capture
     */
    fun updateScreenCapture(bitmap: Bitmap) {
        latestScreenCapture = bitmap
    }

    /**
     * Cleanup resources
     */
    fun close() {
        textRecognizer.close()
        latestScreenCapture?.recycle()
        latestScreenCapture = null
    }
   
    /**
     * Reset the recognizer if needed
     */
    fun resetRecognizer() {
        try {
            // Close the existing recognizer
            textRecognizer.close()
            
            // Create a new instance
            textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            
            // Reset security error flag
            hasSecurityError.set(false)
            
            Log.d(TAG, "Text recognizer has been reset")
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting text recognizer: ${e.message}")
        }
    }
} 