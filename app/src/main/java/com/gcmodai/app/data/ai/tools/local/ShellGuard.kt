package com.gcmodai.app.data.ai.tools.local

/**
 * 远程命令的安全闸门。
 *
 * SSH 远程建站与电脑控制都会执行 shell 命令，拦截规则必须一致——
 * 两处各写一份迟早会漂移，导致换个入口就能绕过。
 *
 * 拦截原则：**不可回滚的破坏性操作**一律拒绝，让用户自己手动做。
 * 正常的开发运维命令（ls / cat / git / nginx -t / 重启服务）全部放行。
 */
internal object ShellGuard {

    /**
     * 明显破坏性或不可逆的命令片段。
     *
     * 都是小写匹配（调用前会把命令转小写），大小写混写也拦得住。
     */
    private val BLOCKED_FRAGMENTS = listOf(
        // 删根 / 删家目录 / 删当前目录
        "rm -rf /",
        "rm -rf /*",
        "rm -rf ~",
        "rm -rf .",
        "rm -fr /",
        // 格式化与覆写磁盘
        "mkfs",
        "dd if=",
        "> /dev/sd",
        "> /dev/nvme",
        "truncate -s 0 /",
        // 关机重启
        "shutdown",
        "reboot",
        "halt",
        "poweroff",
        "init 0",
        "init 6",
        // 账号与凭据
        "userdel",
        "useradd",
        "passwd",
        "chpasswd",
        "visudo",
        // 权限大范围放开
        "chmod 777 /",
        "chmod -r 777 /",
        "chown -r / ",
        // fork 炸弹
        ":(){",
        "fork bomb",
        // 防火墙整体关闭
        "iptables -f",
        "ufw disable",
        // 移动根目录
        "mv / ",
        // 关掉关键服务（容易把机器搞失联）
        "systemctl disable",
    )

    /**
     * 是否应当拒绝执行。
     *
     * @param command 原始命令
     */
    fun isDangerous(command: String): Boolean {
        val c = command.lowercase()
        return BLOCKED_FRAGMENTS.any { c.contains(it) }
    }

    /** 给模型的拒绝说明，顺带给出替代做法。 */
    fun rejectionMessage(command: String): String = buildString {
        append("已拒绝执行：该命令包含不可回滚的破坏性操作（$command）。")
        append("请把这一步交给用户手动确认；如果你只是想查看状态，")
        append("可以改用只读命令，例如 ls / cat / df -h / systemctl status。")
    }
}
