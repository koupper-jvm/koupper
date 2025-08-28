package com.koupper.shared.validators.rules

import com.koupper.shared.validators.core.Rule

private class WithMessage<T>(
    val base: Rule<T>,
    val custom: String
) : Rule<T> {
    override fun check(v: T?): String? = base.check(v)?.let { custom }
}

fun <T> Rule<T>.withMessage(message: String): Rule<T> =
    WithMessage(this, message)

object NotBlank: Rule<String> {
    override fun check(v: String?) = if (v.isNullOrBlank()) "must_not_be_blank" else null
}

class Pattern(private val rx: Regex, private val msg: String): Rule<String> {
    override fun check(v: String?) = if (v != null && !rx.matches(v)) msg else null
}

object Email: Rule<String> {
    private val rx = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    override fun check(v: String?) = if (v != null && !rx.matches(v)) "invalid_email" else null
}

class MaxLen(private val n: Int): Rule<String> {
    override fun check(v: String?) = if (v != null && v.length > n) "too_long" else null
}

class MinLen(private val n: Int): Rule<String> {
    override fun check(v: String?) = if ((v ?: "").length < n) "too_short" else null
}

object Phone: Rule<String> {
    private val rx = Regex("^[\\d+\\-\\s()]{6,}$")
    override fun check(v: String?) = if (v != null && v.isNotBlank() && !rx.matches(v)) "invalid_phone" else null
}

object IsoDate: Rule<String> {
    private val rx = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    override fun check(v: String?) = if (v != null && v.isNotBlank() && !rx.matches(v)) "invalid_date" else null
}
