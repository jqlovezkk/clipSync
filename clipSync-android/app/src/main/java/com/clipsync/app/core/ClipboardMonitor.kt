package com.clipsync.app.core

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Content type detected by the clipboard monitor.
 */
enum class ClipboardContentType {
    NONE,
    TEXT,
    IMAGE
}

/**
 * Represents clipboard content with type information.
 */
data class ClipboardContent(
    val contentType: ClipboardContentType,
    val textContent: String? = null,
    val imageBase64: String? = null,
    val imageFormat: String = "image/png",
    val sizeBytes: Int = 0,
    val checksum: String = ""
)

/**
 * Monitors system clipboard for changes.
 * Detects text and image content and emits changes via StateFlow.
 */
class ClipboardMonitor(context: Context) {

    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val appContext = context.applicationContext

    private val _currentText = MutableStateFlow<String?>(null)
    val currentText: StateFlow<String?> = _currentText.asStateFlow()

    private val _currentImage = MutableStateFlow<ByteArray?>(null)
    val currentImage: StateFlow<ByteArray?> = _currentImage.asStateFlow()

    private val _currentContent = MutableStateFlow<ClipboardContent?>(null)
    val currentContent: StateFlow<ClipboardContent?> = _currentContent.asStateFlow()

    private var lastContent: String? = null
    private var lastImageChecksum: String? = null

    /** Maximum clipboard content size: 512KB */
    var maxContentSizeBytes: Int = DEFAULT_MAX_CONTENT_SIZE

    private val primaryClipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        checkClipboard()
    }

    /**
     * Start monitoring the clipboard.
     */
    fun start() {
        clipboardManager.addPrimaryClipChangedListener(primaryClipChangedListener)
        // Check initial clipboard content
        checkClipboard()
        Log.d(TAG, "Clipboard monitoring started")
    }

    /**
     * Stop monitoring the clipboard.
     */
    fun stop() {
        clipboardManager.removePrimaryClipChangedListener(primaryClipChangedListener)
        Log.d(TAG, "Clipboard monitoring stopped")
    }

    /**
     * Get current clipboard text without triggering a change event.
     */
    fun getCurrentText(): String? {
        return try {
            val clipData = clipboardManager.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                clipData.getItemAt(0).coerceToText(null)?.toString()
            } else {
                null
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot access clipboard", e)
            null
        }
    }

    /**
     * Set text to clipboard.
     * This will NOT trigger the change listener (we track lastContent to avoid echo).
     */
    fun setTextToClipboard(text: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                lastContent = text // Prevent echo loop
                val clip = ClipData.newPlainText("ClipSync", text)
                clipboardManager.setPrimaryClip(clip)
                // Do not emit here: observers treat StateFlow changes as local user copies.
                Log.d(TAG, "Set clipboard text (${text.length} chars)")
            } catch (e: SecurityException) {
                Log.e(TAG, "Cannot set clipboard", e)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error setting clipboard text", e)
            }
        }
    }

    private fun checkClipboard() {
        try {
            val clipData = clipboardManager.primaryClip
            if (clipData == null || clipData.itemCount == 0) {
                return
            }

            val description = clipboardManager.primaryClipDescription
            if (description == null) {
                return
            }

            // Check if clipboard contains image
            if (description.hasMimeType("image/*")) {
                extractImageContent(clipData)
            } else if (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                       description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) {
                extractTextContent(clipData)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot access clipboard", e)
        }
    }

    private fun extractTextContent(clipData: ClipData) {
        val text = clipData.getItemAt(0).coerceToText(null)?.toString()
        if (!text.isNullOrEmpty() && text != lastContent) {
            val sizeBytes = text.toByteArray(Charsets.UTF_8).size
            if (sizeBytes > maxContentSizeBytes) {
                Log.w(TAG, "Clipboard text too large (${sizeBytes} bytes), skipping")
                return
            }
            lastContent = text
            _currentText.value = text
            _currentContent.value = ClipboardContent(
                contentType = ClipboardContentType.TEXT,
                textContent = text,
                sizeBytes = sizeBytes,
                checksum = EncryptionHelper.computeChecksum(text.toByteArray(Charsets.UTF_8))
            )
            Log.d(TAG, "Clipboard text changed: ${text.take(50)}...")
        }
    }

    private fun extractImageContent(clipData: ClipData) {
        try {
            val item = clipData.getItemAt(0)
            val uri = item.uri
            if (uri == null) {
                // Try to get as HTML/text if no URI
                val text = item.coerceToText(null)?.toString()
                if (!text.isNullOrEmpty() && text != lastContent) {
                    lastContent = text
                    _currentText.value = text
                }
                return
            }

            // Read image from URI
            val inputStream = appContext.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.w(TAG, "Cannot open image stream from URI: $uri")
                return
            }

            val imageBytes = inputStream.use { it.readBytes() }

            if (imageBytes.isEmpty() || imageBytes.size > maxContentSizeBytes) {
                Log.w(TAG, "Image size invalid: ${imageBytes.size} bytes")
                return
            }

            val checksum = EncryptionHelper.computeChecksum(imageBytes)

            // Skip if same image (deduplication)
            if (checksum == lastImageChecksum) {
                Log.d(TAG, "Image content unchanged (checksum match)")
                return
            }

            lastImageChecksum = checksum
            _currentImage.value = imageBytes
            _currentContent.value = ClipboardContent(
                contentType = ClipboardContentType.IMAGE,
                imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP),
                imageFormat = detectImageFormat(uri),
                sizeBytes = imageBytes.size,
                checksum = checksum
            )
            Log.d(TAG, "Clipboard image changed: ${imageBytes.size} bytes, checksum=$checksum")
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting image from clipboard", e)
        }
    }

    /**
     * Detect image format from URI.
     */
    private fun detectImageFormat(uri: Uri): String {
        return try {
            val mimeType = appContext.contentResolver.getType(uri)
            when {
                mimeType?.contains("png") == true -> "image/png"
                mimeType?.contains("jpeg") == true || mimeType?.contains("jpg") == true -> "image/jpeg"
                mimeType?.contains("webp") == true -> "image/webp"
                mimeType?.contains("gif") == true -> "image/gif"
                else -> "image/png" // Default fallback
            }
        } catch (e: Exception) {
            "image/png"
        }
    }

    /**
     * Set image to clipboard (from Base64).
     * Saves the bitmap to a temporary file and sets it via URI.
     */
    fun setImageToClipboard(base64Content: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val imageBytes = Base64.decode(base64Content, Base64.NO_WRAP)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                if (bitmap == null) {
                    Log.e(TAG, "Failed to decode bitmap from base64")
                    return@post
                }

                lastImageChecksum = EncryptionHelper.computeChecksum(imageBytes)

                // Save bitmap to temporary file
                val cacheDir = appContext.cacheDir
                val imageFile = File(cacheDir, "clipsync_temp_${System.currentTimeMillis()}.png")
                FileOutputStream(imageFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                // Get content URI for the file
                val imageUri = Uri.fromFile(imageFile)

                // Create clip data with image URI
                val clip = ClipData.newUri(appContext.contentResolver, "ClipSync", imageUri)
                clipboardManager.setPrimaryClip(clip)

                // Schedule cleanup of temporary file after a delay
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (imageFile.exists()) {
                        imageFile.delete()
                        Log.d(TAG, "Cleaned up temporary image file")
                    }
                }, 60000) // Delete after 1 minute

                Log.d(TAG, "Set clipboard image (${imageBytes.size} bytes, ${bitmap.width}x${bitmap.height})")
            } catch (e: Exception) {
                Log.e(TAG, "Cannot set image to clipboard", e)
            }
        }
    }

    /**
     * Reset the last content trackers. Useful after receiving remote content.
     */
    fun resetLastContent() {
        lastContent = null
        lastImageChecksum = null
    }

    companion object {
        private const val TAG = "ClipboardMonitor"
        private const val DEFAULT_MAX_CONTENT_SIZE = 512 * 1024 // 512KB
    }
}
