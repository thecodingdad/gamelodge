package io.github.thecodingdad.gamelodge.data.db

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/** Runs [block] in one write transaction (DAO calls inside take part in it). */
suspend fun <T> LauncherDatabase.withTransaction(block: suspend () -> T): T =
    useWriterConnection { it.immediateTransaction { block() } }
