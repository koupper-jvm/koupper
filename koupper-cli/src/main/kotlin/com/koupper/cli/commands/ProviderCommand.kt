package com.koupper.cli.commands

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.koupper.cli.ANSIColors
import com.koupper.cli.ANSIColors.ANSI_GREEN_155
import com.koupper.cli.ANSIColors.ANSI_RESET
import com.koupper.cli.ANSIColors.ANSI_WHITE
import com.koupper.cli.ANSIColors.ANSI_YELLOW_229
import com.koupper.cli.commands.AvailableCommands.PROVIDER
import java.io.File

data class ProviderCatalog(
    val version: String = "1.0",
    val providers: List<ProviderEntry> = emptyList()
)

data class ProviderEntry(
    val id: String,
    val serviceProvider: String,
    val description: String,
    val bindings: List<ProviderBinding> = emptyList(),
    val env: List<ProviderEnvVar> = emptyList(),
    val docs: String? = null
)

data class ProviderBinding(
    val contract: String,
    val implementations: List<ProviderImplementation> = emptyList()
)

data class ProviderImplementation(
    val `class`: String,
    val tag: String? = null
)

data class ProviderEnvVar(
    val name: String,
    val required: Boolean,
    val description: String
)

class ProviderCommand : Command() {
    private val mapper = jacksonObjectMapper()
    private val userHome = System.getProperty("user.home")
    private val configuredCatalogPath = System.getProperty("koupper.providers.catalog.path")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: System.getenv("KOUPPER_PROVIDERS_CATALOG")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    private val catalogPath = "$userHome/.koupper/catalog/providers.json"

    init {
        super.name = PROVIDER
        super.usage = "\n" + """
   koupper provider list                          Lists available service providers.
   koupper provider info <provider-id-or-class>  Shows detailed provider information.
        """
        super.description = "\n   Lists service providers and their environment requirements\n"
        super.arguments = mapOf(
            "list" to "Shows all providers with a short description.",
            "info <name>" to "Shows contracts, implementations, tags and environment variables."
        )
        super.additionalInformation = "\n   For provider setup details, see official documentation."
    }

    override fun name(): String = PROVIDER

    override fun execute(vararg args: String): String {
        val realArgs = args.drop(1)

        if (realArgs.isEmpty()) {
            return "${showDescription()}${showUsage()}${showArguments()}"
        }

        val subcommand = realArgs.first().lowercase()
        val catalog = loadCatalog() ?: return missingCatalogMessage()

        return when (subcommand) {
            "list" -> listProviders(catalog)
            "info" -> {
                val needle = realArgs.getOrNull(1)
                    ?: return "\n${ANSIColors.ANSI_RED}Missing provider identifier. Use: koupper provider info <name>${ANSI_RESET}\n"
                providerInfo(catalog, needle)
            }

            else -> "\n${ANSIColors.ANSI_RED}Unknown provider subcommand: '$subcommand'${ANSI_RESET}\n${showUsage()}"
        }
    }

    private fun loadCatalog(): ProviderCatalog? {
        return catalogCandidates()
            .map(::File)
            .firstNotNullOfOrNull { file ->
                if (!file.exists() || !file.isFile) return@firstNotNullOfOrNull null
                runCatching { mapper.readValue<ProviderCatalog>(file) }.getOrNull()
            }
    }

    private fun catalogCandidates(): List<String> {
        val candidates = mutableListOf<String>()
        configuredCatalogPath?.let { candidates += it }
        candidates += catalogPath
        candidates += "koupper/providers/src/main/resources/providers-catalog.json"
        candidates += "../koupper/providers/src/main/resources/providers-catalog.json"
        return candidates.distinct()
    }

    private fun missingCatalogMessage(): String {
        return "\n${ANSIColors.ANSI_RED}Providers catalog not found at $catalogPath.${ANSI_RESET}\n" +
                "Run ${ANSI_GREEN_155}kotlinc -script install.kts -- --force${ANSI_RESET} to refresh local artifacts, " +
                "or set ${ANSI_GREEN_155}koupper.providers.catalog.path${ANSI_RESET}/" +
                "${ANSI_GREEN_155}KOUPPER_PROVIDERS_CATALOG${ANSI_RESET}.\n"
    }

    private fun listProviders(catalog: ProviderCatalog): String {
        if (catalog.providers.isEmpty()) {
            return "\n${ANSIColors.ANSI_RED}No providers found in catalog.${ANSI_RESET}\n"
        }

        val maxName = catalog.providers.maxOf { "${it.id} (${it.serviceProvider})".length }
        val header = "\n ${ANSI_YELLOW_229}- Available providers:${ANSI_RESET}\n"

        val rows = catalog.providers
            .sortedBy { it.id }
            .joinToString("\n") { provider ->
                val left = "${provider.id} (${provider.serviceProvider})".padEnd(maxName + 3)
                "   $ANSI_GREEN_155$left$ANSI_WHITE${provider.description}$ANSI_RESET"
            }

        return "$header$rows\n\n   Use ${ANSI_GREEN_155}koupper provider info <name>${ANSI_RESET} for details.\n"
    }

    private fun providerInfo(catalog: ProviderCatalog, needle: String): String {
        val normalized = needle.trim().lowercase()

        val provider = catalog.providers.firstOrNull {
            it.id.lowercase() == normalized ||
                    it.serviceProvider.lowercase() == normalized ||
                    it.bindings.any { binding -> binding.contract.lowercase() == normalized }
        } ?: return "\n${ANSIColors.ANSI_RED}Provider '$needle' was not found.${ANSI_RESET}\n"

        val bindings = if (provider.bindings.isEmpty()) {
            "   ${ANSI_WHITE}none${ANSI_RESET}"
        } else {
            provider.bindings.joinToString("\n") { binding ->
                val impls = if (binding.implementations.isEmpty()) {
                    "none"
                } else {
                    binding.implementations.joinToString(", ") { impl ->
                        if (impl.tag.isNullOrBlank()) impl.`class` else "${impl.`class`} [tag=${impl.tag}]"
                    }
                }
                "   $ANSI_GREEN_155${binding.contract}$ANSI_RESET -> $ANSI_WHITE$impls$ANSI_RESET"
            }
        }

        val envVars = if (provider.env.isEmpty()) {
            "   ${ANSI_WHITE}none${ANSI_RESET}"
        } else {
            provider.env.joinToString("\n") { variable ->
                val req = if (variable.required) "required" else "optional"
                "   $ANSI_GREEN_155${variable.name}$ANSI_RESET ($req) - ${variable.description}"
            }
        }

        val docsLine = provider.docs?.let { "\n ${ANSI_YELLOW_229}- Docs:$ANSI_RESET\n   $ANSI_WHITE$it$ANSI_RESET\n" } ?: ""

        return "\n ${ANSI_YELLOW_229}- Provider:$ANSI_RESET\n" +
                "   $ANSI_GREEN_155${provider.id}$ANSI_RESET (${provider.serviceProvider})\n\n" +
                " ${ANSI_YELLOW_229}- Description:$ANSI_RESET\n" +
                "   $ANSI_WHITE${provider.description}$ANSI_RESET\n\n" +
                " ${ANSI_YELLOW_229}- Bindings:$ANSI_RESET\n$bindings\n\n" +
                " ${ANSI_YELLOW_229}- Environment variables:$ANSI_RESET\n$envVars" +
                docsLine
    }
}
