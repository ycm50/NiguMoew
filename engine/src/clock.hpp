// clock.hpp - 时钟：自动按 tickMs/speed 推进时间片并主动推送 push 行
#pragma once

#include "engine.hpp"

#include <string>
#include <atomic>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <chrono>
#include <functional>

namespace tsim {

using PushFn = std::function<void(const std::string& line)>;

class Clock {
public:
    Clock() = default;
    ~Clock() { stop(); }

    Clock(const Clock&) = delete;
    Clock& operator=(const Clock&) = delete;

    void setPushFn(PushFn fn) { push_ = std::move(fn); }

    void start(Engine& e) {
        stop();
        stopRequested_ = false;
        running_ = true;
        worker_ = std::thread([this, &e]() { loop(e); });
    }

    void stop() {
        {
            std::lock_guard<std::mutex> lk(mtx_);
            stopRequested_ = true;
            running_ = false;
        }
        cv_.notify_all();
        if (worker_.joinable()) worker_.join();
    }

    bool running() const { return running_.load(); }

    // 让时钟线程立即按新倍速重新计算间隔
    void poke() { cv_.notify_all(); }

private:
    void loop(Engine& e) {
        while (true) {
            double intervalMs = 0.0;
            {
                std::unique_lock<std::mutex> lk(mtx_);
                if (stopRequested_.load() || !running_.load()) break;
                intervalMs = e.tickIntervalMs();
                if (!(intervalMs > 0.0)) intervalMs = 1.0;
                cv_.wait_for(lk, std::chrono::milliseconds(static_cast<long long>(intervalMs)),
                             [this]() { return stopRequested_.load() || !running_.load(); });
            }
            if (stopRequested_.load() || !running_.load()) break;
            // 一次推进：拿到本片的 events；爆仓事实必须**在同一次迭代内**推送，
            // 否则若时钟随即被 stop（或用例只跑几十毫秒），bankrupt 推送会丢失。
            Json data = e.clockStepPush();
            std::string outTick;
            std::string outBankrupt;
            if (push_) {
                outTick = "{\"id\":0,\"push\":true,\"type\":\"tick\",\"data\":";
                outTick += data.dump();
                outTick += "}";
            }
            // 本片内（或此前遗留）的爆仓：立刻生成 bankrupt 推送，并做一次去重标记
            if ((e.bankruptStock || e.bankruptForex) && !e.bankruptPushed) {
                e.bankruptPushed = true;
                if (push_) {
                    Json bd = Json::obj();
                    bd["account"] = Json(e.bankruptForex ? "forex" : "stock");
                    outBankrupt = "{\"id\":0,\"push\":true,\"type\":\"bankrupt\",\"data\":";
                    outBankrupt += bd.dump();
                    outBankrupt += "}";
                }
            }
            // 先推 tick（它承载本片的 events），再推 bankrupt；
            // 两者在同一次迭代内成对输出，保证前端一定收得到爆仓事实。
            if (!outTick.empty()) push_(outTick);
            if (!outBankrupt.empty()) push_(outBankrupt);
        }
        running_ = false;
    }

    std::thread worker_;
    std::atomic<bool> running_{false};
    std::atomic<bool> stopRequested_{false};
    std::mutex mtx_;
    std::condition_variable cv_;
    PushFn push_;
};

}  // namespace tsim
