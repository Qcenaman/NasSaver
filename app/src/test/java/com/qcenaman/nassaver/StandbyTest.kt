package com.qcenaman.nassaver

import com.qcenaman.nassaver.core.Standby
import com.qcenaman.nassaver.core.Standby.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyTest {
    @Test fun offNeedsNothing() {
        assertEquals(Action.NONE, Standby.decide(0, canWriteSettings = true))
        assertEquals(Action.NONE, Standby.decide(0, canWriteSettings = false))
        assertEquals("off", Standby.describe(0, false))
    }

    @Test fun onIsTurnedOffWhenAllowed() {
        assertEquals(Action.TURN_OFF, Standby.decide(1, canWriteSettings = true))
    }

    @Test fun unsetCountsAsOn() {
        assertEquals(Action.TURN_OFF, Standby.decide(null, canWriteSettings = true))
        assertEquals(Action.NEEDS_PERMISSION, Standby.decide(null, canWriteSettings = false))
    }

    @Test fun onWithoutPermissionSaysHowToFix() {
        assertTrue(Standby.describe(1, false).contains("WRITE_SECURE_SETTINGS"))
    }
}
