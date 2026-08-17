package com.rsunk.app

import android.content.Context
import android.net.Uri

object Prefs {
    private const val PREFS = "rsunk_prefs"
    private const val SOURCE = "source_uri"
    private const val DEST = "dest_uri"
    private const val SCHEDULED = "scheduled"
    private const val DARK_MODE = "dark_mode"
    private const val TRANSFER_MODE = "transfer_mode"
    private const val TRANSFER_SCOPE = "transfer_scope"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun source(context: Context): Uri? = prefs(context).getString(SOURCE, null)?.let(Uri::parse)
    fun destination(context: Context): Uri? = prefs(context).getString(DEST, null)?.let(Uri::parse)
    fun setSource(context: Context, uri: Uri) = prefs(context).edit().putString(SOURCE, uri.toString()).apply()
    fun clearSource(context: Context) = prefs(context).edit().remove(SOURCE).apply()
    fun setDestination(context: Context, uri: Uri) = prefs(context).edit().putString(DEST, uri.toString()).apply()
    fun clearDestination(context: Context) = prefs(context).edit().remove(DEST).apply()

    // Retained only for cleaning up the preference from older automatic versions.
    fun isScheduled(context: Context): Boolean = prefs(context).getBoolean(SCHEDULED, false)
    fun setScheduled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(SCHEDULED, value).apply()

    fun isDarkMode(context: Context): Boolean = prefs(context).getBoolean(DARK_MODE, false)
    fun setDarkMode(context: Context, value: Boolean) = prefs(context).edit().putBoolean(DARK_MODE, value).apply()

    fun transferMode(context: Context): Sorter.TransferMode = try {
        Sorter.TransferMode.valueOf(prefs(context).getString(TRANSFER_MODE, Sorter.TransferMode.MOVE.name)!!)
    } catch (_: Exception) {
        Sorter.TransferMode.MOVE
    }

    fun setTransferMode(context: Context, mode: Sorter.TransferMode) =
        prefs(context).edit().putString(TRANSFER_MODE, mode.name).apply()

    fun transferScope(context: Context): Sorter.TransferScope = try {
        Sorter.TransferScope.valueOf(
            prefs(context).getString(TRANSFER_SCOPE, Sorter.TransferScope.CONTENTS_ONLY.name)!!
        )
    } catch (_: Exception) {
        Sorter.TransferScope.CONTENTS_ONLY
    }

    fun setTransferScope(context: Context, scope: Sorter.TransferScope) =
        prefs(context).edit().putString(TRANSFER_SCOPE, scope.name).apply()
}
