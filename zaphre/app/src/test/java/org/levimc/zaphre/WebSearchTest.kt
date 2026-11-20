package org.levimc.zaphre

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.levimc.zaphre.ai.WebSearch
import java.io.IOException

class WebSearchTest {
    @Test fun parsesSafeLinksAndSnippets() {
        val html = """<div class="result"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Ftest">Example</a><div class="result__snippet">Useful snippet</div></div><div class="result"><a class="result__a" href="javascript:alert(1)">bad</a></div>"""
        val result = WebSearch.parse(html)
        assertTrue(result.contains("https://example.com/test"))
        assertTrue(result.contains("Useful snippet"))
        assertFalse(result.contains("javascript:"))
        assertTrue(result.contains("untrusted"))
    }
    @Test(expected = IOException::class) fun rejectsEmptyOrBlockedResults() {
        WebSearch.parse("<html>blocked</html>")
    }
}
