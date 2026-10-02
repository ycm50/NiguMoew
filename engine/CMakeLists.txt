# =============================================================================
# engine/CMakeLists.txt - NiguMeow simulation engine
#
# Two ways to build are supported and they must stay equivalent:
#
#   A) direct, script driven (scripts\build.cmd does this, do not break it)
#        g++ -std=c++17 -O2 -Wall -Wextra -I engine/src engine/src/trade_sim.cpp \
#            -o build/engine/trade_sim.exe
#
#   B) CMake, from the project root
#        cmake -S engine -B build/engine-cmake -G "MinGW Makefiles"
#        cmake --build build/engine-cmake
#
#       with MSVC:
#        cmake -S engine -B build/engine-cmake -G "Visual Studio 17 2022" -A x64
#        cmake --build build/engine-cmake --config Release
#
# The target is always called trade_sim so both routes produce the same artifact
# name.  Build outputs default to <root>/build/engine for single-config
# generators and <root>/build/engine/<Config> for multi-config generators.
# =============================================================================

cmake_minimum_required(VERSION 3.16)

project(TradeSim
        VERSION 1.0.0
        DESCRIPTION "NiguMeow simulation engine (stocks / forex / T+1 / cheats)"
        LANGUAGES CXX)

# ---------------------------------------------------------------- options ---
option(TRADESIM_BUILD_TOOLS "also build tools/jsoncheck and tools/driver" ON)
option(TRADESIM_BUILD_SMOKE "also build the pure logic smoke test" ON)
option(TRADESIM_WARNINGS_AS_ERRORS "treat compiler warnings as errors" OFF)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

# CMake puts MinGW runtime DLLs next to the executable on Windows; keep the
# artefacts in build/engine next to what scripts\build.cmd produces.
set(TRADESIM_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/..")
set(TRADESIM_OUTDIR "${TRADESIM_ROOT}/build/engine" CACHE PATH "TradeSim output directory")
foreach(cfg "" _DEBUG _RELEASE _RELWITHDEBINFO _MINSIZEREL)
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY${cfg} "${TRADESIM_OUTDIR}")
endforeach()

# -------------------------------------------------------------- warnings ----
# Both MSVC and MinGW/g++ must build with zero warnings.
set(TRADESIM_WARN_FLAGS "")
if(MSVC)
    list(APPEND TRADESIM_WARN_FLAGS /W4 /permissive- /utf-8 /EHsc)
    if(TRADESIM_WARNINGS_AS_ERRORS)
        list(APPEND TRADESIM_WARN_FLAGS /WX)
    endif()
else()
    list(APPEND TRADESIM_WARN_FLAGS -Wall -Wextra)
    if(TRADESIM_WARNINGS_AS_ERRORS)
        list(APPEND TRADESIM_WARN_FLAGS -Werror)
    endif()
endif()

# ------------------------------------------------------------------ engine --
# The engine is header only; trade_sim.cpp is the single translation unit that
# includes every header and provides main().  Add more sources here if that
# ever changes - the list is intentionally explicit.
set(TRADESIM_ENGINE_SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/src/trade_sim.cpp")

if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/src/trade_sim.cpp")
    message(WARNING
        "engine/src/trade_sim.cpp not found yet - run the build after the engine "
        "entry point has been written.  Headers found:")
    file(GLOB TRADESIM_HEADERS "${CMAKE_CURRENT_SOURCE_DIR}/src/*.hpp")
    foreach(h ${TRADESIM_HEADERS})
        message(STATUS "    ${h}")
    endforeach()
    return()
endif()

add_executable(trade_sim ${TRADESIM_ENGINE_SOURCES})
target_include_directories(trade_sim PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
target_compile_options(trade_sim PRIVATE ${TRADESIM_WARN_FLAGS})

if(NOT MSVC)
    # Static linking keeps trade_sim.exe runnable from cmd without MSYS2 on PATH.
    target_link_options(trade_sim PRIVATE -static-libgcc -static-libstdc++)
endif()

set_target_properties(trade_sim PROPERTIES
    OUTPUT_NAME "trade_sim"
    RUNTIME_OUTPUT_DIRECTORY "${TRADESIM_OUTDIR}"
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON)

# ------------------------------------------------------------------- tools --
if(TRADESIM_BUILD_TOOLS)
    set(TRADESIM_JSONCHECK_SRC "${TRADESIM_ROOT}/tools/jsoncheck/jsoncheck.cpp")
    if(EXISTS "${TRADESIM_JSONCHECK_SRC}")
        add_executable(jsoncheck "${TRADESIM_JSONCHECK_SRC}")
        target_compile_options(jsoncheck PRIVATE ${TRADESIM_WARN_FLAGS})
        set_target_properties(jsoncheck PROPERTIES
            RUNTIME_OUTPUT_DIRECTORY "${TRADESIM_ROOT}/build/tools")
    endif()

    set(TRADESIM_DRIVER_SRC "${TRADESIM_ROOT}/tools/driver/driver.cpp")
    if(WIN32 AND EXISTS "${TRADESIM_DRIVER_SRC}")
        add_executable(driver "${TRADESIM_DRIVER_SRC}")
        target_compile_options(driver PRIVATE ${TRADESIM_WARN_FLAGS})
        target_compile_definitions(driver PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
        # bcrypt provides the SHA-256 used to stamp the engine digest into driver.log.
        target_link_libraries(driver PRIVATE bcrypt)
        if(NOT MSVC)
            target_link_options(driver PRIVATE -static-libgcc -static-libstdc++)
        endif()
        set_target_properties(driver PROPERTIES
            RUNTIME_OUTPUT_DIRECTORY "${TRADESIM_ROOT}/build/tools")
    endif()
endif()

# ------------------------------------------------------------ smoke tests ---
if(TRADESIM_BUILD_SMOKE AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests/smoke.cpp")
    enable_testing()
    add_executable(tradesim_smoke "${CMAKE_CURRENT_SOURCE_DIR}/tests/smoke.cpp")
    target_include_directories(tradesim_smoke PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
    target_compile_options(tradesim_smoke PRIVATE ${TRADESIM_WARN_FLAGS})

    add_test(NAME smoke COMMAND tradesim_smoke)

    # End to end: spawn trade_sim --stdio and check the v1 protocol contract.
    if(TARGET driver AND TARGET trade_sim)
        add_test(NAME e2e
                 COMMAND driver
                         --engine "$<TARGET_FILE:trade_sim>"
                         --root "${TRADESIM_ROOT}"
                         --quick)
        set_tests_properties(e2e PROPERTIES DEPENDS smoke)
    endif()

    set_target_properties(tradesim_smoke PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${TRADESIM_ROOT}/build/tools")
endif()

# ------------------------------------------------------------- summary ------
get_property(TRADESIM_TARGETS DIRECTORY PROPERTY BUILDSYSTEM_TARGETS)
message(STATUS "TradeSim configured")
message(STATUS "  compiler  : ${CMAKE_CXX_COMPILER_ID} ${CMAKE_CXX_COMPILER_VERSION}")
message(STATUS "  output dir: ${TRADESIM_OUTDIR}")
message(STATUS "  targets   : ${TRADESIM_TARGETS}")
