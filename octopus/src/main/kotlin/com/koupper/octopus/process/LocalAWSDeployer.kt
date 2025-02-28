package com.koupper.octopus.process

import com.koupper.container.interfaces.Container
import org.gradle.tooling.GradleConnector
import java.io.*


class LocalAWSDeployer(private val container: Container) : Process {
    lateinit var name: String
    lateinit var moduleType: String
    lateinit var version: String
    lateinit var packageName: String
    lateinit var scripts: Map<String, String>

    override fun register(name: String,
                          moduleType: String,
                          version: String,
                          packageName: String,
                          scripts: Map<String, String>
    ) : Process {
        this.name = name
        this.moduleType = moduleType
        this.version = version
        this.packageName = packageName
        this.scripts = scripts

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

            val exitCode = process.waitFor()
            println("Process exited with code: $exitCode")
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }
}