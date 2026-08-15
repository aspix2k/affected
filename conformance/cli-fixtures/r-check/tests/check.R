library(affectedcheck)

marker <- Sys.getenv("AFFECTED_R_CHECK_MARKER", unset = NA_character_)
if (!is.na(marker)) {
  cat("RPackageCheck\n", file = marker, append = TRUE)
}

stopifnot(identical(probe_value(), 42L))
