import kotlin.test.Test
import kotlin.test.assertEquals

class CategoriesTest {
    // Category names are display strings that feed push topics and data file names — keep the set
    // closed so a typo (or an accidental singular) in Items.kt fails fast instead of silently
    // minting a new category file + topic namespace.
    @Test
    fun `ITEMS only uses the known category set, in display order`() {
        assertEquals(
            listOf(
                "AI", "Video Games", "Books", "Anime", "Shows",
                "Movies", "People", "Resources", "Tech", "Miscellaneous",
            ),
            ITEMS.map { it.category }.distinct(),
        )
    }

    @Test
    fun `category file names are slugged`() {
        assertEquals("video-games.json", categoryFileName("Video Games"))
        assertEquals("ai.json", categoryFileName("AI"))
        assertEquals("miscellaneous.json", categoryFileName("Miscellaneous"))
    }

    @Test
    fun `item ids are unique`() {
        assertEquals(ITEMS.size, ITEMS.map { it.id }.toSet().size)
    }
}
