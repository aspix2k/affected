test_that("beta", {
  cat("BetaTest\n")
  writeLines("BetaTest", testthat::test_path("beta.marker"))
  expect_true(TRUE)
})
