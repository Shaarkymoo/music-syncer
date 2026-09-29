package com.musicsyncer.app

import com.musicsyncer.app.sync.isNewerVersion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-Kotlin unit tests for the numeric dot-segment version compare (no Robolectric). */
class UpdaterTest {

    /** (a) Numeric compare beats lexical: "0.10.0" > "0.9.0" even though "0.1" < "0.9" lexically. */
    @Test fun numericCompareBeatsLexical() {
        assertTrue(isNewerVersion("0.10.0", "0.9.0"))
    }

    /** (b) Equal versions are not newer. */
    @Test fun equalVersionsAreNotNewer() {
        assertFalse(isNewerVersion("0.1.0", "0.1.0"))
    }

    /** (c) Patch bump is newer. */
    @Test fun patchBumpIsNewer() {
        assertTrue(isNewerVersion("0.1.1", "0.1.0"))
    }

    /** (d) Shorter segment tail vs longer: "0.2" > "0.1.5" (missing tail counts as 0). */
    @Test fun shorterSegmentTailBeatsLonger() {
        assertTrue(isNewerVersion("0.2", "0.1.5"))
    }

    /** (e) Shorter with equal prefix is not newer: "0.1" < "0.1.5". */
    @Test fun shorterEqualPrefixIsNotNewer() {
        assertFalse(isNewerVersion("0.1", "0.1.5"))
    }

    /** (f) Non-numeric segment is dropped, not a crash: "0.1.0-beta" == "0.1.0". */
    @Test fun nonNumericSegmentHandledWithoutCrash() {
        assertFalse(isNewerVersion("0.1.0-beta", "0.1.0"))
    }
}