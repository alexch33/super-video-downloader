package congestion

import (
	"time"

	"github.com/apernet/quic-go/internal/monotime"
)

// Time is a monotonic timestamp used by congestion control.
type Time = monotime.Time

// Now returns the current monotonic time.
func Now() Time {
	return monotime.Now()
}

// Since returns the time elapsed since t.
func Since(t Time) time.Duration {
	return monotime.Since(t)
}

// Until returns the duration until t.
func Until(t Time) time.Duration {
	return monotime.Until(t)
}

// FromTime converts a time.Time to a monotonic Time.
func FromTime(t time.Time) Time {
	return monotime.FromTime(t)
}
