package com.juanlink.core.snapshot

import com.juanlink.core.canvas.CanvasDocument
import com.juanlink.core.canvas.StrokeAdd
import com.juanlink.core.model.RectF
import com.juanlink.core.model.Stroke
import com.juanlink.core.model.StrokePoint
import com.juanlink.core.model.StrokeStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SnapshotTest {

    private fun stroke(id: String): Stroke = Stroke(
        id = id,
        layerId = "layer-1",
        points = listOf(StrokePoint(0f, 0f), StrokePoint(10f, 10f)),
        style = StrokeStyle(),
        bounds = RectF(0f, 0f, 10f, 10f),
    )

    @Test
    fun captureCapturesDocumentState() {
        val doc = CanvasDocument("doc")
        doc.apply(StrokeAdd(stroke("s1")))
        doc.apply(StrokeAdd(stroke("s2")))

        val store = SnapshotStore()
        val snap = store.capture(doc)
        assertEquals(2, snap.strokeCount)
        assertEquals(2, snap.strokes.size)
        assertEquals("s1", snap.strokes[0].id)
        assertEquals(1, store.size)
    }

    @Test
    fun storeKeepsLatestWithinCapacity() {
        val doc = CanvasDocument("doc")
        val store = SnapshotStore(capacity = 3)
        doc.apply(StrokeAdd(stroke("s1")))
        store.capture(doc)
        doc.apply(StrokeAdd(stroke("s2")))
        store.capture(doc)
        doc.apply(StrokeAdd(stroke("s3")))
        store.capture(doc)
        doc.apply(StrokeAdd(stroke("s4")))
        store.capture(doc)

        assertEquals(3, store.size, "容量内保留最近 3 张")
        assertEquals(2, store.all.first().strokeCount, "最旧快照被淘汰")
        assertEquals(4, store.all.last().strokeCount)
    }

    @Test
    fun jsonRoundTrip() {
        val doc = CanvasDocument("doc")
        doc.apply(StrokeAdd(stroke("s1")))
        val store = SnapshotStore()
        store.capture(doc)

        val json = SnapshotJson.encodeList(store.all)
        val restored = SnapshotJson.decodeList(json)
        assertEquals(1, restored.size)
        assertNotNull(restored[0].strokes.firstOrNull { it.id == "s1" })
    }

    @Test
    fun loadAllRestoresHistory() {
        val doc = CanvasDocument("doc")
        val store = SnapshotStore(capacity = 5)
        doc.apply(StrokeAdd(stroke("s1")))
        store.capture(doc)
        doc.apply(StrokeAdd(stroke("s2")))
        store.capture(doc)

        val newStore = SnapshotStore(capacity = 5)
        newStore.loadAll(SnapshotJson.decodeList(SnapshotJson.encodeList(store.all)))
        assertEquals(2, newStore.size)
        assertEquals(2, newStore.all.last().strokeCount)
    }
}
