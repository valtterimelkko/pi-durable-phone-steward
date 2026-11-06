package com.example.phonesteward.logic

/**
 * Structural guard for the test buttons: an action such as trashing may take
 * only a URI this app created and recorded. Anything else is refused, so a
 * bug in the UI can never reach media the app did not make.
 */
class OwnItemsGuard {

    private val owned = LinkedHashSet<String>()

    /** URIs the app created, in creation order. */
    val ownedUris: List<String> get() = owned.toList()

    fun record(uri: String) {
        require(!uri.isBlank()) { "blank uri" }
        owned.add(uri)
    }

    fun forget(uri: String) {
        owned.remove(uri)
    }

    /** Throws unless [uri] is in the app's own created-items list. */
    fun requireOwned(uri: String) {
        check(uri in owned) { "Refusing: media item was not created by this app: $uri" }
    }
}
