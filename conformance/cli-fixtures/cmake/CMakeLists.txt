cmake_minimum_required(VERSION 3.20)
project(affected_cli_fixture C)

enable_testing()

add_executable(affected_alpha alpha.c)
add_executable(affected_beta beta.c)
add_test(NAME affected_alpha COMMAND affected_alpha)
add_test(NAME affected_beta COMMAND affected_beta)
