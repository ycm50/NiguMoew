# 交易大亨 · 引擎/前端通信协议 v1 (FROZEN)

> 本文件是 C++ 引擎与 Java 前端之间的**唯一契约**。冻结后任何一方不得单方面修改；
> 如需变更，由 Lead 更新本文件版本号 +1 并通知全体成员。

## 0. 传输层
- 前端通过**子进程**启动引擎：`engine/trade_sim.exe --stdio`
- 双方用 **UTF-8** 文本行（`\n` 分隔，CRLF 亦接受）通信，stdin/stdout。
- 引擎的日志/诊断一律写 **stderr**，stdout **只允许**出现协议消息。
- 前端启动时可先读取 stderr 直到出现 `READY` 标记行（`TRADE_SIM ready`）以确认引擎就绪；
  也可以直接发 `hello`。二者择一，超时 10s 视为启动失败。

## 1. 消息格式
每行一个 JSON 对象（**单行，无内嵌换行**）。

请求：
```json
{"id": 1, "cmd": "quote", "args": {"symbol": "SH600519"}}
```
- `id`: 整数，>=1，前端自增，用于匹配响应。
- `cmd`: 字符串命令名。
- `args`: 对象，可为 `{}`；缺省视为 `{}`。

响应（成功）：
```json
{"id": 1, "ok": true, "data": { ... }}
```
响应（失败）：
```json
{"id": 1, "ok": false, "error": {"code": "NO_SUCH_SYMBOL", "message": "未知代码 SH600519"}}
```
- `id` 必须原样回显；对无法解析的 `id`（含 0 / null）用 `"id": 0` 回复。
- 每个请求**必须**恰好有一个响应，顺序与请求一致（前端仍按 id 匹配）。
- `data` 缺失时前端按 `{}` 处理；`error.message` 为中文，可直接展示给用户。

初始化握手：引擎启动后**主动**输出一行
`{"id":0,"ok":true,"data":{"type":"hello","protocol":1,"engine":"TradeSim","version":"1.0.0"}}`

退出：前端发 `{"id":N,"cmd":"quit"}`，引擎回 `{"id":N,"ok":true,"data":{}}` 后退出(0)。
前端关闭 stdin 时引擎应正常退出。

## 2. 数据类型
| 类型 | JSON 表示 |
|---|---|
| 价格 | number，**最多 4 位小数**（round 到 0.0001） |
| 金额 | number，最多 2 位小数 |
| 股数 | 整数 |
| 汇率手数 | 整数（1 手 = 1000 基础货币单位） |
| 比率/涨跌幅 | number，小数形式（`0.0123` = +1.23%），最多 6 位小数 |
| 时间 | `{"date":"2024-03-05","slot":3}` slot: 0..3 |
| 缺失字符串 | `""`（不用 null 表示字符串） |

## 3. 命令一览（v1 必须全部实现）

### 3.1 hello
- args: `{}`  ·  data: `{"type":"hello","protocol":1,"engine":"TradeSim","version":"1.0.0"}`

### 3.2 newgame
- args: `{"seed":<int>, "name":"<玩家名>", "cashStock":1000000, "cashForex":10000, "difficulty":"normal"}`
  - 所有字段可选。`difficulty`: `easy|normal|hard`（影响做市商议价与初始资金，默认 normal）。
  - 未给 `cashStock`/`cashForex` 时按难度默认：normal = 1000000 / 10000。
- data: 同 `snapshot` 的 data（见 3.4）。

### 3.3 tick   ⭐ 时间推进（自动 T+1 的核心）
- args: `{"n": 3, "mode": "auto"}`
  - `n`: 要推进的时间片数量，1..2000，默认 1。
  - `mode`: `"auto"`（默认）或 `"manual"`。
    - `auto`: 在推进过程中，若结算日(`slot==3`)结束、且玩家**存在可自动续约**的挂单/自动策略，
      引擎自行处理（T+1 解锁、自动重新挂单、自动止盈止损）。
    - `manual`: 不做任何自动决策，只推进时间。
- data:
```json
{"time": {"date":"2024-03-05","slot":2},
 "advanced": 3,
 "events": [ {"kind":"order_filled","symbol":"SH600519","side":"buy","qty":100,"price":1712.5,
              "note":"限价买单全部成交","at":{"date":"2024-03-05","slot":1}},
             {"kind":"t1_unlock","symbol":"SH600519","qty":100,"at":{...}},
             {"kind":"margin_call","account":"stock","amount":-1234.5,"at":{...}},
             {"kind":"news","newsId":7,"title":"央行降准","at":{...}} ],
 "halted": ["SZ000001"]}
```
- `events[].kind` 取值（**枚举冻结**）：
  `order_filled` | `order_partial` | `order_cancelled` | `order_expired` |
  `t1_unlock` | `stop_triggered` | `take_profit_triggered` |
  `margin_call` | `news` | `dividend` | `fx_swap` | `bankrupt`
- `events` 按发生顺序排列，可为空数组 `[]`（**不可为 null**）。

### 3.4 snapshot
- args: `{}`
- data:
```json
{"time": {"date":"2024-03-05","slot":1},
 "stockAccount": {"cash": 800000.0, "frozen": 20000.0, "equity": 1050000.0,
                  "marketValue": 250000.0, "pnlDay": 1234.5, "pnlTotal": 50000.0,
                  "marginUsed": 0.0, "buyingPower": 400000.0, "t1FrozenCash": 0.0},
 "forexAccount": {"cash": 10000.0, "margin": 200.0, "equity": 10250.0,
                  "freeMargin": 10050.0, "marginLevel": 51.25, "pnlFloat": 250.0,
                  "pnlTotal": 250.0, "usedLots": 2, "currency":"USD"},
 "stockPositions": [ {"symbol":"SH600519","name":"贵州茅台","qty":100,"frozenQty":0,
                      "avgCost":1700.0,"last":1712.5,"marketValue":171250.0,
                      "pnl":1250.0,"pnlPct":0.0074,
                      "marginDebt":0.0,"pnlPctOwn":0.0074,"todayBoughtQty":100} ],
 "forexPositions": [ {"positionId":5,"symbol":"EURUSD","name":"欧元/美元","side":"long","lots":2,
                      "openRate":1.0812,"last":1.0850,"margin":200.0,"pnl":760.0,
                      "swap":-1.2,"stopLoss":0.0,"takeProfit":0.0} ],
 "orders": [ {"id":11,"symbol":"SH600519","market":"stock","side":"buy","type":"limit",
              "qty":100,"filled":0,"price":1700.0,"status":"open",
              "created":{"date":"2024-03-05","slot":1}} ],
 "autoT1": {"enabled": true, "autoRenew": false, "autoStop": false},
 "stat": {"tradeCount": 12, "winCount": 7, "realizedPnl": 5000.0,
          "totalCommission": 320.5, "startEquity": 1010000.0},
 "bankrupt": false,
 "extra": {"name": "玩家", "difficulty": "normal"}}
```
- `extra` 为**可选扩展对象**（v1.0.1 登记）：`name`/`difficulty` 等纯展示字段放这里；
  必须是对象、字段名稳定、**不得为 null 或缺失以外的类型**。前端不得依赖它存在。
- `status` 枚举: `open|partial|filled|cancelled|expired`
- `type` 枚举: `market|limit`
- `side` (股票): `buy|sell`；`side` (外汇): `long|short`
- `market` 枚举: `stock|forex`
- `marginLevel` 单位是**百分比**（equity/margin*100），无持仓时为 `0.0`。
- `bankrupt` 为 true 时前端弹出破产提示，但仍可继续调用（作弊器可以救）。

### 3.5 market
- args: `{"market":"stock"|"forex"|"all", "symbol":"<可选，只看一个>"} `
- data:
```json
{"time": {...},
 "stocks":[{"symbol":"SH600519","name":"贵州茅台","last":1712.5,"prevClose":1700.0,
            "open":1705.0,"high":1730.0,"low":1698.0,"changePct":0.0074,
            "volume":1234567,"bid":1712.4,"ask":1712.6,"halted":false,
            "pe":32.1,"hist":[{...最近60根K线...}],"currency":"CNY"}],
 "forex":[{"symbol":"EURUSD","name":"欧元/美元","last":1.0850,"prevClose":1.0840,
           "open":1.0842,"high":1.0861,"low":1.0838,"changePct":0.0009,
           "bid":1.0849,"ask":1.0851,"spread":0.0002,"digits":4,"pip":0.0001,
           "pointValue":10.0,"hist":[...]}]}
```
- 股票代码前缀：`SH`(沪) / `SZ`(深) / `HK`(港) / `US`(美)，每种 ≥ 4 只。
- 外汇对：`EURUSD,GBPUSD,USDJPY,AUDUSD,USDCHF,USDCAD,NZDUSD,EURJPY,GBPJPY,XAUUSD`，
  `digits`：USDJPY/EURJPY/GBPJPY 为 3，XAUUSD 为 2，其余 4。

### 3.6 quote
- args: `{"symbol":"SH600519"}`
- data: `{"symbol":"...","name":"...","market":"stock","last":..,"bid":..,"ask":..,"prevClose":..,"changePct":..,"halted":false,"digits":2}`

### 3.7 buy / sell   （外汇用 long/short 的语义见 3.9；股票用下面两个）
- args: `{"symbol":"SH600519","qty":100,"type":"market"|"limit","price":1700.0}`
  - `qty` 必须是 **100 的整数倍**（A 股规则），>0。`price` 仅 `type=="limit"` 时必填。
  - **`leverage`（可选，v1.0.2）**：股票融资杠杆，整数 **1..25**，默认 1（不用杠杆）。
    仅 **买入** 方向可用；卖出传 `leverage` 会返回 `BAD_ARG`。
    - 自有保证金 = 成交金额 / leverage，手续费按**全额**计收，差额计入该持仓的 `marginDebt`（融资负债）；
    - 卖出时按卖出股数比例**自动偿还**负债，净得款 = 成交额 − 手续费 − 偿还额；
    - `stockAccount.equity` 与 `buyingPower` **已扣除**负债；越界（<1 或 >25）返回 `BAD_ARG`。
- data: `{"orderId":11,"status":"filled"|"open","filled":100,"avgPrice":1701.2,"commission":17.01,"cash":799982.99}`
- 失败错误码（**冻结**）：
  `NO_SUCH_SYMBOL` `BAD_QTY` `INSUFFICIENT_CASH` `INSUFFICIENT_POSITION`
  `T1_LOCKED` `MARKET_HALTED` `NO_MARKET_DATA` `BAD_PRICE` `BAD_ARG` `NO_GAME` `INTERNAL`
- **T+1 规则**：当日买入的股票 `todayBoughtQty` 在**下一个交易日开盘前**不可卖出；
  尝试卖出被锁数量返回错误码 `T1_LOCKED`，`message` 例："T+1 锁定：当日买入 100 股需次日开盘后可卖"。

### 3.7b 股票融资强平（v1.0.3）
- **维持保证金率** = `(持仓市值 + 冻结 − 融资负债) / (持仓市值 + 冻结) × 100%`。
  无融资负债时为 `100%`，此时永不触发。
- 引擎在**每个时间片结束时**检查（与外汇同期）：
  1. 跌破 **25%** → 产生 `margin_call` 预警事件：
     `{"kind":"margin_call","account":"stock","level":x,"debt":x,"note":"...","at":{...}}`
     （同一轮下跌只提醒一次；回到 25% 以上后复位，可再次提醒）
  2. 跌破 **20%** → **强制平仓**：反复卖出「自有权益占比最低」的标的，
     直到维持率回到 25% 以上或负债清零。事件额外带 `symbol`/`side`/`qty`/`price`：
     `{"kind":"margin_call","account":"stock","symbol":"SH600519","side":"sell","qty":100,"price":x,"level":x,"debt":x,"note":"融资维持保证金率低于 20%，强制平仓 SH600519","at":{...}}`
  3. 强平成交写入 `history`，`reason = "liquidation"`；
  4. 强平后 `stockEquity() <= 0` → 追加 `{"kind":"bankrupt","account":"stock"}` 并置 `bankrupt=true`。
- **强平不受 T+1 限制**：风控优先于 T+1 冻结（真实市场亦如此），当日买入的股票在强平时可被卖出。
- `godMode` 开启时不做任何强平检查（避免干扰测试）。
- 前端应对 `account=="stock"` 的 `margin_call` 显示为"融资追缴/强平"，与外汇爆仓区分文案。
### 3.7c 股票做空 / 平空（融券，v1.0.4）
- **`short`**：开空。args `{"symbol":"SH600519","qty":100,"type":"market"|"limit","price":x,"leverage":1..5}`
  - `qty` 必须是 **100 的整数倍**；`leverage` 可选，**1..5**（比融资买入保守），默认 1。
  - 效果：借入股票卖出，成交额 / 杠杆 作为**冻结保证金**从现金扣除；
    `shortQty`/`shortAvgPrice`/`shortMargin` 记录在 `stockPositions[]` 上。
  - 返回：`{"orderId":N,"status":"filled","filled":100,"avgPrice":x,"commission":x,"cash":x,"shortQty":100,"shortMargin":x}`
- **`cover`**：平空（买入归还）。args `{"symbol":"SH600519","qty":100}`（`qty` 省略 = 全平）
  - 盈亏 = `(开仓价 − 平仓价) × 数量`；释放按比例冻结的保证金；返回含 `realizedPnl`。
  - 成交写入 `history`，`side = "cover"`。
- **T+1**：当日开空的仓位**当日不可平**，返回 `T1_LOCKED`；次日开盘后解锁。
- **做空风控**：空头保证金率 = `(冻结保证金 + 浮盈) / 开仓市值 × 100%`
  - 跌破 **25%** → `margin_call`（`account:"stock"`、`side:"short"`）预警；
  - 跌破 **20%** → 强制平空（买入归还），事件 `side:"cover"`，成交 `reason:"liquidation"`。
- **权益口径**：`stockEquity()` = 现金 + 冻结 + 多头市值 − 融资负债 + 做空保证金 + 做空浮盈。
  （做空保证金从现金扣出后要加回，它仍是自有资金；做空浮盈才是真盈亏。）
- 前端：交易按钮为 **做多买入 / 做空卖出**；持仓列表按**浮动盈亏降序**排序，
  多头行带「卖出」按钮、空头行带「平仓」按钮。
### 3.7d 股票 margin_call 事件字段（v1.0.5）
股票融资/融券的 `margin_call` 事件**统一携带 `loss` 字段**（早期缺失，前端取不到就显示"亏损 0.0"）：
- 公共字段：`kind` / `account:"stock"` / `level`（触发时保证金率 %）/ `at`。
- `mode`：`"warn"`（仅预警，未强平）/ `"liquidate"`（本次已强平）。
- **`loss` 的语义按 mode 区分**：
  - `mode:"warn"`：`loss` = **保证金缺口**（把保证金率补回 25% 所需的自有资金），恒为非负；
    另带 `floatPnl` = 当前浮动盈亏（亏损为负）。
  - `mode:"liquidate"`：`loss` = **本笔强平的实际亏损**（负数表示亏损）。
- 强平事件额外带：`symbol` / `side`（融资为 `"sell"`，融券为 `"cover"`）/ `qty`（股）/ `price`。
- `debt`：融资口径为融资负债总额（仅融资路径携带）。
- 前端**只在事件确实含 `loss` 时**才展示该行，避免用 0 兜底造成误导。
### 3.8 cancel
- args: `{"orderId":11}`
- data: `{"orderId":11,"cancelled":true,"refundedCash":20000.0}`

### 3.8a 挂单有效期（order_expired）—— v1.0.1 明确
- **限价挂单的有效期 TTL = 20 个时间片（= 5 个交易日）**。
- 引擎在每个时间片结束时递减挂单剩余寿命；到期仍未完全成交的挂单：
  1. `status` 置为 `"expired"`，剩余冻结资金/持仓**立即解冻**；
  2. 产生事件 `{"kind":"order_expired","orderId":11,"symbol":"SH600519","side":"buy","qty":100,"filled":0,"at":{...}}`；
  3. 该挂单**不再参与撮合**。
- `order_partial`（部分成交）事件在"挂单被撮合了一部分但未全部成交"时产生，
  字段同 `order_filled`，另加 `"remaining":<未成交数量>`。
- **过期后的可见窗口 = 1 个时间片**（v1.0.1 实测口径，三方独立复核一致）：
  挂单在第 N 片到期时置 `status:"expired"`**并仍出现在该片的 `orders` 列表中**；
  **下一个时间片起即从列表中清除**（实测：片 20 = `[{"status":"expired",...}]`，片 21 = `[]`）。
  三个种子（1/7/555）逐片采样结果完全一致。
- 前端「挂单」面板应：
  1. 对 `expired` 状态显示为灰色并标注"已过期"（仅在到期那一瞬间可见）；
  2. **必须监听 `order_expired` 事件做本地留档**（不是"锦上添花"，而是**唯一可行手段**）——
     由于可见窗口只有 1 个时间片，用户几乎必然错过；靠事件留档才能交代"委托去哪了"。
     java-ui 已按此实现（事件留档 + 与在挂单合并展示 + 底部"已过期 N 条"提示）。

### 3.8b 外汇爆仓（margin call）—— 阈值口径（v1.0.1 明确）
- `marginLevel = equity / margin * 100`（百分比；无持仓时 `0.0`）。
- 引擎在 **每个时间片结束时**检查所有外汇持仓；当 `marginLevel < 50.0`（即 `marginLevel` 低于
  **50%** 爆仓线）时触发强制平仓，并产生 `kind:"margin_call"` 事件，事件字段：
  `{"kind":"margin_call","account":"forex","positionId":5,"lots":2,"level":42.7,"loss":-123.4,"at":{...}}`。
- 强制平仓会从**亏损最大**的持仓开始，直到 `marginLevel >= 50.0` 或持仓清空。
- `marginLevel` 一旦击穿 50% 且账户权益 ≤ 0，额外产生 `kind:"bankrupt"` 事件并置 `bankrupt=true`。
- 前端应把 `marginLevel < 100` 显示为警戒色、`< 50` 显示为危险色。

### 3.9 forex 开平仓
- `open`: args `{"symbol":"EURUSD","side":"long"|"short","lots":2,"leverage":100,"stopLoss":0,"takeProfit":0}`
- `close`: args `{"positionId":5,"lots":2}`  （`lots` 可省略=全平）
- 错误码：`INSUFFICIENT_MARGIN` `NO_POSITION` `BAD_LOTS` 及 3.7 中的通用码。
- 1 手 = 1000 基础货币单位，`margin = lots*1000*price/leverage`（USD 计价对 1 手点值 10.0 美元/1pip）。
- data(open): `{"positionId":5,"lots":3,"openRate":1.0812,"margin":200.0,"swap":0.0}`

### 3.10 orders
- args: `{"market":"stock"|"forex"|"all"}`
- data: `{"orders":[ ...同 snapshot 的 orders 元素... ]}` —— **对象包数组**（v1.0.1 明确），
  `history`/`news` 同理为 `{"trades":[...]}` / `{"news":[...]}`。**不是裸数组。**

### 3.11 history
- args: `{"limit":50,"market":"all"}`  ·  data:
```json
{"trades":[{"seq":1,"time":{...},"market":"stock","symbol":"SH600519","side":"sell",
            "qty":100,"price":1712.5,"amount":171250.0,"commission":171.25,
            "realizedPnl":1250.0,"reason":"manual|stop|takeprofit|liquidation|dividend"}]}
```
- 时间倒序（最新在前）。

### 3.12 news
- args: `{"limit":20,"unreadOnly":false}`  ·  data:
```json
{"news":[{"id":7,"time":{...},"title":"央行意外降准","body":"...","scope":"stock|forex|macro",
          "impact":0.025,"symbols":["SH601398"],"read":false}]}
```

### 3.13 settings
- args（部分字段可省略，省略=不改）:
```json
{"speed": 1.0, "t1": true, "autoRenew": false, "autoStop": true,
 "commission": 0.00025, "slippage": 0.0005, "tickMs": 0,
 "stopLossPct": 0.05, "takeProfitPct": 0.10, "difficulty": "normal"}
```
- `tickMs`: >0 时引擎在每次 `tick` 之间主动 `sleep(tickMs/speed)` 毫秒（由前端 tick 循环控制，见 3.14）。
- data: 回显**完整**设置对象（含所有字段）。

### 3.14 clock   ⭐ 时钟控制（时间倍率）
- args: `{"action":"start"|"stop"|"set"|"get","speed":4.0,"tickMs":500}`
- `action`:
  - `get`  返回当前状态，不改变任何东西。
  - `set`  设置 `speed`(0.25..256) 与 `tickMs`(50..60000)；
  - `start` 等价 `set` 后开始**自动推进**：引擎自行按 `tickMs/speed` 的间隔推进时间片，
    并把每个时间片产生的 `events` **主动推送**给前端（见 3.15）。
  - `stop`  停止自动推进。
- data: `{"running":true,"speed":4.0,"tickMs":500,"tickIntervalMs":125,"time":{...},"advanceUnit":"slot"}`
- **`tickIntervalMs` 是「实际生效值」**（可能被夹到下限或取整到 2 位小数），前端必须直接采用该值，
  不要自己用 `tickMs/speed` 重算。自洽要求：`tickIntervalMs × speed ≈ tickMs`（相对误差 < 5%）。
- 越界（`speed ∉ [0.25,256]` 或 `tickMs ∉ [50,60000]`）必须返回 `BAD_ARG`。
- 规则：1 个时间片 = 1 个 slot；4 slot = 1 个交易日；1 个 slot 在现实中耗时 `tickMs/speed` 毫秒。
  例：`tickMs=500, speed=8` → 每 62.5ms 推进 1 slot → 16 片/秒 → 16÷4 = **4.0 天/秒**。

### 3.15 主动推送（服务器事件，无请求）
引擎在**自动时钟运行**时主动输出（`id` 固定为 `0`，`type` 区分）：
```json
{"id":0,"push":true,"type":"tick","data":{"time":{...},"advanced":1,"events":[...]}}
{"id":0,"push":true,"type":"bankrupt","data":{"account":"forex"}}
```
- **【v1.0.1 · 强制】push 的 `data.events` 必须与同步 `tick` 返回的 `events` 完全等价。**
  自动时钟推进时发生的任何事件（含 `margin_call`、`bankrupt`、`order_filled`、`t1_unlock` …）
  **必须**出现在某条 `type:"tick"` 推送的 `data.events` 里，**不得**因为走了推送通道就丢弃。
- **破产必须主动推送**：当账户进入破产时，除在下一片 `tick` 推送的 `events` 中包含
  `{"kind":"bankrupt","account":"stock"|"forex"}` 外，引擎**还应**额外推送一条
  `{"id":0,"push":true,"type":"bankrupt","data":{"account":"forex"}}`。
  两种形态**都要有**，前端按 `type:"bankrupt"` 弹破产提示，按 `events[].kind=="bankrupt"` 记流水。
- 前端必须在有未决请求时也能处理这些行（按 `push:true` 分流）。

## 4. 作弊器（Cheat）命令 —— 纯模拟，无任何现实货币进出
```
cheat.args: {"op":"<名>", ...op参数字段}
```
| op | 参数 | 说明 |
|---|---|---|
| `list` | — | 返回所有可用作弊项：`{"cheats":[{"op":"money","label":"注入资金","desc":"...","args":["amount"]}]}` |
| `money` | `{"amount":1000000,"account":"stock"\|"forex"\|"both"}` | 直接加现金 |
| `reset` | `{} ` | 资金/持仓/订单回到初始状态（时间和新闻保留） |
| `price` | `{"symbol":"SH600519","to":2000.0}` 或 `{"symbol":"...","pct":0.1}` | 强制设定/按比例改动价格 |
| `pump` | `{"symbol":"...","pct":0.1,"bars":5}` | 未来 N 个时间片持续涨/跌 |
| `freeze` | `{"symbol":"...","halted":true}` | 停牌/复牌 |
| `unlock` | `{"symbol":"..."}`(可省略=全部) | 立即解除 T+1 锁定 |
| `t1` | `{"enabled":false}` | 开关 T+1 规则（作弊项） |
| `infiniteMoney` | `{"enabled":true}` | 现金不足时自动补足（买入永不失败） |
| `godMode` | `{"enabled":true}` | 免保证金/免手续费/永不爆仓 |
| `noCommission` | `{"enabled":true}` | 手续费归零 |
| `perfectInfo` | `{"enabled":true}` | 前端显示隐藏信息（引擎把 `news` 未读与未来 `pump` 计划放进 snapshot 的 `cheatInfo`） |
| `fillOrders` | `{"all":true}` | 立即成交所有未成交挂单 |
| `setCash` | `{"account":"stock","value":5000000}` | 直接把现金设成指定值 |
| `winRate` | `{"value":0.9}` | 之后每次随机行情出现的"对你有利"概率 |
| `seed` | `{"seed":12345}` | 重置随机种子并重新生成行情（时间/仓位保留） |
| `speed` | `{"speed":64}` | 作弊器倍速（=clock set speed） |
| `skip` | `{"slots":40,"auto":true}` | 快进 N 个时间片（等价 tick，但允许多） |
| `news` | `{"title":"...","impact":0.05,"scope":"stock","symbols":[...]}` | 手工生成一条新闻并立刻影响行情 |
| `bankrupt` | `{"account":"stock"}` | 强制破产（测试用） |
| `unbankrupt` | `{} ` | 解除破产状态 |
| `revealSeed`| `{} ` | 返回当前种子与行情内部状态 |

- `cheat` 的 data: `{"op":"money","ok":true,"detail":"已注入 1,000,000.00 到股票账户","cheatState":{...}}`
- **`cheatState` 仅由「变更类」op 回带**（money/setCash/t1/infiniteMoney/godMode/noCommission/perfectInfo/unlock/fillOrders/winRate/seed/reset/unbankrupt…）。`op=list` 只返回可用作弊项清单 `{"cheats":[...]}`，**不回带 cheatState**（v1.0.1 明确）。前端对 cheatState 必须做"仅非空才更新"的兜底。
- `cheatState` 固定字段：`{"t1":true,"infiniteMoney":false,"godMode":false,"noCommission":false,"perfectInfo":false,"winRate":0.5,"seed":12345}`
- **重要**：前端「作弊器」面板必须显式标注"仅模拟，不涉及任何真实资金"。

## 5. 错误码总表（冻结）
| code | 含义 | 前端提示 |
|---|---|---|
| `NO_GAME` | 尚未 newgame | "请先开始新游戏" |
| `NO_SUCH_SYMBOL` | 未知代码 | "未知交易代码" |
| `BAD_ARG` | 参数缺失/类型错 | "参数错误" |
| `BAD_QTY` | 数量非法(非100整数倍/<=0) | "股票数量必须是100的整数倍" |
| `BAD_LOTS` | 手数非法 | "手数非法" |
| `BAD_PRICE` | 价格非法 | "价格非法" |
| `INSUFFICIENT_CASH` | 现金不足 | "可用资金不足" |
| `INSUFFICIENT_POSITION` | 持仓不足 | "可卖持仓不足" |
| `INSUFFICIENT_MARGIN` | 保证金不足 | "保证金不足" |
| `T1_LOCKED` | T+1 锁定 | "T+1 锁定，次日可卖" |
| `MARKET_HALTED` | 停牌 | "该标的已停牌" |
| `NO_MARKET_DATA` | 无行情 | "行情数据缺失" |
| `NO_POSITION` | 无此外汇持仓 | "没有该持仓" |
| `UNKNOWN_CMD` | 未知命令 | "引擎不支持该命令" |
| `INTERNAL` | 内部错误 | "引擎内部错误" |

## 6. 变更记录
- v1 (冻结): 初版。
- **v1.0.1**（2026-10-02，Lead，无破坏性变更）：
  1. §3.14 更正正文笔误：`tickMs=500, speed=8` → 16 片/秒 → **4.0 天/秒**（原写 3.2 天/秒）。
     天/秒 = (1000/tickMs×speed) ÷ 4。
  2. §3.4 正式登记 `extra` 为**可选扩展对象**（`name`/`difficulty` 等展示字段）。
  3. §3.10 明确 `orders`/`history`/`news` 的 data 为**对象包数组**（非裸数组）。
  4. §0 补充：`TRADE_SIM ready` 仅在环境变量 `TRADE_SIM_READY=1` 时输出，
     默认静默；hello 帧即为就绪信号。
  5. §3.3 明确日历口径：**4 slot = 1 自然日，跳过周六/周日**（交易日日期 +1，逢周末顺延到下周一）。
  6. §4 明确 `cheatState` **仅由变更类 op 回带**，`op=list` 只返回 `{"cheats":[...]}`。
  7. §1 重申：`quit` 也必须**原样回显请求 id** 并回 `{"id":N,"ok":true,"data":{}}`。
  8. §2 补充：`fmtNum` 对极小量（|v|<1e-6）必须**展开为十进制**（如 `0.00000001`），既不输出科学计数法、也**不得截断为 0**。
  9. §3.15 新增：**push 的 `data.events` 必须与同步 `tick` 的 `events` 等价**（任何事件不得因走推送通道而丢弃）；
     破产须**双形态**推送（`events[].kind=="bankrupt"` + 独立 `type:"bankrupt"` 推送）。
 10. §3.8a 新增：限价挂单 **TTL = 20 时间片（5 个交易日）**，到期未成交置 `expired`、解冻、产生 `order_expired` 事件。
     过期元素在 `orders` 中的**可见窗口 = 1 个时间片**（下一片即清除，三方独立复核一致）；
     前端**必须**靠 `order_expired` 事件本地留档才能向用户交代。
 11. §3.4 登记 `forexPositions[].positionId`。
 12. §3.8b 新增：外汇爆仓阈值 **marginLevel < 50%**，强平顺序为亏损最大优先。
 16. **§3.7d 股票 `margin_call` 统一携带 `loss`（缺口或本笔亏损）与 `mode`**，
     前端不再用 0 兜底显示"亏损 0.0"。
 15. **§3.7c 新增股票做空（融券）**：`short` / `cover` 命令，杠杆 1..5，T+1 锁定，
     空头保证金率 25% 预警 / 20% 强制平空；`stockPositions[]` 新增
     `shortQty`/`shortAvgPrice`/`shortMargin`/`shortPnl`/`shortPnlPct`/`todayShortedQty`。
 14. **§3.7b 新增股票融资强平**：维持保证金率 25% 预警 / 20% 强制平仓，强平不受 T+1 限制。
 13. **§3.7 新增股票融资杠杆 `leverage`（1..25，仅买入）**；
     §3.4 的 `stockPositions[]` 登记 `marginDebt`（融资负债）与 `pnlPctOwn`（自有资金收益率）。
     `stockAccount.equity`/`buyingPower` 均**已扣除**融资负债；`marginUsed` 即负债总额。
