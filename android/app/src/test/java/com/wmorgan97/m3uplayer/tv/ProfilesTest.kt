package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.*
import org.junit.Test

class ProfilesTest {
    @Test fun mainAlwaysFirstAndRemovedOnesHidden() {
        assertEquals(listOf("Main"), Profiles.list(emptyList()).map { it.name })
        assertFalse(Profiles.list(emptyList()).first().named)
        val l = Profiles.list(listOf(Profile("pb", "Mazie", 1), Profile("main", "Will", 0), Profile("pa", "", 2, deleted = true), Profile("pc", "Guest", 2)))
        assertEquals(listOf("Will", "Mazie", "Guest"), l.map { it.name })
        assertTrue(l.first().named)
    }
    @Test fun askOnlyWithMoreThanOneAndNothingRemembered() {
        val two = Profiles.list(listOf(Profile("main", "Will", 0), Profile("pb", "Mazie", 1)))
        assertFalse(Profiles.shouldAsk(Profiles.list(emptyList()), "main", false))
        assertTrue(Profiles.shouldAsk(two, "main", false))
        assertFalse(Profiles.shouldAsk(two, "pb", true))
        assertTrue("a remembered profile that was removed asks again", Profiles.shouldAsk(two, "gone", true))
    }
    @Test fun coloursAndInitials() {
        assertEquals(2, Profiles.nextColor(Profiles.list(listOf(Profile("main", "Will", 0), Profile("pb", "Mazie", 1)))))
        assertEquals("M", Profiles.initial(" mazie")); assertEquals("?", Profiles.initial(""))
        assertTrue(Profiles.newId().matches(Regex("p[a-z0-9]{1,24}")))
        assertEquals(Profiles.PERSONAL.toSet(), setOf("favorites", "favAt", "recents", "recReset", "progress", "reminders", "follows", "watchlater"))
    }
}
