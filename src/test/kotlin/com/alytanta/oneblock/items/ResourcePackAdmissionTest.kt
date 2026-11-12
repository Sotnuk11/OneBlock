package com.alytanta.oneblock.items

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class ResourcePackAdmissionTest {
    @TempDir lateinit var directory:Path
    private fun settings(hash:String="a".repeat(40))=ResourcePackSettings(true,true,"https://pack.example.test/oneblock.zip",hash,10)
    @Test fun `accepted and downloaded do not unlock gameplay while applied status does`() {
        val gate=PackAdmission();gate.apply(settings());val player=UUID.randomUUID();val ticket=gate.offer(player)
        assertFalse(gate.allowed(player));gate.status(player,ticket.id,"ACCEPTED");assertFalse(gate.allowed(player))
        gate.status(player,ticket.id,"DOWNLOADED");assertFalse(gate.allowed(player));gate.status(player,ticket.id,"SUCCESSFULLY_LOADED");assertTrue(gate.allowed(player))
        gate.status(player,ticket.id,"ACCEPTED");assertTrue(gate.allowed(player))
    }
    @Test fun `failure timeout stale offer reconnect and changed pack cannot reuse previous readiness`() {
        val player=UUID.randomUUID();val gate=PackAdmission();gate.apply(settings())
        for(status in listOf("DECLINED","FAILED_DOWNLOAD","INVALID_URL","FAILED_RELOAD","DISCARDED")) {
            val t=gate.offer(player);assertTrue(gate.status(player,t.id,status));assertFalse(gate.allowed(player))
            assertFalse(gate.status(player,t.id,"SUCCESSFULLY_LOADED"));assertFalse(gate.allowed(player))
        }
        val stale=gate.offer(player);val current=gate.offer(player)
        assertFalse(gate.status(player,stale.id,"SUCCESSFULLY_LOADED"));assertFalse(gate.allowed(player))
        gate.status(player,current.id,"ACCEPTED");gate.status(player,current.id,"SUCCESSFULLY_LOADED");assertTrue(gate.allowed(player))
        gate.apply(settings("b".repeat(40)));assertFalse(gate.allowed(player));assertFalse(gate.status(player,current.id,"SUCCESSFULLY_LOADED"))
        val pending=gate.offer(player);pending.state=PackAdmission.State.FAILED;assertFalse(gate.status(player,pending.id,"SUCCESSFULLY_LOADED"))
        gate.remove(player);assertFalse(gate.allowed(player))
    }
    @Test fun `delivery cannot be enabled without a real URL hash and valid timeout`() {
        val file=directory.resolve("resource-pack.yml").toFile()
        file.writeText("version: 1\nenabled: false\nrequired: true\nurl: ''\nsha1: ''\ntimeout-seconds: 60\n")
        assertTrue(ResourcePackSettings.read(file).first!!.required)
        val original=file.readText();file.writeText(original.replace("enabled: false","enabled: true"))
        assertThrows(IllegalArgumentException::class.java) {ResourcePackSettings.read(file)}
        for(url in listOf("file:///secret.zip","https://user:secret@pack.example.test/pack.zip","https://[invalid")) {
            file.writeText("version: 1\nenabled: true\nrequired: true\nurl: '$url'\nsha1: '${"a".repeat(40)}'\ntimeout-seconds: 60\n")
            assertThrows(Exception::class.java) {ResourcePackSettings.read(file)}
        }
    }
    @Test fun `optional and disabled policy is explicit and never reuses another players ticket`() {
        val a=UUID.randomUUID();val b=UUID.randomUUID();val gate=PackAdmission();gate.apply(settings());val ticket=gate.offer(a)
        gate.status(a,ticket.id,"ACCEPTED");gate.status(a,ticket.id,"SUCCESSFULLY_LOADED");assertTrue(gate.allowed(a));assertFalse(gate.allowed(b))
        gate.apply(settings().copy(required=false));assertTrue(gate.allowed(b));gate.apply(ResourcePackSettings());assertTrue(gate.allowed(b))
    }
}
