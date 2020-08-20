import io.kup.container.extensions.instanceOf
import io.kup.container.interfaces.Container
import io.kup.providers.despatch.Sender
import io.kup.providers.logger.Logger
import io.kup.providers.parsing.TextParser
import zigocapital.db.DBZigoManager
import zigocapital.logger.DBLoggerConfigZigocapital

val applicationFundedNotification: (Container) -> Container = { container ->
    val dbZigoManager = container.create().instanceOf<DBZigoManager>()

    val applicationsFunded: List<Map<String, String?>> = dbZigoManager.applicationsFunded()

    val htmlParser = container.create("TextParserHtmlEmailTemplate").instanceOf<TextParser>()

    val htmlLayout = htmlParser.readFromPath("/Users/jacobacosta/Code/kup-framework/octopus/src/main/resources/notifications/template.html")

    val htmlEmailSender = container.create()
            .instanceOf<Sender>()
            .configUsing("/Users/jacobacosta/Code/kup-framework/octopus/src/main/resources/notifications/.env_notifications")

    val dbLogger = container
            .create().instanceOf<Logger>()
            .configUsing(DBLoggerConfigZigocapital())

    for (applicationFunded in applicationsFunded) {
        val emailContent = htmlParser.bind(
                applicationFunded,
                htmlLayout
        )

        htmlEmailSender.withContent(emailContent.toString())
        htmlEmailSender.sendTo(applicationFunded["email"]!!)
        htmlEmailSender.trackUsing(dbLogger)
    }

    container
}
