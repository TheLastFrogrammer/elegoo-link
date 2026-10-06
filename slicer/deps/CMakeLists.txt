# Engine-only dependency superbuild for the headless ElegooSlicer engine.
#
# Reuses ElegooSlicer's own per-dependency recipes (deps/<Name>/<Name>.cmake: pinned versions, hashes and patches)
# but only for the libraries libslic3r links, so nothing here needs OpenGL, GTK, wxWidgets, Sentry or CURL.
# The prologue and orcaslicer_add_cmake_project() follow ElegooSlicer's deps/CMakeLists.txt.
cmake_minimum_required(VERSION 3.16)
project(LinkSlicer-deps)

if(POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)
endif()

set(ELEGOOSLICER_SRC "" CACHE PATH "ElegooSlicer source checkout")
if (NOT EXISTS "${ELEGOOSLICER_SRC}/deps/Boost/Boost.cmake")
    message(FATAL_ERROR "Set ELEGOOSLICER_SRC to an ElegooSlicer checkout (see scripts/fetch-elegooslicer.sh)")
endif ()
set(_recipes "${ELEGOOSLICER_SRC}/deps")

include(ExternalProject)
include(ProcessorCount)
ProcessorCount(NPROC)
if (NPROC EQUAL 0)
    set(NPROC 1)
endif ()

if (CMAKE_SYSTEM_NAME STREQUAL "Linux" AND CMAKE_VERSION VERSION_LESS "3.25")
    set(LINUX ON)
endif ()

set(DEP_DOWNLOAD_DIR "${CMAKE_BINARY_DIR}/downloads" CACHE PATH "Path for downloaded source packages.")
set(DESTDIR "${CMAKE_BINARY_DIR}/ElegooSlicer_dep" CACHE PATH "Path to dependencies install directory")
set(DESTDIR "${DESTDIR}/usr/local/")
set(FLATPAK FALSE)
set(IS_CROSS_COMPILE FALSE)
set(_is_multi FALSE)

if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif ()

find_package(Git REQUIRED)
set(PATCH_CMD ${GIT_EXECUTABLE} apply --verbose --ignore-space-change --whitespace=fix)

# Some recipes apply patches with "git apply"; inside a git work tree that needs the path relative to its top level.
execute_process(
    COMMAND ${GIT_EXECUTABLE} rev-parse --show-toplevel
    WORKING_DIRECTORY ${CMAKE_BINARY_DIR}
    RESULT_VARIABLE _toplevel_result
    OUTPUT_VARIABLE _toplevel
    OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET
)
if (_toplevel_result EQUAL 0 AND _toplevel)
    set(IN_GIT_REPO TRUE)
    file(RELATIVE_PATH BINARY_DIR_REL "${_toplevel}" "${CMAKE_BINARY_DIR}")
endif ()

# GitHub source archives (github.com/<owner>/<repo>/archive/...) can be unreachable where plain git is not (for example
# behind proxies that only serve git). With DEP_GIT_ARCHIVES on, such URLs are replaced by a tarball made with
# "git archive" from a shallow clone of the same tag; the recipe's URL_HASH no longer applies to that tarball, so the
# tag's commit is logged in git-sources.txt instead.
option(DEP_GIT_ARCHIVES "Fetch GitHub archive URLs with git instead" OFF)
set(DEP_GIT_MIRROR_DIR "${DEP_DOWNLOAD_DIR}/git-archives")
function(_git_archive_for url out_var)
    set(${out_var} "" PARENT_SCOPE)
    if (NOT DEP_GIT_ARCHIVES OR NOT url MATCHES "^https://github.com/([^/]+)/([^/]+)/archive/(refs/tags/|refs/heads/)?(.+)\\.(zip|tar\\.gz)$")
        return()
    endif ()
    set(_owner "${CMAKE_MATCH_1}")
    set(_repo "${CMAKE_MATCH_2}")
    set(_ref "${CMAKE_MATCH_4}")
    set(_tarball "${DEP_GIT_MIRROR_DIR}/${_owner}_${_repo}_${_ref}.tar.gz")
    if (NOT EXISTS "${_tarball}")
        set(_clone "${DEP_GIT_MIRROR_DIR}/${_owner}_${_repo}_${_ref}")
        file(REMOVE_RECURSE "${_clone}")
        file(MAKE_DIRECTORY "${_clone}")
        message(STATUS "Fetching ${_owner}/${_repo} ${_ref} with git")
        # init + fetch works for tags, branches and commit hashes alike.
        execute_process(COMMAND ${GIT_EXECUTABLE} -C "${_clone}" init -q)
        execute_process(COMMAND ${GIT_EXECUTABLE} -C "${_clone}" fetch -q --depth 1 https://github.com/${_owner}/${_repo}.git ${_ref}
                RESULT_VARIABLE _result)
        if (NOT _result EQUAL 0)
            message(FATAL_ERROR "git fetch of ${_owner}/${_repo} at ${_ref} failed")
        endif ()
        execute_process(COMMAND ${GIT_EXECUTABLE} -C "${_clone}" rev-parse FETCH_HEAD OUTPUT_VARIABLE _commit OUTPUT_STRIP_TRAILING_WHITESPACE)
        execute_process(COMMAND ${GIT_EXECUTABLE} -C "${_clone}" archive --format=tar.gz --prefix=${_repo}-${_ref}/ -o "${_tarball}" FETCH_HEAD
                RESULT_VARIABLE _result)
        if (NOT _result EQUAL 0)
            message(FATAL_ERROR "git archive of ${_owner}/${_repo} failed")
        endif ()
        file(APPEND "${DEP_GIT_MIRROR_DIR}/git-sources.txt" "${_owner}/${_repo} ${_ref} ${_commit}\n")
        file(REMOVE_RECURSE "${_clone}")
    endif ()
    set(${out_var} "${_tarball}" PARENT_SCOPE)
endfunction()

# Rewrites "URL <github archive> URL_HASH <hash>" in a recipe's arguments when DEP_GIT_ARCHIVES applies.
function(_rewrite_sources out_var)
    set(_args ${ARGN})
    list(FIND _args URL _url_index)
    if (_url_index GREATER -1)
        math(EXPR _value_index "${_url_index} + 1")
        list(GET _args ${_value_index} _url)
        _git_archive_for("${_url}" _tarball)
        if (_tarball)
            list(REMOVE_AT _args ${_value_index})
            list(INSERT _args ${_value_index} "${_tarball}")
            list(FIND _args URL_HASH _hash_index)
            if (_hash_index GREATER -1)
                list(REMOVE_AT _args ${_hash_index})
                list(REMOVE_AT _args ${_hash_index})
            endif ()
        endif ()
    endif ()
    set(${out_var} "${_args}" PARENT_SCOPE)
endfunction()

function(orcaslicer_add_cmake_project projectname)
    _rewrite_sources(_sources ${ARGN})
    cmake_parse_arguments(P_ARGS "FORWARD_CONFIG" "INSTALL_DIR;BUILD_COMMAND;INSTALL_COMMAND" "CMAKE_ARGS" ${_sources})
    if ($ENV{CMAKE_BUILD_PARALLEL_LEVEL})
        set(_build_j "")
    else ()
        set(_build_j "-j${NPROC}")
    endif ()
    ExternalProject_Add(
        dep_${projectname}
        EXCLUDE_FROM_ALL    ON
        INSTALL_DIR         ${DESTDIR}
        DOWNLOAD_DIR        ${DEP_DOWNLOAD_DIR}/${projectname}
        CMAKE_ARGS
            -DCMAKE_POLICY_VERSION_MINIMUM=3.5
            -DCMAKE_INSTALL_PREFIX:STRING=${DESTDIR}
            -DCMAKE_MODULE_PATH:STRING=${ELEGOOSLICER_SRC}/cmake/modules
            -DCMAKE_PREFIX_PATH:STRING=${DESTDIR}
            -DCMAKE_FIND_ROOT_PATH:STRING=${DESTDIR}
            -DCMAKE_IGNORE_PREFIX_PATH:STRING=${CMAKE_IGNORE_PREFIX_PATH}
            -DCMAKE_DEBUG_POSTFIX:STRING=d
            -DCMAKE_C_COMPILER:STRING=${CMAKE_C_COMPILER}
            -DCMAKE_CXX_COMPILER:STRING=${CMAKE_CXX_COMPILER}
            -DCMAKE_C_COMPILER_LAUNCHER:STRING=${CMAKE_C_COMPILER_LAUNCHER}
            -DCMAKE_CXX_COMPILER_LAUNCHER:STRING=${CMAKE_CXX_COMPILER_LAUNCHER}
            -DCMAKE_TOOLCHAIN_FILE:STRING=${CMAKE_TOOLCHAIN_FILE}
            -DCMAKE_EXE_LINKER_FLAGS:STRING=${CMAKE_EXE_LINKER_FLAGS}
            -DCMAKE_SHARED_LINKER_FLAGS:STRING=${CMAKE_SHARED_LINKER_FLAGS}
            -DCMAKE_MODULE_LINKER_FLAGS:STRING=${CMAKE_MODULE_LINKER_FLAGS}
            -DBUILD_SHARED_LIBS:BOOL=OFF
            -DCMAKE_BUILD_TYPE:STRING=${CMAKE_BUILD_TYPE}
            ${DEP_CMAKE_OPTS}
            ${P_ARGS_CMAKE_ARGS}
            ${DEP_CMAKE_OVERRIDES}
        ${P_ARGS_UNPARSED_ARGUMENTS}
        BUILD_COMMAND ${CMAKE_COMMAND} --build . --config Release -- ${_build_j}
        INSTALL_COMMAND ${CMAKE_COMMAND} --build . --target install --config Release
    )
endfunction()

set(DEP_CMAKE_OPTS "-DCMAKE_POSITION_INDEPENDENT_CODE=ON")
set(TBB_MINGW_WORKAROUND "")

if (ANDROID)
    # The NDK toolchain file reads these from the cache, so each dependency's own configure needs them too.
    list(APPEND DEP_CMAKE_OPTS -DANDROID_ABI=${ANDROID_ABI} -DANDROID_PLATFORM=${ANDROID_PLATFORM} -DANDROID_STL=${ANDROID_STL})
    # Autotools recipes (GMP, MPFR) call the compiler directly: use the NDK's target-specific clang wrappers, and
    # TOOLCHAIN_PREFIX, which those recipes pass to --host when cross-compiling.
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        set(TOOLCHAIN_PREFIX aarch64-linux-android)
    elseif (ANDROID_ABI STREQUAL "x86_64")
        set(TOOLCHAIN_PREFIX x86_64-linux-android)
    else ()
        message(FATAL_ERROR "Unsupported ANDROID_ABI ${ANDROID_ABI}")
    endif ()
    set(_android_api ${ANDROID_PLATFORM_LEVEL})
    set(ANDROID_AUTOTOOLS_CC "${ANDROID_TOOLCHAIN_ROOT}/bin/${TOOLCHAIN_PREFIX}${_android_api}-clang")
    set(ANDROID_AUTOTOOLS_CXX "${ANDROID_TOOLCHAIN_ROOT}/bin/${TOOLCHAIN_PREFIX}${_android_api}-clang++")
endif ()

# Includes an autotools recipe with the compiler it should call (see ANDROID_AUTOTOOLS_CC above).
macro(include_autotools_recipe recipe)
    if (ANDROID)
        set(_saved_cc "${CMAKE_C_COMPILER}")
        set(_saved_cxx "${CMAKE_CXX_COMPILER}")
        set(CMAKE_C_COMPILER "${ANDROID_AUTOTOOLS_CC}")
        set(CMAKE_CXX_COMPILER "${ANDROID_AUTOTOOLS_CXX}")
        include(${recipe})
        set(CMAKE_C_COMPILER "${_saved_cc}")
        set(CMAKE_CXX_COMPILER "${_saved_cxx}")
    else ()
        include(${recipe})
    endif ()
endmacro()

# Always build zlib/png/jpeg/freetype from the pinned recipes so the Linux and Android builds use the same versions.
include(${_recipes}/ZLIB/ZLIB.cmake)
set(ZLIB_PKG dep_ZLIB)
include(${_recipes}/PNG/PNG.cmake)
set(PNG_PKG dep_PNG)
include(${_recipes}/JPEG/JPEG.cmake)
if (ANDROID)
    # FreeType's recipe uses its internal zlib except on Linux; its symbols then collide with zlib's in a static
    # Android link (lld rejects what macOS's linker tolerates). Use the pinned zlib instead.
    set(DEP_CMAKE_OVERRIDES -DFT_DISABLE_ZLIB=FALSE -DFT_REQUIRE_ZLIB=TRUE)
endif ()
include(${_recipes}/FREETYPE/FREETYPE.cmake)
set(DEP_CMAKE_OVERRIDES "")
if (ANDROID)
    ExternalProject_Add_StepDependencies(dep_FREETYPE configure dep_ZLIB)
endif ()
set(FREETYPE_PKG dep_FREETYPE)

set(DEP_Boost_COMPONENTS system iostreams filesystem thread log locale regex date_time)
include(${_recipes}/Boost/Boost.cmake)
include(${_recipes}/Cereal/Cereal.cmake)
include(${_recipes}/Qhull/Qhull.cmake)
include(${_recipes}/TBB/TBB.cmake)
include(${_recipes}/Blosc/Blosc.cmake)
include(${_recipes}/OpenEXR/OpenEXR.cmake)
include(${_recipes}/OpenVDB/OpenVDB.cmake)
include_autotools_recipe(${_recipes}/GMP/GMP.cmake)
include_autotools_recipe(${_recipes}/MPFR/MPFR.cmake)
include(${_recipes}/Eigen/Eigen.cmake)
include(${_recipes}/CGAL/CGAL.cmake)
include(${_recipes}/NLopt/NLopt.cmake)
include(${_recipes}/libnoise/libnoise.cmake)
include(${_recipes}/Draco/Draco.cmake)
include(${_recipes}/OCCT/OCCT.cmake)
set(_saved_opts "${DEP_CMAKE_OPTS}")
if (ANDROID)
    # OpenCV's Android mode also builds its sample apps and Java bindings unless told not to.
    list(APPEND DEP_CMAKE_OPTS -DBUILD_ANDROID_PROJECTS=OFF -DBUILD_ANDROID_EXAMPLES=OFF -DBUILD_ANDROID_SERVICE=OFF -DBUILD_FAT_JAVA_LIB=OFF)
endif ()
include(${_recipes}/OpenCV/OpenCV.cmake)
set(DEP_CMAKE_OPTS "${_saved_opts}")

# libslic3r uses OpenSSL only for MD5 (3MF checksums). Linux builds use the system library; Android has none, so
# build libcrypto from the same OpenSSL release ElegooSlicer pins, with OpenSSL's own Android target.
set(_extra_deps "")
if (ANDROID)
    set(_openssl_url "https://github.com/openssl/openssl/archive/refs/tags/openssl-3.1.8.zip")
    _git_archive_for("${_openssl_url}" _openssl_tarball)
    if (_openssl_tarball)
        set(_openssl_source URL "${_openssl_tarball}")
    else ()
        set(_openssl_source URL "${_openssl_url}" URL_HASH SHA256=bbd5cbd8cc8ea852d31c001a9b767eadef0548b098e132b580a1f0c80d1778b7)
    endif ()
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        set(_openssl_target android-arm64)
    else ()
        set(_openssl_target android-x86_64)
    endif ()
    set(_openssl_env env "ANDROID_NDK_ROOT=${ANDROID_NDK}" "PATH=${ANDROID_TOOLCHAIN_ROOT}/bin:$ENV{PATH}")
    ExternalProject_Add(dep_OpenSSL
        EXCLUDE_FROM_ALL ON
        ${_openssl_source}
        DOWNLOAD_DIR ${DEP_DOWNLOAD_DIR}/OpenSSL
        BUILD_IN_SOURCE ON
        CONFIGURE_COMMAND ${_openssl_env} ./Configure ${_openssl_target} -D__ANDROID_API__=${ANDROID_PLATFORM_LEVEL}
            "--prefix=${DESTDIR}" "--openssldir=${DESTDIR}" --libdir=lib
            no-shared no-asm no-tests no-ssl3-method no-dynamic-engine no-engine
        BUILD_COMMAND ${_openssl_env} make -j${NPROC} build_libs
        INSTALL_COMMAND ${_openssl_env} make install_dev
    )
    list(APPEND _extra_deps dep_OpenSSL)
endif ()

add_custom_target(deps ALL DEPENDS ${_extra_deps}
    dep_ZLIB dep_PNG dep_JPEG dep_FREETYPE dep_Boost dep_TBB dep_Cereal dep_Qhull dep_GMP dep_MPFR dep_Eigen dep_CGAL
    dep_NLopt dep_libnoise dep_Draco dep_OCCT dep_OpenCV dep_Blosc dep_OpenEXR dep_OpenVDB)
