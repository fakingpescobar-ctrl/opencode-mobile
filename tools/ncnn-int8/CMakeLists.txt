# Local harness: сравнение whisper encoder fp32 vs int8 на синтетическом mel-входе.
# Косine similarity, max|Δ|, замер времени. Сборка из tools/ncnn-int8/build.
cmake_minimum_required(VERSION 3.16)
project(compare_encoder CXX)

set(NCNN_SRC "C:/Projects/opencode-mobile/third_party/ncnn")
set(NCNN_INC "${NCNN_SRC}/src")
set(NCNN_LIB "${NCNN_SRC}/src/Release/ncnn.lib")

find_package(OpenMP QUIET)

add_executable(compare_encoder compare_encoder.cpp)
target_include_directories(compare_encoder PRIVATE ${NCNN_INC} ${NCNN_INC}/layer/${CMAKE_SYSTEM_PROCESSOR})
target_link_libraries(compare_encoder PRIVATE ${NCNN_LIB})
if(OpenMP_CXX_FOUND)
    target_link_libraries(compare_encoder PRIVATE OpenMP::OpenMP_CXX)
endif()