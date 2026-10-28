package greeting

import "fmt"

// Hello returns a greeting. Use this symbol for navigation and rename checks.
func Hello(name string) string {
	return fmt.Sprintf("Hello, %s!", name)
}
