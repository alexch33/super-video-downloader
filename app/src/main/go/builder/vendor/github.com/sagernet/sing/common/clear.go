package common

// Deprecated: use the [clear] builtin directly.
func ClearArray[T ~[]E, E any](t T) {
	clear(t)
}

// Deprecated: use the [clear] builtin directly.
func ClearMap[T ~map[K]V, K comparable, V any](t T) {
	clear(t)
}
