cmake_minimum_required(VERSION 3.31)
project(termux_shared LANGUAGES CXX)

# Matches Android.mk: LOCAL_MODULE=local-socket, local-socket.cpp and -llog.
# cxx::cxx supplies static libc++ as in Application.mk (APP_STL=c++_static).
find_package(cxx REQUIRED CONFIG)
add_library(local-socket SHARED local-socket.cpp)
target_link_libraries(local-socket PRIVATE log cxx::cxx)
