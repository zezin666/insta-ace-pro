package com.acegallery.export

import kotlin.math.max
import kotlin.math.min

/** Text measurement port so the layout stays pure Kotlin and unit-testable on the JVM. */
interface TextMeasure {
    fun mainWidth(text: String, textSize: Float): Float
    fun smallWidth(text: String, textSize: Float): Float
    fun smallCharWidth(char: Char, textSize: Float): Float
}

data class WatermarkRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * Geometry of the official-style watermark for one image size.
 *
 * All sizes derive from a 4032px-wide reference and are scaled proportionally, so a small
 * editor preview and the full-size export show the same relative watermark.
 */
data class WatermarkLayout(
    val mainTextSize: Float,
    val smallTextSize: Float,
    val letterSpacing: Float,
    val cornerRadius: Float,
    val group: WatermarkRect, // outline and white-fill area
    val instaX: Float,
    val mainCenterY: Float,
    val aceFill: WatermarkRect,
    val proX: Float,
    val smallTextLeft: Float,
    val smallCenterY: Float,
    val logo: WatermarkRect,
    val overallLeft: Float,
    val overallTop: Float,
    val overallRight: Float,
    val overallBottom: Float,
) {
    companion object {
        const val BASE_WIDTH = 4032f

        private const val MAIN_TEXT_SIZE = 100f
        private const val SMALL_TEXT_SIZE = 34f
        private const val LETTER_SPACING = 4f
        private const val GROUP_HEIGHT = 92f
        private const val CORNER_RADIUS = 18f
        private const val PILL_PAD = 14f
        private const val GAP_PILL_PRO = 14f
        private const val GROUP_PAD_RIGHT = 26f
        private const val GROUP_GAP = 30f
        private const val LOGO_SIZE = 116f
        private const val LOGO_GAP = 26f
        private const val EDGE_MARGIN = 85f
        private const val LOGO_TO_GROUP_CENTER_Y = 126f
        private const val SMALL_TEXT = "CO-ENGINEERED WITH"

        fun compute(
            imageWidth: Int,
            imageHeight: Int,
            position: WatermarkPosition,
            measure: TextMeasure,
        ): WatermarkLayout {
            val scale = (imageWidth / BASE_WIDTH).coerceIn(0.2f, 2.5f)
            val mainTextSize = MAIN_TEXT_SIZE * scale
            val smallTextSize = SMALL_TEXT_SIZE * scale
            val letterSpacing = LETTER_SPACING * scale
            val groupHeight = GROUP_HEIGHT * scale
            val cornerRadius = CORNER_RADIUS * scale
            val pillPad = PILL_PAD * scale
            val gapPillPro = GAP_PILL_PRO * scale
            val groupPadRight = GROUP_PAD_RIGHT * scale
            val groupGap = GROUP_GAP * scale

            val instaWidth = measure.mainWidth("Insta360", mainTextSize)
            val aceWidth = measure.mainWidth("Ace", mainTextSize)
            val proWidth = measure.mainWidth("Pro", mainTextSize)
            val pillWidth = aceWidth + 2 * pillPad
            val groupWidth = pillWidth + gapPillPro + proWidth + groupPadRight
            val groupX = instaWidth + groupGap

            val logoSize = LOGO_SIZE * scale
            val logoGap = LOGO_GAP * scale
            val logoLeft = groupX + groupWidth + logoGap

            val smallWidth = measure.smallWidth(SMALL_TEXT, smallTextSize) +
                letterSpacing * (SMALL_TEXT.length - 1)
            val smallTextLeft = logoLeft - measure.smallCharWidth('C', smallTextSize) - smallWidth

            val unshiftedLeft = min(0f, smallTextLeft)
            val unshiftedRight = max(groupX + groupWidth, logoLeft + logoSize)

            val edgeMargin = EDGE_MARGIN * scale
            val isBottom = position == WatermarkPosition.BOTTOM_START ||
                position == WatermarkPosition.BOTTOM_END ||
                position == WatermarkPosition.BOTTOM_CENTER
            val groupTop: Float
            val logoCenterY: Float
            if (isBottom) {
                val bottom = imageHeight - edgeMargin
                logoCenterY = bottom - logoSize / 2
                groupTop = logoCenterY - LOGO_TO_GROUP_CENTER_Y * scale
            } else {
                groupTop = edgeMargin
                logoCenterY = groupTop + LOGO_TO_GROUP_CENTER_Y * scale
            }

            val shiftX = when (position) {
                WatermarkPosition.BOTTOM_CENTER ->
                    (imageWidth - (unshiftedRight - unshiftedLeft)) / 2f - unshiftedLeft
                WatermarkPosition.TOP_START, WatermarkPosition.BOTTOM_START ->
                    edgeMargin - unshiftedLeft
                WatermarkPosition.TOP_END, WatermarkPosition.BOTTOM_END ->
                    imageWidth - edgeMargin - unshiftedRight
            }

            val shiftedGroupLeft = groupX + shiftX
            val group = WatermarkRect(
                left = shiftedGroupLeft,
                top = groupTop,
                right = shiftedGroupLeft + groupWidth,
                bottom = groupTop + groupHeight,
            )
            val aceFill = WatermarkRect(
                left = group.left,
                top = group.top,
                right = group.left + pillWidth,
                bottom = group.bottom,
            )
            val shiftedLogoLeft = logoLeft + shiftX
            val logo = WatermarkRect(
                left = shiftedLogoLeft,
                top = logoCenterY - logoSize / 2,
                right = shiftedLogoLeft + logoSize,
                bottom = logoCenterY + logoSize / 2,
            )

            return WatermarkLayout(
                mainTextSize = mainTextSize,
                smallTextSize = smallTextSize,
                letterSpacing = letterSpacing,
                cornerRadius = cornerRadius,
                group = group,
                instaX = shiftX,
                mainCenterY = group.centerY,
                aceFill = aceFill,
                proX = aceFill.right + gapPillPro,
                smallTextLeft = smallTextLeft + shiftX,
                smallCenterY = logoCenterY,
                logo = logo,
                overallLeft = unshiftedLeft + shiftX,
                overallTop = groupTop,
                overallRight = unshiftedRight + shiftX,
                overallBottom = logo.bottom,
            )
        }
    }
}
