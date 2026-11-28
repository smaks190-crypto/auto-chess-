package dev.nox.local

import android.content.Context
import android.content.Intent
import android.text.Html
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.net.HttpURLConnection
import java.util.Locale

/** Fixed HTTPS endpoints; never fetch URLs returned by a search result or model. */
internal object PublicInformation {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun json(url: String, allowed: () -> Boolean, key: String? = null): JSONObject {
        check(allowed())
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 7000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "NOX-Android/1.0 (personal assistant)")
            if (key != null) connection.setRequestProperty("X-Subscription-Token", key)
            check(connection.responseCode == 200) { "HTTP " + connection.responseCode }
            val bytes = connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    check(allowed())
                    val n = stream.read(buffer)
                    if (n < 0) break
                    check(output.size() + n <= 512 * 1024)
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            check(allowed())
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
    private fun plain(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()
        .replace(Regex("[\\s\\p{Cntrl}]+"), " ").trim()
    fun search(c: Context, cmd: ExtraCommand.Search, allowed: () -> Boolean): String {
        if (cmd.query.isBlank() || cmd.query.length > 300) return "Укажи поисковый запрос до 300 символов."
        val key = if (cmd.encyclopedia) null else SearchCredential.read(c)
        return if (key != null) {
            val data = json("https://api.search.brave.com/res/v1/web/search?q=" + enc(cmd.query) + "&count=3&search_lang=ru", allowed, key)
            val results = data.optJSONObject("web")?.optJSONArray("results")
            val items = (0 until minOf(results?.length() ?: 0, 3)).mapNotNull { i ->
                val item = results?.optJSONObject(i) ?: return@mapNotNull null
                val url = item.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return@mapNotNull null
                plain(item.optString("title")).take(140) + "\n" + plain(item.optString("description")).take(300) + "\n" + url.take(500)
            }
            if (items.isEmpty()) "Brave Search: результатов нет." else "Результаты Brave Search:\n" + items.joinToString("\n\n")
        } else {
            val data = json("https://ru.wikipedia.org/w/api.php?action=query&list=search&srlimit=3&format=json&srsearch=" + enc(cmd.query), allowed)
            val results = data.optJSONObject("query")?.optJSONArray("search")
            val items = (0 until minOf(results?.length() ?: 0, 3)).mapNotNull { i ->
                val item = results?.optJSONObject(i) ?: return@mapNotNull null
                val id = item.optLong("pageid", -1).takeIf { it > 0 } ?: return@mapNotNull null
                plain(item.optString("title")).take(140) + "\n" + plain(item.optString("snippet")).take(300) +
                    "\nhttps://ru.wikipedia.org/?curid=" + id
            }
            val intro = if (cmd.encyclopedia) "Википедия:" else "Поиск только по Википедии (полный веб-поиск требует ключ Brave в настройках):"
            intro + "\n" + items.joinToString("\n\n").ifBlank { "Результатов нет." }
        }
    }
    fun weather(cmd: ExtraCommand.Weather, allowed: () -> Boolean): String {
        if (cmd.city.isBlank()) return "Назови город: например, «погода завтра в Москве»."
        // The only location sent is the explicit place from the current request.
        val city = cmd.city.trim()
        val geo = json("https://geocoding-api.open-meteo.com/v1/search?name=" + enc(city) + "&count=5&language=ru&format=json", allowed)
        val matches = geo.optJSONArray("results") ?: return "Город не найден. Назови его в именительном падеже."
        if (matches.length() == 0) return "Город не найден."
        val first = matches.getJSONObject(0)
        val exact = (0 until matches.length()).map { matches.getJSONObject(it) }.filter {
            it.optString("name").equals(city, true)
        }
        if (exact.size > 1) return "Есть несколько городов: " + exact.joinToString("; ") {
            it.optString("name") + ", " + it.optString("admin1") + ", " + it.optString("country")
        } + ". Уточни название."
        val selected = exact.singleOrNull() ?: first
        val lat = selected.getDouble("latitude"); val lon = selected.getDouble("longitude")
        require(lat in -90.0..90.0 && lon in -180.0..180.0)
        val label = selected.getString("name") + ", " + selected.optString("country")
        val result = json("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon +
            "&current=temperature_2m,apparent_temperature,weather_code&daily=temperature_2m_min,temperature_2m_max,precipitation_probability_max,weather_code&timezone=auto&forecast_days=2", allowed)
        val daily = result.getJSONObject("daily")
        val i = if (cmd.tomorrow) 1 else 0
        val min = daily.getJSONArray("temperature_2m_min").getDouble(i)
        val max = daily.getJSONArray("temperature_2m_max").getDouble(i)
        val rain = daily.getJSONArray("precipitation_probability_max").getInt(i)
        val current = result.getJSONObject("current")
        val now = if (cmd.tomorrow) "" else "Сейчас " + current.getDouble("temperature_2m") + " °C, ощущается как " +
            current.getDouble("apparent_temperature") + " °C. "
        return label + " · " + daily.getJSONArray("time").getString(i) + ". " + now +
            "Прогноз от " + min + " до " + max + " °C, вероятность осадков до " + rain + "%. Источник: Open-Meteo."
    }
    fun openWeather(c: Context): String {
        if (!DeviceInteraction.unlocked(c)) return "Чтобы открыть Погоду Samsung, разблокируй телефон. Или назови город для прогноза."
        val intent = Intent("com.samsung.android.weather.intent.action.DETAIL").setPackage("com.sec.android.daemonapp")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(c.packageManager) == null) return "Приложение Погода Samsung недоступно."
        return runCatching {
            c.startActivity(intent)
            "Android получил запрос открыть Погоду Samsung. Содержимое прогноза ещё не прочитано."
        }.getOrElse { "Не удалось открыть Погоду Samsung." }
    }
}
