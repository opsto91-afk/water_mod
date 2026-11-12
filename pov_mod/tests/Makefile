# Host-side tests for the PovCore math (no Android toolchain needed).
CXX ?= g++
CXXFLAGS ?= -std=c++20 -Wall -Wextra -O1

.PHONY: run
run: pov_core_test
	./pov_core_test

pov_core_test: pov_core_test.cpp ../src/PovCore.hpp
	$(CXX) $(CXXFLAGS) -I../src pov_core_test.cpp -o pov_core_test

clean:
	rm -f pov_core_test
