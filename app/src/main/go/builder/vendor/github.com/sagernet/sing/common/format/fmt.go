package format

import (
	"strconv"
	"strings"

	"github.com/sagernet/sing/common"
)

type Stringer interface {
	String() string
}

func ToString(messages ...any) string {
	var output strings.Builder
	for _, rawMessage := range messages {
		if rawMessage == nil {
			output.WriteString("nil")
			continue
		}
		switch message := rawMessage.(type) {
		case string:
			output.WriteString(message)
		case bool:
			if message {
				output.WriteString("true")
			} else {
				output.WriteString("false")
			}
		case uint:
			output.WriteString(strconv.FormatUint(uint64(message), 10))
		case uint8:
			output.WriteString(strconv.FormatUint(uint64(message), 10))
		case uint16:
			output.WriteString(strconv.FormatUint(uint64(message), 10))
		case uint32:
			output.WriteString(strconv.FormatUint(uint64(message), 10))
		case uint64:
			output.WriteString(strconv.FormatUint(message, 10))
		case int:
			output.WriteString(strconv.FormatInt(int64(message), 10))
		case int8:
			output.WriteString(strconv.FormatInt(int64(message), 10))
		case int16:
			output.WriteString(strconv.FormatInt(int64(message), 10))
		case int32:
			output.WriteString(strconv.FormatInt(int64(message), 10))
		case int64:
			output.WriteString(strconv.FormatInt(message, 10))
		case float32:
			output.WriteString(strconv.FormatFloat(float64(message), 'f', -1, 32))
		case float64:
			output.WriteString(strconv.FormatFloat(message, 'f', -1, 64))
		case uintptr:
			output.WriteString(strconv.FormatUint(uint64(message), 10))
		case error:
			output.WriteString(message.Error())
		case Stringer:
			output.WriteString(message.String())
		default:
			panic("unknown value")
		}
	}
	return output.String()
}

func ToString0[T any](message T) string {
	return ToString(message)
}

func MapToString[T any](arr []T) []string {
	return common.Map(arr, ToString0[T])
}

func Seconds(seconds float64) string {
	seconds100 := int(seconds * 100)
	return ToString(seconds100/100, ".", seconds100%100/10, seconds100%10)
}
