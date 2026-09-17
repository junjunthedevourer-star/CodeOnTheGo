cmake_minimum_required(VERSION 3.31)
project(termux_emulator LANGUAGES C)

# Mirrors Android.mk: LOCAL_MODULE=libtermux; LOCAL_SRC_FILES=termux.c.
# CMake already prepends 'lib' to the termux target, producing libtermux.so.
add_library(termux SHARED termux.c)
target_compile_options(termux PRIVATE -std=c11 -Wall -Wextra -Werror -Os -fno-stack-protector)
target_link_options(termux PRIVATE -Wl,--gc-sections)
