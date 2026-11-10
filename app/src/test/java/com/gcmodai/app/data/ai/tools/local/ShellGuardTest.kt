package com.gcmodai.app.data.ai.tools.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电脑控制的安全闸门验证。
 *
 * AI 能控制整台电脑后，破坏性命令的拦截就是最后一道防线。
 * 这里把"必须拦"和"必须放"两类都钉死，避免以后改规则时把口子开大。
 */
class ShellGuardTest {

    // ---------------- 必须拦截 ----------------

    @Test
    fun `blocks rm rf root`() {
        assertTrue(ShellGuard.isDangerous("rm -rf /"))
        assertTrue(ShellGuard.isDangerous("rm -rf /*"))
        assertTrue(ShellGuard.isDangerous("sudo rm -rf /"))
    }

    @Test
    fun `blocks home and current dir wipe`() {
        assertTrue(ShellGuard.isDangerous("rm -rf ~"))
        assertTrue(ShellGuard.isDangerous("rm -rf ."))
    }

    @Test
    fun `blocks disk formatting`() {
        assertTrue(ShellGuard.isDangerous("mkfs.ext4 /dev/sda1"))
        assertTrue(ShellGuard.isDangerous("dd if=/dev/zero of=/dev/sda"))
    }

    @Test
    fun `blocks power commands`() {
        assertTrue(ShellGuard.isDangerous("shutdown -h now"))
        assertTrue(ShellGuard.isDangerous("reboot"))
        assertTrue(ShellGuard.isDangerous("poweroff"))
        assertTrue(ShellGuard.isDangerous("init 0"))
    }

    @Test
    fun `blocks account changes`() {
        assertTrue(ShellGuard.isDangerous("userdel alice"))
        assertTrue(ShellGuard.isDangerous("useradd bob"))
        assertTrue(ShellGuard.isDangerous("passwd root"))
    }

    @Test
    fun `blocks permission wide open`() {
        assertTrue(ShellGuard.isDangerous("chmod 777 /"))
        assertTrue(ShellGuard.isDangerous("chmod -R 777 /"))
    }

    @Test
    fun `blocks fork bomb`() {
        assertTrue(ShellGuard.isDangerous(":(){ :|:& };:"))
    }

    @Test
    fun `blocks firewall disable`() {
        assertTrue(ShellGuard.isDangerous("iptables -F"))
        assertTrue(ShellGuard.isDangerous("ufw disable"))
    }

    @Test
    fun `blocks disabling systemd services`() {
        // 误关关键服务会让机器失联，必须拦
        assertTrue(ShellGuard.isDangerous("systemctl disable sshd"))
    }

    @Test
    fun `case insensitive`() {
        assertTrue(ShellGuard.isDangerous("RM -RF /"))
        assertTrue(ShellGuard.isDangerous("Shutdown -h now"))
    }

    // ---------------- 必须放行 ----------------

    @Test
    fun `allows read only inspection`() {
        assertFalse(ShellGuard.isDangerous("ls -la /var/www"))
        assertFalse(ShellGuard.isDangerous("cat /etc/nginx/nginx.conf"))
        assertFalse(ShellGuard.isDangerous("df -h"))
        assertFalse(ShellGuard.isDangerous("free -h"))
        assertFalse(ShellGuard.isDangerous("ps aux | grep nginx"))
    }

    @Test
    fun `allows package install`() {
        assertFalse(ShellGuard.isDangerous("apt-get install -y nginx"))
        assertFalse(ShellGuard.isDangerous("yum install -y git"))
        assertFalse(ShellGuard.isDangerous("pip install requests"))
    }

    @Test
    fun `allows service management`() {
        assertFalse(ShellGuard.isDangerous("systemctl restart nginx"))
        assertFalse(ShellGuard.isDangerous("systemctl status nginx"))
        assertFalse(ShellGuard.isDangerous("nginx -t"))
    }

    @Test
    fun `allows building and git`() {
        assertFalse(ShellGuard.isDangerous("./gradlew build"))
        assertFalse(ShellGuard.isDangerous("npm install"))
        assertFalse(ShellGuard.isDangerous("git status"))
        assertFalse(ShellGuard.isDangerous("git commit -m 'x'"))
    }

    @Test
    fun `allows creating and moving project files`() {
        assertFalse(ShellGuard.isDangerous("mkdir -p /opt/app"))
        assertFalse(ShellGuard.isDangerous("cp -r src /opt/app/"))
        assertFalse(ShellGuard.isDangerous("mv build /opt/app/dist"))
        assertFalse(ShellGuard.isDangerous("rm -rf node_modules"))
    }

    // ---------------- 拒绝说明 ----------------

    @Test
    fun `rejection message is actionable`() {
        val msg = ShellGuard.rejectionMessage("rm -rf /")
        assertTrue(msg.contains("拒绝"))
        assertTrue("应说明为什么", msg.contains("不可回滚"))
        assertTrue("应给出替代做法", msg.contains("ls") || msg.contains("只读"))
    }
}
