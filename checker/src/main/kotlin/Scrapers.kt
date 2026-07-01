import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*

// ── One-off scrapers (Homestar Runner sitemap, AAA gas prices) ────────────────

suspend fun checkHomestarRunnerSitemap(client: HttpClient): Pair<String, String> {
    val link210 = "<a href=\"https://homestarrunner.com/sbemails/210-robots\" target=\"_blank\" rel=\"noopener\">Still on 210.</a>"
    return try {
        val xml = client.get("https://homestarrunner.com/sitemap.xml").bodyAsText()
        val locRegex = Regex("<loc>(https://homestarrunner\\.com/sbemails/211[^<]*)</loc>", RegexOption.IGNORE_CASE)
        val match = locRegex.find(xml)
        if (match != null) {
            val url = match.groupValues[1]
            "Yes." to "<a href=\"$url\" target=\"_blank\" rel=\"noopener\">It's here!</a>"
        } else {
            "No." to link210
        }
    } catch (e: Exception) {
        "No." to link210
    }
}

// Pull the national average out of the AAA gas-prices HTML. The value sits in server-rendered
// markup as "National Average …<p class="numb"> $3.901". Returns the raw price (e.g. 3.901) or
// null — the caller formats it for display and compares it against the threshold answers.
internal val GAS_AVG_REGEX = Regex("National Average[\\s\\S]{0,200}?\\\$\\s*([0-9]+\\.[0-9]{2,4})")

suspend fun fetchNationalGasAverage(client: HttpClient, url: String): Double? = try {
    val html = client.get(url) {
        header("User-Agent", "Mozilla/5.0 (compatible; is-whatever-out-yet/1.0; +https://iswhateveroutyet.com)")
    }.bodyAsText()
    GAS_AVG_REGEX.find(html)?.groupValues?.get(1)?.toDoubleOrNull()
} catch (e: Exception) {
    null
}
