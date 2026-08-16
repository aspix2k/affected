//go:build affected_never

package alpha

import "testing"

func TestExcluded(t *testing.T) {
	mark(t, "excluded.marker")
}
