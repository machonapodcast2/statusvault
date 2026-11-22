package com.machonamedia.statusvault

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.ImageView

/** Grid cell that is always as tall as it is wide. */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}

/** Holds one image with pinch-to-zoom, drag-to-pan and double-tap zoom. */
class ZoomLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    val image: ImageView = ImageView(context)

    private var scale = 1f
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scale = (scale * detector.scaleFactor).coerceIn(1f, 5f)
                applyTransform()
                return true
            }
        }
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                scale = if (scale > 1f) 1f else 2.5f
                applyTransform()
                return true
            }
        }
    )

    init {
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun reset() {
        scale = 1f
        applyTransform()
    }

    private fun applyTransform() {
        image.scaleX = scale
        image.scaleY = scale
        if (scale <= 1f) {
            image.translationX = 0f
            image.translationY = 0f
        } else {
            clamp()
        }
    }

    private fun clamp() {
        val maxX = width * (scale - 1f) / 2f
        val maxY = height * (scale - 1f) / 2f
        image.translationX = image.translationX.coerceIn(-maxX, maxX)
        image.translationY = image.translationY.coerceIn(-maxY, maxY)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        tapDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x
                lastY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (scale > 1f && ev.pointerCount == 1 && !scaleDetector.isInProgress) {
                    image.translationX += ev.x - lastX
                    image.translationY += ev.y - lastY
                    clamp()
                }
                lastX = ev.x
                lastY = ev.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Continue panning from the finger that is still down.
                val keep = if (ev.actionIndex == 0) 1 else 0
                if (keep < ev.pointerCount) {
                    lastX = ev.getX(keep)
                    lastY = ev.getY(keep)
                }
            }
        }
        // While zoomed (or pinching) keep the pager from swiping to the next status.
        parent?.requestDisallowInterceptTouchEvent(scale > 1f || ev.pointerCount > 1)
        return true
    }
}
