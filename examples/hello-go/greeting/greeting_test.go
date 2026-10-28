package greeting

import "testing"

func TestHello(t *testing.T) {
	for _, name := range []string{"GoForge", "Go 开发者"} {
		t.Run(name, func(t *testing.T) {
			if got := Hello(name); got != "Hello, "+name+"!" {
				t.Fatalf("unexpected greeting: %q", got)
			}
		})
	}
}
