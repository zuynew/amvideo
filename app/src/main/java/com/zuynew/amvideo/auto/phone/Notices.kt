package com.zuynew.amvideo.auto.phone

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.zuynew.amvideo.auto.ui.Notice
import com.zuynew.amvideo.auto.ui.resolve
import kotlinx.coroutines.launch

/** Shows [notice] once, then tells the view model it has been seen. */
@Composable
fun ShowNotice(notice: Notice?, hostState: SnackbarHostState, onShown: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(notice) {
        val text = notice?.resolve(context) ?: return@LaunchedEffect
        // Consumed before it is shown, because a notice still pending when the
        // activity is recreated would otherwise be shown a second time. The
        // snackbar itself runs outside this effect, which the consumption
        // immediately restarts.
        onShown()
        scope.launch {
            hostState.showSnackbar(
                message = text,
                duration = if (notice.isError) SnackbarDuration.Long else SnackbarDuration.Short,
            )
        }
    }
}
