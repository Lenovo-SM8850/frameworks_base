/*
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.miner7222.systemui.qs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot

/** Process lifetime appearance state; compositions never own observer registrations. */
class SharedQsAppearanceSettings private constructor(app: Context) {
    private val resolver = app.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private val refreshers = mutableMapOf<Uri, MutableList<() -> Unit>>()
    private val integers = mutableMapOf<Triple<String, Int, Int>, State<Int>>()
    private val systemStrings = mutableMapOf<Pair<String, String>, State<String>>()
    private val secureStrings = mutableMapOf<Pair<String, String>, State<String>>()

    init {
        app.registerReceiverForAllUsers(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = refreshAll()
            },
            IntentFilter(Intent.ACTION_USER_SWITCHED),
            null,
            handler,
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    @Synchronized
    fun systemInt(key: String, defaultValue: Int, failureValue: Int = defaultValue): State<Int> =
        integers.getOrPut(Triple(key, defaultValue, failureValue)) {
            observe(Settings.System.getUriFor(key)) {
                try {
                    Settings.System.getIntForUser(
                        resolver, key, defaultValue, UserHandle.USER_CURRENT)
                } catch (_: Throwable) {
                    failureValue
                }
            }
        }

    @Synchronized
    fun systemString(key: String, defaultValue: String): State<String> =
        systemStrings.getOrPut(key to defaultValue) {
            observe(Settings.System.getUriFor(key)) {
                try {
                    Settings.System.getStringForUser(resolver, key, UserHandle.USER_CURRENT)
                        ?: defaultValue
                } catch (_: Throwable) {
                    defaultValue
                }
            }
        }

    @Synchronized
    fun secureString(key: String, defaultValue: String): State<String> =
        secureStrings.getOrPut(key to defaultValue) {
            observe(Settings.Secure.getUriFor(key)) {
                Settings.Secure.getStringForUser(resolver, key, UserHandle.USER_CURRENT)
                    ?: defaultValue
            }
        }

    private fun <T> observe(uri: Uri, read: () -> T): State<T> {
        val state = mutableStateOf(read())
        val refresh = { state.value = read() }
        val existing = refreshers[uri]
        if (existing != null) {
            existing.add(refresh)
        } else {
            refreshers[uri] = mutableListOf(refresh)
            resolver.registerContentObserver(
                uri,
                false,
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) = refreshUri(uri)
                },
                UserHandle.USER_ALL,
            )
        }
        // Close the gap between the initial read and observer registration.
        refresh()
        return state
    }

    @Synchronized
    private fun refreshUri(uri: Uri) {
        Snapshot.withMutableSnapshot { refreshers[uri]?.forEach { it() } }
    }

    @Synchronized
    private fun refreshAll() {
        Snapshot.withMutableSnapshot { refreshers.values.forEach { readers -> readers.forEach { it() } } }
    }

    companion object {
        private var instance: SharedQsAppearanceSettings? = null

        @Synchronized
        fun get(context: Context): SharedQsAppearanceSettings =
            instance ?: SharedQsAppearanceSettings(context.applicationContext ?: context).also {
                instance = it
            }
    }
}
