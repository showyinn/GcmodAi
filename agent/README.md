# Windows 助手（GcmodAi-Agent）

在 Windows 电脑上双击运行 `GcmodAi-Agent.exe`，手机上的 GcmodAi 即可控制这台电脑：
执行命令、列目录、读写文件、查看系统信息。**不需要装 Python，不需要配 SSH。**

## 构建 exe

```bash
pip install pyinstaller
python -m PyInstaller --noconfirm --onefile --console --name GcmodAi-Agent gcmodai_agent.py
# 产物：dist/GcmodAi-Agent.exe
```

## 打包分发

```bash
python package_agent.py
# 产物：exe-out/GcmodAi-Agent.zip（含 exe + 启动助手.bat + 使用说明.txt）
```

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/ping` | 探活（免口令） |
| GET | `/info` | 主机名 / 系统 / 用户 / 所有 IP / 盘符 |
| GET | `/ls?path=` | 列目录 |
| GET | `/read?path=` | 读文件 |
| POST | `/exec` | 执行 cmd 命令 |
| POST | `/write` | 写文件 |

所有请求需带 `Authorization: Bearer <口令>`（`/ping` 除外）。

## 安全

- 每次启动随机生成 16 位口令
- 破坏性命令一律 403（format / diskpart / shutdown / net user / bcdedit / 删盘 等）
- 只监听本机网络，不主动外联、不上传数据
- 关掉窗口即断开，不装服务、后台无残留