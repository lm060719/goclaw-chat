package xyz.limo060719.goclaw.ui.chat

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Immutable

/** Text / images / files shared into the app from another app's share sheet. */
@Immutable
data class SharedContent(
    val text: String,
    val images: List<Uri>,
    val files: List<Uri>,
) {
    companion object {
        /**
         * Parses an ACTION_SEND / ACTION_SEND_MULTIPLE intent; null for anything else or empty.
         * [typeOf] resolves a uri's MIME type (the ContentResolver), to split images from files.
         */
        fun from(intent: Intent, typeOf: (Uri) -> String?): SharedContent? {
            val action = intent.action
            if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
            val text = listOfNotNull(
                intent.getStringExtra(Intent.EXTRA_SUBJECT),
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            ).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")

            val streams: List<Uri> = if (action == Intent.ACTION_SEND) {
                listOfNotNull(intent.streamExtra())
            } else {
                intent.streamListExtra()
            }
            // The intent's type is only a summary ("image/*", "*/*"); ask per item, falling back to it.
            val images = streams.filter { uri ->
                (runCatching { typeOf(uri) }.getOrNull() ?: intent.type)?.startsWith("image/") == true
            }
            val files = streams - images.toSet()
            if (text.isBlank() && streams.isEmpty()) return null
            return SharedContent(text, images, files)
        }

        @Suppress("DEPRECATION")
        private fun Intent.streamExtra(): Uri? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else getParcelableExtra(Intent.EXTRA_STREAM)

        @Suppress("DEPRECATION")
        private fun Intent.streamListExtra(): List<Uri> =
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
    }
}
