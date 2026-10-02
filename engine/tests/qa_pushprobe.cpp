#include "qa_json.h"
#include "qa_runner.h"
#include "qa_assert.h"
#include <cstdio>
#include <string>
#include <set>
#include <vector>
using namespace qaj;

static void runCase(const std::string& exe, const std::string& wd, const char* title,
                    const std::vector<std::string>& setup, int observeMs) {
    qa::Runner r;
    if (!r.start(exe, wd)) { printf("START FAILED\n"); return; }
    std::string hello; r.readLine(hello, 6000);
    printf("================ %s ================\n", title);
    int id = 0;
    for (auto& s : setup) {
        id++;
        auto idstr = std::string("\"id\":") + std::to_string(id);
        r.send("{\"id\":" + std::to_string(id) + s);
        std::string line;
        std::string want = "\"id\":" + std::to_string(id);
        auto pred = [&](const std::string& l){ return l.find(want) != std::string::npos && l.find("\"push\":true") == std::string::npos; };
        if (r.waitFor(pred, line, 20000)) {
            printf("  REQ  id=%d %s\n", id, s.substr(0, 90).c_str());
            printf("  RESP %s\n", qa::clip(line, 260).c_str());
            r.pending_.clear();
        } else {
            printf("  REQ  id=%d %s\n  RESP <TIMEOUT>\n", id, s.substr(0, 90).c_str());
        }
    }
    // observe push lines
    std::vector<std::string> pushes;
    double t0 = qa::Runner::nowMs();
    while (qa::Runner::nowMs() - t0 < observeMs) {
        std::string l;
        if (!r.readLine(l, 200)) continue;
        pushes.push_back(l);
    }
    std::set<std::string> kinds;
    int tickPush = 0, bkPush = 0, nonJson = 0;
    for (auto& l : pushes) {
        ValuePtr v;
        try { v = qaj::parse(l); } catch (...) { nonJson++; continue; }
        auto pf = v->get("push");
        if (!(pf && pf->isBool() && pf->b)) continue;
        auto ty = v->get("type");
        if (ty && ty->isStr()) { if (ty->str == "tick") tickPush++; else if (ty->str == "bankrupt") bkPush++; }
        size_t pos = 0;
        while ((pos = l.find("\"kind\":\"", pos)) != std::string::npos) {
            size_t b = pos + 8, e = l.find('"', b);
            if (e == std::string::npos) break;
            kinds.insert(l.substr(b, e - b));
            pos = e;
        }
    }
    std::string ks;
    for (auto& k : kinds) { if (!ks.empty()) ks += ","; ks += k; }
    printf("  push 总行数=%zu  type=tick=%d  type=bankrupt=%d  非JSON=%d\n", pushes.size(), tickPush, bkPush, nonJson);
    printf("  push 中出现过的 kind = [%s]\n", ks.c_str());
    // print any push line containing bankrupt
    for (auto& l : pushes) {
        if (l.find("bankrupt") != std::string::npos) printf("  >>> %s\n", qa::clip(l, 300).c_str());
    }
    r.kill();
    printf("\n");
}

int main(int argc, char** argv) {
    std::string exe = argc > 1 ? argv[1] : "build\\engine\\trade_sim.exe";
    std::string wd  = argc > 2 ? argv[2] : "A:\\Downloads\\tg";

    // Case A: bankruptcy  (setup entries are the body after "id":N,)
    runCase(exe, wd, "A: bankruptcy -> clock start", {
        ",\"cmd\":\"newgame\",\"args\":{\"seed\":8002,\"cashForex\":1000}}",
        ",\"cmd\":\"open\",\"args\":{\"symbol\":\"EURUSD\",\"side\":\"long\",\"lots\":500,\"leverage\":1000}}",
        ",\"cmd\":\"cheat\",\"args\":{\"op\":\"price\",\"symbol\":\"EURUSD\",\"pct\":-0.95}}",
        ",\"cmd\":\"clock\",\"args\":{\"action\":\"set\",\"speed\":16,\"tickMs\":100}}",
        ",\"cmd\":\"clock\",\"args\":{\"action\":\"start\"}}"
    }, 3000);

    // Case B: order_filled
    runCase(exe, wd, "B: order_filled -> clock start", {
        ",\"cmd\":\"newgame\",\"args\":{\"seed\":31337,\"cashForex\":50000}}",
        ",\"cmd\":\"buy\",\"args\":{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"limit\",\"price\":2376.61}}",
        ",\"cmd\":\"buy\",\"args\":{\"symbol\":\"SH600519\",\"qty\":100,\"type\":\"market\"}}",
        ",\"cmd\":\"open\",\"args\":{\"symbol\":\"EURUSD\",\"side\":\"long\",\"lots\":1,\"leverage\":100}}",
        ",\"cmd\":\"clock\",\"args\":{\"action\":\"set\",\"speed\":32,\"tickMs\":80}}",
        ",\"cmd\":\"clock\",\"args\":{\"action\":\"start\"}}"
    }, 6000);
    return 0;
}
