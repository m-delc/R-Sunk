# R-Sunk

R-Sunk is a manual Android/GrapheneOS folder transfer utility built around Android's Storage Access Framework (SAF). It is designed for quickly moving or copying folders and files between user-selected locations while preserving Android's scoped-storage and profile isolation model.

## Current version

**R-Sunk 2.0.1**  
**Version code:** 21

## v2.0.1 changes

- Added a temporary **TEST** button to the main screen for validating the permanent-project update/release workflow.
- Added the reusable `release.sh` one-command release workflow.
- Release workflow pins the Gradle wrapper to **Gradle 8.13** before building.

## Core transfer features

- **Manual transfers only** — R-Sunk does not perform automatic or scheduled moves/copies.
- Choose between **Move** and **Copy**.
- Choose the transfer scope:
  - **Folder itself (including contents)** — transfers the selected source folder into the destination.
  - **Contents only** — transfers only the items inside the selected source folder directly into the destination.
- Existing destination folders are merged by name.
- Existing same-named files are skipped rather than overwritten.
- Move mode uses Android provider-level move operations when supported for better performance, with a safe fallback when necessary.
- Copy mode leaves the source intact.

## Wi-Fi transfer (v2.0.0)

R-Sunk 2.0.0 adds a separate **Wi-Fi Transfer** screen without replacing the existing local Move/Copy workflow.

- Transfer a selected folder between two Android/GrapheneOS devices running R-Sunk on the same local network.
- Receiver advertising/discovery uses Android Network Service Discovery (NSD), so the sender can choose a nearby R-Sunk receiver by device name.
- A six-digit pairing code shown by the receiver is entered on the sender.
- Each session uses an ephemeral ECDH key exchange; the shared secret plus pairing code derives a 256-bit AES key.
- File/control frames are protected with **AES-256-GCM**, providing confidentiality and tamper detection in transit.
- Every source file is hashed with **SHA-256**. The receiver independently hashes the completed destination file and reports verification back to the sender.
- Existing destination files with matching size and SHA-256 are skipped, providing practical restart/resume behavior.
- Hash mismatches are retried automatically up to three attempts and a failed partial destination file is removed.
- Transfers are processed internally in groups of **34 files**. These are transfer batches only; R-Sunk never creates `Batch_001`-style folders.
- Relative paths are recreated on the destination. By default, the selected source folder itself is created inside the chosen destination; this can be disabled to transfer only its contents.
- File MIME types are carried across when destination documents are created.

The Wi-Fi transfer screen is intentionally separate from R-Sunk's original SAF Move/Copy controls, so all v1.8.0 behavior remains available.

## Searchable folder browser

R-Sunk includes its own folder browser for locations covered by persisted SAF folder-tree permissions.

- **Choose Source** opens R-Sunk's searchable granted-folder browser when folder access has already been granted.
- **Choose Destination** does the same for destination selection.
- Search recursively by folder name or by any portion of the displayed path.
- Source selection mode shows **Use as Source**.
- Destination selection mode shows **Use as Destination**.
- A generic **Browse / Search Granted Folders** view can set either Source or Destination.
- The standard Android/GrapheneOS folder picker remains available as an explicit fallback for locations outside the granted trees.

This is intended to make large folder trees practical to use without manually scrolling through hundreds of folders in the system Files picker.

## Manage Folder Access

**Manage Folder Access** lets you grant R-Sunk persistent access to broader directory trees through SAF, for example:

```text
Pictures/Instagram
```

R-Sunk can then browse and search the folders beneath that granted tree without requiring a separate permission grant for every subfolder.

## Transfer controls and safety

- **Dry Run** previews what R-Sunk would do without transferring anything.
- **Progress bar** shows processed/total files and percentage.
- **Current item** remains visible while a transfer is running.
- **ETA** is shown once enough progress exists to estimate remaining time.
- **Stop** requests a clean interruption: R-Sunk finishes the current operation and stops before beginning the next item.
- Completed work is kept; unprocessed source items remain untouched.
- The on-screen activity view follows the newest activity automatically while limiting the visible history for performance.

## Activity log

- R-Sunk maintains a full activity log for the current run.
- **Export Full Activity** opens Android's Save dialog and exports the complete activity history to a text file, including lines that have already scrolled out of the on-screen activity window.

## Interface

- Saved **Dark mode** preference.
- Proper Android system-bar insets so content stays below the status bar and above navigation areas.
- **About** screen displays the installed app version and version code.
- Custom R-Sunk launcher/adaptive icon.

## Share-menu support

R-Sunk can receive compatible Android share intents and attempt to use a shared folder as a Source or Destination.

Folder sharing behavior is controlled by the sending file manager. Some file managers, including configurations where a folder share is expanded into the files inside that folder, may not provide R-Sunk with the folder itself. For reliable folder selection, use R-Sunk's **Manage Folder Access** and searchable in-app folder browser.

## GrapheneOS / secondary profiles

R-Sunk is designed to work through SAF inside the Android user profile in which it is installed. This avoids relying on ADB, Termux, direct filesystem paths, or cross-profile storage access.

## Build baseline

- **Android Gradle Plugin:** 8.13.2
- **Gradle:** 8.13
- **JDK:** 17
- **Package ID:** `com.rsunk.app`

Keeping the package ID unchanged allows newer builds to install over earlier R-Sunk versions when signed with the same signing key.

## v2.0.0 changes

- Added encrypted local Wi-Fi transfer as a separate screen.
- Added six-digit device pairing, ephemeral ECDH session keys, and AES-256-GCM encrypted transfer frames.
- Added SHA-256 verification of source and completed destination files.
- Added automatic skip/resume for files already matching on the destination.
- Added up to three automatic retries after a SHA-256 mismatch.
- Added internal 34-file transfer batching while preserving the source directory structure.
- Preserved the existing v1.8.0 local Move/Copy interface and behavior.

## v1.8.0 changes

- **Choose Source** and **Choose Destination** now open R-Sunk's searchable granted-folder browser by default when persisted folder-tree access exists.
- Source browser results show only **Use as Source**.
- Destination browser results show only **Use as Destination**.
- Android/GrapheneOS folder picker remains available as an explicit fallback inside the browser.

## Earlier major additions

- **v1.7.x:** searchable recursive browser for granted folder trees.
- **v1.6.x:** Manage Folder Access, share-intent handling, and custom launcher icon.
- **v1.5.x:** separate Folder itself / Contents only transfer scope.
- **v1.4.x:** Move/Copy modes, system-bar inset handling, and support for files directly inside the selected source folder.
- **v1.3.x:** dark mode, About/version display, and full activity-log export.
- **v1.2.x:** graceful Stop function.
- **v1.1.x:** faster transfer logic, current-item display, progress bar, and ETA.
