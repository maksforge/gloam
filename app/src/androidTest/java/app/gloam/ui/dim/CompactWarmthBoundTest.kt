package app.gloam.ui.dim

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gloam.R
import app.gloam.shade.AutoOff
import app.gloam.theme.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The warmth section's height bound, read off a real layout: the label row's height depends on the
 * font scale, so only a measure pass can say whether the section still fits beside the bar.
 */
@RunWith(AndroidJUnit4::class)
class CompactWarmthBoundTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theCloseButtonStaysWholeOnTheSurfaceWithWarmthOpenBesideAFloorBarAtDoubleFontScale() {
        // The bar at its floor with the warmth button and the close under it, and nothing to spare:
        // the shortest surface that still draws both.
        val surfaceHeight = compactStackHeightDp(BAR_MIN_HEIGHT_DP, buttons = 2)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                AppTheme {
                    Box(modifier = Modifier.size(SURFACE_WIDTH_DP.dp, surfaceHeight.dp).testTag(SURFACE)) {
                        CompactControls(
                            dimLevel = 50,
                            warmth = 50,
                            warmthColor = 50,
                            running = true,
                            autoOff = AutoOff.Default,
                            pause = PauseUi(),
                            onDimLevel = {},
                            onWarmth = {},
                            onAutoOff = {},
                            onToggleRunning = {},
                            onPause = {},
                            onResume = {},
                            onOpenApp = {},
                            modifier = Modifier.fillMaxSize(),
                            onClose = {},
                        )
                    }
                }
            }
        }

        val warmthLabel = context.getString(R.string.dim_warmth_label)
        compose.onNodeWithContentDescription(warmthLabel).performClick()

        val surface = compose.onNodeWithTag(SURFACE).getUnclippedBoundsInRoot()
        val close =
            compose
                .onNodeWithContentDescription(
                    context.getString(R.string.panel_close),
                ).getUnclippedBoundsInRoot()
        val column =
            compose
                .onNode(
                    hasContentDescription(warmthLabel) and
                        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
                ).getUnclippedBoundsInRoot()

        // Compose squeezes the last child of an overfull column rather than pushing it out, so the
        // close is checked for its whole height as well as for its position.
        assertTrue(
            "The close button ends at ${close.bottom}, below the surface's ${surface.bottom}",
            close.bottom <= surface.bottom + TOLERANCE,
        )
        assertTrue(
            "The close button is ${close.bottom - close.top} tall",
            close.bottom - close.top >= (BAR_ICON_BUTTON_DP.dp - TOLERANCE),
        )
        assertTrue(
            "The warmth column is ${column.bottom - column.top} tall, under the ${WARMTH_COLUMN_MIN_HEIGHT_DP}dp floor",
            column.bottom - column.top >= (WARMTH_COLUMN_MIN_HEIGHT_DP.dp - TOLERANCE),
        )
    }

    private companion object {
        const val SURFACE = "surface"
        const val SURFACE_WIDTH_DP = 360
        val TOLERANCE = 0.5.dp
    }
}
