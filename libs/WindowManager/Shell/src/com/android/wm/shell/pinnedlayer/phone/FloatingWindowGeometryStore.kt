// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

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
) {
    constructor(directory: File, executor: Executor) : this(
        { userId -> File(directory, "floating_window_geometry_$userId.json") }, executor)

    data class Entry(val bounds: Rect, val manual: Boolean, val landscape: Boolean,
        val scale: Float = FloatingWindowGeometry.DEFAULT_SCALE)
    private val users = mutableMapOf<Int, JSONObject>()
    private val pendingWrites = mutableMapOf<Int, ByteArray>()
    private val writingUsers = mutableSetOf<Int>()

    private fun user(userId: Int): JSONObject = users.getOrPut(userId) {
        val file = fileForUser(userId) ?: return@getOrPut JSONObject()
        try {
            val atomic = AtomicFile(file)
            if (atomic.exists()) atomic.openRead().bufferedReader().use { JSONObject(it.readText()) }
            else JSONObject()
        } catch (e: Exception) {
            Slog.w(TAG, "Could not read floating geometry", e)
            JSONObject()
        }
    }

    fun store(packageName: String, userId: Int, orientation: Int, bounds: Rect, stable: Rect,
        displayWidth: Int, displayHeight: Int, density: Int, manual: Boolean, landscape: Boolean,
        scale: Float = FloatingWindowGeometry.DEFAULT_SCALE) {
        if (bounds.isEmpty || stable.isEmpty || !scale.isFinite() ||
            scale !in FloatingWindowGeometry.MIN_SCALE..1f) return
        val value = JSONObject().put("left", (bounds.left - stable.left).toDouble() / stable.width())
            .put("top", (bounds.top - stable.top).toDouble() / stable.height())
            .put("width", bounds.width().toDouble() / stable.width())
            .put("height", bounds.height().toDouble() / stable.height())
            .put("short", min(displayWidth, displayHeight))
            .put("long", max(displayWidth, displayHeight)).put("density", density)
            .put("manual", manual).put("landscape", landscape).put("scale", scale.toDouble())
        val data = user(userId)
        val key = "$packageName:$orientation"
        if (data.optJSONObject(key)?.toString() == value.toString()) return
        data.put(key, value)
        persist(userId)
    }

    fun restore(packageName: String, userId: Int, orientation: Int, stable: Rect,
        displayWidth: Int, displayHeight: Int, density: Int): Entry? {
        if (stable.isEmpty) return null
        val key = "$packageName:$orientation"
        val value = user(userId).optJSONObject(key) ?: return null
        if (value.optInt("short") != min(displayWidth, displayHeight) ||
            value.optInt("long") != max(displayWidth, displayHeight) ||
            value.optInt("density") != density) {
            user(userId).remove(key)
            persist(userId)
            return null
        }
        val x = value.optDouble("left", Double.NaN)
        val y = value.optDouble("top", Double.NaN)
        val w = value.optDouble("width", Double.NaN)
        val h = value.optDouble("height", Double.NaN)
        if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite() ||
            x !in 0.0..1.0 || y !in 0.0..1.0 || w <= 0 || h <= 0 || w > 1 || h > 1) return null
        val left = stable.left + (x * stable.width()).roundToInt()
        val top = stable.top + (y * stable.height()).roundToInt()
        val bounds = Rect(left, top, left + (w * stable.width()).roundToInt(),
            top + (h * stable.height()).roundToInt())
        val scale = value.optDouble("scale", FloatingWindowGeometry.DEFAULT_SCALE.toDouble()).toFloat()
        if (!scale.isFinite() || scale !in FloatingWindowGeometry.MIN_SCALE..1f) return null
        return Entry(bounds, value.optBoolean("manual"), value.optBoolean("landscape"), scale)
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
