package com.otakustream.core.sources.api

fun stableSourceId(vararg parts: String): Long {
    var hash = 1_125_899_906_842_597L
    for (char in parts.joinToString("/")) hash = 31 * hash + char.code
    return hash
}
