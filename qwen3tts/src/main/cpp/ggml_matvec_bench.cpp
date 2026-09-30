// Micro-benchmark of ggml's CPU matrix-vector products at the sizes the Qwen3-TTS Code Predictor
// uses, on 1..6 threads. The Code Predictor is ~2/3 of generation time and every one of its steps is
// a few hundred small products in sequence; this shows whether those are limited by arithmetic and
// memory (they should then scale with threads) or by the per-operation thread synchronisation
// (they then stop scaling after 1-2 threads and a leaner implementation would pay off).
//
// Each measurement is one graph of N independent products with N distinct weight matrices (so the
// working set is like the real one, tens of MB, not one cache-resident matrix), run repeatedly for a
// fixed time after a warm-up; the median repetition is reported per product.
#include "ggml_matvec_bench.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdint>
#include <random>
#include <string>
#include <vector>

#include "ggml.h"
#include "ggml-alloc.h"
#include "ggml-backend.h"

namespace {

double now_ms() {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

void set_threads(ggml_backend_t be, int n) {
    ggml_backend_dev_t dev = ggml_backend_get_device(be);
    if (!dev) return;
    ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
    if (!reg) return;
    auto fn = (ggml_backend_set_n_threads_t) ggml_backend_reg_get_proc_address(reg, "ggml_backend_set_n_threads");
    if (fn) fn(be, n);
}

struct Measurement {
    // ms per product for each thread count, in the order of the thread list; <0 = failed.
    std::vector<double> ms;
    double weight_mb = 0;
};

Measurement measure(ggml_backend_t be, ggml_type type, int K, int N, int cols, int n_ops,
                    const std::vector<int> & threads, double budget_ms) {
    Measurement m;
    m.ms.assign(threads.size(), -1.0);

    const size_t mem = ggml_tensor_overhead() * (size_t) (2 * n_ops + 8) + ggml_graph_overhead_custom((size_t) n_ops + 8, false) + 4096;
    ggml_init_params ip{mem, nullptr, true};
    ggml_context * ctx = ggml_init(ip);
    if (!ctx) return m;

    ggml_tensor * x = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, K, cols);
    std::vector<ggml_tensor *> w((size_t) n_ops);
    for (int i = 0; i < n_ops; ++i) w[(size_t) i] = ggml_new_tensor_2d(ctx, type, K, N);
    ggml_cgraph * g = ggml_new_graph_custom(ctx, (size_t) n_ops + 8, false);
    for (int i = 0; i < n_ops; ++i) ggml_build_forward_expand(g, ggml_mul_mat(ctx, w[(size_t) i], x));

    ggml_backend_buffer_t buf = ggml_backend_alloc_ctx_tensors(ctx, be);
    if (!buf) {
        ggml_free(ctx);
        return m;
    }

    std::mt19937 rng(12345);
    std::uniform_real_distribution<float> dist(-1.f, 1.f);
    std::vector<float> xv((size_t) K * (size_t) cols);
    for (float & v : xv) v = dist(rng);
    ggml_backend_tensor_set(x, xv.data(), 0, xv.size() * sizeof(float));

    std::vector<float> wf((size_t) K * (size_t) N);
    for (float & v : wf) v = dist(rng);
    const size_t row = ggml_row_size(type, K);
    std::vector<uint8_t> q(row * (size_t) N);
    if (type == GGML_TYPE_F16) {
        ggml_fp32_to_fp16_row(wf.data(), (ggml_fp16_t *) q.data(), (int64_t) K * N);
    } else {
        ggml_quantize_chunk(type, wf.data(), q.data(), 0, N, K, nullptr);
    }
    for (int i = 0; i < n_ops; ++i) ggml_backend_tensor_set(w[(size_t) i], q.data(), 0, q.size());
    m.weight_mb = (double) q.size() / (1024.0 * 1024.0);

    for (size_t t = 0; t < threads.size(); ++t) {
        set_threads(be, threads[t]);
        for (int i = 0; i < 5; ++i) ggml_backend_graph_compute(be, g);
        std::vector<double> reps;
        const double start = now_ms();
        while (now_ms() - start < budget_ms || reps.size() < 8) {
            const double t0 = now_ms();
            if (ggml_backend_graph_compute(be, g) != GGML_STATUS_SUCCESS) break;
            reps.push_back(now_ms() - t0);
            if (reps.size() >= 2000) break;
        }
        if (reps.empty()) continue;
        std::sort(reps.begin(), reps.end());
        m.ms[t] = reps[reps.size() / 2] / n_ops;
    }

    ggml_backend_buffer_free(buf);
    ggml_free(ctx);
    return m;
}

}  // namespace

std::string run_matvec_benchmark(bool (*cancelled)()) {
    ggml_backend_t be = ggml_backend_init_by_type(GGML_BACKEND_DEVICE_TYPE_CPU, nullptr);
    if (!be) return "no CPU backend\n";

    struct Shape { const char * label; int k; int n; int ops; };
    const Shape shapes[] = {
        {"1024x1024", 1024, 1024, 24},
        {"1024x2048", 1024, 2048, 24},
        {"1024x3072", 1024, 3072, 24},
        {"3072x1024", 3072, 1024, 24},
        {"256x256 (tiny: sync cost)", 256, 256, 64},
    };
    struct Type { const char * label; ggml_type type; };
    const Type types[] = {{"Q4_K", GGML_TYPE_Q4_K}, {"Q8_0", GGML_TYPE_Q8_0}, {"F16", GGML_TYPE_F16}};
    const int col_counts[] = {1, 4};
    const std::vector<int> threads = {1, 2, 3, 4, 6};

    std::string out = "ggml CPU matvec/matmul benchmark (ms per product, median; speedup vs 1 thread)\n";
    out += "columns=1 is a matrix-vector product (what a decode step does), columns=4 a small matmul.\n";
    out += "threads:";
    for (int t : threads) out += " " + std::to_string(t);
    out += "\n\n";

    char line[256];
    for (const Shape & s : shapes) {
        for (const Type & ty : types) {
            for (int cols : col_counts) {
                if (cancelled && cancelled()) {
                    ggml_backend_free(be);
                    return out + "(cancelled)\n";
                }
                Measurement m = measure(be, ty.type, s.k, s.n, cols, s.ops, threads, 150.0);
                snprintf(line, sizeof(line), "%-5s %-26s c=%d (%.1f MB/matrix):", ty.label, s.label, cols, m.weight_mb);
                out += line;
                const double base = m.ms.empty() ? -1.0 : m.ms[0];
                for (size_t i = 0; i < threads.size(); ++i) {
                    if (m.ms[i] < 0) {
                        out += "  -";
                    } else if (i == 0 || base <= 0) {
                        snprintf(line, sizeof(line), "  %.3f", m.ms[i]);
                        out += line;
                    } else {
                        snprintf(line, sizeof(line), "  %.3f (%.2fx)", m.ms[i], base / m.ms[i]);
                        out += line;
                    }
                }
                out += "\n";
            }
        }
        out += "\n";
    }
    ggml_backend_free(be);
    return out;
}

// Quick per-device choice of the model's thread count (talker + Code Predictor): the same kind of
// products as the Code Predictor's, on 1..4 threads, about a second in total. On some phones (Pixel 10
// Pro) every extra thread makes those tiny products slower, on others (Snapdragon 865) 4 threads are
// 2.5x faster, so this cannot be a constant. More threads are only chosen if at least 10% faster.
std::string tune_threads(int * chosen) {
    *chosen = 0;
    ggml_backend_t be = ggml_backend_init_by_type(GGML_BACKEND_DEVICE_TYPE_CPU, nullptr);
    if (!be) return "no CPU backend\n";

    struct Shape { int k; int n; int ops; };
    const Shape shapes[] = {{1024, 1024, 24}, {1024, 2048, 24}, {3072, 1024, 24}};
    const std::vector<int> threads = {1, 2, 3, 4};
    std::vector<double> total(threads.size(), 0.0);
    for (const Shape & sh : shapes) {
        Measurement m = measure(be, GGML_TYPE_Q4_K, sh.k, sh.n, 1, sh.ops, threads, 60.0);
        for (size_t i = 0; i < threads.size(); ++i) {
            total[i] = (total[i] < 0 || m.ms[i] < 0) ? -1.0 : total[i] + m.ms[i];
        }
    }
    ggml_backend_free(be);

    size_t best = 0;
    for (size_t i = 1; i < threads.size(); ++i) {
        if (total[i] > 0 && total[best] > 0 && total[i] < total[best] * 0.9) best = i;
    }
    *chosen = threads[best];
    std::string out = "Thread auto-tune (Code Predictor-like products, ms per 3 products):";
    char line[96];
    for (size_t i = 0; i < threads.size(); ++i) {
        snprintf(line, sizeof(line), "  %dt=%.3f", threads[i], total[i]);
        out += line;
    }
    snprintf(line, sizeof(line), "  -> %d\n", *chosen);
    out += line;
    return out;
}
