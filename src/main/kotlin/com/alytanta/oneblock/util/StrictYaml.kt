package com.alytanta.oneblock.util

/** Reject duplicate mapping keys (including duplicate slots/IDs) before Bukkit flattens them. */
object StrictYaml {
    fun validate(source: String) {
        val options=org.yaml.snakeyaml.LoaderOptions().apply { isAllowDuplicateKeys=false }
        org.yaml.snakeyaml.Yaml(org.yaml.snakeyaml.constructor.SafeConstructor(options)).load<Any?>(source)
    }
}
