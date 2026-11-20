package com.suarez.coder.repomap

import java.io.InputStream

/**
 * Minimal streaming reader for (gzip-decompressed) tar archives as served by GitHub's tarball endpoint.
 * Supports ustar prefixes, PAX `path=` records and GNU long names. Contents are only read when requested.
 */
object TarStream {
    class Entry(val path: String, val size: Long, private val reader: () -> ByteArray) {
        fun read(): ByteArray = reader()
    }

    fun forEachFile(input: InputStream, stripFirstComponent: Boolean = true, visit: (Entry) -> Unit) {
        val header = ByteArray(512)
        var longName: String? = null
        var zeroBlocks = 0
        while (true) {
            if (!readFully(input, header)) return
            if (header.all { it == 0.toByte() }) {
                if (++zeroBlocks >= 2) return else continue
            }
            zeroBlocks = 0
            val name = cString(header, 0, 100)
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val prefix = if (String(header, 257, 5, Charsets.US_ASCII) == "ustar") cString(header, 345, 155) else ""
            val padded = (size + 511) / 512 * 512
            when (type) {
                'x', 'L' -> {
                    val data = readBytes(input, size)
                    skip(input, padded - size)
                    longName = if (type == 'L') String(data, Charsets.UTF_8).trimEnd('\u0000') else paxPath(data) ?: longName
                    continue
                }
                'g' -> { skip(input, padded); continue } // global header (GitHub puts the commit sha here)
            }
            var path = longName ?: if (prefix.isNotEmpty()) "$prefix/$name" else name
            longName = null
            if (stripFirstComponent) path = path.substringAfter('/', "")
            val isFile = type == '0' || type == '\u0000' || type == '7'
            if (!isFile || path.isEmpty()) { skip(input, padded); continue }
            var consumed = false
            var cached: ByteArray? = null
            visit(Entry(path, size) {
                cached ?: readBytes(input, size).also { cached = it; consumed = true }
            })
            skip(input, if (consumed) padded - size else padded)
        }
    }

    private fun paxPath(data: ByteArray): String? {
        // Records: "<len> key=value\n"
        val s = String(data, Charsets.UTF_8)
        var i = 0
        while (i < s.length) {
            val sp = s.indexOf(' ', i)
            if (sp < 0) break
            val len = s.substring(i, sp).toIntOrNull() ?: break
            val rec = s.substring(sp + 1, minOf(s.length, i + len)).trimEnd('\n')
            if (rec.startsWith("path=")) return rec.removePrefix("path=")
            i += len
        }
        return null
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        val s = String(b, off, len, Charsets.US_ASCII).trim { it == ' ' || it == '\u0000' }
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) return n == buf.size
            n += r
        }
        return true
    }

    private fun readBytes(input: InputStream, size: Long): ByteArray {
        val buf = ByteArray(size.toInt())
        readFully(input, buf)
        return buf
    }

    private fun skip(input: InputStream, count: Long) {
        var left = count
        val tmp = ByteArray(8192)
        while (left > 0) {
            val r = input.read(tmp, 0, minOf(tmp.size.toLong(), left).toInt())
            if (r < 0) return
            left -= r
        }
    }
}
