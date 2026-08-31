package com.personaledge.agent.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.VerbatimTtsAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTextFormatterTest {
    @Test
    fun screenshotMarkdownAndKoreanTextMathRenderWithoutSourceMarkers() {
        val formatted = format(
            """
            **1. 일의 자리 더하기:**
            먼저 일의 자리 숫자끼리 더합니다.
            ${'$'}7 + 5 = 12${'$'}
            120에서 2를 쓰고 1을 십의 자리로 올립니다.

            **2. 십의 자리 더하기:**
            ${'$'}1 \text{(올림수)} + 1 \text{(17의 십의 자리)} + 2 \text{(25의 십의 자리)} = 4${'$'}

            **결과:** 따라서 합은 **42**입니다.
            """.trimIndent(),
        )

        assertTrue(formatted.text.contains("1. 일의 자리 더하기:"))
        assertTrue(formatted.text.contains("7 + 5 = 12"))
        assertTrue(
            formatted.text.contains(
                "1 (올림수) + 1 (17의 십의 자리) + 2 (25의 십의 자리) = 4",
            ),
        )
        assertTrue(formatted.text.contains("결과: 따라서 합은 42입니다."))
        assertFalse(formatted.text.contains("**"))
        assertFalse(formatted.text.contains("\\text"))
        assertFalse(formatted.text.contains('$'))
        assertTrue(formatted.spanStyles.any { range -> range.item.fontWeight == FontWeight.Bold })
        assertTrue(formatted.spanStyles.any { range -> range.item.fontFamily == FontFamily.Serif })
    }

    @Test
    fun commonLatexDelimitersOperatorsScriptsFractionsAndRootsAreStyledLocally() {
        val formatted = format(
            """
            \(x_1^2 \le 9\)
            ${'$'}${'$'}\frac{a_1+b^2}{c} + \sqrt{16} \approx 4${'$'}${'$'}
            \[\alpha + \beta \to \infty\]
            """.trimIndent(),
        )

        assertTrue(formatted.text.contains("x12 ≤ 9"))
        assertTrue(formatted.text.contains("a1+b2⁄c + √(16) ≈ 4"))
        assertTrue(formatted.text.contains("α + β → ∞"))
        assertFalse(formatted.text.contains("\\frac"))
        assertFalse(formatted.text.contains("\\sqrt"))
        assertFalse(formatted.text.contains("\\alpha"))
        assertTrue(
            formatted.spanStyles.any { range ->
                range.item.baselineShift == BaselineShift.Superscript
            },
        )
        assertTrue(
            formatted.spanStyles.any { range ->
                range.item.baselineShift == BaselineShift.Subscript
            },
        )
        assertTrue(formatted.paragraphStyles.isNotEmpty())
    }

    @Test
    fun compactMathCurrencyAndCommandScriptsDoNotChangeMeaning() {
        val formatted = format(
            """
            compact ${'$'}7+5=12${'$'} and variables ${'$'}x^\infty + y_\alpha${'$'}
            implicit ${'$'}2(x+1)${'$'}, ${'$'}2x+1${'$'}, ${'$'}2 ** 3${'$'}
            roots ${'$'}\sqrt[3]{8}=2, \sqrt[n]{x}${'$'}
            prices ${'$'}12 + ${'$'}5 and ${'$'}12 + tax, then ${'$'}5 **합계**
            commands ${'$'}z^{\leq} + q_{\subseteq}${'$'}
            unsupported ${'$'}x^{\leqq} + y_{\subsetneq}${'$'}
            structural ${'$'}x^{\frac{1}{2}} + y_{\sqrt{2}}${'$'}
            unbraced ${'$'}u^\frac{1}{2} + v_😀${'$'}
            units ${'$'}2\mathrm{kg} + 3\mathrm{kg}${'$'} and ${'$'}2\operatorname{mod}5${'$'}
            """.trimIndent(),
        )

        assertTrue(formatted.text.contains("compact 7+5=12"))
        assertTrue(formatted.text.contains("variables x∞ + yα"))
        assertTrue(formatted.text.contains("implicit 2(x+1), 2x+1, 2 ** 3"))
        assertTrue(formatted.text.contains("roots ∛8=2, n√x"))
        assertTrue(formatted.text.contains("prices ${'$'}12 + ${'$'}5"))
        assertTrue(formatted.text.contains("${'$'}12 + tax, then ${'$'}5"))
        assertTrue(formatted.text.contains("${'$'}5 합계"))
        assertTrue(formatted.text.contains("commands z≤ + q⊆"))
        assertTrue(formatted.text.contains("unsupported x\\leqq + y\\subsetneq"))
        assertTrue(formatted.text.contains("structural x1⁄2 + y√2"))
        assertTrue(formatted.text.contains("unbraced u1⁄2 + v😀"))
        assertTrue(formatted.text.contains("units 2kg + 3kg and 2mod5"))
        assertTrue(
            formatted.spanStyles.count { range ->
                range.item.baselineShift == BaselineShift.Superscript
            } >= 2,
        )
        assertTrue(
            formatted.spanStyles.any { range ->
                range.item.baselineShift == BaselineShift.Subscript
            },
        )
        val spokenScripts = formatted.getTtsAnnotations(0, formatted.length)
            .mapNotNull { range -> (range.item as? VerbatimTtsAnnotation)?.verbatim }
        assertTrue(spokenScripts.any { speech -> speech.contains("위 첨자 ∞") })
        assertTrue(spokenScripts.any { speech -> speech.contains("아래 첨자 α") })
        assertTrue(spokenScripts.any { speech -> speech.contains("위 첨자 ≤") })
        assertTrue(spokenScripts.any { speech -> speech.contains("아래 첨자 ⊆") })
        assertTrue(spokenScripts.any { speech -> speech.contains("위 첨자 leqq") })
        assertTrue(spokenScripts.any { speech -> speech.contains("아래 첨자 subsetneq") })
        assertTrue(spokenScripts.any { speech -> speech.contains("위 첨자 1⁄2") })
        assertTrue(spokenScripts.any { speech -> speech.contains("아래 첨자 √2") })
        assertTrue(spokenScripts.any { speech -> speech.contains("아래 첨자 😀") })
        assertFalse(spokenScripts.any { speech -> speech.contains("≤q") })
        assertFalse(spokenScripts.any { speech -> speech.contains("⊂neq") })
        assertFalse(spokenScripts.any { speech -> speech.contains("frac12") })
        assertFalse(spokenScripts.any { speech -> speech.contains("sqrt2") })
    }

    @Test
    fun currencyMarkersNeverCrossIntoLaterInlineMath() {
        val formatted = format(
            "가격은 ${'$'}12이고 식은 ${'$'}x${'$'}입니다. " +
                "가격 ${'$'}12 + ${'$'}5, 식 ${'$'}7 + 5 = 12${'$'}. " +
                "숫자식 ${'$'}12${'$'} ${'$'}12.5${'$'} ${'$'}12 + tax${'$'}, " +
                "수식 ${'$'}2x+1${'$'}. " +
                "경로 ${'$'}HOME/${'$'}USER, 참조 ${'$'}A${'$'}1",
        )

        assertEquals(
            "가격은 ${'$'}12이고 식은 x입니다. " +
                "가격 ${'$'}12 + ${'$'}5, 식 7 + 5 = 12. " +
                "숫자식 12 12.5 ${'$'}12 + tax${'$'}, 수식 2x+1. " +
                "경로 ${'$'}HOME/${'$'}USER, 참조 ${'$'}A${'$'}1",
            formatted.text,
        )
    }

    @Test
    fun identifiersDunderNamesAndMultiplicationAreNotConsumedAsEmphasis() {
        val formatted = format(
            "MAX_OUTPUT_TOKENS snake_case_value __init__ 2*3*4 foo**bar**baz " +
                "*강조* **굵게**",
        )

        assertEquals(
            "MAX_OUTPUT_TOKENS snake_case_value __init__ 2*3*4 foo**bar**baz 강조 굵게",
            formatted.text,
        )
    }

    @Test
    fun multiLetterMathIdentifiersRenderWithoutTreatingShellSigilsAsMath() {
        val formatted = format(
            "identifiers ${'$'}xy${'$'} ${'$'}loss_t${'$'} " +
                "${'$'}total = price \\times quantity${'$'} " +
                "${'$'}2+\\sqrt{velocity}${'$'} ${'$'}2xy${'$'} " +
                "${'$'}2loss_t${'$'} ${'$'}5!${'$'}; " +
                "sigils ${'$'}HOME/${'$'}USER ${'$'}USD${'$'}",
        )

        assertEquals(
            "identifiers xy losst total = price × quantity 2+√(velocity) 2xy " +
                "2losst 5!; " +
                "sigils ${'$'}HOME/${'$'}USER ${'$'}USD${'$'}",
            formatted.text,
        )
        assertTrue(
            formatted.spanStyles.any { range ->
                range.item.baselineShift == BaselineShift.Subscript
            },
        )
    }

    @Test
    fun multiLetterArithmeticAndCommonOperatorsRenderWithoutRawLatexCommands() {
        val formatted = format(
            "${'$'}loss + regularization${'$'} ${'$'}foo - bar${'$'} " +
                "${'$'}distance / time${'$'} " +
                "${'$'}\\sin(x) + \\log_{10}(x) + \\max(a,b)${'$'}",
        )

        assertEquals(
            "loss + regularization foo - bar distance / time " +
                "sin(x) + log10(x) + max(a,b)",
            formatted.text,
        )
        assertFalse(formatted.text.contains("\\sin"))
        assertFalse(formatted.text.contains("\\log"))
        assertTrue(
            formatted.spanStyles.any { range ->
                range.item.baselineShift == BaselineShift.Subscript
            },
        )
    }

    @Test
    fun escapedInlineMarkersStayLiteralWithoutProtectingLaterEmphasis() {
        val formatted = format(
            "\\`x **굵게** \\` \\${'$'}x **강조** \\${'$'} \\~~삭제 아님~~",
        )

        assertEquals(
            "`x 굵게 ` ${'$'}x 강조 ${'$'} ~~삭제 아님~~",
            formatted.text,
        )
        assertTrue(
            formatted.spanStyles.count { range -> range.item.fontWeight == FontWeight.Bold } >= 2,
        )
        assertFalse(
            formatted.spanStyles.any { range ->
                range.item.textDecoration == androidx.compose.ui.text.style.TextDecoration.LineThrough
            },
        )
    }

    @Test
    fun manyInvalidEmphasisCandidatesStayLiteral() {
        val source = "*a ".repeat(4_000) + "**b ".repeat(4_000)
        val formatted = format(source)

        assertEquals(source, formatted.text)
        assertFalse(
            formatted.spanStyles.any { range ->
                range.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic ||
                    range.item.fontWeight == FontWeight.Bold
            },
        )
    }

    @Test
    fun emphasisDoesNotCloseInsideCodeAndCombinedMarkerKeepsBothStyles() {
        val formatted = format("**설명 `x**y` 끝** ***굵은 기울임***")

        assertEquals("설명 x**y 끝 굵은 기울임", formatted.text)
        assertTrue(formatted.spanStyles.any { range -> range.item.fontWeight == FontWeight.Bold })
        assertTrue(
            formatted.spanStyles.any { range ->
                range.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic
            },
        )
        assertTrue(formatted.spanStyles.any { range -> range.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun literalDollarPairDoesNotExposeItsMarkersToOuterEmphasis() {
        val formatted = format("**앞 ${'$'}USD **fee** total${'$'} 뒤**")

        assertEquals("앞 ${'$'}USD **fee** total${'$'} 뒤", formatted.text)
        assertTrue(formatted.spanStyles.any { range -> range.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun malformedOrUnsupportedMathRemainsAtomicAndDoesNotTriggerMarkdown() {
        val formatted = format(
            "${'$'}\\foo{bar}{baz}${'$'} ${'$'}\\frac{1}${'$'} ${'$'}\\left${'$'} " +
                "${'$'}\\begin{matrix}${'$'} ${'$'}x^{${'$'} ${'$'}x_{${'$'} " +
                "${'$'}x^}${'$'} ${'$'}x^&${'$'} " +
                "${'$'}x^\\frac{1}${'$'} " +
                "${'$'}\\left( x \\right${'$'} " +
                "${'$'}\\left( x \\\\right)${'$'} " +
                "${'$'}\\left( a + \\left[ b \\right)${'$'} " +
                "${'$'}\\left(1+\\left[x\\right]\\right)${'$'} " +
                "${'$'}\\text{\\right)}${'$'} " +
                "${'$'}\\begin{matrix}\\begin{matrix}x\\end{matrix}${'$'} " +
                "${'$'}\\begin{matrix}y\\end{matrix}${'$'} ${'$'}**not math**",
        )

        assertTrue(formatted.text.contains("\\foo{bar}{baz}"))
        assertTrue(formatted.text.contains("\\frac{1}"))
        assertTrue(formatted.text.contains("\\left"))
        assertTrue(formatted.text.contains("\\begin{matrix}"))
        assertTrue(formatted.text.contains("x^{"))
        assertTrue(formatted.text.contains("x_{"))
        assertTrue(formatted.text.contains("x^}"))
        assertTrue(formatted.text.contains("x^&"))
        assertTrue(formatted.text.contains("x^\\frac{1}"))
        assertTrue(formatted.text.contains("\\left( x \\right"))
        assertTrue(formatted.text.contains("\\left( x \nright)"))
        assertTrue(formatted.text.contains("\\left( a + [ b )"))
        assertTrue(formatted.text.contains("(1+[x])"))
        assertTrue(formatted.text.contains("\\right)"))
        assertTrue(formatted.text.contains("\\begin{matrix}x"))
        assertTrue(formatted.text.contains(" y "))
        assertTrue(formatted.text.endsWith("${'$'}**not math**"))
    }

    @Test
    fun codeEscapesCurrencyAndMalformedMarkupRemainLiteral() {
        val formatted = format(
            """
            가격은 ${'$'}12입니다. 이스케이프는 \${'$'}5이고
            닫히지 않은 수식은 ${'$'}x + 1입니다.
            `**코드** ${'$'}x${'$'}`
            ```text
            **그대로** ${'$'}y${'$'} \text{raw}
            ```
            """.trimIndent(),
        )

        assertTrue(formatted.text.contains("가격은 ${'$'}12입니다."))
        assertTrue(formatted.text.contains("이스케이프는 ${'$'}5"))
        assertTrue(formatted.text.contains("닫히지 않은 수식은 ${'$'}x + 1입니다."))
        assertTrue(formatted.text.contains("**코드** ${'$'}x${'$'}"))
        assertTrue(formatted.text.contains("**그대로** ${'$'}y${'$'} \\text{raw}"))
        assertFalse(formatted.text.contains("```"))
        assertTrue(formatted.spanStyles.any { range -> range.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun listsHeadingsQuotesAndBareHttpsRemainReadableWithoutHiddenHtmlExecution() {
        val formatted = format(
            """
            ## 요약
            - 첫 항목
            > 중요한 설명
            출처 https://example.com/a?q=1.
            [숨은 링크](javascript:alert(1)) <img src=https://bad.invalid/x>
            """.trimIndent(),
        )

        assertEquals(
            "요약\n• 첫 항목\n│ 중요한 설명\n출처 https://example.com/a?q=1.\n" +
                "[숨은 링크](javascript:alert(1)) <img src=https://bad.invalid/x>",
            formatted.text,
        )
        assertTrue(formatted.spanStyles.any { range -> range.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun balancedUrlClosingParenthesisStaysInsideTheTappableTarget() {
        val url = "https://en.wikipedia.org/wiki/Function_(mathematics)"
        val formatted = format("참고 $url. 다음")
        val links = formatted.getLinkAnnotations(0, formatted.length)

        assertEquals("참고 $url. 다음", formatted.text)
        assertEquals(1, links.size)
        assertEquals(url, (links.single().item as LinkAnnotation.Url).url)
    }

    @Test
    fun manyUnclosedDisplayOpenersStayLiteralWithoutRepeatedForwardScans() {
        val multiline = List(2_000) { "\\[" }.joinToString("\n")
        val sameLineParentheses = List(4_000) { "\\(" }.joinToString(" ")
        val sameLineBrackets = List(4_000) { "\\[" }.joinToString(" ")

        assertEquals(multiline, format(multiline).text)
        assertEquals(sameLineParentheses, format(sameLineParentheses).text)
        assertEquals(sameLineBrackets, format(sameLineBrackets).text)
    }

    @Test
    fun manyInvalidHttpsPrefixesStayLiteralWithoutRepeatedForwardScans() {
        val source = List(4_000) { "https:// " }.joinToString("")

        assertEquals(source, format(source).text)
    }

    @Test
    fun displayMathPairingNeverCrossesFencedCode() {
        val formatted = format(
            """
            ```text
            ${'$'}${'$'}
            raw code
            ```
            ${'$'}${'$'}
            x^2
            ${'$'}${'$'}
            """.trimIndent(),
        )

        assertTrue(formatted.text.contains("${'$'}${'$'}\nraw code"))
        assertTrue(formatted.text.endsWith("x2"))
        assertTrue(
            formatted.spanStyles.any { range ->
                range.item.baselineShift == BaselineShift.Superscript
            },
        )
    }

    private fun format(source: String) = AssistantTextFormatter.format(
        source = source,
        linkColor = Color.Blue,
        codeBackground = Color.DarkGray,
    )
}
