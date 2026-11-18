package com.jmd.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCommandIdPolicyTest {
    @Test
    fun acceptsBuiltInCommandIds() {
        assertTrue(RemoteCommandIdPolicy.isValid("jsonPretty"))
        assertTrue(RemoteCommandIdPolicy.isValid("sql-in"))
        assertTrue(RemoteCommandIdPolicy.isValid("command_2"))
    }

    @Test
    fun acceptsCustomWorkflowIds() {
        assertTrue(RemoteCommandIdPolicy.isValid("custom:1234567890abcdef1234567890abcdef"))
        assertTrue(RemoteCommandIdPolicy.isValid("custom:test:1234567890abcdef1234567890abcdef"))
    }

    @Test
    fun encodesCustomWorkflowIdInExecutionPath() {
        assertEquals(
            "/api/v1/commands/custom%3A1234567890abcdef1234567890abcdef/execute",
            RemoteCommandIdPolicy.executePath("custom:1234567890abcdef1234567890abcdef")
        )
    }

    @Test
    fun rejectsEmptyOrUnsafeIds() {
        assertFalse(RemoteCommandIdPolicy.isValid(""))
        assertFalse(RemoteCommandIdPolicy.isValid("custom:workflow/execute"))
        assertFalse(RemoteCommandIdPolicy.isValid("custom:workflow id"))
        assertFalse(RemoteCommandIdPolicy.isValid("x".repeat(121)))
    }
}
