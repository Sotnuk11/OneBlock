package com.alytanta.oneblock.items

import com.alytanta.oneblock.combat.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.util.UUID

class ItemGrantJournalTest {
    @TempDir lateinit var folder:Path
    private fun record():ItemGrantRecord {
        val id=UUID.randomUUID();val items=listOf(FrozenCombatItem("IRON_SWORD","exact-pdc-bytes"))
        return ItemGrantRecord(id,"oneblock:warden_blade",1,"console",PreparedCombatReward(CombatRewardRequest("admin_items",id,UUID.randomUUID(),UUID.randomUUID(),"admin",PreparedCombatReward.hash(items)),items))
    }
    @Test fun `producer retains exact bytes before delivery across reopen and archive preserves dedup identity`() {
        val journal=ItemGrantJournal(folder);val record=record();journal.save(record)
        assertEquals(listOf(record),ItemGrantJournal(folder).pending());assertEquals(record,journal.existing(record.id))
        journal.acknowledge(record.id);assertTrue(journal.pending().isEmpty());assertEquals(record,journal.existing(record.id))
        journal.acknowledge(record.id);assertEquals(record,journal.read(journal.file(record.id,true)))
    }
    @Test fun `partial and malformed producer records cannot silently become a new grant`() {
        val journal=ItemGrantJournal(folder);Files.writeString(folder.resolve("incomplete.partial"),"{broken")
        assertTrue(journal.pending().isEmpty());val record=record();journal.save(record)
        val path=journal.file(record.id);Files.writeString(path,Files.readString(path).replace("exact-pdc-bytes","changed-pdc-bytes"))
        assertThrows(IllegalArgumentException::class.java) {journal.pending()}
    }
}
