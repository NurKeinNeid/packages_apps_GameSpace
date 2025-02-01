/*
 * Copyright (C) 2021 Chaldeaprjkt
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
package io.chaldeaprjkt.gamespace.data

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.google.gson.Gson
import javax.inject.Inject

class GameSession @Inject constructor(
    private val context: Context,
    private val appSettings: AppSettings,
    private val systemSettings: SystemSettings,
    private val gson: Gson,
) {

    private val db by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var state
        get() = db.getString(KEY_SAVED_SESSION, "")
            .takeIf { !it.isNullOrEmpty() }
            ?.let {
                try {
                    gson.fromJson(it, SessionState::class.java)
                } catch (e: RuntimeException) {
                    null
                }
            }
        set(value) = db.edit()
            .putString(KEY_SAVED_SESSION, value?.let {
                try {
                    gson.toJson(value)
                } catch (e: RuntimeException) {
                    ""
                }
            } ?: "")
            .apply()

    fun register(sessionName: String) {
        try {
            val previousState = state
            state = SessionState(
                packageName = sessionName,
                autoBrightness = systemSettings.autoBrightness,
                threeScreenshot = systemSettings.threeScreenshot,
                headsUp = systemSettings.headsUp,
                ringerMode = audioManager.ringerModeInternal,
                doubleTapToSleep = systemSettings.doubleTapToSleep,
                fastChargeDisabler = systemSettings.fastChargeDisabler as? Boolean
            )

            // Save previous state for recovery
            db.edit().putString(KEY_PREVIOUS_STATE, gson.toJson(previousState)).apply()

            // Apply settings with error handling
            applySettings()
        } catch (e: Exception) {
            // Restore previous state if available
            restorePreviousState()
            throw e
        }
    }

    private fun applySettings() {
        if (appSettings.noAutoBrightness) {
            systemSettings.autoBrightness = false
        }
        if (appSettings.noThreeScreenshot) {
            systemSettings.threeScreenshot = false
        }
        if (appSettings.doubleTaptoSleep) {
            systemSettings.doubleTapToSleep = false
        }
        if (appSettings.fastChargeDisabler) {
            systemSettings.fastChargeDisabler = false
        }

        // Handle notifications mode
        when (appSettings.notificationsMode) {
            0, 3 -> systemSettings.headsUp = false
            1, 2 -> systemSettings.headsUp = true
        }

        // Handle ringer mode
        if (appSettings.ringerMode != 3) {
            audioManager.ringerModeInternal = appSettings.ringerMode
        }
    }

    fun unregister() {
        try {
            val orig = state?.copy() ?: return
            // Restore all settings in a safe manner
            if (appSettings.noAutoBrightness) {
                orig.autoBrightness?.let { systemSettings.autoBrightness = it }
            }
            if (appSettings.noThreeScreenshot) {
                orig.threeScreenshot?.let { systemSettings.threeScreenshot = it }
            }
            if (appSettings.doubleTaptoSleep) {
                orig.doubleTapToSleep?.let { systemSettings.doubleTapToSleep = it }
            }
            if (appSettings.fastChargeDisabler) {
                orig.fastChargeDisabler?.let { systemSettings.fastChargeDisabler = it }
            }
            orig.headsUp?.let { systemSettings.headsUp = it }
            if (appSettings.ringerMode != 3) {
                audioManager.ringerModeInternal = orig.ringerMode
            }
            
            // Clear state and saved data
            state = null
            db.edit().remove(KEY_PREVIOUS_STATE).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error during unregister", e)
            // Attempt to restore previous state even in case of error
            restorePreviousState()
        }
    }

    fun finalize() {
        try {
            unregister()
        } catch (e: Exception) {
            Log.e(TAG, "Error during finalize", e)
        }
    }

    private fun restorePreviousState() {
        try {
            val previousStateJson = db.getString(KEY_PREVIOUS_STATE, null)
            if (previousStateJson != null) {
                val previousState = gson.fromJson(previousStateJson, SessionState::class.java)
                previousState?.let { state ->
                    systemSettings.autoBrightness = state.autoBrightness ?: true
                    systemSettings.threeScreenshot = state.threeScreenshot ?: true
                    systemSettings.doubleTapToSleep = state.doubleTapToSleep ?: true
                    systemSettings.fastChargeDisabler = state.fastChargeDisabler ?: true
                    systemSettings.headsUp = state.headsUp ?: true
                    audioManager.ringerModeInternal = state.ringerMode
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring previous state", e)
        } finally {
            db.edit().remove(KEY_PREVIOUS_STATE).apply()
        }
    }

    companion object {
        private const val TAG = "GameSession"
        const val PREFS_NAME = "persisted_session"
        const val KEY_SAVED_SESSION = "session"
        private const val KEY_PREVIOUS_STATE = "previous_state"
    }
}
