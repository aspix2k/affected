test_that("unrelated", {
  cat("UnrelatedTest\n")
  writeLines("UnrelatedTest", testthat::test_path("unrelated.marker"))
  expect_true(TRUE)
})
