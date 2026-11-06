package com.example.phonesteward.logic

import kotlinx.serialization.Serializable
import com.example.phonesteward.net.ApiException
import com.example.phonesteward.net.PairResponse

/**
 * The paired identity: the server base URL and the device token, stored and
 * used as ONE value (fix 01-1). A token can never travel without its server
 * URL, so a rebind cannot send it to the wrong host.
 */
@Serializable
data class PairedIdentity(val baseUrl: String, val token: String)

/** Where the paired identity lives (Keystore-backed adapter on Android). */
interface IdentityStore {
    fun read(): PairedIdentity?
    fun write(identity: PairedIdentity)
    fun clear()
}

/**
 * Pairing and revocation (protocol §2), pure logic:
 * - `pair` exchanges the one-time code for a token and stores URL+token
 *   together as one identity, then notifies the owner of the change;
 * - any 401 — TOKEN_REVOKED, or an UNAUTHORIZED token the server no longer
 *   accepts — clears the identity so the app returns to its pairing screen;
 * - other errors leave the stored identity alone.
 */
class PairingFlow(
    private val store: IdentityStore,
    private val pairCall: (baseUrl: String, code: String, deviceName: String) -> PairResponse,
    private val onIdentityChanged: () -> Unit = {},
) {

    fun isPaired(): Boolean = store.read() != null

    fun identity(): PairedIdentity? = store.read()

    /** Returns the deviceId; URL+token are stored together in the [IdentityStore]. */
    fun pair(baseUrl: String, code: String, deviceName: String): String {
        require(code.isNotBlank()) { "pairing code is blank" }
        require(baseUrl.isNotBlank()) { "server address is blank" }
        val res = pairCall(baseUrl.trim().trimEnd('/'), code.trim(), deviceName)
        store.write(PairedIdentity(baseUrl.trim().trimEnd('/'), res.token))
        onIdentityChanged()
        return res.deviceId
    }

    /**
     * Handles an error from any authenticated call. Returns true when the
     * app must return to pairing (the identity was cleared).
     */
    fun onApiError(e: ApiException): Boolean {
        if (e.httpStatus == 401) {
            store.clear()
            onIdentityChanged()
            return true
        }
        return false
    }

    /** Owner-initiated unpair (e.g. before re-pairing a replacement device). */
    fun forget() {
        store.clear()
        onIdentityChanged()
    }
}
