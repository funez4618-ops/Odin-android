package com.odin.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private companion object {
        const val SAMSUNG_VID = 0x04E8
        val DOWNLOAD_PIDS = setOf(0x685D, 0x68C3)
        const val ACTION_PERMISSION = "com.odin.android.USB_PERMISSION"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var usb: UsbManager
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scroll: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var btnScan: Button
    private lateinit var btnPit: Button
    private lateinit var btnCopy: Button
    private val allButtons = mutableListOf<Button>()
    private var target: UsbDevice? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val dev = IntentCompat.getParcelableExtra(
                        intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java
                    )
                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false
                    )
                    if (granted && dev != null) setTarget(dev)
                    else log("Permiso USB denegado.")
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scanDevices()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    target = null
                    setStatus("SIN CONEXIÓN", "#CFD8DC")
                    log("Dispositivo desconectado.")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        tvStatus = TextView(this).apply {
            textSize = 18f
            setPadding(20, 20, 20, 20)
            setTextColor(Color.BLACK)
        }
        root.addView(tvStatus)

        progress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
        }
        root.addView(progress)

        btnScan = Button(this).apply {
            text = "1. Buscar Samsung USB"
            setOnClickListener { scanDevices() }
        }
        btnPit = Button(this).apply {
            text = "2. Leer PIT (solo lectura)"
            setOnClickListener { readPit() }
        }
        btnCopy = Button(this).apply {
            text = "Copiar registro"
            setOnClickListener { copyLog() }
        }

        allButtons += listOf(btnScan, btnPit, btnCopy)
        listOf(btnScan, btnPit, btnCopy).forEach { root.addView(it) }

        tvLog = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.parseColor("#80CBC4"))
            setTextIsSelectable(true)
        }

        scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#102027"))
            setPadding(16, 16, 16, 16)
            addView(tvLog)
        }

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        setContentView(root)

        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        setStatus("SIN CONEXIÓN", "#CFD8DC")
        log("Odin Android 0.2 (diagnóstico).")
        log("Solo lee el PIT; NO flashea todavía.")
        log("Conecta el A8 en Modo Download con cable OTG.")
        scanDevices()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        scope.cancel()
    }

    private fun scanDevices() {
        if (usb.deviceList.isEmpty()) {
            log("No hay dispositivos USB. Revisa el adaptador OTG.")
            return
        }

        usb.deviceList.values.forEach {
            log(
                "USB: VID=0x${it.vendorId.toString(16)} " +
                    "PID=0x${it.productId.toString(16)}"
            )
        }

        val samsung = usb.deviceList.values.firstOrNull {
            it.vendorId == SAMSUNG_VID
        }

        if (samsung == null) {
            log("Ninguno es Samsung. Pon el A8 en Modo Download.")
            return
        }

        log("Samsung detectado: PID 0x${samsung.productId.toString(16).uppercase()}")

        if (samsung.productId !in DOWNLOAD_PIDS) {
            log("Aviso: ese PID no parece de Modo Download.")
        }

        if (usb.hasPermission(samsung)) setTarget(samsung)
        else requestPermission(samsung)
    }

    private fun requestPermission(dev: UsbDevice) {
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else 0

        val pi = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable
        )
        usb.requestPermission(dev, pi)
        log("Acepta el permiso USB en pantalla.")
    }

    private fun setTarget(dev: UsbDevice) {
        target = dev
        setStatus("CONECTADO  0:[COM]", "#4FC3F7")
        log("Listo. Pulsa '2. Leer PIT'.")
    }

    private fun setBusy(busy: Boolean) {
        allButtons.forEach { it.isEnabled = !busy }
        if (busy) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun readPit() {
        val dev = target ?: run {
            log("Primero conecta y autoriza el Samsung.")
            return
        }

        setBusy(true)
        progress.progress = 0
        setStatus("LEYENDO PIT...", "#FFF176")

        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val session = OdinSession(usb, dev) { msg -> log(msg) }
                var pitOk = false

                try {
                    session.open()
                    session.beginSession()
                    val pit = session.downloadPit()

                    log("--- TABLA PIT (${pit.entries.size} entradas) ---")
                    pit.entries.forEach {
                        log(
                            "id=${it.partitionId} b=${it.binaryType} " +
                                "d=${it.deviceType} " +
                                "${it.partitionName} -> ${it.flashFilename}"
                        )
                    }

                    val saved = session.savePit(
                        pit,
                        getExternalFilesDir(null) ?: filesDir
                    )
                    log("PIT guardado en: ${saved.absolutePath}")
                    pitOk = true

                    // Si el cierre de sesión no responde, no marcamos fallo:
                    // el PIT ya se leyó correctamente.
                    try {
                        session.endSession(reboot = false)
                    } catch (e: Exception) {
                        log("Aviso al cerrar sesión: ${e.message} (PIT ya leído).")
                    }
                } catch (e: Exception) {
                    log("ERROR: ${e.message}")
                } finally {
                    session.close()
                }

                pitOk
            }

            setStatus(
                if (ok) "PIT OK (solo lectura)" else "FALLO",
                if (ok) "#66BB6A" else "#EF5350"
            )
            if (ok) progress.progress = 100
            setBusy(false)
        }
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("odin_log", tvLog.text))
        Toast.makeText(this, "Registro copiado", Toast.LENGTH_SHORT).show()
    }

    private fun setStatus(text: String, colorHex: String) {
        tvStatus.text = text
        tvStatus.setBackgroundColor(Color.parseColor(colorHex))
    }

    private fun log(msg: String) = runOnUiThread {
        tvLog.append("$msg\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
