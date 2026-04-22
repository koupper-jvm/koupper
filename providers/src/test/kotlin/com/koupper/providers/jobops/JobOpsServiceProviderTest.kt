package com.koupper.providers.jobops

import com.koupper.container.app
import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertTrue

class JobOpsServiceProviderTest : AnnotationSpec() {
    @Test
    fun `should bind job ops provider`() {
        JobOpsServiceProvider().up()

        assertTrue {
            app.getInstance(JobOps::class) is DefaultJobOps
        }
    }
}
