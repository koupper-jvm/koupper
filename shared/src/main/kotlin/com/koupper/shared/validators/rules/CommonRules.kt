package com.koupper.shared.validators.rules

import com.koupper.shared.validators.core.Rule

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
