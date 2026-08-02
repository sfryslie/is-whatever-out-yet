import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.system.exitProcess

// Orchestration only — the item catalogue lives in Items.kt, check execution in Checks.kt,
// state tracking in StateTracking.kt, push in Push.kt, and data-file I/O in Output.kt.
fun main(): Unit = runBlocking {
    val dataDir = System.getenv("DATA_DIR") ?: "../data"

    // Previous run's output — drives `since`, push notifications, `updated`, and the commit message.
    val prevData = readPreviousData(dataDir)
    val prevById = prevData?.items?.associateBy { it.id } ?: emptyMap()

    val client = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
    }
    val today = LocalDate.now()

    val ctx = CheckContext.fromEnv(client, ITEMS, prevById, today)
    val baseResults = ITEMS.map { runCheck(it, ctx) }

    // The previous run is resolved against its own clock, not today's — otherwise a date-driven
    // release that flipped overnight looks like it was already out last run and never notifies.
    val prevClock = previousClock(prevData, today)
    val (results, transitions) = trackState(ITEMS, baseResults, prevById, today, prevClock)
    notifyTransitions(client, transitions)
    client.close()

    writeOutput(dataDir, prevData, results, today)

    exitProcess(0)  // intentional — kills Ktor CIO's background threads cleanly
}
