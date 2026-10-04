package io.github.dmitryweiner.synesthesia.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kept points and the tokens through the generated Kotlin: what the app
 * writes to `points.json`, what it offers to name a point, and what a pasted
 * token or an opened link turns out to be.
 */
class PointsBindingsTest {
    @Test
    fun aPointIsKeptUnderANameAndComesBackFromTheFile() {
        val list = PointList()
        assertEquals(0u, list.count())
        assertEquals("Point 1", list.suggestName(null))
        assertEquals("never a built-in's name", "Point 1", list.suggestName("Fractal garden"))

        assertEquals(0u, list.keep("Dawn", requireNotNull(presetStateJson(3u))))
        assertEquals(1u, list.keep("Dusk", requireNotNull(presetStateJson(8u))))
        assertEquals(listOf("Dawn", "Dusk"), list.names())
        assertEquals("re-saving your own overwrites it", "Dawn", list.suggestName("Dawn"))
        assertEquals("Point 1", list.suggestName(null))

        // The file is the console's, and a person may read it.
        val file = list.toJson()
        assertTrue(file, file.contains("\"name\": \"Dawn\""))
        val back = PointList.parse(file)
        assertEquals(listOf("Dawn", "Dusk"), back.names())
        assertEquals("Dusk", back.nameAt(1u))
        val point = requireNotNull(back.pointJson(0u))
        assertTrue(point.contains("\"presetName\":\"Dawn\""))

        assertTrue(back.forget(0u))
        assertEquals(listOf("Dusk"), back.names())
        assertFalse(back.forget(7u))
        assertNull(back.pointJson(7u))
    }

    @Test
    fun anUnreadablePointsFileIsAnErrorNotAnEmptyList() {
        assertEquals(0u, PointList.parse("").count())
        assertEquals(0u, PointList.parse("[]").count())
        try {
            PointList.parse("{oh dear")
            throw AssertionError("a broken file should not read as no points")
        } catch (e: CoreException.BadPointsFile) {
            assertTrue(e.reason.isNotEmpty())
        }
    }

    @Test
    fun aPointTravelsAsATokenAndComesBackFromOne() {
        val json = requireNotNull(presetStateJson(5u))
        val token = pointToken(json)
        assertTrue(token.isNotEmpty())
        assertEquals(json, pointFromToken(token))
        // The whole link it would have been pasted from works too.
        assertEquals(json, pointFromToken("  https://dmitryweiner.github.io/synesthesia/#s=$token  "))
        try {
            pointFromToken("not a token")
            throw AssertionError("nonsense should not open")
        } catch (e: CoreException.InvalidPoint) {
            assertTrue(e.reason.isNotEmpty())
        }
    }

    @Test
    fun aLinkSaysWhatItOpens() {
        val json = requireNotNull(presetStateJson(2u))
        val site = "https://dmitryweiner.github.io/synesthesia/"
        assertEquals(LinkPoint.Point(json), pointFromLink("$site#s=${pointToken(json)}"))
        assertEquals(LinkPoint.Preset(4u), pointFromLink("$site?preset=4"))
        // A point kept on the web app's server: there is no network here.
        assertEquals(LinkPoint.NeedsTheWebApp("aB3dE6gH9j"), pointFromLink("$site?presetId=aB3dE6gH9j"))
        assertEquals(LinkPoint.Nothing, pointFromLink(site))
    }
}
