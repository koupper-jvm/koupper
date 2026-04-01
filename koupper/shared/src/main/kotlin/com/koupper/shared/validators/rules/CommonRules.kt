package com.koupper.shared.validators.rules

import com.koupper.shared.validators.core.Rule
import com.koupper.shared.validators.core.Schema
import com.koupper.shared.validators.core.validate

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

object NotNull : Rule<Any> {
    override fun check(v: Any?): String? =
        if (v == null) "must_not_be_null" else null
}

class MinValue<T : Number>(private val min: T) : Rule<T> {
    override fun check(v: T?): String? =
        if (v != null && v.toDouble() < min.toDouble()) "too_small" else null
}

class MaxValue<T : Number>(private val max: T) : Rule<T> {
    override fun check(v: T?): String? =
        if (v != null && v.toDouble() > max.toDouble()) "too_large" else null
}


class OneOf(private val values: Set<String>) : Rule<String> {
    override fun check(v: String?): String? =
        if (v != null && !values.contains(v)) "invalid_value" else null
}

object Url : Rule<String> {
    private val rx =
        Regex("^(https?://)[^\\s/$.?#].[^\\s]*$")

    override fun check(v: String?): String? =
        if (v != null && v.isNotBlank() && !rx.matches(v))
            "invalid_url"
        else null
}

object Base64String : Rule<String> {
    override fun check(v: String?): String? {
        if (v.isNullOrBlank()) return null
        return try {
            java.util.Base64.getDecoder().decode(
                v.replaceFirst("data:[^;]+;base64,".toRegex(), "")
            )
            null
        } catch (e: Exception) {
            "invalid_base64"
        }
    }
}

object NotEmptyList : Rule<List<Any?>> {
    override fun check(v: List<Any?>?): String? =
        if (v == null || v.isEmpty()) "must_not_be_empty" else null
}

class ListMinSize(private val n: Int) : Rule<List<Any?>> {
    override fun check(v: List<Any?>?): String? =
        if (v != null && v.size < n) "list_too_small" else null
}

class ListMaxSize(private val n: Int) : Rule<List<Any?>> {
    override fun check(v: List<Any?>?): String? =
        if (v != null && v.size > n) "list_too_large" else null
}

object UUIDFormat : Rule<String> {
    private val rx = Regex("^[0-9a-fA-F-]{36}$")

    override fun check(v: String?): String? =
        if (v != null && !rx.matches(v)) "invalid_uuid" else null
}

object BooleanString : Rule<String> {
    override fun check(v: String?): String? {
        if (v == null) return null
        val s = v.lowercase()
        return if (s != "true" && s != "false")
            "invalid_boolean"
        else null
    }
}

class EachValid<E : Any>(
    private val schema: Schema<E>,
    private val maxErrors: Int = 50
) : Rule<List<E>> {
    override fun check(v: List<E>?): String? {
        if (v == null) return null

        val msgs = mutableListOf<String>()

        for ((i, item) in v.withIndex()) {
            val res = validate(item, schema)
            if (!res.ok) {
                for (err in res.errors) {
                    for (m in err.messages) {
                        msgs += "[$i].${err.field}: $m"
                        if (msgs.size >= maxErrors) break
                    }
                    if (msgs.size >= maxErrors) break
                }
            }
            if (msgs.size >= maxErrors) break
        }

        return if (msgs.isEmpty()) null else msgs.joinToString(" | ")
    }
}

class EachRule<T>(
    private val rule: Rule<T>,
    private val maxErrors: Int = 50
) : Rule<List<T>> {

    override fun check(v: List<T>?): String? {
        if (v == null) return null

        val msgs = mutableListOf<String>()

        for ((i, item) in v.withIndex()) {
            val m = rule.check(item)
            if (m != null) {
                msgs += "[$i]: $m"
                if (msgs.size >= maxErrors) break
            }
        }

        return if (msgs.isEmpty()) null else msgs.joinToString(" | ")
    }
}
