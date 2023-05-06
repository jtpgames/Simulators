import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.stream.IntStream.range

fun main()
{
    configureLogging("teastore")

    val predictor = Predictor(
        "teastore_model_DT_New_PR3.pmml",
        "teastore_requests_mapping_DT_ordinal_encoding.json"
    )

    val prefix = "/tools.descartes.teastore.webui/"

    val urlsToIgnoreInPlugins = listOf("/", "/logs/reset")

    val rastSimulationKtorPlugins = RASTSimulationKtorPlugins(prefix, urlsToIgnoreInPlugins, predictor)

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

