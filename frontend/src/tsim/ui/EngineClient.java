package tsim.ui;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import tsim.json.Json;
import tsim.json.JsonDeserializer;
import tsim.json.JsonDeserializer.ClockState;
import tsim.json.JsonDeserializer.MarketData;
import tsim.json.JsonDeserializer.NewsInfo;
import tsim.json.JsonDeserializer.OrderInfo;
import tsim.json.JsonDeserializer.Settings;
import tsim.json.JsonDeserializer.Snapshot;
import tsim.json.JsonDeserializer.TickResult;
import tsim.json.JsonDeserializer.TradeInfo;
import tsim.json.JsonDeserializer.TradeResult;

/**
 * 引擎客户端：以子进程方式启动 {@code engine/trade_sim.exe --stdio}，
 * 通过 stdin/stdout 交换 UTF-8 JSON 行，stderr 收进日志缓冲。
 *
 * <p>线程模型：</p>
 * <ul>
 *   <li>stdout 读取线程：逐行解析，普通响应按 id 唤醒对应的 {@link CompletableFuture}，
 *       {@code push:true} 的行交给注册的推送回调。</li>
 *   <li>stderr 读取线程：把引擎诊断写入环形日志缓冲并通知监听者。</li>
 *   <li>超时看门狗线程：请求超时后完成 future（异常为 {@link EngineError}）。</li>
 *   <li>请求在调用者线程发出（线程安全），UI 通过异步回调拿结果，绝不阻塞 EDT。</li>
 * </ul>
 */
public final class EngineClient {

    /** 协议版本（PROTOCOL.md v1）。 */
    public static final int PROTOCOL_VERSION = 1;

    /** 默认启动超时（毫秒）。 */
    public static final long START_TIMEOUT_MS = 10_000L;

    /** 默认单请求超时（毫秒）。 */
    public static final long DEFAULT_TIMEOUT_MS = 15_000L;

    /** 引擎可执行文件相对工作目录的路径。 */
    public static final String RELATIVE_EXE = "engine" + File.separator + "trade_sim.exe";

    private static final int LOG_CAPACITY = 2000;

    /** 引擎通信/业务错误；{@code message} 为中文，可直接展示。 */
    public static final class EngineError extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String code;

        /** 用错误码与中文消息构造。 */
        public EngineError(String code, String message) {
            super(message == null ? "" : message);
            this.code = code == null ? "" : code;
        }

        /** 冻结错误码，如 T1_LOCKED。 */
        public String code() {
            return code;
        }

        /** 是否 T+1 锁定。 */
        public boolean isT1Locked() {
            return "T1_LOCKED".equals(code);
        }
    }

    /** 引擎推送回调（tick / bankrupt）。 */
    public interface PushListener {
        /**
         * 收到一条推送。
         *
         * @param type 推送类型：tick / bankrupt
         * @param data 推送数据对象
         */
        void onPush(String type, Map<String, Object> data);
    }

    /** stderr 日志监听。 */
    public interface LogListener {
        /** 收到一行引擎日志。 */
        void onLog(String line);
    }

    /** 引擎退出监听。 */
    public interface ExitListener {
        /** 引擎进程结束。 */
        void onExit(int exitCode, boolean expected);
    }

    private final Path exePath;
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final List<PushListener> pushListeners = new CopyOnWriteArrayList<>();
    private final List<LogListener> logListeners = new CopyOnWriteArrayList<>();
    private final List<ExitListener> exitListeners = new CopyOnWriteArrayList<>();
    private final Object[] logRing = new Object[LOG_CAPACITY];
    private final AtomicInteger logCount = new AtomicInteger();
    private final AtomicInteger logWrite = new AtomicInteger();

    private volatile Process process;
    private volatile BufferedWriter stdin;
    private volatile boolean stopped;
    private volatile boolean quitting;
    private volatile boolean exited;
    private volatile boolean readySeen;
    private int nextId = 1;
    private String lastError = "";

    private final ExecutorService ioExecutor;
    private final ScheduledExecutorService watchdog;

    private static final class Pending {
        final CompletableFuture<Map<String, Object>> future;
        final ScheduledFuture<?> timeout;

        Pending(CompletableFuture<Map<String, Object>> future, ScheduledFuture<?> timeout) {
            this.future = future;
            this.timeout = timeout;
        }
    }

    /** 系统属性：显式指定引擎路径（最高优先级）。 */
    public static final String PROP_ENGINE_PATH = "tsim.engine";

    /** 用自动探测到的引擎路径构造。 */
    public EngineClient() {
        this(discoverEnginePath());
    }

    /**
     * 健壮地定位 engine\trade_sim.exe。
     *
     * <p>探测顺序：</p>
     * <ol>
     *   <li>系统属性 {@code -Dtsim.engine=<path>}（最高优先）；</li>
     *   <li>JVM 当前工作目录 {@code .\engine\trade_sim.exe}；</li>
     *   <li>当前工作目录向上 1..4 级的 {@code engine\trade_sim.exe}；</li>
     *   <li>jar / 类所在目录及其向上 1..4 级的 {@code engine\trade_sim.exe}；</li>
     * </ol>
     *
     * <p>全部失败时返回"当前工作目录 + engine\trade_sim.exe"（调用方用
     * {@link #describeSearch()} 给出中文报错）。</p>
     */
    public static Path discoverEnginePath() {
        List<Path> roots = new ArrayList<>();
        Path cwd = Paths.get("").toAbsolutePath().normalize();

        // 1) 系统属性显式指定（最高优先）
        String prop = System.getProperty(PROP_ENGINE_PATH);
        if (prop != null && !prop.trim().isEmpty()) {
            Path p = Paths.get(prop.trim()).toAbsolutePath().normalize();
            if (Files.isRegularFile(p)) {
                return p;
            }
        }

        // 2) jar / classes 所在目录优先（run.cmd 从仓库根启动、classpath 指向 build\frontend\classes）
        Path codeBase = codeBaseDir();
        if (codeBase != null) {
            addWithAncestors(roots, codeBase, 4);
        }
        // 3) 再退到 JVM 当前工作目录
        addWithAncestors(roots, cwd, 4);

        for (Path root : roots) {
            Path cand = root.resolve(RELATIVE_EXE).normalize();
            if (Files.isRegularFile(cand)) {
                return cand;
            }
        }
        return cwd.resolve(RELATIVE_EXE).normalize();
    }

    /** 把 dir 及其向上 maxUp 级祖先加入候选根列表（去重、保持顺序）。 */
    private static void addWithAncestors(List<Path> roots, Path dir, int maxUp) {
        Path p = dir;
        if (p != null && !roots.contains(p)) {
            roots.add(p);
        }
        for (int i = 0; i < maxUp && p != null && p.getParent() != null; i++) {
            p = p.getParent();
            if (!roots.contains(p)) {
                roots.add(p);
            }
        }
    }

    /** jar / classes 所在目录（可能为 null）。 */
    private static Path codeBaseDir() {
        try {
            java.security.CodeSource cs = EngineClient.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            Path dir = Paths.get(cs.getLocation().toURI());
            if (!Files.isDirectory(dir)) {
                dir = dir.getParent();
            }
            return dir == null ? null : dir.toAbsolutePath().normalize();
        } catch (RuntimeException | java.net.URISyntaxException ex) {
            return null;
        }
    }

    /** 找不到引擎时的中文排查文本。 */
    public static String describeSearch() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        return "未找到引擎程序 engine\\trade_sim.exe\n"
                + "  · 当前工作目录：" + cwd + "\n"
                + "  · 系统属性 " + PROP_ENGINE_PATH + "：" + (System.getProperty(PROP_ENGINE_PATH) == null
                        ? "（未设置）" : System.getProperty(PROP_ENGINE_PATH)) + "\n\n"
                + "请先构建引擎再启动：\n"
                + "  1. 运行 scripts\\build.cmd（生成 engine\\trade_sim.exe）\n"
                + "  2. 运行 scripts\\run.cmd（会从仓库根目录启动本程序）\n"
                + "  3. 或手动指定：java -D" + PROP_ENGINE_PATH
                + "=A:\\Downloads\\tg\\engine\\trade_sim.exe -jar trade-tower.jar";
    }

    /** 用指定可执行文件路径构造（测试可指向 mock 脚本）。 */
    public EngineClient(Path exePath) {
        this.exePath = exePath.toAbsolutePath();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "engine-io");
            t.setDaemon(true);
            return t;
        };
        ThreadFactory wf = r -> {
            Thread t = new Thread(r, "engine-watchdog");
            t.setDaemon(true);
            return t;
        };
        this.ioExecutor = Executors.newCachedThreadPool(tf);
        this.watchdog = Executors.newSingleThreadScheduledExecutor(wf);
    }

    /** 引擎可执行文件的绝对路径。 */
    public Path exePath() {
        return exePath;
    }

    /** 最近一次错误（中文），无错误为 ""。 */
    public String lastError() {
        return lastError;
    }

    /** 是否已观察到协议 §0 的可选就绪标记 "TRADE_SIM ready"（仅作参考）。 */
    public boolean readySignalSeen() {
        return readySeen;
    }

    /** 引擎进程是否仍在运行。 */
    public boolean isAlive() {
        Process p = process;
        return p != null && p.isAlive() && !exited;
    }

    /** 协议版本。 */
    public int protocolVersion() {
        return PROTOCOL_VERSION;
    }

    // ------------------------------------------------------------ 生命周期

    /**
     * 启动引擎子进程。
     *
     * @throws EngineError 可执行文件不存在或启动失败；message 为中文
     */
    public void start() {
        if (isAlive()) {
            return;
        }
        if (!Files.isRegularFile(exePath)) {
            lastError = describeSearch();
            throw new EngineError("ENGINE_NOT_FOUND", lastError);
        }
        if (!Files.isReadable(exePath)) {
            lastError = "引擎程序不可读（可能被杀毒软件锁定）：" + exePath;
            throw new EngineError("ENGINE_NOT_READABLE", lastError);
        }
        ProcessBuilder pb = new ProcessBuilder(exePath.toString(), "--stdio");
        // 工作目录固定为 exe 所在目录（engine\），避免相对路径问题；找不到父目录时退回 JVM 当前目录
        Path workDir = exePath.getParent();
        pb.directory(workDir == null ? Paths.get("").toAbsolutePath().toFile() : workDir.toFile());
        // 【契约】必须为 false：引擎的 stderr 携带 "TRADE_SIM ready" 等诊断行，
        // 若并入 stdout 会污染协议流（引擎/前端都只认 stdout 上的 JSON）。
        pb.redirectErrorStream(false);
        try {
            Process p = pb.start();
            this.process = p;
            this.exited = false;
            this.stopped = false;
            this.quitting = false;
            this.stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            ioExecutor.execute(() -> readLoop(p.getInputStream(), "engine-stdout"));
            ioExecutor.execute(() -> readLoop(p.getErrorStream(), "engine-stderr"));
            ioExecutor.execute(this::waitLoop);
            log("已启动引擎：" + exePath);
        } catch (IOException ex) {
            lastError = "启动引擎失败：" + ex.getMessage()
                    + "\n可执行文件：" + exePath;
            throw new EngineError("ENGINE_SPAWN_FAILED", lastError);
        }
    }

    /** 请求引擎退出并回收进程。 */
    public void shutdown() {
        if (!isAlive()) {
            return;
        }
        quitting = true;
        try {
            sendRaw("{\"id\":" + nextId() + ",\"cmd\":\"quit\",\"args\":{}}");
        } catch (RuntimeException ex) {
            log("发送 quit 失败：" + ex.getMessage());
        }
        try {
            BufferedWriter w = stdin;
            if (w != null) {
                w.close();
            }
        } catch (IOException ignored) {
            // 关闭 stdin 失败不影响后续强制结束
        }
        ioExecutor.execute(() -> {
            Process p = process;
            if (p == null) {
                return;
            }
            try {
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroy();
                }
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        });
    }

    /** 立即终止（窗口关闭时用，尽量礼貌）。 */
    public void kill() {
        stopped = true;
        Process p = process;
        if (p != null) {
            try {
                BufferedWriter w = stdin;
                if (w != null) {
                    w.close();
                }
            } catch (IOException ignored) {
                // 忽略
            }
            if (p.isAlive()) {
                p.destroy();
            }
        }
        for (Map.Entry<Integer, Pending> e : pending.entrySet()) {
            Pending pd = e.getValue();
            pd.timeout.cancel(false);
            pd.future.completeExceptionally(new EngineError("ENGINE_EXITED", "引擎已退出，请重启"));
        }
        pending.clear();
    }

    // ------------------------------------------------------------ 监听器

    /** 注册推送监听。 */
    public void addPushListener(PushListener l) {
        if (l != null) {
            pushListeners.add(l);
        }
    }

    /** 注册日志监听。 */
    public void addLogListener(LogListener l) {
        if (l != null) {
            logListeners.add(l);
        }
    }

    /** 注册退出监听。 */
    public void addExitListener(ExitListener l) {
        if (l != null) {
            exitListeners.add(l);
        }
    }

    /** 已有日志行数（最多 {@value #LOG_CAPACITY}）。 */
    public int logSize() {
        return logCount.get();
    }

    /** 取最近的日志（最新在后）。 */
    public List<String> logLines() {
        int n = logCount.get();
        List<String> out = new ArrayList<>(n);
        int start = Math.max(0, n - LOG_CAPACITY);
        for (int i = start; i < n; i++) {
            Object o = logRing[i % LOG_CAPACITY];
            if (o != null) {
                out.add((String) o);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ 请求

    /**
     * 异步发出一条请求。
     *
     * @param cmd  命令名
     * @param args 参数对象，可为 null
     * @return 返回 data 对象的 future；失败时抛出 {@link EngineError}
     */
    public CompletableFuture<Map<String, Object>> request(String cmd, Map<String, Object> args) {
        return request(cmd, args, DEFAULT_TIMEOUT_MS);
    }

    /** 带超时的异步请求。 */
    public CompletableFuture<Map<String, Object>> request(String cmd, Map<String, Object> args, long timeoutMs) {
        if (!isAlive()) {
            String msg = exited ? "引擎已退出，请重启" : "引擎尚未启动或已退出，请重启";
            return failed(new EngineError(exited ? "ENGINE_EXITED" : "ENGINE_NOT_STARTED", msg));
        }
        int id = nextId();
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("id", Long.valueOf(id));
        env.put("cmd", cmd);
        env.put("args", args == null ? new LinkedHashMap<String, Object>() : args);
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        ScheduledFuture<?> to = watchdog.schedule(() -> {
            Pending p = pending.remove(Integer.valueOf(id));
            if (p != null) {
                p.future.completeExceptionally(new EngineError("TIMEOUT",
                        "引擎响应超时（" + cmd + "，" + timeoutMs + "ms）"));
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        pending.put(Integer.valueOf(id), new Pending(f, to));
        try {
            sendRaw(Json.write(env));
        } catch (RuntimeException ex) {
            pending.remove(Integer.valueOf(id));
            to.cancel(false);
            f.completeExceptionally(ex);
        }
        return f;
    }

    /**
     * 阻塞式请求（仅在后台线程调用，UI 线程禁用）。
     *
     * @throws EngineError 超时或引擎返回错误
     */
    public Map<String, Object> requestSync(String cmd, Map<String, Object> args, long timeoutMs) {
        try {
            return request(cmd, args, timeoutMs).get(timeoutMs + 2000L, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new EngineError("TIMEOUT", "引擎响应超时（" + cmd + "）");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new EngineError("INTERRUPTED", "请求被中断（" + cmd + "）");
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof EngineError) {
                throw (EngineError) cause;
            }
            throw new EngineError("INTERNAL", "请求失败：" + cause.getMessage());
        }
    }

    private static <T> CompletableFuture<T> failed(RuntimeException ex) {
        CompletableFuture<T> f = new CompletableFuture<>();
        f.completeExceptionally(ex);
        return f;
    }

    private int nextId() {
        synchronized (this) {
            return nextId++;
        }
    }

    private void sendRaw(String line) {
        BufferedWriter w = stdin;
        if (w == null || !isAlive()) {
            throw new EngineError("ENGINE_EXITED", "引擎已退出，请重启");
        }
        try {
            synchronized (this) {
                w.write(line);
                w.write('\n');
                w.flush();
            }
        } catch (IOException ex) {
            throw new EngineError("ENGINE_IO", "写入引擎失败：" + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ 协议命令封装

    /** hello 握手。 */
    public CompletableFuture<Map<String, Object>> hello() {
        return request("hello", new LinkedHashMap<>());
    }

    /** 新游戏。 */
    public CompletableFuture<Map<String, Object>> newGame(long seed, String name, double cashStock,
                                                          double cashForex, String difficulty) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("seed", Long.valueOf(seed));
        a.put("name", name == null ? "玩家" : name);
        a.put("cashStock", Double.valueOf(cashStock));
        a.put("cashForex", Double.valueOf(cashForex));
        a.put("difficulty", difficulty == null ? "normal" : difficulty);
        return request("newgame", a, 30_000L);
    }

    /** 时间推进。 */
    public CompletableFuture<Map<String, Object>> tick(int n, String mode) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("n", Long.valueOf(Math.max(1, n)));
        a.put("mode", mode == null ? "auto" : mode);
        return request("tick", a, 60_000L);
    }

    /** 全量快照。 */
    public CompletableFuture<Map<String, Object>> snapshot() {
        return request("snapshot", new LinkedHashMap<>());
    }

    /** 行情。 */
    public CompletableFuture<Map<String, Object>> market(String mk, String symbol) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("market", mk == null ? "all" : mk);
        if (symbol != null && !symbol.isEmpty()) {
            a.put("symbol", symbol);
        }
        return request("market", a, 30_000L);
    }

    /** 单标的报价。 */
    public CompletableFuture<Map<String, Object>> quote(String symbol) {
        return request("quote", Json.obj("symbol", symbol));
    }

    /** 买/卖（股票）。 */
    public CompletableFuture<Map<String, Object>> buySell(String cmd, String symbol, int qty, String type, double price) {
        return buySell(cmd, symbol, qty, type, price, 1);
    }

    /**
     * 买/卖（股票），带融资杠杆。
     *
     * @param leverage 融资杠杆 1..25（协议 v1.0.2）；1 = 不用杠杆。仅买入有意义，卖出请传 1。
     */
    public CompletableFuture<Map<String, Object>> buySell(String cmd, String symbol, int qty, String type,
                                                          double price, int leverage) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("symbol", symbol);
        a.put("qty", Long.valueOf(qty));
        a.put("type", type == null ? "market" : type);
        if ("limit".equals(type)) {
            a.put("price", Double.valueOf(price));
        }
        if (leverage > 1 && "buy".equals(cmd)) {
            a.put("leverage", Long.valueOf(leverage));
        }
        return request(cmd, a);
    }

    /** 撤单。 */
    public CompletableFuture<Map<String, Object>> cancel(int orderId) {
        return request("cancel", Json.obj("orderId", Long.valueOf(orderId)));
    }

    /** 外汇开仓。 */
    public CompletableFuture<Map<String, Object>> forexOpen(String symbol, String side, int lots, int leverage,
                                                            double stopLoss, double takeProfit) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("symbol", symbol);
        a.put("side", side);
        a.put("lots", Long.valueOf(lots));
        a.put("leverage", Long.valueOf(leverage));
        a.put("stopLoss", Double.valueOf(stopLoss));
        a.put("takeProfit", Double.valueOf(takeProfit));
        return request("open", a);
    }

    /** 外汇平仓（lots<=0 表示全平）。 */
    public CompletableFuture<Map<String, Object>> forexClose(int positionId, int lots) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("positionId", Long.valueOf(positionId));
        if (lots > 0) {
            a.put("lots", Long.valueOf(lots));
        }
        return request("close", a);
    }

    /** 挂单列表。 */
    public CompletableFuture<Map<String, Object>> orders(String mk) {
        return request("orders", Json.obj("market", mk == null ? "all" : mk));
    }

    /** 成交流水。 */
    public CompletableFuture<Map<String, Object>> history(int limit, String mk) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("limit", Long.valueOf(limit));
        a.put("market", mk == null ? "all" : mk);
        return request("history", a);
    }

    /** 新闻。 */
    public CompletableFuture<Map<String, Object>> news(int limit, boolean unreadOnly) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("limit", Long.valueOf(limit));
        a.put("unreadOnly", Boolean.valueOf(unreadOnly));
        return request("news", a);
    }

    /** 设置。 */
    public CompletableFuture<Map<String, Object>> settings(Map<String, Object> partial) {
        return request("settings", partial == null ? new LinkedHashMap<>() : partial);
    }

    /** 时钟控制。 */
    public CompletableFuture<Map<String, Object>> clock(String action, Double speed, Integer tickMs) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("action", action);
        if (speed != null) {
            a.put("speed", speed);
        }
        if (tickMs != null) {
            a.put("tickMs", Long.valueOf(tickMs));
        }
        return request("clock", a);
    }

    /** 作弊器。 */
    public CompletableFuture<Map<String, Object>> cheat(Map<String, Object> args) {
        Map<String, Object> a = new LinkedHashMap<>();
        Object op = args == null ? null : args.get("op");
        a.put("op", op == null ? "list" : op);
        if (args != null) {
            for (Map.Entry<String, Object> e : args.entrySet()) {
                if (!"op".equals(e.getKey())) {
                    a.put(e.getKey(), e.getValue());
                }
            }
        }
        return request("cheat", a, 30_000L);
    }

    // ------------------------------------------------------------ 便捷反序列化

    /** 请求 snapshot 并解析。 */
    public CompletableFuture<Snapshot> snapshotModel() {
        return snapshot().thenApply(Snapshot::of);
    }

    /** 请求 market 并解析。 */
    public CompletableFuture<MarketData> marketModel(String mk) {
        return market(mk, null).thenApply(MarketData::of);
    }

    /** 请求 tick 并解析。 */
    public CompletableFuture<TickResult> tickModel(int n, String mode) {
        return tick(n, mode).thenApply(TickResult::of);
    }

    /** 请求 history 并解析。 */
    public CompletableFuture<List<TradeInfo>> historyModel(int limit) {
        return history(limit, "all").thenApply(d -> {
            List<TradeInfo> out = new ArrayList<>();
            for (Object o : Json.array(d, "trades")) {
                out.add(TradeInfo.of(Json.asObject(o)));
            }
            return out;
        });
    }

    /** 请求 orders 并解析。 */
    public CompletableFuture<List<OrderInfo>> ordersModel(String mk) {
        return orders(mk).thenApply(d -> {
            List<OrderInfo> out = new ArrayList<>();
            for (Object o : Json.array(d, "orders")) {
                out.add(OrderInfo.of(Json.asObject(o)));
            }
            return out;
        });
    }

    /** 请求 news 并解析。 */
    public CompletableFuture<List<NewsInfo>> newsModel(int limit, boolean unreadOnly) {
        return news(limit, unreadOnly).thenApply(d -> {
            List<NewsInfo> out = new ArrayList<>();
            for (Object o : Json.array(d, "news")) {
                out.add(NewsInfo.of(Json.asObject(o)));
            }
            return out;
        });
    }

    /** 请求 clock get 并解析。 */
    public CompletableFuture<ClockState> clockState() {
        return clock("get", null, null).thenApply(ClockState::of);
    }

    /** 请求 settings（空参数=查询）并解析。 */
    public CompletableFuture<Settings> settingsModel(Map<String, Object> partial) {
        return settings(partial).thenApply(Settings::of);
    }

    /** 买/卖并解析。 */
    public CompletableFuture<TradeResult> tradeModel(String cmd, String symbol, int qty, String type, double price) {
        return tradeModel(cmd, symbol, qty, type, price, 1);
    }

    /**
     * 股票做空 / 平空（协议 v1.0.4）。
     *
     * @param action   short = 开空，cover = 平空
     * @param leverage 做空杠杆 1..5（平空忽略）
     */
    public CompletableFuture<Map<String, Object>> stockShortModel(String action, String symbol, int qty,
                                                                  String type, double price, int leverage) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("symbol", symbol);
        a.put("qty", Long.valueOf(qty));
        if ("short".equals(action)) {
            a.put("type", type == null ? "market" : type);
            if ("limit".equals(type)) {
                a.put("price", Double.valueOf(price));
            }
            if (leverage > 1) {
                a.put("leverage", Long.valueOf(leverage));
            }
        }
        return request(action, a);
    }

    /** 买/卖并解析（带融资杠杆）。 */
    public CompletableFuture<TradeResult> tradeModel(String cmd, String symbol, int qty, String type,
                                                     double price, int leverage) {
        return buySell(cmd, symbol, qty, type, price, leverage).thenApply(TradeResult::of);
    }

    // ------------------------------------------------------------ 内部线程

    private void waitLoop() {
        Process p = process;
        if (p == null) {
            return;
        }
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
        }
        exited = true;
        boolean expected = quitting || stopped;
        String msg = expected ? "引擎已退出（正常关闭）" : "引擎已退出，请重启（exit=" + code + "）";
        lastError = msg;
        log(msg);
        for (Map.Entry<Integer, Pending> e : pending.entrySet()) {
            Pending pd = e.getValue();
            pd.timeout.cancel(false);
            pd.future.completeExceptionally(new EngineError("ENGINE_EXITED", "引擎已退出，请重启"));
        }
        pending.clear();
        for (ExitListener l : exitListeners) {
            try {
                l.onExit(code, expected);
            } catch (RuntimeException ex) {
                log("退出回调异常：" + ex);
            }
        }
    }

    private void readLoop(InputStream in, final String tag) {
        boolean stdout = "engine-stdout".equals(tag);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty()) {
                    continue;
                }
                if (stdout) {
                    handleLine(s);
                } else {
                    // 协议 §0：引擎会在 stderr 打印 "TRADE_SIM ready" 作为可选的就绪辅助信号。
                    // 前端只把它记进日志面板，绝不用它阻塞 EDT；真正就绪以 hello 帧为准。
                    if (s.contains("TRADE_SIM ready")) {
                        readySeen = true;
                        log("[就绪] 引擎已输出 TRADE_SIM ready（协议 §0 可选就绪信号）");
                    }
                    log(s);
                }
            }
        } catch (IOException ex) {
            if (!exited) {
                log(tag + " 读取结束：" + ex.getMessage());
            }
        }
    }

    private void handleLine(String line) {
        Object parsed;
        try {
            parsed = Json.parse(line);
        } catch (RuntimeException ex) {
            log("[协议] 无法解析的引擎输出，已忽略：" + shorten(line) + "  (" + ex.getMessage() + ")");
            return;
        }
        if (!(parsed instanceof Map)) {
            log("[协议] 引擎输出了非对象 JSON，已忽略：" + shorten(line));
            return;
        }
        Map<String, Object> msg = Json.asObject(parsed);
        if (Json.bool(msg, "push")) {
            String type = Json.str(msg, "type");
            Map<String, Object> data = Json.object(msg, "data");
            for (PushListener l : pushListeners) {
                try {
                    l.onPush(type, data);
                } catch (RuntimeException ex) {
                    log("推送回调异常：" + ex);
                }
            }
            return;
        }
        int id = (int) Json.lng(msg, "id");
        if (id == 0) {
            String type = Json.str(Json.object(msg, "data"), "type");
            if ("hello".equals(type)) {
                Map<String, Object> d = Json.object(msg, "data");
                int ver = (int) Json.lng(d, "protocol", PROTOCOL_VERSION);
                if (ver != PROTOCOL_VERSION) {
                    Kits.reportProtocolMismatch(ver);
                }
                log("引擎握手成功：" + Json.str(d, "engine") + " " + Json.str(d, "version")
                        + " (protocol " + ver + ")");
                return;
            }
            log("[协议] 收到 id=0 的非推送消息：" + shorten(line));
            return;
        }
        Pending pd = pending.remove(Integer.valueOf(id));
        if (pd == null) {
            log("[协议] 收到无对应请求的响应 id=" + id + "：" + shorten(line));
            return;
        }
        pd.timeout.cancel(false);
        Map<String, Object> data = Json.object(msg, "data");
        Map<String, Object> err = Json.object(msg, "error");
        String code = Json.str(err, "code");
        String message = Json.str(err, "message");

        // 双兼容判定：
        //  (a) 协议 §1 标准形态：{"id":N,"ok":false,"error":{"code":..,"message":..}}
        //  (b) 过渡期形态：{"id":N,"ok":true,"data":{"__fail":true,"code":..,"message":..}}
        //      —— engine-core 修完后 (b) 会被删除，这里保留向后/向前兼容，行为一致。
        boolean protocolFailure = !Json.bool(msg, "ok");
        boolean legacyFailure = Json.bool(data, "__fail");
        if (protocolFailure || legacyFailure) {
            if (protocolFailure) {
                if (legacyFailure && code.isEmpty()) {
                    code = Json.str(data, "code");
                }
                if (legacyFailure && message.isEmpty()) {
                    message = Json.str(data, "message");
                }
            } else {
                code = Json.str(data, "code");
                message = Json.str(data, "message");
            }
            log("[引擎失败] " + (code.isEmpty() ? "?" : code) + "：" + message);
            pd.future.completeExceptionally(new EngineError(code,
                    message.isEmpty() ? errorText(code) : message));
            return;
        }
        pd.future.complete(data);
    }

    /** 冻结错误码 -> 默认中文提示（引擎已给 message 时优先用引擎的）。 */
    public static String errorText(String code) {
        switch (code) {
            case "NO_GAME":
                return "请先开始新游戏";
            case "NO_SUCH_SYMBOL":
                return "未知交易代码";
            case "BAD_ARG":
                return "参数错误";
            case "BAD_QTY":
                return "股票数量必须是100的整数倍";
            case "BAD_LOTS":
                return "手数非法";
            case "BAD_PRICE":
                return "价格非法";
            case "INSUFFICIENT_CASH":
                return "可用资金不足";
            case "INSUFFICIENT_POSITION":
                return "可卖持仓不足";
            case "INSUFFICIENT_MARGIN":
                return "保证金不足";
            case "T1_LOCKED":
                return "T+1 锁定，次日可卖";
            case "MARKET_HALTED":
                return "该标的已停牌";
            case "NO_MARKET_DATA":
                return "行情数据缺失";
            case "NO_POSITION":
                return "没有该持仓";
            case "UNKNOWN_CMD":
                return "引擎不支持该命令";
            case "INTERNAL":
                return "引擎内部错误";
            case "TIMEOUT":
                return "引擎响应超时";
            case "ENGINE_EXITED":
                return "引擎已退出，请重启";
            default:
                return code.isEmpty() ? "未知错误" : ("引擎错误：" + code);
        }
    }

    private void log(String line) {
        if (line == null) {
            return;
        }
        int idx = logWrite.getAndIncrement();
        logRing[idx % LOG_CAPACITY] = line;
        int n = logCount.incrementAndGet();
        if (n > LOG_CAPACITY * 4) {
            logCount.set(LOG_CAPACITY + (n % LOG_CAPACITY));
        }
        for (LogListener l : logListeners) {
            try {
                l.onLog(line);
            } catch (RuntimeException ignored) {
                // 单个监听器异常不影响其它监听器
            }
        }
    }

    private static String shorten(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    /** 全局协议状态（供欢迎页/状态栏查询）。 */
    public static final class Kits {
        private static final AtomicBoolean MISMATCH = new AtomicBoolean(false);

        private Kits() {
        }

        static void reportProtocolMismatch(int engineVersion) {
            MISMATCH.set(true);
            System.err.println("[TradeTower] 协议版本不一致：引擎=" + engineVersion
                    + "，前端=" + PROTOCOL_VERSION);
        }

        /** 是否检测到协议版本不一致。 */
        public static boolean mismatch() {
            return MISMATCH.get();
        }
    }
}