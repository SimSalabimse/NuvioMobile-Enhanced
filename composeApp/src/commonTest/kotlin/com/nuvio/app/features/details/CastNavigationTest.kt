package com.nuvio.app.features.details

import com.nuvio.app.core.ui.KeyboardTap
import com.nuvio.app.core.ui.keyboardTapAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CastNavigationTest {
    @Test
    fun goodIdsNavigateAndMissingIdsDoNot() {
        val ready = castNavigationPlan(tmdbId = 287, overlayVisible = false)
        assertEquals(CastNavigation.OpenPerson, ready.navigation)
        assertEquals(287, ready.personId)
        assertEquals(false, ready.dismissOverlayFirst)

        val missing = castNavigationPlan(tmdbId = null, overlayVisible = true)
        assertEquals(CastNavigation.Unavailable, missing.navigation)
        assertNull(missing.personId)
        assertEquals(false, missing.dismissOverlayFirst)

        assertEquals(CastNavigation.Unavailable, castNavigationPlan(0, overlayVisible = true).navigation)
        assertEquals(CastNavigation.Unavailable, castNavigationPlan(-4, overlayVisible = false).navigation)
    }

    @Test
    fun overlayIsDismissedOnlyWhenThePersonIdIsReal() {
        val plan = castNavigationPlan(tmdbId = 15, overlayVisible = true)
        assertEquals(CastNavigation.OpenPerson, plan.navigation)
        assertEquals(true, plan.dismissOverlayFirst)
        val after = castNavigationPlan(tmdbId = 15, overlayVisible = false)
        assertEquals(false, after.dismissOverlayFirst)
        assertEquals(15, after.personId)
    }

    @Test
    fun idShapesFromProvidersStayRealAndNamesAreNotPeople() {
        assertEquals(500, navigablePersonId("500"))
        assertEquals(500, navigablePersonId(500))
        assertEquals(88, personIdFromUrl("https://www.themoviedb.org/person/88-ada"))
        assertEquals(88, personIdFromUrl("tmdb:person:88"))
        assertNull(personIdFromUrl("https://www.imdb.com/name/nm0000001/"))
        assertNull(navigablePersonId("Ada Lovelace"))
        assertNull(navigablePersonId("nm0000001"))
        assertNull(personIdFromUrl("stremio:///search?search=Ada"))
    }

    @Test
    fun parserKeepsMixedProviderIdsAndDropsTheRest() {
        val parsed = MetaDetailsParser.parse(
            """
            {
              "meta": {
                "id": "tt9",
                "type": "series",
                "name": "Show",
                "cast": ["Name Only"],
                "app_extras": {
                  "cast": [
                    {"name": "Numeric", "id": 11, "character": "Lead"},
                    {"name": "Textual", "tmdb_id": "22"},
                    {"name": "Linked Later", "character": "Guest"}
                  ]
                },
                "links": [
                  {"name": "From Url", "category": "cast", "url": "https://www.themoviedb.org/person/33"},
                  {"name": "Linked Later", "category": "actor", "url": "tmdb:person:44"},
                  {"name": "Imdb Only", "category": "actors", "url": "https://www.imdb.com/name/nm0000093/"}
                ]
              }
            }
            """.trimIndent(),
        )
        val byName = parsed.cast.associateBy { it.name }
        assertEquals(11, byName.getValue("Numeric").tmdbId)
        assertEquals(22, byName.getValue("Textual").tmdbId)
        assertEquals(33, byName.getValue("From Url").tmdbId)
        assertEquals(44, byName.getValue("Linked Later").tmdbId)
        assertNull(byName.getValue("Name Only").tmdbId)
        assertNull(byName.getValue("Imdb Only").tmdbId)
    }

    @Test
    fun diskRoundTripKeepsARealIdAndDoesNotInventOne() {
        val stored = MetaDetails(
            id = "tt1",
            type = "movie",
            name = "Movie",
            cast = listOf(
                MetaPerson(name = "Kept", tmdbId = 42),
                MetaPerson(name = "Blank", tmdbId = null),
                MetaPerson(name = "Zero", tmdbId = 0),
            ),
        ).toStoredRenderMeta().toMetaDetails()
        assertEquals(42, stored.cast[0].tmdbId)
        assertNull(stored.cast[1].tmdbId)
        assertNull(stored.cast[2].tmdbId)
    }

    @Test
    fun keyboardDismissDoesNotTakeARowTap() {
        assertEquals(KeyboardTap.ChildHandled, keyboardTapAction(childConsumed = true, imeVisible = true))
        assertEquals(KeyboardTap.DismissKeyboard, keyboardTapAction(childConsumed = false, imeVisible = true))
        assertEquals(KeyboardTap.Ignore, keyboardTapAction(childConsumed = false, imeVisible = false))
    }
}
