package com.ski.live

import com.ski.live.model.GameState
import com.ski.live.notif.Otp
import com.ski.live.sports.Leagues
import com.ski.live.sports.clockSeconds
import com.ski.live.sports.gameFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogicTest {
    @Test fun findsCodes() {
        assertEquals("482913", Otp.find("G-482913 is your Google verification code."))
        assertEquals("4821", Otp.find("Your Uber code: 4821. Never share this code."))
        assertEquals("123456", Otp.find("Amazon: 123 456 is your OTP"))
        assertEquals("AB12CD", Otp.find("Use AB12CD to sign in"))
        assertEquals("907311", Otp.find("Your login code for 2026 is 907311"))
    }

    @Test fun ignoresNonCodes() {
        assertNull(Otp.find("Dinner at 7? I'll bring 2 pizzas"))
        assertNull(Otp.find("Call 555-123-4567 about the code"))
        assertNull(Otp.find("Your total is $1234, please confirm"))
    }

    @Test fun clock() {
        assertEquals(101f, clockSeconds("1:41")!!, 0.01f)
        assertEquals(34.2f, clockSeconds("34.2")!!, 0.01f)
        assertNull(clockSeconds(""))
    }

    @Test fun fractions() {
        val nba = Leagues.byKey("nba")
        val g = GameState("nba", "1", state = "in", period = 4, clock = "6:00")
        assertEquals(0.875f, gameFraction(g, nba), 0.001f)
        assertEquals(0f, gameFraction(g.copy(state = "pre"), nba), 0f)
        assertEquals(1f, gameFraction(g.copy(state = "post"), nba), 0f)
        val mlb = Leagues.byKey("mlb")
        assertEquals((4 + 0.25f) / 9, gameFraction(GameState("mlb", "2", state = "in", period = 5, detail = "Top 5th"), mlb), 0.001f)
        val epl = Leagues.byKey("epl")
        assertEquals(67f / 90, gameFraction(GameState("epl", "3", state = "in", period = 2, clock = "67'"), epl), 0.001f)
    }
}
