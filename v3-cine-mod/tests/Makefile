# Host-side test (no Android, no GPU).  Usage:  make -C tests run
CXX ?= g++
SRC = ../src
IMG = ../third_party/imgui
FLAGS = -std=c++20 -O0 -g -Wall -Wno-unused-function -I$(SRC) -I$(IMG)

host_test: host_test.cpp $(SRC)/Core.cpp $(SRC)/CameraLink.cpp $(SRC)/TouchRouter.cpp $(SRC)/Editor.cpp \
           $(IMG)/imgui.cpp $(IMG)/imgui_draw.cpp $(IMG)/imgui_tables.cpp $(IMG)/imgui_widgets.cpp
	$(CXX) $(FLAGS) $^ -o $@

run: host_test
	./host_test

clean:
	rm -f host_test
.PHONY: run clean
