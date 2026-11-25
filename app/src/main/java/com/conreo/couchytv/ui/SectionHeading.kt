package com.conreo.couchytv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.conreo.couchytv.data.CategoryCfg
import com.conreo.couchytv.data.IconLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SectionIcon(name: String?, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    val context = LocalContext.current.applicationContext
    val icon by produceState<Pair<String, ImageBitmap>?>(null, name, context) {
        value = name?.let { key ->
            withContext(Dispatchers.IO) {
                runCatching { IconLibrary.load(context).find(key)?.render(64)?.asImageBitmap() }
                    .getOrNull()?.let { key to it }
            }
        }
    }
    icon?.takeIf { it.first == name }?.second?.let {
        Image(it, contentDescription = null, modifier = modifier, colorFilter = tint.takeUnless { it == Color.Unspecified }?.let { ColorFilter.tint(it) })
    }
}

@Composable
internal fun SectionHeading(section: CategoryCfg, showName: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionIcon(section.visibleIcon, Modifier.size(21.dp))
        if (showName) {
            Text(
                section.name.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.6f),
                letterSpacing = 1.5.sp,
            )
        }
    }
}
