package com.gcmodai.app.web

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.http.content.staticFiles
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE

fun startWebServer(
    port: Int = 8080,
    host: String = "0.0.0.0",
    /**
     * 热更新目录：存在且含 index.html 时优先从这里出静态资源。
     *
     * 为什么放在 web 模块而不是 App 里判断：静态资源路由注册在 WebServer 启动时，
     * 只有这里能决定「用 APK 内置的还是用热更新包里的」。传 null 就走内置资源。
     */
    hotUiDir: java.io.File? = null,
    module: suspend Application.() -> Unit
): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
    return embeddedServer(CIO, port = port, host = host, module = {
        install(Compression)
        install(CORS) {
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
            allowNonSimpleContentTypes = true
            anyHost()
            anyMethod()
        }
        install(SSE)
        install(DefaultHeaders)
        routing {
            val hot = hotUiDir?.takeIf { it.isDirectory && java.io.File(it, "index.html").isFile }
            if (hot != null) {
                staticFiles("/", hot) {
                    default("index.html")
                    enableAutoHeadResponse()
                    singlePageApplication()
                }
            } else {
                staticResources("/", "static") {
                    default("index.html")
                    enableAutoHeadResponse()
                    singlePageApplication()
                }
            }
        }
        module()
    })
}
