package com.example.link_pi.miniapp

import org.junit.Assert.*
import org.junit.Test

class MiniAppParserTest {

    // ── extractHtml ──

    @Test
    fun `extracts HTML from markdown html code block`() {
        val response = """Here's your app:
```html
<!DOCTYPE html>
<html><head><title>Test</title></head><body>Hello</body></html>
```
Enjoy!"""
        val html = MiniAppParser.extractHtml(response)
        assertNotNull(html)
        assertTrue(html!!.contains("<!DOCTYPE html>"))
        assertTrue(html.contains("</html>"))
    }

    @Test
    fun `extracts HTML from generic code block with html content`() {
        val response = """Check this:
```
<!DOCTYPE html>
<html><body>World</body></html>
```"""
        val html = MiniAppParser.extractHtml(response)
        assertNotNull(html)
        assertTrue(html!!.contains("<html>"))
    }

    @Test
    fun `returns null when no HTML in code block`() {
        val response = """Here's some Python:
```python
print("hello")
```"""
        assertNull(MiniAppParser.extractHtml(response))
    }

    @Test
    fun `returns null for plain text without code blocks`() {
        assertNull(MiniAppParser.extractHtml("This is just text with no code"))
    }

    @Test
    fun `handles truncated HTML block - repairs missing closing tags`() {
        val response = """```html
<!DOCTYPE html>
<html><head><title>App</title></head>
<body>
<script>
console.log("hello");"""
        val html = MiniAppParser.extractHtml(response)
        assertNotNull(html)
        // repairHtml should close unclosed script, body, html tags
        assertTrue(html!!.contains("</script>"))
        assertTrue(html.contains("</body>"))
        assertTrue(html.contains("</html>"))
    }

    // ── extractMiniApp ──

    @Test
    fun `extractMiniApp returns MiniApp with correct title`() {
        val response = """Here's your app:
```html
<!DOCTYPE html>
<html><head><title>My Cool App</title></head><body>Content</body></html>
```"""
        val app = MiniAppParser.extractMiniApp(response)
        assertNotNull(app)
        assertEquals("My Cool App", app!!.name)
        assertTrue(app.id.isNotBlank())
    }

    @Test
    fun `extractMiniApp uses default name when no title tag`() {
        val response = """```html
<html><body>No title here</body></html>
```"""
        val app = MiniAppParser.extractMiniApp(response)
        assertNotNull(app)
        assertEquals("Mini App", app!!.name)
    }

    @Test
    fun `extractMiniApp returns null for non-HTML response`() {
        assertNull(MiniAppParser.extractMiniApp("Just a text response with no code"))
    }

    @Test
    fun `extractMiniApp captures description from text before code`() {
        val response = """This is an awesome calculator app that does math.
```html
<html><head><title>Calc</title></head><body></body></html>
```"""
        val app = MiniAppParser.extractMiniApp(response)
        assertNotNull(app)
        assertTrue(app!!.description.contains("calculator"))
    }

    @Test
    fun `description is truncated to 200 chars`() {
        val longText = "A".repeat(300)
        val response = """$longText
```html
<html><body>x</body></html>
```"""
        val app = MiniAppParser.extractMiniApp(response)
        assertNotNull(app)
        assertTrue(app!!.description.length <= 203) // 200 + "..."
    }

    // ── getDisplayText ──

    @Test
    fun `getDisplayText removes html code blocks`() {
        val response = """Before text
```html
<html><body>Hidden</body></html>
```
After text"""
        val display = MiniAppParser.getDisplayText(response)
        assertFalse(display.contains("<html>"))
        assertTrue(display.contains("Before text"))
        assertTrue(display.contains("After text"))
    }

    @Test
    fun `getDisplayText removes generic code blocks with HTML`() {
        val response = """Intro
```
<html><body>X</body></html>
```
Outro"""
        val display = MiniAppParser.getDisplayText(response)
        assertFalse(display.contains("<html>"))
        assertTrue(display.contains("Intro"))
    }

    @Test
    fun `getDisplayText handles plain text without code blocks`() {
        assertEquals("Hello world", MiniAppParser.getDisplayText("Hello world"))
    }

    // ── repairHtml edge cases (tested via extractHtml) ──

    @Test
    fun `repairs unclosed style tags`() {
        val response = """```html
<html><head><style>
body { color: red; }"""
        val html = MiniAppParser.extractHtml(response)
        assertNotNull(html)
        assertTrue(html!!.contains("</style>"))
        assertTrue(html.contains("</html>"))
    }

    @Test
    fun `does not double-close already closed tags`() {
        val response = """```html
<html><head><title>X</title></head><body></body></html>"""
        // This should match the truncated regex and go through repairHtml
        // but repairHtml should not add duplicate closing tags
        val html = MiniAppParser.extractHtml(response)
        assertNotNull(html)
        val htmlCloseCount = Regex("</html>", RegexOption.IGNORE_CASE).findAll(html!!).count()
        assertEquals(1, htmlCloseCount)
    }
}
