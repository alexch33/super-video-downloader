package common

import "sync"

// Deprecated: use [sync.OnceFunc] directly.
func OnceFunc(f func()) func() {
	return sync.OnceFunc(f)
}

// Deprecated: use [sync.OnceValue] directly.
func OnceValue[T any](f func() T) func() T {
	return sync.OnceValue(f)
}

// Deprecated: use [sync.OnceValues] directly.
func OnceValues[T1, T2 any](f func() (T1, T2)) func() (T1, T2) {
	return sync.OnceValues(f)
}
