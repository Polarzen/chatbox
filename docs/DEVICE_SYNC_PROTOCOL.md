# 设备同步协议 0.1.19

Windows 和 Android 客户端通过设备配对建立跨端连接。服务实例内仅将经 Windows 主人确认登记的档案纳入设备目录。手机确认现有及未来范围后加入。Windows 本机服务可复用旧共享数据库。下表的 `legacyToken` 用于核对原档案或房间的主人身份，旧凭据的授权仍限于原范围。原始草稿、附件文件、OpenAPI 密钥与管理员登录态不参与历史传输。日期用 Unix 毫秒，操作和设备编号用 UUID，JSON 使用 UTF-8。以下路径位于 `/v1/device/`。除 health、preview、join、prove 外均须设备 Bearer 凭据。

| 方法和路径 | 请求 | 回执 |
| --- | --- | --- |
| GET health | 无 | instance, version=1 |
| POST invite | Windows 主人；pcProfiles, pcSessions | code, expiresIn |
| POST preview | code | instance, pcProfiles, pcSessions, expiresIn |
| POST join | code, device, token, username, phoneProfiles, phoneSessions。手机先在本机保存随机设备编号和高熵凭据，再发出请求 | instance, device, owner=false, address |
| GET me | 无 | instance, device, owner, address。join 回执丢失时凭原编号和凭据核对 |
| POST endpoint | Windows 主人；address，手机可达的 HTTPS 地址 | address |
| POST prove | device, nonce，均为 UUID；无需传送凭据 | instance, device, address, proof。proof 是以 SHA-256(UTF-8(token)) 二进制摘要为 key 的 HMAC-SHA256 小写十六进制，明文为以换行连接的 chatbox-device-address-v1、instance、device、已登记 address、nonce，无末尾换行。验证绑定和目的地址后才向新地址发送凭据 |
| GET devices / POST revoke | 限主人；revoke 传 device | devices 数组，各项 device,name；撤销返回空对象 |
| GET profiles | 无 | profiles 数组，各项 profile,sourceId,base,username,name,originDevice,upstream,available |
| POST profile | sourceId,base,username,name；可选 upstream（Windows 本机已登记的路由 ID）、legacyToken（同实例原档案主人证明） | 同一档案对象。手机只能登记本安装的用户名和来源。未映射的来源保留可读记录，不携带既有密钥访问未知地址 |
| GET sessions | offset,limit，可选 revision | sessions,revision,more。每项 profile,sid,title,activity,total,imported,reason,originDevice。按 activity 降序、profile 和 sid 排序；快照改变返回 409 |
| POST session | profile,sid,title,activity,total，可选 legacyToken（原房间主人或须保持只读的 unverified 标记）。仅发原生已提交记录 | 目录项。已获证明的原房间保持原权威轮次；响应中的 profile/sid 是权威键 |
| POST import | profile,sid,start,turns；每项 id,question,answer,status | imported，当前连续前缀长度。冲突不覆盖原文 |
| GET turns / GET turn | profile,sid；分页传 after,limit；单轮传 id | turns,more 或单轮 seq,id,question,answer,status |
| POST start / POST send | profile,sid,id,question。start 使用新建端预先持久化的随机 UUID sid；send 使用原 sid。两端普通文字共用本操作 | profile,sid,turn。新建首发的目录项和首轮同一事务，提交后派发模型。相同操作重试返回原轮，不同内容或目标冲突返回 409 |
| GET operation | id，按当前设备查找已经提交的操作 | profile,sid,turn；未收到返回 404 |
| POST media-begin | profile,sid,id,question。仅档案来源设备可预留本机媒体轮次。首发使用持久化的 UUID sid | profile,sid,turn,dispatch。首次创建预留返回 `dispatch=true`，重复核对原预留返回 `false`。两者均须经来源安装的持久待发记录核对，仅成功将本机派发状态从未开始变为已开始的执行者可调用 AstrBot。查到原轮后仍以相同编号、目标和问题重提本请求，核实确为媒体预留 |
| POST media-end | profile,sid,id,answer,status。原设备报告 `complete` 或 `unknown`，与预留使用相同编号 | profile,sid,turn。重复提交相同结果返回原轮，冲突结果返回 409 |
| POST remove | 主人；profile,sid | 空对象；保留移除标记，拒绝旧快照重发。手机的「隐藏」仅改本机 |

主人设置、签发邀请、管理上游、设置公网地址和管理设备只在电脑本机。手机网关按方法及路径允许 GET health/me/profiles/sessions/turns/turn/operation，POST preview/join/prove/profile/session/import/start/send/media-begin/media-end。媒体原件由原生发送与本机附件库处理；预留与文字共用同一房间的发送互斥。

Windows 开启设备共享时通过本机命令登记主人和各上游。CLI 使用 `service.py --db <数据库> device-owner`，标准输入 JSON 包含 device,token。`device-upstream` 接收 sourceId,base,key，可选 launcherId。提供 `addAlias` 可增量登记主人确认的同实例 HTTPS 手机来源，提供 `aliases` 可替换手动集合，省略两者保留原有别名。回执包含 upstream。密钥通过本机管道进入受保护的本地存储。
