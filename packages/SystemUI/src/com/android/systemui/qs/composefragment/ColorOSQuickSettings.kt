/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.android.compose.animation.Expandable
import com.android.compose.animation.scene.ContentScope
import com.android.settingslib.volume.domain.interactor.AudioVolumeInteractor
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.animation.Expandable
import com.android.systemui.brightness.domain.model.GammaBrightness
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.brightness.ui.viewmodel.Drag
import com.android.systemui.common.ui.compose.PagerDots
import com.android.systemui.grid.ui.compose.GridSpan
import com.android.systemui.grid.ui.compose.PlacedGridItem
import com.android.systemui.grid.ui.compose.TwoDimensionalSpannedGrid
import com.android.systemui.grid.ui.compose.calculate2DPlacements
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.footer.ui.compose.rememberSystemSettingEnabled
import com.android.systemui.qs.panels.ui.viewmodel.QSColumnsViewModel
import com.android.systemui.shade.QsHeaderImageTint
import com.android.systemui.qs.panels.ui.compose.GridLayout
import com.android.systemui.qs.panels.ui.compose.TileListener
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSActiveFill
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSGap
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSGridFooterHeight
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSGridHeadroom
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSGridRows
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSMinGridRows
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSRingerRed
import com.android.systemui.qs.panels.ui.compose.infinitegrid.colorOSRingerLightBubble
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSSliderFill
import com.android.systemui.qs.panels.ui.compose.infinitegrid.ColorOSGlassBlur
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalColorOSGlassBlur
import com.android.systemui.qs.panels.ui.compose.infinitegrid.colorOSGlassBlur
import com.android.systemui.qs.panels.ui.compose.infinitegrid.colorOSGlassTileMaterial
import com.android.systemui.qs.panels.ui.compose.infinitegrid.colorOSTileRim
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberColorOSGlassBlur
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.qs.panels.ui.compose.infinitegrid.InfiniteGridLayout
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalColorOSGridRows
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalColorOSGap
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalColorOSTablet
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalColorOSTileSize
import com.android.systemui.qs.panels.ui.compose.infinitegrid.colorOSIconRowGap
import com.android.systemui.qs.panels.ui.compose.infinitegrid.colorOSPress
import com.android.systemui.qs.panels.ui.compose.infinitegrid.colorOSSpring
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberQSTileLabelHide
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.shared.ui.QuickSettings
import com.android.systemui.res.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Tile specs that ColorOS pins to the fixed wide slots, in order of preference. */
private val COLOROS_WIFI_SPECS = listOf("internet", "wifi")
private val COLOROS_BT_SPECS = listOf("bt", "bluetooth")

/**
 * What fills a pinned slot whose own tile is not in the panel, in order of preference. ColorOS
 * always shows two cards in the first row, so a panel without a Wi-Fi tile or without a Bluetooth
 * tile takes the first of these that it has instead of leaving a hole.
 */
private val COLOROS_CARD_FALLBACK_SPECS = listOf("cell", "hotspot", "nfc", "cast", "airplane")

/**
 * The specs of the two cards of the first row, the Wi-Fi one first and the Bluetooth one second. A
 * slot with no tile of its own gets a fallback, which no other slot uses, and stays null when there
 * is none.
 */
internal fun colorOSCardSlotsOf(present: Set<String>): List<String?> {
    val fallbacks = COLOROS_CARD_FALLBACK_SPECS.filter { it in present }.iterator()
    fun fallback(): String? = if (fallbacks.hasNext()) fallbacks.next() else null
    val wifi = COLOROS_WIFI_SPECS.firstOrNull { it in present }
    val bt = COLOROS_BT_SPECS.firstOrNull { it in present }
    return listOf(wifi ?: fallback(), bt ?: fallback())
}

/**
 * The specs of the tiles the panel pins as cards, the Wi-Fi and Bluetooth ones that are present and
 * the fallbacks of the ones that are not. The grid and the editor leave them out, and the editor
 * keeps them at the front of the list. The mobile data tile is a round tile of the grid unless it
 * stands in for a missing Wi-Fi tile, the third card is My Devices.
 */
internal fun colorOSCardSpecsOf(present: Set<String>): Set<String> =
    colorOSCardSlotsOf(present).filterNotNull().toSet()

/** The blue of the volume icon, as in the ColorOS panel. */
private val ColorOSSliderBlue = Color(0xFF0068FF)

/** An icon on the white fill of a slider is black at 70%, not solid black, as in ColorOS. */
private val ColorOSSliderIconOnFill = Color(0xB3000000)

/** Size of the icon at the foot of a ColorOS slider, the brightness sun. */
private val ColorOSSliderIconSize = 28.dp

/**
 * Size of the headphones of the volume slider, they are drawn larger than the sun, as ColorOS does,
 * and the other volume icons.
 */
private val ColorOSVolumeHeadphonesIconSize = 32.dp
private val ColorOSVolumeIconSize = 28.dp

/** Space between the foot of a ColorOS slider and its icon. */
private val ColorOSSliderIconBottomInset = 18.dp

/** Sun-icon colour at full brightness (the orange of the 🔆 emoji). */
private val ColorOSBrightnessFull = Color(0xFFFF9F0A)

/** Slider position treated as "full" (allows for rounding of the brightness value). */
private const val COLOROS_FULL_THRESHOLD = 0.995f

/** Diameter of the selected-mode bubble of the ringer switch, relative to the pill height. */
private const val ColorOSRingerBubbleRatio = 0.68f

/** A ringer glyph to the height of the switch, small enough to leave room beside the bubble. */
private const val ColorOSRingerIconRatio = 0.28f

/**
 * Extra space between the ringer row and the round tiles, ColorOS leaves a bit more there. The
 * pager adds [ColorOSGridHeadroom] on top of this, inside its bounds, so the gap is 6dp in all.
 */
private val ColorOSGridTopPadding = 2.dp

/** Smallest round tile the panel shrinks to before it gives up and scrolls instead. */
private val ColorOSMinTileSize = 56.dp

/**
 * How the ColorOS panel is sized: the diameter of the round tiles (which is also the height of the
 * pills and of the sliders), the rows of round tiles per page, and whether it all fits the screen.
 */
internal data class ColorOSFit(val tileSize: Dp, val rows: Int, val fits: Boolean)

/**
 * Sizes the ColorOS panel for [availableHeight], the height the panel may take inside the Quick
 * Settings scroller. The tiles keep the width of a column, [columnSize], when that fits. On a
 * short screen they shrink to the height that does fit, but not below [ColorOSMinTileSize], and
 * the grid first drops from [ColorOSGridRows] to [ColorOSMinGridRows] rows per page. If even that
 * does not fit (landscape, tiny screens), the panel keeps its smallest size and the Quick Settings
 * scroller takes over, which [ColorOSFit.fits] reports.
 *
 * The panel is stacked from, in tiles: the pills (1), the media card and sliders (2), the pills
 * again (1) and the rows of the grid, plus the gaps, the room for the labels and the pager footer.
 */
internal fun colorOSFit(
    columnSize: Dp,
    availableHeight: Dp,
    labelHeight: Dp,
    iconRowGap: Dp,
): ColorOSFit {
    if (availableHeight == Dp.Unspecified || availableHeight <= 0.dp) {
        return ColorOSFit(columnSize, ColorOSGridRows, fits = true)
    }
    for (rows in ColorOSGridRows downTo ColorOSMinGridRows) {
        // Gaps: between the four blocks (3) and inside the media row (1).
        val fixed =
            ColorOSGap * 4 +
                ColorOSGridTopPadding +
                ColorOSGridHeadroom +
                labelHeight * rows +
                iconRowGap * (rows - 1) +
                ColorOSGridFooterHeight
        val tileSize = (availableHeight - fixed) / (4 + rows)
        if (tileSize >= ColorOSMinTileSize) {
            return ColorOSFit(minOf(columnSize, tileSize), rows, fits = true)
        }
    }
    // Do not grow back to full-width tiles when the minimum layout just misses the viewport:
    // the cards above the grid would consume its space again, pushing all round tiles offscreen.
    return ColorOSFit(minOf(columnSize, ColorOSMinTileSize), ColorOSMinGridRows, fits = false)
}

/**
 * Least height to width ratio of a ColorOS slider. The sliders are one column wide, which is right
 * on a phone, but on a wide panel the column grows and the module does not, so they would turn
 * fat. They stop at this ratio and sit centered in their column instead.
 */
private const val ColorOSSliderAspect = 2.1f

/** Corner radius of the glass slot behind the media card, playing or idle. */
internal val ColorOSMediaShape = RoundedCornerShape(32.dp)

/** The soft light along the top of the glass of the light panel, where there is no blur region. */
private val ColorOSLightGlassHighlight =
    Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.18f),
        0.5f to Color.Transparent,
    )

/**
 * ColorOS-style expanded Quick Settings.
 *
 * ```
 * [ Wi-Fi         ][ Bluetooth     ]
 * [ Media        ][ ☀ ][ 🔊 ]          tall brightness + media volume sliders
 * [ My Devices   ][ 🔔  📳  🔕     ]   Bluetooth devices, ringer switch
 * ( ) ( ) ( ) ( )                       every other tile: round, label below
 *
 * The media card spans the first two columns of the round tile grid and each slider is one
 * column wide, so they line up with the tiles below.
 * ```
 *
 * The fixed tiles are the real tile view models, so they behave exactly like their stock
 * versions. Anything missing on a device (e.g. no Bluetooth tile) simply leaves its slot out.
 */
@Composable
internal fun ContentScope.ColorOSQuickSettingsLayout(
    tiles: List<TileViewModel>,
    gridLayout: GridLayout,
    infiniteGridLayout: InfiniteGridLayout,
    brightnessViewModel: BrightnessSliderViewModel,
    audioVolumeInteractor: AudioVolumeInteractor,
    showMedia: Boolean,
    media: @Composable (Modifier) -> Unit,
    listening: () -> Boolean,
    modifier: Modifier = Modifier,
    availableHeight: Dp = Dp.Unspecified,
    onFitChanged: (Boolean) -> Unit = {},
    glassAlpha: () -> Float = { 1f },
    expansion: () -> Float = { 1f },
    headerHeight: Dp = 0.dp,
    header: @Composable () -> Unit = {},
    onOpenDevices: () -> Unit = {},
    maximumTileSize: Dp = Dp.Infinity,
    largeTiles: Set<String> = emptySet(),
) {
    val chosen by rememberColorOSPinned()
    val pinned =
        remember(tiles, chosen) {
            val bySpec = tiles.associateBy { it.spec.spec }
            val slots = colorOSPinnedOf(bySpec.keys, chosen)
            slots.cards.map { spec -> spec?.let(bySpec::get) } to slots.pill?.let(bySpec::get)
        }
    val fixed = remember(pinned) { pinned.first.filterNotNull() }
    val pillTile = pinned.second
    val rest = remember(tiles, fixed, pillTile) { tiles.filterNot { it in fixed || it == pillTile } }

    // The fixed tiles are not part of a grid, so make them listen ourselves. The large screen panel
    // has every tile in its own grid and listens to all of them.
    if (maximumTileSize == Dp.Infinity) {
        TileListener(fixed + listOfNotNull(pillTile), listening)
    } else {
        TileListener(tiles, listening)
    }

    // Null where SurfaceFlinger cannot blur behind the shade, the flat fills stay then.
    // The modules cascade in from the top as the shade opens and lift away bottom first as it
    // closes, following the drag, and each one fades its own glass with it.
    val reveal: () -> Float = { colorOSExpansionReveal(expansion()) }
    // The glass is a blur region of the window, so it does not follow the alpha the scene
    // transition gives the content. The content fades in later than the panel is revealed, and
    // without its alpha the glass would show up before the tiles do. The alpha is not known yet
    // while the content has not been placed in the transition, the content is not drawn then, and
    // neither is the glass. A panel that is not in a transition is fully there.
    val glassBlur =
        rememberColorOSGlassBlur {
            val sceneAlpha =
                QuickSettings.Elements.QuickSettingsContent.currentAlpha()
                    ?: if (layoutState.currentTransition == null) 1f else 0f
            glassAlpha() * sceneAlpha
        }
    val glass = colorOSGlass(blurred = glassBlur != null)

    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        // ColorOS is built on four equal columns with a 17dp gap. The round tiles, the pills, the
        // media card and sliders share the columns. Short screens shrink the tiles, see colorOSFit.
        // A panel with a maximum tile size is the large screen one, which has its own layout, see
        // ColorOSTabletLayout, on 4 to 6 columns.
        val panelMaxWidth = maxWidth
        val fullColumnSize = (panelMaxWidth - ColorOSGap * 3) / 4
        // Without the labels the round tile rows are shorter and further apart.
        val labelsHidden = rememberQSTileLabelHide()
        val labelHeight = if (labelsHidden) 0.dp else CommonTileDefaults.ClassicLabelHeight
        val iconRowGap = colorOSIconRowGap(labelsHidden)
        if (maximumTileSize != Dp.Infinity) {
            // The tablet grid is one module: its glass fades with the cells, like the others.
            CompositionLocalProvider(LocalColorOSGlassBlur provides glassBlur) {
                ColorOSModuleGlass(2, reveal) {
                    ColorOSTabletLayout(
                        tiles = tiles,
                        largeTiles = largeTiles,
                        infiniteGridLayout = infiniteGridLayout,
                        brightnessViewModel = brightnessViewModel,
                        audioVolumeInteractor = audioVolumeInteractor,
                        showMedia = showMedia,
                        media = media,
                        listening = listening,
                        glass = glass,
                        glassBlur = LocalColorOSGlassBlur.current,
                        labelHeight = labelHeight,
                        labelsHidden = labelsHidden,
                        availableHeight = availableHeight,
                        fullWidth = panelMaxWidth,
                        onOpenDevices = onOpenDevices,
                        reveal = { m -> m.colorOSModuleReveal(2, reveal) },
                    )
                }
            }
            SideEffect { onFitChanged(true) }
            return@BoxWithConstraints
        }
        val gap = ColorOSGap
        val fit =
            remember(fullColumnSize, availableHeight, labelHeight, iconRowGap) {
                colorOSFit(fullColumnSize, availableHeight, labelHeight, iconRowGap)
            }
        SideEffect { onFitChanged(fit.fits) }
        val tileSize = fit.tileSize
        // Half of the panel holds the media card, the other half the sliders and the ringer.
        val columnSize = fullColumnSize
        val halfWidth = columnSize * 2 + gap
        val sliderSlot = columnSize
        val rowCard = pillTile
        CompositionLocalProvider(
            LocalColorOSTileSize provides tileSize,
            LocalColorOSGridRows provides fit.rows,
            LocalColorOSGlassBlur provides glassBlur,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = spacedBy(gap),
            ) {
                // The room the shade header keeps above the panel, with the two buttons at the top.
                // The status line in it is drawn by the shade header. The gap of the column is
                // part of the height, so the panel ends up where it was without the buttons.
                if (headerHeight > gap) {
                    Box(Modifier.fillMaxWidth().height(headerHeight - gap)) { header() }
                }

                // Row 1: Wi-Fi + Bluetooth, or what stands in for the one that is missing
                if (fixed.isNotEmpty()) {
                    ColorOSModuleGlass(1, reveal) {
                        Row(
                            horizontalArrangement = spacedBy(gap),
                            modifier = Modifier.colorOSModuleReveal(1, reveal),
                        ) {
                            fixed.forEach { tile ->
                                with(infiniteGridLayout) {
                                    ColorOSWideTile(tile, listening, Modifier.weight(1f))
                                }
                            }
                            if (fixed.size < 2) Box(Modifier.weight(1f))
                        }
                    }
                }

                // Row 2: media + tall brightness + tall volume, on the same four columns as the round
                // tiles below: the media card covers columns 1-2 and each slider one of columns 3-4.
                val moduleHeight = tileSize * 2 + gap
                val sliderWidth = minOf(sliderSlot, moduleHeight / ColorOSSliderAspect)
                val mediaContent = Modifier.fillMaxSize()
                val mediaCard: @Composable (Modifier) -> Unit = { cardModifier ->
                    Box(
                        modifier =
                            cardModifier
                                // Playing and idle share the same glass slot, like the other
                                // ColorOS cards, the media card only lays out its content on it.
                                .glassSurface(glass, ColorOSMediaShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (showMedia) {
                            media(mediaContent)
                        } else {
                            ColorOSIdleMediaCard(modifier = mediaContent)
                        }
                    }
                }
                val sliders: @Composable () -> Unit = {
                    Box(Modifier.width(sliderSlot).fillMaxHeight(), Alignment.Center) {
                        ColorOSBrightnessSlider(
                            viewModel = brightnessViewModel,
                            glass = glass,
                            modifier = Modifier.width(sliderWidth).fillMaxHeight(),
                        )
                    }
                    Box(Modifier.width(sliderSlot).fillMaxHeight(), Alignment.Center) {
                        ColorOSVolumeSlider(
                            audioVolumeInteractor = audioVolumeInteractor,
                            glass = glass,
                            modifier = Modifier.width(sliderWidth).fillMaxHeight(),
                        )
                    }
                }
                val ringer: @Composable (Modifier) -> Unit = { ringerModifier ->
                    ColorOSRingerSwitch(
                        audioVolumeInteractor = audioVolumeInteractor,
                        glass = glass,
                        height = tileSize,
                        modifier = ringerModifier,
                    )
                }
                ColorOSModuleGlass(2, reveal) {
                    Row(
                        horizontalArrangement = spacedBy(gap),
                        modifier = Modifier.colorOSModuleReveal(2, reveal).fillMaxWidth().height(moduleHeight),
                    ) {
                        mediaCard(Modifier.width(halfWidth).fillMaxHeight())
                        sliders()
                    }
                }
                // The third row: the pinned pill (or My Devices) on the left and the ringer
                // switch on the right. The mobile data tile is one of the round tiles below.
                ColorOSModuleGlass(3, reveal) {
                    Row(horizontalArrangement = spacedBy(gap), modifier = Modifier.colorOSModuleReveal(3, reveal)) {
                        if (rowCard != null) {
                            with(infiniteGridLayout) {
                                ColorOSWideTile(rowCard, listening, Modifier.weight(1f))
                            }
                        } else {
                            ColorOSMyDevices(
                                glass = glass,
                                height = tileSize,
                                onClick = onOpenDevices,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        ringer(Modifier.weight(1f))
                    }
                }

                // Everything else: round icon tiles with labels (the grid handles paging + listening)
                if (rest.isNotEmpty()) {
                    ColorOSModuleGlass(4, reveal) {
                        with(gridLayout) {
                            TileGrid(
                                tiles = rest,
                                modifier =
                                    Modifier.colorOSModuleReveal(4, reveal)
                                        .fillMaxWidth()
                                        .padding(top = ColorOSGridTopPadding),
                                listening = listening,
                                enableRevealEffect = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lays the content out [baseHeight] tall, with the width that keeps its proportions, and scales it
 * down to the height it is given when that is less, instead of squeezing its parts.
 */
internal fun Modifier.colorOSFitScale(baseHeight: Dp): Modifier =
    layout { measurable, constraints ->
        val base = baseHeight.roundToPx()
        val height = constraints.maxHeight
        if (!constraints.hasBoundedHeight || !constraints.hasBoundedWidth || height >= base ||
            height <= 0
        ) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val scale = height.toFloat() / base
        val width = constraints.maxWidth
        val placeable = measurable.measure(Constraints.fixed((width / scale).roundToInt(), base))
        layout(width, height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }

/** Colours shared by the ColorOS glass cards, matching the tile glass in Tile.kt. */
internal data class ColorOSGlass(
    val fill: Color,
    val iconOff: Color,
    val accent: Color,
    val dark: Boolean,
    /** Whether the glass is drawn by SurfaceFlinger, and not with [fill] and the rim. */
    val blurred: Boolean,
    val material: Int,
)

@Composable
internal fun colorOSGlass(blurred: Boolean): ColorOSGlass {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val accent = ColorOSSliderBlue
    return ColorOSGlass(
        fill = if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.55f),
        iconOff = onSurface,
        accent = accent,
        dark = dark,
        blurred = blurred,
        material = colorOSGlassTileMaterial(),
    )
}

/**
 * The glass of a pill, a slider, the ringer switch or the media card, which all use the material
 * of the tiles that are off. SurfaceFlinger draws it, with the tint, the lit rim and the glow of the
 * ColorOS material, when [glass] is [ColorOSGlass.blurred]. Otherwise it is a translucent fill
 * with a rim.
 */
@Composable
internal fun Modifier.glassSurface(glass: ColorOSGlass, shape: Shape): Modifier =
    if (glass.blurred) {
        this.clip(shape).colorOSGlassBlur(shape, glass.material)
    } else {
        // Without blur the light panel has no glass behind it to catch the light, so its glass
        // gets the white rim and the light along the top of the light panel of MiraCLO.
        this.clip(shape)
            .background(glass.fill, shape)
            .then(
                if (glass.dark) Modifier
                else Modifier.background(ColorOSLightGlassHighlight, shape)
            )
            .border(1.dp, colorOSTileRim(), shape)
    }

/**
 * "My Devices" of ColorOS 17: a glass pill with a bubble that holds the orbit glyph, which opens
 * the Bluetooth devices. It is the left half of the ringer row.
 */
@Composable
internal fun ColorOSMyDevices(
    glass: ColorOSGlass,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pressSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(percent = 50)
    val bubble = height * ColorOSRingerBubbleRatio
    val inset = (height - bubble) / 2
    Row(
        modifier =
            modifier
                .height(height)
                .colorOSPress(pressSource)
                .glassSurface(glass, shape)
                .clickable(
                    interactionSource = pressSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(horizontal = inset),
        horizontalArrangement = spacedBy(inset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(bubble)
                    .clip(CircleShape)
                    .background(
                        if (glass.dark) Color.White.copy(alpha = 0.12f)
                        else Color.Black.copy(alpha = 0.06f)
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_coloros_my_devices),
                contentDescription = null,
                tint = glass.iconOff,
                modifier = Modifier.size(bubble * 0.6f),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.qs_coloros_my_devices),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.qs_coloros_my_devices_hint),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** How long a released slider waits for the value it set to be reported back. */
private const val ColorOSSliderSettleMillis = 400L

/** How far the reported value may be from the released one to count as caught up. */
private const val ColorOSSliderSettleTolerance = 0.005f

/**
 * A tall pill slider: the white fill rises from the bottom. Dragging is relative to where the
 * finger lands, like ColorOS. While dragging, the shade is asked not to steal the gesture.
 */
@Composable
private fun ColorOSVerticalSlider(
    fraction: Float,
    @DrawableRes iconRes: Int,
    iconTint: Color,
    label: String,
    glass: ColorOSGlass,
    onChange: (Float) -> Unit,
    onStop: (Float) -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = ColorOSSliderIconSize,
    fullIconTint: Color? = null,
    snap: (Float) -> Float = { it },
    onLongPress: (() -> Unit)? = null,
    onIconTap: (() -> Unit)? = null,
    iconOverrideTint: Color? = null,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var local by remember { mutableFloatStateOf(fraction) }
    var dragging by remember { mutableStateOf(false) }
    // After a release, [fraction] reports the values on the way to the one that was set, the one
    // from before the drag among them, so the fill stays where it was released until the reported
    // value gets there instead of jumping back and forward again.
    var settling by remember { mutableStateOf(false) }
    LaunchedEffect(fraction, dragging, settling) {
        when {
            dragging -> Unit
            !settling -> local = fraction
            abs(fraction - local) < ColorOSSliderSettleTolerance -> {
                settling = false
                local = fraction
            }
            else -> {
                delay(ColorOSSliderSettleMillis)
                settling = false
                local = fraction
            }
        }
    }
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnStop by rememberUpdatedState(onStop)
    val currentSnap by rememberUpdatedState(snap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnIconTap by rememberUpdatedState(onIconTap)
    val shape = RoundedCornerShape(percent = 50)
    // The icon at the foot of the slider, as far as a tap on it reaches.
    val iconZone = with(LocalDensity.current) { (ColorOSSliderIconBottomInset * 2 + iconSize).toPx() }

    Box(
        modifier =
            modifier
                .colorOSStretch()
                .glassSurface(glass, shape)
                .semantics { contentDescription = label }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        dragging = true
                        down.consume()
                        val start = local
                        val height = size.height.coerceAtLeast(1)
                        // A tap on the icon does not set a level; moving from it still does.
                        var onIcon = currentOnIconTap != null && down.position.y >= height - iconZone
                        // A finger that stays down without moving does not set a level, it fires
                        // the long press. Once that fired the rest of the gesture is ignored.
                        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                        var longPressed = false
                        val longPressJob =
                            if (currentOnLongPress != null) {
                                scope.launch {
                                    delay(longPressTimeout)
                                    longPressed = true
                                    currentOnLongPress?.invoke()
                                }
                            } else {
                                null
                            }
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (longPressed) {
                                change.consume()
                                continue
                            }
                            if ((change.position - down.position).getDistance() >
                                viewConfiguration.touchSlop
                            ) {
                                longPressJob?.cancel()
                                onIcon = false
                            }
                            if (onIcon) {
                                change.consume()
                                continue
                            }
                            val delta = (down.position.y - change.position.y) / height
                            local = (start + delta).coerceIn(0f, 1f)
                            currentOnChange(local)
                            change.consume()
                        }
                        longPressJob?.cancel()
                        if (onIcon && !longPressed) {
                            currentOnIconTap?.invoke()
                        } else if (!longPressed) {
                            local = currentSnap(local)
                            settling = true
                            currentOnStop(local)
                        }
                        dragging = false
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
    ) {
        Box(
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(local.coerceIn(0.001f, 1f))
                    .background(ColorOSSliderFill)
        )
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint =
                when {
                    iconOverrideTint != null -> iconOverrideTint
                    fullIconTint != null && local >= COLOROS_FULL_THRESHOLD -> fullIconTint
                    local > 0.18f -> iconTint
                    else -> glass.iconOff
                },
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = ColorOSSliderIconBottomInset)
                    .size(iconSize),
        )
    }
}

@Composable
internal fun ColorOSBrightnessSlider(
    viewModel: BrightnessSliderViewModel,
    glass: ColorOSGlass,
    modifier: Modifier = Modifier,
) {
    val gamma = viewModel.currentBrightness.value
    val min = viewModel.minBrightness.value
    val max = viewModel.maxBrightness.value
    val scope = rememberCoroutineScope()
    val span = (max - min).coerceAtLeast(1)
    val fraction =
        if (gamma < 0) 0.5f else ((gamma - min).toFloat() / span).coerceIn(0f, 1f)
    fun toGamma(f: Float) = GammaBrightness((min + f * span).toInt().coerceIn(min, max))
    val context = LocalContext.current
    val automatic by rememberColorOSAutoBrightness()

    ColorOSVerticalSlider(
        fraction = fraction,
        // Automatic brightness shows the sun with an A, in yellow.
        iconRes =
            if (automatic) R.drawable.ic_coloros_brightness_auto else R.drawable.ic_coloros_brightness,
        iconOverrideTint = if (automatic) ColorOSBrightnessAuto else null,
        onIconTap = { setColorOSAutoBrightness(context, !automatic) },
        iconTint = ColorOSSliderIconOnFill,
        // At full brightness only the sun icon turns orange, like the 🔆 emoji.
        fullIconTint = ColorOSBrightnessFull,
        label = stringResource(R.string.quick_settings_brightness_dialog_title),
        glass = glass,
        onChange = { f -> scope.launch { viewModel.onDrag(Drag.Dragging(toGamma(f))) } },
        onStop = { f -> scope.launch { viewModel.onDrag(Drag.Stopped(toGamma(f))) } },
        modifier = modifier,
    )
}

@Composable
internal fun ColorOSVolumeSlider(
    audioVolumeInteractor: AudioVolumeInteractor,
    glass: ColorOSGlass,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val stream = remember { AudioStream(AudioManager.STREAM_MUSIC) }
    val model by
        remember(audioVolumeInteractor) { audioVolumeInteractor.getAudioStream(stream) }
            .collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val current = model
    val min = current?.minVolume ?: 0
    val max = current?.maxVolume ?: 1
    val span = (max - min).coerceAtLeast(1)
    val fraction = current?.let { ((it.volume - min).toFloat() / span).coerceIn(0f, 1f) } ?: 0f
    fun toVolume(f: Float) = (min + Math.round(f * span)).coerceIn(min, max)
    val silent = current?.isMuted == true || fraction == 0f
    val headphones = rememberColorOSHeadphonesOutput()

    ColorOSVerticalSlider(
        fraction = fraction,
        iconRes =
            when {
                silent && headphones -> R.drawable.ic_coloros_volume_headphones_mute
                headphones -> R.drawable.ic_coloros_volume_headphones
                silent -> R.drawable.ic_coloros_volume_mute
                else -> R.drawable.ic_coloros_volume
            },
        iconTint = glass.accent,
        iconSize = if (headphones) ColorOSVolumeHeadphonesIconSize else ColorOSVolumeIconSize,
        label = stringResource(R.string.stream_music),
        glass = glass,
        onChange = { f -> scope.launch { audioVolumeInteractor.setVolume(stream, toVolume(f)) } },
        onStop = { f -> scope.launch { audioVolumeInteractor.setVolume(stream, toVolume(f)) } },
        modifier = modifier,
        // The volume is in steps, so the fill rests on the step the volume gets set to.
        snap = { f -> ((toVolume(f) - min).toFloat() / span).coerceIn(0f, 1f) },
        onLongPress = {
            audioManager?.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_TOGGLE_MUTE,
                0,
            )
        },
    )
}

/** How far the end buttons are pulled towards the middle, in segments, by the selected bubble. */
private const val ColorOSRingerNudge = 0.1f

private class ColorOSRingerMode(
    val mode: Int,
    @DrawableRes val icon: Int,
    @RawRes val animation: Int,
    val label: String,
)

/**
 * Which way the switch leans. The buttons and the bubble lean in, towards the middle, only while
 * the bubble sits at an end, with the middle one selected each icon stays in the middle of its
 * third.
 */
private fun colorOSRingerNudge(selected: Int, last: Int): Float =
    when (selected) {
        0 -> 1f
        last -> -1f
        else -> 0f
    }

@Composable
internal fun ColorOSRingerSwitch(
    audioVolumeInteractor: AudioVolumeInteractor,
    glass: ColorOSGlass,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val ringerMode by audioVolumeInteractor.ringerMode.collectAsStateWithLifecycle()
    val modes =
        listOf(
            ColorOSRingerMode(
                AudioManager.RINGER_MODE_NORMAL,
                R.drawable.ic_volume_ringer,
                R.raw.coloros_ringer_normal,
                stringResource(R.string.volume_ringer_status_normal),
            ),
            ColorOSRingerMode(
                AudioManager.RINGER_MODE_VIBRATE,
                R.drawable.ic_volume_ringer_vibrate,
                R.raw.coloros_ringer_vibrate,
                stringResource(R.string.volume_ringer_status_vibrate),
            ),
            ColorOSRingerMode(
                AudioManager.RINGER_MODE_SILENT,
                R.drawable.ic_volume_ringer_mute,
                R.raw.coloros_ringer_silent,
                stringResource(R.string.volume_ringer_status_silent),
            ),
        )
    val shape = RoundedCornerShape(percent = 50)
    val bubble = height * ColorOSRingerBubbleRatio
    val selected = modes.indexOfFirst { it.mode == ringerMode.value }.coerceAtLeast(0)
    val last = modes.lastIndex
    val bubbleColor by
        animateColorAsState(
            if (glass.dark) ColorOSActiveFill else colorOSRingerLightBubble(modes[selected].mode),
            colorOSSpring(0f, 0.3f),
            label = "colorOSRingerBubbleColor",
        )

    BoxWithConstraints(
        modifier = modifier.height(height).glassSurface(glass, shape),
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        val segmentPx = with(density) { maxWidth.toPx() } / modes.size
        val bubblePx = with(density) { bubble.toPx() }
        val nudge = colorOSRingerNudge(selected, last) * ColorOSRingerNudge * segmentPx
        // One bubble slides to the selected mode, it leans a little towards the middle when it
        // sits at an end. The buttons lean with it.
        val indicatorX by
            animateFloatAsState(
                selected * segmentPx + (segmentPx - bubblePx) / 2f + nudge,
                colorOSSpring(0.25f, 0.3f),
                label = "colorOSRingerIndicator",
            )
        // requiredSize: the slot can be a little narrower than the bubble on small screens, and
        // size() would squash the circle into an oval.
        Box(
            Modifier.graphicsLayer { translationX = indicatorX }
                .requiredSize(bubble)
                .background(bubbleColor, CircleShape)
        )
        Row(Modifier.fillMaxSize()) {
            modes.forEachIndexed { index, mode ->
                val shift by
                    animateFloatAsState(
                        nudge,
                        colorOSSpring(0f, 0.35f),
                        label = "colorOSRingerNudge",
                    )
                ColorOSRingerButton(
                    mode = mode,
                    selected = index == selected,
                    glass = glass,
                    audioManager = audioManager,
                    modifier =
                        Modifier.weight(1f).fillMaxHeight().graphicsLayer { translationX = shift },
                    iconSize = minOf(22.dp, height * ColorOSRingerIconRatio),
                )
            }
        }
    }
}

/**
 * One mode of the ringer switch. Its icon is red on the white bubble of a dark panel and white on
 * the colored bubble of a light one, it grows a little and plays its animation when it gets selected.
 */
@Composable
private fun ColorOSRingerButton(
    mode: ColorOSRingerMode,
    selected: Boolean,
    glass: ColorOSGlass,
    audioManager: AudioManager?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
) {
    val pressSource = remember { MutableInteractionSource() }
    val scale by
        animateFloatAsState(
            if (selected) 1.2f else 1f,
            colorOSSpring(0f, 0.25f),
            label = "colorOSRingerScale",
        )
    val color by
        animateColorAsState(
            when {
                !selected -> glass.iconOff
                glass.dark -> ColorOSRingerRed
                else -> Color.White
            },
            colorOSSpring(0f, 0.3f),
            label = "colorOSRingerColor",
        )
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(mode.animation))
    val progress = remember { Animatable(1f) }
    var wasSelected by remember { mutableStateOf(selected) }
    LaunchedEffect(selected, composition) {
        val clip = composition ?: return@LaunchedEffect
        if (selected == wasSelected) return@LaunchedEffect
        wasSelected = selected
        if (!selected) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(clip.duration.toInt(), easing = LinearEasing))
    }

    Box(
        modifier =
            modifier
                .colorOSPress(
                    pressSource,
                    press = colorOSSpring(0.18f, 0.13f),
                    release = colorOSSpring(0.62f, 0.35f),
                )
                .semantics { contentDescription = mode.label }
                .pointerInput(mode.mode) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val press = PressInteraction.Press(down.position)
                        pressSource.tryEmit(press)
                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            up.consume()
                            pressSource.tryEmit(PressInteraction.Release(press))
                            audioManager?.setRingerModeInternal(mode.mode)
                        } else {
                            pressSource.tryEmit(PressInteraction.Cancel(press))
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        val iconModifier =
            Modifier.size(iconSize).graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
        val clip = composition
        if (clip != null) {
            // One colour for the whole animation, so it follows the icon colour above.
            val properties =
                rememberLottieDynamicProperties(
                    rememberLottieDynamicProperty(LottieProperty.COLOR, color.toArgb(), "**"),
                    rememberLottieDynamicProperty(
                        LottieProperty.STROKE_COLOR,
                        color.toArgb(),
                        "**",
                    ),
                )
            LottieAnimation(
                composition = clip,
                progress = { progress.value },
                dynamicProperties = properties,
                modifier = iconModifier,
            )
        } else {
            Icon(
                painter = painterResource(mode.icon),
                contentDescription = null,
                tint = color,
                modifier = iconModifier,
            )
        }
    }
}

/** Diameter of the two round buttons of the ColorOS header. */
private val ColorOSHeaderButtonSize = 40.dp

/** How far the two buttons sit in from the sides of the panel, ColorOS keeps them off its edge. */
private val ColorOSHeaderButtonInset = 6.dp

/** How far the row of the buttons is moved up, ColorOS puts it higher above the status row. */
private val ColorOSHeaderButtonRaise = 5.5.dp

/**
 * The glyphs of the two header buttons are drawn at different sizes, as the ColorOS gear is larger
 * in its circle than the pencil is.
 */
private val ColorOSHeaderEditIconSize = 21.dp
private val ColorOSHeaderSettingsIconSize = 29.dp

/** The glyph of a header button over a light part of the header image. */
private val ColorOSHeaderIconOnLightImage = Color.Black

/**
 * The two buttons above the panel: edit on the start side and settings on the end side, round
 * glass like the tiles that are off. A missing [onSettings], or a button that the footer settings
 * hide, leaves its place empty. The panel has no power button, so a long press on settings opens
 * the power menu ([onSettingsLongClick]), and a long press on edit opens the Evolver settings
 * ([onEditLongClick]), which the long press of the regular settings button does.
 */
@Composable
internal fun ColorOSHeaderButtons(
    onEdit: (Expandable) -> Unit,
    onSettings: ((Expandable) -> Unit)?,
    modifier: Modifier = Modifier,
    onEditLongClick: ((Expandable) -> Unit)? = null,
    onSettingsLongClick: ((Expandable) -> Unit)? = null,
) {
    val glass = colorOSGlass(blurred = LocalColorOSGlassBlur.current != null)
    // The same settings that hid the buttons of the footer. A hidden button keeps its place, so
    // the other one stays on its side.
    val showEdit by rememberSystemSettingEnabled(Settings.System.QS_FOOTER_SHOW_EDIT)
    val showSettings by rememberSystemSettingEnabled(Settings.System.QS_FOOTER_SHOW_SETTINGS)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .offset(y = -ColorOSHeaderButtonRaise)
                .padding(horizontal = ColorOSHeaderButtonInset),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        if (showEdit) {
            ColorOSHeaderButton(
                iconRes = R.drawable.ic_edit,
                iconSize = ColorOSHeaderEditIconSize,
                region = QsHeaderImageTint.Region.EDIT,
                description = stringResource(R.string.accessibility_quick_settings_edit),
                glass = glass,
                onClick = onEdit,
                onLongClick = onEditLongClick,
            )
        } else {
            Spacer(Modifier.size(ColorOSHeaderButtonSize))
        }
        if (showSettings && onSettings != null) {
            ColorOSHeaderButton(
                iconRes = R.drawable.ic_qs_footer_settings,
                iconSize = ColorOSHeaderSettingsIconSize,
                region = QsHeaderImageTint.Region.SETTINGS,
                description = stringResource(R.string.accessibility_quick_settings_settings),
                glass = glass,
                onClick = onSettings,
                onLongClick = onSettingsLongClick,
            )
        } else {
            Spacer(Modifier.size(ColorOSHeaderButtonSize))
        }
    }
}

/** The glass of the header buttons, for buttons drawn by another component. */
@Composable
internal fun Modifier.colorOSHeaderGlass(shape: Shape): Modifier =
    glassSurface(colorOSGlass(blurred = LocalColorOSGlassBlur.current != null), shape)

/**
 * The edit button at the start of the footer row of the large screen panel, with the glyph as large as
 * the footer buttons beside it. The footer setting that hides it leaves its place empty.
 */
@Composable
internal fun ColorOSTabletEditButton(
    size: Dp,
    modifier: Modifier = Modifier,
    onEdit: (Expandable) -> Unit,
) {
    val showEdit by rememberSystemSettingEnabled(Settings.System.QS_FOOTER_SHOW_EDIT)
    if (!showEdit) {
        Spacer(modifier.size(size))
        return
    }
    Box(modifier) { ColorOSHeaderButton(
        iconRes = R.drawable.ic_edit,
        iconSize = ColorOSTabletHeaderIconSize,
        region = QsHeaderImageTint.Region.EDIT,
        description = stringResource(R.string.accessibility_quick_settings_edit),
        glass = colorOSGlass(blurred = LocalColorOSGlassBlur.current != null),
        onClick = onEdit,
        size = size,
    ) }
}

/** Size of the default footer buttons, which the large screen panel keeps. */
internal val ColorOSTabletStockButtonSize = 40.dp

/** Glyph size of the footer buttons of the large screen panel. */
private val ColorOSTabletHeaderIconSize = 18.dp

@Composable
private fun ColorOSHeaderButton(
    iconRes: Int,
    iconSize: Dp,
    region: QsHeaderImageTint.Region,
    description: String,
    glass: ColorOSGlass,
    onClick: (Expandable) -> Unit,
    onLongClick: ((Expandable) -> Unit)? = null,
    size: Dp = ColorOSHeaderButtonSize,
) {
    val pressSource = remember { MutableInteractionSource() }
    val onLightImage by QsHeaderImageTint.lightFlow(region).collectAsState()
    Expandable(
        color = Color.Unspecified,
        shape = CircleShape,
        onClick = onClick,
        onLongClick = onLongClick,
        interactionSource = pressSource,
        useModifierBasedImplementation = true,
        defaultMinSize = false,
        modifier =
            Modifier.size(size)
                .onGloballyPositioned {
                    // Where the header image is sampled to tell if the glyph has to be dark.
                    val b = it.boundsInWindow()
                    QsHeaderImageTint.setBounds(
                        region,
                        b.left.roundToInt(),
                        b.top.roundToInt(),
                        b.right.roundToInt(),
                        b.bottom.roundToInt(),
                    )
                }
                .colorOSPress(pressSource)
                .semantics {
                    role = Role.Button
                    contentDescription = description
                }
                .glassSurface(glass, CircleShape),
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (onLightImage) ColorOSHeaderIconOnLightImage else glass.iconOff,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * The large screen ColorOS panel: every tile, the media card, the sliders, the ringer switch and My
 * Devices on one grid of [rememberColorOSTabletColumns], in the order and sizes of the editor, in
 * pages.
 */
@Composable
private fun ContentScope.ColorOSTabletLayout(
    tiles: List<TileViewModel>,
    largeTiles: Set<String>,
    infiniteGridLayout: InfiniteGridLayout,
    brightnessViewModel: BrightnessSliderViewModel,
    audioVolumeInteractor: AudioVolumeInteractor,
    showMedia: Boolean,
    media: @Composable (Modifier) -> Unit,
    listening: () -> Boolean,
    glass: ColorOSGlass,
    glassBlur: ColorOSGlassBlur?,
    labelHeight: Dp,
    labelsHidden: Boolean,
    availableHeight: Dp,
    fullWidth: Dp,
    onOpenDevices: () -> Unit,
    reveal: @Composable (Modifier) -> Modifier,
) {
    val columns = rememberColorOSTabletColumns(infiniteGridLayout)
    val metrics = rememberColorOSTabletMetrics(columns)
    val saved by rememberColorOSTabletLayout()
    val bySpec = remember(tiles) { tiles.associateBy { it.spec.spec } }
    val entries =
        remember(saved, tiles) { colorOSTabletEntries(saved, tiles.map { it.spec.spec }) }
    val spans = remember(entries, largeTiles) { entries.map { colorOSTabletSpan(it, largeTiles) } }
    CompositionLocalProvider(
        LocalColorOSTileSize provides metrics.tile,
        LocalColorOSGap provides metrics.gap,
        LocalColorOSTablet provides true,
        LocalColorOSGlassBlur provides glassBlur,
    ) {
        ColorOSTabletGrid(
            entries = entries,
            spans = spans,
            metrics = metrics,
            label = labelHeight,
            availableHeight = availableHeight,
            fullWidth = fullWidth,
            item = { entry, span, itemModifier ->
                val cell = reveal(itemModifier.fillMaxSize())
                val top: @Composable (@Composable (Modifier) -> Unit) -> Unit = { content ->
                    Box(cell, contentAlignment = Alignment.TopCenter) {
                        content(Modifier.fillMaxWidth().height(metrics.tile))
                    }
                }
                when (entry.key) {
                    ColorOSTabletItem.MEDIA ->
                        Box(cell.glassSurface(glass, ColorOSMediaShape)) {
                            val content = Modifier.fillMaxSize().colorOSFitScale(ColorOSMediaBaseHeight)
                            if (showMedia) media(content) else ColorOSIdleMediaCard(content)
                        }
                    ColorOSTabletItem.BRIGHTNESS ->
                        ColorOSBrightnessSlider(brightnessViewModel, glass, cell)
                    ColorOSTabletItem.VOLUME -> ColorOSVolumeSlider(audioVolumeInteractor, glass, cell)
                    ColorOSTabletItem.RINGER ->
                        top { ColorOSRingerSwitch(audioVolumeInteractor, glass, metrics.tile, it) }
                    ColorOSTabletItem.MY_DEVICES ->
                        if (span.colSpan > 1) {
                            top { ColorOSMyDevices(glass, metrics.tile, onOpenDevices, it) }
                        } else {
                            ColorOSMyDevicesToggle(glass, metrics.tile, labelsHidden, onOpenDevices, cell)
                        }
                    else ->
                        bySpec[entry.key]?.let { tile ->
                            if (span.colSpan > 1) {
                                top {
                                    with(infiniteGridLayout) {
                                        ColorOSWideTile(
                                            tile,
                                            listening,
                                            it,
                                            iconOnly = false,
                                            shared = false,
                                        )
                                    }
                                }
                            } else {
                                Box(cell, contentAlignment = Alignment.TopCenter) {
                                    with(infiniteGridLayout) {
                                        ColorOSWideTile(
                                            tile,
                                            listening,
                                            Modifier.fillMaxWidth()
                                                .wrapContentHeight(Alignment.Top, unbounded = true),
                                            iconOnly = true,
                                            shared = false,
                                        )
                                    }
                                }
                            }
                        }
                }
            },
        )
    }
}

/** Yellow of the automatic brightness icon on the slider. */
private val ColorOSBrightnessAuto = Color(0xFFFFC107)

/** Whether automatic brightness is on, kept up to date while the setting changes. */
@Composable
private fun rememberColorOSAutoBrightness(): State<Boolean> {
    val context = LocalContext.current
    fun read() =
        Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            UserHandle.USER_CURRENT,
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    val state = remember(context) { mutableStateOf(read()) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    state.value = read()
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        state.value = read()
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return state
}

private fun setColorOSAutoBrightness(context: Context, automatic: Boolean) {
    Settings.System.putIntForUser(
        context.contentResolver,
        Settings.System.SCREEN_BRIGHTNESS_MODE,
        if (automatic) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        UserHandle.USER_CURRENT,
    )
}

/** Items of the large screen ColorOS panel that are not tiles, placed in its grid like tiles. */
internal object ColorOSTabletItem {
    const val MEDIA = "coloros_media"
    const val BRIGHTNESS = "coloros_brightness"
    const val VOLUME = "coloros_volume"
    const val RINGER = "coloros_ringer"
    const val MY_DEVICES = "coloros_mydevices"

    val ALL = listOf(MEDIA, BRIGHTNESS, VOLUME, RINGER, MY_DEVICES)

    fun isItem(key: String) = key in ALL

    /** Items that are one cell or a two column pill, like a tile. The ringer switch is always a pill. */
    fun isResizable(key: String) = key == MY_DEVICES
}

/** Secure setting with the order of the panel's tiles and items, and the small items. */
internal const val COLOROS_TABLET_LAYOUT_SETTING = "sysui_coloros_tablet_layout"

/** One entry of the large screen panel: a tile spec or an item, and whether an item is small. */
internal data class ColorOSTabletEntry(val key: String, val small: Boolean = false)

internal fun parseColorOSTabletLayout(raw: String?): List<ColorOSTabletEntry>? =
    raw
        ?.takeIf { it.isNotBlank() }
        ?.split(",")
        ?.mapNotNull { item ->
            val parts = item.trim().split(":")
            parts[0].takeIf { it.isNotEmpty() }?.let {
                // A saved small ringer switch is a pill again, as it cannot be resized.
                ColorOSTabletEntry(
                    it,
                    small = parts.getOrNull(1) == "s" && ColorOSTabletItem.isResizable(it),
                )
            }
        }
        ?.distinctBy { it.key }

internal fun formatColorOSTabletLayout(entries: List<ColorOSTabletEntry>): String =
    entries.joinToString(",") { if (it.small) "${it.key}:s" else it.key }

internal fun readColorOSTabletLayout(context: Context): List<ColorOSTabletEntry>? =
    parseColorOSTabletLayout(
        Settings.Secure.getStringForUser(
            context.contentResolver,
            COLOROS_TABLET_LAYOUT_SETTING,
            UserHandle.USER_CURRENT,
        )
    )

internal fun writeColorOSTabletLayout(context: Context, entries: List<ColorOSTabletEntry>) {
    Settings.Secure.putStringForUser(
        context.contentResolver,
        COLOROS_TABLET_LAYOUT_SETTING,
        formatColorOSTabletLayout(entries),
        UserHandle.USER_CURRENT,
    )
}

/** The saved order of the panel, null before it was ever edited, kept up to date. */
@Composable
internal fun rememberColorOSTabletLayout(): State<List<ColorOSTabletEntry>?> {
    val context = LocalContext.current
    val state = remember(context) { mutableStateOf(readColorOSTabletLayout(context)) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    state.value = readColorOSTabletLayout(context)
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(COLOROS_TABLET_LAYOUT_SETTING),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        state.value = readColorOSTabletLayout(context)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return state
}

/**
 * The entries of the panel in order: the saved ones that still exist and then the tiles added since.
 * Before the panel was edited, the Wi-Fi and Bluetooth tiles come first, then the media card, the
 * sliders, the ringer switch and My Devices, as on ColorOS, then the other tiles. A device with
 * mobile data has its tile there instead of My Devices.
 */
internal fun colorOSTabletEntries(
    saved: List<ColorOSTabletEntry>?,
    tileSpecs: List<String>,
): List<ColorOSTabletEntry> {
    if (saved == null) {
        val first = tileSpecs.filter { it in ColorOSTabletFirstTiles }
        val cell = ColorOSTabletCellTile.takeIf { it in tileSpecs }
        val items =
            if (cell == null) ColorOSTabletItem.ALL
            else ColorOSTabletItem.ALL.map { if (it == ColorOSTabletItem.MY_DEVICES) cell else it }
        return (first + items + tileSpecs.filterNot { it in first || it == cell }).map {
            ColorOSTabletEntry(it)
        }
    }
    val present = tileSpecs.toSet()
    val kept = saved.filter { ColorOSTabletItem.isItem(it.key) || it.key in present }
    val keys = kept.mapTo(HashSet()) { it.key }
    return kept + tileSpecs.filterNot { it in keys }.map { ColorOSTabletEntry(it) }
}

private val ColorOSTabletFirstTiles = setOf("internet", "wifi", "bt", "bluetooth")

/** The mobile data tile, which takes the place of My Devices in the default layout. */
internal const val ColorOSTabletCellTile = "cell"

/**
 * Cells an entry takes: the media card 2x2, a slider 1x2, a pill 2x1 (the ringer switch always) and
 * a round tile 1x1.
 */
internal fun colorOSTabletSpan(entry: ColorOSTabletEntry, large: Set<String>): GridSpan =
    when (entry.key) {
        ColorOSTabletItem.MEDIA -> GridSpan(colSpan = 2, rowSpan = 2)
        ColorOSTabletItem.BRIGHTNESS,
        ColorOSTabletItem.VOLUME -> GridSpan(colSpan = 1, rowSpan = 2)
        ColorOSTabletItem.RINGER -> GridSpan(colSpan = 2, rowSpan = 1)
        ColorOSTabletItem.MY_DEVICES -> GridSpan(colSpan = if (entry.small) 1 else 2, rowSpan = 1)
        else -> GridSpan(colSpan = if (entry.key in large) 2 else 1, rowSpan = 1)
    }

/** Sizes of the large screen panel: its tile (pill height, slider width), its gap and columns. */
internal data class ColorOSTabletMetrics(
    val tile: Dp,
    val gap: Dp,
    val columns: Int = ColorOSTabletColumns,
) {
    val width: Dp
        get() = tile * columns + gap * (columns - 1)
}

/** Fewest and most columns of the large screen panel. */
internal const val ColorOSTabletColumns = 4
internal const val ColorOSTabletMaxColumns = 6

/** The panel has the tile and the gap of its editor, the same everywhere, on [columns]. */
@Composable
internal fun rememberColorOSTabletMetrics(columns: Int): ColorOSTabletMetrics =
    ColorOSTabletMetrics(CommonTileDefaults.ColorOSTileHeight, ColorOSGap, columns)

/**
 * Columns of the large screen panel: those of the grid of the other styles, so the column setting
 * and the orientation change them the same way, from [ColorOSTabletColumns] to
 * [ColorOSTabletMaxColumns].
 */
@Composable
internal fun rememberColorOSTabletColumns(factory: QSColumnsViewModel.Factory): Int {
    val columns =
        rememberViewModel(traceName = "ColorOSTabletColumns", key = factory) {
            factory.createWithoutMediaTracking()
        }
    // The tiles keep their size: a pane too narrow for the columns gets fewer of them. The pane is
    // half the screen in split shade, less the margin on each side. Six columns in landscape can
    // reach into the side inset of the panel, see ColorOSTabletGrid.
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val splitShade =
        booleanResource(R.bool.config_use_split_notification_shade) ||
            booleanResource(R.bool.force_config_use_split_notification_shade)
    val paneWidth =
        (if (splitShade) screenWidth / 2 else screenWidth) -
            dimensionResource(R.dimen.qs_horizontal_margin) * 2
    val tile = CommonTileDefaults.ColorOSTileHeight
    var fit = columns.columns.coerceIn(ColorOSTabletColumns, ColorOSTabletMaxColumns)
    while (fit > ColorOSTabletColumns && tile * fit + ColorOSGap * (fit - 1) > paneWidth) fit--
    return fit
}

/** [rememberColorOSTabletColumns] with the column factory of [infiniteGridLayout]. */
@Composable
internal fun rememberColorOSTabletColumns(infiniteGridLayout: InfiniteGridLayout): Int =
    rememberColorOSTabletColumns(
        remember(infiniteGridLayout) {
            infiniteGridLayout.viewModelFactory.create().columnsWithMediaViewModelFactory
        }
    )

/** Whether a round one cell entry has a label under it, which makes its row taller. */
private fun GridSpan.isLabelled() = colSpan == 1 && rowSpan == 1

/** Rows of [placements] that hold a labelled entry, by their index in the page. */
private fun labelledRows(placements: List<PlacedGridItem>, spans: List<GridSpan>): Set<Int> =
    placements.filter { spans[it.index].isLabelled() }.mapTo(HashSet()) { it.row }

/** Height of the rows of a page, with the labelled ones taller by [label]. */
private fun pageHeight(
    placements: List<PlacedGridItem>,
    spans: List<GridSpan>,
    metrics: ColorOSTabletMetrics,
    label: Dp,
): Dp {
    val rows = placements.maxOfOrNull { it.row + it.rowSpan } ?: 0
    if (rows == 0) return 0.dp
    // The labels of the last row hang under it.
    return metrics.tile * rows + colorOSTabletRowGap(metrics, label) * (rows - 1) + label
}

/** Gap between the rows of the panel: room for the labels of round tiles, the gap without them. */
internal fun colorOSTabletRowGap(metrics: ColorOSTabletMetrics, label: Dp): Dp =
    maxOf(metrics.gap, label)

/** One page of the panel: the indices of its entries and where they are, from row 0. */
internal data class ColorOSTabletPage(val indices: List<Int>, val placements: List<PlacedGridItem>)

/**
 * The pages of the panel: as many rows a page as fit [available], at least two, flowing [spans] in
 * order over the columns of [metrics]. Each placement is reindexed to its page.
 */
internal fun colorOSTabletPages(
    spans: List<GridSpan>,
    metrics: ColorOSTabletMetrics,
    label: Dp,
    available: Dp,
): List<ColorOSTabletPage> {
    fun paged(rows: Int): List<ColorOSTabletPage> {
        val placed = calculate2DPlacements(spans, metrics.columns, maxRowsPerPage = rows)
        return placed
            .groupBy { it.row / rows }
            .toSortedMap()
            .values
            .map { items ->
                val indices = items.map { it.index }
                ColorOSTabletPage(
                    indices,
                    items.mapIndexed { i, p -> p.copy(index = i, row = p.row % rows) },
                )
            }
            .ifEmpty { listOf(ColorOSTabletPage(emptyList(), emptyList())) }
    }
    if (available == Dp.Unspecified || available <= 0.dp) return paged(ColorOSTabletDefaultRows)
    for (rows in ColorOSTabletMaxRows downTo 2) {
        val pages = paged(rows)
        val fits =
            pages.all { page ->
                pageHeight(page.placements, page.indices.map { spans[it] }, metrics, label) <=
                    available
            }
        if (fits) return pages
    }
    return paged(2)
}

private const val ColorOSTabletDefaultRows = 6
private const val ColorOSTabletMaxRows = 10

/** Height of the page dots under the grid of the large screen panel. */
private val ColorOSTabletDotsHeight = 20.dp

/**
 * Room the pager of the large screen panel keeps beside its columns for the press bounce of the
 * items, which scales a pill by up to about 3% above its size when it springs back, 2dp on each side
 * of one that is two columns wide. The pager clips to its bounds.
 */
private val ColorOSTabletBounceHeadroom = 6.dp

/**
 * The grid of the large screen panel: every tile and item on the columns of [metrics], in pages.
 * [item] draws one entry in the cells it is given. The pager spans the width it is given so the
 * next page slides in whole, and each page keeps to the columns in its middle.
 */
@Composable
internal fun ColorOSTabletGrid(
    entries: List<ColorOSTabletEntry>,
    spans: List<GridSpan>,
    metrics: ColorOSTabletMetrics,
    label: Dp,
    availableHeight: Dp,
    fullWidth: Dp,
    item: @Composable (entry: ColorOSTabletEntry, span: GridSpan, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridHeight =
        if (availableHeight == Dp.Unspecified) Dp.Unspecified
        else availableHeight - ColorOSTabletDotsHeight
    val pages =
        remember(spans, metrics, label, gridHeight) {
            colorOSTabletPages(spans, metrics, label, gridHeight)
        }
    val pagerState = rememberPagerState { pages.size }
    val inset = ((fullWidth - metrics.width) / 2).coerceAtLeast(0.dp)
    // The columns can reach the edge of the panel, as six of them do in landscape. The pager is
    // made wider than the panel by what [inset] lacks of the headroom, and keeps its pages where
    // they were, so the bounce of the items in the outer columns is not cut off by its bounds.
    val extra = (ColorOSTabletBounceHeadroom - inset).coerceAtLeast(0.dp)
    Column(modifier.requiredWidth(fullWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = inset + extra),
            pageSpacing = inset + extra,
            beyondViewportPageCount = 1,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.requiredWidth(fullWidth + extra * 2),
        ) { pageIndex ->
            val page = pages[pageIndex]
            val pageSpans = page.indices.map { spans[it] }
            TwoDimensionalSpannedGrid(
                columns = metrics.columns,
                columnSpacing = metrics.gap,
                rowSpacing = colorOSTabletRowGap(metrics, label),
                spans = pageSpans,
                customPlacements = page.placements,
                rowHeight = metrics.tile,
                // The labels under the last row stay above the page dots.
                modifier = Modifier.width(metrics.width).padding(bottom = label),
                keys = { entries[page.indices[it]].key },
            ) { index, _ ->
                item(entries[page.indices[index]], pageSpans[index], Modifier)
            }
        }
        if (pages.size > 1) {
            Box(Modifier.height(ColorOSTabletDotsHeight), contentAlignment = Alignment.Center) {
                PagerDots(
                    pagerState = pagerState,
                    activeColor = MaterialTheme.colorScheme.onSurface,
                    nonActiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    roundDots = true,
                )
            }
        }
    }
}

/** My Devices as a round tile, which opens the Bluetooth devices. */
@Composable
internal fun ColorOSMyDevicesToggle(
    glass: ColorOSGlass,
    size: Dp,
    labelsHidden: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ColorOSRoundItem(
        icon = R.drawable.ic_coloros_my_devices,
        label = stringResource(R.string.qs_coloros_my_devices),
        glass = glass,
        size = size,
        labelsHidden = labelsHidden,
        onClick = onClick,
        modifier = modifier,
    )
}

/** A round glass item with a glyph and a label under it, like a round tile. */
@Composable
private fun ColorOSRoundItem(
    icon: Int,
    label: String,
    glass: ColorOSGlass,
    size: Dp,
    labelsHidden: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The fill and glyph colour of an item that is lit, as a tile that is on; null when off. */
    active: Pair<Color, Color>? = null,
) {
    Column(
        modifier.wrapContentHeight(Alignment.Top, unbounded = true),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(size)
                    .glassSurface(glass, CircleShape)
                    .then(
                        if (active != null) Modifier.background(active.first, CircleShape)
                        else Modifier
                    )
                    .clickable(onClick = onClick)
                    .semantics {
                        role = Role.Button
                        contentDescription = label
                    },
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = active?.second ?: MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(size * CommonTileDefaults.ColorOSIconRatio),
            )
        }
        if (!labelsHidden) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                style =
                    MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        lineHeight = CommonTileDefaults.ColorOSLabelLineHeight,
                    ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
            )
        }
    }
}
