// SPDX-License-Identifier: Apache-2.0
package com.android.wm.shell.pinnedlayer.phone

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.Executor
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class FloatingWindowGeometryStoreTest {
    @Test fun storeRestore_keepsVisibleGeometryAndScaleAcrossInstances() {
        for (scale in listOf(.5f, 1f / 1.3f, 1f)) {
            repository().store("scaled.app", 10, 1, bounds, stable, 1200, 1920, 320,
                true, false, scale)
            val restored = repository().restore("scaled.app", 10, 1, stable, 1200, 1920, 320)!!
            assertThat(restored.bounds).isEqualTo(bounds)
            assertThat(restored.scale).isEqualTo(scale)
        }
    }

    @Test fun normalGeometryBelowOldFloor_isRememberedWhenAboveMiniSize() {
        val store = repository()
        val normal = Rect(200, 300, 600, 900)
        store.store("small.normal", 0, 1, normal, stable, 1200, 1920, 320, false, false, .4f)
        assertThat(store.restore("small.normal", 0, 1, stable, 1200, 1920, 320)!!.bounds)
            .isEqualTo(normal)
    }

    @Test fun store_rejectsMiniAndStashByStateEvenAtNormalSize() {
        val store = repository()
        store.store("state.app", 0, 1, bounds, stable, 1200, 1920, 320, false, false, .75f)
        for (state in listOf(FloatingWindowGeometryStore.PresentationState.MINI,
            FloatingWindowGeometryStore.PresentationState.STASHED)) {
            store.store("state.app", 0, 1, Rect(100, 200, 900, 1400), stable,
                1200, 1920, 320, false, false, 1f, state)
        }
        store.store("state.app", 0, 1, Rect(-500, 200, 100, 1100), stable,
            1200, 1920, 320, false, false, .75f)
        assertThat(store.restore("state.app", 0, 1, stable, 1200, 1920, 320)!!.bounds)
            .isEqualTo(bounds)
    }

    @Test fun restore_rejectsPersistedMiniStateAndLegacyMiniGeometry() {
        val file = File(temporaryFolder.root, "floating_window_geometry_0.json")
        repository().store("old.app", 0, 1, bounds, stable, 1200, 1920, 320, false, false, .75f)
        val saved = org.json.JSONObject(file.readText())
        saved.getJSONObject("old.app:1").put("presentation", "MINI")
        file.writeText(saved.toString())
        assertThat(repository().restore("old.app", 0, 1, stable, 1200, 1920, 320)).isNull()
        saved.getJSONObject("old.app:1").apply {
            remove("presentation")
            put("width", .3)
            put("scale", .45)
        }
        file.writeText(saved.toString())
        assertThat(repository().restore("old.app", 0, 1, stable, 1200, 1920, 320)).isNull()
    }

    @Test fun store_rejectsInvalidScaleWithoutOverwritingGeometry() {
        val store = repository()
        store.store("scaled.app", 0, 1, bounds, stable, 1200, 1920, 320, false, false, .7f)
        for (scale in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, .4f, 1.01f)) {
            store.store("scaled.app", 0, 1, Rect(0, 0, 100, 100), stable, 1200, 1920,
                320, false, false, scale)
        }
        val restored = repository().restore("scaled.app", 0, 1, stable, 1200, 1920, 320)!!
        assertThat(restored.bounds).isEqualTo(bounds)
        assertThat(restored.scale).isEqualTo(.7f)
    }

    @Test fun store_coalescesPendingSnapshotsAndSkipsDuplicateGeometry() {
        val writes = java.util.ArrayDeque<Runnable>()
        val store = FloatingWindowGeometryStore(temporaryFolder.root, Executor { writes.add(it) })
        val latest = Rect(bounds).apply { offset(20, 30) }
        store.store("scaled.app", 0, 1, bounds, stable, 1200, 1920, 320, false, false, .75f)
        store.store("scaled.app", 0, 1, latest, stable, 1200, 1920, 320, false, false, .8f)
        store.store("scaled.app", 0, 1, latest, stable, 1200, 1920, 320, false, false, .8f)
        assertThat(writes.size).isEqualTo(1)
        writes.removeFirst().run()
        val restored = repository().restore("scaled.app", 0, 1, stable, 1200, 1920, 320)!!
        assertThat(restored.bounds).isEqualTo(latest)
        assertThat(restored.scale).isEqualTo(.8f)
        store.store("scaled.app", 0, 1, latest, stable, 1200, 1920, 320, false, false, .8f)
        assertThat(writes).isEmpty()
    }

    @get:Rule val temporaryFolder = TemporaryFolder()
    private fun repository() = FloatingWindowGeometryStore(
        temporaryFolder.root, Executor { it.run() })
    private val stable = Rect(0, 100, 1200, 1900)
    private val bounds = Rect(200, 400, 800, 1300)

    @Test fun storeRestore_survivesNewInstanceAndPreservesChoice() {
        repository().store("test.app", 10, 1, bounds, stable, 1200, 1920, 320, true, false)
        val restored = repository().restore("test.app", 10, 1, stable, 1200, 1920, 320)!!
        assertThat(restored.bounds).isEqualTo(bounds)
        assertThat(restored.manual).isTrue()
        assertThat(restored.landscape).isFalse()
    }

    @Test fun keys_separatePackageUserAndDeviceOrientation() {
        val store = repository()
        store.store("test.app", 10, 1, bounds, stable, 1200, 1920, 320, false, false)
        assertThat(store.restore("other.app", 10, 1, stable, 1200, 1920, 320)).isNull()
        assertThat(store.restore("test.app", 11, 1, stable, 1200, 1920, 320)).isNull()
        assertThat(store.restore("test.app", 10, 2, stable, 1920, 1200, 320)).isNull()
    }

    @Test fun restore_usesFractionsOfStableBounds() {
        val store = repository()
        store.store("test.app", 0, 1, bounds, stable, 1200, 1920, 320, false, false)
        val newStable = Rect(100, 0, 1180, 1800)
        assertThat(store.restore("test.app", 0, 1, newStable, 1200, 1920, 320)!!.bounds)
            .isEqualTo(Rect(280, 300, 820, 1200))
    }

    @Test fun displaySizeChange_dropsPersistedEntry() {
        val store = repository()
        store.store("test.app", 0, 1, bounds, stable, 1200, 1920, 320, true, false)
        assertThat(store.restore("test.app", 0, 1, stable, 1400, 2200, 320)).isNull()
        assertThat(repository().restore("test.app", 0, 1, stable, 1200, 1920, 320)).isNull()
    }

    @Test fun densityChange_dropsEntry() {
        val store = repository()
        store.store("test.app", 0, 1, bounds, stable, 1200, 1920, 320, false, false)
        assertThat(store.restore("test.app", 0, 1, stable, 1200, 1920, 400)).isNull()
    }

    @Test fun secondaryUsers_storeSeparateFilesInProcessDirectory() {
        val store = repository()
        store.store("test.app", 10, 1, bounds, stable, 1200, 1920, 320, true, false)
        store.store("test.app", 11, 1, bounds, stable, 1200, 1920, 320, false, false)
        assertThat(temporaryFolder.root.list()!!.toList()).containsExactly(
            "floating_window_geometry_10.json", "floating_window_geometry_11.json")
        assertThat(repository().restore("test.app", 10, 1, stable, 1200, 1920, 320)!!.manual)
            .isTrue()
        assertThat(repository().restore("test.app", 11, 1, stable, 1200, 1920, 320)!!.manual)
            .isFalse()
    }

    @Test fun removedUser_deletesOnlyTheirCacheAndFileAfterQueuedWrites() {
        val writes = java.util.ArrayDeque<Runnable>()
        val store = FloatingWindowGeometryStore(temporaryFolder.root, Executor { writes.add(it) })
        store.store("test.app", 10, 1, bounds, stable, 1200, 1920, 320, true, false)
        store.store("test.app", 11, 1, bounds, stable, 1200, 1920, 320, false, false)
        while (writes.isNotEmpty()) writes.removeFirst().run()
        store.store("test.app", 10, 1, bounds, stable, 1200, 1920, 320, true, false)
        store.removeUser(10)
        assertThat(store.restore("test.app", 10, 1, stable, 1200, 1920, 320)).isNull()
        while (writes.isNotEmpty()) writes.removeFirst().run()
        assertThat(File(temporaryFolder.root, "floating_window_geometry_10.json").exists())
            .isFalse()
        assertThat(store.restore("test.app", 10, 1, stable, 1200, 1920, 320)).isNull()
        assertThat(repository().restore("test.app", 11, 1, stable, 1200, 1920, 320))
            .isNotNull()
    }

    @Test fun corruptedFile_usesEmptyMemory() {
        File(temporaryFolder.root, "floating_window_geometry_0.json").writeText("invalid json")
        assertThat(repository().restore("test.app", 0, 1, stable, 1200, 1920, 320)).isNull()
    }
}
