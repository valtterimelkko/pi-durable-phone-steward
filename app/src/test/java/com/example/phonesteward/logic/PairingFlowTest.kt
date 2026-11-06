package com.example.phonesteward.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.logic.IdentityStore
import com.example.phonesteward.logic.PairedIdentity
import com.example.phonesteward.net.ApiException
import com.example.phonesteward.net.PairResponse

class PairingFlowTest {

    private class FakeStore : IdentityStore {
        var value: PairedIdentity? = null
        var changes = 0
        override fun read(): PairedIdentity? = value
        override fun write(identity: PairedIdentity) { value = identity; changes++ }
        override fun clear() { value = null; changes++ }
    }

    private fun flow(
        store: FakeStore,
        pairCall: (baseUrl: String, code: String, deviceName: String) -> PairResponse,
        onChanged: () -> Unit = {},
    ) = PairingFlow(store, pairCall, onChanged)

    @Test
    fun `pair stores the URL and token as one identity and notifies`() {
        val store = FakeStore()
        var notified = 0
        val f = flow(store, { baseUrl, code, device ->
            assertEquals("https://phone.example", baseUrl)
            assertEquals("K7Q4-9MZD", code)
            assertEquals("Example Phone", device)
            PairResponse("dev_1", "tok-43")
        }) { notified++ }
        assertFalse(f.isPaired())
        assertEquals("dev_1", f.pair("https://phone.example/", "K7Q4-9MZD", "Example Phone"))
        assertEquals(PairedIdentity("https://phone.example", "tok-43"), store.value)
        assertTrue(f.isPaired())
        assertEquals(1, notified)
    }

    @Test
    fun `an invalid code propagates and stores nothing`() {
        val store = FakeStore()
        val f = flow(store, { _, _, _ ->
            throw ApiException(400, "PAIRING_CODE_INVALID", "no detail on purpose")
        })
        try {
            f.pair("https://s", "WRONG-CODE", "d")
            throw AssertionError("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("PAIRING_CODE_INVALID", e.code)
        }
        assertEquals(null, store.value)
    }

    @Test
    fun `401 TOKEN_REVOKED clears the token and returns the app to pairing`() {
        val store = FakeStore().apply { value = PairedIdentity("https://s", "tok") }
        var notified = 0
        val flow = flow(store, { _, _, _ -> PairResponse("d", "t") }) { notified++ }
        val backToPairing = flow.onApiError(ApiException(401, "TOKEN_REVOKED"))
        assertTrue(backToPairing)
        assertFalse(flow.isPaired())
        assertEquals(1, notified)
    }

    @Test
    fun `401 UNAUTHORIZED also clears (the server no longer accepts the token)`() {
        val store = FakeStore().apply { value = PairedIdentity("https://s", "tok") }
        val flow = flow(store, { _, _, _ -> PairResponse("d", "t") })
        assertTrue(flow.onApiError(ApiException(401, "UNAUTHORIZED")))
        assertFalse(flow.isPaired())
    }

    @Test
    fun `other statuses keep the token`() {
        val store = FakeStore().apply { value = PairedIdentity("https://s", "tok") }
        val flow = flow(store, { _, _, _ -> PairResponse("d", "t") })
        assertFalse(flow.onApiError(ApiException(429, "RATE_LIMITED", retryAfterSeconds = 30)))
        assertFalse(flow.onApiError(ApiException(500, "UNKNOWN")))
        assertTrue(flow.isPaired())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a blank code is refused before any call`() {
        flow(FakeStore(), { _, _, _ -> PairResponse("d", "t") }).pair("https://s", "  ", "d")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a blank server address is refused before any call`() {
        flow(FakeStore(), { _, _, _ -> PairResponse("d", "t") }).pair("  ", "K7Q4-9MZD", "d")
    }

    @Test
    fun `forget clears the identity and notifies`() {
        val store = FakeStore().apply { value = PairedIdentity("https://s", "tok") }
        var notified = 0
        val f = flow(store, { _, _, _ -> PairResponse("d", "t") }) { notified++ }
        f.forget()
        assertFalse(f.isPaired())
        assertEquals(1, notified)
    }
}
