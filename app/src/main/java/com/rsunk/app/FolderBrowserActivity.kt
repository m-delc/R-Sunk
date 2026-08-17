package com.rsunk.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

class FolderBrowserActivity : AppCompatActivity() {
    private data class FolderEntry(
        val name: String,
        val path: String,
        val selectionUri: Uri
    )

    private lateinit var searchBox: EditText
    private lateinit var statusView: TextView
    private lateinit var scanProgress: ProgressBar
    private lateinit var resultsContainer: LinearLayout
    private lateinit var resultsScroll: ScrollView

    @Volatile private var scanGeneration = 0
    private var allFolders: List<FolderEntry> = emptyList()
    private var target: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = intent.getStringExtra(EXTRA_TARGET)
        title = when (target) {
            TARGET_SOURCE -> "Choose source"
            TARGET_DESTINATION -> "Choose destination"
            else -> "Browse folders"
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = buildUi()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        scanGrantedFolders()
    }

    override fun onDestroy() {
        scanGeneration++
        super.onDestroy()
    }

    private fun buildUi(): ViewGroup {
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }

        content.addView(TextView(this).apply {
            text = when (target) {
                TARGET_SOURCE -> "Choose Source"
                TARGET_DESTINATION -> "Choose Destination"
                else -> "R-Sunk Folder Browser"
            }
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = "Search every folder beneath the trees you granted to R-Sunk. The Android folder picker is still available below for locations outside those trees."
            textSize = 14f
            setPadding(0, dp(4), 0, dp(14))
        })

        searchBox = EditText(this).apply {
            hint = "Search folder names or paths"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isEnabled = false
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderResults()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        content.addView(searchBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scanProgress = ProgressBar(this).apply { isIndeterminate = true }
        controls.addView(scanProgress, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
            marginEnd = dp(10)
        })
        statusView = TextView(this).apply {
            text = "Scanning granted folders…"
            textSize = 13f
        }
        controls.addView(statusView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(Button(this).apply {
            text = "RESCAN"
            setOnClickListener { scanGrantedFolders() }
        })
        content.addView(controls)

        resultsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultsScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(resultsContainer)
        }
        content.addView(resultsScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply { topMargin = dp(8) })

        content.addView(Button(this).apply {
            text = "USE ANDROID FOLDER PICKER"
            setOnClickListener { openAndroidPicker() }
        })
        content.addView(Button(this).apply {
            text = "CLOSE"
            setOnClickListener { finish() }
        })
        return content
    }

    private fun openAndroidPicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        @Suppress("DEPRECATION")
        startActivityForResult(picker, SYSTEM_PICKER_REQUEST)
    }

    @Deprecated("Deprecated in Android API but retained for broad compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SYSTEM_PICKER_REQUEST || resultCode != Activity.RESULT_OK) return
        val returnedIntent = data ?: return
        val uri = returnedIntent.data ?: return
        val flags = returnedIntent.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (flags != 0) {
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
                // Selection can still be usable for the current session.
            }
        }
        assignUri(uri, friendlyPath(uri))
    }

    private fun scanGrantedFolders() {
        val generation = ++scanGeneration
        allFolders = emptyList()
        searchBox.isEnabled = false
        scanProgress.visibility = View.VISIBLE
        statusView.text = "Scanning granted folders…"
        resultsContainer.removeAllViews()

        val grants = topLevelPersistedTrees()
        if (grants.isEmpty()) {
            scanProgress.visibility = View.GONE
            statusView.text = "No folder trees are granted. Use Android Folder Picker below to choose or grant one."
            return
        }

        Thread {
            val found = LinkedHashMap<String, FolderEntry>()
            var scanned = 0
            try {
                for (rootUri in grants) {
                    if (generation != scanGeneration) return@Thread
                    val root = DocumentFile.fromTreeUri(this, rootUri) ?: continue
                    if (!root.exists() || !root.isDirectory) continue
                    val rootPath = displayRootName(rootUri)
                    scanned += walkFolders(root, rootPath, found, generation)
                    runOnUiThread {
                        if (generation == scanGeneration) statusView.text = "Scanning… $scanned folders found"
                    }
                }
            } catch (_: Exception) {
                // Keep any folders already discovered usable.
            }

            val sorted = found.values.sortedWith(compareBy<FolderEntry>({ it.name.lowercase(Locale.US) }, { it.path.lowercase(Locale.US) }))
            runOnUiThread {
                if (generation != scanGeneration) return@runOnUiThread
                allFolders = sorted
                scanProgress.visibility = View.GONE
                searchBox.isEnabled = true
                statusView.text = "Indexed ${sorted.size} folders. Type to search."
                renderResults()
                searchBox.requestFocus()
            }
        }.start()
    }

    private fun walkFolders(
        dir: DocumentFile,
        path: String,
        found: LinkedHashMap<String, FolderEntry>,
        generation: Int
    ): Int {
        if (generation != scanGeneration) return 0
        var count = 0
        val selectionUri = dir.uri
        val key = selectionUri.toString()
        if (found.containsKey(key)) return 0
        found[key] = FolderEntry(dir.name ?: path.substringAfterLast('/'), path, selectionUri)
        count++

        val children = try { dir.listFiles() } catch (_: Exception) { emptyArray() }
        for (child in children) {
            if (generation != scanGeneration) break
            if (!child.isDirectory) continue
            val childName = child.name ?: continue
            count += walkFolders(child, "$path/$childName", found, generation)
        }
        return count
    }

    private fun renderResults() {
        if (!::resultsContainer.isInitialized) return
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()

        val query = if (::searchBox.isInitialized) searchBox.text.toString().trim().lowercase(Locale.US) else ""
        val matches = if (query.isBlank()) {
            allFolders.take(MAX_RESULTS)
        } else {
            allFolders.asSequence()
                .filter { it.name.lowercase(Locale.US).contains(query) || it.path.lowercase(Locale.US).contains(query) }
                .take(MAX_RESULTS)
                .toList()
        }
        val totalMatches = if (query.isBlank()) allFolders.size else allFolders.count {
            it.name.lowercase(Locale.US).contains(query) || it.path.lowercase(Locale.US).contains(query)
        }

        resultsContainer.removeAllViews()
        if (allFolders.isEmpty()) {
            resultsContainer.addView(TextView(this).apply {
                text = "No folders were found inside the granted trees."
                setPadding(0, dp(18), 0, dp(18))
            })
            return
        }
        if (matches.isEmpty()) {
            resultsContainer.addView(TextView(this).apply {
                text = "No folders match \"${searchBox.text}\"."
                setPadding(0, dp(18), 0, dp(18))
            })
            statusView.text = "No matches"
            return
        }

        statusView.text = when {
            query.isBlank() && totalMatches > MAX_RESULTS -> "Indexed ${allFolders.size} folders. Showing the first $MAX_RESULTS — search to narrow it down."
            totalMatches > MAX_RESULTS -> "$totalMatches matches. Showing the first $MAX_RESULTS."
            query.isBlank() -> "Indexed ${allFolders.size} folders."
            else -> "$totalMatches match${if (totalMatches == 1) "" else "es"}"
        }

        for (entry in matches) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            card.addView(TextView(this).apply {
                text = entry.name
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            })
            card.addView(TextView(this).apply {
                text = entry.path
                textSize = 12f
                setPadding(0, dp(2), 0, dp(5))
            })

            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            when (target) {
                TARGET_SOURCE -> actions.addView(actionButton("USE AS SOURCE") { assign(entry, true) })
                TARGET_DESTINATION -> actions.addView(actionButton("USE AS DESTINATION") { assign(entry, false) })
                else -> {
                    actions.addView(actionButton("SET SOURCE") { assign(entry, true) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    actions.addView(actionButton("SET DESTINATION") { assign(entry, false) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
                }
            }
            card.addView(actions)
            resultsContainer.addView(card)
        }
        resultsScroll.post { resultsScroll.scrollTo(0, 0) }
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun assign(entry: FolderEntry, source: Boolean) {
        if (SafFolders.openSelection(this, entry.selectionUri)?.isDirectory != true) {
            Toast.makeText(this, "R-Sunk can no longer access that folder. Rescan or renew folder access.", Toast.LENGTH_LONG).show()
            return
        }
        if (source) Prefs.setSource(this, entry.selectionUri) else Prefs.setDestination(this, entry.selectionUri)
        Toast.makeText(this, "${if (source) "Source" else "Destination"} set: ${entry.path}", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun assignUri(uri: Uri, path: String) {
        when (target) {
            TARGET_SOURCE -> Prefs.setSource(this, uri)
            TARGET_DESTINATION -> Prefs.setDestination(this, uri)
            else -> {
                Toast.makeText(this, "Choose Source or Destination from the main screen before using the Android picker.", Toast.LENGTH_LONG).show()
                return
            }
        }
        Toast.makeText(this, "${if (target == TARGET_SOURCE) "Source" else "Destination"} set: $path", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun canUseTreeUri(uri: Uri): Boolean = try {
        val doc = DocumentFile.fromTreeUri(this, uri)
        doc != null && doc.exists() && doc.isDirectory
    } catch (_: Exception) {
        false
    }

    private fun topLevelPersistedTrees(): List<Uri> {
        val raw = contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }
            .filter { canUseTreeUri(it) }
            .distinctBy { it.toString() }
        return raw.filter { candidate ->
            raw.none { other -> other != candidate && containsTree(other, candidate) }
        }.sortedBy { displayRootName(it).lowercase(Locale.US) }
    }

    private fun containsTree(parent: Uri, child: Uri): Boolean {
        if (parent.authority != child.authority) return false
        return try {
            val parentId = DocumentsContract.getTreeDocumentId(parent)
            val childId = DocumentsContract.getTreeDocumentId(child)
            if (parentId == childId) false
            else if (parent.authority == "com.android.externalstorage.documents") {
                childId.startsWith(parentId.trimEnd('/') + "/")
            } else false
        } catch (_: Exception) {
            false
        }
    }

    private fun displayRootName(uri: Uri): String {
        return try {
            val id = DocumentsContract.getTreeDocumentId(uri)
            if (uri.authority == "com.android.externalstorage.documents") {
                val relative = id.substringAfter(':', id).trim('/')
                relative.ifBlank { "Internal storage" }
            } else Uri.decode(id)
        } catch (_: Exception) {
            Uri.decode(uri.toString())
        }
    }

    private fun friendlyPath(uri: Uri): String {
        return try {
            val id = DocumentsContract.getTreeDocumentId(uri)
            if (uri.authority == "com.android.externalstorage.documents") {
                val relative = id.substringAfter(':', id).trim('/')
                if (relative.isBlank()) "Internal storage" else "Internal storage/$relative"
            } else Uri.decode(id)
        } catch (_: Exception) {
            Uri.decode(uri.toString())
        }
    }

    companion object {
        const val EXTRA_TARGET = "com.rsunk.app.extra.TARGET"
        const val TARGET_SOURCE = "source"
        const val TARGET_DESTINATION = "destination"
        private const val MAX_RESULTS = 200
        private const val SYSTEM_PICKER_REQUEST = 2001
    }
}
