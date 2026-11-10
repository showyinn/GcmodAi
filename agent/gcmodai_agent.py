#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GcmodAi Windows 助手
====================

双击运行，手机上的 GcmodAi 就能控制这台电脑：执行命令、列目录、
读写文件、看系统信息。不需要装 Python、不需要配 SSH。

安全设计：
  * 每次启动生成随机口令，必须带上才能调用
  * 只监听本机网络，不主动外联、不上传任何数据
  * 破坏性命令（格式化、关机、删盘、改账号）一律拒绝
  * 关掉窗口即断开，不留后台服务

用法：
  1. 双击 GcmodAi-Agent.exe
  2. 窗口里会显示「地址」和「口令」
  3. 打开 App → 设置 → 工作区与建站 → Windows 电脑 → 添加，把这两项填进去

如果手机连不上，多半是 Windows 防火墙拦了：
  首次运行时系统会弹「是否允许访问网络」，必须点「允许」。
  没弹或点错了，用管理员命令行执行下面这条放行：
    netsh advfirewall firewall add rule name="GcmodAi Agent" dir=in action=allow protocol=TCP localport=8787
"""

import argparse
import json
import os
import platform
import secrets
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

AGENT_VERSION = "1.1"
DEFAULT_PORT = 8787
MAX_READ_BYTES = 512 * 1024
MAX_OUTPUT_CHARS = 20000
DEFAULT_TIMEOUT = 90

# 与 App 侧 ShellGuard 保持同一套拦截口径
BLOCKED = [
    "rm -rf /", "rm -rf /*", "rm -rf ~", "rm -fr /",
    "mkfs", "dd if=",
    "format ", "format.com", "del /f /s /q c:\\", "del /s /q c:\\",
    "shutdown", "reboot", "poweroff", "halt", "logoff",
    "userdel", "useradd", "passwd", "chpasswd",
    "net user", "net localgroup",
    "chmod 777 /", "chown -r / ",
    ":(){", "iptables -f", "ufw disable",
    "reg delete hklm", "bcdedit", "diskpart", "vssadmin delete",
    "cipher /w", "sdelete",
]

TOKEN = ""
START_TIME = time.time()


def is_dangerous(cmd):
    c = (cmd or "").lower()
    return any(p in c for p in BLOCKED)


def local_ips():
    """列出本机局域网 IPv4。"""
    ips = []
    try:
        host = socket.gethostname()
        for info in socket.getaddrinfo(host, None):
            ip = info[4][0]
            if ":" in ip or ip.startswith("127."):
                continue
            if ip not in ips:
                ips.append(ip)
    except Exception:
        pass
    if not ips:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ips.append(s.getsockname()[0])
            s.close()
        except Exception:
            pass
    return ips


def try_add_firewall_rule(port):
    """
    尝试自动放行防火墙。

    需要管理员权限；失败不报错，只提示手动处理——
    用户开着一个黑窗口时，弹 UAC 反而更让人困惑。
    """
    if os.name != "nt":
        return False
    cmd = (
        'netsh advfirewall firewall add rule name="GcmodAi Agent" '
        'dir=in action=allow protocol=TCP localport=%d' % port
    )
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, timeout=15,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        return r.returncode == 0
    except Exception:
        return False


class Handler(BaseHTTPRequestHandler):
    server_version = "GcmodAi-Agent/%s" % AGENT_VERSION
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        line = "[%s] %s" % (time.strftime("%H:%M:%S"), fmt % args)
        print(line, flush=True)

    # ---------- 响应 ----------

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _ok(self, data=None, message=""):
        self._json(200, {"success": True, "message": message, "data": data or {}})

    def _err(self, code, message):
        self._json(code, {"success": False, "message": message, "data": {}})

    def _body(self, limit=4 * 1024 * 1024):
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            return None
        if n <= 0:
            return {}
        if n > limit:
            return None
        try:
            return json.loads(self.rfile.read(n).decode("utf-8"))
        except Exception:
            return None

    def _auth(self):
        h = self.headers.get("Authorization", "")
        token = h[7:].strip() if h.lower().startswith("bearer ") else ""
        if token and token == TOKEN:
            return True
        self._err(401, "口令不正确，请核对 App 里填的口令")
        return False

    # ---------- GET ----------

    def do_GET(self):
        parsed = urlparse(self.path)
        path = unquote(parsed.path)
        qs = parse_qs(parsed.query)

        # ping 不需要口令：用来先确认地址是否通
        if path == "/ping":
            self._ok({
                "agent": AGENT_VERSION,
                "host": socket.gethostname(),
                "os": platform.platform(),
                "port": self.server.server_address[1],
                "uptimeSec": int(time.time() - START_TIME),
                "requiresToken": True,
            })
            return

        if not self._auth():
            return

        if path == "/info":
            self._ok({
                "hostname": socket.gethostname(),
                "os": platform.platform(),
                "user": os.environ.get("USERNAME") or os.environ.get("USER") or "",
                "cwd": os.getcwd(),
                "ips": local_ips(),
                "drives": self._drives(),
                "python": platform.python_version(),
            })
            return

        if path == "/ls":
            target = (qs.get("path", [""])[0] or "").strip() or os.getcwd()
            self._list(target)
            return

        if path == "/read":
            target = (qs.get("path", [""])[0] or "").strip()
            if not target:
                self._err(400, "缺少 path")
                return
            self._read(target)
            return

        self._err(404, "未知接口")

    # ---------- POST ----------

    def do_POST(self):
        path = urlparse(self.path).path
        if not self._auth():
            return
        payload = self._body()
        if payload is None:
            self._err(400, "请求体无效或过大")
            return
        if path == "/exec":
            self._exec(payload)
            return
        if path == "/write":
            self._write(payload)
            return
        self._err(404, "未知接口")

    # ---------- 动作 ----------

    def _list(self, target):
        try:
            items = []
            with os.scandir(target) as it:
                for e in it:
                    try:
                        st = e.stat()
                        items.append({
                            "name": e.name,
                            "dir": e.is_dir(),
                            "size": 0 if e.is_dir() else st.st_size,
                            "mtime": int(st.st_mtime),
                        })
                    except OSError:
                        pass
            items.sort(key=lambda x: (not x["dir"], x["name"].lower()))
            self._ok({"path": target, "items": items[:500], "truncated": len(items) > 500})
        except Exception as e:
            self._err(400, "列目录失败：%s" % e)

    def _read(self, target):
        try:
            size = os.path.getsize(target)
            if size > MAX_READ_BYTES:
                self._err(413, "文件 %.2f MB 太大，请用 pc_exec 的 type/findstr 分段查看"
                          % (size / 1048576.0))
                return
            with open(target, "rb") as f:
                raw = f.read()
            self._ok({
                "path": target,
                "size": len(raw),
                "content": raw.decode("utf-8", "replace"),
            })
        except Exception as e:
            self._err(400, "读取失败：%s" % e)

    def _exec(self, payload):
        cmd = str(payload.get("command") or "").strip()
        cwd = str(payload.get("cwd") or "").strip() or None
        try:
            timeout = int(payload.get("timeout") or DEFAULT_TIMEOUT)
        except (TypeError, ValueError):
            timeout = DEFAULT_TIMEOUT
        timeout = max(1, min(timeout, 600))

        if not cmd:
            self._err(400, "缺少 command")
            return
        if is_dangerous(cmd):
            self._err(403, "已拒绝：该命令包含不可回滚的破坏性操作，请手动执行")
            return

        started = time.time()
        try:
            proc = subprocess.run(
                cmd, shell=True, cwd=cwd, capture_output=True, timeout=timeout,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            out = (proc.stdout or b"").decode("utf-8", "replace")
            err = (proc.stderr or b"").decode("gbk", "replace")
            self._ok({
                "exitCode": proc.returncode,
                "stdout": out[:MAX_OUTPUT_CHARS],
                "stderr": err[:8000],
                "durationMs": int((time.time() - started) * 1000),
                "truncated": len(out) > MAX_OUTPUT_CHARS,
            })
        except subprocess.TimeoutExpired:
            self._err(504, "命令超时（%d 秒）" % timeout)
        except Exception as e:
            self._err(500, "执行失败：%s" % e)

    def _write(self, payload):
        target = str(payload.get("path") or "").strip()
        content = payload.get("content")
        if not target:
            self._err(400, "缺少 path")
            return
        if content is None:
            content = ""
        try:
            parent = os.path.dirname(target)
            if parent:
                os.makedirs(parent, exist_ok=True)
            with open(target, "wb") as f:
                f.write(str(content).encode("utf-8"))
            self._ok({"path": target, "size": os.path.getsize(target)})
        except Exception as e:
            self._err(400, "写入失败：%s" % e)

    def _drives(self):
        if os.name != "nt":
            return ["/"]
        return ["%s:\\" % c for c in "CDEFGHIJKLMNOPQRSTUVWXYZ" if os.path.exists("%s:\\" % c)]


def main():
    global TOKEN

    parser = argparse.ArgumentParser(description="GcmodAi Windows 助手")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--token", default="")
    parser.add_argument("--no-firewall", action="store_true")
    args = parser.parse_args()

    TOKEN = args.token.strip() or secrets.token_hex(8)

    fw_ok = False
    if not args.no_firewall:
        fw_ok = try_add_firewall_rule(args.port)

    try:
        server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    except OSError as e:
        print("端口 %d 被占用：%s" % (args.port, e))
        print("换一个端口再试：GcmodAi-Agent.exe --port 8788")
        input("按回车退出…")
        return

    ips = local_ips()
    print("=" * 64)
    print(" GcmodAi Windows 助手  已启动")
    print("=" * 64)
    print()
    print(" 把下面两项填进 App：设置 → 工作区与建站 → Windows 电脑")
    print()
    if ips:
        for ip in ips:
            print("   地址： http://%s:%d" % (ip, args.port))
    else:
        print("   地址： http://<本机IP>:%d （未探测到局域网 IP）" % args.port)
    print("   口令： %s" % TOKEN)
    print()
    print(" 手机要和这台电脑连同一个 Wi-Fi。")
    print(" 防火墙放行：%s" % ("已自动添加规则" if fw_ok else "未自动添加（首次运行请点「允许」）"))
    print(" 关闭本窗口即断开，不会留后台。")
    print("=" * 64)
    print()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。")
        server.shutdown()


if __name__ == "__main__":
    main()
