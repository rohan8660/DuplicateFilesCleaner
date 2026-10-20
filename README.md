# Duplicate Files Cleaner

A modern Android application built with **Jetpack Compose** to help users reclaim storage space by quickly finding and deleting identical files.

## 🚀 Features

* **True Deep Scanning:** Uses a highly optimized SHA-256 chunk-based hashing algorithm. It only flags files that are 100% byte-for-byte identical, completely avoiding false positives from files that just share the same name or size.
* **Two Scan Modes:**
  * **Scoped Folder Scan:** Select a specific folder (like Downloads or Pictures) using Android's native Storage Access Framework (SAF).
  * **Full Device Scan:** Fast, native `java.io.File` system traversal over the entire device (utilizing the Android 11+ `MANAGE_EXTERNAL_STORAGE` permission).
* **Smart Auto-Selection:** Automatically selects duplicates for deletion while guaranteeing the "Original" (oldest modified) file remains untouched.
* **Failsafe UX:** 
  * Prevents users from manually selecting 100% of the files in a duplicate group (saving you from accidental total data loss).
  * Explicit confirmation dialogs before invoking permanent storage deletion.
* **Modern Material 3 Design:** 
  * Beautiful UI with responsive animations.
  * Native Dark Mode / Light Mode toggle.
  * Image file thumbnail previews powered by [Coil](https://coil-kt.github.io/coil/).

## 🛠️ Technologies Used

* **Language:** Kotlin
* **UI Framework:** Jetpack Compose (Material 3)
* **Concurrency:** Kotlin Coroutines (`Dispatchers.IO` for heavy I/O tasks)
* **Image Loading:** Coil Compose
* **Storage APIs:** `DocumentFile` (SAF) & Native Java `File` IO

## 🔧 Installation & Setup

1. Clone this repository:
   ```bash
   git clone https://github.com/rohan8660/DuplicateFilesCleaner.git
   ```
2. Open the project in **Android Studio** (Ladybug or newer recommended).
3. Let Gradle sync the dependencies.
4. Run the app on an Android emulator or a physical device via USB/Wireless Debugging.

## 📱 Permissions Context

This app utilizes the `MANAGE_EXTERNAL_STORAGE` (All Files Access) permission. This is necessary because Android 11+ restricts scoped storage (SAF) from accessing root directories or scanning the entire device efficiently. If you plan to publish a fork of this app on the Google Play Store, you will need to fill out a permissions declaration form proving the app acts as a File Manager / Device Cleaner. 

## 👨‍💻 Developer

**Rohan**  
[GitHub Profile](https://github.com/rohan8660)

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.