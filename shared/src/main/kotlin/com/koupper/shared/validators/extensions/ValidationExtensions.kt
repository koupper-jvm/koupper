package com.koupper.shared.validators.extensions

import com.koupper.shared.validators.core.FieldRule
import com.koupper.shared.validators.core.Rule
import com.koupper.shared.validators.core.Schema

class FieldRuleBuilder<T, F>(
    private val name: String,
    private val getter: (T) -> F?
) {
    val rules = mutableListOf<Rule<F>>()

    operator fun Rule<F>.unaryPlus() {
        rules.add(this)
    }

    fun build(): FieldRule<T, F> =
        FieldRule(name, getter, null, rules.toList())
}

class SchemaBuilder<T> {
    val fields = mutableListOf<FieldRule<T, *>>()

    fun <F> field(name: String, getter: (T) -> F?, block: FieldRuleBuilder<T, F>.() -> Unit) {
        val builder = FieldRuleBuilder(name, getter)
        builder.block()
        fields.add(builder.build())
    }

    fun build(): Schema<T> = Schema(fields)
}

inline fun <reified T : Any> schema(block: SchemaBuilder<T>.() -> Unit): Schema<T> {
    val builder = SchemaBuilder<T>()
    builder.block()
    return builder.build()
}
