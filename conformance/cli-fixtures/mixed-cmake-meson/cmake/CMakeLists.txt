cmake_minimum_required(VERSION 3.29)
project(mixed_cmake C)

enable_testing()
add_executable(mixed_cmake_test value_test.c)
add_test(NAME mixed_cmake COMMAND mixed_cmake_test)
