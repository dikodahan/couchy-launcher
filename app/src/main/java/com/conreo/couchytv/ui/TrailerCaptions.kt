package com.conreo.couchytv.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@OptIn(UnstableApi::class)
@Composable
internal fun TrailerCaptions(cues: List<Cue>) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.padding(bottom = maxHeight * 0.08f).fillMaxWidth(0.85f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (cue in cues) {
                val bitmap = cue.bitmap
                if (bitmap != null) {
                    val width = cue.size.takeIf { it in 0.01f..1f } ?: 0.7f
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(width),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    cue.text?.toString()?.takeIf(String::isNotBlank)?.let { text ->
                        Text(
                            text = text,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(3.dp))
                                .padding(horizontal = 12.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}
