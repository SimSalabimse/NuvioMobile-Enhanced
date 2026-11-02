package com.nuvio.app.features.downloads

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommonMainJvmOnlyApiTest {
    @Test
    fun flagsJvmOnlyCallsOnASample() {
        val sample = """
            import java.util.HashMap
            fun bad(map: HashMap<String, Any>) {
                map.remove("id", "job")
                String.format("%s", "x")
                @Synchronized
                fun locked() = map.putIfAbsent("id", "job")
                map.computeIfAbsent("id") { "job" }
                map.computeIfPresent("id") { _, value -> value }
            }
        """.trimIndent()
        val found = CommonMainJvmOnlyApis.scan(sample, "Sample.kt")
        assertTrue(found.any { it.endsWith("map.remove(\"id\", \"job\")") })
        assertTrue(found.any { it.contains("import java.util.HashMap") })
        assertTrue(found.any { it.contains("String.format") })
        assertTrue(found.any { it.contains("@Synchronized") })
        assertTrue(found.any { it.contains("putIfAbsent") })
        assertTrue(found.any { it.contains("computeIfAbsent") })
        assertTrue(found.any { it.contains("computeIfPresent") })

        val common = """
            fun ok(map: MutableMap<String, Any>) {
                map.remove("id")
                "%s".format("x")
                synchronized(map) { map.remove("id") }
                cached.remove(CacheScope(profileId = profileId, source = source))
            }
        """.trimIndent()
        assertEquals(emptyList(), CommonMainJvmOnlyApis.scan(common, "Ok.kt"))
    }

    @Test
    fun commonMainHasNoUnlistedJvmOnlyApis() {
        val root = File("src/commonMain/kotlin")
        assertTrue(root.isDirectory, "run from composeApp: ${root.absolutePath}")
        val allow = allowlist()
        val found = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val path = root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                CommonMainJvmOnlyApis.scan(file.readText(), path).asSequence()
            }
            .toList()
        assertEquals(emptyList(), found.filter { it !in allow })
        assertEquals(emptySet(), allow - found.toSet())
    }

    private fun allowlist(): Set<String> {
        val file = File("src/androidHostTest/resources/common-main-jvm-only-allowlist.txt")
        assertTrue(file.isFile, file.absolutePath)
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}

internal object CommonMainJvmOnlyApis {
    fun scan(source: String, path: String): List<String> {
        val code = blankStringsAndComments(source)
        val found = mutableListOf<String>()
        importPattern.findAll(code).forEach { match ->
            found += "$path ${match.value.trim()}"
        }
        annotationPattern.findAll(code).forEach { match ->
            found += "$path ${match.value}"
        }
        callPattern.findAll(code).forEach { match ->
            found += "$path ${match.value.takeWhile { it != '(' }.trim()}("
        }
        var index = 0
        while (true) {
            val at = code.indexOf(".remove(", index)
            if (at < 0) break
            val open = at + ".remove".length
            val close = closingParen(code, open)
            val args = if (close < 0) "" else code.substring(open + 1, close)
            if (topLevelCommas(args) == 1) {
                val receiver = receiver(code, at)
                val original = source.substring(open + 1, close).replace(whitespace, " ").trim()
                found += "$path $receiver.remove($original)"
            }
            index = at + ".remove(".length
        }
        return found
    }

    private fun receiver(code: String, dot: Int): String {
        var i = dot - 1
        while (i >= 0 && (code[i].isLetterOrDigit() || code[i] == '_')) i--
        return code.substring(i + 1, dot)
    }

    private fun closingParen(code: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun topLevelCommas(args: String): Int {
        var commas = 0
        var paren = 0
        var brace = 0
        var bracket = 0
        args.forEach { char ->
            when (char) {
                '(' -> paren++
                ')' -> paren--
                '{' -> brace++
                '}' -> brace--
                '[' -> bracket++
                ']' -> bracket--
                ',' -> if (paren == 0 && brace == 0 && bracket == 0) commas++
            }
        }
        return commas
    }

    private fun blankStringsAndComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            if (source.startsWith("//", i)) {
                val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                out.append(" ".repeat(end - i))
                i = end
                continue
            }
            if (source.startsWith("/*", i)) {
                val end = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                out.append(blankKeepingNewlines(source.substring(i, end)))
                i = end
                continue
            }
            if (source.startsWith("\"\"\"", i)) {
                val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it + 3 }
                out.append(blankKeepingNewlines(source.substring(i, end)))
                i = end
                continue
            }
            if (source[i] == '"') {
                val end = endOfQuoted(source, i)
                out.append(" ".repeat(end - i))
                i = end
                continue
            }
            if (source[i] == '\'') {
                val end = endOfChar(source, i)
                out.append(" ".repeat(end - i))
                i = end
                continue
            }
            out.append(source[i])
            i++
        }
        return out.toString()
    }

    private fun endOfQuoted(source: String, start: Int): Int {
        var i = start + 1
        while (i < source.length) {
            if (source[i] == '\\') {
                i += 2
                continue
            }
            if (source[i] == '"') return i + 1
            i++
        }
        return source.length
    }

    private fun endOfChar(source: String, start: Int): Int {
        var i = start + 1
        while (i < source.length) {
            if (source[i] == '\\') {
                i += 2
                continue
            }
            if (source[i] == '\'') return i + 1
            i++
        }
        return source.length
    }

    private fun blankKeepingNewlines(text: String): String =
        text.map { if (it == '\n') '\n' else ' ' }.joinToString("")

    private val importPattern = Regex("""(?m)^\s*import\s+java(?:x)?\.[^\n]+""")
    private val annotationPattern = Regex("""@Synchronized\b""")
    private val callPattern = Regex(
        """(?<![.\w])(?:String\.format|ConcurrentHashMap)\b|\.(?:putIfAbsent|computeIfAbsent|computeIfPresent)\(""",
    )
    private val whitespace = Regex("""\s+""")
}
