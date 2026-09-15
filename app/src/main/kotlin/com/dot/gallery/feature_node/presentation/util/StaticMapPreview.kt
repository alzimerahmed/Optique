package com.dot.gallery.feature_node.presentation.util

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.dot.gallery.feature_node.presentation.location.MapAppearance
import kotlin.math.roundToInt

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
internal fun StaticMapPreview(
    latitude: Double,
    longitude: Double,
    appearance: MapAppearance,
    effectiveAppIsDark: Boolean,
    zoom: Int,
    apiKey: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val semanticsModifier = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    BoxWithConstraints(modifier.then(semanticsModifier).clipToBounds()) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val placements = remember(latitude, longitude, zoom, widthPx, heightPx) {
            StaticMapURL.centeredTiles(
                latitude = latitude,
                longitude = longitude,
                zoom = zoom,
                viewportWidthPx = widthPx,
                viewportHeightPx = heightPx,
            )
        }
        val density = LocalDensity.current
        placements.forEach { placement ->
            key(placement.tileX, placement.tileY) {
                val tileSize = with(density) { placement.sizePx.toDp() }
                GlideImage(
                    model = StaticMapURL.tileUrl(
                        tileX = placement.tileX,
                        tileY = placement.tileY,
                        appearance = appearance,
                        effectiveAppIsDark = effectiveAppIsDark,
                        zoom = zoom,
                        apiKey = apiKey,
                    ),
                    contentDescription = null,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = placement.leftPx.roundToInt(),
                                y = placement.topPx.roundToInt(),
                            )
                        }
                        .size(tileSize),
                    contentScale = ContentScale.FillBounds,
                    requestBuilderTransform = {
                        it.diskCacheStrategy(DiskCacheStrategy.ALL)
                    },
                )
            }
        }
    }
}
