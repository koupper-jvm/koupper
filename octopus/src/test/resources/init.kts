import com.koupper.octopus.Config

val init: (Config) -> Config = {
    it.runScript("/Users/jacobacosta/Code/koupper/octopus/src/test/resources/example.kts")
}