package com.musicsyncer.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HashingTest {
    @Test
    fun sha256MatchesKnownVector() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            Hashing.sha256("hello".encodeToByteArray()),
        )
    }
}
