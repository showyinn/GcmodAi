package com.gcmodai.app.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 文件落盘目录的契约测试。
 *
 * 这个 bug 的形态很隐蔽：加固结果被存进 UPLOAD 目录，
 * 提示语却写着「已保存到加固包」，用户去那里找不到任何东西，
 * 而文件其实在另一个目录里 —— 没有报错、没有日志异常，纯静默错位。
 *
 * 所以这里把「不同业务必须落到不同目录」这件事钉死。
 */
class FileFolderContractTest {

    @Test
    fun `harden artifacts do not share the upload folder`() {
        assertNotEquals(
            "加固产物必须与聊天上传分开目录，否则用户找不到",
            FileFolders.UPLOAD,
            FileFolders.HARDEN,
        )
    }

    @Test
    fun `both folders have stable names`() {
        // 这两个字符串是磁盘上的真实目录名，改名等于丢掉已有文件
        assertEquals("upload", FileFolders.UPLOAD)
        assertEquals("harden", FileFolders.HARDEN)
    }

    @Test
    fun `folder names are filesystem safe`() {
        listOf(FileFolders.UPLOAD, FileFolders.HARDEN).forEach { folder ->
            assertEquals("目录名不能含路径分隔符: $folder", -1, folder.indexOf('/'))
            assertEquals("目录名不能含反斜杠: $folder", -1, folder.indexOf('\\'))
            assertEquals("目录名不能是上跳路径: $folder", false, folder.contains(".."))
        }
    }
}
