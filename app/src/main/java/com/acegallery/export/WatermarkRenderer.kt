package com.acegallery.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface

/**
 * [TextMeasure] backed by the same Paints used for drawing, so the pure layout and the Canvas
 * rendering agree on every width. Only `textSize` changes here; the caller configures typefaces.
 */
class PaintTextMeasure(
    private val mainPaint: Paint,
    private val smallPaint: Paint,
) : TextMeasure {

    override fun mainWidth(text: String, textSize: Float): Float {
        mainPaint.textSize = textSize
        return mainPaint.measureText(text)
    }

    override fun smallWidth(text: String, textSize: Float): Float {
        smallPaint.textSize = textSize
        return smallPaint.measureText(text)
    }

    override fun smallCharWidth(char: Char, textSize: Float): Float {
        smallPaint.textSize = textSize
        return smallPaint.measureText(char.toString())
    }
}

/** Draws a [WatermarkLayout] with the official Insta360 / Leica watermark style. */
object WatermarkRenderer {

    private const val MAIN_TEXT = "Insta360"
    private const val ACE_TEXT = "Ace"
    private const val PRO_TEXT = "Pro"
    private const val SMALL_TEXT = "CO-ENGINEERED WITH"

    /** [WatermarkLayout] derives every size from this reference main text size. */
    private const val REFERENCE_MAIN_TEXT_SIZE = 100f
    private const val OUTLINE_STROKE = 3f

    fun draw(
        canvas: Canvas,
        layout: WatermarkLayout,
        logo: Bitmap?,
        mainTypeface: Typeface,
        smallTypeface: Typeface,
    ) {
        val scale = layout.mainTextSize / REFERENCE_MAIN_TEXT_SIZE
        val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = mainTypeface
            textSize = layout.mainTextSize
            textAlign = Paint.Align.LEFT
        }
        val mainBaseline = centeredBaseline(mainPaint, layout.mainCenterY)

        canvas.drawText(MAIN_TEXT, layout.instaX, mainBaseline, mainPaint)

        drawAceGroup(canvas, layout, mainPaint, mainBaseline, scale)
        drawSmallText(canvas, layout, smallTypeface)
        drawLogo(canvas, layout, logo)
    }

    /**
     * The white `Ace` pill is knocked out inside a saved layer, so [PorterDuff.Mode.CLEAR] only
     * punches holes through the pill and never through the photo on the main canvas.
     */
    private fun drawAceGroup(
        canvas: Canvas,
        layout: WatermarkLayout,
        mainPaint: Paint,
        mainBaseline: Float,
        scale: Float,
    ) {
        val aceFill = layout.aceFill.toRectF()
        val pillPad = (layout.aceFill.width - mainPaint.measureText(ACE_TEXT)) / 2f

        val saveCount = canvas.saveLayer(aceFill, null)
        canvas.drawRoundRect(aceFill, layout.cornerRadius, layout.cornerRadius, mainPaint)
        val knockOutPaint = Paint(mainPaint).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        canvas.drawText(ACE_TEXT, layout.aceFill.left + pillPad, mainBaseline, knockOutPaint)
        canvas.restoreToCount(saveCount)

        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = OUTLINE_STROKE * scale
        }
        canvas.drawRoundRect(
            layout.group.toRectF(),
            layout.cornerRadius,
            layout.cornerRadius,
            outlinePaint,
        )
        canvas.drawText(PRO_TEXT, layout.proX, mainBaseline, mainPaint)
    }

    private fun drawSmallText(canvas: Canvas, layout: WatermarkLayout, smallTypeface: Typeface) {
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = smallTypeface
            textSize = layout.smallTextSize
            textAlign = Paint.Align.LEFT
            // Paint letter spacing is expressed in em units.
            letterSpacing = layout.letterSpacing / layout.smallTextSize
        }
        canvas.drawText(
            SMALL_TEXT,
            layout.smallTextLeft,
            centeredBaseline(smallPaint, layout.smallCenterY),
            smallPaint,
        )
    }

    private fun drawLogo(canvas: Canvas, layout: WatermarkLayout, logo: Bitmap?) {
        if (logo == null) return
        val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(logo, null, layout.logo.toRectF(), logoPaint)
    }

    /** Baseline that vertically centers the glyphs of [paint] on [centerY]. */
    private fun centeredBaseline(paint: Paint, centerY: Float): Float {
        val metrics = paint.fontMetrics
        return centerY - (metrics.ascent + metrics.descent) / 2f
    }

    private fun WatermarkRect.toRectF(): RectF = RectF(left, top, right, bottom)
}
