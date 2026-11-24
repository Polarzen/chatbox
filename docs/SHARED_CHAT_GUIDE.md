# Windows 与 Android 接续同一段 AstrBot 对话

0.1.16 是跨端文字聊天初验包。电脑上的共享服务为两台设备保存同一份轮次记录，并以同一个 AstrBot 用户名和会话编号转发新的提问。日常设备的初验对象是 Windows PC 与 Redmi Turbo 3（Android 16 / HyperOS 3）。

## 一次性准备

1. 在 Windows 安装 MSI，在测试手机安装 APK。升级 Windows 前退出旧版 Chatbox，沿用原安装目录。已有 Android 安装若签名不一致，请换用测试设备或独立用户空间，避免卸载时清除已有记录。
2. 启动目标 AstrBot。在 WebUI 的 OpenAPI 设置中创建一把仅含 `chat` 权限的密钥。记下 Windows Chatbox 连接设置显示的**实际 AstrBot 基础地址**。Launcher 通常使用带端口的 `http://127.0.0.1`。这个地址供电脑上的共享服务转发聊天使用。
3. 解压随包的 `shared-service.zip`，在解压目录打开 PowerShell。准备可运行的 Python 3.10 或更高版本。以下命令建立当前 Windows 用户自己的数据库。将输入的上游地址与客户端档案对应。每个不同 AstrBot 实例或用户使用各自的数据库。

   ```powershell
   $store = Join-Path $env:LOCALAPPDATA 'ChatboxShared'
   New-Item -ItemType Directory -Force $store | Out-Null
   $db = Join-Path $store 'history.sqlite3'
   $astr = Read-Host '当前 AstrBot 基础地址'
   python .\service.py --db "$db" init --upstream "$astr" --launcher
   python .\service.py --db "$db" bootstrap
   python .\service.py --db "$db" serve
   ```

   上面是 Windows Launcher 的命令。若使用自部署 AstrBot，将首次初始化命令末尾的 `--launcher` 去掉。Launcher 模式从当前回环地址识别实例 UUID，之后按实例重新查找运行端口。记下 `bootstrap` 显示的一次性创建码。运行 `serve` 时在隐藏提示中输入聊天密钥，保持这个窗口运行。创建码过期可在另一个窗口重新执行 `bootstrap`。已有数据库重启时只运行 `serve`。如果确实有两个通往**同一** AstrBot 实例的地址，可在首次 `init` 时追加 `--alias "另一个基础地址"`。

4. 在同一局域网给电脑分配固定地址，并在路由器或局域网 DNS 添加 A 记录，让 **chatbox.home.arpa** 指向该地址。确保手机使用该 DNS，且 Wi-Fi 允许设备之间互访。在另一窗口进入解压目录运行以下命令，保持窗口打开。

   ```powershell
   .\caddy.exe run --config .\Caddyfile --adapter caddyfile
   ```

   若 Windows 防火墙要求授权，只为专用网络放行 Caddy 的 TCP 8443。服务本身仅监听电脑回环端口 8765。共享服务、Caddy、AstrBot 及它使用的模型都需要保持可用。服务端可脱离 Windows Chatbox 窗口继续运行。

5. 将运行 Caddy 的 Windows 用户目录下 `%APPDATA%\Caddy\pki\authorities\local\root.crt` 通过 USB 复制到手机。可在电脑执行下式查看证书文件指纹，核对传入的文件。只传 `root.crt`，私钥保存在电脑上。在 HyperOS 设置中搜索「CA 证书」，选择「从存储设备安装」并选中该证书。应用允许在 **chatbox.home.arpa** 使用这枚用户安装的 CA。手机共享地址为 **https://chatbox.home.arpa:8443**。若网络已有可信 HTTPS 域名，可用指向同一回环服务的可信反向代理地址。

   ```powershell
   Get-FileHash "$env:APPDATA\Caddy\pki\authorities\local\root.crt" -Algorithm SHA256
   ```

## 两轮初验

1. 在 Windows Chatbox 的目标 AstrBot 档案中新建或选择测试会话，从正常输入框发送「本轮代号是青松47」，等回复结束。若仅在共享服务配置了普通密钥，也可以先把空会话设为共享，再从共享输入框发送。
2. 打开所选会话的共享入口。选「在另一台设备继续」，填写 **http://127.0.0.1:8765** 和创建码。确认导入范围后创建。进入「邀请」，生成一次性加入码。
3. 打开手机 Chatbox 的共享入口，选「加入已有会话」，填写 **https://chatbox.home.arpa:8443** 和加入码。核对手机上的原问题和电脑显示的完整回复。每个码有效十分钟，可使用一次。
4. 在手机共享输入框发送「我刚才给的代号是什么？」，等回复。核对回复包含「青松47」，电脑自动出现同一问答，两端顺序相同。记录设备、两端版本、模型和实际结果。

## 可同步的历史和异常处理

- 创建时导入发起设备当前选中会话已保存的提问、回复和历史附件提示。若有旧轮次，共享服务先向原 AstrBot 的会话列表核对用户名与会话编号；找不到原会话时会拒绝导入。仅核对到会话编号无法证明模型保留了多久以前的上下文。导入最多 10,000 轮，整个请求上限 8 MiB，超限会整体拒绝。旧设备独有、已删除或未保存的记录无法从 AstrBot 自动补回。文件内容留在本机。之后在共享输入框发送的文字由个人共享服务记账。普通本地模式仍可继续使用原有附件和草稿。
- 两端在线打开共享会话时自动刷新。手机断网后在电脑完成两轮，恢复网络或回到前台后按权威顺序补齐。连续两次发送相同文字应保留两轮。退出重开会重新核对访问权并读取历史。
- 同一房间当前有提问正在生成时，第二次发送会被拒绝或暂时禁用。请求结果未知时先等自动核对，不直接重复发送。共享服务若在上游回复结束前退出，下一次启动会保留已收到的正文，将该轮标记为未知并暂停这个房间的新生成。操作员确认 AstrBot 已停止处理该轮后，在服务目录运行以下命令。原记录会保留未知状态。

  ```powershell
  $db = Join-Path $env:LOCALAPPDATA 'ChatboxShared\history.sqlite3'
  python .\service.py --db "$db" unresolved
  python .\service.py --db "$db" resolve --room <上一行的房间编号> --id <轮次编号>
  ```

- 主人在「邀请」中可取消加入码，在「更多」中可撤销其他设备。客户端本地删除只影响本机；另一设备和服务端已保存的记录继续保留。服务端轮次正文保存在当前用户的 SQLite 文件中。备份时先正常停止服务并保存整个数据目录。
- 操作员可通过 `devices` 列出本地服务的设备，通过 `revoke-device 设备编号` 撤销指定设备，通过 `revoke-code` 撤销未用创建码。若创建后客户端未收到响应、主人设备丢失或已移除本机绑定，用 `rooms` 找到原房间，再用 `recover-owner --room 房间编号` 生成一次性恢复码。在新设备选「加入已有会话」兑换，原主人凭据和待用访客邀请码随即撤销，原访客继续有效。每条命令均使用上述同一个 `--db`。

## 现场记录

由瑶先在测试设备核验本节和前面的两轮对话，记录实际设备及系统构建号、包哈希、网络条件、双方看到的最后一轮和是否重复。手机短暂断网、杀进程重开、电脑重启（重新启动共享服务和 Caddy）、连续发送相同文本、同时发送、切换档案和撤销设备均需记录。Windows 本地旧会话与草稿也要打开检查。问题修复后用新包复验受影响项目。初验通过后，再将该版本及初验摘要交给 Polarzen 作日常设备复验。
