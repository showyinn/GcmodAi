package com.gcmodai.app.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附件类型判定的契约。
 *
 * 历史事故：白名单只放行文档/文本/代码，用户选 apk / zip / 7z / so 后
 * 只看到一句"不支持的文件类型"，文件根本没附上。现在改为"用户选了就放行"，
 * 这几个格式必须永远为 true，别再退回去。
 */
class ChatUtilTest {

    @Test
    fun `allows agc camera configs as text documents`() {
        assertTrue(isAllowedFileType("camera-config.agc", "application/octet-stream"))
        assertTrue(isAllowedFileType("CAMERA-CONFIG.AGC", "application/octet-stream"))
    }

    @Test
    fun `allows apk zip and 7z packages`() {
        assertTrue(isAllowedFileType("app-release.apk", "application/vnd.android.package-archive"))
        assertTrue(isAllowedFileType("backup.zip", "application/zip"))
        assertTrue(isAllowedFileType("data.7z", "application/x-7z-compressed"))
        // 很多机型把这三类都报成 octet-stream，也必须放行
        assertTrue(isAllowedFileType("app.apk", "application/octet-stream"))
        assertTrue(isAllowedFileType("data.7z", "application/octet-stream"))
    }

    @Test
    fun `allows native libraries and other binaries`() {
        assertTrue(isAllowedFileType("libtest.so", "application/octet-stream"))
        assertTrue(isAllowedFileType("dump.bin", "application/octet-stream"))
        assertTrue(isAllowedFileType("setup.exe", "application/octet-stream"))
    }

    @Test
    fun `allows archives with uncommon extensions`() {
        assertTrue(isAllowedFileType("logs.tar.gz", "application/gzip"))
        assertTrue(isAllowedFileType("bundle.aab", "application/octet-stream"))
        assertTrue(isAllowedFileType("package.apks", "application/octet-stream"))
    }

    @Test
    fun `unknown extension is allowed by design`() {
        // 刻意放行：文件由用户自选，模型只读不执行；拦截只会造成"上传不进去"
        assertTrue(isAllowedFileType("archive.unknown", "application/octet-stream"))
        assertTrue(isAllowedFileType("no-extension", "application/octet-stream"))
    }

    @Test
    fun `known extension list is documented and non-empty`() {
        assertTrue(isKnownFileExtension("libtest.so"))
        assertTrue(isKnownFileExtension("app.apk"))
        assertTrue(isKnownFileExtension("data.7z"))
        assertFalse(isKnownFileExtension("archive.unknown"))
    }
}
