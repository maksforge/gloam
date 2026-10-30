package app.gloam.ui.dim

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.gloam.R
import app.gloam.shade.AutoOff
import app.gloam.shade.PauseLength
import app.gloam.theme.Spacing
import kotlin.math.roundToInt

/**
 * Which disclosure is open, if any — **one at a time, and that is a size bound rather than taste.**
 *
 * Both compact hosts are windows sized to their own content, and the panel's is a *touchable* window
 * over somebody else's app: every pixel it claims is a pixel of the app underneath that stops
 * answering to a finger (ADR-0011). Two sections open at once is a window half again as wide as the
 * design, at 1.59 nits, blocking touches nobody can see it blocking. One at a time makes the widest
 * possible state a number the window can be *added with* rather than one that has to be measured.
 *
 * Kotlin note for a JS reader: an `enum class` used purely as a closed set of states — the
 * discriminated union you would write as `'none' | 'timer' | 'warmth'`, except the compiler checks
 * the `when` over it is exhaustive.
 */
private enum class CompactSection {
    None,
    Timer,
    Warmth,
}

/** Tapping the open section's own button closes it; tapping another's swaps to it. */
private fun CompactSection.toggling(target: CompactSection): CompactSection =
    if (this == target) CompactSection.None else target

/**
 * **The controls as they are met in the dark: an edge bar under the thumb, and two buttons under
 * it.**
 *
 * The shared body of both compact surfaces — `ControlsActivity`'s floating window *below* the shade
 * and `PanelWindow`'s overlay *above* it. It is not a smaller [DimControls]; it is a different
 * chrome around the same dim level, which is the seam that lets a change to what dim *means* still
 * land once. What both hosts now draw is the design's edge bar (4a / 6b), replacing the full-width
 * sheet that came before it.
 *
 * ## Why a bar at the edge rather than a sheet across the bottom
 *
 * The sheet was as wide as the display, which on the panel means a touchable window as wide as the
 * display: nothing underneath it answered a finger. The bar is 84dp and anchored to one side, so
 * what it blocks is a strip rather than a band, and the thumb that reaches it does not have to
 * travel to the middle of the screen to find it.
 *
 * ## The three gestures, and why two of them need a rule
 *
 * The bar's body sets the level wherever it is touched. The **foot band** (run/stop) and the **pip
 * row** (open the full screen) are tap targets inside that same surface, so each is a
 * [tapOrDragZone]: press and release does its own thing, press and slide more than
 * [BAR_TAP_SLOP_DP] becomes a level drag on the same track. Without that rule the top and bottom of
 * the bar would silently swallow drags that start inside them, which is exactly where a thumb lands.
 *
 * ## What is deliberately not here
 *
 * The schedule, the backlight switch and the deadline read-out. This surface is for the level, and
 * the full screen is one tap on the pips away — a surface reached in the dark earns its value by
 * *not* accumulating rows.
 *
 * @param onSectionOpen fires when a disclosure opens or closes, because in the panel the window's
 *   *width* is the safety bound and a section opening is the only thing that changes what it needs.
 *   The Activity host ignores it: its window is laid out by the window manager around its content.
 * @param onClose the panel's way out: it carries `FLAG_NOT_FOCUSABLE`, so the Back key never
 *   reaches it and this button plus the idle timeout are its only exits that do not also take the
 *   shade down. The compact host passes one too, so both draw the same group (`ControlsBody`).
 * @param onPause pauses for the length it is handed, which here is always the remembered one.
 * @param onOpenApp the pips, and the long press on the foot band. Both hosts route it to the full
 *   app rather than to a settings screen — that is where Settings is, and it is also the only way to
 *   reach the explainer, the schedule and everything this surface deliberately does not carry.
 */
@Composable
fun CompactControls(
    dimLevel: Int,
    warmth: Int,
    warmthColor: Int,
    running: Boolean,
    autoOff: AutoOff,
    pause: PauseUi,
    onDimLevel: (Int) -> Unit,
    onWarmth: (Int) -> Unit,
    onAutoOff: (AutoOff) -> Unit,
    onToggleRunning: () -> Unit,
    onPause: (PauseLength) -> Unit,
    onResume: () -> Unit,
    onOpenApp: () -> Unit,
    modifier: Modifier = Modifier,
    onSectionOpen: (Boolean) -> Unit = {},
    onClose: (() -> Unit)? = null,
) {
    // Not hoisted and not persisted: a disclosure is about the last few seconds, not about the user.
    // Both hosts are fresh per summon anyway — the compact host is `noHistory` and the panel builds
    // a new `PanelHost` each time — so "closed" is what every summon starts from by construction.
    //
    // Kotlin note: `by` delegation on `mutableStateOf` is `useState` with the setter hidden behind
    // assignment — `section = …` is the setter call, and reading `section` is what subscribes this
    // composable to it.
    var section by remember { mutableStateOf(CompactSection.None) }

    fun show(target: CompactSection) {
        section = section.toggling(target)
        onSectionOpen(section != CompactSection.None)
    }

    // **The bar's height is measured rather than fixed.** 330dp is the design's number for a phone
    // held upright; the same window in landscape has barely more height than that in total, and a
    // bar that does not fit is a bar whose foot band — the control that stops the dimming — is off
    // the bottom of the screen. `BoxWithConstraints` is the one composable that can read what its
    // parent is offering, which in both hosts is the display.
    // `CenterEnd` rather than a corner, so the box agrees with the gravity both hosts give their
    // window (`ShadeService.addPanelWindow` says why that is the centre). **This alignment is what
    // places the bar in the compact host**: its window fills the area between the system bars and
    // this centres the group inside it, while the panel's window wraps the group and is centred in
    // that same area by gravity. Same group, same area, same pixels — as long as both hosts pass the
    // same buttons, which is why the compact host now passes `onClose` too.
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.CenterEnd) {
        // `hasBoundedHeight` rather than a comparison against `Dp.Infinity`: an unbounded parent —
        // a scrolling column, or a measure pass before the window has been given a size — reports
        // no maximum at all, and the design's own height is the right answer there.
        val closeRows = if (onClose != null) 1 else 0
        val stack =
            compactStack(
                availableHeightDp = if (constraints.hasBoundedHeight) maxHeight.value else BAR_HEIGHT_DP,
                buttons = 3 + closeRows,
            )
        val barHeight = stack.barHeightDp.dp
        val optionalRows = stack.buttons - closeRows

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(Spacing.tight),
        ) {
            // The bar keeps the end of the row whether or not a section is open, so it never moves out
            // from under the thumb. A section takes the room to its left, which is room the window was
            // widened for.
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
                verticalAlignment = Alignment.Bottom,
            ) {
                when (section) {
                    CompactSection.Warmth ->
                        WarmthSection(warmth = warmth, warmthColor = warmthColor, onWarmth = onWarmth)
                    CompactSection.Timer ->
                        TimerSection(autoOff = autoOff, onAutoOff = onAutoOff)
                    CompactSection.None -> Unit
                }

                EdgeBar(
                    dimLevel = dimLevel,
                    running = running,
                    barHeight = barHeight,
                    onDimLevel = onDimLevel,
                    onToggleRunning = onToggleRunning,
                    onOpenApp = onOpenApp,
                )
            }

            // Centred on the bar rather than on the group: the buttons belong to the bar, and a group
            // that grows to the left would otherwise drag them away from it when a section opens.
            Box(modifier = Modifier.width(BAR_WIDTH_DP.dp), contentAlignment = Alignment.Center) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.tight)) {
                    if (optionalRows >= 1) {
                        CompactIconButton(
                            onClick = { show(CompactSection.Warmth) },
                            selected = section == CompactSection.Warmth,
                        ) {
                            // The glyph is the way out of the section it opened, which is why it changes:
                            // the button is in the same place either way, and a second tap on a sun that
                            // still says "open me" reads as a control that did not take.
                            if (section == CompactSection.Warmth) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.dim_warmth_label),
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.ic_warmth),
                                    contentDescription = stringResource(R.string.dim_warmth_label),
                                )
                            }
                        }
                    }
                    if (optionalRows >= 2) {
                        CompactIconButton(
                            onClick = { show(CompactSection.Timer) },
                            selected = section == CompactSection.Timer,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_timer),
                                // The section header's own words, so the button and what it opens agree.
                                // Icon buttons have no visible label, so this is the only name a screen
                                // reader gets.
                                contentDescription = stringResource(R.string.dim_auto_off_label),
                            )
                        }
                    }
                    if (optionalRows >= 3) {
                        PauseButton(running = running, pause = pause, onPause = onPause, onResume = onResume)
                    }
                    if (onClose != null) {
                        CompactIconButton(onClick = onClose, selected = false) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.panel_close),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The bar: a column of light with a shade over it, a foot band that runs and stops it, and three
 * pips at the top that are the way back to the whole app.
 *
 * **The draggable track is not the whole bar.** The foot band's 72dp are outside it, so the level a
 * finger means is measured against [barTrackHeightDp] — the part above the band — and the band's own
 * drags are offset into that same track by the height they sit below it.
 */
@Composable
private fun EdgeBar(
    dimLevel: Int,
    running: Boolean,
    barHeight: Dp,
    onDimLevel: (Int) -> Unit,
    onToggleRunning: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val colors = columnColors()
    val trackHeight = barTrackHeightDp(barHeight.value).dp
    val density = LocalDensity.current
    val trackPx = with(density) { trackHeight.toPx() }
    val slopPx = with(density) { BAR_TAP_SLOP_DP.dp.toPx() }
    val covered = trackHeight * (dimLevel.coerceIn(0, 100) / 100f)
    val openApp = stringResource(R.string.controls_open_app)

    ShadeColumn(
        level = dimLevel,
        width = BAR_WIDTH_DP.dp,
        height = barHeight,
        corner = BAR_RADIUS_DP.dp,
        handleWidth = BAR_HANDLE_WIDTH_DP.dp,
        trackHeight = trackHeight,
        colors = colors,
        // `requireUnconsumed`: the pip row and the foot band consume their own presses, and without
        // this the bar underneath would *also* read them as a level being set — a tap on Stop would
        // slam the dim level to 100 on its way through.
        modifier = Modifier.levelDrag(trackHeightPx = trackPx, onLevel = onDimLevel, requireUnconsumed = true),
    ) {
        BarValue(dimLevel = dimLevel, covered = covered, colors = colors)

        PipRow(
            covered = covered,
            colors = colors,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(BAR_PIP_ROW_DP.dp)
                    // Three dots have nothing for a screen reader to read, and this is the only
                    // route from the panel to the rest of the app — so the zone carries the name
                    // and the role the pips cannot.
                    .semantics {
                        contentDescription = openApp
                        role = Role.Button
                    }.tapOrDragZone(
                        trackHeightPx = trackPx,
                        topOffsetPx = 0f,
                        slopPx = slopPx,
                        onTap = onOpenApp,
                        onLevel = onDimLevel,
                    ),
        )

        FootBand(
            running = running,
            colors = colors,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(BAR_FOOT_HEIGHT_DP.dp)
                    .tapOrDragZone(
                        trackHeightPx = trackPx,
                        // The band begins where the track ends, so a finger at its top edge means
                        // "fully covered" and one dragged upward from it walks back down the track.
                        topOffsetPx = trackPx,
                        slopPx = slopPx,
                        onTap = onToggleRunning,
                        onLevel = onDimLevel,
                        onLongPress = onOpenApp,
                    ),
        )
    }
}

/**
 * The percentage, riding the shade edge — **never half-covered.**
 *
 * It sits above the edge in light ink once there is shade to sit on, and below it on the bare plate
 * in dark ink before that, so it needs no scrim and stays legible at every level. The threshold is
 * higher here than the bare arithmetic would need because the pip row owns the top 40dp: flipping at
 * 48dp would print the number over the pips.
 */
@Composable
private fun BoxScope.BarValue(
    dimLevel: Int,
    covered: Dp,
    colors: ColumnColors,
) {
    val density = LocalDensity.current
    val placement =
        with(density) {
            valuePlacement(
                coveredPx = covered.toPx(),
                flipPx = BAR_VALUE_FLIP_DP.dp.toPx(),
                abovePx = BAR_VALUE_ABOVE_DP.dp.toPx(),
                belowPx = BAR_VALUE_BELOW_DP.dp.toPx(),
            )
        }
    val ink = if (placement.onShade) colors.shadeInk else colors.plateInk

    Row(
        verticalAlignment = Alignment.Bottom,
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(x = 0, y = placement.topPx.roundToInt()) },
    ) {
        Text(
            text = stringResource(R.string.dim_level_number, dimLevel),
            style = MaterialTheme.typography.headlineSmall,
            color = ink,
        )
        Text(
            text = stringResource(R.string.dim_level_percent),
            style = MaterialTheme.typography.labelMedium,
            color = ink.copy(alpha = 0.75f),
        )
    }
}

/**
 * Three pips at the top of the bar: **the route to the full screen, inside the control rather than
 * beside it.**
 *
 * A third button would have been the obvious alternative and it is the wrong one — it grows the
 * group downward, away from the thumb, for something used once a session. The pips read as a handle,
 * which is what they are, and their ink flips across the shade edge the way the percentage does at
 * a lower weight, because they name a gesture rather than carrying a value.
 */
@Composable
private fun PipRow(
    covered: Dp,
    colors: ColumnColors,
    modifier: Modifier = Modifier,
) {
    val onShade = covered >= (BAR_PIP_ROW_DP / 2).dp
    val ink =
        if (onShade) colors.shadeInk.copy(alpha = 0.7f) else colors.plateInk.copy(alpha = 0.55f)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) {
                Box(modifier = Modifier.size(5.dp).background(ink, RoundedCornerShape(percent = 50)))
            }
        }
    }
}

/**
 * Run and stop, at the foot of the bar and **opaque at every dim level.**
 *
 * It is the one control here whose contrast cannot be allowed to follow the shade, because it is the
 * control that ends the shade. 72dp tall for the same reason: it is the target somebody finds
 * without looking.
 */
@Composable
private fun FootBand(
    running: Boolean,
    colors: ColumnColors,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(colors.foot), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.footBorder),
        )
        if (running) {
            Icon(
                painter = painterResource(R.drawable.ic_stop),
                contentDescription = stringResource(R.string.dim_stop),
                tint = colors.plateInk,
                modifier = Modifier.size(30.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.dim_start),
                tint = colors.plateInk,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

/**
 * The warmth column, opened to the left of the bar. Up is warmer — the opposite axis, on purpose.
 *
 * **Strength only; the colour is not here.** It is set once in the full app, and what somebody changes
 * while reading is how much tint, not which one. The column is painted in the chosen colour, so this
 * surface still shows it.
 */
@Composable
private fun WarmthSection(
    warmth: Int,
    warmthColor: Int,
    onWarmth: (Int) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.hair),
    ) {
        Text(
            text = stringResource(R.string.dim_level_number, warmth),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WarmthColumn(warmth = warmth, warmthColor = warmthColor, onWarmth = onWarmth)
    }
}

/**
 * The five auto-off values as a stack of pills, right-aligned beside the bar.
 *
 * **The chips do not close the section.** Staying open is what lets the selection redraw under the
 * finger that just tapped, which is the only confirmation this surface gives that anything happened.
 * A stack rather than a wrapped row: the window is 84dp of bar plus whatever a section is allowed,
 * and a row would either be clipped or be the thing that decides the window's width in a language
 * nobody tested.
 */
@Composable
private fun TimerSection(
    autoOff: AutoOff,
    onAutoOff: (AutoOff) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(Spacing.tight),
        modifier = Modifier.widthIn(max = BAR_SECTION_WIDTH_DP.dp),
    ) {
        for (choice in AutoOff.entries) {
            FilterChip(
                selected = autoOff == choice,
                onClick = { onAutoOff(choice) },
                label = { Text(stringResource(choice.shortLabelRes())) },
                shape = RoundedCornerShape(percent = 50),
                colors =
                    FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
            )
        }
    }
}

/**
 * Pause, or while paused a play glyph inside a ring of the time left, which a tap resumes from.
 *
 * Disabled rather than hidden while the shade is down: the group is centred, and a button that came
 * and went with the shade would move the bar under the thumb at every start and stop.
 */
private const val PAUSE_RING_DP = 40

@Composable
private fun PauseButton(
    running: Boolean,
    pause: PauseUi,
    onPause: (PauseLength) -> Unit,
    onResume: () -> Unit,
) {
    val end = pause.end
    if (end == null || !running) {
        CompactIconButton(onClick = { onPause(pause.length) }, selected = false, enabled = running) {
            Icon(
                painter = painterResource(R.drawable.ic_pause),
                contentDescription = stringResource(R.string.dim_pause, stringResource(pause.length.labelRes())),
            )
        }
        return
    }
    CompactIconButton(onClick = onResume, selected = true) {
        Box(contentAlignment = Alignment.Center) {
            pause.from?.let { from ->
                PauseRing(
                    from = from,
                    end = end.atMillis,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(PAUSE_RING_DP.dp),
                )
            }
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.dim_resume_now),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The round buttons under the bar.
 *
 * `OutlinedIconButton` rather than the filled one: filled is reserved for the control that changes
 * what the *screen* looks like, and on this surface that is the foot band. These open a section and
 * close it again, so they carry their state in their container the way a chip does, and nothing else
 * on the surface is competing for that reading.
 */
@Composable
private fun CompactIconButton(
    onClick: () -> Unit,
    selected: Boolean,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    OutlinedIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(BAR_ICON_BUTTON_DP.dp),
        shape = RoundedCornerShape(percent = 50),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors =
            IconButtonDefaults.outlinedIconButtonColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                contentColor =
                    if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
            ),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}
