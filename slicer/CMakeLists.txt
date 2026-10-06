# Headless ElegooSlicer engine (libslic3r) plus the Link Slicer facade and command-line tool.
#
# Builds ElegooSlicer's src/libslic3r and the parts of deps_src it needs straight from an ElegooSlicer checkout,
# with the setup its top-level CMakeLists.txt does, minus everything GUI-only (wxWidgets, OpenGL, GLFW, Sentry,
# CURL, DBus, the Agora/Elegoo network libraries). Dependencies come from deps/ (scripts/build-deps-linux.sh).
cmake_minimum_required(VERSION 3.16)
if (${CMAKE_VERSION} VERSION_GREATER_EQUAL "4.0")
    set(CMAKE_POLICY_VERSION_MINIMUM 3.13 CACHE STRING "" FORCE)
endif ()
project(LinkSlicer CXX C)

set(ELEGOOSLICER_SRC "" CACHE PATH "ElegooSlicer source checkout")
if (NOT EXISTS "${ELEGOOSLICER_SRC}/src/libslic3r/CMakeLists.txt")
    message(FATAL_ERROR "Set ELEGOOSLICER_SRC to an ElegooSlicer checkout (see scripts/fetch-elegooslicer.sh)")
endif ()

if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif ()

# --- What ElegooSlicer's top-level CMakeLists.txt sets up for libslic3r ---------------------------------------------
include("${ELEGOOSLICER_SRC}/version.inc")
# ElegooSlicer reads this and its service URLs from a private .env file; the engine needs none of them.
set(ELEGOO_INTERNAL_TESTING 0)
set(SLIC3R_RESOURCES_DIR "${ELEGOOSLICER_SRC}/resources")
set(SLIC3R_STATIC ON)
set(SLIC3R_GUI OFF)
set(SLIC3R_PCH ON CACHE BOOL "Use precompiled headers")
set(SLIC3R_ENC_CHECK OFF)
set(IS_CROSS_COMPILE ${CMAKE_CROSSCOMPILING})
set(TOP_LEVEL_PROJECT_DIR "${ELEGOOSLICER_SRC}")
list(APPEND CMAKE_MODULE_PATH "${ELEGOOSLICER_SRC}/cmake/modules/")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_compile_definitions("$<IF:$<CONFIG:Debug>,DEBUG,NDEBUG>")
add_compile_definitions("BBL_RELEASE_TO_PUBLIC=$<CONFIG:Release>")
add_definitions(-DwxUSE_UNICODE -D_UNICODE -DUNICODE -DWXINTL_NO_GETTEXT_MACRO -DwxNO_UNSAFE_WXSTRING_CONV)

if (CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
    # Boost's quad float literals need GCC's numeric literal extensions.
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fext-numeric-literals")
    add_compile_options(-Wno-unknown-pragmas)
    if (CMAKE_CXX_COMPILER_VERSION VERSION_GREATER 14)
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-error=template-id-cdtor")
    endif ()
endif ()
if (CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-reorder")
    add_compile_options(-Werror=return-type -Wno-unused-function -Wno-unused-variable -Wno-unused-but-set-variable
        -Wno-unused-label -Wno-unused-local-typedefs -Wno-sign-compare -Wno-misleading-indentation -Wno-switch
        -Wno-ignored-attributes)
endif ()
if (CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    add_compile_options(-Wno-deprecated-declarations)
    include(CheckCXXCompilerFlag)
    check_cxx_compiler_flag(-Wno-error=enum-constexpr-conversion HAS_WNO_ERROR_ENUM_CONSTEXPR_CONV)
    if (HAS_WNO_ERROR_ENUM_CONSTEXPR_CONV)
        add_compile_options(-Wno-error=enum-constexpr-conversion)
    endif ()
endif ()

set(LIBDIR "${ELEGOOSLICER_SRC}/src")
set(LIBDIR_BIN "${CMAKE_CURRENT_BINARY_DIR}/src")
include_directories(SYSTEM ${LIBDIR})
include_directories(SYSTEM ${LIBDIR}/libigl)

# ElegooSlicer's encoding check needs its dev-utils tool; the engine build skips it.
function(encoding_check TARGET)
endfunction()
# Only matters for multi-config MSVC builds in ElegooSlicer; a no-op here.
function(slic3r_remap_configs targets from_Cfg to_Cfg)
endfunction()

# --- Dependencies ------------------------------------------------------------------------------------------------
set(THREADS_PREFER_PTHREAD_FLAG ON)
find_package(Threads REQUIRED)

if (POLICY CMP0167)
    cmake_policy(SET CMP0167 NEW)
endif ()
set(Boost_USE_STATIC_LIBS ON)
set(Boost_NO_SYSTEM_PATHS TRUE)
find_package(Boost 1.83.0 REQUIRED COMPONENTS system filesystem thread log log_setup locale regex chrono atomic date_time iostreams program_options nowide)
find_package(Eigen3 5.0.1 REQUIRED)
add_library(boost_libs INTERFACE)
add_library(boost_headeronly INTERFACE)
target_include_directories(boost_headeronly SYSTEM INTERFACE ${Boost_INCLUDE_DIRS})
target_link_libraries(boost_libs INTERFACE boost_headeronly ${Boost_LIBRARIES})

set(TBB_STATIC 1)
set(CMAKE_MAP_IMPORTED_CONFIG_RELWITHDEBINFO RelWithDebInfo Release "")
find_package(TBB REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(Freetype REQUIRED)
find_package(ZLIB REQUIRED)
find_package(EXPAT)
if (NOT EXPAT_FOUND)
    add_library(expat STATIC ${LIBDIR}/expat/xmlparse.c ${LIBDIR}/expat/xmlrole.c ${LIBDIR}/expat/xmltok.c)
    set(EXPAT_FOUND 1)
    set(EXPAT_INCLUDE_DIRS ${LIBDIR}/expat/)
    set(EXPAT_LIBRARIES expat)
endif ()
find_package(PNG REQUIRED)
find_package(cereal REQUIRED)
if (NOT TARGET cereal::cereal)
    set_target_properties(cereal PROPERTIES IMPORTED_GLOBAL TRUE)
    add_library(cereal::cereal ALIAS cereal)
else ()
    set_target_properties(cereal::cereal PROPERTIES IMPORTED_GLOBAL TRUE)
endif ()
find_package(NLopt 1.4 REQUIRED)
find_package(libnoise REQUIRED)
# OpenVDB is only used for SLA hollowing, but libslic3r's SLA sources include it unconditionally.
set(OPENVDB_USE_STATIC_LIBS ON)
set(USE_BLOSC TRUE)
find_package(OpenVDB 5.0 REQUIRED COMPONENTS openvdb)

# --- ElegooSlicer sources ---------------------------------------------------------------------------------------
# Only the bundled libraries libslic3r uses; imgui, hidapi, mdns and the hints tool are GUI or network pieces.
set(_bundled agg ankerl earcut fast_float nanosvg nlohmann stb_dxt Shiny admesh clipper clipper2 expat glu-libtess
    libigl libnest2d mcut miniz qhull qoi semver)
foreach (_lib ${_bundled})
    if (EXISTS "${ELEGOOSLICER_SRC}/deps_src/${_lib}/CMakeLists.txt")
        add_subdirectory("${ELEGOOSLICER_SRC}/deps_src/${_lib}" "${CMAKE_CURRENT_BINARY_DIR}/deps_src/${_lib}")
    endif ()
endforeach ()
add_subdirectory("${ELEGOOSLICER_SRC}/src/libslic3r" "${LIBDIR_BIN}/libslic3r")

# nanosvg's implementation lives in ElegooSlicer's GUI; give libslic3r its own so it links after libslic3r.
add_library(nanosvg_impl STATIC engine/nanosvg_impl.cpp)
target_include_directories(nanosvg_impl PRIVATE "${ELEGOOSLICER_SRC}/deps_src")
target_link_libraries(libslic3r PUBLIC nanosvg_impl)

# --- Link Slicer ------------------------------------------------------------------------------------------------
add_library(link_slicer STATIC engine/link_slicer.cpp engine/link_slicer.hpp)
target_include_directories(link_slicer PUBLIC engine)
target_link_libraries(link_slicer PUBLIC libslic3r boost_libs cereal::cereal noise::noise Threads::Threads ${CMAKE_DL_LIBS})

add_executable(link-slicer cli/main.cpp)
target_link_libraries(link-slicer PRIVATE link_slicer)
