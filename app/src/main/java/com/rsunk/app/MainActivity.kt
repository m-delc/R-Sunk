package com.rsunk.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.documentfile.provider.DocumentFile
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private val sourceRequest = 1001
    private val destinationRequest = 1002
    private val exportRequest = 1003
    private val accessRequest = 1004
    private val browserRequest = 1005

    private lateinit var sourceLabel: TextView
    private lateinit var destinationLabel: TextView
    private lateinit var dryRun: CheckBox
    private lateinit var darkMode: CheckBox
    private lateinit var modeGroup: RadioGroup
    private lateinit var moveMode: RadioButton
    private lateinit var copyMode: RadioButton
    private lateinit var scopeGroup: RadioGroup
    private lateinit var selectedFolderScope: RadioButton
    private lateinit var contentsOnlyScope: RadioButton
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var sortButton: Button
    private lateinit var stopButton: Button
    private lateinit var exportButton: Button
    private lateinit var currentView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressView: TextView

    // The on-screen activity area is intentionally capped for performance.
    private val logLines = ArrayDeque<String>()

    // This retains the complete current activity log so exports are never truncated.
    private val fullLogLines = mutableListOf<String>()

    private var sortStartedAt = 0L
    @Volatile private var stopRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(
            if (Prefs.isDarkMode(this)) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)

        // v1.4.2 removes automatic transfers entirely. Cancel any periodic work
        // that may have been scheduled by an older R-Sunk version.
        WorkManager.getInstance(this).cancelUniqueWork(LEGACY_WORK_NAME)
        Prefs.setScheduled(this, false)

        title = "R-Sunk"
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = buildUi()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        refreshFolderLabels()
        handleIncomingShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    private fun buildUi(): ViewGroup {
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }

        content.addView(TextView(this).apply {
            text = "R-Sunk"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "Move or copy a selected folder, or only its contents, into another Android directory without overwriting existing files."
            textSize = 15f
            setPadding(0, dp(4), 0, dp(20))
        })

        content.addView(Button(this).apply {
            text = "TEST"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Test")
                    .setMessage("Test button works.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })

        content.addView(sectionTitle("Source folder"))
        sourceLabel = pathLabel()
        content.addView(sourceLabel)
        content.addView(Button(this).apply {
            text = "Choose Source"
            setOnClickListener { chooseFolderSmart(sourceRequest) }
        })

        content.addView(sectionTitle("Destination folder"))
        destinationLabel = pathLabel()
        content.addView(destinationLabel)
        content.addView(Button(this).apply {
            text = "Choose Destination"
            setOnClickListener { chooseFolderSmart(destinationRequest) }
        })

        content.addView(sectionTitle("Folder access"))
        content.addView(TextView(this).apply {
            text = "Grant R-Sunk persistent access to broader folder trees. Access applies to their subfolders and files."
            textSize = 13f
            setPadding(0, 0, 0, dp(6))
        })
        content.addView(Button(this).apply {
            text = "MANAGE FOLDER ACCESS"
            setOnClickListener { showFolderAccessManager() }
        })
        content.addView(Button(this).apply {
            text = "BROWSE / SEARCH GRANTED FOLDERS"
            setOnClickListener { openFolderBrowser() }
        })

        content.addView(sectionTitle("Transfer mode"))
        modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        moveMode = RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "Move"
        }
        copyMode = RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "Copy"
        }
        modeGroup.addView(moveMode)
        modeGroup.addView(copyMode)
        when (Prefs.transferMode(this)) {
            Sorter.TransferMode.MOVE -> moveMode.isChecked = true
            Sorter.TransferMode.COPY -> copyMode.isChecked = true
        }
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == copyMode.id) Sorter.TransferMode.COPY else Sorter.TransferMode.MOVE
            Prefs.setTransferMode(this@MainActivity, mode)
            updateModeLabels(mode)
        }
        content.addView(modeGroup)

        content.addView(sectionTitle("Transfer scope"))
        scopeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        selectedFolderScope = RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "Folder itself (including contents) — put this folder inside the destination"
        }
        contentsOnlyScope = RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "Contents only — put what is inside directly into the destination"
        }
        scopeGroup.addView(selectedFolderScope)
        scopeGroup.addView(contentsOnlyScope)
        when (Prefs.transferScope(this)) {
            Sorter.TransferScope.SELECTED_FOLDER -> selectedFolderScope.isChecked = true
            Sorter.TransferScope.CONTENTS_ONLY -> contentsOnlyScope.isChecked = true
        }
        scopeGroup.setOnCheckedChangeListener { _, checkedId ->
            val scope = if (checkedId == selectedFolderScope.id) {
                Sorter.TransferScope.SELECTED_FOLDER
            } else {
                Sorter.TransferScope.CONTENTS_ONLY
            }
            Prefs.setTransferScope(this@MainActivity, scope)
            updateModeLabels(Prefs.transferMode(this@MainActivity))
        }
        content.addView(scopeGroup)

        dryRun = CheckBox(this).apply {
            isChecked = true
            setPadding(0, dp(14), 0, 0)
        }
        content.addView(dryRun)

        darkMode = CheckBox(this).apply {
            text = "Dark mode"
            isChecked = Prefs.isDarkMode(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                if (checked == Prefs.isDarkMode(this@MainActivity)) return@setOnCheckedChangeListener
                Prefs.setDarkMode(this@MainActivity, checked)
                AppCompatDelegate.setDefaultNightMode(
                    if (checked) AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO
                )
            }
        }
        content.addView(darkMode)

        sortButton = Button(this).apply {
            textSize = 18f
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { runSort() }
        }
        updateModeLabels(Prefs.transferMode(this))
        content.addView(sortButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18) })

        stopButton = Button(this).apply {
            text = "STOP"
            isEnabled = false
            setOnClickListener {
                stopRequested = true
                isEnabled = false
                currentView.text = "Stopping after current file…"
                appendLog("Stop requested — finishing the current file, then stopping cleanly.")
            }
        }
        content.addView(stopButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        content.addView(sectionTitle("Progress"))
        currentView = TextView(this).apply {
            text = "Ready"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(8), dp(10), dp(6))
        }
        content.addView(currentView)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            progress = 0
        }
        content.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(18)
        ))

        progressView = TextView(this).apply {
            text = "0 / 0 files"
            textSize = 13f
            setPadding(dp(10), dp(4), dp(10), dp(8))
        }
        content.addView(progressView)

        content.addView(sectionTitle("Activity"))
        logView = TextView(this).apply {
            text = "Choose both folders. Dry run is enabled by default."
            textSize = 13f
            setTypeface(Typeface.MONOSPACE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        logLines.add("Choose both folders. Dry run is enabled by default.")
        fullLogLines.add("Choose both folders. Dry run is enabled by default.")

        logScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(logView)
        }
        content.addView(logScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(240)
        ))

        exportButton = Button(this).apply {
            text = "EXPORT FULL ACTIVITY"
            setOnClickListener { exportActivityLog() }
        }
        content.addView(exportButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        content.addView(sectionTitle("Wi-Fi transfer"))
        content.addView(TextView(this).apply {
            text = "Send folders to another R-Sunk device over the local network with encrypted transfer and SHA-256 verification."
            textSize = 13f
            setPadding(0, 0, 0, dp(6))
        })
        content.addView(Button(this).apply {
            text = "WI-FI TRANSFER"
            setOnClickListener { startActivity(Intent(this@MainActivity, WifiTransferActivity::class.java)) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        content.addView(Button(this).apply {
            text = "ABOUT"
            setOnClickListener { showAbout() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        return ScrollView(this).apply {
            addView(content)
        }
    }

    private fun sectionTitle(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 18, 0, 4)
    }

    private fun pathLabel() = TextView(this).apply {
        text = "Not selected"
        textSize = 13f
        gravity = Gravity.START
        setPadding(0, 0, 0, 4)
    }

    private fun chooseFolderSmart(requestCode: Int) {
        val hasGrant = contentResolver.persistedUriPermissions.any { it.isReadPermission }
        if (hasGrant) {
            openFolderBrowser(requestCode)
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Folder access needed")
            .setMessage(
                "Grant R-Sunk access to a broader folder tree first. After that, Choose Source and Choose Destination can search folders inside R-Sunk instead of using the GrapheneOS Files picker."
            )
            .setPositiveButton("Grant folder access") { _, _ -> chooseFolder(accessRequest) }
            .setNeutralButton("Use Android picker") { _, _ -> chooseFolder(requestCode) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseFolder(requestCode: Int, initialUri: Uri? = null) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            if (initialUri != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, requestCode)
    }

    private fun openFolderBrowser(targetRequestCode: Int? = null) {
        if (contentResolver.persistedUriPermissions.none { it.isReadPermission }) {
            AlertDialog.Builder(this)
                .setTitle("Folder access needed")
                .setMessage("Grant R-Sunk access to a broad folder tree first, then the browser can search all folders underneath it.")
                .setPositiveButton("Grant access") { _, _ -> chooseFolder(accessRequest) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val browserIntent = Intent(this, FolderBrowserActivity::class.java).apply {
            when (targetRequestCode) {
                sourceRequest -> putExtra(FolderBrowserActivity.EXTRA_TARGET, FolderBrowserActivity.TARGET_SOURCE)
                destinationRequest -> putExtra(FolderBrowserActivity.EXTRA_TARGET, FolderBrowserActivity.TARGET_DESTINATION)
            }
        }
        @Suppress("DEPRECATION")
        startActivityForResult(browserIntent, browserRequest)
    }

    private fun showFolderAccessManager() {
        val grants = contentResolver.persistedUriPermissions
            .filter { it.isReadPermission || it.isWritePermission }
            .sortedBy { friendlyPath(it.uri).lowercase(Locale.US) }

        val message = if (grants.isEmpty()) {
            "No persistent folder access has been granted yet.\n\nGrant a broader tree such as Pictures/Instagram to let R-Sunk work with folders underneath it."
        } else {
            "Persisted access:\n\n" + grants.joinToString("\n") { "• ${friendlyPath(it.uri)}" }
        }

        AlertDialog.Builder(this)
            .setTitle("Manage Folder Access")
            .setMessage(message)
            .setPositiveButton("Add folder access") { _, _ -> chooseFolder(accessRequest) }
            .apply {
                if (grants.isNotEmpty()) {
                    setNeutralButton("Revoke access") { _, _ -> showRevokeAccessDialog() }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showRevokeAccessDialog() {
        val grants = contentResolver.persistedUriPermissions
            .filter { it.isReadPermission || it.isWritePermission }
            .sortedBy { friendlyPath(it.uri).lowercase(Locale.US) }
        if (grants.isEmpty()) return

        val labels = grants.map { friendlyPath(it.uri) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Revoke Folder Access")
            .setItems(labels) { _, which ->
                val grant = grants[which]
                var flags = 0
                if (grant.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (grant.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    contentResolver.releasePersistableUriPermission(grant.uri, flags)
                    if (SafFolders.selectionUsesGrant(this, Prefs.source(this), grant.uri)) Prefs.clearSource(this)
                    if (SafFolders.selectionUsesGrant(this, Prefs.destination(this), grant.uri)) Prefs.clearDestination(this)
                    refreshFolderLabels()
                    appendLog("Folder access revoked: ${friendlyPath(grant.uri)}")
                } catch (e: Exception) {
                    appendLog("Could not revoke access: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleIncomingShare(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND) return
        val sharedUri = incoming.data ?: run {
            @Suppress("DEPRECATION")
            incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        } ?: incoming.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: return
        // Consume the share action so configuration changes (such as toggling dark mode)
        // do not show the same assignment dialog again.
        incoming.action = null

        AlertDialog.Builder(this)
            .setTitle("Use shared folder")
            .setMessage("Set this shared folder as the R-Sunk source or destination?")
            .setPositiveButton("Set as Source") { _, _ -> assignSharedFolder(sharedUri, sourceRequest, incoming.flags) }
            .setNeutralButton("Set as Destination") { _, _ -> assignSharedFolder(sharedUri, destinationRequest, incoming.flags) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun assignSharedFolder(sharedUri: Uri, requestCode: Int, incomingFlags: Int) {
        // DocumentsUI gives us a real tree/document URI. Fossify File Manager may instead
        // share a generic content URI. When its URI exposes the external-storage path, derive
        // the equivalent ExternalStorageProvider tree URI so an existing broader SAF grant can
        // be reused immediately, or so the system picker can open close to the shared folder.
        val treeUri = normalizeFolderUri(sharedUri) ?: externalStorageTreeCandidate(sharedUri)
        if (treeUri != null && canUseTreeUri(treeUri)) {
            val takeFlags = incomingFlags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if ((incomingFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0 && takeFlags != 0) {
                try {
                    contentResolver.takePersistableUriPermission(treeUri, takeFlags)
                } catch (_: Exception) {
                    // A broader persisted tree grant may already provide access.
                }
            }
            if (requestCode == sourceRequest) Prefs.setSource(this, treeUri)
            else Prefs.setDestination(this, treeUri)
            refreshFolderLabels()
            appendLog("${if (requestCode == sourceRequest) "Source" else "Destination"} set from Android share menu: ${friendlyPath(treeUri)}")
        } else {
            appendLog("Shared folder needs folder-access confirmation. Opening Android's folder picker.")
            chooseFolder(requestCode, treeUri ?: sharedUri)
        }
    }

    private fun externalStorageTreeCandidate(uri: Uri): Uri? {
        return try {
            val candidates = listOfNotNull(uri.path, Uri.decode(uri.toString()))
            val marker = "/storage/emulated/0/"
            val relative = candidates.firstNotNullOfOrNull { value ->
                val index = value.indexOf(marker)
                if (index >= 0) value.substring(index + marker.length).substringBefore('?').trim('/')
                else null
            } ?: return null
            if (relative.isBlank()) return null
            DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents",
                "primary:$relative"
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeFolderUri(uri: Uri): Uri? {
        return try {
            when {
                DocumentsContract.isTreeUri(uri) -> uri
                DocumentsContract.isDocumentUri(this, uri) &&
                    contentResolver.getType(uri) == DocumentsContract.Document.MIME_TYPE_DIR -> {
                    val authority = uri.authority ?: return null
                    DocumentsContract.buildTreeDocumentUri(authority, DocumentsContract.getDocumentId(uri))
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun canUseTreeUri(uri: Uri): Boolean = try {
        val doc = DocumentFile.fromTreeUri(this, uri)
        doc != null && doc.isDirectory && doc.exists()
    } catch (_: Exception) {
        false
    }

    private fun exportActivityLog() {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "R-Sunk-activity-$timestamp.txt")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, exportRequest)
    }

    @Deprecated("Deprecated in Android API but retained for broad compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        if (requestCode == browserRequest) {
            refreshFolderLabels()
            appendLog("Folder selection updated from R-Sunk Folder Browser.")
            return
        }
        val returnedIntent = data ?: return
        val uri = returnedIntent.data ?: return

        if (requestCode == exportRequest) {
            writeActivityExport(uri)
            return
        }

        val flags = returnedIntent.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (flags != 0) {
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
                appendLog("Warning: persistent folder permission was not granted.")
            }
        }

        when (requestCode) {
            sourceRequest -> {
                Prefs.setSource(this, uri)
                appendLog("Source set: ${friendlyPath(uri)}")
            }
            destinationRequest -> {
                Prefs.setDestination(this, uri)
                appendLog("Destination set: ${friendlyPath(uri)}")
            }
            accessRequest -> appendLog("Folder access granted: ${friendlyPath(uri)}")
        }
        refreshFolderLabels()
    }

    private fun writeActivityExport(uri: Uri) {
        try {
            val output = contentResolver.openOutputStream(uri, "w")
                ?: throw IllegalStateException("Unable to open export file")
            output.bufferedWriter().use { writer ->
                writer.write(buildExportText())
            }
            appendLog("Full activity exported successfully.")
        } catch (e: Exception) {
            appendLog("EXPORT FAILED: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun buildExportText(): String {
        val generated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("R-Sunk activity log")
            appendLine("Version: ${appVersionName()} (${appVersionCode()})")
            appendLine("Exported: $generated")
            appendLine("Source: ${Prefs.source(this@MainActivity)?.let(::friendlyPath) ?: "Not selected"}")
            appendLine("Destination: ${Prefs.destination(this@MainActivity)?.let(::friendlyPath) ?: "Not selected"}")
            appendLine("Mode: ${Prefs.transferMode(this@MainActivity).name.lowercase().replaceFirstChar { it.uppercase() }}")
            appendLine("Scope: ${if (Prefs.transferScope(this@MainActivity) == Sorter.TransferScope.SELECTED_FOLDER) "Selected folder" else "Contents only"}")
            appendLine("Dry run: ${dryRun.isChecked}")
            appendLine()
            append(fullLogLines.joinToString("\n"))
            appendLine()
        }
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("About R-Sunk")
            .setMessage(
                "R-Sunk\n\n" +
                    "Version ${appVersionName()} (${appVersionCode()})\n\n" +
                    "Manual folder move/copy utility for Android and GrapheneOS. " +
                    "Choose whether to transfer the selected folder itself or only its contents. " +
                    "R-Sunk supports persistent folder-tree access, recursive in-app folder search, compatible Android folder sharing, and encrypted local Wi-Fi transfer between R-Sunk devices. " +
                    "Wi-Fi transfers preserve relative folder paths and verify destination content with SHA-256. " +
                    "Manual local move/copy uses Android's Storage Access Framework and never overwrites an existing same-named file."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"
    } catch (_: Exception) {
        "Unknown"
    }

    private fun appVersionCode(): Long = try {
        packageManager.getPackageInfo(packageName, 0).longVersionCode
    } catch (_: Exception) {
        0L
    }

    private fun refreshFolderLabels() {
        sourceLabel.text = Prefs.source(this)?.let(::friendlyPath) ?: "Not selected"
        destinationLabel.text = Prefs.destination(this)?.let(::friendlyPath) ?: "Not selected"
    }

    private fun friendlyPath(uri: Uri): String {
        return try {
            val decodedId = when {
                DocumentsContract.isDocumentUri(this, uri) -> DocumentsContract.getDocumentId(uri)
                DocumentsContract.isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
                else -> return Uri.decode(uri.toString())
            }
            if (uri.authority == "com.android.externalstorage.documents") {
                val relative = Uri.decode(decodedId).substringAfter(':', Uri.decode(decodedId)).trim('/')
                if (relative.isBlank()) "/" else "/$relative"
            } else {
                Uri.decode(decodedId)
            }
        } catch (_: Exception) {
            Uri.decode(uri.toString())
        }
    }

    private fun runSort() {
        val source = Prefs.source(this)
        val destination = Prefs.destination(this)
        if (source == null || destination == null) {
            appendLog("Choose both source and destination folders first.")
            return
        }

        val isDryRun = dryRun.isChecked
        val mode = Prefs.transferMode(this)
        val scope = Prefs.transferScope(this)
        stopRequested = false
        sortButton.isEnabled = false
        stopButton.isEnabled = true
        dryRun.isEnabled = false
        darkMode.isEnabled = false
        modeGroup.isEnabled = false
        moveMode.isEnabled = false
        copyMode.isEnabled = false
        scopeGroup.isEnabled = false
        selectedFolderScope.isEnabled = false
        contentsOnlyScope.isEnabled = false
        val action = if (mode == Sorter.TransferMode.MOVE) "move" else "copy"
        clearLog(if (isDryRun) "Starting $action dry run…" else "Starting $action…")
        sortStartedAt = System.currentTimeMillis()
        currentView.text = "Scanning source…"
        progressBar.isIndeterminate = true
        progressView.text = "Counting files…"

        Thread {
            try {
                val result = Sorter.run(
                    context = this,
                    sourceUri = source,
                    destinationUri = destination,
                    dryRun = isDryRun,
                    mode = mode,
                    scope = scope,
                    log = { line -> runOnUiThread { appendLog(line) } },
                    progress = { p -> runOnUiThread { updateProgress(p, isDryRun, mode) } },
                    shouldStop = { stopRequested }
                )
                runOnUiThread {
                    appendLog("")
                    if (result.stopped) {
                        currentView.text = "Stopped cleanly"
                        appendLog(
                            if (mode == Sorter.TransferMode.MOVE)
                                "Stopped cleanly. Completed moves were kept; unprocessed items remain in the source."
                            else
                                "Stopped cleanly. Completed copies were kept; source files remain intact."
                        )
                    } else {
                        appendLog(
                            "Done. Folders transferred=${result.foldersTransferred}, merged=${result.foldersMerged}, " +
                                "files transferred=${result.filesTransferred}, conflicts=${result.conflictsSkipped}, failures=${result.failures}"
                        )
                        if (!isDryRun && mode == Sorter.TransferMode.MOVE &&
                            scope == Sorter.TransferScope.SELECTED_FOLDER && result.sourceFolderRemoved) {
                            Prefs.clearSource(this@MainActivity)
                            refreshFolderLabels()
                            appendLog("Source selection cleared because the selected folder itself was moved.")
                        }
                    }
                    stopButton.isEnabled = false
                    sortButton.isEnabled = true
                    dryRun.isEnabled = true
                    darkMode.isEnabled = true
                    modeGroup.isEnabled = true
                    moveMode.isEnabled = true
                    copyMode.isEnabled = true
                    scopeGroup.isEnabled = true
                    selectedFolderScope.isEnabled = true
                    contentsOnlyScope.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    currentView.text = "Stopped with an error"
                    progressBar.isIndeterminate = false
                    appendLog("ERROR: ${e.message ?: e.javaClass.simpleName}")
                    stopButton.isEnabled = false
                    sortButton.isEnabled = true
                    dryRun.isEnabled = true
                    darkMode.isEnabled = true
                    modeGroup.isEnabled = true
                    moveMode.isEnabled = true
                    copyMode.isEnabled = true
                    scopeGroup.isEnabled = true
                    selectedFolderScope.isEnabled = true
                    contentsOnlyScope.isEnabled = true
                }
            }
        }.start()
    }

    private fun updateModeLabels(mode: Sorter.TransferMode) {
        if (!::dryRun.isInitialized || !::sortButton.isInitialized) return
        val scope = if (::scopeGroup.isInitialized) Prefs.transferScope(this) else Sorter.TransferScope.CONTENTS_ONLY
        val scopeWord = if (scope == Sorter.TransferScope.SELECTED_FOLDER) "folder" else "contents"
        if (mode == Sorter.TransferMode.MOVE) {
            dryRun.text = "Dry run — show what would happen without moving $scopeWord"
            sortButton.text = if (scope == Sorter.TransferScope.SELECTED_FOLDER) "MOVE FOLDER NOW" else "MOVE CONTENTS NOW"
        } else {
            dryRun.text = "Dry run — show what would happen without copying $scopeWord"
            sortButton.text = if (scope == Sorter.TransferScope.SELECTED_FOLDER) "COPY FOLDER NOW" else "COPY CONTENTS NOW"
        }
    }

    private fun updateProgress(p: Sorter.Progress, isDryRun: Boolean, mode: Sorter.TransferMode) {
        currentView.text = when (p.phase) {
            Sorter.Phase.SCANNING -> "Scanning: ${p.current}"
            Sorter.Phase.TRANSFERRING -> if (isDryRun) "Checking: ${p.current}" else if (mode == Sorter.TransferMode.MOVE) "Moving: ${p.current}" else "Copying: ${p.current}"
            Sorter.Phase.DONE -> if (stopRequested) "Stopped" else "Finished"
        }

        if (p.phase == Sorter.Phase.SCANNING || p.total <= 0) {
            progressBar.isIndeterminate = true
            progressView.text = if (p.processed > 0) "Found ${p.processed} files so far…" else "Counting files…"
            return
        }

        progressBar.isIndeterminate = false
        progressBar.max = 1000
        progressBar.progress = ((p.processed.toDouble() / p.total.toDouble()) * 1000.0).toInt().coerceIn(0, 1000)

        val percent = ((p.processed.toDouble() / p.total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        val eta = estimateRemaining(p.processed, p.total)
        progressView.text = buildString {
            append("${p.processed} / ${p.total} files • $percent%")
            if (eta != null && p.phase != Sorter.Phase.DONE) append(" • about $eta remaining")
        }
    }

    private fun estimateRemaining(processed: Int, total: Int): String? {
        if (processed < 3 || total <= processed || sortStartedAt <= 0L) return null
        val elapsedMs = System.currentTimeMillis() - sortStartedAt
        if (elapsedMs <= 0L) return null
        val remainingMs = (elapsedMs.toDouble() / processed.toDouble() * (total - processed)).toLong()
        val seconds = max(1L, remainingMs / 1000L)
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    private fun clearLog(firstLine: String) {
        logLines.clear()
        fullLogLines.clear()
        logView.text = ""
        appendLog(firstLine)
    }

    private fun appendLog(line: String) {
        fullLogLines.add(line)
        logLines.addLast(line)
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        logView.text = logLines.joinToString("\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    companion object {
        private const val LEGACY_WORK_NAME = "rsunk_three_hour_sort"
        private const val MAX_LOG_LINES = 250
    }
}
