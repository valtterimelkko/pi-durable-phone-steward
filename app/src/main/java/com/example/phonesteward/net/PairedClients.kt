package com.example.phonesteward.net

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import com.example.phonesteward.logic.PairedIdentity

/**
 * Every client this app talks to, bound to the paired identity as ONE value
 * (fix 01-1, hardened by fix 02-1): each client captures ONE immutable
 * [PairedIdentity] snapshot at creation — its base URL AND its token — so a
 * handle held across a rebind can never mix an old server with a new token.
 * On rebind the previous generation is closed: any further call through a
 * held handle throws `IllegalStateException("client closed")` without
 * touching the network, and in-flight calls are cancelled.
 */
class PairedClients(
    private val identityProvider: () -> PairedIdentity?,
    private val onIdentityChanged: () -> Unit = {},
    private val httpFactory: () -> OkHttpClient = { StewardApi.defaultClient() },
    private val streamHttpFactory: () -> OkHttpClient = { defaultStreamClient() },
) : AutoCloseable {

    /** Called by the identity owner (pairing, revocation, URL change). */
    fun rebind() {
        synchronized(this) {
            api?.close()
            api = null
            stream?.close()
            stream = null
        }
        onIdentityChanged()
    }

    fun client(): StewardApi {
        val identity = identityProvider()
        synchronized(this) {
            val current = api
            if (current != null && boundIdentity == identity) return current
            // Close the old generation first: a held handle must stop working.
            current?.close()
            // One immutable snapshot: URL and token from the SAME identity.
            val freshId = identity ?: PairedIdentity(NO_BASE_URL, "")
            val fresh = StewardApi(
                freshId.baseUrl,
                tokenProvider = { freshId.token },
                http = httpFactory(), // per generation, so close() poisons nothing else
            )
            boundIdentity = identity
            api = fresh
            return fresh
        }
    }

    /** The SSE stream client for the current identity; recreated on rebind. */
    fun streamClient(): ChatStreamClient {
        val identity = identityProvider()
        synchronized(this) {
            val current = stream
            if (current != null && boundStreamIdentity == identity) return current
            current?.close()
            val freshId = identity ?: PairedIdentity(NO_BASE_URL, "")
            val fresh = ChatStreamClient(
                freshId.baseUrl,
                tokenProvider = { freshId.token },
                http = streamHttpFactory(),
            )
            boundStreamIdentity = identity
            stream = fresh
            return fresh
        }
    }

    @Volatile
    private var api: StewardApi? = null

    @Volatile
    private var boundIdentity: PairedIdentity? = null

    @Volatile
    private var stream: ChatStreamClient? = null

    @Volatile
    private var boundStreamIdentity: PairedIdentity? = null

    override fun close() {
        api?.close()
        stream?.close()
    }

    companion object {
        /** Placeholder root while unpaired; the app never calls it. */
        const val NO_BASE_URL = "http://127.0.0.1:0"

        fun defaultStreamClient(): OkHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // streams stay open
            .build()
    }
}
