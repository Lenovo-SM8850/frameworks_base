// SPDX-License-Identifier: Apache-2.0
package io.github.miner7222.systemui.island

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * The icon of an app for a popup: the complete launcher icon, not one of its layers, in the shape
 * of the system icon mask. It is resolved against the configuration of the window it is shown in,
 * so the night variants of the app's own resources are used, and loaded again when that changes.
 *
 * [fallback] is drawn when the package has no icon of its own to load. Null when there is neither.
 */
@Composable
internal fun rememberAppIconBitmap(
    packageName: String?,
    fallback: Drawable?,
    size: Dp,
): ImageBitmap? {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    return remember(packageName, fallback, configuration.uiMode, configuration.densityDpi, sizePx) {
        renderAppIcon(context, Configuration(configuration), packageName, fallback, sizePx)
            ?.asImageBitmap()
    }
}

private fun renderAppIcon(
    context: Context,
    configuration: Configuration,
    packageName: String?,
    fallback: Drawable?,
    sizePx: Int,
): Bitmap? {
    val source = packageName?.let { loadPackageIcon(context, configuration, it) } ?: fallback
    source ?: return null
    // A copy, so the bounds of a drawable that others hold are left alone.
    val drawable = source.constantState?.newDrawable()?.mutate() ?: source
    val savedBounds = drawable.copyBounds()
    return try {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        // An adaptive icon draws both of its layers under the system icon mask.
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(bitmap))
        bitmap
    } catch (e: RuntimeException) {
        null
    } finally {
        drawable.setBounds(savedBounds)
    }
}

/**
 * The launcher icon of [packageName] from its resources under [configuration], or null when the
 * package or its icon is not there.
 */
private fun loadPackageIcon(
    context: Context,
    configuration: Configuration,
    packageName: String,
): Drawable? =
    try {
        val packageContext =
            context.createPackageContext(packageName, 0).createConfigurationContext(configuration)
        val iconRes = packageContext.applicationInfo.icon
        if (iconRes != 0) {
            packageContext.resources.getDrawableForDensity(
                iconRes,
                configuration.densityDpi,
                packageContext.theme,
            )
        } else {
            packageContext.packageManager.getApplicationIcon(packageName)
        }
    } catch (e: Exception) {
        null
    }
