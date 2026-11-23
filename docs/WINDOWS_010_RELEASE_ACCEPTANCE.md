# Chatbox 0.1.10 Windows 发布验收

官方 0.1.9 直接升级至 0.1.10 后，日常历史、图片缓存和已保存的密钥均通过核对。最终 MSI 安装版通过 A–H 窗口实测。

## 升级基线

远端基线为提交 `e805c873` 和 0.1.9 Release。使用官方 MSI 将 0.1.9 恢复到原注册安装路径，再分别通过日常环境和新建测试环境的快捷方式冷启动已安装的 0.1.9。[0.1.9 Release](https://github.com/Polarzen/chatbox/releases/tag/v0.1.9)

恢复后核对了日常历史和图片缓存，Windows DPAPI 能在当前用户上下文中解密已保存的活动密钥。搜索测试数据中的 47 个会话标识和 43 个手动标题均保留。

## 构建、测试与安装包

| 检查 | 实测结果 |
| --- | --- |
| Windows 本地构建 | Gradle `--no-daemon test desktopSmoke createDistributable packageMsi` 成功，耗时 2 分 46 秒。 |
| 自动化测试 | 共 86 项，全部通过，跳过 0 项。URL 测试覆盖附件、文本复制及搜索高亮。 |
| 打包程序烟测 | 单独以 `--smoke-test` 启动本次打包的可执行文件，退出码为 0，生成标记 `chatbox-smoke-ok`。 |
| MSI | `Chatbox-0.1.10.msi`，57,630,720 字节。 |
| MSI SHA-256 | `927ae81175e2b0d4c3ba8489107133e8ec2dca0c0e6ff0d653d68ed89fb2fe2c` |

此构建为正文中的 HTTP(S) 地址和服务地址标签应用了主题配色。

## 已安装的 0.1.9 覆盖升级

以管理员权限运行上述 MSI。Windows Installer 日志中 `JP_UPGRADABLE_FOUND` 识别出已安装的官方 0.1.9，原路径上的直接升级完成，退出码为 0。核对安装注册信息和实际可执行文件，两者的版本均为 0.1.10。通过原日常环境的开始菜单快捷方式冷启动 0.1.10 后正常关闭。

| 核对对象 | 升级前后实测结果 |
| --- | --- |
| 日常历史与图片 | 历史摘要一致，包含 8 个会话、44 条记录、3 个回复图片引用，图片缓存字节内容一致。 |
| 已保存的密钥 | 1 把命名密钥的元数据和密文均相同，Windows DPAPI 在当前用户上下文中成功解密已保存的活动密钥。 |
| 搜索测试数据 | 新建测试环境的 47 个会话全部保留，其中 43 个会话的手动标题均保留。 |
| 安装目录 | 将实际安装的 179 个文件与最终 MSI 管理安装解包所得文件逐一比对，SHA-256 全部一致。`app` 和 `runtime` 内额外文件数为 0。 |

## 安装版窗口实测

以下结果采自上述 MSI 安装后的 0.1.10 窗口。搜索用的 A 配置有 46 个会话、3,007 轮对话，B 配置有 1 个会话、3 轮对话。

搜索复测使用真实 Windows 键盘和鼠标输入。连续编辑的计时改用本地测试程序完成。发送输入前核对目标窗口句柄、进程 ID、可执行文件、前台窗口和指针位置，确保操作落在加载测试数据的已安装程序上。截图前检查目标窗口是否被遮挡，再按 Windows 桌面窗口管理器报告的窗口边界截取画面。

**A · 标题、正文和退出搜索。** 实测覆盖手动标题、旧版标题、按提问生成的标题及空标题。包含中文标点的查询按字面匹配助手回复。阅读窗口中部的第 048 段时，输入框有未发送的草稿。按 Ctrl+K 搜索、清空查询并按 Esc 后，仍可见原段落和草稿。[旧版标题](evidence/windows-010/search-legacy.png) · [中文标点命中](evidence/windows-010/search-chinese-assistant.png) · [搜索前](evidence/windows-010/search-middle-before.png) · [退出后](evidence/windows-010/search-middle-after.png)

**B · 同名会话和离线配置隔离。** 两个同名会话分别以 `TWIN-WEST` 命中用户提问、以 `TWIN-EAST` 命中助手回复，打开后会话和发言方都正确。A 配置中搜索 B 独有的词无结果。在两个服务端口均已拒绝连接（Winsock 10061）后，B 配置中搜索 `TWIN-WEST` 无结果，搜索 `B-ONLY-ORCHID` 可离线打开第 3 轮助手回复。[同名会话的提问](evidence/windows-010/search-twin-west.png) · [同名会话的回复](evidence/windows-010/search-twin-east.png) · [A 中排除 B](evidence/windows-010/search-a-excludes-b.png) · [B 中排除 A](evidence/windows-010/search-b-excludes-a.png) · [离线跳转](evidence/windows-010/search-offline-jump.png)

**C · 跳转到命中正文和网址。** 点击 `WRAP-LANTERN` 的结果后，自动换行的长回复直接定位到第 048 段。按字面搜索 `https://example.org/fixture/amber-index` 得到唯一结果，点击后正文中的网址高亮。浅色和深色主题下均能看到网址的强调色和下划线。跨行选中网址并复制，所得文本与完整网址逐字相同。复制整条正文后，文本也与原文一致。[长回复定位](evidence/windows-010/search-wrapped-jump.png) · [网址命中高亮](evidence/windows-010/search-url-jump.png) · [浅色网址](evidence/windows-010/search-url-light.png) · [深色网址](evidence/windows-010/search-url-dark.png) · [跨行选区](evidence/windows-010/search-url-selection.png)

**D · 查询耗时和窄窗口。** 在 A 配置中，从开始输入到截图确认结果可见，3,000 条、1 条和 0 条命中分别耗时 337、311 和 308 毫秒。计时含 210 毫秒的观察等待和截图，表示结果可见时间的上界。连续三次快速编辑共耗时 121 毫秒，从第一次输入起第 394 毫秒已确认最后一次查询的结果可见。窄窗口下输入区继续显示未发送的草稿。[3,000 条命中](evidence/windows-010/search-3000-hits.png) · [快速编辑结果](evidence/windows-010/search-rapid-edits.png) · [窄窗口草稿](evidence/windows-010/search-narrow-composer.png)

**E · 原生置顶、键盘焦点和对话框。** 开启置顶后，Chatbox 保持原生窗口样式 `WS_EX_TOPMOST`，并显示在记事本上方。记事本仍是前台窗口，实际收到键入的 `FINAL-FOCUS-010`。取消置顶后，记事本可以覆盖 Chatbox。置顶时打开文件选择器，选择器位于前台，所属窗口为 Chatbox，父窗口在选择器打开期间禁用。会话改名成功，取消删除后会话保留，设置可正常打开。最小化后点击实际任务栏按钮，还原了同一窗口句柄。[记事本接收输入时的窗口叠放](evidence/windows-010/window-pin-notepad-overlap.jpg) · [会话改名](evidence/windows-010/window-light-rename.jpg) · [删除确认框](evidence/windows-010/window-light-delete-confirm.jpg)

**F · 主题、控件与流式阅读。** 在宽窗口和窄窗口中检查了浅色、深色主题，以及设置和按钮的启用、禁用外观。WebUI 控件在两种主题下均显示名称。聊天正文中的 HTTP(S) 地址也按主题显示，选取和复制结果见 C 项。[浅色设置](evidence/windows-010/window-light-settings-disabled.jpg) · [深色设置](evidence/windows-010/window-dark-settings-disabled.jpg) · [浅色 WebUI 控件](evidence/windows-010/window-light-webui-control.jpg) · [深色 WebUI 控件](evidence/windows-010/window-dark-webui-control.jpg)

从搜索结果跳转后，第 045 段可见并高亮。第一批后续文本到达、切换主题、第二批文本到达、显示回复图片和回复结束时均保持该段阅读位置，输入框保留草稿。点击“回到最新”后，第 108 段和回复图片可见，复制所得正文与全部 108 段预期文本一致。[命中段落](evidence/windows-010/search-stream-jump.png) · [切换主题后的草稿和段落](evidence/windows-010/search-stream-theme.png) · [图片到达](evidence/windows-010/search-stream-image.png) · [回复结束](evidence/windows-010/search-stream-end.png) · [回到最新](evidence/windows-010/search-stream-latest.png)

**G · 原生标题栏和聊天区的不透明度。** 六个采集阶段均核对了最终安装版的目标窗口句柄。按同一物理区域截取 Windows 合成后的桌面画面，桌面坐标为 (81, 72) 至 (957, 1203)，截图尺寸为 876 × 1131 像素。取样坐标相对于截图左上角，标题栏为 (190, 7) 至 (350, 28)，聊天区为 (95, 755) 至 (265, 795)。无遮挡棋盘的明暗对比度为 160，将各处测得的对比度除以 160 即得透过率。[无遮挡参照](evidence/windows-010/window-reference.png) · [浅色主题 100%](evidence/windows-010/window-light100.png) · [深色主题 75%](evidence/windows-010/window-dark75.png)

| 窗口不透明度 | 标题栏对比度 | 聊天区对比度 | 标题栏透过率 | 聊天区透过率 |
| --- | --- | --- | --- | --- |
| [100%](evidence/windows-010/window-100.png) | 0 | 0 | 0% | 0% |
| 75% | 40 | 40 | 25% | 25% |
| [50%](evidence/windows-010/window-50.png) | 80 | 80 | 50% | 50% |

按住并拖动滑块从 75% 调至 50% 时，鼠标尚未松开，原生窗口的 alpha 值已从 191 降至 128。松开后保持 128。在 50% 下，输入、滚动、通过原生标题栏拖动窗口、用键盘调整大小、最大化和还原窗口、复制正文均成功。随后可恢复到 100%。[50% 下的滚动和输入](evidence/windows-010/window-50-scroll-input.jpg)

**H · 组合操作、冷启动和默认设置。** 同时启用置顶、深色主题和 75% 不透明度，在 B 配置中搜索并打开命中。确认原有两个进程均已退出后，通过已安装的快捷方式冷启动新窗口，原生置顶样式、alpha 值 191（75%）和深色主题均保留。切换到 A 配置后三项仍保留。恢复默认设置后，窗口为浅色、100% 不透明度，置顶关闭。[组合设置下的搜索跳转](evidence/windows-010/window-combined-jump.jpg) · [冷启动后](evidence/windows-010/window-restart-dark75-pin.jpg) · [切换到 A 配置后](evidence/windows-010/window-profile-a-dark75-pin.jpg) · [恢复默认设置后](evidence/windows-010/window-restored-defaults.jpg)

## 代码与 CI 记录

私有仓库 PR #6 中的代码提交 `27a69a78bb40e6652736b5d89dc3d721ee6a3ed5` 通过 `windows` 和 `android-regression` 两项 CI 作业。[PR #6](https://github.com/Polarzen/chatbox/pull/6) · [CI 运行记录](https://github.com/Polarzen/chatbox/actions/runs/45188094221)
