test_that("value", {
  writeLines("ran", testthat::test_path("mixed-r.marker"))
  expect_equal(value(), 1L)
})
