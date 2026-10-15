package jp.trisplit

import org.junit.Assert.assertEquals
import org.junit.Test

class PresetTest {

    private val work = Preset("1", "Work", listOf("a.mail/a.mail.Main", "b.docs/b.docs.Main", "c.chat/c.chat.Main"))

    @Test
    fun `presets round-trip through the encoding`() {
        val presets = listOf(work, Preset("2", "Play", listOf("x/x.A", "y/y.B", "z/z.C")))
        assertEquals(presets, Preset.decode(Preset.encode(presets)))
    }

    @Test
    fun `empty or missing text decodes to no presets`() {
        assertEquals(emptyList<Preset>(), Preset.decode(null))
        assertEquals(emptyList<Preset>(), Preset.decode(""))
    }

    @Test
    fun `names lose tabs and newlines`() {
        val tricky = Preset("3", "a\tb\nc", listOf("x/x.A", "y/y.B", "z/z.C"))
        assertEquals("a b c", Preset.decode(Preset.encode(listOf(tricky))).single().name)
    }

    private val installed = setOf("a.mail", "b.docs", "c.chat")

    @Test
    fun `preset containing the partner offers its other two apps and the partner's column`() {
        val choices = work.splitChoices(partnerPackage = "b.docs", installed = installed)
        assertEquals(1, choices.partnerColumn)
        assertEquals(listOf("a.mail/a.mail.Main", "c.chat/c.chat.Main"), choices.candidates)
    }

    @Test
    fun `preset without the partner offers all three apps to choose from`() {
        val choices = work.splitChoices(partnerPackage = "q.other", installed = installed)
        assertEquals(null, choices.partnerColumn)
        assertEquals(work.components, choices.candidates)
    }

    @Test
    fun `uninstalled apps are not offered`() {
        val choices = work.splitChoices(partnerPackage = "b.docs", installed = setOf("a.mail", "b.docs"))
        assertEquals(listOf("a.mail/a.mail.Main"), choices.candidates)
    }
}
