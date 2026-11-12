package com.alytanta.oneblock.release

/** Recipe IDs may differ from output item IDs. Display labels never establish item identity. */
object ReleaseLabels {
    fun name(rules:ReleaseRules?,id:String,text:(String)->String):String {
        rules?.customItems?.find(id)?.let {return it.name}
        rules?.combat?.items?.get(id)?.let {return it.name}
        val key="release.name.$id";val localized=text(key)
        if(localized!=key)return localized
        val output=rules?.recipes?.get(id)?.items?.firstOrNull()
        output?.tool?.let {tool->rules?.customItems?.find(tool)?.let {return it.name};rules?.combat?.items?.get(tool)?.let {return it.name}}
        return output?.material?.let {material(it,text)}?:id.replace('_',' ').replaceFirstChar {it.uppercase()}
    }
    fun material(material:String,text:(String)->String):String {
        val key="release.item.${material.lowercase()}";val label=text(key)
        return if(label==key)material.lowercase().replace('_',' ').replaceFirstChar {it.uppercase()} else label
    }
}
