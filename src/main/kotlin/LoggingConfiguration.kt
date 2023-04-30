import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths

private var auditLogger: Logger? = null

fun configureLogging(applicationName: String)
{
    val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
    val jc = JoranConfigurator()
    jc.context = ctx
    ctx.reset() // override default configuration

    // inject the name of the current application as "application-name"
    // property of the LoggerContext
    // inject the name of the current application as "application-name"
    // property of the LoggerContext
    ctx.putProperty("application-name", applicationName)
    jc.doConfigure(object {}.javaClass.getResource("logback.xml"))

    // HACK: remove the log file implicitly created by the LoggerFactory.getILoggerFactory() call
    Files.deleteIfExists(Paths.get("application-name_IS_UNDEFINED_simulation.log"))

    auditLogger = LoggerFactory.getLogger("Audit")
}

fun logInfo(tid: String, msg: String)
{
    auditLogger?.info("UID: $tid, $msg")
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