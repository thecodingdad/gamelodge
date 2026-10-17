package io.github.thecodingdad.moonlightlauncher.host

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** Only the owner may read [file] (POSIX; on Windows the user's profile folder already is private). */
fun restrictToOwner(file: File) {
    runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
}
