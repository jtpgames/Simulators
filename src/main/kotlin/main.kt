import com.google.common.base.Stopwatch
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.jpmml.evaluator.*
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.lang.Double.max
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.IntStream.range

data class StartedCommand(
    val cmd: String = "",
    val parallelCommandsStart: Int = 0,
    var parallelCommandsFinished: Int = 0
)

val logger = LoggerFactory.getLogger("Audit")

fun logInfo(tid: String, msg: String)
{
    logger.info("UID: $tid, $msg")
}

fun logCommand(tid: String, cmd: String, startOrEndOfCmd: String)
{
    logInfo(tid, "%-9s %s".format(startOrEndOfCmd, cmd))
}

fun logStartCommand(tid: String, cmd: String)
{
    logCommand(tid, cmd, "CMD-START")
}

fun logEndCommand(tid: String, cmd: String)
{
    logCommand(tid, cmd, "CMD-ENDE")
}

@OptIn(ExperimentalSerializationApi::class)
class Predictor(nameOfModel: String, nameOfMappingFile: String)
{
    private val evaluator: Evaluator
    val knownRequestTypes: Map<String, Int>

    private val log = LoggerFactory.getLogger(javaClass)

    private var numberOfParallelRequestsPending: AtomicInteger = AtomicInteger(0)
    val startedCommands = mutableMapOf<String, StartedCommand>()

    init
    {
        val pathToModel = this.javaClass.getResource(nameOfModel).path
        val pathToMapping = this.javaClass.getResource(nameOfMappingFile).path

        knownRequestTypes = Json.decodeFromStream(FileInputStream(pathToMapping))
        log.debug(knownRequestTypes.toString())

        // Building a model evaluator from a PMML file
        evaluator = LoadingModelEvaluatorBuilder()
            .load(File(pathToModel))
            .build()

        // Performing the self-check
        evaluator.verify()
        log.debug("Summary: ${evaluator.summary}")

        // Printing input (x1, x2, .., xn) fields
        val inputFields = evaluator.inputFields
        log.debug("Input fields count: ${inputFields.size}")
        log.debug("Input fields content: $inputFields")

        // Printing primary result (y) field(s)
        val targetFields = evaluator.targetFields
        log.debug("Target fields count: ${targetFields.size}")
        log.debug("Target field(s): $targetFields")

        // Printing secondary result (eg. probability(y), decision(y)) fields
        val outputFields: List<OutputField> = evaluator.outputFields
        log.debug("Output fields count: ${outputFields.size}")
        log.debug("Output fields: $outputFields")
    }

    fun predictSleepTime(tid: String, command: String): Double
    {
        val requestTypeAsInt = knownRequestTypes.getOrDefault(command, 0)

        val inputMap = mapOf(
            Pair("PR 1", startedCommands.getOrDefault(tid, StartedCommand()).parallelCommandsStart),
            Pair("PR 3", startedCommands.getOrDefault(tid, StartedCommand()).parallelCommandsFinished),
            Pair("Request Type", requestTypeAsInt)
        )

        log.debug("-> UID: $tid, X: $inputMap -")
        val y = predict(inputMap)
        log.debug("<- UID: $tid, y: $y -")

        return max(0.0, y)
    }

    fun addNewStartedCommand(tid: String, command: String)
    {
        val numberOfParallelRequestsAtBeginning = numberOfParallelRequestsPending.getAndIncrement()

        logStartCommand(tid, command)

        synchronized(this) {
            startedCommands[tid] = StartedCommand(
                cmd = command,
                parallelCommandsStart = numberOfParallelRequestsAtBeginning,
                parallelCommandsFinished = 0
            )
        }
    }

    fun removeStartedCommand(tid: String)
    {
        numberOfParallelRequestsPending.decrementAndGet()

        synchronized(this) {
            val command = startedCommands.remove(tid)

            for (cmd in startedCommands.values)
            {
                ++cmd.parallelCommandsFinished
            }

            command?.let { logEndCommand(tid, it.cmd) }
        }
    }

    private inline fun predict(inputMap: Map<String, Int>): Double
    {
        val prediction = evaluator.evaluate(inputMap)

        // Decoupling results from the JPMML-Evaluator runtime environment
        val results = EvaluatorUtil.decodeAll(prediction)

        return results.values.first() as Double
    }
}

inline fun <T : Any> getAttributeKeyFor(name: String): AttributeKey<T>
{
    return AttributeKey(name)
}

fun main()
{
    val predictor = Predictor(
        "teastore_model_LR_18-02-2023.pmml",
        "teastore_requests_mapping_18-02-2023.json"
    )

    val prefix = "/tools.descartes.teastore.webui/"

    val urlsToIgnoreInPlugins = listOf("/", "/logs/reset")

    ////////////////////////////////////////////////////////////////////////////
    // Ktor plugins
    ////////////////////////////////////////////////////////////////////////////

    val simulateProcessingTimePlugin =  createApplicationPlugin(name = "simulateProcessingTimePlugin") {
        application.environment.log.info("simulateProcessingTimePlugin is installed!")

        onCall {call ->
            if (call.request.path() in urlsToIgnoreInPlugins)
            {
                return@onCall
            }

            if (call.isHandled)
            {
                return@onCall
            }

            call.application.environment.log.debug("simulateProcessingTimePlugin: onCall")

            val tid = call.attributes[getAttributeKeyFor<String>("X-UID")]
            val foundCommand = call.attributes[getAttributeKeyFor<String>("X-CMD")]
            val stopwatch = call.attributes[getAttributeKeyFor<Stopwatch>("X-SW")]

            var total_sleep_time = 0.0

            var elapsedTimeSeconds = stopwatch.elapsed().toMillis() / 1000.0
            var sleep_time_to_use = predictor.predictSleepTime(tid, foundCommand)
            call.application.environment.log.debug("--> UID: $tid, $foundCommand: Elapsed time: ${elapsedTimeSeconds}s")
            call.application.environment.log.debug("--> UID: $tid, $foundCommand: Predicted processing time: ${sleep_time_to_use}s")
            sleep_time_to_use -= elapsedTimeSeconds
            sleep_time_to_use = max(0.0, sleep_time_to_use)

            if (sleep_time_to_use > 0)
            {
                call.application.environment.log.debug("--> UID: $tid, $foundCommand: Waiting for $sleep_time_to_use")
                delay((sleep_time_to_use * 1000).toLong())
                total_sleep_time += sleep_time_to_use

                for (i in range(1, 2))
                {
                    elapsedTimeSeconds = stopwatch.elapsed().toMillis() / 1000.0
                    var sleep_time_test = predictor.predictSleepTime(tid, foundCommand)
                    call.application.environment.log.debug("--> UID: $tid, $foundCommand: Elapsed time: ${elapsedTimeSeconds}s")
                    call.application.environment.log.debug("--> UID: $tid, $foundCommand: Predicted processing time: ${sleep_time_test}s")
                    sleep_time_test -= elapsedTimeSeconds
                    sleep_time_to_use = max(0.0, sleep_time_test)

                    if (sleep_time_to_use > 0)
                    {
                        call.application.environment.log.debug("--> UID: $tid, $foundCommand: Waiting for $sleep_time_to_use")
                        delay((sleep_time_to_use * 1000).toLong())
                        total_sleep_time += sleep_time_to_use
                    }
                    else
                    {
                        break
                    }
                }
            }
            else
            {
                call.application.environment.log.debug("--> UID: $tid, $foundCommand: Skip waiting")
            }

            call.response.header("X-Pred-Process-Time", total_sleep_time.toString())
        }
    }

    val trackParallelRequestsPlugin2 = createApplicationPlugin(name = "trackParallelRequestsPlugin2") {
        application.environment.log.info("trackParallelRequestsPlugin2 is installed!")

        onCall { call ->
            if (call.request.path() in urlsToIgnoreInPlugins)
            {
                return@onCall
            }

            if (call.isHandled)
            {
                return@onCall
            }

            call.application.environment.log.debug("trackParallelRequestsPlugin2: onCall")

            val tid = call.attributes[getAttributeKeyFor<String>("X-UID")]

            predictor.removeStartedCommand(tid)
        }
    }

    val trackParallelRequestsPlugin = createApplicationPlugin(name = "trackParallelRequestsPlugin") {
        application.environment.log.info("trackParallelRequestsPlugin is installed!")

        onCall { call ->
            if (call.request.path() in urlsToIgnoreInPlugins)
            {
                return@onCall
            }

            if (call.isHandled)
            {
                return@onCall
            }

            call.application.environment.log.debug("trackParallelRequestsPlugin: onCall")

            val tid = call.attributes[getAttributeKeyFor<String>("X-UID")]
            val foundCommand = call.attributes[getAttributeKeyFor<String>("X-CMD")]
            
            predictor.addNewStartedCommand(tid, foundCommand)
        }
    }

    val extractCommandPlugin = createApplicationPlugin(name = "extractCommandPlugin") {
        application.environment.log.info("extractCommandPlugin is installed!")

        onCall { call ->
            if (call.request.path() in urlsToIgnoreInPlugins)
            {
                return@onCall
            }

            call.application.environment.log.debug("extractCommandPlugin: onCall")

            val url = call.request.path()
            call.application.environment.log.debug(url)

            val command = if (url != prefix) url.removePrefix(prefix) else "index"

            val tid = call.attributes[getAttributeKeyFor<String>("X-UID")]
            call.application.environment.log.info("$tid Cmd: $command")

            var foundCommand: String? = null

            for (knownCommand in predictor.knownRequestTypes)
            {
                if (command.lowercase() in knownCommand.key.lowercase())
                {
                    foundCommand = knownCommand.key
                    break
                }
            }

            if (foundCommand == null)
            {
                call.respondText("Command not found", status = HttpStatusCode.NotFound)
                return@onCall
            }

            call.application.environment.log.info("-> $foundCommand")

            call.attributes.put(getAttributeKeyFor("X-CMD"), foundCommand)
        }
    }

    val addUniqueIdPlugin = createApplicationPlugin(name = "addUniqueIdPlugin") {
        application.environment.log.info("addUniqueIdPlugin is installed!")

        onCall { call ->
            call.application.environment.log.debug("addUniqueIdPlugin: onCall")

            val uniqueCommandId = UUID.randomUUID().toString()

            call.attributes.put(getAttributeKeyFor("X-UID"), uniqueCommandId)
        }
    }

    val addProcessTimeHeaderPlugin = createApplicationPlugin(name = "addProcessTimeHeaderPlugin") {
        application.environment.log.info("addProcessTimeHeaderPlugin is installed!")

        onCall { call ->
            call.application.environment.log.debug("addProcessTimeHeaderPlugin: onCall")
            val stopwatch = Stopwatch.createStarted()
            call.attributes.put(getAttributeKeyFor("X-SW"), stopwatch)
        }
        onCallRespond { call ->
            call.application.environment.log.debug("addProcessTimeHeaderPlugin: onCallRespond")

            val stopwatch = call.attributes[getAttributeKeyFor<Stopwatch>("X-SW")]
            stopwatch.stop()

            val tid = call.attributes[getAttributeKeyFor<String>("X-UID")]

            val cmdKey = getAttributeKeyFor<String>("X-CMD")
            if (call.attributes.contains(cmdKey))
            {
                val command = call.attributes[cmdKey]
                call.application.environment.log.info("UID: $tid, CMD: $command, Processing Time: $stopwatch")
            }

            call.response.header("X-Process-Time", stopwatch.toString())
        }
    }

    ////////////////////////////////////////////////////////////////////////////
    // Ktor server
    ////////////////////////////////////////////////////////////////////////////
    val server = embeddedServer(Netty, host = "0.0.0.0", port = 1337) {
        install(addProcessTimeHeaderPlugin)
        install(addUniqueIdPlugin)
        install(extractCommandPlugin)
        install(trackParallelRequestsPlugin)
        install(simulateProcessingTimePlugin)
        install(trackParallelRequestsPlugin2)
        install(CallLogging) {
            disableDefaultColors()
        }

        routing {
            get("/") {
                call.application.environment.log.info("GET /")
                call.respondText("Success")
            }
            get("/logs/reset") {
                call.application.environment.log.info("reset logs")

                withContext(Dispatchers.IO) {
                    FileChannel
                        .open(Paths.get("teastore-cmd_simulation.log"), StandardOpenOption.WRITE)
                        .truncate(0)
                        .close()
                }

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
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(1, 5, TimeUnit.SECONDS)
    })
    Thread.currentThread().join()
}

