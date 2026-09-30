#pragma once

#include <string>

// Runs the CPU matvec benchmark (see the .cpp) and returns the report. [cancelled] is polled between measurements.
std::string run_matvec_benchmark(bool (*cancelled)());
