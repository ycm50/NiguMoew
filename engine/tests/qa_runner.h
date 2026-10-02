// ============================================================================
// qa_runner.h — 真实子进程驱动（Windows 命名管道）—— 独立实现，不用被测代码
// 功能:
//   * CreateProcessW 启动 trade_sim.exe --stdio
//   * 三个子线程: 写 stdin / 读 stdout / 读 stderr
//   * 每次 send() 记录请求原文, 每条 stdout 行记录原始字节
//   * request() 发送并同步等待"同 id 响应", 同时把期间到达的 push 行/乱序行全部记账
//   * 超时保护; quit() 后等待进程退出并返回 exit code
// ============================================================================
#pragma once
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <string>
#include <vector>
#include <deque>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <atomic>
#include <chrono>
#include <functional>
#include <cstdio>

#include "qa_json.h"

namespace qa {

struct RawLine { std::string text; double atMs; };

class Runner {
public:
    ~Runner() { kill(); }

    // ------------- 启动 -------------
    bool start(const std::string& exePath, const std::string& workDir,
               const std::string& args = "--stdio") {
        exePath_ = exePath;
        SECURITY_ATTRIBUTES sa{};
        sa.nLength = sizeof(sa);
        sa.bInheritHandle = TRUE;

        if (!CreatePipe(&inR_, &inW_, &sa, 0)) return false;
        if (!CreatePipe(&outR_, &outW_, &sa, 0)) return false;
        if (!CreatePipe(&errR_, &errW_, &sa, 0)) return false;
        SetHandleInformation(inW_, HANDLE_FLAG_INHERIT, 0);
        SetHandleInformation(outR_, HANDLE_FLAG_INHERIT, 0);
        SetHandleInformation(errR_, HANDLE_FLAG_INHERIT, 0);

        std::wstring wexe = widen(exePath);
        std::wstring wdir = widen(workDir);
        std::wstring wcmd = L"\"" + wexe + L"\" " + widen(args);

        STARTUPINFOW si{};
        si.cb = sizeof(si);
        si.dwFlags = STARTF_USESTDHANDLES;
        si.hStdInput = inR_;
        si.hStdOutput = outW_;
        si.hStdError = errW_;

        std::vector<wchar_t> cmd(wcmd.begin(), wcmd.end());
        cmd.push_back(0);

        BOOL ok = CreateProcessW(wexe.c_str(), cmd.data(), nullptr, nullptr, TRUE,
                                 CREATE_NO_WINDOW, nullptr,
                                 wdir.empty() ? nullptr : wdir.c_str(), &si, &pi_);
        if (!ok) {
            launcherr_ = "CreateProcessW failed, GetLastError=" + std::to_string((long)GetLastError());
            return false;
        }
        CloseHandle(pi_.hThread);
        CloseHandle(inR_);  inR_ = nullptr;
        CloseHandle(outW_); outW_ = nullptr;
        CloseHandle(errW_); errW_ = nullptr;
        running_ = true;
        tOut_ = std::thread([this]{ pump(outR_, &outLines_, &outMx_, &outCv_, true);  });
        tErr_ = std::thread([this]{ pump(errR_, &errLines_, &errMx_, &errCv_, false); });
        startMs_ = nowMs();
        return true;
    }

    bool started() const { return running_; }
    const std::string& launchError() const { return launcherr_; }

    // ------------- 发送 / 接收 -------------
    void writeLine(const std::string& s) {
        std::lock_guard<std::mutex> lk(wMx_);
        std::string data = s;
        data += "\n";
        DWORD wrote = 0;
        WriteFile(inW_, data.data(), (DWORD)data.size(), &wrote, nullptr);
    }

    // 发送任意原文（可能是非 JSON / 畸形输入），并记账
    void sendRaw(const std::string& raw, bool newline = true) {
        sent_.push_back(raw);
        std::lock_guard<std::mutex> lk(wMx_);
        std::string data = raw;
        if (newline) data += "\n";
        DWORD wrote = 0;
        WriteFile(inW_, data.data(), (DWORD)data.size(), &wrote, nullptr);
    }

    // 发送 JSON 请求
    void send(const std::string& json) { sendRaw(json); }

    // 从 stdout 队列取一行（带超时）
    bool readLine(std::string& out, int timeoutMs) {
        std::unique_lock<std::mutex> lk(outMx_);
        if (!outCv_.wait_for(lk, std::chrono::milliseconds(timeoutMs),
                             [this]{ return !outLines_.empty() || !running_; }))
            return false;
        if (outLines_.empty()) return false;
        out = outLines_.front().text;
        outLines_.pop_front();
        return true;
    }

    // 把 stdout/stderr 里已有的行全部取走
    std::vector<RawLine> drainOut() {
        std::lock_guard<std::mutex> lk(outMx_);
        std::vector<RawLine> v(outLines_.begin(), outLines_.end());
        outLines_.clear();
        return v;
    }
    std::vector<RawLine> drainErr() {
        std::lock_guard<std::mutex> lk(errMx_);
        std::vector<RawLine> v(errLines_.begin(), errLines_.end());
        errLines_.clear();
        return v;
    }
    std::vector<RawLine> peekErr() {
        std::lock_guard<std::mutex> lk(errMx_);
        return std::vector<RawLine>(errLines_.begin(), errLines_.end());
    }

    // 等待 stdout 出现满足谓词的行；返回该行。期间丢弃的不匹配行记入 pending_。
    bool waitFor(std::function<bool(const std::string&)> pred, std::string& out, int timeoutMs) {
        auto deadline = nowMs() + timeoutMs;
        for (;;) {
            double left = deadline - nowMs();
            if (left <= 0) return false;
            std::string line;
            if (!readLine(line, (int)left)) return false;
            if (pred(line)) { out = line; return true; }
            pending_.push_back(line);
        }
    }

    // 等待 READY 标记（stderr 里找 TRADE_SIM ready）——不走 out 队列
    bool waitStderrContains(const std::string& needle, int timeoutMs) {
        auto deadline = nowMs() + timeoutMs;
        size_t seen = 0;
        std::unique_lock<std::mutex> lk(errMx_);
        for (;;) {
            if (errLines_.size() > seen) {
                for (size_t k = seen; k < errLines_.size(); k++)
                    if (errLines_[k].text.find(needle) != std::string::npos) return true;
                seen = errLines_.size();
            }
            if (!running_) return false;
            if (errCv_.wait_for(lk, std::chrono::milliseconds(50)) == std::cv_status::timeout) {}
            if (nowMs() > deadline) return false;
        }
    }

    bool alive() {
        if (!running_) return false;
        DWORD c = 0;
        if (GetExitCodeProcess(pi_.hProcess, &c) && c != STILL_ACTIVE) return false;
        return true;
    }

    bool waitExit(int timeoutMs, DWORD* codeOut) {
        DWORD r = WaitForSingleObject(pi_.hProcess, (DWORD)timeoutMs);
        if (r == WAIT_TIMEOUT) return false;
        DWORD c = 0;
        GetExitCodeProcess(pi_.hProcess, &c);
        if (codeOut) *codeOut = c;
        running_ = false;
        return true;
    }

    void closeStdin() {
        std::lock_guard<std::mutex> lk(wMx_);
        if (inW_) { CloseHandle(inW_); inW_ = nullptr; }
    }

    void kill() {
        if (pi_.hProcess) {
            running_ = false;
            TerminateProcess(pi_.hProcess, 0xDEAD);
            CloseHandle(pi_.hProcess);
            pi_.hProcess = nullptr;
        }
        closeStdin();
        if (tOut_.joinable()) tOut_.join();
        if (tErr_.joinable()) tErr_.join();
        if (inW_)  { CloseHandle(inW_);  inW_ = nullptr; }
        if (inR_)  { CloseHandle(inR_);  inR_ = nullptr; }
        if (outR_) { CloseHandle(outR_); outR_ = nullptr; }
        if (errR_) { CloseHandle(errR_); errR_ = nullptr; }
    }

    // 已发送请求原文（用于证据）
    std::vector<std::string> sent_;
    std::deque<std::string> pending_;   // waitFor 期间被跳过的行（push 等）
    std::string exePath_;

    static double nowMs() {
        using namespace std::chrono;
        return (double)duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count() / 1000.0;
    }

private:
    HANDLE inR_ = nullptr, inW_ = nullptr, outR_ = nullptr, outW_ = nullptr, errR_ = nullptr, errW_ = nullptr;
    PROCESS_INFORMATION pi_{};
    std::atomic<bool> running_{false};
    std::string launcherr_;
    double startMs_ = 0;

    std::thread tOut_, tErr_;
    std::deque<RawLine> outLines_, errLines_;
    std::mutex outMx_, errMx_, wMx_;
    std::condition_variable outCv_, errCv_;

    static std::wstring widen(const std::string& s) {
        if (s.empty()) return L"";
        int n = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), nullptr, 0);
        std::wstring w(n, 0);
        MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), &w[0], n);
        return w;
    }
    static std::string narrow(const char* p, int n) {
        if (n <= 0) return "";
        int m = MultiByteToWideChar(CP_UTF8, 0, p, n, nullptr, 0);
        std::wstring w(m, 0);
        MultiByteToWideChar(CP_UTF8, 0, p, n, &w[0], m);
        int k = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), m, nullptr, 0, nullptr, nullptr);
        std::string s(k, 0);
        WideCharToMultiByte(CP_UTF8, 0, w.c_str(), m, &s[0], k, nullptr, nullptr);
        return s;
    }

    void pump(HANDLE h, std::deque<RawLine>* dq, std::mutex* mx, std::condition_variable* cv, bool isOut) {
        std::string buf;
        char tmp[4096];
        DWORD got = 0;
        for (;;) {
            if (!ReadFile(h, tmp, sizeof(tmp), &got, nullptr) || got == 0) break;
            buf.append(tmp, got);
            size_t pos;
            while ((pos = buf.find('\n')) != std::string::npos) {
                std::string line = buf.substr(0, pos);
                buf.erase(0, pos + 1);
                if (!line.empty() && line.back() == '\r') line.pop_back();
                pushLine(dq, mx, cv, line, isOut);
            }
        }
        if (!buf.empty()) pushLine(dq, mx, cv, buf, isOut);
        {
            std::lock_guard<std::mutex> lk(*mx);
            if (isOut) running_ = false;
        }
        cv->notify_all();
    }

    void pushLine(std::deque<RawLine>* dq, std::mutex* mx, std::condition_variable* cv,
                  const std::string& line, bool isOut) {
        {
            std::lock_guard<std::mutex> lk(*mx);
            dq->push_back(RawLine{line, nowMs()});
        }
        cv->notify_all();
        (void)isOut;
    }
};

// ---------------------------------------------------------------------------
// 高层：一次请求 -> 恰好一条响应；同时收集期间的 push 行
// ---------------------------------------------------------------------------
struct Exchange {
    std::string requestText;
    std::string responseText;      // 同 id 的那一行
    bool gotResponse = false;
    bool timedOut = false;
    std::vector<std::string> extraLines;   // 在等到目标响应之前跳过的行
    std::vector<std::string> afterLines;   // 之后 20ms 内到达的额外行（用于检测"一个请求多个响应"）
};

// 发送并等待 id 匹配（或 id=0 的 catch-all）
inline Exchange request(Runner& r, int id, const std::string& cmd,
                        const std::string& argsJson = "{}",
                        int timeoutMs = 5000,
                        bool allowId0 = false) {
    Exchange ex;
    std::string req = "{\"id\":" + std::to_string(id) + ",\"cmd\":\"" + cmd + "\",\"args\":" + argsJson + "}";
    ex.requestText = req;
    r.send(req);

    std::string wantId = "\"id\":" + std::to_string(id);
    std::string altId  = "\"id\": " + std::to_string(id);
    auto pred = [&](const std::string& line) {
        if (allowId0 && (line.find("\"id\":0") != std::string::npos && line.find("\"push\"") == std::string::npos))
            return true;
        if (line.find(wantId) == std::string::npos && line.find(altId) == std::string::npos) return false;
        if (line.find("\"push\":true") != std::string::npos) return false;
        return true;
    };
    std::string line;
    if (r.waitFor(pred, line, timeoutMs)) {
        ex.gotResponse = true;
        ex.responseText = line;
        ex.extraLines = std::vector<std::string>(r.pending_.begin(), r.pending_.end());
        r.pending_.clear();
        // 再等 30ms 看有没有"多出来的第二响应"
        std::string more;
        auto t0 = Runner::nowMs();
        while (Runner::nowMs() - t0 < 30) {
            if (!r.readLine(more, 10)) continue;
            ex.afterLines.push_back(more);
        }
    } else {
        ex.timedOut = true;
        ex.extraLines = std::vector<std::string>(r.pending_.begin(), r.pending_.end());
        r.pending_.clear();
    }
    return ex;
}

// 只发送，不等待（用于 burst/并发测试）
inline std::string makeReq(int id, const std::string& cmd, const std::string& argsJson = "{}") {
    return "{\"id\":" + std::to_string(id) + ",\"cmd\":\"" + cmd + "\",\"args\":" + argsJson + "}";
}

} // namespace qa
