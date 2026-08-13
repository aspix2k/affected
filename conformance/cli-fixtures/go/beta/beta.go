package beta

import "example.com/affected-fixture/alpha"

func Value() int {
	return alpha.Value() + 1
}
