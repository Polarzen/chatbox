# 档案共享协议（0.1.18）

现有单房间接口保持原有授权范围。下表中的路径均位于 `/v1/profile/` 下，JSON 使用 UTF-8。除 create、join 外，所有请求使用本次档案签发的 Bearer 凭据。服务从凭据确定档案；客户端给出的会话编号不能改变档案边界。

| 方法及路径 | 输入 | 返回 |
| --- | --- | --- |
| POST create | code（profile-bootstrap 创建码，或 profile-recover 为原档案签发的一次性主人恢复码）、sourceBase、username、sourceId（PC 端持久标识）、name | profile、instance、token、owner、name |
| POST invite | 空对象，限主人 | code、expiresIn（秒） |
| POST join | code（一次性档案邀请码） | 与 create 相同，owner 为 false |
| GET devices | 限主人 | devices 数组，各项为 id 和 name |
| POST revoke | device（所选来宾 id），限主人 | 空对象 |
| POST status | complete、expected（原会话数）、issue（可显示的同步缺口），限主人 | 空对象 |
| POST session | sid（PC 原会话号）、title、activity（毫秒，旧数据未知为 0）、total（本机待导入的历史前缀轮数）、legacyToken（可选，关联已有房间的主人凭据）、restore（可选布尔值，默认 false；显式恢复已移除的目录项时传 true），限主人 | 单个目录项 |
| POST import | sid、start（从 0 开始的 PC 本地前缀位置）、turns 数组，各项为 id、question、answer、status，限主人 | imported（已接受的连续前缀长度） |
| POST remove | sid，限主人 | 空对象。目录移除后该档案设备不能再读取、发送，已有房间原有保留策略独立生效 |
| GET sessions | offset、limit、revision（首屏可省略） | sessions、more、revision、complete、expected、issue、name |
| GET turns | sid、after（从 0 开始的排他 seq）、limit | turns、more，轮次沿用单房间的 seq、id、question、answer、status |
| GET turn | sid、id（UUID） | 单个轮次。关联旧房间后，使用此前缓存的导入 UUID 查询，回执保留该 UUID，正文取自同一历史位置；重新分页返回房间中的 UUID。 |
| POST send | sid、id（本轮请求 UUID）、question | 200/202，单个轮次 |

目录项字段为 sid、title、activity、total、imported、reason。reason 非空时仍可阅读已导入正文，并应显示不能接续的原因。列表按 activity 降序、sid 升序，每页最多 100 条；变动使分页快照失效时返回 409，客户端重取首屏，完整一遍后才移除旧缓存。正文按 seq 递增分页，生成中的末轮复查同一 id。

每个 PC 历史位置持久对应一个请求编号，同文不会合并。导入从最后确认的连续位置分批进行，不调用模型。服务对同一位置的重复提交核对内容。已有房间只能在主人证明、真实服务与身份均相符时关联；按原顺序核对旧导入前缀，保留旧房间的轮次编号与后来产生的轮次。两端后续文字经同一房间入队，复用其单轮互斥、结果未知和请求编号去重规则。普通设备无目录发布和管理权限。网络断开时已缓存历史可读；首次获知档案凭据已撤销（401）时清除该授权的凭据与缓存。
