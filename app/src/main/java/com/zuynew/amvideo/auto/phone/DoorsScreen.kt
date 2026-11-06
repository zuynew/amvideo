package com.zuynew.amvideo.auto.phone

import android.annotation.SuppressLint
import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zuynew.amvideo.auto.R
import com.zuynew.amvideo.auto.data.Door
import com.zuynew.amvideo.auto.proximity.DoorProximityService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoorsScreen(
    state: UiState,
    onArrivalEnabled: (Boolean) -> Unit,
    onOpen: (Door) -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    onNoticeShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    ShowNotice(state.notice, snackbarHostState, onNoticeShown)

    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.doors_title)) },
                actions = {
                    TextButton(onClick = onRefresh, enabled = !state.busy) {
                        Text(stringResource(R.string.refresh_action))
                    }
                    TextButton(onClick = { confirmSignOut = true }) {
                        Text(stringResource(R.string.sign_out_action))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { insets ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
        ) {
            when {
                state.doors.isNotEmpty() -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    arrivalPermissionCard()
                    item { ArrivalSwitch(state.arrivalEnabled, onArrivalEnabled) }
                    item { SessionTokenCard(state.sessionToken) }

                    items(state.doors, key = { it.key }) { door ->
                        DoorCard(
                            door = door,
                            opening = door.key == state.openingDoorKey,
                            enabled = state.openingDoorKey == null,
                            onOpen = { onOpen(door) },
                        )
                    }
                }

                state.busy -> BusyIndicator(Modifier.align(Alignment.Center))

                else -> Text(
                    text = stringResource(R.string.no_doors),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }

    // Signing out drops the stored credentials, and there is no way to sign back
    // in from the car — worth one tap of confirmation.
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.sign_out_confirm_title)) },
            text = { Text(stringResource(R.string.sign_out_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        onSignOut()
                    },
                ) {
                    Text(stringResource(R.string.sign_out_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) {
                    Text(stringResource(R.string.cancel_action))
                }
            },
        )
    }
}

private fun LazyListScope.arrivalPermissionCard() = item { ArrivalPermissionCard() }

/**
 * The off switch. The watcher holds a standing notification for as long as it is
 * armed, so leaving the driver no way to turn it off would not be acceptable.
 */
@Composable
private fun ArrivalSwitch(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    if (!hasArrivalPermissions(context)) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.arrival_switch),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.arrival_switch_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = enabled, onCheckedChange = onChange)
        }
    }
}

/**
 * Asks for what the arrival prompt needs, which the driver can only grant here:
 * Android Auto will not put a permission dialog in front of someone driving.
 * It disappears for good once both halves are granted.
 */
@Composable
private fun ArrivalPermissionCard() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasArrivalPermissions(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = hasArrivalPermissions(context) }

    if (granted) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.permission_location_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.permission_location_text),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { launcher.launch(arrivalPermissions()) }) {
                Text(stringResource(R.string.permission_grant))
            }
        }
    }
}

/**
 * Both halves are needed: location to notice the arrival, notifications to tell
 * the driver about it. Granting one without the other leaves the feature dead, so
 * the card stays until both are in.
 */
private fun hasArrivalPermissions(context: Context): Boolean =
    DoorProximityService.hasLocationPermission(context) &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED)

private fun arrivalPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

/**
 * Lets the session be carried to another device by hand.
 *
 * Worth the screen space because the server caps logins per day and keeps only
 * one session per account: pasting this into a second device is the only way to
 * run both without each one evicting the other.
 *
 * Hidden until asked for, and copied with the sensitive flag set, so it does not
 * sit in the clipboard preview — it opens the doors just as the password does.
 */
@Composable
private fun SessionTokenCard(token: String?) {
    if (token.isNullOrEmpty()) return
    val context = LocalContext.current
    var revealed by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.token_card_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (revealed) token else stringResource(R.string.token_hidden),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { revealed = !revealed }) {
                    Text(
                        stringResource(
                            if (revealed) R.string.password_hide else R.string.password_show,
                        ),
                    )
                }
                TextButton(onClick = { context.copySessionToken(token) }) {
                    Text(stringResource(R.string.token_copy))
                }
            }
        }
    }
}

// EXTRA_IS_SENSITIVE is API 33, but it is an inlined string key that older
// clipboards simply ignore, so no runtime branch is needed.
@SuppressLint("InlinedApi")
private fun Context.copySessionToken(token: String) {
    val clip = ClipData.newPlainText("", token).apply {
        description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    getSystemService<ClipboardManager>()?.setPrimaryClip(clip)
}

@Composable
private fun DoorCard(door: Door, opening: Boolean, enabled: Boolean, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = door.address,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
            )
            Button(onClick = onOpen, enabled = enabled) {
                if (opening) {
                    BusyIndicator()
                } else {
                    Text(stringResource(R.string.open_action))
                }
            }
        }
    }
}
