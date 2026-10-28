package main

import (
	"fmt"
	"sync"

	"example.com/goforge/hello/greeting"
)

func main() {
	var workers sync.WaitGroup
	workers.Add(1)
	go func() {
		defer workers.Done()
		message := greeting.Hello("GoForge") // Debug fixture: inspect message here.
		fmt.Println(message)
	}()
	workers.Wait()
}
