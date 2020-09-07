import com.koupper.container.interfaces.Container

val applicationFundedNotification: (Container) -> Container = { container ->
    println("Hello World!")

    container
}