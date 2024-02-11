/*
 * Copyright (C) 2024 the risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.qs

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Handler
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import androidx.cardview.widget.CardView

import com.android.systemui.R

import kotlin.math.abs

interface UserInteractionListener {
    fun onUserInteractionStart()
    fun onUserInteractionEnd()
    fun onLongPress()
}

open class VerticalSlider(context: Context, attrs: AttributeSet? = null) : CardView(context, attrs) {

    private val listeners: MutableList<UserInteractionListener> = mutableListOf()

    private val longPressTimeout = ViewConfiguration.getLongPressTimeout()
    private val longPressHandler = Handler()
    private var longPressRunnable: Runnable? = null
    private var isLongPressDetected = false
    private val longPressThreshold = 10f

    protected var progress: Int = 0
    private var max: Int = 100
    private val cornerRadius = context.resources.getDimensionPixelSize(R.dimen.qs_controls_slider_corner_radius).toFloat()
    private val layoutRect: RectF = RectF()
    private val layoutPaint = Paint().apply {
        isAntiAlias = true
    }
    private val progressRect: RectF = RectF()
    private val progressPaint = Paint().apply {
        isAntiAlias = true
    }
    private val path = Path()
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var lastProgress: Int = 0
    private val threshold = 0.05f
    private var actionPerformed = false

    private val isNightMode: Boolean
        get() = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    init {
        setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPressDetected = false
                    actionPerformed = false
                    startLongPressDetection(event)
                    lastX = event.x
                    lastY = event.y
                    lastProgress = progress
                    requestDisallowInterceptTouchEventFromParentsAndRoot(true)
                    notifyListenersUserInteractionStart()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = Math.abs(lastX - event.x)
                    val deltaY = Math.abs(lastY - event.y)
                    if (isLongPress(event, deltaX, deltaY)) {
                        isLongPressDetected = true
                        doLongPressAction()
                    } else {
                        cancelLongPressDetection()
                        val deltaY = lastY - event.y
                        val progressDelta = deltaY * max / measuredHeight.toFloat()
                        progress = (lastProgress + progressDelta).toInt().coerceIn(0, max)
                        updateProgressRect()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    notifyListenersUserInteractionEnd()
                    cancelLongPressDetection()
                    true
                }
                else -> {
                    false
                }
            }
        }
        updateSliderPaint()
    }

    private fun startLongPressDetection(event: MotionEvent) {
        longPressRunnable = Runnable {
            doLongPressAction()
        }
        longPressHandler.postDelayed(longPressRunnable, longPressTimeout.toLong())
    }

    private fun doLongPressAction() {
        if (isLongPressDetected && !actionPerformed) {
            listeners.forEach { it.onLongPress() }
            actionPerformed = true
            isLongPressDetected = false
        }
    }

    private fun cancelLongPressDetection() {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        isLongPressDetected = false
    }

    private fun isLongPress(event: MotionEvent, deltaX: Float, deltaY: Float): Boolean {
        val pressDuration = event.eventTime - event.downTime
        val distanceMoved = Math.sqrt((deltaX * deltaX + deltaY * deltaY).toDouble()).toFloat()
        return pressDuration >= longPressTimeout && distanceMoved < longPressThreshold
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return true
    }
    
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_MOVE && (event.y <= 0 || event.y >= measuredHeight)) {
            return false
        }
        return true
    }

    fun addUserInteractionListener(listener: UserInteractionListener) {
        listeners.add(listener)
    }

    fun removeUserInteractionListener(listener: UserInteractionListener) {
        listeners.remove(listener)
    }

    private fun notifyListenersUserInteractionStart() {
        listeners.forEach { it.onUserInteractionStart() }
    }

    private fun notifyListenersUserInteractionEnd() {
        listeners.forEach { it.onUserInteractionEnd() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (measuredHeight > 0 && measuredWidth > 0) {
            layoutRect.set(0f, 0f, measuredWidth.toFloat(), measuredHeight.toFloat())
            progressRect.set(
                0f,
                (1 - calculateProgress()) * measuredHeight,
                measuredWidth.toFloat(),
                measuredHeight.toFloat()
            )
            path.reset()
            path.addRoundRect(layoutRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutRect.set(0f, 0f, w.toFloat(), h.toFloat())
        progressRect.set(0f, (1 - calculateProgress()) * h, w.toFloat(), h.toFloat())
        path.reset()
        path.addRoundRect(layoutRect, cornerRadius, cornerRadius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.clipPath(path)
        canvas.drawRect(layoutRect, layoutPaint)
        canvas.drawRect(progressRect, progressPaint)
    }

    protected open fun updateSliderPaint() {
        layoutPaint.color = context.getColor(if (isNightMode) R.color.qs_controls_container_bg_color_dark else R.color.qs_controls_container_bg_color_light)
        progressPaint.color = context.getColor(if (isNightMode) R.color.qs_controls_active_color_dark else R.color.qs_controls_active_color_light)
    }

    fun updateIconTint(view: ImageView?) {
        val emptyThreshold = max * 0.15
        val isEmpty = progress <= emptyThreshold
        val iconColor = if (isEmpty) {
            if (isNightMode)
                R.color.qs_controls_bg_color_light
            else
                R.color.qs_controls_bg_color_dark
        } else {
            if (isNightMode)
                R.color.qs_controls_active_color_light
            else
                R.color.qs_controls_active_color_dark
        }
        val color = context.getColor(iconColor)
        view?.setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    protected open fun updateProgressRect() {
        val calculatedProgress = calculateProgress()
        val newTop = (1 - calculatedProgress) * measuredHeight
        val thresholdDelta = measuredHeight * threshold
        if (Math.abs(newTop - progressRect.top) < thresholdDelta) {
            progressRect.top = newTop
        } else {
            progressRect.top += (newTop - progressRect.top) * 0.1f
        }
        invalidate()
    }

    private fun calculateProgress(): Float {
        return progress.toFloat() / max.toFloat()
    }

    fun setMax(max: Int) {
        this.max = max
    }
    
    private fun requestDisallowInterceptTouchEventFromParentsAndRoot(disallowIntercept: Boolean) {
        var parentView = this.parent
        while (parentView != null && parentView !is ViewGroup) {
            parentView = parentView.parent
        }
        if (parentView != null) {
            parentView.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
        
        var rootView = this.rootView
        while (rootView != null && rootView.parent != null && rootView.parent is View) {
            rootView = rootView.parent as View
        }
        rootView?.parent?.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

}
