# Chatbox Windows 0.1.7 发布验收

## 候选安装包

| 项目 | 结果 |
|---|---|
| 构建源码 | `11953f2f114852f4466f1e74a95db0a6dab04235` |
| 构建运行 | [Windows 分支 CI 45112103783](https://github.com/Polarzen/chatbox/actions/runs/45112103783) 直接构建该提交 |
| 安装包 | `Chatbox-0.1.7.msi`，55,418,880 字节 |
| MSI SHA-256 | `c1622a874510d58c9bf03a7a6e90c226be8a0ae74e52ed5ef89f41ea6580aac2` |

## 托管 Windows 原目录升级

托管运行器先安装 [v0.1.5 正式发布的 MSI](https://github.com/Polarzen/chatbox/releases/tag/v0.1.5)，再以相同的 `INSTALLDIR` 安装候选版。开始菜单快捷方式指向已安装的 `Chatbox.exe`，该文件的版本为 0.1.7，SHA-256 与候选包中的 EXE 一致。通过开始菜单连续启动两次，原有 DPAPI 凭据仍可使用，测试会话和图片引用均保留。托管环境不支持本轮需要的交互式窗口操作。[构建及升级验证运行](https://github.com/Polarzen/chatbox/actions/runs/45112103783)

## 本机构建与测试

本机构建在提交 `0317aec` 上执行，包含候选源码 `11953f2` 和后续测试夹具修复。夹具在延迟首个 SSE 事件前先刷新响应头。完整运行 `test desktopSmoke createDistributable packageMsi` 成功，53 项测试全部通过，其中包括 7 项显式启用的窗口测试，跳过 0 项。[Windows 与 Android CI](https://github.com/Polarzen/chatbox/actions/runs/45113070328)

## 日常 Windows 窗口验收

升级前先用 CI MSI 解包程序预检。下列宽度对照取自日常机器升级前的 0.1.6 安装版，以及原目录升级后从新开始菜单冷启动的 0.1.7 安装版。0.1.7 安装版截图的窗口记录均指向 `D:\chat box\Chatbox.exe`。

### 滚动条与布局对照

两版载入同一份含 48 个会话的 QA 数据并选中同一会话。窗口均为 1000 × 760 dp。在 Windows 100% 缩放（96 DPI）下，窗口外框为 1000 × 760 px，客户区为 984 × 721 px。在 150% 缩放（144 DPI）下，外框为 1500 × 1140 px，客户区为 1478 × 1084 px。分别在 PNG 的 y = 170 和 y = 255 处量测滑块。表中每个宽度链接到对应的完整窗口截图。

| 缩放 | 0.1.6 常态 | 0.1.6 悬停 | 0.1.7 安装版常态 | 0.1.7 安装版悬停 |
|---|---:|---:|---:|---:|
| 100% | [16 px](windows-017/before-100-normal.png) | [16 px](windows-017/before-100-hover.png) | [4 px](windows-017/installed-100-normal.png) | [6 px](windows-017/installed-100-hover.png) |
| 150% | [24 px](windows-017/before-150-normal.png) | [24 px](windows-017/before-150-hover.png) | [6 px](windows-017/installed-150-normal.png) | [9 px](windows-017/installed-150-hover.png) |

0.1.7 安装版滑块常态为灰色 `#A49FAF`，悬停时加深为 `#797284`。0.1.6 为滚动控件单独留出 24 dp。0.1.7 将 14 dp 宽的鼠标操作区叠在列表边缘，额外布局占宽为 0 dp。对照相同缩放下的窗口，会话行末菜单向右移动 24 dp，右侧聊天正文区域逐像素一致。

### 100% 和 150% 缩放下的窄窗口

日常安装的 0.1.6 与升级后的 0.1.7 安装版 均载入同一份 48 会话 QA 数据，选中「验收 24」。两个缩放档位的客户区宽均为 612 dp。缩窄时两版侧栏均自动收起，随后用顶栏按钮手动展开并截图。

| Windows 缩放 | 窗口外框 | 客户区 | PNG 采样行 | 0.1.6 常态 | 0.1.6 悬停 | 0.1.7 安装版常态 | 0.1.7 安装版悬停 |
|---|---|---|---|---:|---:|---:|---:|
| 100%（96 DPI） | 628 × 760 px | 612 × 721 px | y = 170 | [16 px](windows-017/before-narrow-100-normal.png) | [16 px](windows-017/before-narrow-100-hover.png) | [4 px](windows-017/installed-narrow-100-normal.png) | [6 px](windows-017/installed-narrow-100-hover.png) |
| 150%（144 DPI） | 940 × 1140 px | 918 × 1084 px | y = 255 | [约 24 px](windows-017/before-narrow-150-normal.png) | [约 24 px](windows-017/before-narrow-150-hover.png) | [约 6 px](windows-017/installed-narrow-150-normal.png) | [约 9 px](windows-017/installed-narrow-150-hover.png) |

按包含两端的 PNG 坐标量测，100% 下旧版常态和悬停均占 x = 209–224，0.1.7 安装版常态占 x = 234–237，悬停占 x = 233–238。150% 下如计入所有抗锯齿着色边缘，旧版常态与悬停均为 25 px，0.1.7 安装版分别为 7 px 和 10 px。

在 150% 缩放的 0.1.7 安装版窄窗口侧栏点击下箭头，首行从 01 变为 02。点击上箭头后，首行回到 01。用滚轮可浏览第 06 至 13 行。关闭侧栏后，聊天区仍显示「验收 24」和原有图片。

### 安装版长列表与同名会话

在 150% 缩放的安装版长列表中，从 14 dp 操作区的 x = 220 处按下鼠标，该点在滑块可见边缘外。拖到底部松开后，第 48 行完整可见。再拖回中段，第 20 至 28 行可见。滚动过程中右侧仍为「验收 24」，原正文、图片和「混合拖放草稿 017」保留。[第 48 行](windows-017/installed-bottom.png) · [拖回中段](windows-017/installed-middle-before.png)

点击第一条「中段同名会话」后，选中行原位高亮，右侧切换到第 25 行的回复。以 PNG 左上角为原点，量测点击前后的标题文字像素（绿色通道小于 160，坐标含两端）。两张图中，第 25 行标题均位于 x = 50–168、y = 718–736，第 26 行均位于 x = 50–168、y = 833–852。[点击前](windows-017/installed-middle-before.png) · [选中第 25 行](windows-017/installed-middle-selected.png)

两次切换后分别从 QA 磁盘记录读取 `currentSession`，并核对窗口正文及草稿。

| 同名行 | 磁盘中的当前会话 ID | 窗口正文及草稿 |
|---|---|---|
| 25 | `a313361a-cd72-52dc-b9d7-0769fa7d3900` | 显示「QA long 25 独立答复」。输入「第25行独立草稿」，从第 26 行切回后原草稿恢复。 |
| 26 | `fb1f4cb4-a464-5f21-b4e1-75badff8a930` | 显示「QA long 26 独立答复」，切入时草稿为空。 |

打开第 25 行末的「⋯」，选择删除后出现目标会话的确认框。点击取消，第 25 行保留。切回「验收 24」后，其原中文草稿「混合拖放草稿 017」恢复。[删除确认](windows-017/installed-delete-confirm.png) · [取消删除](windows-017/installed-delete-cancel.png)

此前的解包预检另打开过 12 条会话的独立 QA 配置。在默认高度下可见第 1 至第 8 条，第 8 条仅部分可见，列表右侧出现细滑块。

### 窗口高度与滑块显隐

分别从新开始菜单冷启动 0.1.7 安装版，载入 0 条和 3 条会话的独立 QA 配置。空列表显示「还没有对话」，短列表的三条记录均可见，其中一条长标题换为两行。两种列表均未显示滑块、轨道或上下按钮。[空列表](windows-017/installed-empty.png) · [三条会话](windows-017/installed-few.png)

在 Windows 150% 缩放（144 DPI）下，从新开始菜单启动安装版，载入含 7 条会话的 QA 数据。第 2、5 条的长标题均分为两行。窗口外框宽度固定为 1500 px，默认外框为 1500 × 1140 px，列表可完整容纳 7 条会话。

| 窗口外框 | 末行与滑块 |
|---|---|
| [1500 × 1009 px](windows-017/installed-threshold-short.png) | 拉矮窗口后，第 7 行底部被截，出现滑块。 |
| [1500 × 1073 px](windows-017/installed-threshold-fit.png) | 拉高窗口后，第 7 行完整可见，滑块隐藏。 |

连续两轮拉矮、拉高窗口，比较每轮在上述两个高度采到的截图。同一高度的两张整窗截图逐像素一致。两组 RGB 像素比对均为 0 个像素不同。[第二轮 1009 px](windows-017/installed-threshold-short-repeat.png) · [第二轮 1073 px](windows-017/installed-threshold-fit-repeat.png)

再对照两个高度，行标题和行末菜单位于 PNG 的 x = 28–329、y = 167–895 区域，该区域逐像素一致。

### 日常机器原目录升级及安装版复核

| 项目 | 当前状态 |
|---|---|
| 日常机器 0.1.6 原目录升级至 0.1.7 | 在原目录 `D:\chat box` 完成升级，`msiexec` 退出码为 0。Windows Installer 登记的 ProductCode 为 `{C9EF1C2E-3A39-3A84-9AA5-6AB51E20A1BC}`。 |
| 开始菜单及安装文件 | 快捷方式目标为 `D:\chat box\Chatbox.exe`，文件版本为 0.1.7。安装目录中 EXE 和应用 JAR 的 SHA-256 分别与 CI MSI 内相应文件一致。 |
| 个人数据 | 安装完成后、首次冷启动前，与升级前逐字节比对 64 份个人文件，内容均一致。包括 2 份凭据、3 份历史、1 份密钥元数据、3 份回复缓存及 55 份其他文件。 |
| 安装版冷启动 | 从新开始菜单冷启动后，QA 会话「验收 24」的原图片和右侧回复可见，顶栏显示「已连接」及原「测试」Key。[安装版窗口](windows-017/installed-100-normal.png) |
| 安装版会话操作 | 长列表滑块拖至底部并返回、同名会话原位切换、独立草稿恢复及取消删除均已在安装版验证。 |
| 资源管理器拖入文件 | 资源管理器已准备好三个真实测试文件。跨窗口拖放手势及安装版反馈仍待验证。 |

### 既有双图拖放证据

0.1.6 验收时，用户现场确认从资源管理器拖入 PNG 和 JPEG 后同时出现两个预览，并使用中文输入法写入草稿。安装版计时和会话历史确认两张图片依次上传、同一条消息完成且有回复。真实资源管理器中 TXT 的拒绝提示当时仍待确认，文件名和拒绝原因只在模拟 AWT 拖放中观察到。本轮未新增发送请求。[0.1.6 验收记录](WINDOWS_016_RELEASE_ACCEPTANCE.md)

## GitHub Release

正式发布仍待完成。发布后从 v0.1.7 Release 重新下载 MSI 和 `SHA256SUMS.txt`，比对文件大小及 SHA-256。
