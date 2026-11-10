import hashlib
import io
import os
import zipfile

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "exe-out")

README = """GcmodAi Windows 助手  使用说明
================================

【怎么用】
1. 把整个文件夹解压到任意位置（不要在压缩包里直接双击）
2. 双击「启动助手.bat」
3. 黑窗口里会显示两行：
       地址： http://192.168.x.x:8787
       口令： xxxxxxxxxxxxxxxx
   把这两行填进手机 App：设置 → 工作区与建站 → Windows 电脑 → 右下角 +
4. 手机和电脑要连同一个 Wi-Fi
5. 用完关掉黑窗口即可断开

【为什么用 bat 启动，而不是直接双击 exe】
Windows 的 SmartScreen 会拦截「从网上下载的、没有数字签名的 exe」，
弹「Windows 已保护你的电脑」。这跟病毒无关，只是没有签名证书。
启动器会先去掉文件的「来自网络」标记再运行，就不会被拦。

【如果你已经双击了 exe，看到蓝色提示】
点「更多信息」→ 点「仍要运行」即可，只需一次。
或者直接用「启动助手.bat」启动。

【手机连不上】
- 首次运行时 Windows 弹「是否允许访问网络」，必须点「允许」
- 电脑休眠会断开，唤醒后重新打开助手
- 地址里的 IP 变了（换 Wi-Fi 会变），重新看窗口里的地址
- 手动放行防火墙（管理员命令行）：
  netsh advfirewall firewall add rule name="GcmodAi Agent" dir=in action=allow protocol=TCP localport=8787

【安全说明】
- 每次启动随机生成口令，没口令无法调用
- 格式化、关机、删盘、改账号等破坏性命令一律拒绝
- 不主动联网、不上传任何数据
- 关掉窗口即断开，不装服务、后台无残留
"""


def main():
    with io.open(os.path.join(BASE, "使用说明.txt"), "w", encoding="utf-8") as f:
        f.write(README)

    zp = os.path.join(BASE, "GcmodAi-Agent.zip")
    with zipfile.ZipFile(zp, "w", zipfile.ZIP_DEFLATED) as z:
        for n in ("GcmodAi-Agent.exe", "启动助手.bat", "使用说明.txt"):
            z.write(os.path.join(BASE, n), n)

    size = os.path.getsize(zp)
    with open(zp, "rb") as f:
        h = hashlib.sha256(f.read()).hexdigest()
    with io.open(zp + ".sha256", "w", encoding="utf-8") as f:
        f.write(h + "  GcmodAi-Agent.zip\n")

    print("zip 大小 %.2f MB" % (size / 1048576.0))
    print("zip sha256", h)
    with zipfile.ZipFile(zp) as z:
        print("内含:", z.namelist())


if __name__ == "__main__":
    main()
