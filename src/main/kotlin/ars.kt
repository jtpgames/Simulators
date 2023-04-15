import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.concurrent.TimeUnit

fun main()
{
    configureLogging()

    val predictor = Predictor(
        "gs_model_prod_workload.pmml",
        "gs_requests_mapping_prod_workload.json"
    )

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