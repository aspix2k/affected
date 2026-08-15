package alpha

import (
	"os"
	"path/filepath"
	"testing"
)

func TestValue(t *testing.T) {
	mark(t, "alpha-value.marker")
	if os.Getenv("AFFECTED_GO_FAIL") == "1" {
		t.Fatal("requested Go fixture failure")
	}
	if Value() != 1 {
		t.Fatal("unexpected value")
	}
}

func mark(t *testing.T, name string) {
	t.Helper()
	directory := os.Getenv("AFFECTED_GO_MARKER_DIR")
	if directory == "" {
		return
	}
	if err := os.WriteFile(filepath.Join(directory, name), []byte("ran"), 0o600); err != nil {
		t.Fatal(err)
	}
}
