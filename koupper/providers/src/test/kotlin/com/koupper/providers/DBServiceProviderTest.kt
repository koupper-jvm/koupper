package com.koupper.providers

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.container.app
import com.koupper.providers.db.DBConnector
import com.koupper.providers.db.DBServiceProvider
import com.koupper.providers.db.DBPSQLConnector
import com.koupper.providers.db.DBSQLiteConnector
import io.kotest.extensions.system.withEnvironment
import kotlin.test.assertTrue

class DBServiceProviderTest : AnnotationSpec() {

}