import java.io.File

fun main() {
    val input = File("c:/Users/dosek/develop/koupper infrastructure/examples/cli-report-generator.kts").readText()
    
    val exportMatch = Regex("""@(?:[\w.]*\.)?Export\b""").find(input) ?: return
    val tail = input.substring(exportMatch.range.first)

    val sig = Regex(
        """(?s)
           (?:.*?@(?:[\w.]*\.)?Export\b.*?)
           (?:\s*@[\w.]+(?:\([^()]*\))?\s*)*
           \s*val\s+`?[\w$]+`?\s*:\s*
           \((.*?)\)\s*->\s*
           ([^=\{\n;]+)
        """.trimIndent(),
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.COMMENTS)
    ).find(tail)
    
    if (sig != null) {
        println("CAPTURED PARAMS: " + sig.groupValues[1])
        println("CAPTURED RETURN: " + sig.groupValues[2])
    } else {
        println("REGEX MATCH FAILED!")
    }
}

main()
