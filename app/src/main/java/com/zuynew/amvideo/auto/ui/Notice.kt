package com.zuynew.amvideo.auto.ui

import android.content.Context
import androidx.annotation.StringRes
import com.zuynew.amvideo.auto.R
import com.zuynew.amvideo.auto.data.DoorsResult
import com.zuynew.amvideo.auto.data.OpenResult
import com.zuynew.amvideo.auto.data.PinRequestResult
import com.zuynew.amvideo.auto.data.SignInResult

/**
 * Something to tell the driver, held as data rather than text so that view models
 * need no [Context] and the car and the phone word the same outcome the same way.
 *
 * [isError] drives how it is shown — in red and lingering, or in passing.
 */
sealed interface Notice {

    val isError: Boolean

    /** Wording the server supplied, already in Russian. */
    data class FromServer(val text: String, override val isError: Boolean = true) : Notice

    data class Local(
        @StringRes val id: Int,
        val args: List<Any> = emptyList(),
        override val isError: Boolean = true,
    ) : Notice
}

fun Notice.resolve(context: Context): String = when (this) {
    is Notice.FromServer -> text
    is Notice.Local -> context.getString(id, *args.toTypedArray())
}

fun OpenResult.toNotice(): Notice = when (this) {
    is OpenResult.Sent -> message.orLocal(R.string.open_sent, isError = false)
    is OpenResult.Rejected -> message.orLocal(R.string.error_unknown)
    is OpenResult.Cooldown -> Notice.Local(R.string.open_cooldown, listOf(secondsLeft))
    OpenResult.NetworkError -> Notice.Local(R.string.error_network)
    is OpenResult.ServerError -> serverNotice(detail)
    OpenResult.SignInRequired -> Notice.Local(R.string.error_session)
    is OpenResult.DeviceNotTrusted -> message.orLocal(R.string.error_device_changed)
}

fun PinRequestResult.toNotice(): Notice = when (this) {
    is PinRequestResult.Sent -> message.orLocal(R.string.pin_sent, isError = false)
    is PinRequestResult.Rejected -> message.orLocal(R.string.error_unknown)
    is PinRequestResult.Cooldown -> Notice.Local(R.string.pin_cooldown, listOf(secondsLeft))
    PinRequestResult.NetworkError -> Notice.Local(R.string.error_network)
    is PinRequestResult.ServerError -> serverNotice(detail)
}

/** @return null when the doors simply loaded and there is nothing to say. */
fun DoorsResult.toNotice(): Notice? = when (this) {
    is DoorsResult.Loaded -> null
    is DoorsResult.Rejected -> message.orLocal(R.string.error_unknown)
    DoorsResult.NetworkError -> Notice.Local(R.string.error_network)
    is DoorsResult.ServerError -> serverNotice(detail)
    DoorsResult.SignInRequired -> Notice.Local(R.string.error_session)
    is DoorsResult.DeviceNotTrusted -> message.orLocal(R.string.error_device_changed)
}

/** @return null when the sign-in succeeded and there is nothing to say. */
fun SignInResult.toNotice(): Notice? = when (this) {
    SignInResult.Success -> null
    is SignInResult.PinRequired -> message.orLocal(R.string.pin_hint, isError = false)
    is SignInResult.Rejected -> message.orLocal(R.string.error_unknown)
    SignInResult.NetworkError -> Notice.Local(R.string.error_network)
    is SignInResult.ServerError -> serverNotice(detail)
}

/** Shows what the server actually said, rather than blaming the network for it. */
private fun serverNotice(detail: String): Notice =
    if (detail.isBlank()) {
        Notice.Local(R.string.error_unknown)
    } else {
        Notice.Local(R.string.error_server, listOf(detail))
    }

/** A server that answered with nothing useful still needs to say something. */
private fun String.orLocal(@StringRes fallback: Int, isError: Boolean = true): Notice =
    if (isBlank()) Notice.Local(fallback, isError = isError) else Notice.FromServer(this, isError)
