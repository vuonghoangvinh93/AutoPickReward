package com.kelvin.autopickreward.model

/**
 * Data class that holds the settings for auto scroll and click functionality
 */
data class AppSettings(
    val conditionForScroll: String = "",
    val conditionForClick: String = "",
    val delayForCheckScroll: Int = 3,
    val delayForCheckClick: Int = 3,
    val timeoutForCheckClick: Int = 650,
    val directionForScroll: ScrollDirection = ScrollDirection.DOWN,
    val radiusSearchArea: Int = 130,
    val clickPoint: ClickPoint? = null
)

/**
 * Data class that represents a point for clicking
 */
data class ClickPoint(
    val x: Float,
    val y: Float,
    var isVisible: Boolean = true
)

/**
 * Enum class that represents the scroll direction
 */
enum class ScrollDirection {
    UP, DOWN
} 