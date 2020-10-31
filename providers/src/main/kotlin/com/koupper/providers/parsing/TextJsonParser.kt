package com.koupper.providers.parsing

interface TextJsonParser<String, out T> : TextParser {
    fun load(jsonString: String)
}