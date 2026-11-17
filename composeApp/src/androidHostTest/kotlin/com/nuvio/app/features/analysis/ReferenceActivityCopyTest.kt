package com.nuvio.app.features.analysis

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.nuvio.app.core.ui.NuvioTheme
import org.jetbrains.compose.resources.stringResource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.analysis_activity_reference_compare
import nuvio.composeapp.generated.resources.analysis_activity_reference_fetch
import nuvio.composeapp.generated.resources.analysis_activity_reference_read

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReferenceActivityCopyTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun referenceStatusUsesTheStringResources() {
        compose.setContent {
            NuvioTheme {
                Column {
                    Text(referenceActivityLabel(ReferenceActivity("read", 40, 3, 4)))
                    Text(referenceActivityLabel(ReferenceActivity("compare", 40, 3, 4)))
                    Text(referenceActivityLabel(ReferenceActivity("fetch", 40, 3, 4)))
                    Text(stringResource(Res.string.analysis_activity_reference_read, 40, 3, 4))
                    Text(stringResource(Res.string.analysis_activity_reference_compare, 40, 3, 4))
                    Text(stringResource(Res.string.analysis_activity_reference_fetch, 40, 3, 4))
                }
            }
        }
        compose.onAllNodesWithText("Reading the downloaded episode, 40s of S3 E4").assertCountEquals(2)
        compose.onAllNodesWithText("Comparing 40s of S3 E4").assertCountEquals(2)
        compose.onAllNodesWithText("Fetching 40s of S3 E4").assertCountEquals(2)
    }
}
