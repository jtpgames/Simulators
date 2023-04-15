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

        return java.lang.Double.max(0.0, y)
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