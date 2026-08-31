cmake_minimum_required(VERSION 3.22.1)
project(mosh_pty_bridge C)

add_library(mosh_pty_bridge SHARED mosh_pty_bridge.c)
target_link_libraries(mosh_pty_bridge log)
