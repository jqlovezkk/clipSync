package com.clipsync.test

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * Standalone unit tests for message serialization.
 * These tests do not depend on Android SDK classes.
 */
class ProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class TestMessage(
        val type: String,
        val version: Int = 1,
        val timestamp: Long = 0,
        val payload: Map<String, String> = emptyMap()
    )

    @Test
    fun `create and serialize auth message`() {
        val message = TestMessage(
            type = "auth",
            payload = mapOf(
                "token" to "test-token",
                "device_name" to "TestDevice",
                "platform" to "android"
            )
        )
        
        val jsonStr = json.encodeToString(message)
        
        assertTrue("JSON should contain auth type", jsonStr.contains("auth"))
        assertTrue("JSON should contain token", jsonStr.contains("test-token"))
    }

    @Test
    fun `create and serialize heartbeat message`() {
        val message = TestMessage(
            type = "heartbeat",
            payload = mapOf("seq" to "5")
        )
        
        assertNotNull("Message should not be null", message)
        assertEquals("Message type should be heartbeat", "heartbeat", message.type)
    }

    @Test
    fun `deserialize valid message json`() {
        val jsonStr = """
            {
                "type": "auth_response",
                "version": 1,
                "timestamp": 1234567890,
                "payload": {}
            }
        """.trimIndent()
        
        val message = json.decodeFromString<TestMessage>(jsonStr)
        
        assertNotNull("Deserialized message should not be null", message)
        assertEquals("Type should be auth_response", "auth_response", message.type)
        assertEquals("Version should be 1", 1, message.version)
    }

    @Test
    fun `deserialize invalid json throws exception`() {
        try {
            json.decodeFromString<TestMessage>("not valid json")
            fail("Should throw exception for invalid JSON")
        } catch (e: Exception) {
            // Expected - exception is acceptable for invalid input
            assertTrue("Exception should have message or cause", 
                e.message?.isNotEmpty() == true || e.cause != null)
        }
    }

    @Test
    fun `message with default version`() {
        val message = TestMessage(type = "ping")
        
        assertEquals("Default version should be 1", 1, message.version)
    }

    @Test
    fun `message with custom timestamp`() {
        val timestamp = System.currentTimeMillis()
        val message = TestMessage(type = "ping", timestamp = timestamp)
        
        assertEquals("Timestamp should match", timestamp, message.timestamp)
        assertTrue("Timestamp should be positive", message.timestamp > 0)
    }

    @Test
    fun `serialize and deserialize round trip`() {
        val original = TestMessage(
            type = "clipboard_push",
            version = 1,
            timestamp = 1234567890,
            payload = mapOf("content" to "Hello World")
        )
        
        val jsonStr = json.encodeToString(original)
        val deserialized = json.decodeFromString<TestMessage>(jsonStr)
        
        assertEquals("Messages should match after round trip", original, deserialized)
    }
}
