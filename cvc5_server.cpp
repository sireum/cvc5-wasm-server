#include <cvc5/cvc5.h>
#include <cvc5/cvc5_parser.h>

#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <sstream>
#include <string>
#include <vector>
#include <utility>

// Protocol (binary, big-endian):
//   Query loop:
//     1. [4-byte len][CLI options string]   (length=0 means shutdown)
//        e.g., "--produce-models --full-saturate-quant"
//        Parsed: strip "--", split on "=", call solver.setOption(key, value).
//        If no "=", value is "true". Skip "--lang=*".
//     2. [4-byte len][query bytes]
//        Response: [4-byte len][result bytes]
//
// Each query gets a fresh solver instance with the provided options applied.

static uint32_t read_u32() {
    uint8_t buf[4];
    if (fread(buf, 1, 4, stdin) != 4) return 0;
    return ((uint32_t)buf[0] << 24) | ((uint32_t)buf[1] << 16)
         | ((uint32_t)buf[2] << 8)  | (uint32_t)buf[3];
}

static void write_u32(uint32_t v) {
    uint8_t buf[4];
    buf[0] = (uint8_t)(v >> 24);
    buf[1] = (uint8_t)(v >> 16);
    buf[2] = (uint8_t)(v >> 8);
    buf[3] = (uint8_t)(v);
    fwrite(buf, 1, 4, stdout);
}

// Parse CLI options string into key-value pairs for solver.setOption
static std::vector<std::pair<std::string, std::string>> parse_options(const std::string &opts_str) {
    std::vector<std::pair<std::string, std::string>> result;
    std::istringstream iss(opts_str);
    std::string token;
    while (iss >> token) {
        // Strip leading "--"
        size_t start = 0;
        while (start < token.size() && token[start] == '-') start++;
        std::string stripped = token.substr(start);
        if (stripped.empty()) continue;

        // Skip --lang=*
        if (stripped.substr(0, 5) == "lang=") continue;

        // Split on "="
        size_t eq = stripped.find('=');
        if (eq != std::string::npos) {
            result.emplace_back(stripped.substr(0, eq), stripped.substr(eq + 1));
        } else {
            result.emplace_back(stripped, "true");
        }
    }
    return result;
}

int main() {
    while (true) {
        // 1. Read options (zero-length means shutdown)
        uint32_t opts_len = read_u32();
        if (opts_len == 0) break;

        std::string opts_str(opts_len, '\0');
        if (fread(&opts_str[0], 1, opts_len, stdin) != opts_len) break;
        auto opts = parse_options(opts_str);

        // 2. Read query
        uint32_t len = read_u32();
        if (len == 0) break;

        std::string query(len, '\0');
        if (fread(&query[0], 1, len, stdin) != len) break;

        // Fresh solver per query
        cvc5::TermManager tm;
        cvc5::Solver solver(tm);

        // Apply options
        for (const auto &opt : opts) {
            solver.setOption(opt.first, opt.second);
        }

        cvc5::parser::SymbolManager sm(tm);
        cvc5::parser::InputParser parser(&solver, &sm);
        parser.setStringInput(
            cvc5::modes::InputLanguage::SMT_LIB_2_6, query, "query");

        // Parse and execute commands, collect output
        std::ostringstream out;
        bool first = true;
        while (!parser.done()) {
            cvc5::parser::Command cmd = parser.nextCommand();
            if (cmd.isNull()) break;
            std::ostringstream cmdOut;
            cmd.invoke(&solver, &sm, cmdOut);
            std::string s = cmdOut.str();
            if (!s.empty()) {
                if (!first) out << '\n';
                out << s;
                first = false;
            }
        }

        // Write response
        std::string result = out.str();
        write_u32((uint32_t)result.size());
        if (!result.empty()) {
            fwrite(result.data(), 1, result.size(), stdout);
        }
        fflush(stdout);
    }

    return 0;
}
