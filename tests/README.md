# 测试执行

## 2026-09-07 新增边界检查

执行 `node tests/run-regressions.cjs` 运行全部 13 组独立前端回归，已接入 CI。`review-request-boundaries.cjs` 的 4 项正确行为断言已全部修复并通过；`review-write-recovery.cjs` 验证弱网重试、账号隔离和提交内容锁定；`daily-deduction-board.cjs` 验证首页公示的日期竞争、分页、错误恢复与完整事项说明。均使用实际页面 JS 和模拟微信原语，不等于真机验收。初始检视见 `docs/PROJECT_REVIEW_20260907.md`，最终改动见 `docs/FIX_REVIEW_20260907.md`。

从仓库根目录执行。脚本中的账号和密码仅供 `FullStackAcceptanceTest` 创建的临时数据库使用，不能指向生产。

## 独立前端回归

```powershell
node tests/run-regressions.cjs
if ($LASTEXITCODE -ne 0) { throw 'Frontend regression failed' }
node admin-web/node_modules/typescript/bin/tsc -p miniprogram/tsconfig.json --noEmit
npm.cmd --prefix admin-web run build
```

不要直接执行全部 `tests/*.cjs`：其中包含长期运行的本地服务器和依赖临时数据库的集成脚本。

## 后端

```powershell
$env:JAVA_HOME = (Resolve-Path '.runtime/jdk/jdk-21.0.12+8').Path
& '.runtime/maven/apache-maven-3.9.16/bin/mvn.cmd' -o '-Dmaven.repo.local=.runtime/m2' -f server/pom.xml test
```

`wrongPasswordsPersistAndLockAfterFiveAttempts` 最初暴露连续错密计数事务回滚缺陷，现已修复并通过。新增测试覆盖计数清零、锁定到期、管理员解锁、12 路并发及微信绑定事务回滚。Windows 沙箱不能启动嵌入式 PostgreSQL 时，需要允许隔离测试进程在沙箱外运行。

## 前后端真实联调

终端一执行下列命令。看到 `BROWSER_ACCEPTANCE_READY` 后，临时后端最多保留 10 分钟，然后关闭并输出测试结果。

```powershell
$env:JAVA_HOME = (Resolve-Path '.runtime/jdk/jdk-21.0.12+8').Path
& '.runtime/maven/apache-maven-3.9.16/bin/mvn.cmd' -o '-Dmaven.repo.local=.runtime/m2' -f server/pom.xml '-Dtest=FullStackAcceptanceTest' '-Dacceptance.browser=true' test
```

终端二先执行页面读取，再执行会创建测试数据的异常流程：

```powershell
node tests/miniprogram-real-api.cjs
node tests/incident-real-workflow.cjs
```

需要浏览器验收时运行 `node tests/web-real-server.cjs`，访问 `http://127.0.0.1:4181`。代理只允许读取测试生成的 loopback 后端地址。账号为 TECHNICIAN、ASSISTANT_ENGINEER、SUPERVISOR、DEPARTMENT_MANAGER、ADMINISTRATOR，临时密码均为 Acceptance2026!。浏览器自动化操作若被审批拦截，需要具体授权；不能改用注入登录态绕过。

完成后停止 Node 服务，并通知 Java 测试关闭后端与数据库：

```powershell
Set-Content server/target/acceptance-browser-stop finish
```

`web-fixture-server.cjs` 则仅提供模拟 API，用于固定样本展示检查，不能作为真实后端验收证据。
