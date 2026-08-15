cat("SetupRun\n")
argv_marker <- Sys.getenv("AFFECTED_R_ARGV_MARKER")
if (nzchar(argv_marker)) writeLines(commandArgs(trailingOnly = TRUE), argv_marker)
