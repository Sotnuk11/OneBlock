package com.alytanta.oneblock.items

/** Pure URL/base64 parsing. No request is made while loading YAML or styling an item. */
object HeadTextures {
    fun url(value:String):String? {
        val raw=if(value.startsWith("http://") || value.startsWith("https://"))value else runCatching {
            val json=com.google.gson.JsonParser.parseString(java.util.Base64.getMimeDecoder().decode(value).toString(Charsets.UTF_8)).asJsonObject
            json.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").asString
        }.getOrNull()?:return null
        if(!raw.matches(Regex("https?://textures\\.minecraft\\.net/texture/[0-9a-fA-F]+")))return null
        return raw.replaceFirst("http://","https://")
    }
}
