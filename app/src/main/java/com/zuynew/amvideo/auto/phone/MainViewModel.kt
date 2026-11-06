package com.zuynew.amvideo.auto.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zuynew.amvideo.auto.data.Door
import com.zuynew.amvideo.auto.data.DoorRepository
import com.zuynew.amvideo.auto.data.DoorsResult
import com.zuynew.amvideo.auto.data.OpenResult
import com.zuynew.amvideo.auto.data.SignInResult
import com.zuynew.amvideo.auto.data.normalizePhone
import com.zuynew.amvideo.auto.R
import com.zuynew.amvideo.auto.ui.Notice
import com.zuynew.amvideo.auto.ui.toNotice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val signedIn: Boolean,
    val doors: List<Door> = emptyList(),
    val busy: Boolean = false,
    val pinRequired: Boolean = false,
    val openingDoorKey: String? = null,
    val arrivalEnabled: Boolean = false,
    /** Shown only when the user asks for it; it opens the doors on its own. */
    val sessionToken: String? = null,
    val notice: Notice? = null,
)

class MainViewModel(private val repository: DoorRepository) : ViewModel() {

    private val _state = MutableStateFlow(
        UiState(
            signedIn = repository.isSignedIn,
            doors = repository.cachedDoors(),
            arrivalEnabled = repository.arrivalEnabled,
            sessionToken = repository.sessionToken,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refreshIfSignedIn()
    }

    /**
     * Called whenever the phone UI comes back to the foreground. The car side
     * shares the repository and can lose the session while this screen sits in
     * the background, so even without a fetch the state is re-read.
     */
    fun refreshIfSignedIn() {
        if (repository.isSignedIn) refresh() else update { it }
    }

    fun signIn(phone: String, password: String, pin: String) {
        update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val result = repository.signIn(normalizePhone(phone), password, pin)
            update {
                it.copy(
                    busy = false,
                    // Sticky: a wrong PIN must not take the field away again.
                    pinRequired = it.pinRequired || result is SignInResult.PinRequired,
                    notice = result.toNotice(),
                )
            }
            if (result is SignInResult.Success) refresh()
        }
    }

    /** Nothing else delivers a PIN, so this is the only way out of a new device. */
    fun requestPin(phone: String) {
        if (_state.value.busy) return
        update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val result = repository.requestPin(normalizePhone(phone))
            update { it.copy(busy = false, notice = result.toNotice()) }
        }
    }

    /** Signs in with a session carried from another device, spending no login. */
    fun signInWithToken(token: String) {
        update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val result = repository.signInWithToken(token)
            update { it.copy(busy = false, notice = result.toNotice()) }
            if (result is SignInResult.Success) refresh()
        }
    }

    fun refresh() {
        update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = repository.refreshDoors()
            update { current ->
                current.copy(
                    busy = false,
                    doors = if (result is DoorsResult.Loaded) result.doors else current.doors,
                    // Re-login was refused as a new device, so the form will need
                    // the PIN field; showing it now saves a wasted attempt.
                    pinRequired = current.pinRequired || result is DoorsResult.DeviceNotTrusted,
                    // Loading the doors says nothing, so an unseen open result stands.
                    notice = result.toNotice() ?: current.notice,
                )
            }
        }
    }

    fun open(door: Door) {
        if (_state.value.openingDoorKey != null) return
        update { it.copy(openingDoorKey = door.key) }
        viewModelScope.launch {
            val result = repository.open(door)
            update {
                it.copy(
                    openingDoorKey = null,
                    pinRequired = it.pinRequired || result is OpenResult.DeviceNotTrusted,
                    notice = result.toNotice(),
                )
            }
        }
    }

    /**
     * @return true once it is on, so the caller can start the watcher — the
     *   service needs a Context, which does not belong in here.
     */
    fun setArrivalEnabled(enabled: Boolean): Boolean {
        repository.arrivalEnabled = enabled
        update { it.copy(arrivalEnabled = enabled) }
        return enabled
    }

    fun signOut() {
        repository.signOut()
        _state.value = UiState(signedIn = false)
    }

    fun noticeShown() {
        update { it.copy(notice = null) }
    }

    /**
     * Every state change goes through here so [UiState.signedIn] is always the
     * repository's own answer rather than a copy that has to be kept in step.
     */
    private fun update(block: (UiState) -> UiState) {
        _state.update {
            block(it).copy(
                signedIn = repository.isSignedIn,
                sessionToken = repository.sessionToken,
            )
        }
    }

    companion object {
        fun factory(repository: DoorRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(repository) }
        }
    }
}
