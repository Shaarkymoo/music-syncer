package com.musicsyncer.engine

import java.io.InputStream
import java.security.MessageDigest

object Hashing {
    private const val CHUNK = 1 shl 20

    fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).toHex()

    fun sha256(stream: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(CHUNK)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().toHex()
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
