import com.google.common.base.Stopwatch
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import java.lang.Double.max
import kotlinx.coroutines.delay
import java.util.*
import java.util.stream.IntStream

class RASTSimulationKtorPlugins(
    prefix: String,
    urlsToIgnoreInPlugins: List<String>,
    predictor: Predictor
)
{
    private val addProcessTimeHeaderPlugin: ApplicationPlugin<Unit> = createApplicationPlugin(name = "addProcessTimeHeaderPlugin") {
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

    private val addUniqueIdPlugin = createApplicationPlugin(name = "addUniqueIdPlugin") {
        application.environment.log.info("addUniqueIdPlugin is installed!")

        onCall { call ->
            call.application.environment.log.debug("addUniqueIdPlugin: onCall")

            val uniqueCommandId = call.request.header("Request-Id") ?: UUID.randomUUID().toString()

            call.attributes.put(getAttributeKeyFor("X-UID"), uniqueCommandId)
        }
    }

    private val extractCommandPlugin = createApplicationPlugin(name = "extractCommandPlugin") {
        application.environment.log.info("extractCommandPlugin is installed!")

        onCall { call ->
            if (call.request.path() in urlsToIgnoreInPlugins)
            {
                return@onCall
            }

            call.application.environment.log.debug("extractCommandPlugin: onCall")

            val url = call.request.path()
            call.application.environment.log.debug(url)

            val command: String = if (prefix.isNotBlank())
            {
                if (url != prefix) url.removePrefix(prefix) else "index"
            } else
            {
                url
            }

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

    private val trackParallelRequestsPlugin = createApplicationPlugin(name = "trackParallelRequestsPlugin") {
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

    private val simulateProcessingTimePlugin =  createApplicationPlugin(name = "simulateProcessingTimePlugin") {
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

                for (i in IntStream.range(1, 2))
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

    private val trackParallelRequestsPlugin2 = createApplicationPlugin(name = "trackParallelRequestsPlugin2") {
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

    fun installAllPlugins(app: Application)
    {
        app.apply {
            install(addProcessTimeHeaderPlugin)
            install(addUniqueIdPlugin)
            install(extractCommandPlugin)
            install(trackParallelRequestsPlugin)
            install(simulateProcessingTimePlugin)
            install(trackParallelRequestsPlugin2)
        }
    }
}