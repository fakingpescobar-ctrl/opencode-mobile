# Local harness: сравнение whisper encoder fp32 vs int8 на синтетическом mel-входе.
# Косine similarity, max|Δ|, замер времени. Сборка из tools/ncnn-int8/build.
cmake_minimum_required(VERSION 3.16)
project(compare_encoder CXX)

# ncnn — в репо (third_party/ncnn): путь ОТНОСИТЕЛЬНЫЙ от этого файла
# (tools/ncnn-int8 → корень репо = 2 уровня вверх), не абсолютный C:/... (CI-17).
set(NCNN_SRC "${CMAKE_CURRENT_LIST_DIR}/../../third_party/ncnn")
set(NCNN_INC "${NCNN_SRC}/src")
set(NCNN_LIB "${NCNN_SRC}/src/Release/ncnn.lib")

find_package(OpenMP QUIET)

add_executable(compare_encoder compare_encoder.cpp)
target_include_directories(compare_encoder PRIVATE ${NCNN_INC} ${NCNN_INC}/layer/${CMAKE_SYSTEM_PROCESSOR})
target_link_libraries(compare_encoder PRIVATE ${NCNN_LIB})
if(OpenMP_CXX_FOUND)
    target_link_libraries(compare_encoder PRIVATE OpenMP::OpenMP_CXX)
endif()