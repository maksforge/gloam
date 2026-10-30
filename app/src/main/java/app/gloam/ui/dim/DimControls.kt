package app.gloam.ui.dim

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gloam.R
import app.gloam.data.SavedPreset
import app.gloam.shade.AutoOff
import app.gloam.shade.Schedule
import app.gloam.shade.ScheduleKind
import app.gloam.shade.warmthTint
import app.gloam.theme.Spacing
import app.gloam.ui.common.SwitchRow
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import kotlin.math.roundToInt

/**
 * The dim level, the warmth and the start/stop button — **state in, callbacks out, and no
 * `Context`**.
 *
 * ## What the redesign changed, and what it did not
 *
 * The two M3 sliders are gone. The dim level is now [DimColumn] — a picture of the light with a
 * shade over it — and warmth stays a slider precisely so it cannot be mistaken for a second column.
 * The rule that made this file worth extracting has not moved: a composable that takes only values
 * and lambdas can be rendered by **any** host, and one that reads `LocalActivity` or asks for a
 * `ViewModel` can only be rendered where those exist — which the panel's raw `WindowManager` window
 * is not. So no service is started here and no permission is asked here.
 *
 * **The backlight switch left this composable**, and that is the redesign's one real loss. The
 * column eats the space two sliders and a hint used to spend, so the switch and its explanation now
 * live inside [AutoOffCard], which opens by default but can be closed. Somebody who closes it and
 * never opens it again does not read why their brightness slider is inert; the shade's own
 * notification still says so, which is what bounds the loss.
 *
 * **Kotlin note for a JS reader:** props-down / events-up, and nothing else. Compose's version of
 * "where may a side effect live" is stricter than React's: a `CompositionLocal` is context, and
 * reading one the host never provided is an exception at composition time rather than `undefined`.
 */
@Composable
fun DimControls(
    dimLevel: Int,
    warmth: Int,
    warmthColor: Int,
    running: Boolean,
    pause: PauseUi,
    onDimLevel: (Int) -> Unit,
    onWarmth: (Int) -> Unit,
    onWarmthColor: (Int) -> Unit,
    onToggleRunning: () -> Unit,
    savedPresets: List<SavedPreset>,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onPreset: (Preset) -> Unit,
    onSavedPreset: (SavedPreset) -> Unit,
    onSavePreset: (name: String) -> Unit,
    onRenamePreset: (from: String, to: String) -> Unit,
    onDeletePreset: (name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.base),
            // Bottom-aligned rather than centred: the column is a fixed 326dp and the text beside it
            // is not, so aligning their *tops* would leave the caption floating halfway up a column
            // whose bottom is the thing it is describing.
            verticalAlignment = Alignment.Bottom,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.base, end = Spacing.base, top = Spacing.section),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.hair),
                // `weight` takes the room the column does not, and `widthIn(min = 0.dp)` is what
                // lets it actually shrink — without it a long translated caption pushes the column
                // off the right edge instead of wrapping.
                modifier = Modifier.weight(1f).widthIn(min = 0.dp),
            ) {
                Text(
                    text = stringResource(R.string.dim_level_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                DimLevelValue(dimLevel)
                Text(
                    text = stringResource(R.string.dim_level_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
            }

            DimColumn(dimLevel = dimLevel, onDimLevel = onDimLevel)
        }

        WarmthRow(
            warmth = warmth,
            warmthColor = warmthColor,
            onWarmth = onWarmth,
            onWarmthColor = onWarmthColor,
            modifier = Modifier.padding(horizontal = Spacing.base, vertical = Spacing.base),
        )

        PresetRow(
            dimLevel = dimLevel,
            warmth = warmth,
            warmthColor = warmthColor,
            savedPresets = savedPresets,
            onPreset = onPreset,
            onSavedPreset = onSavedPreset,
            onSavePreset = onSavePreset,
            onRenamePreset = onRenamePreset,
            onDeletePreset = onDeletePreset,
            modifier = Modifier.padding(start = Spacing.base, end = Spacing.base, bottom = Spacing.base),
        )

        RunButton(
            running = running,
            onToggleRunning = onToggleRunning,
            modifier = Modifier.padding(horizontal = Spacing.base),
        )

        PauseRow(
            running = running,
            pause = pause,
            onPause = onPause,
            onResume = onResume,
            modifier = Modifier.padding(start = Spacing.base, end = Spacing.base, top = Spacing.tight),
        )
    }
}

/**
 * The number, and the per-cent sign beside it at a third of the size.
 *
 * Two `Text`s rather than one, because they are two type sizes; `alignByBaseline` is what keeps them
 * sitting on the same line rather than on their own boxes' bottoms. Both strings are
 * `translatable="false"`: one is a bare integer placeholder and the other is a symbol, and neither
 * has anything for a translator to decide. The consequence, recorded rather than discovered later:
 * a locale that writes the sign *before* the number would need this composable, not a translation.
 */
@Composable
private fun DimLevelValue(dimLevel: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = stringResource(R.string.dim_level_number, dimLevel),
            style =
                MaterialTheme.typography.displayLarge.copy(
                    fontSize = 86.sp,
                    lineHeight = 78.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = (-3).sp,
                ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.alignByBaseline(),
        )
        Text(
            text = stringResource(R.string.dim_level_percent),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Light),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

/**
 * Warmth and its colour: a label and a ramp each, and the warmth's number — **and both stay sliders on
 * purpose.**
 *
 * Two columns side by side would be two controls that look like the same control, and the one thing
 * a user must never do by accident in the dark is set the wrong one. A horizontal track is a
 * different gesture with a different picture.
 *
 * **Three columns of cells rather than a `Row` per bar**, so the two tracks start and end on the same
 * pixels however long either label runs in a translation. A `Row` each would start each track wherever
 * its own label happened to end. Every cell is the tracks' hit height, which is what keeps each label
 * level with its track.
 *
 * **Both ramps paint the shade's own tint**, not the app's accent. The warmth track runs from nothing
 * to the chosen tint, and the colour track is the tint's whole path. That makes them the same kind of
 * exception `ColumnColors` is: a picture of what the shade does, which the palette does not own.
 */
@Composable
fun WarmthRow(
    warmth: Int,
    warmthColor: Int,
    onWarmth: (Int) -> Unit,
    onWarmthColor: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = columnColors()
    val warmthLabel = stringResource(R.string.dim_warmth_label)
    val colorLabel = stringResource(R.string.dim_warmth_color_label)

    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.snug),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            RampLabel(warmthLabel)
            RampLabel(colorLabel)
        }
        Column(modifier = Modifier.weight(1f)) {
            RampTrack(
                value = warmth,
                onValue = onWarmth,
                label = warmthLabel,
                brush = Brush.horizontalGradient(listOf(colors.warmthCool, Color(warmthTint(warmthColor)))),
            )
            RampTrack(
                value = warmthColor,
                onValue = onWarmthColor,
                label = colorLabel,
                brush = Brush.horizontalGradient(COLOR_TRACK_STOPS),
            )
        }
        // The colour has no number: 0–100 along a hue says nothing a reader can use, and the track
        // under the thumb already shows the answer.
        Box(contentAlignment = Alignment.CenterEnd, modifier = Modifier.height(TRACK_HIT_HEIGHT)) {
            Text(
                text = stringResource(R.string.dim_level_number, warmth),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(28.dp),
            )
        }
    }
}

/** A ramp's label, in a cell as tall as the ramp's hit area so the two stay level. */
@Composable
private fun RampLabel(text: String) {
    Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.height(TRACK_HIT_HEIGHT)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The tracks' touch height; the painted line inside is 10dp. */
private val TRACK_HIT_HEIGHT = 44.dp

/**
 * The colour track's paint: five samples of `warmthTint` along its path. The gradient between two
 * samples blends stored bytes rather than light, so it drifts from the real path by a byte or two in
 * between, which is too little to see on a 10dp line.
 */
private val COLOR_TRACK_STOPS = (0..4).map { Color(warmthTint(it * 25)) }

/**
 * One ramp: a painted track, and a bar for a thumb. Warmth and its colour each draw one.
 *
 * **The 10dp track sits inside a 44dp hit area.** What the finger has to find is the whole row, not
 * the line — the same reason `SwitchRow` makes the row the target rather than the switch.
 *
 * The width has to be measured rather than known: the track is `weight(1f)` between a label and a
 * readout, so nothing in the composition can say how many pixels it got until it is laid out.
 * `onSizeChanged` reports that, and it is also the gesture's key — a stale width would keep
 * converting a finger's x with the old number after a rotation.
 *
 * Kotlin note: `mutableFloatStateOf` rather than `mutableStateOf(0f)` — the specialised one avoids
 * boxing a `Float` into an object on every layout pass. Same semantics, one fewer allocation.
 */
@Composable
private fun RampTrack(
    value: Int,
    onValue: (Int) -> Unit,
    label: String,
    brush: Brush,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableFloatStateOf(0f) }

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier =
            modifier
                .height(TRACK_HIT_HEIGHT)
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(widthPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        onValue(alongTrack(down.position.x, widthPx))
                        drag(down.id) { change ->
                            change.consume()
                            onValue(alongTrack(change.position.x, widthPx))
                        }
                    }
                }.semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..100f, steps = 99)
                    setProgress { target ->
                        onValue(target.roundToInt().coerceIn(0, 100))
                        true
                    }
                },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(brush),
        )
        Box(
            modifier =
                Modifier
                    // Half the thumb's own width back, so it is centred on the value rather than
                    // starting at it. Inside `offset`'s lambda the receiver is a `Density`, which is
                    // what makes a dp convertible to pixels here without a `LocalDensity` read.
                    .offset { IntOffset(x = handleX(value, widthPx) - 2.dp.roundToPx(), y = 0) }
                    .size(4.dp, 36.dp)
                    .background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(2.dp)),
        )
    }
}

/** Where along the track a finger at [x] is asking for. Clamped, so the ends are reachable. */
private fun alongTrack(
    x: Float,
    widthPx: Float,
): Int {
    if (widthPx <= 0f || !x.isFinite()) return 0
    return ((x / widthPx) * 100f).roundToInt().coerceIn(0, 100)
}

/** The thumb's left edge, pulled back by half its own width so it is centred on the value. */
private fun handleX(
    value: Int,
    widthPx: Float,
): Int = (widthPx * value.coerceIn(0, 100) / 100f).roundToInt()

/**
 * The one control whose tap changes what the *screen* looks like rather than what this window shows,
 * so it is the one filled thing on the surface.
 */
@Composable
fun RunButton(
    running: Boolean,
    onToggleRunning: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onToggleRunning,
        shape = RoundedCornerShape(percent = 50),
        modifier = modifier.fillMaxWidth().height(60.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                Icon(painter = painterResource(R.drawable.ic_stop), contentDescription = null)
            } else {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
            }
            // The label is the button's own name, so the icon beside it takes no description — a
            // screen reader would otherwise say "stop, Stop dimming".
            Text(
                text = stringResource(if (running) R.string.dim_stop else R.string.dim_start),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/**
 * **Everything the screen is not about, in one card that opens by default.**
 *
 * Three rows stacked: when the shade turns itself off, whether Gloam may take the backlight down
 * with it, and what the schedule currently says. They are together because they are all *when and
 * whether*, against a hero that is *how much* — and the card is what lets that whole question fold
 * away on a screen whose primary control is now 326dp tall.
 *
 * The schedule row is deliberately outside the collapsible half: it is the one line that reports a
 * setting made on another screen, and somebody who set a schedule three weeks ago and forgot is by
 * definition not opening a card to look for it.
 *
 * Kotlin note: `rememberSaveable` rather than `remember` for the expansion. `remember` survives
 * recomposition; `rememberSaveable` also survives the Activity being recreated — a rotation, or the
 * theme switch this release makes more likely — by writing the boolean into the saved-state bundle.
 */
@Composable
fun AutoOffCard(
    autoOff: AutoOff,
    offAtMillis: Long?,
    running: Boolean,
    onAutoOff: (AutoOff) -> Unit,
    lowerBacklight: Boolean,
    backlightAvailable: Boolean,
    onLowerBacklight: (Boolean) -> Unit,
    schedule: Schedule,
    scheduleAtRisk: Boolean,
    onOpenSchedule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
        // A 1dp outline rather than tone alone: on the near-black ground of the dark scheme a
        // container is barely a step off the background, and the card would otherwise read as an
        // area of the screen where the text happens to be indented.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(horizontal = Spacing.base, vertical = Spacing.base),
            ) {
                Text(
                    text = stringResource(R.string.dim_auto_off_label),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(autoOff.shortLabelRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = Spacing.tight),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    // The row's own label names it; the chevron only says which way it will go.
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
                        verticalArrangement = Arrangement.spacedBy(Spacing.tight),
                        modifier = Modifier.padding(horizontal = Spacing.base),
                    ) {
                        // `FlowRow` rather than `Row`: five chips do not fit one line on a narrow
                        // screen in every language, and a clipped safety control is worse than a
                        // wrapped one.
                        for (choice in AutoOff.entries) {
                            FilterChip(
                                selected = autoOff == choice,
                                onClick = { onAutoOff(choice) },
                                label = { Text(stringResource(choice.shortLabelRes())) },
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.dim_auto_off_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.base, vertical = Spacing.snug),
                    )
                    // Only with a shade up and a deadline on it — `Never` has nothing to say here,
                    // and a stopped shade's deadline went with the intent that owned it.
                    if (running && offAtMillis != null) {
                        Text(
                            text = stringResource(R.string.dim_auto_off_at, rememberTimeText(offAtMillis)),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier =
                                Modifier.padding(horizontal = Spacing.base, vertical = Spacing.tight),
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Visible and *disabled* rather than hidden where the device cannot honour it:
                    // a control that is simply absent leaves the user unable to tell Gloam from a
                    // Gloam that behaves differently on their phone than on someone else's.
                    SwitchRow(
                        title = stringResource(R.string.dim_backlight_label),
                        subtitle =
                            stringResource(
                                if (backlightAvailable) {
                                    R.string.dim_backlight_hint
                                } else {
                                    R.string.dim_backlight_unavailable
                                },
                            ),
                        checked = lowerBacklight,
                        onChange = onLowerBacklight,
                        enabled = backlightAvailable,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            ScheduleRow(
                schedule = schedule,
                atRisk = scheduleAtRisk,
                onClick = onOpenSchedule,
            )
        }
    }
}

/**
 * The one line the dim screen gives the schedule, and the way to the screen that owns it.
 *
 * A trailing chevron rather than a switch: this row *reports*, and everything that changes a
 * schedule is two time pickers and a toggle that would not fit here in any language.
 */
@Composable
private fun ScheduleRow(
    schedule: Schedule,
    atRisk: Boolean,
    onClick: () -> Unit,
) {
    val summary = rememberScheduleSummary(schedule, atRisk)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.base, vertical = Spacing.base),
    ) {
        // Null when the text is a whole sentence that names the schedule itself — the at-risk line —
        // because a row drawing both would say "Schedule" twice.
        if (summary.title != null) {
            Text(
                text = summary.title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = summary.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = Spacing.tight),
            )
        } else {
            Text(
                text = summary.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            // Null rather than a description: the row's own text is its name, and a screen reader
            // announcing "chevron" after it adds nothing a user can act on.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A clock time rather than a countdown, **and that is a saving rather than a compromise.**
 *
 * A countdown has to tick, which is a recomposition every minute for the length of a session, and it
 * has to be formatted into words that plural correctly in every locale. A time does neither: it is
 * one string with one argument, and it does not change, so nothing ticks.
 *
 * Its one weakness, recorded so nobody re-discovers it as a bug: four hours from 23:00 reads as
 * "03:00" with no date. At a four-hour ceiling that is unambiguous enough in context.
 *
 * `internal` rather than private because the compact controls show the same deadline in their timer
 * section, and a second copy of this would be a second place for a locale bug to live.
 *
 * `DateFormat.getTimeFormat` is the platform's, so 12- or 24-hour follows the phone's own setting
 * and the locale for free — never a hand-built format string (`translator-brief.md` §4).
 */
@Composable
internal fun rememberTimeText(instant: Long): String {
    val context = LocalContext.current
    return remember(context, instant) {
        android.text.format.DateFormat
            .getTimeFormat(context)
            .format(Date(instant))
    }
}

/**
 * The same formatter, for a wall-clock time the **user set** rather than an instant the app
 * computed.
 *
 * A `LocalTime` has no date and no zone, and `DateFormat.getTimeFormat` formats a `Date` — so it
 * gets there by being put on today's date, which is the one line of glue between the two and lives
 * here rather than in each of the three callers. One weakness, recorded rather than re-discovered:
 * on the one night a year the clock springs forward, a time inside the missing hour resolves
 * forward, so a schedule set to 02:30 reads as 03:30 that day. That is also exactly when it will
 * come on (`shade/Schedule.kt`), so the reading is honest on the night and merely odd in the
 * daylight before it.
 */
@Composable
internal fun rememberTimeText(time: LocalTime): String =
    rememberTimeText(
        remember(time) {
            LocalDate
                .now()
                .atTime(time)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        },
    )

/**
 * The schedule as one line, for the two surfaces that only *report* it.
 *
 * **It states the window rather than the setting** — *22:00 to 07:00*, not *On* — because the person
 * this line exists for is the one who set a schedule three weeks ago and forgot, and the window is
 * what they need to recognise. And when the battery exemption is missing it says *that* instead:
 * that user is by definition not opening the schedule screen where the banner lives, so the honest
 * state has to appear where they do go (`docs/phase-4.md` §10).
 *
 * @param title the row's own label, or `null` when [text] is a whole sentence that names the
 *   schedule itself. A caller that drew both would say "Schedule" twice.
 */
internal data class ScheduleSummary(
    val title: String?,
    val text: String,
)

@Composable
internal fun rememberScheduleSummary(
    schedule: Schedule,
    atRisk: Boolean,
): ScheduleSummary =
    when {
        schedule.enabled && atRisk ->
            ScheduleSummary(title = null, text = stringResource(R.string.dim_schedule_at_risk))
        // The kind rather than tonight's two times (ADR-0013's consequences). Sunset moves every day,
        // so times here would be a different line each evening for a setting that has not changed, and
        // the person who forgot recognises "Sunset to sunrise" faster than "19:04 to 06:21".
        schedule.enabled && schedule.kind == ScheduleKind.SunsetToSunrise ->
            ScheduleSummary(
                title = stringResource(R.string.dim_schedule_row),
                text = stringResource(R.string.schedule_kind_sun),
            )
        schedule.enabled ->
            ScheduleSummary(
                title = stringResource(R.string.dim_schedule_row),
                text =
                    stringResource(
                        R.string.dim_schedule_window,
                        rememberTimeText(schedule.onAt),
                        rememberTimeText(schedule.offAt),
                    ),
            )
        else ->
            ScheduleSummary(
                title = stringResource(R.string.dim_schedule_row),
                text = stringResource(R.string.dim_schedule_off),
            )
    }

/**
 * The auto-off labels — **one set, everywhere.**
 *
 * There used to be two: long ones for the full screen ("After 30 minutes") and short ones for the
 * compact surfaces. The redesign puts the same five chips in a card the width of a phone and in a
 * stack beside an 84dp bar, and the short labels fit both; a second set would be a second place for
 * a value added to [AutoOff] to be forgotten.
 *
 * Kotlin note: an extension on the enum rather than a field in it. The resource ids belong to the UI
 * layer and `AutoOff` has no Android in it at all — which is what lets `AutoOffTest` run on the JVM.
 */
internal fun AutoOff.shortLabelRes(): Int =
    when (this) {
        AutoOff.Never -> R.string.compact_auto_off_never
        AutoOff.Minutes30 -> R.string.compact_auto_off_30m
        AutoOff.Hour1 -> R.string.compact_auto_off_1h
        AutoOff.Hours2 -> R.string.compact_auto_off_2h
        AutoOff.Hours4 -> R.string.compact_auto_off_4h
    }
