# Chatbox Windows 0.1.5 增量验收

2026-11-22，Windows 日常安装版 0.1.5，AstrBot 4.29.4。记录时间为 UTC+8。

## 发布包来源与升级

| 项目 | 验证结果 |
|---|---|
| 正式版 | [v0.1.5 发布页](https://github.com/Polarzen/chatbox/releases/tag/v0.1.5) · [MSI](https://github.com/Polarzen/chatbox/releases/download/v0.1.5/Chatbox-0.1.5.msi) · [SHA256SUMS.txt](https://github.com/Polarzen/chatbox/releases/download/v0.1.5/SHA256SUMS.txt) |
| 安装验收用候选件 | [PR #3 的 Windows + Android CI](https://github.com/Polarzen/chatbox/actions/runs/45005911848) 检出提交 `ef27182eb32c657e108e35bb814627285aa3ce79`。下载的 Windows MSI 为 55,017,472 字节，SHA-256 `E5F0508D74C6E90F1863CDA41953C53548EF137D7A4A2725B99CBB4EFDF48856`；与本机已安装缓存 MSI 字节及哈希完全相同。 |
| 发布后回验 | 从正式 Release 重新下载 MSI 并计算 SHA-256，与上述 PR 安装包、Windows Installer 本机缓存及随包校验文件一致。 |
| 其他 CI MSI | 主分支 CI 从相同源码树另行生成的 MSI SHA-256 为 `11FB20478AFEFE3BE49B791F38418AE2C516BA97F92B945D75DB094F3320AC35`。该 MSI 与 PR 候选件不同，未用于本机升级验收。 |
| 本机升级 | 从 0.1.4 通过 MSI 升级到 0.1.5，共享安装目录中的 178 个安装组件验证通过；0 个无关文件哈希不匹配。日常使用中的应用已验证可运行。 |

## 既有图片上传验收

0.1.4 已验证从桌面端上传 PNG 与 JPEG，且服务端附件内容与所选图片一致；样本及核对结果见[0.1.4 历史验收报告](https://github.com/Polarzen/chatbox/blob/0ba948c2482fa120ffeab3cd6461441ac0609419/docs/WINDOWS_V1_ACCEPTANCE.md)。

## 服务端贴图的接收与历史查看

通过现有 `meme_manager` 收到一张真实机器人贴图：512×512 WebP，76,270 字节。本机附件缓存字节与服务端附件一致。停止 AstrBot 服务端后冷启动客户端，仍可在历史记录中查看该图。

## 服务端图片生成

Chatbox Draw 插件于本轮记录时间 17:50:25 在 AstrBot 服务端热加载，运行中的插件文件哈希与仓库版本一致（SHA-256 `F574A45A3EC04AA5BAF0F5E685C3BDB0A85641DED593E72998893A44E229DE1E`）。该插件由服务端现有 `openai-responses` 提供方调用北京 DashScope 原生 `POST /api/v1/services/aigc/multimodal-generation/generation` 接口，请求 `qwen-image-3.0`；凭据及实际会话设置仅保存在实例中。插件配置和部署方式见 [Chatbox Draw 说明](https://github.com/Polarzen/chatbox/tree/f7d72946b29418e6ac08a52c951bff537a100144/integrations/astrbot_plugin_chatbox_draw)。

17:53:49，安装版发出的自然语言请求触发了 `chatbox_draw_image`。百炼于 17:54:46 返回结果。服务端请求 UUID 为 `4ab69ca3-4294-9e21-906a-d06617b77f47`，对应附件 UUID 为 `a9b2f844-0cef-4afd-a35e-f5eab4212486`。生成请求耗时 56.813 秒。生成的 PNG 为 1024×1024、964,855 字节，SHA-256 `48441f7d5f87a232e09f82820d4868f076ae8163015dcb6103a2aab7cf811354`；本机缓存与服务端附件的字节和哈希一致，图片保存在原会话中。实时查看和 19:08 所有 Chatbox 进程退出后冷启动查看均确认画面为黄围巾鲸鱼、紫色月亮和深蓝星空；冷启动后图片在完成认证前已显示。Chatbox Draw 的 16 项聚焦测试通过。

## Launcher 恢复与后续对话

先前同一会话中 Launcher 端口由 `22062` 变为 `23403`。本次启动检测到服务端地址改为端口 `18794`，Chatbox 使用 DPAPI 保存的凭据自动认证并恢复连接。随后针对新生成插画的普通追问已完成，回复中包含「黄」，且没有再次生成图片。

## 当前 Key 与权限

「当前 Key」显示已验证密钥的公开标记（标准 Key 显示部分前缀）；「密钥信息」对话框可查看标记，并打开 AstrBot WebUI OpenAPI 设置，按名称、前缀、创建时间、状态和权限范围核对密钥。常规文字和图片收发使用 `chat`、`file` 权限。`chat:admin` 按服务端 `admins_id` 授予对应身份全局管理员权限，应使用专用 Key。管理员专用 `/provider tts` 实际返回 `Please enter the index.`。

## Key 轮换

19:56，在当前实例中确认专用 Key 仅有 `chat`、`file`、`chat:admin` 三项权限。完全退出安装版，确认进程数为零后重新启动。Chatbox 用 DPAPI 保存的凭据自动认证，恢复了原会话。

为验证附件读取，临时移出已备份的插画缓存，然后在原消息上点击「重试获取图片」。安装版重新下载并显示了同一张 PNG，964,855 字节，SHA-256 与上文记录的服务端附件一致。随后在原会话发送管理员命令 `/provider tts`，收到 `Please enter the index.`。服务端专用 Key 的最近使用时间同步更新。

曾暴露的旧 Key 仍待所有者在 WebUI 撤销。
