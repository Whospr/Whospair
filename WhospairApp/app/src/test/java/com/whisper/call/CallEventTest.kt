package com.whisper.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CallEventTest {

    @Test fun `aller retour`() {
        val event = CallEvent(outgoing = true, answered = true, reason = EndReason.HUNG_UP, seconds = 151)
        assertEquals(event, CallEvent.parse(event.encode()))
    }

    @Test fun `un texte tape n'est jamais une trace d'appel`() {
        assertFalse(CallEvent.isCall("Appel sortant · 2:31"))
        assertNull(CallEvent.parse("|1|1|HUNG_UP|3"))
    }

    @Test fun `trace mal formee refusee`() {
        assertNull(CallEvent.parse(CallEvent.MARK + "|1|1|INCONNU|3"))
        assertNull(CallEvent.parse(CallEvent.MARK + "|1|1|HUNG_UP|-4"))
    }
}
