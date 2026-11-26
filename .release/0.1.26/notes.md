Windows 和安卓可覆盖安装 0.1.26。已安装上一轮 0.1.26 测试包的用户可以继续使用，正式版沿用同一份 MSI 和已签名 APK。

### 本版更新

- 配对会话在回复生成时显示已收到的文字。连续文本片段合并刷新，减少界面更新和本地写入。
- 新增 WebChat 主动收件。插件向同一会话发出的主动消息可以在回到 Chat Box 时补收，重复拉取只显示一次。安卓应用被关闭时，重新打开后补收。
- 双端可从「主题」选择全色谱颜色、明暗模式和自定义聊天背景。编辑时可预览效果，虚化只作用于背景图片。已配对设备会同步这些设置。

### 下载与安装

- **Windows** 下载 `Chatbox-0.1.26.msi`。
- **安卓** 下载 `Chatbox-0.1.26.apk`。沿用旧版签名。
- **主动收件** 在运行 AstrBot 4.29.4 的电脑上解压 `Chatbox-AstrBot-4.29.4-Inbox.zip`，按包内中文说明安装适配并为原会话身份授权一次。补收范围从适配安装后新保存的主动消息开始。普通聊天可直接使用原有配置。
- **完全卸载 Windows 版** 可使用 `Chatbox-Uninstall-0.1.26.zip`。请先阅读包内的数据清理说明。
- 每个附件的 SHA-256 见 `SHA256SUMS.txt`。

[主动收件安装指南](https://github.com/Polarzen/chatbox/blob/v0.1.26/integrations/ASTRBOT_INBOX_4.29.4.md) · [双端构建与回归测试](https://github.com/Polarzen/chatbox/actions/runs/45786241250)
