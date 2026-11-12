package com.alytanta.oneblock.storage

import java.nio.file.Files
import java.nio.file.Path

/** Deliberately not a data class: credentials must never appear in generated toString/log output. */
class DatabaseSettings(val host: String, val port: Int, val database: String, val user: String,
    val password: String, val sslMode: String, val channelBinding: String, val timeoutSeconds: Int, val schema: String = "oneblock",
    val socketTimeoutSeconds: Int = 30, val heartbeatSeconds: Int = 10, val retrySeconds: Int = 5) {
    companion object {
        fun parse(text: String): Map<String, String> = text.lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }.associate { line ->
                val parts = line.removePrefix("export ").split('=', limit = 2)
                require(parts.size == 2 && parts[0].trim().matches(Regex("[A-Z_][A-Z0-9_]*"))) { "Invalid .env entry" }
                val raw = parts[1].trim()
                parts[0].trim() to if (raw.startsWith('"') || raw.startsWith('\'')) {
                    require(raw.length >= 2 && raw.last() == raw.first()) { "Unclosed .env value" }; raw.substring(1, raw.lastIndex)
                } else raw
            }
        fun load(path: Path, environment: Map<String, String> = System.getenv()): DatabaseSettings =
            from(parse(Files.readString(path)) + environment.filterKeys { it.startsWith("ONEBLOCK_DB_") })
        fun from(values: Map<String, String>): DatabaseSettings {
            fun required(key: String) = values["ONEBLOCK_DB_$key"]?.takeIf(String::isNotBlank)
                ?: error("Set ONEBLOCK_DB_$key in plugins/one-block/.env")
            val host = required("HOST")
            require(host.matches(Regex("[A-Za-z0-9._:-]+"))) { "Invalid database host" }
            val database = required("NAME"); require(database.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid database name" }
            val port = (values["ONEBLOCK_DB_PORT"] ?: "5432").toInt().also { require(it in 1..65535) }
            val ssl = values["ONEBLOCK_DB_SSLMODE"] ?: "prefer"
            require(ssl in setOf("disable", "allow", "prefer", "require", "verify-ca", "verify-full")) { "Invalid SSL mode" }
            val channelBinding = values["ONEBLOCK_DB_CHANNEL_BINDING"] ?: "prefer"
            require(channelBinding in setOf("disable", "prefer", "require")) { "Invalid channel binding mode" }
            val timeout = (values["ONEBLOCK_DB_TIMEOUT_SECONDS"] ?: "10").toInt().also { require(it in 1..120) }
            val schema = (values["ONEBLOCK_DB_SCHEMA"] ?: "oneblock").also { require(it.matches(Regex("[a-z][a-z0-9_]{0,62}")) && it !in setOf("public", "pg_catalog", "information_schema")) }
            fun seconds(key:String,default:Int,min:Int,max:Int)=(values["ONEBLOCK_DB_$key"]?:default.toString()).toInt().also { require(it in min..max) }
            return DatabaseSettings(host, port, database, required("USER"), required("PASSWORD"), ssl, channelBinding, timeout, schema,
                seconds("SOCKET_TIMEOUT_SECONDS",30,5,300),seconds("HEARTBEAT_SECONDS",10,1,60),seconds("RETRY_SECONDS",5,1,60))
        }
    }
}
