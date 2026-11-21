package org.levimc.zaphre

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.levimc.zaphre.ai.*
import java.io.IOException

class KeyRotationTest {
    @Test fun switchesOnlyAfter429() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "5").setBody("rate limited"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"OK"}}]}"""))
            val settings = ActiveAiSettings(providerId = "custom_openai", protocol = ApiProtocol.OPENAI_COMPAT,
                baseUrl = server.url("/v1").toString().trimEnd('/'), apiKey = "test-first", additionalKeys = listOf("test-second"),
                autoSwitchKeys = true, model = "test")
            val reply = AiApiClient.chat(settings, "system", listOf(AgentTurnItem.User("hello")), emptyList())
            assertEquals("OK", reply.text)
            assertEquals("Bearer test-first", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer test-second", server.takeRequest().getHeader("Authorization"))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun doesNotSwitchOnServerError() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
            val settings = ActiveAiSettings(providerId = "custom_openai", protocol = ApiProtocol.OPENAI_COMPAT,
                baseUrl = server.url("/v1").toString().trimEnd('/'), apiKey = "test-first-503", additionalKeys = listOf("test-second-503"),
                autoSwitchKeys = true, model = "test")
            try {
                AiApiClient.chat(settings, "system", listOf(AgentTurnItem.User("hello")), emptyList())
                fail("Expected an error")
            } catch (_: IOException) { assertEquals(1, server.requestCount) }
        } finally { server.shutdown() }
    }
    @Test fun askModeHonorsDenial() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "5"))
            val settings = ActiveAiSettings(providerId = "custom_openai", protocol = ApiProtocol.OPENAI_COMPAT,
                baseUrl = server.url("/v1").toString().trimEnd('/'), apiKey = "ask-first", additionalKeys = listOf("ask-second"),
                askSwitchKeys = true, model = "test")
            var requested = 0
            try {
                AiApiClient.chat(settings, "system", listOf(AgentTurnItem.User("hello")), emptyList(), confirmKeySwitch = { requested = it; false })
                fail("Expected rejection")
            } catch (_: IOException) {
                assertEquals(2, requested)
                assertEquals(1, server.requestCount)
            }
        } finally { server.shutdown() }
    }
}
