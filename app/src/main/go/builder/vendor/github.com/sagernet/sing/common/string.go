package common

import "strings"

func SubstringAfter(s string, substr string) string {
	_, after, found := strings.Cut(s, substr)
	if found {
		return after
	}
	return s
}

func SubstringAfterLast(s string, substr string) string {
	index := strings.LastIndex(s, substr)
	if index == -1 {
		return s
	}
	return s[index+len(substr):]
}

func SubstringBefore(s string, substr string) string {
	before, _, found := strings.Cut(s, substr)
	if found {
		return before
	}
	return s
}

func SubstringBeforeLast(s string, substr string) string {
	index := strings.LastIndex(s, substr)
	if index == -1 {
		return s
	}
	return s[:index]
}

func SubstringBetween(s string, after string, before string) string {
	return SubstringBefore(SubstringAfter(s, after), before)
}
