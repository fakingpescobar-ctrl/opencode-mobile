# Хост-toolchain для vulkan-shaders-gen (Windows host) — сборка НАТИВНЫМ MSVC
# в окружении vcvars64.bat.
#
# ggml-vulkan компилирует shaders-gen как host-инструмент (вызывает glslc для
# генерации .spv). Внешний конфиг — Android (NDK). NDK clang не годится для
# хоста (нет Windows SDK import-lib). Поэтому:
#   - НЕ полагаемся на ENV из configure (не доходит до отдельного ninja-proцесса);
#   - Активируем окружение Visual Studio через vcvars64.bat ПЕРЕД gradlew —
#     тогда INCLUDE/LIB/PATH заданы системно, и cl/link/mt/rc находят всё сами.
# Здесь только переключаем хост на Windows + именуем cl.exe (который в PATH).
#
# Подключается через -DGGML_VULKAN_SHADERS_GEN_TOOLCHAIN=.

set(CMAKE_SYSTEM_NAME Windows CACHE STRING "Host system" FORCE)
set(CMAKE_SYSTEM_PROCESSOR AMD64 CACHE STRING "" FORCE)
set(CMAKE_HOST_SYSTEM_NAME Windows CACHE STRING "" FORCE)

set(CMAKE_BUILD_TYPE Release)
set(CMAKE_C_FLAGS -O2)
set(CMAKE_CXX_FLAGS -O2)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE NEVER)

# Компилятор по имени — резолвится через PATH (в нём cl.exe после vcvars).
# Так перебиваем унаследованный от внешнего Android-конфига NDK clang.
set(CMAKE_C_COMPILER   "cl.exe" CACHE FILEPATH "Host C compiler" FORCE)
set(CMAKE_CXX_COMPILER "cl.exe" CACHE FILEPATH "Host CXX compiler" FORCE)

# Ninja (host) + его путь.
set(CMAKE_GENERATOR "Ninja")
# Ninja ищем в PATH/стандартных местах; fallback — SDK windows-машины разработчика
# (CI-17: абсолютный путь не обязан существовать на другом хосте).
find_program(NCNN_NINJA_EXE ninja)
if(NCNN_NINJA_EXE)
    set(CMAKE_MAKE_PROGRAM "${NCNN_NINJA_EXE}" CACHE STRING "Ninja for host vulkan-shaders-gen" FORCE)
else()
    set(CMAKE_MAKE_PROGRAM "C:/Users/OLD/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe" CACHE STRING "Ninja for host vulkan-shaders-gen" FORCE)
endif()

# Quoted + forward slashes (избегаем Invalid character escape '\P').
# Относительно toolchain-файла (whisperlib/src/main/jni → ../../../ = whisperlib).
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_LIST_DIR}/../../../build/vk-host" CACHE STRING "Host runtime output")
