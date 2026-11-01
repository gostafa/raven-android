package com.gostafa.raven.core.archive

import java.io.File
import java.io.RandomAccessFile

// java.util.zip does not expose Unix entry modes. Inspect them before opening streams.
internal fun validateZipPermissions(file: File, expectedEntries: Int) {
    RandomAccessFile(file, "r").use { input ->
        fun number(offset: Long, bytes: Int): Long {
            require(offset >= 0 && offset <= input.length() - bytes) { "Invalid ZIP directory" }
            input.seek(offset)
            var value = 0L
            repeat(bytes) { value = value or (input.readUnsignedByte().toLong() shl (8 * it)) }
            return value
        }
        var end = input.length() - 22
        val minimum = (end - 65535).coerceAtLeast(0)
        while (end >= minimum && !(number(end, 4) == 0x06054b50L && end + 22 + number(end + 20, 2) <= input.length())) end--
        require(end >= minimum) { "Missing ZIP directory" }
        var directory = number(end + 16, 4)
        if (directory == 0xffffffffL) {
            require(number(end - 20, 4) == 0x07064b50L) { "Invalid ZIP64 locator" }
            val zip64 = number(end - 12, 8)
            require(number(zip64, 4) == 0x06064b50L) { "Invalid ZIP64 directory" }
            directory = number(zip64 + 48, 8)
        }
        repeat(expectedEntries) {
            require(number(directory, 4) == 0x02014b50L) { "Invalid ZIP directory entry" }
            val creator = number(directory + 5, 1).toInt()
            val attributes = number(directory + 38, 4)
            val mode = (attributes ushr 16).toInt() and 0xf000
            val unix = creator == 3 || creator == 19
            if (unix) require(mode == 0 || mode == 0x8000 || mode == 0x4000) { "Links and special files are not permitted in archives" }
            val nameLength = number(directory + 28, 2)
            val extraLength = number(directory + 30, 2) + number(directory + 32, 2)
            if ((unix && mode == 0x4000) || (!unix && attributes and 0x10L != 0L)) {
                input.seek(directory + 46)
                val name = ByteArray(nameLength.toInt()).also { input.readFully(it) }.toString(Charsets.UTF_8)
                require(name == "media/") { "Unexpected archive directory" }
            }
            directory += 46 + nameLength + extraLength
            require(directory <= end) { "Invalid ZIP directory length" }
        }
    }
}
