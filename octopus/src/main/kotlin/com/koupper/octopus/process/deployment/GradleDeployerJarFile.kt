package com.koupper.octopus.process.deployment

import com.koupper.container.interfaces.Container
import com.koupper.octopus.process.Process
import org.gradle.tooling.GradleConnector
import java.io.*


class GradleDeployerJarFile(private val container: Container) : Process {
    override lateinit var name: String
    override var metadata: MutableMap<String, Any> = mutableMapOf()
    override lateinit var moduleType: String
    override lateinit var location: String
    override lateinit var version: String

    override fun register(name: String, metadata: Map<String, Any>): Process {
        this.name = name
        this.metadata.putAll(metadata)
        this.moduleType = this.metadata["moduleType"] as String
        this.location = this.metadata["location"] as String
        this.version = this.metadata["version"] as String

        return this
    }

    override fun processName(): String {
        return this.name
    }

    override fun processType(): String {
        return this.moduleType
    }

    override fun run() {
        val connection = GradleConnector.newConnector()
            .forProjectDirectory(File(this.name))
            .connect()

        val buildLauncher = connection.newBuild()
        buildLauncher.forTasks("build")
        buildLauncher.run()

        val jarFilePath = "${this.name}/build/libs/${this.name}-${this.version}.jar"


        try {
            val processBuilder = ProcessBuilder("java", "-cp", "$jarFilePath:${this.name}/build/libs/lib/*", "server.SetupKt")
            processBuilder.redirectErrorStream(true) // Redirect error stream to output stream

            val process = processBuilder.start()

            // Read and print the process output
            val inputStream = process.inputStream
            val inputStreamReader = InputStreamReader(inputStream)
            val bufferedReader = BufferedReader(inputStreamReader)
            var line: String?
            while (bufferedReader.readLine().also { line = it } != null) {
                println(line)
            }

            // Wait for the process to complete
            val exitCode = process.waitFor()
            println("Process exited with code: $exitCode")
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }
}