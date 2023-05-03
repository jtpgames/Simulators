import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.jpmml.evaluator.Evaluator
import org.jpmml.evaluator.EvaluatorUtil
import org.jpmml.evaluator.LoadingModelEvaluatorBuilder
import org.jpmml.evaluator.OutputField
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalSerializationApi::class)
class Predictor(
    nameOfModel: String,
    nameOfMappingFile: String,
    private val featureNameMapping: Map<String, String> = mapOf(
        Pair("PR 1", "PR 1"),
        Pair("PR 3", "PR 3"),
        Pair("Request Type", "Request Type"),
        Pair("RPS", "RPS"),
        Pair("RPM", "RPM")
    )
)
{
    private val evaluator: Evaluator
    val knownRequestTypes: Map<String, Double>

    private val log = LoggerFactory.getLogger(javaClass)

    private val numberOfParallelRequestsPending: AtomicInteger = AtomicInteger(0)
    val startedCommands = mutableMapOf<String, StartedCommand>()

    private val requests_per_second: AtomicInteger = AtomicInteger(0)
    private val requests_per_minute: AtomicInteger = AtomicInteger(0)

    private val reset_requests_per_time: Job

    init
    {
        val pathToModel = "src/main/resources/$nameOfModel"
        val pathToMapping = "src/main/resources/$nameOfMappingFile"

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

        @OptIn(DelicateCoroutinesApi::class)
        reset_requests_per_time = GlobalScope.launch {
            launch {
                while (true)
                {
                    delay(1.seconds)
                    requests_per_second.set(0)
                }
            }

            launch {
                while (true)
                {
                    delay(1.minutes)
                    requests_per_minute.set(0)
                }
            }
        }
    }

    fun predictSleepTime(tid: String, command: String): Double
    {
        val requestTypeAsNumber = knownRequestTypes.getOrDefault(command, 0)

        val inputFields = evaluator.inputFields.map { it.name }
        val inputMap = mutableMapOf<String, Number>()
        if (inputFields.contains("PR 1"))
        {
            inputMap[featureNameMapping.getValue("PR 1")] = startedCommands.getOrDefault(tid, StartedCommand()).parallelCommandsStart
        }
        if (inputFields.contains("PR 3"))
        {
            inputMap[featureNameMapping.getValue("PR 3")] = startedCommands.getOrDefault(tid, StartedCommand()).parallelCommandsFinished
        }
        if (inputFields.contains("RPS"))
        {
            inputMap[featureNameMapping.getValue("RPS")] = requests_per_second.get()
        }
        if (inputFields.contains("RPM"))
        {
            inputMap[featureNameMapping.getValue("RPM")] = requests_per_minute.get()
        }
        if (inputFields.contains("Request Type"))
        {
            inputMap[featureNameMapping.getValue("Request Type")] = requestTypeAsNumber
        }

        log.debug("-> UID: $tid, X: $inputMap -")
        val y = predict(inputMap)
        log.debug("<- UID: $tid, y: $y -")

        return java.lang.Double.max(0.0, y)
    }

    fun addNewStartedCommand(tid: String, command: String)
    {
        val numberOfParallelRequestsAtBeginning = numberOfParallelRequestsPending.getAndIncrement()

        requests_per_second.incrementAndGet()
        requests_per_minute.incrementAndGet()

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

    private inline fun predict(inputMap: Map<String, Number>): Double
    {
        val prediction = evaluator.evaluate(inputMap)

        // Decoupling results from the JPMML-Evaluator runtime environment
        val results = EvaluatorUtil.decodeAll(prediction)

        return results.values.first() as Double
    }
}