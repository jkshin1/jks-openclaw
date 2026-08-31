package com.personaledge.agent.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.VerbatimTtsAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em

/**
 * Renders the completed assistant answer without handing untrusted model output to HTML/WebView.
 *
 * The parser deliberately supports a bounded presentation subset: common Markdown emphasis,
 * fenced/inline code, bare HTTPS links, and the LaTeX forms the local model normally emits. The
 * saved [com.personaledge.agent.ChatEntry] remains unchanged. Active thought text never enters
 * this renderer; its disclosure continues to display that channel verbatim.
 */
@Composable
internal fun AssistantRichText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val formatted = remember(text, linkColor, codeBackground) {
        AssistantTextFormatter.format(
            source = text,
            linkColor = linkColor,
            codeBackground = codeBackground,
        )
    }
    Text(
        text = formatted,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/** Pure, deterministic formatter kept visible to local JVM tests. */
internal object AssistantTextFormatter {
    private const val MAX_FORMULA_LENGTH = 8_192
    private const val MAX_INLINE_DEPTH = 12
    private const val MAX_MATH_DEPTH = 12

    fun format(
        source: String,
        linkColor: Color,
        codeBackground: Color,
    ): AnnotatedString = buildAnnotatedString {
        val lines = source.split('\n')
        val displayBlocks = findDisplayBlocks(lines)
        var lineIndex = 0
        var fencedCode = false

        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                fencedCode = !fencedCode
            } else if (fencedCode) {
                withStyle(codeStyle(codeBackground)) { append(line) }
            } else {
                val multilineDisplay = displayBlocks[lineIndex]
                if (multilineDisplay != null) {
                    appendDisplayMath(multilineDisplay.formula)
                    lineIndex = multilineDisplay.closingLine
                } else {
                    appendFormattedLine(
                        line = line,
                        linkColor = linkColor,
                        codeBackground = codeBackground,
                    )
                }
            }
            if (lineIndex < lines.lastIndex) append('\n')
            lineIndex += 1
        }
    }

    private fun AnnotatedString.Builder.appendFormattedLine(
        line: String,
        linkColor: Color,
        codeBackground: Color,
    ) {
        val trimmed = line.trim()
        val sameLineDisplay = extractSameLineDisplayMath(trimmed)
        if (sameLineDisplay != null) {
            appendDisplayMath(sameLineDisplay)
            return
        }

        val leadingWhitespace = line.takeWhile(Char::isWhitespace)
        val body = line.removePrefix(leadingWhitespace)
        append(leadingWhitespace)

        val heading = HEADING.matchEntire(body)
        if (heading != null) {
            val level = heading.groupValues[1].length
            withStyle(
                SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = (1.24f - level * 0.04f).em,
                ),
            ) {
                appendInline(
                    source = heading.groupValues[2],
                    linkColor = linkColor,
                    codeBackground = codeBackground,
                )
            }
            return
        }

        val bullet = BULLET.matchEntire(body)
        if (bullet != null) {
            append("• ")
            appendInline(
                source = bullet.groupValues[1],
                linkColor = linkColor,
                codeBackground = codeBackground,
            )
            return
        }

        if (body.startsWith("> ")) {
            append("│ ")
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInline(
                    source = body.removePrefix("> "),
                    linkColor = linkColor,
                    codeBackground = codeBackground,
                )
            }
            return
        }

        appendInline(
            source = body,
            linkColor = linkColor,
            codeBackground = codeBackground,
        )
    }

    private fun AnnotatedString.Builder.appendInline(
        source: String,
        linkColor: Color,
        codeBackground: Color,
        depthRemaining: Int = MAX_INLINE_DEPTH,
    ) {
        if (depthRemaining <= 0) {
            append(source)
            return
        }
        val protectedOffsets = buildProtectedInlineOffsets(source)
        val nextCombinedClosing = buildNextEmphasisClosing(source, "***", protectedOffsets)
        val nextStrongClosing = buildNextEmphasisClosing(source, "**", protectedOffsets)
        val nextItalicClosing = buildNextEmphasisClosing(source, "*", protectedOffsets)
        val nextStrikeClosing = buildNextEmphasisClosing(source, "~~", protectedOffsets)
        var cursor = 0
        while (cursor < source.length) {
            when {
                source.startsWith("https://", cursor) -> {
                    val match = HTTPS_URL.matchAt(source, cursor)
                    if (match != null) {
                        val raw = match.value
                        val url = trimUrlTrailingPunctuation(raw)
                        if (url.isNotEmpty()) {
                            withLink(LinkAnnotation.Url(url)) {
                                withStyle(
                                    SpanStyle(
                                        color = linkColor,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ) {
                                    append(url)
                                }
                            }
                            append(raw.removePrefix(url))
                            cursor = match.range.last + 1
                            continue
                        }
                    }
                    append(source[cursor])
                    cursor += 1
                }

                source.startsWith("***", cursor) &&
                    canOpenEmphasis(source, cursor, "***") -> {
                    val marker = "***"
                    val closing = nextCombinedClosing[cursor + marker.length]
                    if (closing >= 0) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                appendInline(
                                    source = source.substring(cursor + marker.length, closing),
                                    linkColor = linkColor,
                                    codeBackground = codeBackground,
                                    depthRemaining = depthRemaining - 1,
                                )
                            }
                        }
                        cursor = closing + marker.length
                    } else {
                        append(marker)
                        cursor += marker.length
                    }
                }

                source.startsWith("**", cursor) &&
                    canOpenEmphasis(source, cursor, "**") -> {
                    val marker = "**"
                    val closing = nextStrongClosing[cursor + marker.length]
                    if (closing >= 0) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            appendInline(
                                source = source.substring(cursor + marker.length, closing),
                                linkColor = linkColor,
                                codeBackground = codeBackground,
                                depthRemaining = depthRemaining - 1,
                            )
                        }
                        cursor = closing + marker.length
                    } else {
                        append(marker)
                        cursor += marker.length
                    }
                }

                source.startsWith("~~", cursor) &&
                    !isEscapedAt(source, cursor) &&
                    canOpenEmphasis(source, cursor, "~~") -> {
                    val closing = nextStrikeClosing[cursor + 2]
                    if (closing >= 0) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            appendInline(
                                source = source.substring(cursor + 2, closing),
                                linkColor = linkColor,
                                codeBackground = codeBackground,
                                depthRemaining = depthRemaining - 1,
                            )
                        }
                        cursor = closing + 2
                    } else {
                        append("~~")
                        cursor += 2
                    }
                }

                source[cursor] == '`' && !isEscapedAt(source, cursor) -> {
                    val closing = findUnescaped(source, "`", cursor + 1)
                    if (closing >= 0) {
                        withStyle(codeStyle(codeBackground)) {
                            append(source.substring(cursor + 1, closing))
                        }
                        cursor = closing + 1
                    } else {
                        append('`')
                        cursor += 1
                    }
                }

                source.startsWith("\\(", cursor) && !isEscapedAt(source, cursor) -> {
                    val closing = findUnescaped(source, "\\)", cursor + 2)
                    if (closing >= 0) {
                        appendMath(source.substring(cursor + 2, closing), display = false)
                        cursor = closing + 2
                    } else {
                        append(source.substring(cursor))
                        cursor = source.length
                    }
                }

                source.startsWith("\\[", cursor) && !isEscapedAt(source, cursor) -> {
                    val closing = findUnescaped(source, "\\]", cursor + 2)
                    if (closing >= 0) {
                        appendMath(source.substring(cursor + 2, closing), display = true)
                        cursor = closing + 2
                    } else {
                        append(source.substring(cursor))
                        cursor = source.length
                    }
                }

                source.startsWith("$$", cursor) && !isEscapedAt(source, cursor) -> {
                    val closing = findUnescaped(source, "$$", cursor + 2)
                    if (closing >= 0) {
                        appendMath(source.substring(cursor + 2, closing), display = true)
                        cursor = closing + 2
                    } else {
                        append(source.substring(cursor))
                        cursor = source.length
                    }
                }

                source[cursor] == '$' && !isEscapedAt(source, cursor) -> {
                    val closing = findUnescaped(source, "$", cursor + 1)
                    when (classifyDollarStart(source, cursor, closing)) {
                        DollarDisposition.CURRENCY_MARKER -> {
                            append('$')
                            cursor += 1
                        }
                        DollarDisposition.LITERAL_PAIR -> {
                            append(source.substring(cursor, closing + 1))
                            cursor = closing + 1
                        }
                        DollarDisposition.MATH -> {
                            if (closing < 0) {
                                append(source.substring(cursor))
                                cursor = source.length
                            } else {
                                appendMath(source.substring(cursor + 1, closing), display = false)
                                cursor = closing + 1
                            }
                        }
                    }
                }

                source[cursor] == '\\' && cursor + 1 < source.length &&
                    source[cursor + 1] in ESCAPABLE_MARKDOWN -> {
                    append(source[cursor + 1])
                    cursor += 2
                }

                source[cursor] == '*' &&
                    source.getOrNull(cursor - 1) != '*' &&
                    source.getOrNull(cursor + 1) != '*' &&
                    canOpenEmphasis(source, cursor, "*") -> {
                    val marker = "*"
                    val closing = nextItalicClosing[cursor + 1]
                    if (closing > cursor + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            appendInline(
                                source = source.substring(cursor + 1, closing),
                                linkColor = linkColor,
                                codeBackground = codeBackground,
                                depthRemaining = depthRemaining - 1,
                            )
                        }
                        cursor = closing + 1
                    } else {
                        append(source[cursor])
                        cursor += 1
                    }
                }

                else -> {
                    append(source[cursor])
                    cursor += 1
                }
            }
        }
    }

    private fun AnnotatedString.Builder.appendDisplayMath(formula: String) {
        withStyle(ParagraphStyle(textAlign = TextAlign.Center)) {
            appendMath(formula, display = true)
        }
    }

    private fun AnnotatedString.Builder.appendMath(formula: String, display: Boolean) {
        if (formula.length > MAX_FORMULA_LENGTH) {
            append(formula)
            return
        }
        withStyle(
            SpanStyle(
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = if (display) 1.08.em else 1.em,
            ),
        ) {
            MathAppender(this, formula, MAX_MATH_DEPTH).append()
        }
    }

    /** Matches disjoint multiline display blocks in one pass, including malformed input. */
    private fun findDisplayBlocks(lines: List<String>): Map<Int, DisplayBlock> {
        val blocks = mutableMapOf<Int, DisplayBlock>()
        var openingLine: Int? = null
        var closer: String? = null
        var fencedCode = false
        lines.forEachIndexed { index, line ->
            val marker = line.trim()
            if (marker.startsWith("```")) {
                // A malformed display opener must not capture a marker inside a code fence.
                if (!fencedCode && openingLine != null) {
                    openingLine = null
                    closer = null
                }
                fencedCode = !fencedCode
                return@forEachIndexed
            }
            if (fencedCode) return@forEachIndexed
            val activeOpening = openingLine
            if (activeOpening == null) {
                when (marker) {
                    "$$" -> {
                        openingLine = index
                        closer = "$$"
                    }
                    "\\[" -> {
                        openingLine = index
                        closer = "\\]"
                    }
                }
            } else if (marker == closer) {
                blocks[activeOpening] = DisplayBlock(
                    formula = lines.subList(activeOpening + 1, index).joinToString("\n"),
                    closingLine = index,
                )
                openingLine = null
                closer = null
            }
        }
        return blocks
    }

    private fun extractSameLineDisplayMath(trimmed: String): String? = when {
        trimmed.length >= 4 && trimmed.startsWith("$$") && trimmed.endsWith("$$") ->
            trimmed.substring(2, trimmed.length - 2)
        trimmed.length >= 4 && trimmed.startsWith("\\[") && trimmed.endsWith("\\]") ->
            trimmed.substring(2, trimmed.length - 2)
        else -> null
    }

    private fun findUnescaped(source: String, marker: String, start: Int): Int {
        var cursor = start
        while (cursor <= source.length - marker.length) {
            val match = source.indexOf(marker, cursor)
            if (match < 0) return -1
            var slashCount = 0
            var before = match - 1
            while (before >= 0 && source[before] == '\\') {
                slashCount += 1
                before -= 1
            }
            if (slashCount % 2 == 0) return match
            cursor = match + marker.length
        }
        return -1
    }

    /** Nearest valid closer at or after each offset, built in one reverse pass. */
    private fun buildNextEmphasisClosing(
        source: String,
        marker: String,
        protectedOffsets: BooleanArray,
    ): IntArray {
        val nextClosing = IntArray(source.length + 1) { -1 }
        var nearest = -1
        for (index in source.lastIndex downTo 0) {
            if (index <= source.length - marker.length &&
                source.startsWith(marker, index) &&
                !protectedOffsets[index] &&
                !isEscapedAt(source, index) &&
                canCloseEmphasis(source, index, marker)
            ) {
                nearest = index
            }
            nextClosing[index] = nearest
        }
        return nextClosing
    }

    private fun buildProtectedInlineOffsets(source: String): BooleanArray {
        val protected = BooleanArray(source.length)
        var cursor = 0
        while (cursor < source.length) {
            when {
                source[cursor] == '`' && !isEscapedAt(source, cursor) -> {
                    val closing = findUnescaped(source, "`", cursor + 1)
                    if (closing >= 0) {
                        val endExclusive = closing + 1
                        markProtected(protected, cursor, endExclusive)
                        cursor = endExclusive
                    } else {
                        // An unmatched backtick is literal in appendInline, so later markup
                        // must remain eligible for formatting.
                        cursor += 1
                    }
                }
                source.startsWith("https://", cursor) -> {
                    val match = HTTPS_URL.matchAt(source, cursor)
                    if (match != null) {
                        val endExclusive = match.range.last + 1
                        markProtected(protected, cursor, endExclusive)
                        cursor = endExclusive
                    } else {
                        cursor += 1
                    }
                }
                source.startsWith("$$", cursor) && !isEscapedAt(source, cursor) -> {
                    val closing = findUnescaped(source, "$$", cursor + 2)
                    val endExclusive = if (closing >= 0) closing + 2 else source.length
                    markProtected(protected, cursor, endExclusive)
                    cursor = endExclusive
                }
                source.startsWith("\\(", cursor) && !isEscapedAt(source, cursor) -> {
                    val closing = findUnescaped(source, "\\)", cursor + 2)
                    val endExclusive = if (closing >= 0) closing + 2 else source.length
                    markProtected(protected, cursor, endExclusive)
                    cursor = endExclusive
                }
                source.startsWith("\\[", cursor) && !isEscapedAt(source, cursor) -> {
                    val closing = findUnescaped(source, "\\]", cursor + 2)
                    val endExclusive = if (closing >= 0) closing + 2 else source.length
                    markProtected(protected, cursor, endExclusive)
                    cursor = endExclusive
                }
                source[cursor] == '$' && !isEscapedAt(source, cursor) -> {
                    val closing = findUnescaped(source, "$", cursor + 1)
                    when (classifyDollarStart(source, cursor, closing)) {
                        DollarDisposition.MATH -> {
                            val endExclusive = if (closing >= 0) closing + 1 else source.length
                            markProtected(protected, cursor, endExclusive)
                            cursor = endExclusive
                        }
                        DollarDisposition.LITERAL_PAIR -> {
                            val endExclusive = closing + 1
                            markProtected(protected, cursor, endExclusive)
                            cursor = endExclusive
                        }
                        DollarDisposition.CURRENCY_MARKER -> cursor += 1
                    }
                }
                else -> cursor += 1
            }
        }
        return protected
    }

    private fun markProtected(
        protected: BooleanArray,
        start: Int,
        endExclusive: Int,
    ) {
        for (index in start until endExclusive.coerceAtMost(protected.size)) {
            protected[index] = true
        }
    }

    private fun isEscapedAt(source: String, index: Int): Boolean {
        var slashCount = 0
        var before = index - 1
        while (before >= 0 && source[before] == '\\') {
            slashCount += 1
            before -= 1
        }
        return slashCount % 2 == 1
    }

    private fun canOpenEmphasis(source: String, index: Int, marker: String): Boolean {
        val afterIndex = index + marker.length
        if (afterIndex >= source.length || source[afterIndex].isWhitespace()) return false
        val before = source.getOrNull(index - 1)
        val after = source[afterIndex]
        return before == null || !before.isLetterOrDigit() || !after.isLetterOrDigit()
    }

    private fun canCloseEmphasis(source: String, index: Int, marker: String): Boolean {
        if (marker == "*" &&
            (source.getOrNull(index - 1) == '*' || source.getOrNull(index + 1) == '*')
        ) {
            return false
        }
        val before = source.getOrNull(index - 1) ?: return false
        return !before.isWhitespace()
    }

    private fun trimUrlTrailingPunctuation(raw: String): String {
        var end = raw.length
        val openingCounts = mutableMapOf(
            '(' to raw.count { it == '(' },
            '[' to raw.count { it == '[' },
            '{' to raw.count { it == '{' },
        )
        val closingCounts = mutableMapOf(
            ')' to raw.count { it == ')' },
            ']' to raw.count { it == ']' },
            '}' to raw.count { it == '}' },
        )
        while (end > 0) {
            when (raw[end - 1]) {
                '.', ',', '!', '?', ';', ':' -> end -= 1
                ')' -> if (closingCounts.getValue(')') > openingCounts.getValue('(')) {
                    end -= 1
                    closingCounts[')'] = closingCounts.getValue(')') - 1
                } else {
                    break
                }
                ']' -> if (closingCounts.getValue(']') > openingCounts.getValue('[')) {
                    end -= 1
                    closingCounts[']'] = closingCounts.getValue(']') - 1
                } else {
                    break
                }
                '}' -> if (closingCounts.getValue('}') > openingCounts.getValue('{')) {
                    end -= 1
                    closingCounts['}'] = closingCounts.getValue('}') - 1
                } else {
                    break
                }
                else -> break
            }
        }
        return raw.substring(0, end)
    }

    /**
     * Keeps ordinary prices out of inline math without pairing a price marker with a later
     * formula opener. Numeric formulas win only when their own token has local math syntax.
     */
    private fun classifyDollarStart(
        source: String,
        dollarIndex: Int,
        closing: Int,
    ): DollarDisposition {
        if (closing < 0) {
            return if (source.getOrNull(dollarIndex + 1)?.isDigit() == true) {
                DollarDisposition.CURRENCY_MARKER
            } else {
                DollarDisposition.MATH
            }
        }
        if (closing + 1 < source.length && source[closing + 1].isDigit()) {
            return DollarDisposition.CURRENCY_MARKER
        }
        val candidate = source.substring(dollarIndex + 1, closing)
        if (looksLikeInlineFormula(candidate) || looksLikeClosedNumericFormula(candidate)) {
            return DollarDisposition.MATH
        }
        val containsHangul = candidate.any { it in '\uAC00'..'\uD7A3' }
        val containsWhitespace = candidate.any(Char::isWhitespace)
        return if (candidate.lastOrNull()?.isWhitespace() != true &&
            candidate.lastOrNull() !in MATH_SIGNAL_CHARS &&
            !(containsHangul && containsWhitespace)
        ) {
            DollarDisposition.LITERAL_PAIR
        } else {
            DollarDisposition.CURRENCY_MARKER
        }
    }

    private fun looksLikeInlineFormula(candidate: String): Boolean {
        if (candidate.isBlank()) return false
        val syntax = maskFormulaCommandBodies(candidate)
        if (syntax.any { it in '\uAC00'..'\uD7A3' }) return false
        var hasMathSignal = false
        var hasStructuralSignal = false
        var hasExplicitCommand = false
        var hasUnknownWord = false
        var allUnknownWordsAreLowercase = true
        var cursor = 0
        while (cursor < syntax.length) {
            val character = syntax[cursor]
            if (character in MATH_SIGNAL_CHARS || character == '\\' || character.isLetter()) {
                hasMathSignal = true
            }
            if (character in STRUCTURAL_MATH_SIGNAL_CHARS || character == '\\') {
                hasStructuralSignal = true
            }
            if (character == '\\' && syntax.getOrNull(cursor + 1)?.isLetter() == true) {
                hasExplicitCommand = true
            }
            if (character.isLetter() && character.code < 128) {
                val wordStart = cursor
                while (cursor < syntax.length &&
                    syntax[cursor].isLetter() && syntax[cursor].code < 128
                ) {
                    cursor += 1
                }
                val word = syntax.substring(wordStart, cursor)
                val isCommand = wordStart > 0 && syntax[wordStart - 1] == '\\'
                if (isCommand) hasExplicitCommand = true
                if (!isCommand && word.length > 1 && word !in KNOWN_MATH_WORDS) {
                    hasUnknownWord = true
                    allUnknownWordsAreLowercase =
                        allUnknownWordsAreLowercase && word.all(Char::isLowerCase)
                }
                continue
            }
            cursor += 1
        }
        if (!hasMathSignal || !hasUnknownWord) return hasMathSignal

        val trimmed = syntax.trim()
        if (!allUnknownWordsAreLowercase || trimmed.isEmpty()) return false
        if (trimmed.last() in INVALID_TERMINAL_MATH_CHARS || trimmed.last() == '\\') return false

        val isLowercaseIdentifier = trimmed.all { character ->
            character.isLowerCase() && character.code < 128
        }
        if (isLowercaseIdentifier) return true

        // Keep price-like prose such as `$12 + tax$` literal, while accepting normal
        // multi-letter identifiers once explicit equation structure is present.
        val isCoefficientIdentifier = !trimmed.any(Char::isWhitespace) &&
            trimmed.any(Char::isLetter) &&
            trimmed.all { character ->
                character.isLetterOrDigit() || character == '_' || character == '^'
            }
        if (trimmed.first().isDigit() && !hasExplicitCommand && !isCoefficientIdentifier) {
            return false
        }
        return hasStructuralSignal || isCoefficientIdentifier
    }

    private fun looksLikeClosedNumericFormula(candidate: String): Boolean {
        if (candidate.isEmpty() || candidate.any(Char::isWhitespace)) return false
        var digitCount = 0
        var decimalPoints = 0
        for (character in candidate) {
            when {
                character.isDigit() -> digitCount += 1
                character == '.' && decimalPoints == 0 -> decimalPoints += 1
                else -> return false
            }
        }
        return digitCount > 0 && candidate.first() != '.' && candidate.last() != '.'
    }

    private fun maskFormulaCommandBodies(candidate: String): String {
        val masked = candidate.toCharArray()
        var cursor = 0
        while (cursor < candidate.length) {
            if (candidate[cursor] != '\\' || candidate.getOrNull(cursor + 1)?.isLetter() != true) {
                cursor += 1
                continue
            }
            var commandEnd = cursor + 2
            while (commandEnd < candidate.length && candidate[commandEnd].isLetter()) {
                commandEnd += 1
            }
            val command = candidate.substring(cursor + 1, commandEnd)
            if (command !in MASKED_FORMULA_GROUP_COMMANDS) {
                cursor = commandEnd
                continue
            }
            var groupStart = commandEnd
            while (groupStart < candidate.length && candidate[groupStart].isWhitespace()) {
                groupStart += 1
            }
            if (candidate.getOrNull(groupStart) != '{') {
                cursor = commandEnd
                continue
            }
            val bodyStart = groupStart + 1
            var nesting = 1
            var index = bodyStart
            while (index < candidate.length && nesting > 0) {
                when {
                    candidate[index] == '\\' && index + 1 < candidate.length -> index += 2
                    candidate[index] == '{' -> {
                        nesting += 1
                        index += 1
                    }
                    candidate[index] == '}' -> {
                        nesting -= 1
                        index += 1
                    }
                    else -> index += 1
                }
            }
            if (nesting != 0) {
                cursor = commandEnd
                continue
            }
            for (bodyIndex in bodyStart until index - 1) masked[bodyIndex] = ' '
            cursor = index
        }
        return masked.concatToString()
    }

    private fun codeStyle(background: Color) = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = background,
    )

    private data class DisplayBlock(
        val formula: String,
        val closingLine: Int,
    )

    private enum class DollarDisposition {
        CURRENCY_MARKER,
        LITERAL_PAIR,
        MATH,
    }

    private val HEADING = Regex("^(#{1,6})\\s+(.+)$")
    private val BULLET = Regex("^[*+-]\\s+(.+)$")
    private val HTTPS_URL = Regex("https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?(?:/[^\\s<>\"']*)?")
    private val ESCAPABLE_MARKDOWN = setOf(
        '\\', '`', '*', '_', '{', '}', '[', ']', '(', ')', '#', '+', '-', '.', '!', '$', '~',
    )
    private val MATH_SIGNAL_CHARS = setOf(
        '+', '-', '−', '*', '×', '/', '÷', '=', '<', '>', '≤', '≥', '^', '_',
        '(', ')', '[', ']', '{', '}', '|', '!',
    )
    private val STRUCTURAL_MATH_SIGNAL_CHARS = setOf(
        '+', '-', '−', '*', '×', '/', '÷', '=', '<', '>', '≤', '≥', '^', '_',
        '(', ')', '[', ']', '{', '}', '|', '!',
    )
    private val INVALID_TERMINAL_MATH_CHARS = setOf(
        '+', '-', '−', '*', '×', '/', '÷', '=', '<', '>', '≤', '≥', '^', '_',
        '(', '[', '{', '|',
    )
    private val KNOWN_MATH_WORDS = setOf(
        "sin", "cos", "tan", "log", "ln", "exp", "lim", "min", "max",
    )
    private val MASKED_FORMULA_GROUP_COMMANDS = setOf(
        "text", "textrm", "textnormal", "mathrm", "operatorname", "mathbf", "boldsymbol",
        "mathit", "begin", "end",
    )
    private class MathAppender(
        private val output: AnnotatedString.Builder,
        private val source: String,
        private val depthRemaining: Int,
    ) {
        private var cursor = 0
        private val validSizingCommands by lazy { findValidSizingCommandStarts() }
        private val validEnvironmentCommands by lazy { findValidEnvironmentCommandStarts() }

        fun append() {
            if (depthRemaining <= 0) {
                output.append(source)
                return
            }
            while (cursor < source.length) {
                when (source[cursor]) {
                    '\\' -> appendCommand()
                    '^' -> appendScript(BaselineShift.Superscript)
                    '_' -> appendScript(BaselineShift.Subscript)
                    '{' -> appendLooseGroup()
                    '~' -> {
                        output.append('\u00a0')
                        cursor += 1
                    }
                    '&' -> {
                        output.append(' ')
                        cursor += 1
                    }
                    else -> {
                        output.append(source[cursor])
                        cursor += 1
                    }
                }
            }
        }

        private fun appendCommand() {
            val commandStart = cursor
            cursor += 1
            if (cursor >= source.length) {
                output.append('\\')
                return
            }
            if (!source[cursor].isLetter()) {
                val escaped = source[cursor]
                when (escaped) {
                    '\\' -> output.append('\n')
                    ',', ';', ':', '!' -> output.append(' ')
                    else -> output.append(escaped)
                }
                cursor += 1
                return
            }

            val nameStart = cursor
            while (cursor < source.length && source[cursor].isLetter()) cursor += 1
            val command = source.substring(nameStart, cursor)
            when (command) {
                "text", "textrm", "textnormal" -> appendStyledGroup(
                    SpanStyle(fontFamily = FontFamily.Default, fontStyle = FontStyle.Normal),
                    plain = true,
                    commandStart = commandStart,
                )
                "mathrm", "operatorname" -> appendStyledGroup(
                    SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Normal),
                    plain = false,
                    commandStart = commandStart,
                )
                "mathbf", "boldsymbol" -> appendStyledGroup(
                    SpanStyle(fontWeight = FontWeight.Bold),
                    plain = false,
                    commandStart = commandStart,
                )
                "mathit" -> appendStyledGroup(
                    SpanStyle(fontStyle = FontStyle.Italic),
                    plain = false,
                    commandStart = commandStart,
                )
                "frac", "dfrac", "tfrac" -> appendFraction(commandStart)
                "sqrt" -> appendSquareRoot(commandStart)
                "left", "right" -> consumeSizingCommand(commandStart)
                "begin", "end" -> consumeEnvironment(commandStart)
                "quad", "qquad", "enspace" -> output.append(if (command == "qquad") "    " else "  ")
                in KNOWN_MATH_WORDS -> output.withStyle(
                    SpanStyle(fontStyle = FontStyle.Normal),
                ) {
                    append(command)
                }
                else -> {
                    val symbol = MATH_SYMBOLS[command]
                    if (symbol != null) {
                        output.append(symbol)
                    } else {
                        appendUnsupportedCommand(commandStart)
                    }
                }
            }
        }

        private fun appendStyledGroup(
            style: SpanStyle,
            plain: Boolean,
            commandStart: Int,
        ) {
            val group = readGroup(cursor)
            if (group == null) {
                output.append(source.substring(commandStart, cursor))
                return
            }
            output.withStyle(style) {
                if (plain) {
                    append(unescapePlain(group.content))
                } else {
                    MathAppender(this, group.content, depthRemaining - 1).append()
                }
            }
            cursor = group.after
        }

        private fun appendFraction(commandStart: Int) {
            val numerator = readGroup(cursor)
            val denominator = numerator?.let { readGroup(it.after) }
            if (numerator == null || denominator == null) {
                val fallbackEnd = numerator?.after ?: cursor
                output.append(source.substring(commandStart, fallbackEnd))
                cursor = fallbackEnd
                return
            }
            output.withStyle(
                SpanStyle(fontSize = 0.76.em, baselineShift = BaselineShift.Superscript),
            ) {
                MathAppender(this, numerator.content, depthRemaining - 1).append()
            }
            output.append('⁄')
            output.withStyle(
                SpanStyle(fontSize = 0.76.em, baselineShift = BaselineShift.Subscript),
            ) {
                MathAppender(this, denominator.content, depthRemaining - 1).append()
            }
            cursor = denominator.after
        }

        private fun appendSquareRoot(commandStart: Int) {
            var groupStart = skipWhitespace(cursor)
            var rootIndex: String? = null
            if (groupStart < source.length && source[groupStart] == '[') {
                val optionalEnd = source.indexOf(']', groupStart + 1)
                if (optionalEnd >= 0) {
                    rootIndex = source.substring(groupStart + 1, optionalEnd)
                    groupStart = skipWhitespace(optionalEnd + 1)
                }
            }
            val radicand = readGroup(groupStart)
            if (radicand == null) {
                output.append(source.substring(commandStart, cursor))
                return
            }
            when (rootIndex) {
                null -> output.append('√')
                "3" -> output.append('∛')
                "4" -> output.append('∜')
                else -> {
                    val annotationStart = output.length
                    output.withStyle(
                        SpanStyle(
                            fontSize = 0.62.em,
                            baselineShift = BaselineShift.Superscript,
                        ),
                    ) {
                        MathAppender(this, rootIndex, depthRemaining - 1).append()
                    }
                    output.addTtsAnnotation(
                        VerbatimTtsAnnotation("제곱근 지수 ${scriptSpeech(rootIndex)}"),
                        annotationStart,
                        output.length,
                    )
                    output.append('√')
                }
            }
            val needsParentheses = radicand.content.length > 1
            if (needsParentheses) output.append('(')
            MathAppender(output, radicand.content, depthRemaining - 1).append()
            if (needsParentheses) output.append(')')
            cursor = radicand.after
        }

        private fun appendScript(baselineShift: BaselineShift) {
            val marker = source[cursor]
            cursor += 1
            val group = readGroup(cursor)
            if (group == null && source.getOrNull(cursor) == '{') {
                output.append(marker)
                output.append(source.substring(cursor))
                cursor = source.length
                return
            }
            if (group == null && source.getOrNull(cursor) in INVALID_SCRIPT_ATOM_STARTS) {
                output.append(marker)
                output.append(source[cursor])
                cursor += 1
                return
            }
            val script: String
            val after: Int
            if (group != null) {
                script = group.content
                after = group.after
            } else if (cursor < source.length) {
                val atom = readScriptAtom(cursor)
                if (atom == null) {
                    output.append(marker)
                    output.append(source.substring(cursor))
                    cursor = source.length
                    return
                }
                script = atom.content
                after = atom.after
            } else {
                output.append(marker)
                return
            }
            val annotationStart = output.length
            output.withStyle(
                SpanStyle(fontSize = 0.72.em, baselineShift = baselineShift),
            ) {
                MathAppender(this, script, depthRemaining - 1).append()
            }
            output.addTtsAnnotation(
                VerbatimTtsAnnotation(
                    if (baselineShift == BaselineShift.Superscript) {
                        "위 첨자 ${scriptSpeech(script)}"
                    } else {
                        "아래 첨자 ${scriptSpeech(script)}"
                    },
                ),
                annotationStart,
                output.length,
            )
            cursor = after
        }

        private fun readScriptAtom(from: Int): ScriptAtom? {
            if (source[from] != '\\' || from + 1 >= source.length) {
                val after = from + Character.charCount(source.codePointAt(from))
                return ScriptAtom(source.substring(from, after), after)
            }
            var after = from + 1
            if (source[after].isLetter()) {
                while (after < source.length && source[after].isLetter()) after += 1
            } else {
                after += 1
                return ScriptAtom(source.substring(from, after), after)
            }
            val command = source.substring(from + 1, after)
            after = when (command) {
                "frac", "dfrac", "tfrac" -> {
                    val numerator = readGroup(after) ?: return null
                    val denominator = readGroup(numerator.after) ?: return null
                    denominator.after
                }
                "sqrt" -> {
                    var groupStart = skipWhitespace(after)
                    if (source.getOrNull(groupStart) == '[') {
                        val optionalEnd = source.indexOf(']', groupStart + 1)
                        if (optionalEnd < 0) return null
                        groupStart = optionalEnd + 1
                    }
                    readGroup(groupStart)?.after ?: return null
                }
                "text", "textrm", "textnormal", "mathrm", "operatorname", "mathbf",
                "boldsymbol", "mathit", "begin", "end" -> readGroup(after)?.after ?: return null
                in MATH_SYMBOLS, "quad", "qquad", "enspace", "left", "right" -> after
                else -> {
                    var invocationEnd = after
                    while (true) {
                        val argument = readGroup(invocationEnd) ?: break
                        invocationEnd = argument.after
                    }
                    invocationEnd
                }
            }
            return ScriptAtom(source.substring(from, after), after)
        }

        private fun appendLooseGroup() {
            val group = readGroup(cursor)
            if (group == null) {
                output.append('{')
                cursor += 1
                return
            }
            MathAppender(output, group.content, depthRemaining - 1).append()
            cursor = group.after
        }

        private fun appendUnsupportedCommand(commandStart: Int) {
            var fallbackEnd = cursor
            val optionalStart = skipWhitespace(fallbackEnd)
            if (optionalStart < source.length && source[optionalStart] == '[') {
                val optionalEnd = source.indexOf(']', optionalStart + 1)
                if (optionalEnd >= 0) fallbackEnd = optionalEnd + 1
            }
            while (true) {
                val group = readGroup(fallbackEnd) ?: break
                fallbackEnd = group.after
            }
            output.append(source.substring(commandStart, fallbackEnd))
            cursor = fallbackEnd
        }

        private fun consumeSizingCommand(commandStart: Int) {
            if (commandStart !in validSizingCommands) {
                appendUnsupportedCommand(commandStart)
            }
        }

        private fun hasSizingDelimiterAt(index: Int): Boolean {
            if (index >= source.length) return false
            if (source[index] in "()[]{}|.<>/") return true
            return source[index] == '\\' && index + 1 < source.length &&
                source[index + 1] in "{}|"
        }

        /** Matches top-level sizing commands once; nested groups get their own MathAppender. */
        private fun findValidSizingCommandStarts(): Set<Int> {
            val openLefts = mutableListOf<Int>()
            val matches = mutableSetOf<Int>()
            var index = 0
            while (index < source.length) {
                if (source[index] == '{') {
                    val group = readGroup(index)
                    if (group != null) {
                        index = group.after
                        continue
                    }
                }
                if (source[index] != '\\' || source.getOrNull(index + 1)?.isLetter() != true) {
                    index += if (source[index] == '\\' && index + 1 < source.length) 2 else 1
                    continue
                }
                val commandStart = index
                index += 1
                val nameStart = index
                while (index < source.length && source[index].isLetter()) index += 1
                val command = source.substring(nameStart, index)
                if (command == "left" || command == "right") {
                    val delimiterStart = skipWhitespace(index)
                    if (hasSizingDelimiterAt(delimiterStart)) {
                        if (command == "left") {
                            openLefts += commandStart
                        } else if (openLefts.isNotEmpty()) {
                            matches += openLefts.removeAt(openLefts.lastIndex)
                            matches += commandStart
                        }
                        index = delimiterStart +
                            if (source[delimiterStart] == '\\') 2 else 1
                    }
                    continue
                }
                index = skipSizingScanArguments(command, index)
            }
            return matches
        }

        private fun skipSizingScanArguments(command: String, from: Int): Int = when (command) {
            "quad", "qquad", "enspace" -> from
            in MATH_SYMBOLS -> from
            "frac", "dfrac", "tfrac" -> {
                val numerator = readGroup(from)
                val denominator = numerator?.let { readGroup(it.after) }
                denominator?.after ?: numerator?.after ?: from
            }
            "sqrt" -> {
                var groupStart = skipWhitespace(from)
                if (source.getOrNull(groupStart) == '[') {
                    val optionalEnd = source.indexOf(']', groupStart + 1)
                    if (optionalEnd >= 0) groupStart = optionalEnd + 1
                }
                readGroup(groupStart)?.after ?: from
            }
            else -> {
                var after = from
                val optionalStart = skipWhitespace(after)
                if (source.getOrNull(optionalStart) == '[') {
                    val optionalEnd = source.indexOf(']', optionalStart + 1)
                    if (optionalEnd >= 0) after = optionalEnd + 1
                }
                while (true) {
                    val group = readGroup(after) ?: break
                    after = group.after
                }
                after
            }
        }

        private fun consumeEnvironment(commandStart: Int) {
            val environment = readGroup(cursor)
            if (environment == null) {
                output.append(source.substring(commandStart, cursor))
                return
            }
            if (commandStart in validEnvironmentCommands) {
                cursor = environment.after
            } else {
                output.append(source.substring(commandStart, environment.after))
                cursor = environment.after
            }
        }

        /** Matches top-level begin/end commands one-to-one without repeated forward scans. */
        private fun findValidEnvironmentCommandStarts(): Set<Int> {
            val openEnvironments = mutableListOf<Pair<String, Int>>()
            val matches = mutableSetOf<Int>()
            var index = 0
            while (index < source.length) {
                if (source[index] == '{') {
                    val group = readGroup(index)
                    if (group != null) {
                        index = group.after
                        continue
                    }
                }
                if (source[index] != '\\' || source.getOrNull(index + 1)?.isLetter() != true) {
                    index += if (source[index] == '\\' && index + 1 < source.length) 2 else 1
                    continue
                }
                val commandStart = index
                index += 1
                val nameStart = index
                while (index < source.length && source[index].isLetter()) index += 1
                val command = source.substring(nameStart, index)
                if (command == "begin" || command == "end") {
                    val environment = readGroup(index)
                    if (environment != null) {
                        if (command == "begin") {
                            openEnvironments += environment.content to commandStart
                        } else if (openEnvironments.lastOrNull()?.first == environment.content) {
                            matches += openEnvironments.removeAt(openEnvironments.lastIndex).second
                            matches += commandStart
                        }
                        index = environment.after
                    }
                    continue
                }
                index = skipSizingScanArguments(command, index)
            }
            return matches
        }

        private fun readGroup(from: Int): Group? {
            val start = skipWhitespace(from)
            if (start >= source.length || source[start] != '{') return null
            var nesting = 1
            var index = start + 1
            while (index < source.length) {
                when {
                    source[index] == '\\' && index + 1 < source.length -> index += 2
                    source[index] == '{' -> {
                        nesting += 1
                        index += 1
                    }
                    source[index] == '}' -> {
                        nesting -= 1
                        if (nesting == 0) {
                            return Group(source.substring(start + 1, index), index + 1)
                        }
                        index += 1
                    }
                    else -> index += 1
                }
            }
            return null
        }

        private fun skipWhitespace(from: Int): Int {
            var index = from
            while (index < source.length && source[index].isWhitespace()) index += 1
            return index
        }

        private fun unescapePlain(value: String): String = buildString(value.length) {
            var index = 0
            while (index < value.length) {
                if (value[index] == '\\' && index + 1 < value.length &&
                    value[index + 1] in setOf('\\', '{', '}', '$', '%', '_', '#', '&')
                ) {
                    append(value[index + 1])
                    index += 2
                } else {
                    append(value[index])
                    index += 1
                }
            }
        }

        private fun scriptSpeech(value: String): String {
            val rendered = if (depthRemaining <= 1) {
                value
            } else {
                val temporary = AnnotatedString.Builder()
                MathAppender(temporary, value, depthRemaining - 1).append()
                temporary.toAnnotatedString().text
            }
            return speakCommandTokens(rendered)
        }

        private fun speakCommandTokens(value: String): String = buildString(value.length) {
            var index = 0
            while (index < value.length) {
                when {
                    value[index] == '\\' && value.getOrNull(index + 1)?.isLetter() == true -> {
                        var commandEnd = index + 2
                        while (commandEnd < value.length && value[commandEnd].isLetter()) {
                            commandEnd += 1
                        }
                        val command = value.substring(index + 1, commandEnd)
                        append(MATH_SYMBOLS[command] ?: command)
                        index = commandEnd
                    }
                    value[index] == '\\' && index + 1 < value.length -> {
                        append(value[index + 1])
                        index += 2
                    }
                    value[index] == '{' || value[index] == '}' -> index += 1
                    else -> {
                        append(value[index])
                        index += 1
                    }
                }
            }
        }.trim()

        private data class Group(
            val content: String,
            val after: Int,
        )

        private data class ScriptAtom(
            val content: String,
            val after: Int,
        )

        companion object {
            private val INVALID_SCRIPT_ATOM_STARTS = setOf('{', '}', '&', '^', '_', ' ', '\t', '\n')
        }
    }

    private val MATH_SYMBOLS = mapOf(
        "times" to "×",
        "cdot" to "·",
        "div" to "÷",
        "pm" to "±",
        "mp" to "∓",
        "le" to "≤",
        "leq" to "≤",
        "ge" to "≥",
        "geq" to "≥",
        "ne" to "≠",
        "neq" to "≠",
        "approx" to "≈",
        "equiv" to "≡",
        "to" to "→",
        "rightarrow" to "→",
        "leftarrow" to "←",
        "infty" to "∞",
        "sum" to "∑",
        "prod" to "∏",
        "int" to "∫",
        "partial" to "∂",
        "nabla" to "∇",
        "in" to "∈",
        "notin" to "∉",
        "subset" to "⊂",
        "subseteq" to "⊆",
        "cup" to "∪",
        "cap" to "∩",
        "forall" to "∀",
        "exists" to "∃",
        "alpha" to "α",
        "beta" to "β",
        "gamma" to "γ",
        "delta" to "δ",
        "epsilon" to "ε",
        "theta" to "θ",
        "lambda" to "λ",
        "mu" to "μ",
        "pi" to "π",
        "rho" to "ρ",
        "sigma" to "σ",
        "tau" to "τ",
        "phi" to "φ",
        "omega" to "ω",
        "Delta" to "Δ",
        "Gamma" to "Γ",
        "Lambda" to "Λ",
        "Pi" to "Π",
        "Sigma" to "Σ",
        "Phi" to "Φ",
        "Omega" to "Ω",
    )
}
