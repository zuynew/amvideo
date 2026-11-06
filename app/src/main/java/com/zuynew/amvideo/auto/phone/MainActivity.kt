package com.zuynew.amvideo.auto.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zuynew.amvideo.auto.OpenerApp
import com.zuynew.amvideo.auto.proximity.DoorProximityService

/**
 * The phone half of the app. It exists mainly so the driver can sign in, since
 * Android Auto will not show a sign-in form while the car can move — but it
 * opens doors too, which is handy when walking up to the gate.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repository = OpenerApp.repositoryOf(this)

        setContent {
            MaterialTheme {
                val viewModel: MainViewModel = viewModel(factory = MainViewModel.factory(repository))
                val state by viewModel.state.collectAsStateWithLifecycle()

                LifecycleResumeEffect(viewModel) {
                    viewModel.refreshIfSignedIn()
                    DoorProximityService.start(this@MainActivity)
                    onPauseOrDispose { }
                }

                if (state.signedIn) {
                    DoorsScreen(
                        state = state,
                        onArrivalEnabled = { enabled ->
                            if (viewModel.setArrivalEnabled(enabled)) {
                                DoorProximityService.start(this)
                            } else {
                                DoorProximityService.stop(this)
                            }
                        },
                        onOpen = viewModel::open,
                        onRefresh = viewModel::refresh,
                        onSignOut = viewModel::signOut,
                        onNoticeShown = viewModel::noticeShown,
                    )
                } else {
                    SignInScreen(
                        state = state,
                        onSignIn = viewModel::signIn,
                        onSignInWithToken = viewModel::signInWithToken,
                        onRequestPin = viewModel::requestPin,
                        onNoticeShown = viewModel::noticeShown,
                    )
                }
            }
        }
    }
}
