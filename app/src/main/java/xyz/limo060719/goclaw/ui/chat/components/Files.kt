package xyz.limo060719.goclaw.ui.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.limo060719.goclaw.R
import xyz.limo060719.goclaw.domain.model.FileRef

/** MIME 类型 → 图标。气泡内文件与工具卡片输出共用。 */
internal fun fileIcon(mimeType: String): ImageVector = when {
    mimeType.startsWith("image/") -> Icons.Filled.Image
    mimeType.startsWith("video/") -> Icons.Filled.VideoFile
    mimeType.startsWith("audio/") -> Icons.Filled.AudioFile
    mimeType.contains("pdf") -> Icons.Filled.PictureAsPdf
    mimeType.contains("zip") || mimeType.contains("archive") -> Icons.Filled.FolderZip
    mimeType.startsWith("text/") || mimeType.contains("json") || mimeType.contains("javascript") -> Icons.Filled.Code
    else -> Icons.Filled.InsertDriveFile
}

/** 单个可下载文件行：图标 + 文件名（+ 可选路径副标题）+ 下载按钮。 */
@Composable
internal fun FileRow(
    file: FileRef,
    showPath: Boolean = false,
    onDownload: ((FileRef) -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .let { if (onDownload != null) it.clickable { onDownload(file) } else it }
            .padding(vertical = 4.dp),
    ) {
        Icon(
            fileIcon(file.mimeType),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.filename.ifBlank { file.path.substringAfterLast('/') },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showPath) {
                Text(
                    file.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onDownload != null) {
            IconButton(onClick = { onDownload(file) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = stringResource(R.string.file_download),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
