package com.gostafa.raven.core.archive

import java.io.File

// Signature checks mirror Raven's HTTP sniffing and explicit ISO/AVIF/FLAC exceptions.
internal fun inspectMedia(file: File, mime: String) {
    val bytes = file.inputStream().use { it.readBytesAtMost(512) }
    fun at(offset: Int, value: String) = offset + value.length <= bytes.size &&
        value.indices.all { bytes[offset + it] == value[it].code.toByte() }
    val iso = bytes.size >= 12 && at(4, "ftyp")
    val boxSize = if (bytes.size >= 4) (0..3).fold(0L) { size, index -> (size shl 8) or (bytes[index].toLong() and 255) } else 0
    val matches = when (mime) {
        "image/jpeg" -> at(0, "\u00ff\u00d8\u00ff")
        "image/png" -> at(0, "\u0089PNG\r\n\u001a\n")
        "image/gif" -> at(0, "GIF87a") || at(0, "GIF89a")
        "image/webp" -> at(0, "RIFF") && at(8, "WEBPVP")
        "image/avif" -> iso && (at(8, "avif") || at(8, "avis"))
        "audio/wav" -> at(0, "RIFF") && at(8, "WAVE")
        "audio/mpeg" -> at(0, "ID3") // Go's DetectContentType recognizes ID3, not bare MPEG frames.
        "audio/ogg", "video/ogg" -> at(0, "OggS\u0000")
        "audio/flac" -> at(0, "fLaC")
        "audio/mp4", "video/mp4" -> iso && boxSize % 4 == 0L && boxSize <= bytes.size && (8 until boxSize.toInt() step 4).filter { it != 12 }.any { at(it, "mp4") }
        "video/quicktime" -> iso
        "video/webm" -> at(0, "\u001aE\u00df\u00a3")
        else -> false
    }
    require(matches) { "Attachment bytes do not match $mime" }
}

private fun java.io.InputStream.readBytesAtMost(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var offset = 0
    while (offset < limit) {
        val count = read(buffer, offset, limit - offset)
        if (count < 0) break
        offset += count
    }
    return buffer.copyOf(offset)
}
