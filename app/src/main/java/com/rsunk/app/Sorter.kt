package com.rsunk.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

object Sorter {
    enum class TransferMode { MOVE, COPY }
    enum class TransferScope { SELECTED_FOLDER, CONTENTS_ONLY }

    data class Result(
        var foldersTransferred: Int = 0,
        var foldersMerged: Int = 0,
        var filesTransferred: Int = 0,
        var conflictsSkipped: Int = 0,
        var failures: Int = 0,
        var stopped: Boolean = false,
        var sourceFolderRemoved: Boolean = false
    )

    data class Progress(
        val processed: Int,
        val total: Int,
        val current: String,
        val phase: Phase
    )

    enum class Phase { SCANNING, TRANSFERRING, DONE }

    private data class Scan(
        val fileCounts: MutableMap<String, Int> = mutableMapOf(),
        var totalFiles: Int = 0
    )

    fun run(
        context: Context,
        sourceUri: Uri,
        destinationUri: Uri,
        dryRun: Boolean,
        mode: TransferMode = TransferMode.MOVE,
        scope: TransferScope = TransferScope.CONTENTS_ONLY,
        log: (String) -> Unit = {},
        progress: ((Progress) -> Unit)? = null,
        shouldStop: () -> Boolean = { false }
    ): Result {
        val result = Result()
        val resolver = context.contentResolver
        val source = SafFolders.openSelection(context, sourceUri)
            ?: throw IllegalArgumentException("Cannot open source folder")
        val destination = SafFolders.openSelection(context, destinationUri)
            ?: throw IllegalArgumentException("Cannot open destination folder")

        require(source.isDirectory) { "Source is not a directory" }
        require(destination.isDirectory) { "Destination is not a directory" }
        require(source.uri != destination.uri) { "Source and destination cannot be the same folder" }

        progress?.invoke(Progress(0, 0, "Scanning source…", Phase.SCANNING))
        val scan = Scan()
        scanDirectory(source, scan, shouldStop) { current, seen ->
            progress?.invoke(Progress(seen, 0, current, Phase.SCANNING))
        }
        if (shouldStop()) {
            result.stopped = true
            progress?.invoke(Progress(0, scan.totalFiles, "Stopped", Phase.DONE))
            return result
        }

        if (source.listFiles().isEmpty()) log("Source folder is empty.")

        var processed = 0
        fun report(path: String, amount: Int = 0) {
            processed = (processed + amount).coerceAtMost(scan.totalFiles)
            progress?.invoke(Progress(processed, scan.totalFiles, path, Phase.TRANSFERRING))
        }

        val sourceName = source.name ?: "source"
        when (scope) {
            TransferScope.CONTENTS_ONLY -> {
                // Transfer the items inside the selected source directly into destination.
                // The selected source directory itself stays in place.
                mergeDirectory(
                    resolver, source, destination, dryRun, mode, result, log, scan, sourceName,
                    { pathValue, amount -> report(pathValue, amount) }, shouldStop
                )
            }

            TransferScope.SELECTED_FOLDER -> {
                // Transfer the selected source directory as a named folder under destination.
                // If destination already has a same-named directory, merge into it safely.
                transferSelectedFolder(
                    resolver, source, destination, dryRun, mode, result, log, scan, sourceName,
                    { pathValue, amount -> report(pathValue, amount) }, shouldStop
                )
            }
        }

        progress?.invoke(
            Progress(
                processed,
                scan.totalFiles,
                if (result.stopped) "Stopped" else "Finished",
                Phase.DONE
            )
        )
        return result
    }

    private fun transferSelectedFolder(
        resolver: ContentResolver,
        source: DocumentFile,
        destination: DocumentFile,
        dryRun: Boolean,
        mode: TransferMode,
        result: Result,
        log: (String) -> Unit,
        scan: Scan,
        sourceName: String,
        onProcessed: (String, Int) -> Unit,
        shouldStop: () -> Boolean
    ) {
        if (shouldStop()) {
            result.stopped = true
            return
        }

        val totalFiles = scan.fileCounts[source.uri.toString()] ?: scan.totalFiles
        val existing = destination.listFiles().firstOrNull { it.name == sourceName }
        val verb = if (mode == TransferMode.MOVE) "move" else "copy"

        if (existing != null && !existing.isDirectory) {
            result.conflictsSkipped++
            log("CONFLICT skipped selected folder '$sourceName' because destination has a file with that name")
            onProcessed("Skipped conflict: $sourceName/", totalFiles)
            return
        }

        if (dryRun) {
            if (existing == null) {
                result.foldersTransferred++
                log("would $verb selected folder: $sourceName/ ($totalFiles file(s))")
            } else {
                result.foldersMerged++
                log("would merge selected folder for $verb: $sourceName/ ($totalFiles file(s))")
            }
            // Walk it in dry-run mode so individual conflicts are still visible in Activity.
            if (existing != null) {
                mergeDirectory(
                    resolver, source, existing, true, mode, result, log, scan, sourceName,
                    onProcessed, shouldStop
                )
            } else {
                result.filesTransferred += totalFiles
                onProcessed(sourceName, totalFiles)
            }
            return
        }

        val targetDir = if (existing != null) {
            result.foldersMerged++
            log("merging selected folder: $sourceName/")
            existing
        } else {
            result.foldersTransferred++
            destination.createDirectory(sourceName)
                ?: run {
                    result.failures++
                    log("FAILED creating selected folder: $sourceName/")
                    onProcessed("Failed: $sourceName/", totalFiles)
                    return
                }
        }

        mergeDirectory(
            resolver, source, targetDir, false, mode, result, log, scan, sourceName,
            onProcessed, shouldStop
        )

        if (mode == TransferMode.MOVE && !result.stopped) {
            if (source.listFiles().isEmpty()) {
                if (source.delete()) {
                    result.sourceFolderRemoved = true
                    log("moved selected folder itself: $sourceName/")
                } else {
                    // The contents moved successfully but Android did not permit deleting the tree root.
                    result.failures++
                    log("CONTENTS MOVED but could not delete source folder itself: $sourceName/")
                }
            } else {
                log("source folder retained because skipped/conflicting items remain: $sourceName/")
            }
        }
    }

    private fun scanDirectory(
        dir: DocumentFile,
        scan: Scan,
        shouldStop: () -> Boolean,
        onScan: (String, Int) -> Unit
    ): Int {
        var count = 0
        for (child in dir.listFiles()) {
            if (shouldStop()) break
            val name = child.name ?: "(unnamed)"
            if (child.isDirectory) {
                count += scanDirectory(child, scan, shouldStop, onScan)
            } else {
                count++
                scan.totalFiles++
                if (scan.totalFiles == 1 || scan.totalFiles % 25 == 0) onScan(name, scan.totalFiles)
            }
        }
        scan.fileCounts[dir.uri.toString()] = count
        return count
    }

    private fun mergeDirectory(
        resolver: ContentResolver,
        srcDir: DocumentFile,
        dstDir: DocumentFile,
        dryRun: Boolean,
        mode: TransferMode,
        result: Result,
        log: (String) -> Unit,
        scan: Scan,
        path: String,
        onProcessed: (String, Int) -> Unit,
        shouldStop: () -> Boolean
    ) {
        val destinationChildren = dstDir.listFiles()
            .filter { it.name != null }
            .associateBy { it.name!! }
            .toMutableMap()

        for (srcChild in srcDir.listFiles()) {
            if (shouldStop()) {
                result.stopped = true
                return
            }
            val childName = srcChild.name
            if (childName == null) {
                result.failures++
                log("  SKIP unnamed item")
                continue
            }
            val childPath = "$path/$childName"
            onProcessed(childPath, 0)
            val existing = destinationChildren[childName]
            val verb = if (mode == TransferMode.MOVE) "move" else "copy"

            if (srcChild.isDirectory) {
                val subtreeFiles = scan.fileCounts[srcChild.uri.toString()] ?: 0
                if (existing != null && !existing.isDirectory) {
                    result.conflictsSkipped++
                    log("  CONFLICT skipped directory '$childName' because destination has a file with that name")
                    onProcessed("Skipped conflict: $childPath", subtreeFiles)
                    continue
                }

                if (existing == null) {
                    result.foldersTransferred++
                    if (dryRun) {
                        result.filesTransferred += subtreeFiles
                        log("  would $verb directory: $childName/ ($subtreeFiles file(s))")
                        onProcessed(childPath, subtreeFiles)
                        continue
                    }

                    if (mode == TransferMode.MOVE && tryProviderMove(resolver, srcChild, srcDir, dstDir)) {
                        result.filesTransferred += subtreeFiles
                        log("  moved directory directly: $childName/ ($subtreeFiles file(s))")
                        onProcessed(childPath, subtreeFiles)
                        continue
                    }

                    val targetDir = dstDir.createDirectory(childName)
                    if (targetDir == null) {
                        result.failures++
                        log("  FAILED creating directory: $childName")
                        onProcessed("Failed: $childPath", subtreeFiles)
                        continue
                    }
                    destinationChildren[childName] = targetDir
                    mergeDirectory(resolver, srcChild, targetDir, false, mode, result, log, scan, childPath, onProcessed, shouldStop)
                    if (mode == TransferMode.MOVE && srcChild.listFiles().isEmpty()) srcChild.delete()
                    continue
                }

                result.foldersMerged++
                if (dryRun) log("  would merge directory for $verb: $childName/")
                mergeDirectory(resolver, srcChild, existing, dryRun, mode, result, log, scan, childPath, onProcessed, shouldStop)
                if (mode == TransferMode.MOVE && !dryRun && srcChild.listFiles().isEmpty()) srcChild.delete()
                continue
            }

            if (existing != null) {
                result.conflictsSkipped++
                log("  CONFLICT skipped existing file: $childName")
                onProcessed("Skipped existing: $childPath", 1)
                continue
            }

            if (dryRun) {
                result.filesTransferred++
                log("  would $verb file: $childName")
                onProcessed(childPath, 1)
                continue
            }

            if (mode == TransferMode.MOVE && tryProviderMove(resolver, srcChild, srcDir, dstDir)) {
                result.filesTransferred++
                log("  moved file: $childName")
                onProcessed(childPath, 1)
                continue
            }

            val mime = srcChild.type ?: "application/octet-stream"
            val target = dstDir.createFile(mime, childName)
            if (target == null) {
                result.failures++
                log("  FAILED creating file: $childName")
                onProcessed("Failed: $childPath", 1)
                continue
            }

            try {
                resolver.openInputStream(srcChild.uri).use { input ->
                    resolver.openOutputStream(target.uri, "w").use { output ->
                        if (input == null || output == null) throw IllegalStateException("Unable to open file stream")
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }

                if (mode == TransferMode.MOVE) {
                    if (srcChild.delete()) {
                        result.filesTransferred++
                        log("  moved file: $childName")
                    } else {
                        result.failures++
                        log("  COPIED but could not delete source: $childName")
                    }
                } else {
                    result.filesTransferred++
                    log("  copied file: $childName")
                }
            } catch (e: Exception) {
                target.delete()
                result.failures++
                log("  FAILED $childName: ${e.message ?: e.javaClass.simpleName}")
            }
            onProcessed(childPath, 1)
        }
    }

    private fun tryProviderMove(
        resolver: ContentResolver,
        document: DocumentFile,
        sourceParent: DocumentFile,
        destinationParent: DocumentFile
    ): Boolean = try {
        DocumentsContract.moveDocument(resolver, document.uri, sourceParent.uri, destinationParent.uri) != null
    } catch (_: Exception) {
        false
    }
}
