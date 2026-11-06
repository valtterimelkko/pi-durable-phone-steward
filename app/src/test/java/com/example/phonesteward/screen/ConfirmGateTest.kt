package com.example.phonesteward.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 13.3 confirm gate: committing words ask the owner; Clear storage / Clear data are never allowed. */
class ConfirmGateTest {

    private fun verdict(vararg labels: String) = ConfirmGate.classify(labels.toList())

    @Test
    fun `an ordinary label is allowed`() {
        assertEquals(GateVerdict.Allow, verdict("Storage"))
        assertEquals(GateVerdict.Allow, verdict("Network & internet", "Open"))
        assertEquals(GateVerdict.Allow, verdict())
    }

    @Test
    fun `clear cache is allowed`() {
        assertEquals(GateVerdict.Allow, verdict("Clear cache"))
        assertEquals(GateVerdict.Allow, verdict("CLEAR CACHE"))
        assertEquals(GateVerdict.Allow, verdict("Tyhjennä välimuisti"))
    }

    @Test
    fun `the committing words from the plan ask the owner`() {
        val words = listOf(
            "Uninstall", "Delete", "Remove", "Pay", "Buy", "Send", "Install", "Allow", "Grant",
            "Sign in", "Log out", "Reset", "Force stop", "Disable",
        )
        for (word in words) {
            val v = verdict(word)
            assertTrue("$word should ask the owner, got $v", v is GateVerdict.Confirm)
        }
    }

    @Test
    fun `partial matches inside a longer label also ask`() {
        assertTrue(verdict("Delete all 3 files") is GateVerdict.Confirm)
        assertTrue(verdict("Uninstall updates") is GateVerdict.Confirm)
        assertTrue(verdict("Allow all the time") is GateVerdict.Confirm)
        assertTrue(verdict("Permanently remove from this phone") is GateVerdict.Confirm)
        assertTrue(verdict("Reset network settings") is GateVerdict.Confirm)
        assertTrue(verdict("Pay now") is GateVerdict.Confirm)
    }

    @Test
    fun `a word inside another word does not ask`() {
        assertEquals(GateVerdict.Allow, verdict("Payload viewer"))
        assertEquals(GateVerdict.Allow, verdict("Sender settings"))
        assertEquals(GateVerdict.Allow, verdict("Buyer guide"))
    }

    @Test
    fun `localised committing words ask too`() {
        assertTrue(verdict("Poista") is GateVerdict.Confirm)          // Finnish: delete / remove
        assertTrue(verdict("Poista asennus") is GateVerdict.Confirm)  // Finnish: uninstall
        assertTrue(verdict("Lähetä") is GateVerdict.Confirm)          // Finnish: send
        assertTrue(verdict("Asenna") is GateVerdict.Confirm)          // Finnish: install
        assertTrue(verdict("Salli") is GateVerdict.Confirm)           // Finnish: allow
        assertTrue(verdict("Kirjaudu ulos") is GateVerdict.Confirm)   // Finnish: log out
        assertTrue(verdict("Löschen") is GateVerdict.Confirm)         // German
        assertTrue(verdict("Désinstaller") is GateVerdict.Confirm)    // French
        assertTrue(verdict("Eliminar") is GateVerdict.Confirm)        // Spanish
        assertTrue(verdict("Avinstallera") is GateVerdict.Confirm)    // Swedish
    }

    @Test
    fun `clear storage and clear data are never allowed, even with confirmation`() {
        for (label in listOf("Clear storage", "Clear data", "CLEAR DATA", "Clear all data", "Clear Storage & Cache", "Delete all data", "Factory reset", "Erase all data")) {
            val v = verdict(label)
            assertTrue("$label must be Never, got $v", v is GateVerdict.Never)
        }
    }

    @Test
    fun `localised clear data is never allowed`() {
        assertTrue(verdict("Tyhjennä tiedot") is GateVerdict.Never)
        assertTrue(verdict("Tyhjennä tallennustila") is GateVerdict.Never)
        assertTrue(verdict("Daten löschen") is GateVerdict.Never)
        assertTrue(verdict("Speicher löschen") is GateVerdict.Never)
        assertTrue(verdict("Effacer les données") is GateVerdict.Never)
        assertTrue(verdict("Borrar datos") is GateVerdict.Never)
        assertTrue(verdict("Rensa data") is GateVerdict.Never)
    }

    @Test
    fun `never beats confirm when both appear`() {
        assertTrue(verdict("Delete", "Clear data") is GateVerdict.Never)
    }

    @Test
    fun `the matched word is reported for the owner prompt`() {
        val v = verdict("Uninstall WhatsApp") as GateVerdict.Confirm
        assertEquals("uninstall", v.word)
    }

    @Test
    fun `punctuation and case do not hide a word`() {
        assertTrue(verdict("DELETE!") is GateVerdict.Confirm)
        assertTrue(verdict("  sign   in  ") is GateVerdict.Confirm)
        assertTrue(verdict("Log-out") is GateVerdict.Confirm)
    }

    // ---- N33: chat wiping, leaving, ordering ----

    @Test
    fun `clear chat and the other new committing words ask the owner`() {
        val labels = listOf(
            "Clear chat", "Clear all chats", "Leave", "Leave group", "Exit group", "Archive", "Archive chat", "Call", "Voice call",
            "Video call", "Order", "Place order", "Checkout", "Submit", "Report", "Report and block",
            // Finnish, Swedish, German, French, Spanish
            "Tyhjennä keskustelu", "Poistu ryhmästä", "Arkistoi", "Soita", "Tee tilaus", "Kassalle",
            "Rensa chatt", "Lämna grupp", "Arkivera", "Beställ", "Gå till kassan",
            "Chat leeren", "Gruppe verlassen", "Archivieren", "Anrufen", "Bestellen", "Zur Kasse",
            "Vider la discussion", "Quitter le groupe", "Archiver", "Appeler", "Commander", "Signaler",
            "Vaciar chat", "Salir del grupo", "Archivar", "Llamar", "Realizar pedido", "Finalizar compra", "Denunciar",
        )
        for (label in labels) {
            val v = verdict(label)
            assertTrue("$label should ask the owner, got $v", v is GateVerdict.Confirm)
        }
    }

    @Test
    fun `clear chat names the word it matched`() {
        assertEquals("clear", (verdict("Clear chat") as GateVerdict.Confirm).word)
        assertEquals("place order", (verdict("Place order") as GateVerdict.Confirm).word)
    }

    @Test
    fun `clear cache stays allowed in every language and clear storage stays never`() {
        for (label in listOf("Clear cache", "Clear app cache", "Tyhjennä välimuisti", "Rensa cache", "Cache leeren", "Vider le cache", "Limpiar caché", "Borrar caché")) {
            assertEquals("$label must stay allowed", GateVerdict.Allow, verdict(label))
        }
        for (label in listOf("Clear storage", "Clear data", "Clear cache and data", "Clear cache & data")) {
            assertTrue("$label must be Never", verdict(label) is GateVerdict.Never)
        }
    }

    @Test
    fun `new words do not fire inside longer words`() {
        for (label in listOf("Calendar", "Calls", "Ordered list", "Orders", "Archived chats", "Clearance sale", "Cleared", "Reports", "Leaves", "Callback", "Submitted", "Disorder", "Recall")) {
            assertEquals("$label must stay allowed", GateVerdict.Allow, verdict(label))
        }
    }

    // ---- N33: generic acknowledgements judged by the dialog around them ----

    private fun withDialog(label: String, vararg dialog: String) = ConfirmGate.classify(listOf(label), dialog.toList())

    @Test
    fun `ok under an uninstall question asks and names uninstall`() {
        assertEquals(GateVerdict.Confirm("uninstall"), withDialog("OK", "Uninstall this app?", "Cancel"))
    }

    @Test
    fun `ok on a plain information dialog is allowed`() {
        assertEquals(GateVerdict.Allow, withDialog("OK", "Backup finished", "Your chats were saved", "Cancel"))
    }

    @Test
    fun `ok under a wipe question is never`() {
        assertTrue(withDialog("OK", "Clear data?", "All of the app's data will be deleted") is GateVerdict.Never)
    }

    @Test
    fun `every generic acknowledgement takes its dialog's words`() {
        for (ack in listOf("OK", "Yes", "Continue", "Confirm", "Accept", "Proceed", "Kyllä", "Jatka", "Ja", "Weiter", "Bestätigen", "Oui", "Continuer", "Sí", "Aceptar")) {
            assertEquals("$ack under a delete question", GateVerdict.Confirm("delete"), withDialog(ack, "Delete this chat?"))
        }
    }

    @Test
    fun `a clear cache dialog does not make its ok a committing tap`() {
        assertEquals(GateVerdict.Allow, withDialog("OK", "Clear cache?", "Cancel"))
    }

    @Test
    fun `a label that is not an acknowledgement ignores the window's other words`() {
        assertEquals(GateVerdict.Allow, withDialog("Storage", "Uninstall this app?"))
        assertEquals(GateVerdict.Allow, withDialog("Done", "Delete this chat?"))
    }

    @Test
    fun `a committing label stays committing with or without a dialog`() {
        assertEquals(GateVerdict.Confirm("delete"), withDialog("Delete", "Info"))
    }
}
