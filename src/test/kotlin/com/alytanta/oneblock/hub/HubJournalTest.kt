package com.alytanta.oneblock.hub

import com.alytanta.oneblock.hub.generator.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.util.UUID

class HubJournalTest {
    @TempDir lateinit var directory:Path
    private fun plan()=HubTemplate.plan(HubTarget("test:journal",UUID.randomUUID(),0,100,0),HubGeneratorSettings.read(directory.resolve("none.yml").toFile()).first)
    @Test fun `temporary replacement denial retries same forced bytes without publishing partial intent`() {
        var denied=0;var active=false;var prior="";val pauses=mutableListOf<Long>()
        val journal=HubJournal(directory,{from,to->
            if(active && denied++<3) {
                assertEquals(prior,Files.readString(to));assertTrue(Files.size(from)>0)
                throw AccessDeniedException(from.toString(),to.toString(),null)
            }
            Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);Unit
        },{pauses+=it})
        val plan=plan();val job=HubJob(UUID.randomUUID(),"test",plan.target,plan.hash(),plan.configHash,plan.blocks.size)
        journal.create(job,plan);prior=Files.readString(directory.resolve("job-${job.id}.json"));active=true
        val next=job.copy(intent=256);journal.save(next)
        assertEquals(listOf(20L,40L,80L),pauses);assertEquals(next,journal.load().single())
        assertFalse(Files.exists(directory.resolve("job-${job.id}.json.tmp")))
    }
    @Test fun `permanent denial keeps previous committed job and propagates failure after bounded retries`() {
        var active=false;var attempts=0
        val journal=HubJournal(directory,{from,to->
            if(active) {attempts++;throw AccessDeniedException(from.toString(),to.toString(),null)}
            Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);Unit
        },{})
        val plan=plan();val job=HubJob(UUID.randomUUID(),"test",plan.target,plan.hash(),plan.configHash,plan.blocks.size)
        journal.create(job,plan);active=true
        assertThrows(AccessDeniedException::class.java) {journal.save(job.copy(intent=256))}
        assertEquals(6,attempts);assertEquals(job,journal.load().single())
    }
}
