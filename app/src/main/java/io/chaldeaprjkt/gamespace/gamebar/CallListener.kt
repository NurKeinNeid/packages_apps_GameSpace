/*
 * Copyright (C) 2020 The exTHmUI Open Source Project
 * Copyright (C) 2021 AOSP-Krypton Project
 * Copyright (C) 2022 Nameless-AOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.chaldeaprjkt.gamespace.gamebar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioSystem
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.os.Handler
import android.os.Looper

import androidx.core.app.ActivityCompat

import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import javax.inject.Inject

import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.data.AppSettings
import android.app.NotificationManager
import android.app.NotificationChannel
import android.app.Notification
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract

@ServiceScoped
class CallListener @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettings: AppSettings
) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)
    private val telecomManager = context.getSystemService(TelecomManager::class.java)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    private val callsMode = appSettings.callsMode
    private val callsDelay = appSettings.callsDelay
    private val CHANNEL_ID = "gamespace_calls"
    private val NOTIFICATION_ID = 1001
    private val handler = Handler(Looper.getMainLooper())
    private var pendingCallAction: Runnable? = null

    private val telephonyCallback = Callback()

    private var executor: ExecutorService? = null

    private var callStatus: Int = TelephonyManager.CALL_STATE_OFFHOOK

    fun init() {
        executor = Executors.newSingleThreadExecutor()
        telephonyManager?.registerTelephonyCallback(executor!!, telephonyCallback)
        createNotificationChannel()
    }

    fun destory() {
        if (pendingCallAction != null) {
            handler.removeCallbacks(pendingCallAction!!)
            pendingCallAction = null
        }
        telephonyManager?.unregisterTelephonyCallback(telephonyCallback)
        executor?.shutdownNow()
    }

    private fun isHeadsetPluggedIn(): Boolean {
        val audioDeviceInfoArr: Array<AudioDeviceInfo> =
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: emptyArray()
        return audioDeviceInfoArr.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private fun checkPermission(): Boolean {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ANSWER_PHONE_CALLS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "App does not have required permission ANSWER_PHONE_CALLS")
            return false
        }
        return true
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Game Space Calls",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications for auto-handled calls during gaming"
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager?.createNotificationChannel(channel)
    }

    private fun getContactName(phoneNumber: String): String {
        var contactName = phoneNumber
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    contactName = cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting contact name", e)
        }
        return contactName
    }

    private fun showCallNotification(phoneNumber: String, isAnswered: Boolean) {
        val contactName = getContactName(phoneNumber)
        val message = if (isAnswered) {
            context.getString(R.string.in_game_calls_received_number, contactName)
        } else {
            context.getString(R.string.in_game_calls_rejected_number, contactName)
        }

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.in_game_calls_title))
            .setContentText(message)
            .setAutoCancel(true)
            .build()

        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun handleIncomingCall(number: String, autoAnswer: Boolean) {
        if (pendingCallAction != null) {
            handler.removeCallbacks(pendingCallAction!!)
        }

        pendingCallAction = Runnable {
            if (autoAnswer) {
                telecomManager?.acceptRingingCall()
                showCallNotification(number, true)
            } else {
                telecomManager?.endCall()
                showCallNotification(number, false)
            }
            pendingCallAction = null
        }

        if (callsDelay > 0) {
            handler.postDelayed(pendingCallAction!!, callsDelay * 1000L)
        } else {
            pendingCallAction?.run()
        }
    }

    private inner class Callback : TelephonyCallback(), TelephonyCallback.CallStateListener {
        private var previousState = TelephonyManager.CALL_STATE_IDLE
        private var previousAudioMode = audioManager?.mode
        private var incomingNumber: String? = null

        override fun onCallStateChanged(state: Int) {
            if (callsMode == 0) return
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    if (!checkPermission()) return
                    incomingNumber = telephonyManager?.callState?.let { 
                        try {
                            val method = TelephonyManager::class.java.getDeclaredMethod("getIncomingPhoneNumber")
                            method.isAccessible = true
                            method.invoke(telephonyManager) as String
                        } catch (e: Exception) {
                            "Unknown"
                        }
                    } ?: "Unknown"
                    
                    handleIncomingCall(incomingNumber!!, callsMode == 1)
                }
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    if (callsMode == 2) return
                    if (previousState == TelephonyManager.CALL_STATE_RINGING) {
                        if (isHeadsetPluggedIn()) {
                            audioManager?.isSpeakerphoneOn = false
                            AudioSystem.setForceUse(
                                AudioSystem.FOR_COMMUNICATION,
                                AudioSystem.FORCE_NONE
                            )
                        } else {
                            audioManager?.isSpeakerphoneOn = true
                            AudioSystem.setForceUse(
                                AudioSystem.FOR_COMMUNICATION,
                                AudioSystem.FORCE_SPEAKER
                            )
                        }
                        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
                    }
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (callsMode == 2) return
                    if (previousState == TelephonyManager.CALL_STATE_OFFHOOK) {
                        audioManager?.mode = previousAudioMode ?: AudioManager.MODE_NORMAL
                        AudioSystem.setForceUse(
                            AudioSystem.FOR_COMMUNICATION,
                            AudioSystem.FORCE_NONE
                        )
                        audioManager?.isSpeakerphoneOn = false
                    }
                    if (pendingCallAction != null) {
                        handler.removeCallbacks(pendingCallAction!!)
                        pendingCallAction = null
                    }
                }
            }
            previousState = state
        }
    }

    companion object {
        private const val TAG = "CallListener"
    }
}