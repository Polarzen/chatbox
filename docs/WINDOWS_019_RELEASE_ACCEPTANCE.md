# Chatbox 0.1.9 Windows 发布验收

0.1.8 日常安装已通过 MSI 升级到 0.1.9，并从原开始菜单快捷方式完成两次冷启动。Compose 界面回归和 Windows 实机音频设备测试通过。安装版 A–G 全部通过。

## 0.1.8 安装版复现

从 Windows 开始菜单启动已安装的 0.1.8。长回复自动跟随至第 090 段，再向上滚动阅读旧段落。分别记录后续文本、图片和流结束到达前后的画面。下表列出同一张 988 × 754 像素窗口截图坐标系内、靠近消息区上沿的段落编号及其纵坐标。三次更新均将读者带离原段落。[01 · 自动跟随至 090](evidence/windows-019/01-018-following-090.jpg)

| 更新 | 更新前 | 更新后 |
| --- | --- | --- |
| 新增文本 | [02 · 第 029 段，y = 137](evidence/windows-019/02-018-reading-before-text.jpg) | [03 · 第 103 段，y = 127](evidence/windows-019/03-018-reading-after-text.jpg) |
| 图片到达 | [04 · 第 048 段，y = 138](evidence/windows-019/04-018-reading-before-image.jpg) | [05 · 第 103 段，y = 103](evidence/windows-019/05-018-reading-after-image.jpg) |
| 回复结束 | [06 · 第 048 段，y = 114](evidence/windows-019/06-018-reading-before-end.jpg) | [07 · 第 105 段，y = 129](evidence/windows-019/07-018-reading-after-end.jpg) |

## 0.1.9 Compose 界面回归

五项新增用例通过。使用临时配置和测试服务，未使用真实凭据。Compose 测量使用测试窗口内的坐标，消息视口高 328 像素。

- 阅读长回复时，第 076 段位于 y = 137。重命名当前会话、追加两批文本、显示图片和回复结束后，该段保持原位置。复制选区得到原先选中的文字。
- 点击“回到最新”后，后续文本到达时自动跟随至第 099 段。再次上滚，再用滚轮手动回到底部，下一批文本到达时自动跟随至第 108 段。随后图片和完成状态均在底部可见。
- 同名会话保持各自身份。后台会话收到回复和图片时，当前会话的选择和草稿保持正确。测试也覆盖跨配置使用相同会话 ID，以及调整窗口使内容完整可见。

另一次定向 Compose 测试从已经完成的长回复中段发送新问题。新问题和后续回复自动进入视口，“回到最新”隐藏，同一会话只新增一轮。

## Windows 构建与测试

| 项目 | 实测结果 |
| --- | --- |
| 源码 | [PR #5](https://github.com/Polarzen/chatbox/pull/5) · [构建提交 6714ccd09f5e1a485c12e682729aaf7adfa0a68c](https://github.com/Polarzen/chatbox/commit/6714ccd09f5e1a485c12e682729aaf7adfa0a68c) |
| PR CI | Windows、Android 两项作业均成功。[运行 45167455414](https://github.com/Polarzen/chatbox/actions/runs/45167455414) |
| 本地 Windows Gradle 构建 | 成功，耗时 3 分 1 秒。 |
| 本地全量测试 XML | 共 72 项，71 项通过，1 项需显式启用的音频设备测试跳过，失败及错误均为 0。全部 12 项 DesktopUiAcceptance 测试均已执行。 |
| 音频设备单项复验 | 在 Windows 实机显式启用音频设备测试，1 项通过，跳过、失败及错误均为 0。 |
| 安装包 | `Chatbox-0.1.9.msi`，55,517,184 字节 |
| SHA-256 | `69af357970b78ca85ad58b0195a05ae5770636c18f45f26b4204f6f4ebbcf2e9` |

## 0.1.9 安装版验收

在原日常配置上安装 0.1.9 MSI，再从原开始菜单快捷方式两次冷启动。升级前后均读回 8 个会话、44 条记录、7 个已发送图片引用和 3 个回复图片缓存。默认命名密钥及其加密凭据也通过了核对。原日常草稿状态无法独立核对。临时配置的 Compose 用例已验证草稿保留。

| 项目 | 安装版窗口观察 | 截图 |
| --- | --- | --- |
| A · 底部跟随 | 保持在底部时，可见末段从 090 依次推进至 099、108，随后显示回复图片。回复完成时图片仍在视野内。 | [090](evidence/windows-019/09-019-A-090.jpg) · [099](evidence/windows-019/10-019-A-099.jpg) · [108](evidence/windows-019/11-019-A-108.jpg) · [图片](evidence/windows-019/12-019-A-image.jpg) · [结束](evidence/windows-019/13-019-A-end.jpg) |
| B · 中段阅读 | 上滚后，首个完整可见段落为 030，y 约为 165 像素。重命名当前会话、两批文本到达、图片到达和流结束后，该段仍在同一位置。窗口显示“回到最新”，选中第 030 段中的 `Paragraph` 并复制后，系统剪贴板内容与选区完全一致。 | [更新前](evidence/windows-019/33-019-BG-repeat-before.jpg) · [重命名](evidence/windows-019/34-019-BG-repeat-renamed.jpg) · [文本一](evidence/windows-019/35-019-BG-repeat-text.jpg) · [文本二](evidence/windows-019/36-019-BG-repeat-text2.jpg) · [图片](evidence/windows-019/37-019-BG-repeat-image.jpg) · [结束](evidence/windows-019/38-019-BG-repeat-end.jpg) · [选区](evidence/windows-019/20-019-B-selected-word.jpg) |
| C · 恢复跟随 | 在 879 × 754 像素窗口点击“回到最新”后，新到文本自动跟随至 099。再次上滚，用滚轮回到底部，下一批文本自动跟随至 108。 | [点击前](evidence/windows-019/21-019-C-before-click.jpg) · [点击后](evidence/windows-019/22-019-C-click-bottom.jpg) · [099](evidence/windows-019/23-019-C-click-follow-099.jpg) · [滚动前](evidence/windows-019/24-019-C-before-manual.jpg) · [滚到底部](evidence/windows-019/25-019-C-manual-bottom.jpg) · [108](evidence/windows-019/26-019-C-manual-follow-108.jpg) |
| C · 紧凑窗口 | 从开始菜单启动，窗口设为 640 × 500 dp，144 DPI 下客户区为 960 × 750 像素。阅读已完成的测试会话第 076–080 段时，输入框接受了 `Compact unsent draft`。点击“回到最新”后，第 108 段与图片可见，按钮隐藏，草稿保留。截图已缩放。 | [阅读中输入草稿](evidence/windows-019/48-019-C-compact-reading-draft.jpg) · [返回底部后](evidence/windows-019/49-019-C-compact-bottom-draft.jpg) |
| D · 后台回复隔离 | 阅读另一会话时，后台追加两批文本、图片到达及流结束前后，当前会话选择、History 033 的纵坐标（140 像素）和未发送草稿均保持。 | [文本前](evidence/windows-019/42-019-D-before-late-text.jpg) · [文本至 099](evidence/windows-019/43-019-D-late-text-099.jpg) · [文本至 108](evidence/windows-019/44-019-D-late-text-108.jpg) · [图片前](evidence/windows-019/27-019-D-before.jpg) · [图片](evidence/windows-019/28-019-D-image.jpg) · [结束](evidence/windows-019/29-019-D-end-F-before.jpg) |
| E · 旧数据标题 | 侧栏按原顺序显示旧标题、两个同名会话、从首问生成的标题、“图片会话”和“新会话”。当前会话页头与选中行标题一致。 | [六个会话及页头](evidence/windows-019/08-019-E-title-defaults.jpg) |
| F · 同名会话 | 重命名未选中的第二个同名会话后，另一个同名会话保留原名。选择改名会话后，页头与删除提示均显示新名称。取消删除后该会话仍在列表中。完全退出进程、从开始菜单重开后，仍选中改名后的 `Only second twin`。六个测试会话的 ID、顺序、元数据及记录在重启前后按字节核对一致。 | [列表](evidence/windows-019/30-019-F-after-unselected-rename.jpg) · [页头](evidence/windows-019/31-019-F-selected-header.jpg) · [删除提示](evidence/windows-019/32-019-F-delete-prompt.jpg) · [取消后列表](evidence/windows-019/33-019-BG-repeat-before.jpg) · [退出前](evidence/windows-019/45-019-F-before-cold-exit.jpg) · [冷启动后](evidence/windows-019/46-019-F-cold-reopened.jpg) |
| G · 重命名流式会话 | 当前会话改名后仍使用原 ID，随后的文本及附件归入该会话。直到回复完成，阅读位置和未发送草稿均保持。空名称保存时出现校验提示。取消或按 Esc 关闭后名称保持。“恢复默认名称”清除手动名称，侧栏和页头恢复为 `Legacy field title`，再次冷启动后仍一致。 | [改名前](evidence/windows-019/33-019-BG-repeat-before.jpg) · [改名后](evidence/windows-019/34-019-BG-repeat-renamed.jpg) · [流结束](evidence/windows-019/38-019-BG-repeat-end.jpg) · [空名称](evidence/windows-019/39-019-G-blank.jpg) · [Esc](evidence/windows-019/40-019-G-escape.jpg) · [恢复默认名称](evidence/windows-019/41-019-G-restored-default.jpg) · [再次冷启动](evidence/windows-019/47-019-G-cold-restored.jpg) |

另用 0.1.8 打包 JAR 与已安装的 0.1.9 打包 JAR 读取同一份六会话测试数据。两版返回的六个会话 ID、排列顺序和显示名称逐项一致，覆盖 E 中的全部标题来源及同名行。
