package beta

import (
	"os"
	"path/filepath"
	"testing"
)

func TestValue(t *testing.T) {
	directory := os.Getenv("AFFECTED_GO_MARKER_DIR")
	if directory != "" {
		if err := os.WriteFile(filepath.Join(directory, "beta.marker"), []byte("ran"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	if Value() != 2 {
		t.Fatal("unexpected value")
	}
}
