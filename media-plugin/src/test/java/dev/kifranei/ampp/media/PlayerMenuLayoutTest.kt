package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class PlayerMenuLayoutTest {
    @Test fun `mini player centers while top and artwork buttons open toward available space`() {
        val card = PlayerMenuBounds(75, 250, 250, 300)
        val safe = PlayerMenuInsets(10, 40, 12, 24)
        fun place(x: Int?, y: Int?) = PlayerMenuLayout.place(card, 400, 800, safe, x, y)
        assertEquals(card, place(null, null))
        assertEquals(card.copy(left = 125, top = 60), place(375, 60))
        assertEquals(card.copy(left = 125, top = 260), place(375, 560))
        assertEquals(card.copy(left = 138, top = 40), place(420, 20))
    }
    @Test fun `placement follows the anchor's free space and picks the expansion direction`() {
        val card = PlayerMenuBounds(75, 250, 250, 300)
        val safe = PlayerMenuInsets(10, 40, 12, 24)
        val centered = PlayerMenuLayout.placeAnchored(card, 400, 800, safe, null, null)
        assertEquals(PlayerMenuOpenDirection.CENTER, centered.direction)

        val below = PlayerMenuLayout.placeAnchored(card, 400, 800, safe, 375, 60)
        assertEquals(PlayerMenuOpenDirection.DOWN, below.direction)
        assertEquals(125, below.bounds.left); assertEquals(60, below.bounds.top)
        assertEquals(250f, below.pivotX, 0f); assertEquals(0f, below.pivotY, 0f)

        val above = PlayerMenuLayout.placeAnchored(card, 400, 800, safe, 375, 560)
        assertEquals(PlayerMenuOpenDirection.UP, above.direction)
        assertEquals(300f, above.pivotY, 0f)

        val rightward = PlayerMenuLayout.placeAnchored(card, 400, 800, safe, 20, 60)
        assertEquals(PlayerMenuOpenDirection.DOWN, rightward.direction)
        assertEquals(20, rightward.bounds.left); assertEquals(0f, rightward.pivotX, 0f)
    }
    @Test fun `quick actions and sections preserve every native command once`() {
        val actions = listOf("ADD_TO_LIBRARY", "ADD_TO_PLAYLIST", "SHARE_SONG", "SHARE_LYRICS", "CREATE_STATION",
            "VIEW_SONG_CREDITS", "SLEEP_TIMER", "FAVORITE_ITEM", "SUGGEST_LESS", "OPEN_ALBUM", "OPEN_ARTIST")
            .mapIndexed { index, key -> PlayerMenuAction(key, index + 1) }
        val result = PlayerMenuLayout.groups(actions)
        assertEquals(listOf("ADD_TO_LIBRARY", "FAVORITE_ITEM", "SHARE_SONG"), result.shortcuts.map { it?.key })
        val flattened = result.shortcuts.filterNotNull() + result.sections.flatten()
        assertEquals(actions.toSet(), flattened.toSet())
        assertEquals(actions.size, flattened.size)
        assertEquals("SUGGEST_LESS", result.sections.last().single().key)
    }
    @Test fun `downloaded and favorite states retain their native undo command`() {
        val actions = listOf("REMOVE_DOWNLOAD", "UNDO_FAVORITE_ITEM", "SHARE_LYRICS", "SHARE_STATION")
            .mapIndexed { i, key -> PlayerMenuAction(key, i + 1) }
        val result = PlayerMenuLayout.groups(actions)
        assertEquals(listOf("REMOVE_DOWNLOAD", "UNDO_FAVORITE_ITEM", "SHARE_STATION"), result.shortcuts.map { it?.key })
        assertEquals("SHARE_LYRICS", result.sections.single().single().key)
    }
    @Test fun `unavailable shortcuts remain absent instead of dispatching a different action`() {
        val action = PlayerMenuAction("SLEEP_TIMER", 8)
        val result = PlayerMenuLayout.groups(listOf(action))
        assertEquals(listOf(null, null, null), result.shortcuts)
        assertEquals(listOf(listOf(action)), result.sections)
    }
    @Test fun `long menu floats clear of system insets and keeps room for player controls`() {
        val result = PlayerMenuLayout.bounds(1280, 2772, 3.25f, 130, 80, 2400)
        assertEquals((1280 * .68f).toInt(), result.width)
        assertEquals((1280 - result.width) / 2, result.left)
        assertTrue(result.top >= 130 + 39)
        assertTrue(result.top + result.height <= 2772 - 80)
        assertTrue(result.height < 2400)
    }
    @Test fun `short and long menus stay centered in the viewport`() {
        val result = PlayerMenuLayout.bounds(393, 852, 1f, 44, 34, 180)
        assertEquals(44 + (852 - 44 - 34 - 180) / 2, result.top)
        assertEquals((393 - result.width) / 2, result.left)
        assertEquals(180, result.height)
        val longer = PlayerMenuLayout.bounds(393, 852, 1f, 44, 34, 2400)
        assertEquals((393 - longer.width) / 2, longer.left)
        assertEquals(44 + (852 - 44 - 34 - longer.height) / 2, longer.top)
        val edge = PlayerMenuLayout.bounds(240, 320, 1f, 20, 20, 2000)
        assertEquals((240 - edge.width) / 2, edge.left)
        assertEquals(20 + (320 - 20 - 20 - edge.height) / 2, edge.top)
    }
    @Test fun `reference phone viewport keeps the card centered`() {
        val result = PlayerMenuLayout.bounds(1280, 2772, 3.25f, 152, 80, 2400)
        assertEquals((1280 - result.width) / 2, result.left)
        assertEquals(152 + (2772 - 152 - 80 - result.height) / 2, result.top)
        assertTrue(result.width > 0)
    }
    @Test fun `display cutouts on either side remain outside the menu`() {
        val result = PlayerMenuLayout.bounds(1280, 2772, 3.25f, 130, 80, 2400, 120, 60)
        assertTrue(result.left >= 120)
        assertTrue(result.left + result.width <= 1280 - 60)
        assertEquals(120 + (1280 - 120 - 60 - result.width) / 2, result.left)
    }
    @Test fun `system bars already excluded from dialog content are not counted twice`() {
        val result = PlayerMenuLayout.localInsets(
            PlayerMenuBounds(0, 130, 1280, 2562),
            PlayerMenuBounds(0, 0, 1280, 2772),
            PlayerMenuInsets(0, 130, 0, 80),
        )
        assertEquals(PlayerMenuInsets(0, 0, 0, 0), result)
    }
    @Test fun `offset windows keep local safety margins when their screen origin changes`() {
        val bars = PlayerMenuInsets(20, 50, 24, 32)
        val first = PlayerMenuLayout.localInsets(
            PlayerMenuBounds(120, 300, 1024, 900), PlayerMenuBounds(120, 300, 1024, 900), bars)
        val moved = PlayerMenuLayout.localInsets(
            PlayerMenuBounds(480, 620, 1024, 900), PlayerMenuBounds(480, 620, 1024, 900), bars)
        assertEquals(bars, first)
        assertEquals(first, moved)
        val contentOnly = PlayerMenuLayout.localInsets(
            PlayerMenuBounds(140, 350, 980, 818), PlayerMenuBounds(120, 300, 1024, 900), bars)
        assertEquals(PlayerMenuInsets(0, 0, 0, 0), contentOnly)
    }
    @Test fun `viewport and density changes always produce a visible bounded card`() {
        for ((width, height) in listOf(240 to 320, 393 to 852, 1280 to 2772, 2772 to 1280, 1 to 1)) {
            for (density in listOf(.75f, 1f, 2.625f, 3.25f, 4f)) {
                val result = PlayerMenuLayout.bounds(width, height, density, height / 20, height / 24,
                    5000, width / 20, width / 30)
                assertTrue(result.width > 0 && result.height > 0)
                assertTrue(result.left >= width / 20 && result.top >= height / 20)
                assertTrue(result.left + result.width <= width - width / 30)
                assertTrue(result.top + result.height <= height - height / 24)
            }
        }
        val narrow = PlayerMenuLayout.bounds(1, 1, Float.NaN, 100, 100, 2000, 100, 100)
        assertEquals(PlayerMenuBounds(0, 0, 1, 1), narrow)
    }
}
