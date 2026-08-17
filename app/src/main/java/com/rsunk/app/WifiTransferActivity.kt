package com.rsunk.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class WifiTransferActivity : AppCompatActivity() {
    companion object {
        private const val SOURCE_REQUEST = 2101
        private const val DEST_REQUEST = 2102
        private const val SERVICE_TYPE = "_rsunk._tcp."
        private const val PROTOCOL = "RSUNK2"
        private const val BATCH_SIZE = 34
        private const val CHUNK_SIZE = 256 * 1024
    }

    private data class Peer(val name: String, val host: InetAddress, val port: Int) {
        override fun toString(): String = name
    }

    private data class SourceFile(val file: DocumentFile, val relativePath: String, val size: Long)

    private lateinit var sourceView: TextView
    private lateinit var destinationView: TextView
    private lateinit var pairingCode: EditText
    private lateinit var peerSpinner: Spinner
    private lateinit var includeRootFolder: CheckBox
    private lateinit var statusView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var sendButton: Button
    private lateinit var receiveButton: Button
    private lateinit var stopButton: Button

    private var sourceUri: Uri? = null
    private var destinationUri: Uri? = null
    private val peers = CopyOnWriteArrayList<Peer>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var serverSocket: ServerSocket? = null
    @Volatile private var stopRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "R-Sunk Wi-Fi Transfer"
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = buildUi()
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        startDiscovery()
    }

    override fun onDestroy() {
        stopRequested = true
        stopDiscovery()
        stopReceiver()
        super.onDestroy()
    }

    private fun buildUi(): ViewGroup {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
        }
        content.addView(TextView(this).apply {
            text = "Wi-Fi Transfer"
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "Encrypted Pixel-to-Pixel transfer on the same local network. R-Sunk preserves relative folder paths and verifies every completed file with SHA-256. Files are processed internally in batches of $BATCH_SIZE; no batch folders are created."
            textSize = 14f
            setPadding(0, dp(4), 0, dp(16))
        })

        content.addView(section("Send from this phone"))
        sourceView = pathView("No source selected")
        content.addView(sourceView)
        content.addView(Button(this).apply {
            text = "CHOOSE SOURCE FOLDER"
            setOnClickListener { pickTree(SOURCE_REQUEST) }
        })
        includeRootFolder = CheckBox(this).apply {
            text = "Create the selected source folder inside the destination"
            isChecked = true
        }
        content.addView(includeRootFolder)

        content.addView(section("Receive into this phone"))
        destinationView = pathView("No destination selected")
        content.addView(destinationView)
        content.addView(Button(this).apply {
            text = "CHOOSE DESTINATION FOLDER"
            setOnClickListener { pickTree(DEST_REQUEST) }
        })

        content.addView(section("Pairing"))
        pairingCode = EditText(this).apply {
            hint = "6-digit pairing code"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        content.addView(pairingCode)
        content.addView(TextView(this).apply {
            text = "On the receiving phone, tap Start Receiving. Enter the six-digit code shown there on the sending phone."
            textSize = 13f
        })

        content.addView(section("Nearby R-Sunk devices"))
        peerSpinner = Spinner(this)
        content.addView(peerSpinner)
        content.addView(Button(this).apply {
            text = "REFRESH DEVICES"
            setOnClickListener {
                stopDiscovery()
                peers.clear()
                refreshPeers()
                startDiscovery()
            }
        })

        sendButton = Button(this).apply {
            text = "SEND SOURCE OVER WI-FI"
            setOnClickListener { beginSend() }
        }
        content.addView(sendButton)
        receiveButton = Button(this).apply {
            text = "START RECEIVING"
            setOnClickListener { startReceiver() }
        }
        content.addView(receiveButton)
        stopButton = Button(this).apply {
            text = "STOP WI-FI TRANSFER"
            isEnabled = false
            setOnClickListener {
                stopRequested = true
                stopReceiver()
                status("Stop requested.")
            }
        }
        content.addView(stopButton)

        content.addView(section("Status"))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        content.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20)))
        statusView = TextView(this).apply {
            text = "Ready. Both phones must be on the same Wi-Fi/LAN."
            textSize = 13f
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        content.addView(statusView)
        content.addView(Button(this).apply {
            text = "CLOSE"
            setOnClickListener { finish() }
        })
        return ScrollView(this).apply { addView(content) }
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 18, 0, 4)
    }

    private fun pathView(initial: String) = TextView(this).apply {
        text = initial
        textSize = 13f
        gravity = Gravity.START
        setPadding(0, 0, 0, 6)
    }

    private fun pickTree(request: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, request)
    }

    @Deprecated("Deprecated API retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val flags = (data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        try { contentResolver.takePersistableUriPermission(uri, flags) } catch (_: Exception) {}
        when (requestCode) {
            SOURCE_REQUEST -> { sourceUri = uri; sourceView.text = friendlyPath(uri) }
            DEST_REQUEST -> { destinationUri = uri; destinationView.text = friendlyPath(uri) }
        }
    }

    private fun beginSend() {
        val rootUri = sourceUri ?: run { toast("Choose a source folder first."); return }
        val peer = peerSpinner.selectedItem as? Peer ?: run { toast("No receiving R-Sunk device discovered yet."); return }
        val code = pairingCode.text.toString().trim()
        if (!code.matches(Regex("\\d{6}"))) { toast("Enter the receiver's 6-digit pairing code."); return }
        stopRequested = false
        setBusy(true)
        status("Scanning source folder…")
        thread {
            try {
                val root = DocumentFile.fromTreeUri(this, rootUri) ?: error("Cannot open source folder")
                val files = mutableListOf<SourceFile>()
                walkSource(root, "", files)
                if (files.isEmpty()) error("Source folder contains no files")
                status("Found ${files.size} files. Connecting to ${peer.name}…")
                Socket(peer.host, peer.port).use { socket ->
                    socket.tcpNoDelay = true
                    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                    val key = senderHandshake(input, output, code)
                    val rootName = root.name ?: "Transferred folder"
                    val start = JSONObject().put("type", "manifest").put("count", files.size).put("batchSize", BATCH_SIZE)
                        .put("includeRoot", includeRootFolder.isChecked).put("rootName", rootName)
                    sendControl(output, key, start)
                    var verified = 0
                    var skipped = 0
                    var failed = 0
                    files.forEachIndexed { index, sf ->
                        if (stopRequested) return@forEachIndexed
                        val batch = index / BATCH_SIZE + 1
                        val batches = (files.size + BATCH_SIZE - 1) / BATCH_SIZE
                        status("Batch $batch/$batches • ${index + 1}/${files.size}\nHashing ${sf.relativePath}")
                        val hash = sha256(sf.file)
                        var fileVerified = false
                        var attempt = 0
                        while (!fileVerified && attempt < 3 && !stopRequested) {
                            attempt++
                            val meta = JSONObject()
                                .put("type", "file")
                                .put("path", sf.relativePath)
                                .put("size", sf.size)
                                .put("sha256", hash)
                                .put("mime", sf.file.type ?: "application/octet-stream")
                                .put("attempt", attempt)
                            sendControl(output, key, meta)
                            val pre = receiveControl(input, key)
                            if (pre.optString("status") == "skip") {
                                skipped++; verified++; fileVerified = true
                            } else {
                                sendFileChunks(output, key, sf.file)
                                sendControl(output, key, JSONObject().put("type", "eof"))
                                val result = receiveControl(input, key)
                                if (result.optString("status") == "verified") {
                                    verified++; fileVerified = true
                                } else if (attempt < 3) {
                                    status("SHA-256 mismatch for ${sf.relativePath}. Retrying ($attempt/3)…")
                                }
                            }
                        }
                        if (!fileVerified) failed++
                        progress(index + 1, files.size)
                    }
                    sendControl(output, key, JSONObject().put("type", "done"))
                    status("Transfer complete. $verified/${files.size} files verified${if (skipped > 0) " ($skipped already matched and were skipped)" else ""}${if (failed > 0) ". $failed failed verification." else ". 0 mismatches."}")
                }
            } catch (e: Exception) {
                status("Send failed: ${e.message ?: e.javaClass.simpleName}")
            } finally { setBusy(false) }
        }
    }

    private fun startReceiver() {
        val dest = destinationUri ?: run { toast("Choose a destination folder first."); return }
        stopReceiver()
        stopRequested = false
        val code = (100000..999999).random().toString()
        pairingCode.setText(code)
        pairingCode.setSelection(code.length)
        setBusy(true)
        thread {
            try {
                val server = ServerSocket(0)
                serverSocket = server
                registerReceiverService(server.localPort)
                status("Receiving is ready. Pairing code: $code\nWaiting for the other Pixel…")
                while (!stopRequested && !server.isClosed) {
                    val socket = server.accept()
                    socket.use { handleReceiverConnection(it, dest, code) }
                    if (!stopRequested) status("Transfer session ended. Still listening with pairing code $code.")
                }
            } catch (e: Exception) {
                if (!stopRequested) status("Receiver stopped: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                unregisterReceiverService()
                serverSocket = null
                setBusy(false)
            }
        }
    }

    private fun handleReceiverConnection(socket: Socket, destUri: Uri, code: String) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val key = receiverHandshake(input, output, code)
        val destRoot = DocumentFile.fromTreeUri(this, destUri) ?: error("Cannot open destination")
        val manifest = receiveControl(input, key)
        val total = manifest.getInt("count")
        val receiveRoot = if (manifest.optBoolean("includeRoot", true)) {
            val rootName = safeName(manifest.optString("rootName", "Transferred folder"))
            val existingRoot = destRoot.findFile(rootName)
            when {
                existingRoot == null -> destRoot.createDirectory(rootName) ?: error("Cannot create $rootName")
                existingRoot.isDirectory -> existingRoot
                else -> error("Destination contains a file named $rootName")
            }
        } else destRoot
        var processed = 0
        var verified = 0
        var mismatches = 0
        while (!stopRequested) {
            val msg = receiveControl(input, key)
            when (msg.getString("type")) {
                "done" -> {
                    status("Receive complete. $verified/$total files SHA-256 verified. $mismatches mismatches.")
                    return
                }
                "file" -> {
                    val rel = safeRelativePath(msg.getString("path"))
                    val size = msg.getLong("size")
                    val expected = msg.getString("sha256")
                    val mime = msg.optString("mime", "application/octet-stream")
                    status("Receiving ${processed + 1}/$total\n$rel")
                    val existing = findRelativeFile(receiveRoot, rel)
                    if (existing != null && existing.isFile && existing.length() == size && sha256(existing) == expected) {
                        sendControl(output, key, JSONObject().put("status", "skip"))
                        verified++; processed++; progress(processed, total)
                        continue
                    }
                    sendControl(output, key, JSONObject().put("status", "send"))
                    val target = createRelativeFile(receiveRoot, rel, mime)
                    val digest = MessageDigest.getInstance("SHA-256")
                    contentResolver.openOutputStream(target.uri, "wt")!!.use { raw ->
                        val out = BufferedOutputStream(raw)
                        var written = 0L
                        while (true) {
                            val packet = receivePacket(input, key)
                            if (packet.first == 0) {
                                val control = JSONObject(String(packet.second))
                                if (control.optString("type") == "eof") break
                                error("Unexpected control frame during file transfer")
                            }
                            val chunk = packet.second
                            out.write(chunk)
                            digest.update(chunk)
                            written += chunk.size
                            if (written > size) error("Received more data than expected for $rel")
                        }
                        out.flush()
                        if (written != size) error("Size mismatch for $rel ($written != $size)")
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (actual == expected) {
                        verified++
                        sendControl(output, key, JSONObject().put("status", "verified").put("sha256", actual))
                    } else {
                        mismatches++
                        try { target.delete() } catch (_: Exception) {}
                        sendControl(output, key, JSONObject().put("status", "mismatch").put("sha256", actual))
                    }
                    if (actual == expected) {
                        processed++; progress(processed, total)
                    }
                }
            }
        }
    }

    private fun walkSource(dir: DocumentFile, prefix: String, out: MutableList<SourceFile>) {
        val children = try { dir.listFiles() } catch (_: Exception) { emptyArray() }
        for (child in children) {
            if (stopRequested) return
            val name = child.name ?: continue
            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) walkSource(child, rel, out)
            else if (child.isFile) out.add(SourceFile(child, rel, child.length()))
        }
    }

    private fun sendFileChunks(output: DataOutputStream, key: SecretKey, file: DocumentFile) {
        contentResolver.openInputStream(file.uri)!!.use { raw ->
            val input = BufferedInputStream(raw)
            val buf = ByteArray(CHUNK_SIZE)
            while (!stopRequested) {
                val n = input.read(buf)
                if (n < 0) break
                sendData(output, key, if (n == buf.size) buf else buf.copyOf(n))
            }
        }
    }

    private fun sha256(file: DocumentFile): String {
        val md = MessageDigest.getInstance("SHA-256")
        contentResolver.openInputStream(file.uri)!!.use { raw ->
            val input = BufferedInputStream(raw)
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun senderHandshake(input: DataInputStream, output: DataOutputStream, code: String): SecretKey {
        output.writeUTF(PROTOCOL)
        val kp = newKeyPair()
        output.writeInt(kp.public.encoded.size); output.write(kp.public.encoded); output.flush()
        val len = input.readInt(); require(len in 1..4096) { "Bad receiver key" }
        val peer = ByteArray(len); input.readFully(peer)
        return deriveKey(kp, decodePublicKey(peer), code)
    }

    private fun receiverHandshake(input: DataInputStream, output: DataOutputStream, code: String): SecretKey {
        require(input.readUTF() == PROTOCOL) { "Unsupported R-Sunk protocol" }
        val len = input.readInt(); require(len in 1..4096) { "Bad sender key" }
        val peer = ByteArray(len); input.readFully(peer)
        val kp = newKeyPair()
        output.writeInt(kp.public.encoded.size); output.write(kp.public.encoded); output.flush()
        return deriveKey(kp, decodePublicKey(peer), code)
    }

    private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private fun decodePublicKey(bytes: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))

    private fun deriveKey(ours: KeyPair, theirs: PublicKey, code: String): SecretKey {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(ours.private); agreement.doPhase(theirs, true)
        val shared = agreement.generateSecret()
        val md = MessageDigest.getInstance("SHA-256")
        md.update("R-Sunk Wi-Fi Transfer v2".toByteArray())
        md.update(code.toByteArray())
        md.update(shared)
        return SecretKeySpec(md.digest(), "AES")
    }

    private fun sendControl(out: DataOutputStream, key: SecretKey, json: JSONObject) =
        sendPacket(out, key, 0, json.toString().toByteArray())

    private fun sendData(out: DataOutputStream, key: SecretKey, data: ByteArray) =
        sendPacket(out, key, 1, data)

    private fun receiveControl(input: DataInputStream, key: SecretKey): JSONObject {
        val packet = receivePacket(input, key)
        require(packet.first == 0) { "Expected control frame" }
        return JSONObject(String(packet.second))
    }

    private fun sendPacket(out: DataOutputStream, key: SecretKey, type: Int, payload: ByteArray) {
        val plain = ByteArray(payload.size + 1)
        plain[0] = type.toByte()
        System.arraycopy(payload, 0, plain, 1, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain)
        out.writeInt(iv.size); out.write(iv); out.writeInt(encrypted.size); out.write(encrypted); out.flush()
    }

    private fun receivePacket(input: DataInputStream, key: SecretKey): Pair<Int, ByteArray> {
        val ivLen = input.readInt(); require(ivLen in 12..32) { "Invalid encrypted frame" }
        val iv = ByteArray(ivLen); input.readFully(iv)
        val len = input.readInt(); require(len in 17..(CHUNK_SIZE + 4096)) { "Invalid frame length" }
        val encrypted = ByteArray(len); input.readFully(encrypted)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(encrypted)
        require(plain.isNotEmpty()) { "Empty encrypted frame" }
        return (plain[0].toInt() and 0xff) to plain.copyOfRange(1, plain.size)
    }

    private fun safeName(name: String): String {
        val clean = name.trim().replace('/', '_').replace('\\', '_')
        require(clean.isNotEmpty() && clean != "." && clean != "..") { "Unsafe folder name" }
        return clean
    }

    private fun safeRelativePath(path: String): String {
        val clean = path.replace('\\', '/').trim('/')
        require(clean.isNotEmpty() && clean.split('/').none { it == ".." || it.isEmpty() }) { "Unsafe path" }
        return clean
    }

    private fun findRelativeFile(root: DocumentFile, relative: String): DocumentFile? {
        var current = root
        val parts = relative.split('/')
        parts.forEachIndexed { i, p ->
            val found = current.findFile(p) ?: return null
            if (i == parts.lastIndex) return found
            if (!found.isDirectory) return null
            current = found
        }
        return null
    }

    private fun createRelativeFile(root: DocumentFile, relative: String, mime: String): DocumentFile {
        val parts = relative.split('/')
        var current = root
        for (i in 0 until parts.lastIndex) {
            val name = parts[i]
            val existing = current.findFile(name)
            current = when {
                existing == null -> current.createDirectory(name) ?: error("Cannot create folder $name")
                existing.isDirectory -> existing
                else -> error("A file blocks destination folder $name")
            }
        }
        val name = parts.last()
        val old = current.findFile(name)
        if (old != null) old.delete()
        return current.createFile(if (mime.isBlank()) "application/octet-stream" else mime, name)
            ?: error("Cannot create destination file $relative")
    }

    private fun startDiscovery() {
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { status("Device discovery failed ($errorCode). You can retry with Refresh Devices.") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                peers.removeAll { it.name == serviceInfo.serviceName }
                refreshPeers()
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE) return
                @Suppress("DEPRECATION")
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host ?: return
                        peers.removeAll { it.name == info.serviceName }
                        peers.add(Peer(info.serviceName, host, info.port))
                        refreshPeers()
                    }
                })
            }
        }
        discoveryListener = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun stopDiscovery() {
        val l = discoveryListener ?: return
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        runCatching { nsd.stopServiceDiscovery(l) }
        discoveryListener = null
    }

    private fun registerReceiverService(port: Int) {
        unregisterReceiverService()
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply {
            serviceName = "R-Sunk-${android.os.Build.MODEL.replace(' ', '-')}"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) { status("Receiving as ${serviceInfo.serviceName}.\nPairing code: ${pairingCode.text}") }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { status("Receiver discovery registration failed ($errorCode).") }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun unregisterReceiverService() {
        val l = registrationListener ?: return
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        runCatching { nsd.unregisterService(l) }
        registrationListener = null
    }

    private fun stopReceiver() {
        stopRequested = true
        runCatching { serverSocket?.close() }
        serverSocket = null
        unregisterReceiverService()
    }

    private fun refreshPeers() = runOnUiThread {
        val list = peers.toList().sortedBy { it.name.lowercase(Locale.US) }
        peerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, list)
    }

    private fun status(text: String) = runOnUiThread { if (::statusView.isInitialized) statusView.text = text }
    private fun progress(done: Int, total: Int) = runOnUiThread { progress.progress = if (total == 0) 0 else (done * 100 / total) }
    private fun setBusy(busy: Boolean) = runOnUiThread {
        if (::sendButton.isInitialized) sendButton.isEnabled = !busy
        if (::receiveButton.isInitialized) receiveButton.isEnabled = !busy
        if (::stopButton.isInitialized) stopButton.isEnabled = busy
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun friendlyPath(uri: Uri): String = try {
        val id = if (DocumentsContract.isTreeUri(uri)) DocumentsContract.getTreeDocumentId(uri) else uri.toString()
        Uri.decode(id).replace(':', '/')
    } catch (_: Exception) { uri.toString() }
}
