package com.odin.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PitEntry(
    val binaryType: Int,
    val deviceType: Int,
    val partitionId: Int,
    val attributes: Int,
    val updateAttributes: Int,
    val blockSizeOrOffset: Int,
    val blockCount: Int,
    val fileOffset: Int,
    val fileSize: Int,
    val partitionName: String,
    val flashFilename: String,
    val fotaFilename: String
)

class PitData(
    val entries: List<PitEntry>,
    val rawBytes: ByteArray
)

object PitParser {
    private const val PIT_MAGIC = 0x12349876
    private const val HEADER_SIZE = 28
    private const val ENTRY_SIZE = 132

    fun parse(data: ByteArray): PitData {
        require(data.size >= HEADER_SIZE) { "PIT demasiado pequeño: ${data.size} bytes" }

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == PIT_MAGIC) { "Cabecera PIT inválida" }

        val count = buffer.int
        val maxEntries = (data.size - HEADER_SIZE) / ENTRY_SIZE
        require(count in 1..maxEntries) { "PIT incompleto o cantidad de entradas inválida: $count" }

        buffer.position(HEADER_SIZE)
        val entries = List(count) {
            PitEntry(
                binaryType = buffer.int,
                deviceType = buffer.int,
                partitionId = buffer.int,
                attributes = buffer.int,
                updateAttributes = buffer.int,
                blockSizeOrOffset = buffer.int,
                blockCount = buffer.int,
                fileOffset = buffer.int,
                fileSize = buffer.int,
                partitionName = readString(buffer),
                flashFilename = readString(buffer),
                fotaFilename = readString(buffer)
            )
        }
        return PitData(entries, data)
    }

    private fun readString(buffer: ByteBuffer): String {
        val bytes = ByteArray(32)
        buffer.get(bytes)
        val zero = bytes.indexOf(0.toByte())
        val length = if (zero >= 0) zero else bytes.size
        return String(bytes, 0, length, Charsets.US_ASCII).trim()
    }
}
