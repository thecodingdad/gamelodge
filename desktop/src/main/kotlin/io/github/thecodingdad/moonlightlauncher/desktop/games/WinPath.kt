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

    /** True when [path] lies inside [dir] (case-insensitive). */
    fun isUnder(path: String, dir: String): Boolean {
        val d = dir.replace('/', '\\').trimEnd('\\') + "\\"
        return path.replace('/', '\\').startsWith(d, ignoreCase = true)
    }
}
