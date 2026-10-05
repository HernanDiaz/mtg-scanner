package com.hernan.mtgscanner.data

import okio.BufferedSink
import okio.Buffer

/**
 * Zip sin comprimir (método "stored"): las fotos JPEG ya están comprimidas y el CSV es
 * pequeño, así que no merece la pena. Lo abre cualquier programa (Windows, macOS, Drive...).
 * Común a Android e iPhone. Sin ZIP64: hasta 4 GB y 65 535 ficheros.
 */
class ZipWriter(private val sink: BufferedSink) : AutoCloseable {
    private class Entry(val name: ByteArray, val crc: Int, val size: Int, val offset: Long)

    private val entries = ArrayList<Entry>()
    private var offset = 0L

    /** Añade un fichero con [data] como contenido. */
    fun add(name: String, data: ByteArray) {
        val nameBytes = name.encodeToByteArray()
        val crc = crc32(data)
        entries += Entry(nameBytes, crc, data.size, offset)
        val header = Buffer().apply {
            writeIntLe(0x04034b50)
            writeShortLe(20) // versión necesaria
            writeShortLe(FLAG_UTF8)
            writeShortLe(0) // stored
            writeShortLe(0); writeShortLe(DOS_DATE_1980)
            writeIntLe(crc)
            writeIntLe(data.size); writeIntLe(data.size)
            writeShortLe(nameBytes.size); writeShortLe(0)
            write(nameBytes)
        }
        offset += header.size + data.size
        sink.writeAll(header)
        sink.write(data)
    }

    override fun close() {
        val start = offset
        val dir = Buffer()
        for (e in entries) {
            dir.writeIntLe(0x02014b50)
            dir.writeShortLe(20); dir.writeShortLe(20)
            dir.writeShortLe(FLAG_UTF8)
            dir.writeShortLe(0)
            dir.writeShortLe(0); dir.writeShortLe(DOS_DATE_1980)
            dir.writeIntLe(e.crc)
            dir.writeIntLe(e.size); dir.writeIntLe(e.size)
            dir.writeShortLe(e.name.size); dir.writeShortLe(0); dir.writeShortLe(0)
            dir.writeShortLe(0); dir.writeShortLe(0)
            dir.writeIntLe(0)
            dir.writeIntLe(e.offset.toInt())
            dir.write(e.name)
        }
        val dirSize = dir.size
        dir.writeIntLe(0x06054b50)
        dir.writeShortLe(0); dir.writeShortLe(0)
        dir.writeShortLe(entries.size); dir.writeShortLe(entries.size)
        dir.writeIntLe(dirSize.toInt())
        dir.writeIntLe(start.toInt())
        dir.writeShortLe(0)
        sink.writeAll(dir)
        sink.close()
    }

    private companion object {
        /** Nombres en UTF-8 (tildes en los nombres de las fotos). */
        const val FLAG_UTF8 = 1 shl 11
        /** 1-1-1980, la fecha más baja que admite el formato. */
        const val DOS_DATE_1980 = (0 shl 9) or (1 shl 5) or 1

        val CRC_TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            c
        }

        fun crc32(data: ByteArray): Int {
            var c = -1
            for (b in data) c = CRC_TABLE[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
            return c.inv()
        }
    }
}
