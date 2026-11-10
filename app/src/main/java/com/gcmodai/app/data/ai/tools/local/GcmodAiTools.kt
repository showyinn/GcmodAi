package com.gcmodai.app.data.ai.tools.local

import com.gcmodai.ai.core.InputSchema
import com.gcmodai.ai.core.Tool
import com.gcmodai.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * GcmodAi 官方工具集。
 *
 * 为什么单独建一组：仓库原有的本地工具（时间、剪贴板、日历…）默认只开了 `TimeInfo`，
 * 用户开箱即用时模型手上几乎没有能干活的工具，于是表现成「工具调用不工作」。
 * 这组工具**默认全开**，覆盖日常最常用的几类需求，且全部为零依赖的纯计算/纯编码实现，
 * 不联网、不读用户数据，因而可以安全地默认启用。
 *
 * 每个工具的 description 都写清楚「什么时候该用它」，因为模型选工具只看这段文字，
 * 写得含糊它就不会调用——这是工具调用失败最常见的原因之一。
 */
internal object GcmodAiTools {

    /** 全部官方工具，按注册顺序返回。 */
    fun all(): List<Tool> = listOf(
        buildCalculatorTool(),
        buildTextTool(),
        buildDateTimeTool(),
        buildHashTool(),
        buildJsonTool(),
        buildUnitConvertTool(),
        buildUrlCodecTool(),
        buildBase64Tool(),
    )

    /* ------------------------------ 1. 计算器 ------------------------------ */

    private fun buildCalculatorTool(): Tool = Tool(
        name = "calc_expression",
        description = """
            Evaluate a math expression and return the exact result.
            Use this whenever the user asks for arithmetic, percentages, unit prices, totals,
            or anything numeric that must be correct. Supports + - * / % ^ ( ), and the
            functions sqrt, abs, round, floor, ceil, min, max, pow, log, ln, sin, cos, tan.
            Always prefer this over doing mental math.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("expression") {
                        put("type", "string")
                        put("description", "The math expression, e.g. (128*0.85)+36/2")
                    }
                    putJsonObject("precision") {
                        put("type", "integer")
                        put("description", "Decimal digits to keep in the result, default 6")
                    }
                },
                required = listOf("expression"),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val expr = obj["expression"]?.jsonPrimitive?.content.orEmpty()
            val precision = obj["precision"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(0, 15) ?: 6
            val payload = buildJsonObject {
                put("expression", expr)
                runCatching { ExpressionEvaluator.evaluate(expr) }
                    .onSuccess { value ->
                        val rounded = BigDecimal(value)
                            .setScale(precision, RoundingMode.HALF_UP)
                            .stripTrailingZeros()
                            .toPlainString()
                        put("ok", true)
                        put("result", rounded)
                    }
                    .onFailure { e ->
                        put("ok", false)
                        put("error", e.message ?: "无法计算该表达式")
                    }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    /* ------------------------------ 2. 文本处理 ------------------------------ */

    private fun buildTextTool(): Tool = Tool(
        name = "text_process",
        description = """
            Process text: count characters/words/lines, convert case, trim, replace,
            reverse, or split. Use this when the user asks about text length, word count,
            batch case conversion, or simple string replacement.
            Operations: stats, upper, lower, title, trim, reverse, replace, sort_lines, dedupe_lines.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "The input text")
                    }
                    putJsonObject("operation") {
                        put("type", "string")
                        put("description", "One of: stats, upper, lower, title, trim, reverse, replace, sort_lines, dedupe_lines")
                    }
                    putJsonObject("search") {
                        put("type", "string")
                        put("description", "For replace: the text to find")
                    }
                    putJsonObject("replacement") {
                        put("type", "string")
                        put("description", "For replace: the replacement text")
                    }
                },
                required = listOf("text", "operation"),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val text = obj["text"]?.jsonPrimitive?.content.orEmpty()
            val op = obj["operation"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
            val search = obj["search"]?.jsonPrimitive?.content.orEmpty()
            val replacement = obj["replacement"]?.jsonPrimitive?.content.orEmpty()

            val payload = buildJsonObject {
                put("operation", op)
                put("input_length", text.length)
                when (op) {
                    "stats" -> {
                        val lines = if (text.isEmpty()) 0 else text.lines().size
                        // 中文按字符计，英文按空白切分，两者都给出避免歧义
                        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                        put("chars", text.length)
                        put("chars_no_space", text.count { !it.isWhitespace() })
                        put("words", words)
                        put("lines", lines)
                    }
                    "upper" -> put("result", text.uppercase())
                    "lower" -> put("result", text.lowercase())
                    "title" -> put("result", text.split(" ").joinToString(" ") { w ->
                        w.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }
                    })
                    "trim" -> put("result", text.trim())
                    "reverse" -> put("result", text.reversed())
                    "replace" -> {
                        if (search.isEmpty()) {
                            put("error", "replace 需要提供 search 参数")
                        } else {
                            put("result", text.replace(search, replacement))
                            put("occurrences", Regex(Regex.escape(search)).findAll(text).count())
                        }
                    }
                    "sort_lines" -> put("result", text.lines().sorted().joinToString("\n"))
                    "dedupe_lines" -> {
                        val before = text.lines()
                        val after = before.distinct()
                        put("result", after.joinToString("\n"))
                        put("removed", before.size - after.size)
                    }
                    else -> put("error", "未知操作: $op")
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    /* ------------------------------ 3. 日期计算 ------------------------------ */

    private fun buildDateTimeTool(): Tool = Tool(
        name = "date_calc",
        description = """
            Compute dates: days between two dates, add or subtract days/months/years from a date,
            or get the weekday of a date. Use this for deadline counting, age calculation,
            countdown, and any "how many days until" question.
            Operations: diff, add, weekday.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("operation") {
                        put("type", "string")
                        put("description", "One of: diff, add, weekday")
                    }
                    putJsonObject("start_date") {
                        put("type", "string")
                        put("description", "Start date in yyyy-MM-dd, defaults to today")
                    }
                    putJsonObject("end_date") {
                        put("type", "string")
                        put("description", "For diff: the other date in yyyy-MM-dd")
                    }
                    putJsonObject("days") {
                        put("type", "integer")
                        put("description", "For add: days to add, may be negative")
                    }
                    putJsonObject("months") {
                        put("type", "integer")
                        put("description", "For add: months to add, may be negative")
                    }
                    putJsonObject("years") {
                        put("type", "integer")
                        put("description", "For add: years to add, may be negative")
                    }
                },
                required = listOf("operation"),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val op = obj["operation"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
            val fmt = DateTimeFormatter.ISO_LOCAL_DATE
            fun parseDate(key: String, fallback: LocalDate? = null): LocalDate? {
                val raw = obj[key]?.jsonPrimitive?.content?.trim().orEmpty()
                if (raw.isEmpty()) return fallback
                return runCatching { LocalDate.parse(raw, fmt) }.getOrNull()
            }

            val payload = buildJsonObject {
                put("operation", op)
                when (op) {
                    "diff" -> {
                        val start = parseDate("start_date", LocalDate.now())
                        val end = parseDate("end_date")
                        if (start == null || end == null) {
                            put("error", "日期格式必须是 yyyy-MM-dd")
                        } else {
                            val days = java.time.temporal.ChronoUnit.DAYS.between(start, end)
                            put("start_date", start.toString())
                            put("end_date", end.toString())
                            put("days", days)
                            put("abs_days", kotlin.math.abs(days))
                            put("weeks", String.format("%.2f", days / 7.0))
                        }
                    }
                    "add" -> {
                        val start = parseDate("start_date", LocalDate.now())
                        if (start == null) {
                            put("error", "start_date 格式必须是 yyyy-MM-dd")
                        } else {
                            val days = obj["days"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                            val months = obj["months"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                            val years = obj["years"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                            val result = start.plusDays(days).plusMonths(months).plusYears(years)
                            put("start_date", start.toString())
                            put("result_date", result.toString())
                            put("weekday", result.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.SIMPLIFIED_CHINESE))
                        }
                    }
                    "weekday" -> {
                        val d = parseDate("start_date", LocalDate.now())
                        if (d == null) {
                            put("error", "日期格式必须是 yyyy-MM-dd")
                        } else {
                            put("date", d.toString())
                            put("weekday", d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.SIMPLIFIED_CHINESE))
                            put("weekday_index", d.dayOfWeek.value)
                            put("day_of_year", d.dayOfYear)
                        }
                    }
                    else -> put("error", "未知操作: $op")
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    /* ------------------------------ 4. 哈希 ------------------------------ */

    private fun buildHashTool(): Tool = Tool(
        name = "hash_text",
        description = """
            Compute MD5 / SHA-1 / SHA-256 / SHA-512 of a text, and its length in bytes.
            Use this when the user asks to verify a checksum, generate a digest,
            or compare hashes of two strings.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "Text to hash (UTF-8)")
                    }
                    putJsonObject("algorithm") {
                        put("type", "string")
                        put("description", "md5, sha1, sha256 (default), or sha512")
                    }
                },
                required = listOf("text"),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val text = obj["text"]?.jsonPrimitive?.content.orEmpty()
            val algoRaw = obj["algorithm"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
            val algo = when (algoRaw) {
                "", "sha256", "sha-256" -> "SHA-256"
                "md5" -> "MD5"
                "sha1", "sha-1" -> "SHA-1"
                "sha512", "sha-512" -> "SHA-512"
                else -> null
            }
            val payload = buildJsonObject {
                put("input_length", text.length)
                put("input_bytes", text.toByteArray(Charsets.UTF_8).size)
                if (algo == null) {
                    put("error", "不支持的算法: $algoRaw")
                } else {
                    val digest = MessageDigest.getInstance(algo).digest(text.toByteArray(Charsets.UTF_8))
                    put("algorithm", algo)
                    put("hash", digest.joinToString("") { b -> "%02x".format(b) })
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    /* ------------------------------ 5. JSON 格式化 ------------------------------ */

    private fun buildJsonTool(): Tool = Tool(
        name = "json_format",
        description = """
            Validate and pretty-print a JSON string, or report exactly where it is invalid.
            Use this when the user pastes JSON and asks whether it is correct,
            wants it formatted, or wants the parse error explained.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("json") {
                        put("type", "string")
                        put("description", "The JSON text to validate / format")
                    }
                },
                required = listOf("json"),
            )
        },
        execute = { input ->
            val raw = input.jsonObject["json"]?.jsonPrimitive?.content.orEmpty()
            val payload = buildJsonObject {
                runCatching {
                    val element = kotlinx.serialization.json.Json.parseToJsonElement(raw)
                    put("ok", true)
                    put("type", when (element) {
                        is kotlinx.serialization.json.JsonObject -> "object"
                        is kotlinx.serialization.json.JsonArray -> "array"
                        else -> "scalar"
                    })
                    put("formatted", kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(
                        kotlinx.serialization.json.JsonElement.serializer(), element
                    ))
                    if (element is kotlinx.serialization.json.JsonObject) {
                        put("key_count", element.size)
                    }
                    if (element is kotlinx.serialization.json.JsonArray) {
                        put("item_count", element.size)
                    }
                }.onFailure { e ->
                    put("ok", false)
                    put("error", e.message ?: "JSON 解析失败")
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    /* ------------------------------ 6. 单位换算 ------------------------------ */

    private fun buildUnitConvertTool(): Tool = Tool(
        name = "unit_convert",
        description = """
            Convert between units: length (m/km/cm/mm/mile/feet/inch), weight (kg/g/ton/lb/oz),
            temperature (c/f/k), data size (b/kb/mb/gb/tb), and time (s/min/hour/day).
            Use this for any unit conversion question instead of estimating.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("value") {
                        put("type", "number")
                        put("description", "The numeric value to convert")
                    }
                    putJsonObject("from") {
                        put("type", "string")
                        put("description", "Source unit, e.g. km, kg, c, mb, hour")
                    }
                    putJsonObject("to") {
                        put("type", "string")
                        put("description", "Target unit, e.g. mile, lb, f, gb, min")
                    }
                },
                required = listOf("value", "from", "to"),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val value = obj["value"]?.jsonPrimitive?.content?.toDoubleOrNull()
            val from = obj["from"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
            val to = obj["to"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
            val payload = buildJsonObject {
                put("from", from)
                put("to", to)
                if (value == null) {
                    put("error", "value 必须是数字")
                } else {
                    put("value", value)
                    runCatching { UnitConverter.convert(value, from, to) }
                        .onSuccess { r ->
                            put("ok", true)
                            put("result", BigDecimal(r).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
                        }
                        .onFailure { e ->
                            put("ok", false)
                            put("error", e.message ?: "换算失败")
                        }
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    /* ------------------------------ 7. URL 编解码 ------------------------------ */

    private fun buildUrlCodecTool(): Tool = Tool(
        name = "url_codec",
        description = """
            URL-encode or URL-decode a string. Use this when building a query string,
            debugging a link with %xx escapes, or preparing parameters for an HTTP request.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "The text to encode or decode")
                    }
                    putJsonObject("operation") {
                        put("type", "string")
                        put("description", "encode (default) or decode")
                    }
                },
                required = listOf("text"),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val text = obj["text"]?.jsonPrimitive?.content.orEmpty()
            val op = obj["operation"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty().ifEmpty { "encode" }
            val payload = buildJsonObject {
                put("operation", op)
                runCatching {
                    when (op) {
                        "decode" ->
                            put("result", java.net.URLDecoder.decode(text, "UTF-8"))
                        else ->
                            put("result", URLEncoder.encode(text, "UTF-8"))
                    }
                    put("ok", true)
                }.onFailure { e ->
                    put("ok", false)
                    put("error", e.message ?: "处理失败")
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    /* ------------------------------ 8. Base64 ------------------------------ */

    private fun buildBase64Tool(): Tool = Tool(
        name = "base64_codec",
        description = """
            Base64-encode or decode a text (UTF-8). Use this for API debugging,
            embedding binary-ish content in text, or decoding a Base64 payload.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "The text to encode or decode")
                    }
                    putJsonObject("operation") {
                        put("type", "string")
                        put("description", "encode (default) or decode")
                    }
                },
                required = listOf("text"),
            )
        },
        execute = { input ->
            val obj = input.jsonObject
            val text = obj["text"]?.jsonPrimitive?.content.orEmpty()
            val op = obj["operation"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty().ifEmpty { "encode" }
            val payload = buildJsonObject {
                put("operation", op)
                runCatching {
                    when (op) {
                        "decode" -> {
                            val bytes = Base64.getDecoder().decode(text.trim())
                            put("result", String(bytes, Charsets.UTF_8))
                            put("byte_length", bytes.size)
                        }
                        else -> {
                            val encoded = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
                            put("result", encoded)
                            put("byte_length", text.toByteArray(Charsets.UTF_8).size)
                        }
                    }
                    put("ok", true)
                }.onFailure { e ->
                    put("ok", false)
                    put("error", e.message ?: "处理失败，请确认输入是合法的 Base64")
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )
}

/* ============================== 计算器内核 ============================== */

/**
 * 递归下降表达式求值器。
 *
 * 刻意不引入任何第三方表达式库：工具代码要能在默认开启的前提下安全运行，
 * 依赖越少越好。文法：
 *
 *   expr   := term (('+' | '-') term)*
 *   term   := unary (('*' | '/' | '%') unary)*
 *   unary  := ('-' | '+') unary | power
 *   power  := primary ('^' unary)?
 *   primary:= number | '(' expr ')' | func '(' expr ')'
 */
internal class ExpressionEvaluator(private val src: String) {
    private var pos = 0

    companion object {
        fun evaluate(expression: String): Double {
            val cleaned = expression
                .replace("×", "*")
                .replace("÷", "/")
                .replace("（", "(")
                .replace("）", ")")
                .replace("，", ",")
                .trim()
            require(cleaned.isNotEmpty()) { "表达式为空" }
            val evaluator = ExpressionEvaluator(cleaned)
            val value = evaluator.parseExpr()
            evaluator.skipSpace()
            require(evaluator.pos == cleaned.length) {
                "无法识别的位置 ${evaluator.pos}: '${cleaned.substring(evaluator.pos).take(10)}'"
            }
            require(!value.isNaN() && !value.isInfinite()) { "计算结果无效" }
            return value
        }
    }

    private fun skipSpace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun peek(): Char? {
        skipSpace()
        return if (pos < src.length) src[pos] else null
    }

    private fun parseExpr(): Double {
        var left = parseTerm()
        while (true) {
            when (peek()) {
                '+' -> { pos++; left += parseTerm() }
                '-' -> { pos++; left -= parseTerm() }
                else -> return left
            }
        }
    }

    private fun parseTerm(): Double {
        var left = parseUnary()
        while (true) {
            when (peek()) {
                '*' -> { pos++; left *= parseUnary() }
                '/' -> {
                    pos++
                    val divisor = parseUnary()
                    require(divisor != 0.0) { "除数不能为 0" }
                    left /= divisor
                }
                '%' -> {
                    pos++
                    val divisor = parseUnary()
                    require(divisor != 0.0) { "取模的除数不能为 0" }
                    left %= divisor
                }
                else -> return left
            }
        }
    }

    private fun parseUnary(): Double {
        return when (peek()) {
            '-' -> { pos++; -parseUnary() }
            '+' -> { pos++; parseUnary() }
            else -> parsePower()
        }
    }

    private fun parsePower(): Double {
        val base = parsePrimary()
        return if (peek() == '^') {
            pos++
            // 右结合：2^3^2 = 2^(3^2)
            Math.pow(base, parseUnary())
        } else {
            base
        }
    }

    private fun parsePrimary(): Double {
        skipSpace()
        require(pos < src.length) { "表达式意外结束" }

        if (src[pos] == '(') {
            pos++
            val value = parseExpr()
            skipSpace()
            require(pos < src.length && src[pos] == ')') { "缺少右括号" }
            pos++
            return value
        }

        // 数字
        if (src[pos].isDigit() || src[pos] == '.') {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            val text = src.substring(start, pos)
            return text.toDoubleOrNull() ?: throw IllegalArgumentException("非法数字: $text")
        }

        // 函数名
        if (src[pos].isLetter()) {
            val start = pos
            while (pos < src.length && (src[pos].isLetter() || src[pos].isDigit() || src[pos] == '_')) pos++
            val name = src.substring(start, pos).lowercase()
            skipSpace()
            require(pos < src.length && src[pos] == '(') { "函数 $name 缺少左括号" }
            pos++

            // min/max 支持两个参数
            val args = mutableListOf<Double>()
            if (peek() != ')') {
                args.add(parseExpr())
                while (peek() == ',') {
                    pos++
                    args.add(parseExpr())
                }
            }
            skipSpace()
            require(pos < src.length && src[pos] == ')') { "函数 $name 缺少右括号" }
            pos++

            val a = args.getOrNull(0) ?: throw IllegalArgumentException("函数 $name 缺少参数")
            return when (name) {
                "sqrt" -> { require(a >= 0) { "sqrt 的参数不能为负" }; Math.sqrt(a) }
                "abs" -> Math.abs(a)
                "round" -> Math.round(a).toDouble()
                "floor" -> Math.floor(a)
                "ceil" -> Math.ceil(a)
                "log" -> Math.log10(a)
                "ln" -> Math.log(a)
                "sin" -> Math.sin(a)
                "cos" -> Math.cos(a)
                "tan" -> Math.tan(a)
                "pow" -> {
                    val b = args.getOrNull(1) ?: throw IllegalArgumentException("pow 需要两个参数")
                    Math.pow(a, b)
                }
                "min" -> args.min()
                "max" -> args.max()
                "pi" -> Math.PI
                "e" -> Math.E
                else -> throw IllegalArgumentException("未知函数: $name")
            }
        }

        throw IllegalArgumentException("无法解析的字符: '${src[pos]}'")
    }
}

/* ============================== 单位换算内核 ============================== */

internal object UnitConverter {

    /** 每个类别的单位到基准单位的倍率（温度单独处理）。 */
    private val groups: List<Map<String, Double>> = listOf(
        // 长度，基准米
        mapOf(
            "mm" to 0.001, "cm" to 0.01, "m" to 1.0, "km" to 1000.0,
            "inch" to 0.0254, "foot" to 0.3048, "feet" to 0.3048,
            "mile" to 1609.344, "yard" to 0.9144,
            "毫米" to 0.001, "厘米" to 0.01, "米" to 1.0, "千米" to 1000.0, "公里" to 1000.0,
        ),
        // 重量，基准千克
        mapOf(
            "g" to 0.001, "kg" to 1.0, "ton" to 1000.0, "t" to 1000.0,
            "lb" to 0.45359237, "oz" to 0.028349523125,
            "克" to 0.001, "千克" to 1.0, "公斤" to 1.0, "吨" to 1000.0,
        ),
        // 数据，基准字节
        mapOf(
            "b" to 1.0, "kb" to 1024.0, "mb" to 1024.0 * 1024, "gb" to 1024.0 * 1024 * 1024,
            "tb" to 1024.0 * 1024 * 1024 * 1024,
        ),
        // 时间，基准秒
        mapOf(
            "s" to 1.0, "sec" to 1.0, "min" to 60.0, "hour" to 3600.0, "h" to 3600.0,
            "day" to 86400.0, "week" to 604800.0,
            "秒" to 1.0, "分钟" to 60.0, "小时" to 3600.0, "天" to 86400.0,
        ),
    )

    private val temperatureUnits = setOf("c", "f", "k", "℃", "℉")

    fun convert(value: Double, from: String, to: String): Double {
        val f = normalize(from)
        val t = normalize(to)

        // 温度：先换到摄氏度，再换到目标
        if (f in temperatureUnits || t in temperatureUnits) {
            require(f in temperatureUnits && t in temperatureUnits) {
                "温度不能和长度/重量等单位互转"
            }
            val celsius = when (f) {
                "c", "℃" -> value
                "f", "℉" -> (value - 32) * 5 / 9
                else -> value - 273.15
            }
            return when (t) {
                "c", "℃" -> celsius
                "f", "℉" -> celsius * 9 / 5 + 32
                else -> celsius + 273.15
            }
        }

        val group = groups.firstOrNull { f in it && t in it }
            ?: throw IllegalArgumentException("无法在 $from 和 $to 之间换算（不在同一类别）")
        val base = value * group.getValue(f)
        return base / group.getValue(t)
    }

    private fun normalize(unit: String): String = unit.trim().lowercase()
}
