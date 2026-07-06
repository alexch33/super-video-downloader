package common

import (
	"context"
	"reflect"
)

// Deprecated: use [context.CancelCauseFunc] directly.
type ContextCancelCauseFunc = context.CancelCauseFunc

var (
	// Deprecated: use [context.WithCancelCause] directly.
	ContextWithCancelCause = context.WithCancelCause
	// Deprecated: use [context.Cause] directly.
	ContextCause = context.Cause
)

// Deprecated: not used
func SelectContext(contextList []context.Context) (int, error) {
	if len(contextList) == 1 {
		<-contextList[0].Done()
		return 0, contextList[0].Err()
	}
	chosen, _, _ := reflect.Select(Map(Filter(contextList, func(it context.Context) bool {
		return it.Done() != nil
	}), func(it context.Context) reflect.SelectCase {
		return reflect.SelectCase{
			Dir:  reflect.SelectRecv,
			Chan: reflect.ValueOf(it.Done()),
		}
	}))
	return chosen, contextList[chosen].Err()
}
