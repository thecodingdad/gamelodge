package io.github.thecodingdad.moonlightlauncher.desktop.games

/**
 * Valve's text KeyValues format (libraryfolders.vdf, appmanifest_*.acf, loginusers.vdf). Keys are
 * case-insensitive, like in Steam itself.
 */
class VdfNode(val value: String? = null) {
    val children: MutableMap<String, VdfNode> = java.util.TreeMap(String.CASE_INSENSITIVE_ORDER)

    operator fun get(key: String): VdfNode? = children[key]

    fun value(key: String): String? = children[key]?.value

    companion object {
        private const val OPEN = "\u0001{"

        fun parse(text: String): VdfNode {
            val tokens = tokenize(text).iterator()
            return VdfNode().also { readObject(tokens, it) }
        }

        private fun readObject(tokens: Iterator<String?>, node: VdfNode) {
            while (tokens.hasNext()) {
                val key = tokens.next() ?: return // "}" closes this object
                if (!tokens.hasNext()) return
                val next = tokens.next() ?: return
                node.children[key] = if (next == OPEN) VdfNode().also { readObject(tokens, it) } else VdfNode(next)
            }
        }

        /** Strings; "{" as [OPEN] and "}" as null. */
        private fun tokenize(text: String): Sequence<String?> = sequence {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c.isWhitespace() -> i++
                    c == '/' && i + 1 < text.length && text[i + 1] == '/' -> while (i < text.length && text[i] != '\n') i++
                    c == '{' -> { i++; yield(OPEN) }
                    c == '}' -> { i++; yield(null) }
                    c == '"' -> {
                        val sb = StringBuilder()
                        i++
                        while (i < text.length && text[i] != '"') {
                            if (text[i] == '\\' && i + 1 < text.length) {
                                sb.append(
                                    when (val n = text[i + 1]) {
                                        'n' -> '\n'
                                        't' -> '\t'
                                        else -> n
                                    },
                                )
                                i += 2
                            } else {
                                sb.append(text[i++])
                            }
                        }
                        i++
                        yield(sb.toString())
                    }
                    else -> {
                        val sb = StringBuilder()
                        while (i < text.length && !text[i].isWhitespace() && text[i] != '{' && text[i] != '}') sb.append(text[i++])
                        yield(sb.toString())
                    }
                }
            }
        }
    }
}
