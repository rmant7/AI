#pragma once

#include <string>

// Runs the CPU matvec benchmark (see the .cpp) and returns the report. [cancelled] is polled between measurements.
std::string run_matvec_benchmark(bool (*cancelled)());

// Picks the model thread count for this device (1..4) with a ~1 s test; the report says how.
std::string tune_threads(int * chosen);
