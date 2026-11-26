# 在 AstrBot 4.29.4 接收 WebChat 主动消息

Chat Box 的主动消息收件功能需要在运行 **AstrBot 的电脑**上配置一次。补丁给 WebChat 新保存的主动消息加投递标记，已关闭聊天窗口时也能保留消息。重新打开收件设备后，Chat Box 按原会话补收。已经通过当前对话流送出的内容继续由原来的聊天窗口接收。安装前的历史缺少投递标记，仍可在 AstrBot 对应会话中核对。

## Windows 安装

1. 用需要收件的 Windows 或安卓档案向该 AstrBot 实例发送一条普通消息，建立 WebChat 会话。记下 AstrBot 日志中这一轮的 `webchat!chatbox_…!会话ID`。中间的 `chatbox_…` 是发起该轮的客户端身份。两端各自在本地创建过会话时，客户端身份可能不同。经设备共享续聊的会话沿用原会话身份。
2. 备份实例的数据和程序，停止该 AstrBot 实例。在包含 `astrbot` 和 `pyproject.toml` 的 **AstrBot 4.29.4 源码目录**打开 PowerShell。把下面两个路径指向实际目录。Launcher 独立实例的运行数据目录可能与核心源码分开，`$data` 必须包含该实例的 `data_v4.db`。

   ```powershell
   $integration = 'D:\chatbox\integrations'
   $data = 'D:\AstrBot\data'
   $patch = Join-Path $integration 'patches\astrbot-v4.29.4-chatbox-inbox.patch'
   git apply --check -- "$patch"
   if ($LASTEXITCODE -eq 0) { git apply -- "$patch" }
   ```

   已安装 Chat Box 4.29.4 自选上下文补丁的实例可以叠加本补丁。检查失败时请核对版本和正在运行的源码位置，再决定如何处理本地修改。

3. 使用 Python 列出这台主机上的有效密钥 ID、密钥名称和创建过私聊会话的 Chat Box 身份。此操作只读 SQLite。按 AstrBot 面板中的密钥名称和第 1 步的日志核对所选项。密钥 ID 为 UUID。密钥本身的 `abk_` 字符串无需输入。

   ```powershell
   $configure = Join-Path $integration 'configure_chatbox_inbox.py'
   py -3 "$configure" "$data"
   py -3 "$configure" "$data" --grant '相应的密钥ID' --user 'chatbox_相应的32位身份'
   ```

   每个 Chat Box 身份使用哪一把密钥，就给那一对授权。同一把密钥可重复传 `--user`。共享服务代某个来源档案访问 AstrBot 时，也需要为该服务实际使用的密钥添加该来源身份。密钥须具有 `chat` 权限。对话仍可使用原密钥及当前会话。

4. 启动 AstrBot，打开升级后的 Chat Box。让原插件在没有请求进行时向第 1 步的同一 WebChat 会话发送一条带时间标记的文本。核对 Chat Box 中只出现一条独立消息。关闭客户端、再发一条并重新打开原会话，可验证离线补收。要测两端，请让两端打开同一条已共享会话。

## 授权和消息范围

- 授权保存在实例数据目录中的 `chatbox-inbox-grants.json`。键为 AstrBot 的非秘密密钥 ID，值为该密钥允许接收的 Chat Box 身份列表。管理员编辑或移除授权后，下一次读取即生效。
- 消息来源由 AstrBot WebChat 适配器的主动投递保存路径标记。接口同时核对调用密钥的聊天权限、主机授权和实际私聊会话归属。它只返回该会话已标记的正文。图片、语音、视频、文件和交互按钮显示类型提示。
- 安装后云端模型响应耗时仍由实际运行的模型、插件和网络决定。诊断一次漏收时，请对照插件的目标 UMO、AstrBot 是否报告保存失败、会话归属，以及客户端收件状态。发往 QQ 或另一个 WebChat 会话的消息会留在对应的目标会话。
- 补丁从 `platform_message_history` 中按稳定的记录 ID 分页，每页最多 50 条，并限制响应大小。极长的单条消息超过 256 KiB 的 JSON 编码正文时，会显示截断标记；完整内容保存在 AstrBot。清理 AstrBot 对应会话的历史也会影响尚未补收的记录。

## 检查与还原

在打过补丁的 AstrBot 核心源码目录验证 SQLite 查询、授权、分页和配置助手。测试创建临时数据库。

```powershell
py -3 (Join-Path $integration 'tests\test_chatbox_inbox.py') -v
```

要撤回某个身份的收件权限，从 `chatbox-inbox-grants.json` 对应列表移除该身份即可。要还原核心源码，请停止实例，在同一 AstrBot 目录执行以下命令，再重新启动。已保存的 WebChat 历史仍可由原版 AstrBot 读取。

```powershell
git apply --reverse --check -- "$patch"
if ($LASTEXITCODE -eq 0) { git apply --reverse -- "$patch" }
```
