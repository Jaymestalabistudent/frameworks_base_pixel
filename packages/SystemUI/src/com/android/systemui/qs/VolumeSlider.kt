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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.AttributeSet
import android.widget.ImageView

import com.android.systemui.R

class VolumeSlider(context: Context, attrs: AttributeSet? = null) : VerticalSlider(context, attrs), UserInteractionListener {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var volumeIcon: ImageView? = null
    private var isUserInterAction = false

    private val volumeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.VOLUME_CHANGED_ACTION) {
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                progress = (currentVolume * 100 / maxVolume)
                updateProgressRect()
            }
        }
    }

    init {
        addUserInteractionListener(this)
    }

    override fun onUserInteractionStart() {
        isUserInterAction = true
    }

    override fun onUserInteractionEnd() {
        isUserInterAction = false
    }

    override fun onLongPress() {
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        volumeIcon = findViewById(R.id.qs_controls_volume_slider_icon)
        volumeIcon?.bringToFront()
        updateProgressRect()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter = IntentFilter()
        filter.addAction(AudioManager.VOLUME_CHANGED_ACTION)
        context.registerReceiver(volumeChangeReceiver, filter)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        progress = (currentVolume / maxVolume) * 100
        updateProgressRect()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }

    private fun updateVolume() {
        if (isUserInterAction) {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val volume = maxVolume * progress / 100
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        }
    }

    override fun updateProgressRect() {
        super.updateProgressRect()
        updateVolume()
        volumeIcon?.let { updateIconTint(it) }
    }

    override fun updateSliderPaint() {
        super.updateSliderPaint()
        volumeIcon?.let { updateIconTint(it) }
    }
}
