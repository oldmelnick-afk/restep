package com.rocketglasses.soberyobratno

import org.junit.Assert.*
import org.junit.Test

class VoiceCommandsTest {
    @Test fun stripsOnlyExplicitTrailingCommands() {
        assertEquals(VoiceCommands.Submission("removed two screws", true),
            VoiceCommands.submission("removed two screws send note"))
        assertEquals(VoiceCommands.Submission("два винта", true),
            VoiceCommands.submission("два винта отправь фото"))
        assertEquals(VoiceCommands.Submission("", true), VoiceCommands.submission("send"))
        assertEquals(VoiceCommands.Submission("send the panel for repair", false),
            VoiceCommands.submission("send the panel for repair"))
        assertEquals(VoiceCommands.Submission("removed the sender", false),
            VoiceCommands.submission("removed the sender"))
    }

    @Test fun doesNotTurnSimilarWordsIntoCommands() {
        assertFalse(VoiceCommands.submission("removed sand").send)
        assertFalse(VoiceCommands.submission("сэнд").send)
        assertEquals("два винта", VoiceCommands.submission("два винта сэнд", true).note)
        assertEquals("removed two screws", VoiceCommands.submission("removed two screws sent", true).note)
    }

    @Test fun requiresWholeCommandAndConfidence() {
        assertTrue(VoiceCommands.isSendCommand("send", "en", 0.99))
        assertTrue(VoiceCommands.isSendCommand("send note", "en", 0.9))
        assertTrue(VoiceCommands.isSendCommand("отправь фото", "ru", 0.9))
        assertFalse(VoiceCommands.isSendCommand("send", "en", 0.5))
        assertFalse(VoiceCommands.isSendCommand("[unk] send", "en", 0.99))
        assertFalse(VoiceCommands.isSendCommand("sand", "en", 0.99))
    }
}
