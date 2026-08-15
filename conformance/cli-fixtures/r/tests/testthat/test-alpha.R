test_that("alpha", {
  cat("AlphaTest\n")
  writeLines("AlphaTest", testthat::test_path("alpha.marker"))
  expect_true(alpha())
})
