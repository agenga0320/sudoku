package com.example.sudoku

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI services the player can choose from. All are called over plain HTTP.
 * Claude has its own API; the others speak the OpenAI chat-completions protocol.
 * The default model is only a starting point: the player can pick another from the service's list.
 */
enum class Provider(val label: String, val baseUrl: String, val defaultModel: String, val keyUrl: String) {
    CLAUDE("Claude (Anthropic)", "https://api.anthropic.com/v1", "claude-opus-5-5", "https://platform.claude.com/"),
    OPENAI("ChatGPT (OpenAI)", "https://api.openai.com/v1", "gpt-5.6", "https://platform.openai.com/api-keys"),
    GEMINI(
        "Gemini (Google)",
        "https://generativelanguage.googleapis.com/v1beta/openai",
        "gemini-flash-latest",
        "https://aistudio.google.com/apikey",
    ),
    GROK("Grok (xAI)", "https://api.x.ai/v1", "grok-4.7", "https://console.x.ai/"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com", "deepseek-v4-pro", "https://platform.deepseek.com/api_keys"),
    MISTRAL("Mistral", "https://api.mistral.ai/v1", "mistral-large-latest", "https://console.mistral.ai/api-keys"),
}

/**
 * Asks an AI how to think about the next step, without giving the answer away.
 * The app hands over the candidates it computed itself, so the model only has to reason,
 * and every hint is checked against the solution before the player sees it.
 */
object Coach {
    private const val ATTEMPTS = 2

    /** Which service to ask, with the player's key for it. */
    class Service(val provider: Provider, val apiKey: String, val model: String)

    /** Three increasingly detailed hints, plus the cell and digit they lead to (never shown). */
    class Hint(val levels: List<String>, val cell: Int, val digit: Int)

    /** The service rejected the API key. */
    class BadKeyException : Exception()

    private val SYSTEM_PROMPT = """
        你是數獨教練，任務是教玩家「怎麼想」，不是告訴答案。
        座標說明：r 是由上往下數的第幾橫排，c 是由左往右數的第幾直行，例如 r2c5 是第 2 橫排第 5 直行。
        九宮格由左上到右下編號 1 到 9。跟玩家說明時請用「第 N 橫排」「第 N 直行」「第 N 宮」，不要用 r、c 代號。

        找出盤面上「下一個能靠邏輯確定」的格子，選最簡單、最容易看出來的那一個。
        只能根據提供的候選數字推理，不要自己猜；玩家填的數字一律當作正確。
        任何說明裡都不可以說出任何格子該填的數字。

        嚴格依照下列格式回答，用繁體中文，不要加其他內容：
        [1] 一句話，只說該看哪一橫排、直行或宮。
        [2] 一兩句話，說要用什麼觀察角度或技巧，不提該填的數字。
        [3] 一步步帶玩家推理，直到可以確定某一格，但最後一步留給玩家自己完成，不要說出答案。
        [CHECK] r<橫排>c<直行>=<數字>（這一行玩家看不到，只給程式檢查你的推理用）
    """.trimIndent()

    /** Digits that do not yet appear among the peers of empty cell [i]. */
    fun candidates(values: IntArray, i: Int): List<Int> =
        if (values[i] != 0) emptyList() else (1..9).filter { d -> Sudoku.peers[i].none { values[it] == d } }

    /** The board as the model sees it: the grid, then the candidates of every empty cell. */
    fun describe(game: Game, selected: Int): String = buildString {
        appendLine("盤面（. 是空格，[ ] 內是玩家填的數字，其餘是題目給的）：")
        for (r in 0 until 9) {
            val row = (0 until 9).joinToString(" ") { c ->
                val i = r * 9 + c
                val v = game.values[i]
                when {
                    v == 0 -> "."
                    game.isGiven(i) -> "$v"
                    else -> "[$v]"
                }
            }
            appendLine("r${r + 1}: $row")
        }
        appendLine()
        appendLine("每個空格的候選數字：")
        for (i in 0 until Sudoku.CELLS) {
            if (game.values[i] == 0) appendLine("${cellName(i)}: ${candidates(game.values, i).joinToString(" ")}")
        }
        if (selected >= 0 && game.values[selected] == 0) {
            appendLine()
            appendLine("玩家目前選取的格子是 ${cellName(selected)}，如果它就能推出來，優先從它講起。")
        }
    }

    private fun cellName(i: Int) = "r${i / 9 + 1}c${i % 9 + 1}"

    /** Reads a reply in the format SYSTEM_PROMPT asks for; returns null if anything is missing. */
    fun parse(text: String): Hint? {
        val marks = Regex("""\[(1|2|3|CHECK)]""").findAll(text).toList()
        val sections = marks.mapIndexed { k, m ->
            val end = marks.getOrNull(k + 1)?.range?.first ?: text.length
            m.groupValues[1] to text.substring(m.range.last + 1, end).trim()
        }.toMap()
        val levels = listOf("1", "2", "3").map { sections[it]?.takeIf(String::isNotEmpty) ?: return null }
        val check = Regex("""r([1-9])c([1-9])\s*=\s*([1-9])""").find(sections["CHECK"] ?: return null) ?: return null
        val (r, c, d) = check.destructured
        return Hint(levels, (r.toInt() - 1) * 9 + c.toInt() - 1, d.toInt())
    }

    /** True if the hint leads to an empty cell and the digit the solution really has there. */
    fun isSound(hint: Hint, game: Game) =
        game.values[hint.cell] == 0 && game.solution[hint.cell] == hint.digit

    /**
     * Asks for a hint about [game]; blocks, so call it off the main thread.
     * Returns null when no verified hint came back (the model reasoned wrongly, or the
     * player's entries contain a mistake). Throws [BadKeyException] for a rejected key,
     * IOException when the service can't be reached, and other exceptions for API errors.
     */
    fun ask(service: Service, game: Game, selected: Int): Hint? {
        val board = describe(game, selected)
        repeat(ATTEMPTS) {
            parse(complete(service, board))?.takeIf { isSound(it, game) }?.let { return it }
        }
        return null
    }

    /** The models the player's key can use, for choosing one in the settings. Blocks. */
    fun models(service: Service): List<String> {
        val data = request(service, "/models", null).getJSONArray("data")
        // Gemini lists its models as "models/<id>".
        return (0 until data.length()).map { data.getJSONObject(it).getString("id").removePrefix("models/") }.sorted()
    }

    private fun complete(service: Service, board: String): String {
        val user = JSONObject().put("role", "user").put("content", board)
        if (service.provider == Provider.CLAUDE) {
            val body = JSONObject()
                .put("model", service.model)
                .put("max_tokens", 16000)
                .put("system", SYSTEM_PROMPT)
                .put("messages", JSONArray().put(user))
            val content = request(service, "/messages", body).getJSONArray("content")
            // Thinking blocks come first; only the text blocks are the reply.
            return (0 until content.length()).map { content.getJSONObject(it) }
                .filter { it.optString("type") == "text" }
                .joinToString("") { it.getString("text") }
        }
        val system = JSONObject().put("role", "system").put("content", SYSTEM_PROMPT)
        val body = JSONObject().put("model", service.model).put("messages", JSONArray().put(system).put(user))
        return request(service, "/chat/completions", body)
            .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }

    /** Sends [body] as a POST, or a GET when it is null, and returns the JSON reply. */
    private fun request(service: Service, path: String, body: JSONObject?): JSONObject {
        val connection = URL(service.provider.baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            // Reasoning models can take a couple of minutes before the first byte arrives.
            connection.readTimeout = 300_000
            if (service.provider == Provider.CLAUDE) {
                connection.setRequestProperty("x-api-key", service.apiKey)
                connection.setRequestProperty("anthropic-version", "2026-10-21")
            } else {
                connection.setRequestProperty("Authorization", "Bearer ${service.apiKey}")
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            // Gemini and Grok answer a bad key with 400 instead of 401.
            if (code == 401 || code == 403 || (code == 400 && text.contains("api key", ignoreCase = true))) {
                throw BadKeyException()
            }
            if (code >= 400) throw Exception("HTTP $code\n${text.take(400)}")
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
