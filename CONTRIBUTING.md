# 协作与代码规范（全体成员必读）

## 0. 项目一句话
C++ 引擎负责股票/外汇模拟撮合、行情、T+1、时间推进与作弊器；Java Swing 前端负责可视化操作。
**纯单机模拟，绝无任何真实资金充值/提现/支付相关代码。**

## 1. 工作目录
`A:\Downloads\tg`（所有人共享）。构建产物一律进 `build/`（不要提交到源码目录）。

## 2. 文件所有权（**严格不重叠**）
| 路径 | 负责人 |
|---|---|
| `engine/src/**` | engine-core |
| `engine/tests/smoke.cpp`, `tools/**`, `engine/CMakeLists.txt` | engine-tools |
| `engine/tests/**`（冒烟以外） | qa-verify |
| `frontend/src/**` | java-ui |
| `docs/PROTOCOL.md` | lead（唯一） |
| `scripts/**` | lead |

若要改动别人拥有的文件：**不要直接改**，发消息给 Lead 说明原因。

## 3. C++ 规范
- C++17，能同时用 `g++ (MSYS2 ucrt64)` 和 MSVC 编译。
- 所有源码 **UTF-8 无 BOM**；文件内字符串可以是中文，但**不要**依赖源文件编码以外的 locale。
- 引擎与前端协议字符串比较使用命令名字面量（英文），中文只出现在 `message`/`name`/`label`/`desc`。
- 严禁 `system()`。所有 I/O 走 stdin/stdout/stderr。
- **不允许未捕获异常导致进程退出**：`main` 顶层 `try/catch`，`INTERNAL` 错误码返回。
- 浮点比较用 epsilon；金额一律 `double`，输出时按协议四舍五入。
- 单头文件内联实现（header-only）是允许且推荐的，便于单文件编译。
- 编译警告必须清零：`-Wall -Wextra`。

## 4. Java 规范
- Java 17，仅用 **Swing/AWT + 标准库**，**不要**任何第三方依赖、不要构建工具（无 Maven/Gradle，脚本直接用 `javac`）。
- 包名根：`tsim`。
- 所有 UI 更新必须在 **EDT** 上（`SwingUtilities.invokeLater`）。
- 引擎读写必须在**同一个后台线程**里完成，避免多线程竞态；UI 线程永不阻塞在 I/O 上。
- 中文字体：统一用 `UITheme.UI_FONT`（微软雅黑回退），不要在组件里硬编码字体。
- UTF-8 源码，编译命令必须带 `-encoding UTF-8`。
- 编译必须 **零警告**（`-Xlint:all`）。

## 5. 构建（统一入口，谁都不许自己发明别的）
- `scripts\build.cmd`   —— 全量构建
- `scripts\run.cmd`     —— 构建并启动
- `scripts\selftest.cmd`—— 跑全部自动测试
最终用户入口就是这三个 + 生成的 `trade-tower.exe`。

## 6. 测试纪律（踩过的坑，人人遵守）
1. **禁止在别人跑测试时重建 `build\engine\trade_sim.exe`**。引擎 exe 在 Windows 上被运行时会加锁，
   重建会导致：(a) 链接器报 `cannot open output file ... Permission denied`；(b) 正在跑的测试大面积
   `<no-response>`，看起来像"引擎全线崩溃"，实际是测试污染。
   `scripts\build.cmd` 已做防护：链接前先 `del` 旧文件，失败则 `taskkill trade_sim.exe` 后重试一次。
2. **冻结判据是二元的，必须区分「代码版本」与「二进制版本」**：
   - **(a) 源码指纹**（真正的代码定版）：`engine/src/**` 全部文件按文件名排序，
     每行 `文件名:SHA256` 拼接后再取 SHA256。**同一份源码无论重链接多少次，此值不变。**
     当前值 = `f8a3360f799d3117ce16447a75efb4a3953a6bd11ef8d635511607cfa0df8061`
     （已登记 `build\evidence\engine_src_fingerprint.txt`）。
   - **(b) 二进制 SHA256**：仅用于判断「**同一轮内** exe 是否被中途替换」，**不可跨轮比较**。
     MinGW 的 PE 头含链接时间戳，**同一份源码重链接得到的哈希必然不同**（本项目实测：
     同源码三次链接得到 `267ED1EB…` / `BBF92218…` / `10248412…`）。
   每轮回归跑前跑后各记录一次二进制 SHA256；同轮内不一致 → 丢弃整轮重跑。
   **跨轮比较 exe SHA 一律无效**（同源码重链接必变，本项目实测三次得到三个不同值）。
   **源码指纹用 `scripts\srcfingerprint.ps1` 计算，不要手工算**（口径差一个分隔符就会得到不同值，本项目已因此误判两轮）。
   当前权威值：`fbc1940fcb09edd938754d184de67acc49f0057a12cd0e057267384a0a4d3275`（10 文件 / 752 字节）。
   该算法已用 PowerShell 与 Node 两套独立实现交叉验证一致。
   `scripts\selftest.cmd` 开头打印二者，`driver.log` 头部也带 `engine sha256`。
3. **构建必须是幂等的**：`build.cmd` 在源码未变时必须**跳过链接**，否则「跑一次自测」本身就会
   改写定版二进制、污染别人的回归判据（本项目真实发生过：连续三次重链接导致一轮全绿结果作废）。
3. **断言错了也要被验伪**。期望值一律取运行期实际值（如请求 id 用框架分配的 id），禁止硬编码魔数。
   本项目已发生 3 次"测试断言写错却报成引擎缺陷"的误报。
4. 报告缺陷必须带**可复现命令 + 真实原始输出**；未实测的写 NOT_TESTED 并说明原因，禁止编造证据。
5. **收 push 一律用限时循环 `ReadLine`，禁止 `ReadToEnd()`**。`ReadToEnd()` 要等 stdout EOF，
   而引擎按协议只等 stdin EOF / quit 才退出 —— 两者互等会**永久死锁**（本项目真实发生过：
   `build\ps.ps1` 造成两个 trade_sim.exe 孤儿进程占着 exe 句柄）。
   若坚持用 `ReadToEnd()`，**必须**把 `StandardInput.Close()` 提到它之前。
6. **探针/驱动必须自己回收子进程**（析构里 kill，或限时 `ReadLine` + 超时退出）。
   跑完自检 `Get-Process trade_sim` 计数应为 **0**；有残留 → 该轮结果作废并先清理。
7. 引擎侧：**重建 exe 前先确认无 `trade_sim` 残留**（Windows 文件锁会导致链接失败），
   且**禁止在别人跑测试的窗口内重建**。

## 7. 验收门槛（Lead 最终判定）
1. `scripts\selftest.cmd` 全绿。
2. 引擎能被 MSVC 与 g++ 双编译通过（至少 g++ 必须零警告）。
3. 前端 `javac -Xlint:all` 零警告。
4. 端到端：启动 GUI → 新建游戏 → 买股票 → 尝试当日卖出得到 T+1 提示 → 推进到次日卖出成功 →
   开外汇仓 → 作弊器加钱/解锁 T+1/快进 → 时钟倍速自动推进可见。
5. 协议一致性：Java 的 `EngineClient` 能解析引擎所有 v1 响应（qa 用真实引擎抓包核对）。
