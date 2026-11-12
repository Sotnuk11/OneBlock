package com.alytanta.oneblock.storage

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DatabaseSettingsTest {
    @Test fun `dotenv handles comments quotes and equals without expanding secrets`() {
        val parsed = DatabaseSettings.parse("# comment\nONEBLOCK_DB_HOST=localhost\nONEBLOCK_DB_PASSWORD='abc=# xyz'\nexport ONEBLOCK_DB_NAME=oneblock")
        assertEquals("abc=# xyz", parsed["ONEBLOCK_DB_PASSWORD"])
        assertEquals("oneblock", parsed["ONEBLOCK_DB_NAME"])
        assertThrows(IllegalArgumentException::class.java) { DatabaseSettings.parse("PASSWORD='unfinished") }
        assertThrows(IllegalArgumentException::class.java) { DatabaseSettings.parse("garbage") }
    }
    @Test fun `connection settings validate port and required credentials without printing the password`() {
        val values = mapOf("ONEBLOCK_DB_HOST" to "localhost", "ONEBLOCK_DB_NAME" to "oneblock", "ONEBLOCK_DB_USER" to "test", "ONEBLOCK_DB_PASSWORD" to "secret")
        assertFalse(DatabaseSettings.from(values).toString().contains("secret"))
        assertThrows(IllegalStateException::class.java) { DatabaseSettings.from(values - "ONEBLOCK_DB_PASSWORD") }
        assertThrows(IllegalArgumentException::class.java) { DatabaseSettings.from(values + ("ONEBLOCK_DB_PORT" to "99999")) }
        assertThrows(IllegalArgumentException::class.java) { DatabaseSettings.from(values + ("ONEBLOCK_DB_SSLMODE" to "unknown")) }
    }
}
