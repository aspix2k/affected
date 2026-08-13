cmake_minimum_required(VERSION 3.29)
project(affected_cli_fixture C)

option(AFFECTED_FIXTURE_ENABLE_GENERATED_TEST "Enable a generated CTest executable" OFF)
option(AFFECTED_FIXTURE_ENABLE_RESOURCE_TEST "Enable a resource-dependent CTest test" OFF)
option(AFFECTED_FIXTURE_ENABLE_CTEST_FIXTURE "Enable CTest setup and cleanup fixtures" OFF)

enable_testing()

add_library(affected_alpha_library STATIC alpha.c)
target_include_directories(affected_alpha_library PUBLIC include)
add_executable(affected_alpha_runner tests/alpha_test.c)
target_link_libraries(affected_alpha_runner PRIVATE affected_alpha_library)
add_test(
    NAME affected_alpha
    COMMAND affected_alpha_runner "${CMAKE_CURRENT_BINARY_DIR}/affected-alpha.marker"
)

add_library(affected_alpha_extended_library STATIC alpha_extended.c)
target_include_directories(affected_alpha_extended_library PUBLIC include)
add_executable(affected_alpha_extended_runner tests/alpha_extended_test.c)
target_link_libraries(affected_alpha_extended_runner PRIVATE affected_alpha_extended_library)
add_test(
    NAME affected_alpha_extended
    COMMAND affected_alpha_extended_runner "${CMAKE_CURRENT_BINARY_DIR}/affected-alpha-extended.marker"
)

add_library(affected_beta_library STATIC beta.c)
target_include_directories(affected_beta_library PUBLIC include)
add_executable(affected_beta_runner tests/beta_test.c)
target_link_libraries(affected_beta_runner PRIVATE affected_beta_library)

if(AFFECTED_FIXTURE_ENABLE_CTEST_FIXTURE)
    set(fixture_marker "${CMAKE_CURRENT_BINARY_DIR}/affected-fixture.ready")
    add_test(NAME affected_fixture_setup COMMAND "${CMAKE_COMMAND}" -E touch "${fixture_marker}")
    set_tests_properties(affected_fixture_setup PROPERTIES FIXTURES_SETUP affected_runtime)
    add_test(
        NAME affected_beta
        COMMAND affected_beta_runner
            "${fixture_marker}"
            "${CMAKE_CURRENT_BINARY_DIR}/affected-beta.marker"
    )
    set_tests_properties(affected_beta PROPERTIES FIXTURES_REQUIRED affected_runtime)
    add_test(NAME affected_fixture_cleanup COMMAND "${CMAKE_COMMAND}" -E rm -f "${fixture_marker}")
    set_tests_properties(affected_fixture_cleanup PROPERTIES FIXTURES_CLEANUP affected_runtime)
else()
    add_test(
        NAME affected_beta
        COMMAND affected_beta_runner "${CMAKE_CURRENT_BINARY_DIR}/affected-beta.marker"
    )
endif()

if(AFFECTED_FIXTURE_ENABLE_GENERATED_TEST)
    configure_file(
        generated_test.cmake.in
        "${CMAKE_CURRENT_BINARY_DIR}/affected_generated_test.cmake"
        @ONLY
    )
    include("${CMAKE_CURRENT_BINARY_DIR}/affected_generated_test.cmake")
endif()

if(AFFECTED_FIXTURE_ENABLE_RESOURCE_TEST)
    set_tests_properties(
        affected_alpha
        PROPERTIES REQUIRED_FILES "${CMAKE_CURRENT_SOURCE_DIR}/resources/alpha.required"
    )
endif()
