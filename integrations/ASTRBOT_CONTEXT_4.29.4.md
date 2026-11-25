# AstrBot 4.29.4 上下文适配

Chatbox 的自选上下文使用 AstrBot 4.29.4 内置 WebChat、local 运行器、完整工具定义模式和 OpenAI Chat Completions 提供商。升级 AstrBot 后须重新核对适配版本。未安装补丁时，可在 Chatbox 的会话菜单选择「跟随服务默认」继续使用原有聊天接口。

## 安装与还原

1. 备份 AstrBot 程序和该实例的数据目录，停止待更新实例。核对正在运行的 AstrBot 核心源码目录及版本。验收所用原始 `v4.29.4.zip` 的 SHA-256 为 `202b2478c6230ab542f110a831de663c718419e4f4fd6dc142ac737db8b89e35`。
2. 在含 `pyproject.toml` 和 `astrbot` 目录的核心源码根目录打开 PowerShell。将第一行改为交付补丁的实际位置，依次运行以下命令。若检查失败，保留现有文件并核对版本及本地改动。

   ```powershell
   $contextPatch = 'D:\Chatbox交付\integrations\patches\astrbot-v4.29.4-chatbox-context.patch'
   git apply --check -- "$contextPatch"
   if ($LASTEXITCODE -eq 0) { git apply -- "$contextPatch" }
   ```

3. 重启同一实例。新建隔离测试会话，发两轮带不同标记的问题。在「上下文」选最近 0 轮，只固定第一条问题，然后预览。核对可见的问答来源和当前问题；通过测试提供商的请求记录确认仅包含所固定的旧标记。扩大轮数后，旧轮次可以重新进入模型输入。
4. 还原时停止实例，在同一目录运行 `git apply --reverse --check -- "$contextPatch"`。成功后运行 `git apply --reverse -- "$contextPatch"` 并重启。应用和逆向应用操作针对核心源码文件。

## 请求与数据

- 两个端点都是 POST。预览使用 `/api/v1/chat/context/preview`，生成使用 `/api/v1/chat/context/send`。沿用普通聊天的鉴权和归属核查。自选模式当前接受文本问题。可用模型能力由运行器和提供商决定。
- 两个请求都携带 `username`、`session_id`、`message` 和 `context`。其中 `recent` 为 0 至 500，`pins` 最多 128 项，每项携带 `seq`、`role`、`question`、`answer`。可选 `hints` 最多 500 项，每项携带客户端自己持有的 `seq`、`question`、`answer`。已有原生检查点时，pin 和 hint 均可附 `checkpoint`。客户端可按缓存和请求体大小限制减少 hints。
- 纯文字轮次通过本会话唯一的完整问答确认。带附件或重复的问答需要可验证的原生检查点。预览按原生历史次序返回 `selected`。已核实的正文来自调用方传入的 pin 或 hint。缺少证明时，该项返回角色、检查点和空 `text`。hints 不参与模型历史的选择。
- `revision` 绑定发送身份、会话、问题、选择、已核实的信息和原生历史。生成请求另外附上该 revision、固定的 UUID `turn_id` 以及 `enable_streaming`。重用 turn_id 时，只有可核实的完整文本回复会重放；处理中、回复无法证明完整或复用 ID 发送不同内容均返回 409。
- 预览的 token 数为估算，计入所选历史、工具调用结果、基本系统指令、人格及其示例、当前问题、当前配置和人格启用的工具定义，以及已配置的输出预留。模型请求前还会复核运行时工具定义及实际消息；超限会返回错误，用户可减少最近轮数或移除固定项。模型服务仍可能执行自己的实际窗口限制。
- 固定某一轮的助手回复时，该轮完整工具调用与结果随回复进入模型输入。用户上传文件的提取正文只在选择该用户消息或完整问答时进入模型。对于归属无法核实的历史系统内容，单侧选择会提示改选完整问答。当前人格与系统指令在本次请求中保留，完整历史按原生顺序持久化。若先前已由默认压缩移除原生检查点，适配只会报告无法选取。
- 从打过补丁的 AstrBot 源码根目录运行交付的聚焦测试。使用装有 AstrBot 4.29.4 依赖的 Python，并将测试路径改为实际位置。测试使用独立的临时数据目录。

  ```powershell
  $env:PYTHONPATH = (Get-Location).Path
  python -B 'D:\Chatbox交付\integrations\tests\test_astrbot_context.py' -v
  ```
