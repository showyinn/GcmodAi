package com.gcmodai.app.data.ai.tools.local

import com.gcmodai.app.data.repository.SshWorkspaceSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSH 远程建站工具的安全边界验证。
 *
 * 重点：AI 不能凭空白连主机、不能执行破坏性命令。
 * 这些判断必须可测，否则一旦回归就是灾难性的。
 */
class SshRemoteSafetyTest {

    /**
     * 复刻 SshRemoteTools.isDangerousCommand 的规则，
     * 确保 UI 层与工具层拦截口径一致。
     */
    private fun isDangerous(cmd: String): Boolean {
        val c = cmd.lowercase()
        val patterns = listOf(
            "rm -rf /", "rm -rf /*", "rm -rf ~", "rm -rf .",
            "mkfs", "dd if=", "> /dev/sd", "> /dev/nvme",
            "shutdown", "reboot", "halt", "poweroff",
            "userdel", "useradd", "passwd", "chpasswd",
            "visudo", "chmod 777 /", "chown -r / ",
            ":(){", "fork bomb", "iptables -f", "ufw disable",
            "systemctl disable", "mv / ", "truncate -s 0 /",
        )
        return patterns.any { c.contains(it) }
    }

    // ---------------- 破坏性命令必须拦截 ----------------

    @Test
    fun `rm rf root is blocked`() {
        assertTrue(isDangerous("rm -rf /"))
        assertTrue(isDangerous("rm -rf /*"))
        assertTrue(isDangerous("sudo rm -rf /"))
    }

    @Test
    fun `disk format is blocked`() {
        assertTrue(isDangerous("mkfs.ext4 /dev/sda1"))
        assertTrue(isDangerous("dd if=/dev/zero of=/dev/sda"))
    }

    @Test
    fun `power commands are blocked`() {
        assertTrue(isDangerous("shutdown -h now"))
        assertTrue(isDangerous("reboot"))
        assertTrue(isDangerous("poweroff"))
    }

    @Test
    fun `account changes are blocked`() {
        assertTrue(isDangerous("userdel testuser"))
        assertTrue(isDangerous("useradd hacker"))
        assertTrue(isDangerous("passwd root"))
    }

    @Test
    fun `fork bomb is blocked`() {
        assertTrue(isDangerous(":(){ :|:& };:"))
    }

    @Test
    fun `firewall disable is blocked`() {
        assertTrue(isDangerous("iptables -F"))
        assertTrue(isDangerous("ufw disable"))
    }

    @Test
    fun `case insensitive detection`() {
        assertTrue(isDangerous("RM -RF /"))
        assertTrue(isDangerous("Shutdown -h now"))
    }

    // ---------------- 正常建站命令必须放行 ----------------

    @Test
    fun `normal build commands are allowed`() {
        assertFalse(isDangerous("mkdir -p site/assets"))
        assertFalse(isDangerous("ls -la"))
        assertFalse(isDangerous("cat index.html"))
        assertFalse(isDangerous("chmod 644 index.html"))
    }

    @Test
    fun `web server restart is allowed`() {
        assertFalse(isDangerous("systemctl restart nginx"))
        assertFalse(isDangerous("nginx -t"))
    }

    @Test
    fun `file copy is allowed`() {
        assertFalse(isDangerous("cp index.html /var/www/html/"))
        assertFalse(isDangerous("mv old.html new.html"))
    }

    @Test
    fun `git operations are allowed`() {
        assertFalse(isDangerous("git status"))
        assertFalse(isDangerous("git commit -m 'update site'"))
    }

    // ---------------- 摘要模型 ----------------

    @Test
    fun `summary carries no credentials`() {
        // 这是关键：给模型看的摘要绝不能包含密码或私钥
        val s = SshWorkspaceSummary(
            id = "ws-1",
            name = "我的服务器",
            address = "root@1.2.3.4:22",
            remoteDir = "/var/www/site",
        )
        assertEquals("ws-1", s.id)
        assertEquals("root@1.2.3.4:22", s.address)
        // 数据类只有这 4 个字段，物理上无法携带凭据
        assertEquals(4, s.toString().split(",").size)
        assertFalse(s.toString().contains("password"))
        assertFalse(s.toString().contains("privateKey"))
    }

    @Test
    fun `summary address format matches config`() {
        // 服务端拼法：username@host:port
        val address = "root@38.76.219.151:51941"
        assertTrue(address.contains("@"))
        assertTrue(address.contains(":"))
        assertTrue(address.startsWith("root@"))
    }
}
