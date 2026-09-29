# 业务 API 概览

所有接口以 `/api` 为前缀，统一返回 `ApiResponse`。除登录、健康检查外，请求须携带 `Authorization: Bearer <token>`。Token 有效期 30 天；密码重置、修改、停用或解绑后，已有会话立即失效。

## 身份与消息

- `POST /admin/auth/login`：工号和密码登录；小程序传 `clientType=MINI`，Web 传 `clientType=WEB`。
- `GET /admin/auth/me`：当前用户、业务角色、管理员标识和首次改密状态。
- `POST /admin/auth/change-password`：修改密码；首次登录必须完成。
- `POST /wechat/bind`、`DELETE /wechat/bind`：绑定或解绑微信。
- `POST /wechat/subscriptions`：登记一次微信订阅消息授权。
- `GET /notifications`、`POST /notifications/{id}/read`：站内消息与已读。

业务角色仅有 `TECHNICIAN`、`ASSISTANT_ENGINEER`、`SUPERVISOR`、`DEPARTMENT_MANAGER`；管理员是独立布尔权限，不是业务角色。

## 工作台与目录

- `GET /workbench`：按角色返回当日排班、本人积分或部门待办概览。
- `GET /directory/users`：返回当前数据范围内可选人员。
- `GET /catalog`：小程序使用的班组、线体、站位和启用设备。

## 排班

- `GET /schedules`：按日期、班组和状态查询。
- `POST /schedules/batch`：批量创建草稿或直接发布。
- `PUT /schedules/{id}`：修改排班；开班后仅主管可带原因修改。
- `POST /schedules/{id}/publish`：发布排班。
- `POST /schedules/{id}/acknowledge`：责任人点击“我已知晓”。
- `GET /schedules/{id}/revisions`：查看修改记录。

同一日期、班次、站位最多两名责任人。夜班计入开始日期。

## 积分流程

- `GET /performance-cases/mine`、`GET /performance-cases/pending`：本人明细与待审核事项。
- `POST /performance-cases/bonus`：发起加分；技术员先交助理工程师，助理工程师本人直接交主管。
- `POST /performance-cases/deduction`：助理工程师发起本班组扣分，主管也可直接发起并立即生效。
- `POST /performance-cases/{id}/assistant-decision`：助理工程师通过、退回或否决。
- `POST /performance-cases/{id}/supervisor-decision`：主管审核并确定分值。
- `POST /performance-cases/{id}/resubmit`：退回后重新提交。
- `POST /performance-cases/{id}/appeal`：对扣分申诉，每条一次、24 小时内。
- `POST /performance-appeals/{id}/vote`：原审核主管回避，达到当前申诉表决通过条件后生效。
- `GET /performance/overview`：主管和部长查看所有正式人员实时积分、平均分和排名；一线人员仅能看到本人。

加分证明最多 9 张图片。扣分申诉可附图。所有状态变化和审核痕迹永久保留。

## D 级、月结与封存后更正

- `GET /d-grades`、`POST /d-grades`、`POST /d-grades/{id}/vote`：D 级发起和联合表决。
- `POST /d-grades/{id}/appeal`、`POST /d-grade-appeals/{id}/vote`：D 级申诉和联合重审。
- `GET /settlements/{period}/preview`：月度实时等级预览，仅主管、部长可见。
- `POST /settlements/{period}/confirm`：主管确认月结；达到当前审批确认条件后封存。
- `POST /settlements/{period}/lock`：次月 5 日后封存无待办月份。
- `GET /settlements/{period}/corrections`、`POST /settlements/{period}/corrections`：封存后更正申请。
- `POST /settlement-corrections/{id}/vote`：达到当前表决通过条件后生成更正快照，原快照标记为已取代。

## 设备异常

- `POST /incidents`：助理工程师或主管按线体、站位、多台设备登记异常并自动匹配已发布排班责任人。
- `GET /incidents/pending`：本人待提交说明或主管待审核异常。
- `POST /incidents/{id}/statements/mine`：每名责任人独立提交现象、处理方法、根因和长期对策。
- `POST /incidents/{id}/statements/{statementId}/review`：主管审核，通过或退回。
- `POST /incidents/{id}/append-note`、`POST /incidents/{id}/void`：主管追加说明或带原因作废。
- `GET /incidents/archive`、`GET /incidents/{id}`：全员查看审核通过的永久档案。

异常说明须在发生后 24 小时内提交；退回后 8 小时内重交。系统在剩余 2 小时和 30 分钟提醒，逾期只提醒主管，不自动扣分。

## Web 后台管理与导出

- `/admin/organization/**`：组织、人员、角色、账号重置、解锁和人员导入。
- `/admin/equipment/**`：设备台账维护与 Excel 导入。
- `/admin/catalog/**`：班组、线体和站位维护。
- `/admin/shifts/**`：白夜班时间规则维护。
- `/admin/holidays/**`：中国大陆法定节假日维护。
- `/admin/score-rules/**`：积分规则和等级参数维护。
- `/admin/audit-logs`：审计日志。
- `/exports/**`：积分、排班、异常和设备台账 Excel 导出，导出行为写入审计日志。

技术员禁止登录 Web；助理工程师仅访问本班组数据，主管访问全部门，部长只读。指定系统管理员账号具有独立配置权限。
