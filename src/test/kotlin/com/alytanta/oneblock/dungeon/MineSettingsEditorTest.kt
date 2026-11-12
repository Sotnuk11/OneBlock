package com.alytanta.oneblock.dungeon

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*

class MineSettingsEditorTest {
    @TempDir lateinit var folder:Path
    @Test fun `admin edits nested death tier with backup preserves custom data and rejects stale or invalid candidates`() {
        val file=folder.resolve("dungeon.yml")
        val source=javaClass.getResourceAsStream("/dungeon.yml")!!.reader().readText()
        Files.writeString(file,source.replace("\"version\": 1,","\"version\": 1,\n\"operator-custom\": {\"keep\": \"my-value\"},"))
        val editor=MineSettingsEditor(file) {null};val view=editor.view()
        val next=editor.edit(view,"death-rewards[1].chance","0.6")
        assertEquals(.6,next.tiers[1].chance)
        val after=Files.readString(file);assertTrue(after.contains("my-value"))
        assertTrue(Files.list(folder).use {paths->paths.anyMatch {it.fileName.toString().contains("before-admin")}})
        assertThrows(IllegalStateException::class.java) {editor.edit(view,"attempts","4")}
        assertThrows(IllegalArgumentException::class.java) {editor.edit(editor.view(),"attempts","0")}
        assertEquals(after,Files.readString(file))
        assertThrows(IllegalArgumentException::class.java) {editor.edit(editor.view(),"operator-custom.keep","overwritten")}
        assertTrue(Files.list(folder).use {paths->paths.noneMatch {it.fileName.toString().contains(".candidate-")}})
    }
}
