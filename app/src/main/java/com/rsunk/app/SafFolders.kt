package com.rsunk.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/** Helpers for selecting a child folder while retaining the broader SAF tree grant. */
object SafFolders {
    fun openSelection(context: Context, uri: Uri): DocumentFile? {
        return try {
            if (DocumentsContract.isDocumentUri(context, uri) && DocumentsContract.isTreeUri(uri)) {
                val authority = uri.authority ?: return null
                val rootId = DocumentsContract.getTreeDocumentId(uri)
                val targetId = DocumentsContract.getDocumentId(uri)
                val rootTreeUri = DocumentsContract.buildTreeDocumentUri(authority, rootId)
                val root = DocumentFile.fromTreeUri(context, rootTreeUri) ?: return null
                if (rootId == targetId) return root

                // ExternalStorageProvider IDs expose a path. Walking only the relative path is
                // dramatically faster than recursively searching a tree with hundreds of folders.
                if (authority == "com.android.externalstorage.documents") {
                    val relative = relativeExternalPath(rootId, targetId)
                    if (relative != null) {
                        var current: DocumentFile = root
                        for (segment in relative) {
                            current = current.findFile(segment) ?: return null
                            if (!current.isDirectory) return null
                        }
                        return current
                    }
                }

                findDocumentById(context, root, targetId)
            } else {
                DocumentFile.fromTreeUri(context, uri)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun selectionUsesGrant(context: Context, selection: Uri?, grant: Uri): Boolean {
        if (selection == null) return false
        if (selection == grant) return true
        return try {
            if (!DocumentsContract.isTreeUri(selection) || !DocumentsContract.isTreeUri(grant)) return false
            selection.authority == grant.authority &&
                DocumentsContract.getTreeDocumentId(selection) == DocumentsContract.getTreeDocumentId(grant)
        } catch (_: Exception) {
            false
        }
    }

    private fun relativeExternalPath(rootId: String, targetId: String): List<String>? {
        val rootVolume = rootId.substringBefore(':', "")
        val targetVolume = targetId.substringBefore(':', "")
        if (rootVolume.isBlank() || rootVolume != targetVolume) return null

        val rootPath = rootId.substringAfter(':', "").trim('/')
        val targetPath = targetId.substringAfter(':', "").trim('/')
        if (targetPath == rootPath) return emptyList()
        val prefix = if (rootPath.isBlank()) "" else "$rootPath/"
        if (!targetPath.startsWith(prefix)) return null
        val relative = targetPath.removePrefix(prefix)
        return relative.split('/').filter { it.isNotBlank() }
    }

    private fun findDocumentById(context: Context, dir: DocumentFile, targetId: String): DocumentFile? {
        for (child in try { dir.listFiles() } catch (_: Exception) { emptyArray() }) {
            val childId = try { DocumentsContract.getDocumentId(child.uri) } catch (_: Exception) { null }
            if (childId == targetId) return child
            if (child.isDirectory) {
                val found = findDocumentById(context, child, targetId)
                if (found != null) return found
            }
        }
        return null
    }
}
