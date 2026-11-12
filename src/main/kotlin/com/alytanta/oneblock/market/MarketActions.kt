package com.alytanta.oneblock.market

/** One pending entity-thread action per view. An ignored/failed click never consumes its other buttons. */
internal class MarketActions<T>(private val position: (Int) -> Int? = { it }, private val identity:(Int)->String = {it.toString()}) {
    private val entries = mutableMapOf<String, (T) -> Unit>()
    private val rendered = mutableMapOf<Int,String>()
    private val sources=mutableMapOf<String,Int>()
    private var queued = false

    fun set(slot: Int, action: ((T) -> Unit)?) {
        val id=identity(slot)
        rendered.entries.removeIf {it.value==id}
        val target = position(slot) ?: run {entries.remove(id);sources.remove(id);return}
        sources[id]=slot
        rendered[target]=id
        if (action == null) entries.remove(id) else entries[id] = action
    }
    fun acquire(slot: Int): ((T) -> Unit)? {
        if (queued) return null
        val id=rendered[slot]?:return null
        if(sources[id]?.let(position)!=slot)return null
        return entries[id]?.let(::acquireExternal)
    }
    fun acquireExternal(action: (T) -> Unit): ((T) -> Unit)? = if (queued) null else action.also { queued = true }
    fun release() { queued = false }
}
