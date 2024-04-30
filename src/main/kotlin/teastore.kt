import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.stream.IntStream.range

enum class TeaStoreModelToUse
{
    // Decision Tree Regression with Request Type, PR 1, PR 3
    `DT_T-PR_1_3`,
    // Ridge Regression with Request Type, PR 1, PR 3
    `Ridge_T-PR_1_3`,
    // Decision Tree Regression with Request Type, PR 1, RPS, RPM
    `DT_T-PR_1-RPS-RPM`,
    // Ridge Regression with Request Type, PR 1, RPS, RPM
    `Ridge_T-PR_1-RPS-RPM`,
    // Decision Tree Regression with Request Type, PR 1, PR 3, RPS, RPM
    `DT_T-PR_1_3-RPS-RPM`,
    // Ridge Regression with Request Type, PR 1, PR 3, RPS, RPM
    `Ridge_T-PR_1_3-RPS-RPM`,
}

fun printUsage() {
    println("Usage: java -jar Rast-Simulator.jar [OPTIONS]")
    println("Options:")
    println("  -m <modelToUse>   Specify the model to use (" +
            "0=DT_T-PR_1_3, 1=Ridge_T-PR_1_3, " +
            "2=DT_T-PR_1-RPS-RPM, 3=Ridge_T-PR_1-RPS-RPM, " +
            "4=DT_T-PR_1_3-RPS-RPM, 5=Ridge_T-PR_1_3-RPS-RPM" +
            ")")
    println("  -c <corr_max>     Specify the value for corr_max to use. Defaults to 0")
    println("  -h, --help        Print this help message")
}

fun main(args: Array<String>)
{
    val logger = LoggerFactory.getLogger("Main")

    var modelToUse: TeaStoreModelToUse = TeaStoreModelToUse.`Ridge_T-PR_1_3`
    var corr_max = 0

    if (args.isNotEmpty() && (args[0] == "-h" || args[0] == "--help")) {
        printUsage()
        return
    }

    var i = 0
    while (i < args.size)
    {
        when (args[i])
        {
            "-m" ->
            {
                if (i + 1 < args.size)
                {
                    val argValue = args[i + 1]
                    val modelIndex = argValue.toIntOrNull()
                    if (modelIndex != null && modelIndex in 0 until TeaStoreModelToUse.values().size) {
                        modelToUse = TeaStoreModelToUse.values()[modelIndex]
                    } else {
                        throw IllegalArgumentException("Invalid value for -m argument: $argValue")
                    }
                    i += 2 // Skip the argument and its value
                } else
                {
                    throw IllegalArgumentException("Missing value for -m argument.")
                }
            }
            "-c" ->
            {
                if (i + 1 < args.size)
                {
                    corr_max = Integer.parseInt(args[i + 1])
                    i += 2 // Skip the argument and its value
                } else
                {
                    throw IllegalArgumentException("Missing value for -c argument.")
                }
            }
            else ->
            {
                throw IllegalArgumentException("Unknown argument: ${args[i]}")
            }
        }
    }

    configureLogging("teastore")

    logger.info("Using model $modelToUse")

    val (nameOfModel, nameOfMappingFile) = when (modelToUse)
    {
        TeaStoreModelToUse.`DT_T-PR_1_3` -> Pair("teastore_model_DT_T_PR_1_3.pmml", "teastore_requests_mapping_DT_ordinal_encoding.json")
        TeaStoreModelToUse.`Ridge_T-PR_1_3` -> Pair("teastore_model_Ridge_T_PR_1_3.pmml", "teastore_requests_mapping_LR_ordinal_encoding.json")
        TeaStoreModelToUse.`DT_T-PR_1-RPS-RPM` -> Pair("teastore_model_DT_T-PR_1-RPS-RPM.pmml", "teastore_requests_mapping_DT_ordinal_encoding.json")
        TeaStoreModelToUse.`Ridge_T-PR_1-RPS-RPM` -> Pair("teastore_model_Ridge_T-PR_1-RPS-RPM.pmml", "teastore_requests_mapping_LR_ordinal_encoding.json")
        TeaStoreModelToUse.`DT_T-PR_1_3-RPS-RPM` -> Pair("teastore_model_DT_T-PR_1_3-RPS-RPM.pmml", "teastore_requests_mapping_DT_ordinal_encoding.json")
        TeaStoreModelToUse.`Ridge_T-PR_1_3-RPS-RPM` -> Pair("teastore_model_Ridge_T-PR_1_3-RPS-RPM.pmml", "teastore_requests_mapping_LR_ordinal_encoding.json")
    }

    val predictor = Predictor(nameOfModel, nameOfMappingFile)

    val prefix = "/tools.descartes.teastore.webui/"

    val urlsToIgnoreInPlugins = listOf("/", "/logs/reset")

    val rastSimulationKtorPlugins = RASTSimulationKtorPlugins(
        prefix,
        urlsToIgnoreInPlugins,
        predictor,
        corr_max
    )

    ////////////////////////////////////////////////////////////////////////////
    // Ktor server
    ////////////////////////////////////////////////////////////////////////////
    val server = embeddedServer(Netty, host = "0.0.0.0", port = 8080) {
        rastSimulationKtorPlugins.installAllPlugins(this)
        install(CallLogging) {
            disableDefaultColors()
        }

        routing {
            get("/") {
                call.application.environment.log.info("GET /")
                call.respondText("Success")
            }

            route(prefix) {
                get("") {
                    call.application.environment.log.info("GET index")
                    call.respondText("Success")
                }
                get("login") {
                    call.application.environment.log.info("GET login")
                    call.respondText("Success")
                }
                post("loginAction") {
                    val username = call.request.queryParameters["username"]
                    val password = call.request.queryParameters["password"]
                    val logout = call.request.queryParameters["logout"]

                    call.application.environment.log.info("POST login $username $password $logout")
                    call.respondText("Success")
                }
                get("profile") {
                    call.application.environment.log.info("GET profile")
                    call.respondText("SimProfile")
                }
                get("cart") {
                    call.application.environment.log.info("GET cart")
                    call.respondText("Empty cart")
                }
                post("cartAction") {
                    val action = call.request.queryParameters["action"]
                    val productid = call.request.queryParameters["productid"]?.toInt()
                    val confirm = call.request.queryParameters["confirm"]

                    call.application.environment.log.info("POST cartAction with $action, $productid, $confirm")
                    call.respondText("Ok")
                }
                get("category") {
                    val page = call.request.queryParameters["page"]?.toInt()
                    val category = call.request.queryParameters["category"]?.toInt()
                    var number = call.request.queryParameters["number"]?.toInt()
                    if (number == null)
                    {
                        number = 0
                    }

                    val random_products = range(1, 50)

                    var result_string = ""
                    for (product in random_products)
                    {
                        result_string += """<a href="/tools.descartes.teastore.webui/product?id=$product" ><img 
                                            src=""
                                            alt="Assam (loose)"></a>"""
                    }

                    call.application.environment.log.info("GET category: page:$page, " +
                            "category:$category, " +
                            "number: $number")
                    call.respondText(result_string)
                }
                get("product") {
                    val id = call.request.queryParameters["id"]?.toInt()

                    call.application.environment.log.info("GET cart")
                    call.respondText("Empty cart")
                }
            }
        }
    }.start(wait = false)
    val logsserver = embeddedServer(Netty, host = "0.0.0.0", port = 8081) {
        install(CallLogging) {
            disableDefaultColors()
        }

        routing {
            get("/logs/reset") {
                call.application.environment.log.info("reset logs")

                withContext(Dispatchers.IO) {
                    FileChannel
                        .open(Paths.get("teastore_simulation.log"), StandardOpenOption.WRITE)
                        .truncate(0)
                        .close()
                }

                call.respondText("Success")
            }
        }
    }.start(wait = false)

    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(1, 5, TimeUnit.SECONDS)
        logsserver.stop(1, 5, TimeUnit.SECONDS)
    })
    Thread.currentThread().join()
}

