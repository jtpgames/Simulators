import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.concurrent.TimeUnit

enum class ARSModelToUse
{
    // MASCOTS 2022 model
    MASCOTS2022,
    // TBD 2023 model with ordinal encoding
    TBD2023_ORDINAL,
    // TBD 2023 model with target encoding
    TBD2023_TARGET
}

val MODEL_TO_USE: ARSModelToUse = ARSModelToUse.MASCOTS2022

fun main()
{
    configureLogging("ars")

    val predictor = when (MODEL_TO_USE)
    {
        ARSModelToUse.MASCOTS2022 ->
            Predictor(
                "gs_model_LR_03-11-2022.pmml",
                "gs_requests_mapping_prod_workload.json"
            )

        ARSModelToUse.TBD2023_ORDINAL ->
            Predictor(
                "gs_model_Ridge_18-03-2023.pmml",
                "gs_requests_mapping_Ridge_18-03-2023.json",
                mapOf(
                    Pair("PR 1", "pr_1"), Pair("PR 3", "pr_3"), Pair("Request Type", "cmd")
                )
            )

        ARSModelToUse.TBD2023_TARGET ->
            Predictor(
                "gs_model_Ridge_target_encoding_20-03-2023.pmml",
                "gs_requests_mapping_Ridge_target_encoding_20-03-2023.json",
                mapOf(
                    Pair("PR 1", "pr_1"), Pair("PR 3", "pr_3"), Pair("Request Type", "cmd_target_encoding")
                )
            )
    }

    val prefix = "/"

    val urlsToIgnoreInPlugins = listOf("/")

    val rastSimulationKtorPlugins = RASTSimulationKtorPlugins(prefix, urlsToIgnoreInPlugins, predictor)

    val server = embeddedServer(
        Netty,
        host = "0.0.0.0",
        port = 1337,
        /*configure = {
            connectionGroupSize = 2
            workerGroupSize = 2
            callGroupSize = 1
            requestQueueLimit = 16
            shareWorkGroup = false
        }*/
    ) {
        rastSimulationKtorPlugins.installAllPlugins(this)
        install(CallLogging) {
            disableDefaultColors()
        }

        routing {
            for (requestType in predictor.knownRequestTypes)
            {
                post(requestType.key) {
                    call.application.environment.log.info("GET ${requestType.key}")
                    call.respondText("Success")
                }
            }
        }
    }.start(wait = false)
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(1, 5, TimeUnit.SECONDS)
    })
    Thread.currentThread().join()
}