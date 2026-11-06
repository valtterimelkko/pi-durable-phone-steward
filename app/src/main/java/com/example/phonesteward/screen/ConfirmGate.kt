package com.example.phonesteward.screen

import java.text.Normalizer
import java.util.Locale

sealed interface GateVerdict {
    /** No committing word: the tap goes ahead. */
    data object Allow : GateVerdict

    /** A committing word: the app pauses and asks the owner, naming [word]. */
    data class Confirm(val word: String) : GateVerdict

    /** Wipes app data or the phone: refused even with the owner's yes. Only "Clear cache" is allowed. */
    data class Never(val word: String) : GateVerdict
}

/**
 * The confirm gate: before any tap on a node whose text matches a committing
 * word, the app asks the owner. The lists are a tested resource in English, Finnish, Swedish,
 * German, French and Spanish (the phone's UI language may differ from the owner's).
 *
 * Matching is by whole words at the start of a word, so "Delete all 3 files" and
 * "Allow all the time" match while "Payload" and "Sender" do not.
 */
object ConfirmGate {

    /** Phrases that wipe data: never allowed. */
    val neverPhrases: List<String> = listOf(
        // English
        "clear storage", "clear data", "clear all data", "clear app data", "delete all data", "erase all data",
        "erase everything", "wipe data", "factory reset", "reset phone", "reset device",
        // "Clear cache" is allowed, but not when it is bundled with data or storage.
        "clear cache data", "clear cache and data", "clear cache storage",
        // Finnish
        "tyhjennä tallennustila", "tyhjennä tiedot", "tyhjennä kaikki tiedot", "tyhjennä sovelluksen tiedot",
        "tehdasasetusten palautus", "palauta tehdasasetukset", "nollaa puhelin",
        // Swedish
        "rensa lagring", "rensa data", "rensa all data", "återställ till fabriksinställningar", "rensa lagringsutrymme",
        // German
        "speicher löschen", "daten löschen", "alle daten löschen", "speicherplatz löschen", "auf werkseinstellungen zurücksetzen", "werkseinstellungen",
        // French
        "effacer le stockage", "effacer les données", "effacer toutes les données", "vider le stockage", "supprimer les données", "réinitialisation d'usine",
        // Spanish
        "borrar almacenamiento", "borrar datos", "borrar todos los datos", "eliminar datos", "restablecer datos de fábrica",
    )

    /** Words that commit something: the owner is asked first. */
    val committingWords: List<String> = listOf(
        // English
        "uninstall", "delete", "remove", "pay", "buy", "purchase", "send", "install", "allow", "grant",
        "sign in", "log in", "login", "log out", "logout", "sign out", "reset", "disable", "force stop", "erase",
        "format", "subscribe", "confirm payment", "transfer money", "empty trash", "block",
        // Wiping a chat, leaving a group, ordering, submitting, calling (N33).
        "clear", "leave", "exit group", "archive", "call", "order", "place order", "checkout", "submit", "report",
        // Finnish
        "poista", "asennuksen poisto", "maksa", "osta", "lähetä", "asenna", "salli", "myönnä", "kirjaudu sisään",
        "kirjaudu ulos", "nollaa", "poista käytöstä", "pakota pysäytys", "tyhjennä roskakori", "tilaa",
        "tyhjennä", "poistu", "arkistoi", "soita", "tilaus", "kassalle", "ilmianna", "raportoi",
        // Swedish
        "avinstallera", "ta bort", "radera", "betala", "köp", "skicka", "installera", "tillåt", "logga in", "logga ut", "återställ",
        "rensa", "lämna", "arkivera", "ring", "beställ", "till kassan", "rapportera",
        // German
        "löschen", "deinstallieren", "entfernen", "bezahlen", "kaufen", "senden", "installieren", "zulassen", "erlauben", "anmelden", "abmelden", "zurücksetzen",
        "leeren", "verlassen", "archivieren", "anrufen", "bestellen", "zur kasse", "melden", "absenden", "einreichen",
        // French
        "désinstaller", "supprimer", "retirer", "payer", "acheter", "envoyer", "installer", "autoriser", "se connecter", "se déconnecter", "réinitialiser",
        "effacer", "vider", "quitter", "archiver", "appeler", "commander", "passer la commande", "signaler", "valider la commande",
        // Spanish
        "desinstalar", "eliminar", "borrar", "pagar", "comprar", "enviar", "instalar", "permitir", "iniciar sesión", "cerrar sesión", "restablecer",
        "vaciar", "limpiar", "salir", "abandonar", "archivar", "llamar", "pedir", "realizar pedido", "finalizar compra", "denunciar", "reportar",
    )

    /**
     * Short words that must match as whole words only: "Calendar", "Ordered list", "Archived chats" and "Clearance sale"
     * are not commands. The rule that lets a 5+ letter last word carry a suffix does not apply to these.
     */
    private val exactOnly: Set<String> = setOf(
        "clear", "leave", "archive", "call", "order", "submit", "report",
        "tyhjennä", "poistu", "arkistoi", "soita", "tilaus", "kassalle", "ilmianna", "raportoi",
        "rensa", "lämna", "arkivera", "ring", "beställ", "rapportera",
        "leeren", "verlassen", "archivieren", "anrufen", "bestellen", "melden", "absenden", "einreichen",
        "effacer", "vider", "quitter", "archiver", "appeler", "commander", "signaler",
        "vaciar", "limpiar", "salir", "abandonar", "archivar", "llamar", "pedir", "denunciar", "reportar",
    )

    /** The one clearing action the steward may do: it is taken out of a label before the committing words are looked for. */
    private val allowedPhrases: List<String> = listOf(
        "clear cache", "clear app cache", "clear the cache",
        "tyhjennä välimuisti", "tyhjennä sovelluksen välimuisti",
        "rensa cache", "rensa cacheminne", "rensa cacheminnet", "rensa appens cache",
        "cache leeren", "cache löschen", "app cache leeren",
        "vider le cache", "effacer le cache", "vider cache",
        "limpiar caché", "limpiar cache", "borrar caché", "borrar cache", "vaciar caché", "vaciar cache",
    )

    /**
     * Labels that only say yes: what they commit to is in the dialog around them, so the window's other texts are
     * judged too (N33). "Done" is deliberately not here.
     */
    private val acknowledgements: Set<String> = setOf(
        "ok", "okay", "yes", "continue", "confirm", "accept", "proceed", "agree",
        "kyllä", "jatka", "vahvista", "hyväksy",
        "ja", "fortsätt", "bekräfta", "godkänn", "acceptera",
        "weiter", "bestätigen", "akzeptieren", "zustimmen",
        "oui", "continuer", "confirmer", "accepter", "valider",
        "sí", "si", "continuar", "confirmar", "aceptar",
    )

    fun normalise(label: String): String {
        val decomposed = label.lowercase(Locale.ROOT)
            .replace('’', '\'')
            .replace(Regex("[^\\p{L}\\p{N}']+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        return Normalizer.normalize(decomposed, Normalizer.Form.NFC)
    }

    private fun matchesAt(label: String, phrase: String): Boolean {
        val exact = phrase in exactOnly
        val tokens = label.split(' ')
        val words = phrase.split(' ')
        for (i in 0..(tokens.size - words.size)) {
            var ok = true
            for (j in words.indices) {
                val t = tokens[i + j]
                val w = words[j]
                val last = j == words.lastIndex
                // The last word of a phrase may carry a suffix ("deleted", "poistaa"), but only for words of 5+ letters.
                ok = if (last && w.length >= 5 && !exact) t.startsWith(w) else t == w
                if (!ok) break
            }
            if (ok) return true
        }
        return false
    }

    private val allowedPatterns: List<Regex> = allowedPhrases.map { Regex("(^| )" + Regex.escape(normalise(it)) + "( |$)") }

    private fun withoutAllowed(label: String): String =
        allowedPatterns.fold(label) { text, pattern -> pattern.replace(text, " ").trim() }

    /** The first phrase of [phrases] found in any of [labels], or null. */
    private fun find(labels: List<String>, phrases: List<String>, dropAllowed: Boolean = false): String? {
        val normalised = labels.map { normalise(it) }.map { if (dropAllowed) withoutAllowed(it) else it }.filter { it.isNotEmpty() }
        // Longest phrase first, so "force stop" is reported as such rather than by a shorter word.
        for (phrase in phrases.sortedByDescending { it.length }) {
            val p = normalise(phrase)
            if (normalised.any { matchesAt(it, p) }) return p
        }
        return null
    }

    private fun classifyLabels(labels: List<String>): GateVerdict {
        find(labels, neverPhrases)?.let { return GateVerdict.Never(it) }
        find(labels, committingWords, dropAllowed = true)?.let { return GateVerdict.Confirm(it) }
        return GateVerdict.Allow
    }

    private fun isAcknowledgement(labels: List<String>): Boolean = labels.any { normalise(it) in acknowledgements }

    /**
     * Classify the labels (text and description) of the node about to be tapped. When the tap is a bare "OK" or "Yes",
     * [windowTexts] (the other visible texts of the same window: a dialog's title and message) are judged as well, so
     * "OK" under "Uninstall this app?" asks the owner.
     */
    fun classify(labels: List<String>, windowTexts: List<String> = emptyList()): GateVerdict {
        val own = classifyLabels(labels)
        if (own != GateVerdict.Allow || windowTexts.isEmpty() || !isAcknowledgement(labels)) return own
        return classifyLabels(windowTexts)
    }
}
