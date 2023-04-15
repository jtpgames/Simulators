data class StartedCommand(
    val cmd: String = "",
    val parallelCommandsStart: Int = 0,
    var parallelCommandsFinished: Int = 0
)