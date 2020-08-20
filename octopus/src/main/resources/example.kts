import com.koupper.container.extensions.instanceOf
import com.koupper.container.interfaces.Container
import com.koupper.providers.despatch.Sender
import com.koupper.providers.logger.Logger
import com.koupper.providers.parsing.TextParser
import zigocapital.db.DBZigoManager
import zigocapital.logger.DBLoggerConfigZigocapital

val applicationFundedNotification: (Container) -> Container = { container ->
    val dbZigoManager = container.create().instanceOf<DBZigoManager>()

    val applicationsFunded: List<Map<String, String?>> = dbZigoManager.applicationsFunded()

    val htmlParser = container.create("TextParserHtmlEmailTemplate").instanceOf<TextParser>()

    val htmlLayout = htmlParser.readFromPath("/Users/jacobacosta/Code/koupper/octopus/src/main/resources/notifications/template.html")

    val htmlEmailSender = container.create()
            .instanceOf<Sender>()
            .configUsing("/Users/jacobacosta/Code/koupper/octopus/src/main/resources/notifications/.env_notifications")

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
