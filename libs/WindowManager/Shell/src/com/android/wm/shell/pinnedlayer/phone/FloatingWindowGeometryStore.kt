// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.app.ActivityManager
import android.graphics.Rect
import android.util.AtomicFile
import android.util.Slog
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Per-user atomic snapshots, with display-relative bounds and natural display dimensions. */
class FloatingWindowGeometryStore(
    private val fileForUser: (Int) -> File?,
    private val executor: Executor,
    private val mainExecutor: Executor? = null,
) {
    constructor(directory: File, executor: Executor, mainExecutor: Executor) : this(
        { userId -> File(directory, "floating_window_geometry_$userId.json") }, executor,
        mainExecutor)

    enum class PresentationState { NORMAL, MINI, STASHED }

    data class Entry(val bounds: Rect, val manual: Boolean, val landscape: Boolean,
        val scale: Float = FloatingWindowGeometry.DEFAULT_SCALE)
    private val users = mutableMapOf<Int, JSONObject>()
    private val loadingUsers = mutableSetOf<Int>()
    private val pendingWrites = mutableMapOf<Int, ByteArray>()
    private val writingUsers = mutableSetOf<Int>()

    init {
        mainExecutor?.execute { prefetch(ActivityManager.getCurrentUser()) }
    }

    private fun prefetch(userId: Int) {
        val owner = mainExecutor ?: return
        if (users.containsKey(userId) || !loadingUsers.add(userId)) return
        executor.execute {
            val snapshot = readUser(userId)
            owner.execute {
                loadingUsers.remove(userId)
                // A fallback read, store or removal takes precedence over the prefetched snapshot.
                users.getOrPut(userId) { snapshot }
            }
        }
    }

    private fun user(userId: Int): JSONObject {
        return users.getOrPut(userId) { readUser(userId) }
    }

    private fun readUser(userId: Int): JSONObject {
        val file = fileForUser(userId) ?: return JSONObject()
        return try {
            val atomic = AtomicFile(file)
            if (atomic.exists()) atomic.openRead().bufferedReader().use { JSONObject(it.readText()) }
            else JSONObject()
        } catch (e: Exception) {
            Slog.w(TAG, "Could not read floating geometry", e)
            JSONObject()
        }
    }

    fun removeUser(userId: Int) {
        users[userId] = JSONObject()
        synchronized(pendingWrites) { pendingWrites.remove(userId) }
        val file = fileForUser(userId) ?: return
        executor.execute { AtomicFile(file).delete() }
    }

    private fun persist(userId: Int) {
        val file = fileForUser(userId) ?: return
        val bytes = user(userId).toString().toByteArray(Charsets.UTF_8)
        synchronized(pendingWrites) {
            pendingWrites[userId] = bytes
            if (!writingUsers.add(userId)) return
        }
        executor.execute {
            while (true) {
                val latest = synchronized(pendingWrites) {
                    pendingWrites.remove(userId) ?: run {
                        writingUsers.remove(userId)
                        return@execute
                    }
                }
                write(file, latest)
            }
        }
    }

    private fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file, "floating-window-geometry")
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            Slog.w(TAG, "Could not save floating geometry", e)
        }
    }

    companion object { private const val TAG = "FloatingGeometryStore" }
}
