package com.odin.android

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OdinSession(
    private val usb: UsbManager,
    private val device: UsbDevice,
    private val log: (String) -> Unit
) {
    companion object {
        const val PKT_SESSION = 0x64
        const val PKT_PIT = 0x65
        const val PKT_END = 0x67

        const val SESSION_BEGIN = 0
        const val SESSION_END_ONLY = 0
        const val SESSION_END_REBOOT = 1

        const val PIT_DUMP = 1
        const val PIT_PART = 2
        const val PIT_END = 3

        const val CONTROL_PACKET_SIZE = 1024
        const val RESPONSE_SIZE = 8
        const val PIT_CHUNK = 500
    }

    private var conn: UsbDeviceConnection? = null
    private var commIface: UsbInterface? = null
    private var dataIface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var sessionBegun = false
    var protocolVersion = 0
        private set

    fun open() {
        val c = usb.openDevice(device) ?: error("Sin permiso USB")

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            when (intf.interfaceClass) {
                UsbConstants.USB_CLASS_COMM -> commIface = intf
                UsbConstants.USB_CLASS_CDC_DATA -> dataIface = intf
            }
        }

        // Respaldo si el dispositivo expone endpoints bulk en otra interfaz
        if (dataIface == null) {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                var hasIn = false
                var hasOut = false
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) hasIn = true else hasOut = true
                    }
                }
                if (hasIn && hasOut) {
                    dataIface = intf
                    break
                }
            }
        }

        val intf = dataIface ?: run {
            c.close()
            error("No hay interfaz de datos USB. ¿Está el A8 en modo Download?")
        }

        commIface?.let { c.claimInterface(it, true) }
        if (!c.claimInterface(intf, true)) {
            c.close()
            error("No se pudo reclamar la interfaz USB")
        }

        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
        }

        val inp = epIn
        val out = epOut
        if (inp == null || out == null) {
            c.close()
            error("No se encontraron endpoints bulk IN/OUT")
        }

        // Activar línea CDC y limpiar posibles bytes residuales
        c.controlTransfer(0x21, 0x22, 0x03, 0, null, 0, 1000)
        val drain = ByteArray(512)
        c.bulkTransfer(inp, drain, drain.size, 100)

        conn = c
        log("Interfaz USB abierta (PID 0x${device.productId.toString(16).uppercase()}).")
    }

    fun handshake(): String {
        val c = conn ?: error("Conexión no abierta")
        val out = epOut ?: error("Sin endpoint de salida")
        val inp = epIn ?: error("Sin endpoint de entrada")

        val odin = "ODIN".toByteArray(Charsets.US_ASCII)
        val sent = c.bulkTransfer(out, odin, odin.size, 3000)
        check(sent == odin.size) { "No se pudo enviar ODIN (resultado=$sent)" }

        val buf = ByteArray(512)
        var n = 0
        for (attempt in 0 until 4) {
            n = c.bulkTransfer(inp, buf, buf.size, 3000)
            if (n > 0) break
        }
        check(n >= 4) {
            "Sin respuesta al handshake (resultado=$n). Si ya lo probaste antes, reinicia el modo Download del A8."
        }
        return String(buf, 0, n, Charsets.US_ASCII)
    }

    private fun writePacket(id: Int, sub: Int, data: Int? = null) {
        val c = conn ?: error("Conexión no abierta")
        val out = epOut ?: error("Sin endpoint de salida")
        val pkt = ByteBuffer.allocate(CONTROL_PACKET_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0, id)
            .putInt(4, sub)
            .apply { if (data != null) putInt(8, data) }
            .array()
        val sent = c.bulkTransfer(out, pkt, pkt.size, 5000)
        check(sent == CONTROL_PACKET_SIZE) {
            "Fallo enviando comando 0x${id.toString(16)} (resultado=$sent)"
        }
    }

    private fun readExact(buf: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
        val c = conn ?: error("Conexión no abierta")
        val inp = epIn ?: error("Sin endpoint de entrada")
        var off = offset
        val end = offset + length
        var emptyRetries = 0
        while (off < end) {
            val n = c.bulkTransfer(inp, buf, off, end - off, timeoutMs)
            if (n == 0) {
                emptyRetries++
                check(emptyRetries <= 5) { "Paquetes USB vacíos repetidos ($off/$end bytes)" }
                continue
            }
            check(n > 0) { "Recepción USB incompleta ($off/$end bytes, resultado=$n)" }
            off += n
        }
    }

    private fun readResponse(timeoutMs: Int = 5000): Pair<Int, Int> {
        val buf = ByteArray(RESPONSE_SIZE)
        readExact(buf, 0, RESPONSE_SIZE, timeoutMs)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        return bb.int to bb.int
    }

    private fun command(id: Int, sub: Int, data: Int? = null, timeoutMs: Int = 5000): Pair<Int, Int> {
        writePacket(id, sub, data)
        return readResponse(timeoutMs)
    }

    fun beginSession() {
        val hs = handshake()
        log("Handshake recibido: '${hs.trim()}'")
        check(hs.startsWith("LOKE")) { "Respuesta inesperada al handshake: '$hs'" }

        val (id, data) = command(PKT_SESSION, SESSION_BEGIN)
        check(id == PKT_SESSION) { "Respuesta inesperada a BeginSession (id=0x${id.toString(16)})" }
        protocolVersion = (data ushr 16) and 0xFFFF
        sessionBegun = true
        log("Sesión iniciada (protocolo v$protocolVersion, raw=0x${Integer.toHexString(data)}).")
    }

    /** Solo lectura: descarga la tabla de particiones del dispositivo. */
    fun downloadPit(): PitData {
        check(sessionBegun) { "La sesión no está iniciada" }
        val c = conn ?: error("Conexión no abierta")
        val inp = epIn ?: error("Sin endpoint de entrada")

        val (id, size) = command(PKT_PIT, PIT_DUMP)
        check(id == PKT_PIT) { "Respuesta inesperada a PIT dump (id=0x${id.toString(16)})" }
        check(size in 1..(1 shl 20)) { "Tamaño de PIT inválido: $size bytes" }
        log("Descargando PIT ($size bytes)...")

        val parts = (size + PIT_CHUNK - 1) / PIT_CHUNK
        val raw = ByteArray(size)
        val chunk = ByteArray(1024)
        var off = 0

        for (part in 0 until parts) {
            writePacket(PKT_PIT, PIT_PART, part)
            val expected = minOf(PIT_CHUNK, size - off)
            var gotForPart = 0
            var emptyRetries = 0
            while (gotForPart < expected) {
                val n = c.bulkTransfer(inp, chunk, chunk.size, 5000)
                if (n == 0) {
                    emptyRetries++
                    check(emptyRetries <= 5) { "Sin datos en bloque PIT #$part" }
                    continue
                }
                check(n > 0) { "Error USB leyendo bloque PIT #$part (resultado=$n)" }
                val toCopy = minOf(n, size - off)
                if (toCopy > 0) {
                    System.arraycopy(chunk, 0, raw, off, toCopy)
                    off += toCopy
                }
                gotForPart += n
            }
        }

        val (endId, _) = command(PKT_PIT, PIT_END)
        check(endId == PKT_PIT) { "Error finalizando descarga de PIT (id=0x${endId.toString(16)})" }

        val pit = PitParser.parse(raw)
        log("PIT recibido: ${pit.entries.size} particiones.")
        return pit
    }

    fun savePit(pit: PitData, dir: File): File {
        val f = File(dir, "a8_pit.bin")
        f.writeBytes(pit.rawBytes)
        return f
    }

    fun endSession(reboot: Boolean) {
        if (!sessionBegun) return
        command(PKT_END, SESSION_END_ONLY)
        if (reboot) command(PKT_END, SESSION_END_REBOOT)
        sessionBegun = false
    }

    fun close() {
        try { dataIface?.let { conn?.releaseInterface(it) } } catch (_: Exception) {}
        try { commIface?.let { conn?.releaseInterface(it) } } catch (_: Exception) {}
        try { conn?.close() } catch (_: Exception) {}
        conn = null
        dataIface = null
        commIface = null
    }
}
