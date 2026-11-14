package com.gcmodai.ai.util

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class HttpException(
    message: String
) : RuntimeException(message)

/**
 * 上游把 HTML 页面当成 API 响应返回了。
 *
 * 这是网关层最常见的瞬时故障之一：维护页、Cloudflare/WAF 的拦截页、
 * CDN 回源失败页、登录跳转页，全都是 200 + `<!DOCTYPE html>`。
 * 对客户端来说它长得像一个解析错误，但本质上是「这次没拿到数据」，
 * 和网络抖动同类，应该重试而不是直接判死。
 *
 * 单独建一个类型而不是复用 [SerializationException]：只有「响应体看着像网页」
 * 才允许重试，真正契约不符的 JSON（字段缺失、类型错）不该反复烧钱。
 */
class UpstreamHtmlException(
    val snippet: String,
) : RuntimeException(buildString {
    append("上游返回了网页而不是 JSON（可能是网关维护页 / WAF 拦截页 / 登录跳转页）")
    if (snippet.isNotBlank()) {
        append("：")
        append(snippet)
    }
})

/** 响应体里出现这些信号，就认为上游回的是网页 */
private val HTML_MARKERS = listOf(
    "<!doctype html",
    "<html",
    "<head",
    "<body",
    "<script",
    "<meta ",
)

/**
 * 把一段响应体判成 HTML 页面则抛 [UpstreamHtmlException]，否则原样返回。
 *
 * 用处：把「解析失败」提前成「明确的、可重试的上游错误」，
 * 这样 agent 循环的重试策略能认出它，而不是让它一路冒成崩溃。
 */
fun String.requireJsonLike(): String {
    val head = trimStart().take(512).lowercase()
    if (head.isEmpty()) return this
    val looksHtml = HTML_MARKERS.any { head.contains(it) }
    if (looksHtml) {
        throw UpstreamHtmlException(trim().replace(Regex("\\s+"), " ").take(160))
    }
    return this
}

/**
 * 解析 JSON；失败时把 kotlinx 的解析异常翻译成更可判断的类型。
 *
 * 为什么需要翻译：`Unexpected JSON token at offset 11: Expected EOF after parsing,
 * but had h instead of at path: $` 这种消息暴露给用户毫无意义，
 * 而调用方又无法据此判断「这是瞬时的网关问题还是我真的写错了请求」。
 */
fun Json.parseJsonOrThrow(body: String): JsonElement {
    body.requireJsonLike()
    return try {
        parseToJsonElement(body)
    } catch (e: SerializationException) {
        // 走到这里说明不是典型 HTML，但依然不是合法 JSON：
        // 空 body、被截断的 body、纯文本错误页都在这一类
        throw UpstreamHtmlException(
            body.trim().replace(Regex("\\s+"), " ").take(160).ifBlank { "（空响应体）" }
        )
    }
}

fun JsonElement.parseErrorDetail(): HttpException {
    return when (this) {
        is JsonObject -> {
            // 尝试获取常见的错误字段
            val errorFields = listOf("error", "detail", "message", "description")

            // 查找第一个存在的错误字段
            val foundField = errorFields.firstOrNull { this[it] != null }

            if (foundField != null) {
                // 递归解析找到的字段值
                this[foundField]!!.parseErrorDetail()
            } else {
                // 如果没有找到任何错误字段，序列化整个对象
                HttpException(Json.encodeToString(JsonElement.serializer(), this))
            }
        }

        is JsonArray -> {
            if (this.isEmpty()) {
                HttpException("Unknown error: Empty JSON array")
            } else {
                // 递归解析数组的第一个元素
                this.first().parseErrorDetail()
            }
        }

        is JsonPrimitive -> {
            // 对于基本类型，直接使用其内容
            HttpException(this.jsonPrimitive.content)
        }

        else -> {
            // 其他情况，序列化整个元素
            HttpException(Json.encodeToString(JsonElement.serializer(), this))
        }
    }
}
