\name{probe_value}
\alias{probe_value}
\title{Return the fixture value}
\description{Returns the integer value used by the native package-check fixture.}
\usage{probe_value()}
\value{The integer 42.}
\examples{
stopifnot(identical(probe_value(), 42L))
marker <- Sys.getenv("AFFECTED_R_CHECK_MARKER", unset = "")
if (nzchar(marker)) cat("RPackageExample\n", file = marker, append = TRUE)
}
