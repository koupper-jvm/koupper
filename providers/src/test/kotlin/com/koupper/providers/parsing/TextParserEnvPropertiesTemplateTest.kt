package com.koupper.providers.parsing

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.providers.parsing.extensions.splitKeyValue
import kotlin.test.assertTrue

class TextParserEnvPropertiesTemplateTest : AnnotationSpec() {
    @Ignore
    @Test
    fun `should returns a map of env properties from env file`() {
        val parseEnv = TextParserEnvPropertiesTemplate()

        parseEnv.readFromPath(".your_env_file")

        val properties: Map<String?, String?> = parseEnv.splitKeyValue("=".toRegex())

        assertTrue {
            properties["MAIL_DRIVER"].equals("smtp")
            properties["MAIL_HOST"].equals("smtp.mailtrap.io")
            properties["MAIL_PORT"].equals("2525")
            properties["MAIL_USERNAME"].equals("0b91e81609ece6")
            properties["MAIL_PASSWORD"].equals("38f00e18d59185")
            properties["MAIL_ENCRYPTION"].equals("null")
        }
    }
}