package com.zuynew.amvideo.auto.data

/**
 * The back end wants a bare 11-digit number, and the web client strips anything
 * else before sending, so a pasted "+7 (929) 612-75-92" still works.
 */
fun normalizePhone(input: String): String = input.filter { it.isDigit() }

/** The server only accepts logins that are exactly 11 digits. */
const val PHONE_LENGTH = 11
