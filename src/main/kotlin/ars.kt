import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import com.xenomachina.argparser.mainBody
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

enum class ARSModelToUse
{
    // MASCOTS 2022 model
    MASCOTS2022,
    GS_RIDGE_ORDINAL,
    GS_DT_ORDINAL,

    // SIMUtools 2023 model with ordinal encoding
    SIMUTOOLS2023_ORDINAL,

    // SIMUtools 2023 model with target encoding
    SIMUTOOLS2023_TARGET;

    companion object
    {
        fun fromInt(value: Int) = ARSModelToUse
            .values()
            .first { it.ordinal == value }
    }
}

class ARSArgs(parser: ArgParser)
{
    val modelToUse by parser
        .storing(
            "-m", "--modelToUse",
            help = "index of the model in the `ARSModelToUse` enum.",
            transform = { ARSModelToUse.fromInt(this.toInt()) }
        )
        .default(ARSModelToUse.MASCOTS2022)

    val corrMax by parser
        .storing(
            "-c", "--corr_max",
            help = "Specify the value for corr_max to use. Defaults to 0",
            transform = { toInt() }
        )
        .default(0)
}

fun main(args: Array<String>) = mainBody {
    ArgParser(args)
        .parseInto(::ARSArgs)
        .run {
            configureLogging("ars")

            val logger = LoggerFactory.getLogger("Main")

            logger.info("Using model $modelToUse")

            val predictor = when (modelToUse)
            {
                ARSModelToUse.MASCOTS2022 ->
                    Predictor(
                        "gs_model_LR_03-11-2022.pmml",
                        "gs_requests_mapping_prod_workload.json"
                    )

                ARSModelToUse.GS_RIDGE_ORDINAL ->
                    Predictor(
                        "gs_model_Ridge.pmml",
                        "gs_requests_mapping_LR_ordinal_encoding.json"
                    )

                ARSModelToUse.GS_DT_ORDINAL ->
                    Predictor(
                        "gs_model_DT.pmml",
                        "gs_requests_mapping_DT_ordinal_encoding.json"
                    )

                ARSModelToUse.SIMUTOOLS2023_ORDINAL ->
                    Predictor(
                        "gs_model_Ridge_18-03-2023.pmml",
                        "gs_requests_mapping_Ridge_18-03-2023.json",
                        mapOf(
                            Pair("PR 1", "pr_1"), Pair("PR 3", "pr_3"), Pair("Request Type", "cmd")
                        )
                    )

                ARSModelToUse.SIMUTOOLS2023_TARGET ->
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

            val rastSimulationKtorPlugins = RASTSimulationKtorPlugins(prefix, urlsToIgnoreInPlugins, predictor, corrMax)

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
            Runtime.getRuntime()
                .addShutdownHook(Thread {
                    server.stop(1, 5, TimeUnit.SECONDS)
                })
            Thread.currentThread()
                .join()
        }
}