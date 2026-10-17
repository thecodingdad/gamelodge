package io.github.thecodingdad.moonlightlauncher.desktop.games

/** Path helpers that treat both '\' and '/' as separators (Windows paths, tested on Linux). */
object WinPath {
    private val separators = charArrayOf('\\', '/')

    fun fileName(path: String): String = path.trimEnd(*separators).split(*separators).last()

    fun extension(path: String): String {
        val name = fileName(path)
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot).lowercase() else ""
    }

    fun fileNameWithoutExtension(path: String): String = fileName(path).let { n -> n.lastIndexOf('.').takeIf { it > 0 }?.let { n.substring(0, it) } ?: n }

    /** The folder [path] lies in; null for a bare name. */
    fun directory(path: String): String? {
        val trimmed = path.trimEnd(*separators)
        val i = trimmed.lastIndexOfAny(separators)
        return if (i > 0) trimmed.substring(0, i) else null
    }

    /** True when [path] lies inside [dir] (case-insensitive). */
    fun isUnder(path: String, dir: String): Boolean {
        val d = dir.replace('/', '\\').trimEnd('\\') + "\\"
        return path.replace('/', '\\').startsWith(d, ignoreCase = true)
    }
}
