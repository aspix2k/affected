cmake_minimum_required(VERSION 3.29)
project(mixed_polyglot C)

enable_testing()
add_executable(mixed_alpha alpha_test.c)
add_test(NAME mixed_alpha COMMAND mixed_alpha)
