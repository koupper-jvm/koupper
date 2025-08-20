package com.koupper.shared.validators.core

data class VError(val field: String, val messages: List<String>)
data class VResult<T>(val ok: Boolean, val data: T?, val errors: List<VError> = emptyList())

interface Rule<T> { fun check(value: T?): String? }

data class FieldRule<T, F>(
    val name: String,
    val get: (T)->F?,
    val set: ((T, F)->T)? = null,
    val rules: List<Rule<F>>
)

class Schema<T>(val fields: List<FieldRule<T, *>>)

fun <T: Any> validate(dto: T, schema: Schema<T>): VResult<T> {
    val errs = mutableMapOf<String, MutableList<String>>()
    schema.fields.forEach { f ->
        val value = (f.get).invoke(dto)
        @Suppress("UNCHECKED_CAST")
        (f.rules as List<Rule<Any?>>).forEach { r ->
            r.check(value)?.let { errs.getOrPut(f.name){ mutableListOf() }.add(it) }
        }
    }
    return if (errs.isEmpty()) VResult(true, dto) else
        VResult(false, null, errs.map { VError(it.key, it.value) })
}
