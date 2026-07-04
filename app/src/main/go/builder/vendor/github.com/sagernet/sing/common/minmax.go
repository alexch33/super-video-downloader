package common

import (
	"cmp"
)

// Deprecated: use the [min] builtin directly.
func Min[T cmp.Ordered](x, y T) T {
	return min(x, y)
}

// Deprecated: use the [max] builtin directly.
func Max[T cmp.Ordered](x, y T) T {
	return max(x, y)
}
