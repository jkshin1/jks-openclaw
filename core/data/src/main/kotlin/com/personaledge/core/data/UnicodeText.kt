package com.personaledge.core.data

/**
 * Returns at most [maximumCodePoints] without ever splitting a UTF-16 surrogate pair.
 *
 * Kotlin's `String.take` counts UTF-16 code units. That is fine for ASCII identifiers, but it can
 * leave half an emoji in user/provider text. Storage boundaries use this helper instead.
 */
fun String.takeCodePoints(maximumCodePoints: Int): String {
    require(maximumCodePoints >= 0)
    if (maximumCodePoints == 0 || isEmpty()) return ""
    if (codePointCount(0, length) <= maximumCodePoints) return this
    return substring(0, offsetByCodePoints(0, maximumCodePoints))
}

/**
 * Returns the longest prefix whose UTF-8 encoding fits [maximumBytes].
 *
 * Iterating by code point preserves supplementary characters and never emits an incomplete UTF-8
 * sequence. This is intended for model/network byte budgets rather than display character limits.
 */
fun String.takeUtf8Bytes(maximumBytes: Int): String {
    require(maximumBytes >= 0)
    if (maximumBytes == 0 || isEmpty()) return ""
    if (toByteArray(Charsets.UTF_8).size <= maximumBytes) return this

    var index = 0
    var bytes = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        val codePointBytes = when {
            codePoint <= 0x7F -> 1
            codePoint <= 0x7FF -> 2
            codePoint <= 0xFFFF -> 3
            else -> 4
        }
        if (bytes + codePointBytes > maximumBytes) break
        bytes += codePointBytes
        index += Character.charCount(codePoint)
    }
    return substring(0, index)
}
