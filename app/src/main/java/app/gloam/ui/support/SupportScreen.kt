package app.gloam.ui.support

import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gloam.R
import app.gloam.theme.Spacing
import app.gloam.tip.TipOffer
import app.gloam.tip.TipOutcome
import app.gloam.ui.appViewModelExtras
import app.gloam.ui.common.DetailScaffold

/**
 * The route out of the app, for a tester who has found something.
 *
 * It exists at the door rather than in the polish phase for one reason: **a tester with no way to
 * report is a tester whose fourteen days produce nothing.** The rate-on-Play row is Phase 5's, which
 * is when there was a listing to rate. The tip is the last section, and only where Play will sell
 * one (ADR-0009).
 *
 * A detail screen pushed from Settings' *About* section, beside the licences row — not a third tab.
 * The bottom bar switches between roots, and support is not a root; it is somewhere you go once,
 * from the place you already go to look things up.
 *
 * The three hand-off rows hold no state beyond which app the last one found missing, which belongs
 * to this composition. The `ViewModel` is the tip's: Play's prices and what the last tip became.
 */
@Composable
fun SupportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SupportViewModel =
        viewModel(factory = SupportViewModel.Factory, extras = appViewModelExtras()),
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Not `rememberSaveable`: this says "the tap you just made went nowhere", which is only true of
    // the tap you just made. A rotation clears it, and the next tap says it again if it is still so.
    // Kotlin note: `mutableStateOf<Int?>(null)` is `useState<number | null>(null)`: the id of the
    // message to show, or null for none. One slot rather than a Boolean per row, because only the
    // most recent tap is worth reporting.
    var missingApp by remember { mutableStateOf<Int?>(null) }

    // Assigned from every tap, whatever it returned, so a hand-off that works clears the message
    // an earlier one left behind.
    fun reportHandOff(
        opened: Boolean,
        @StringRes missing: Int,
    ) {
        missingApp = if (opened) null else missing
    }

    DetailScaffold(
        title = stringResource(R.string.support_title),
        onBack = onBack,
        modifier = modifier,
    ) { contentModifier ->
        Column(
            modifier =
                contentModifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
        ) {
            // The two requests this screen owns, named rather than `entries`: the third,
            // `SupportRequest.Language`, is a row in Settings' *Language* section instead, for the
            // reason its own doc comment gives. Its label still comes from the `when` blocks at the
            // bottom of this file — that is the one place a request's wording lives, and Settings
            // reads it from there rather than keeping a second copy.
            for (request in listOf(SupportRequest.Bug, SupportRequest.Feature)) {
                SupportRow(
                    title = stringResource(request.titleRes()),
                    hint = stringResource(request.hintRes()),
                    onClick = {
                        reportHandOff(context.sendSupportMail(request), R.string.support_no_mail_app)
                    },
                )
            }

            SupportRow(
                title = stringResource(R.string.support_rate),
                hint = stringResource(R.string.support_rate_hint),
                onClick = { reportHandOff(context.rateOnPlay(), R.string.support_no_play) },
            )

            // Inline rather than a snackbar: the app has no snackbar host anywhere, and the failure
            // is about the row that was just tapped rather than about the screen. Stated as what is
            // missing on the phone — the app is not broken and neither is the address.
            // Kotlin note: `?.let { }` runs only when the value is not null, the way
            // `{missingApp != null && <Text …/>}` does in JSX.
            missingApp?.let { message ->
                Text(
                    text = stringResource(message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = Spacing.base, vertical = Spacing.snug),
                )
            }

            if (state.offers.isNotEmpty() && activity != null) {
                TipSection(
                    offers = state.offers,
                    outcome = state.outcome,
                    onTip = { offer -> viewModel.tip(activity, offer) },
                )
            }
        }
    }
}

/**
 * The prices are Play's strings, in the user's own currency, so there is no label to translate on
 * the buttons — and nothing here says what a tip buys, because it buys nothing.
 */
@Composable
private fun TipSection(
    offers: List<TipOffer>,
    outcome: TipOutcome?,
    onTip: (TipOffer) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.base, vertical = Spacing.snug),
    ) {
        Text(stringResource(R.string.support_tip), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(R.string.support_tip_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Wraps rather than scrolls: four prices in a long currency format do not fit one row.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.tight),
            modifier = Modifier.padding(top = Spacing.tight),
        ) {
            for (offer in offers) {
                OutlinedButton(onClick = { onTip(offer) }) { Text(offer.price) }
            }
        }
        outcome?.let {
            Text(
                text =
                    stringResource(
                        when (it) {
                            TipOutcome.Thanked -> R.string.support_tip_thanks
                            TipOutcome.Pending -> R.string.support_tip_pending
                        },
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = Spacing.tight),
            )
        }
    }
}

/**
 * A tappable row: what it does, and what will happen if you tap it.
 *
 * The hint is not decoration. Every row here leaves Gloam for another app, and a row that opens
 * somebody's mail composer with no warning is the kind of thing a user backs out of and never taps
 * again.
 */
@Composable
private fun SupportRow(
    title: String,
    hint: String,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.base, vertical = Spacing.snug),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Kotlin note: extensions on the enum rather than two more fields in it, the same split
 * `ThemeMode.labelRes()` makes. [SupportRequest] carries what the *mail* is made of and has no Android
 * UI in it; what a row is labelled is the UI layer's business.
 *
 * `internal` rather than `private` because one of the three rows is not on this screen: Settings'
 * *Language* section renders [SupportRequest.Language] and reads its wording from here. The
 * alternative was a second pair of `stringResource` calls over there, which is the shape that lets a
 * row and its mail drift apart.
 */
internal fun SupportRequest.titleRes(): Int =
    when (this) {
        SupportRequest.Bug -> R.string.support_report
        SupportRequest.Feature -> R.string.support_feature
        SupportRequest.Language -> R.string.settings_language_report
    }

internal fun SupportRequest.hintRes(): Int =
    when (this) {
        SupportRequest.Bug -> R.string.support_report_hint
        SupportRequest.Feature -> R.string.support_feature_hint
        SupportRequest.Language -> R.string.settings_language_report_hint
    }
