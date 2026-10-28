These JSONL files are unmodified output captured from the bundled Go 1.26.4 on
2026-10-27. They are replay data, not mock assertions that a database or IDE ran.

ui-fixture-benchmark.jsonl uses the generated integrations UI workspace:
  go test -json -run '^$' -bench . -benchtime=10ms -count=1 .
The other fixtures use example.com/benchmark-event-probe with GOWORK=off,
GOENV=off and GOTOOLCHAIN=local:
  go test -json -run '^$' -benchtime=1x -bench '^BenchmarkPlain$' .
and corresponding BenchmarkLogged, BenchmarkNested, BenchmarkSkip, BenchmarkFail.
repeat-classic additionally uses -count=2 -cpu=1,2 and BenchmarkClassic.
empty-parent uses -bench '^BenchmarkEmptyParent$/nomatch'.

Plain/Logged use for b.Loop() {}; Logged calls b.Log before the loop.
Nested runs child/leaf sub-benchmarks; EmptyParent runs child with no matching
sub-benchmark selected. Skip uses b.Skip; Fail deliberately uses b.Fatal.
Classic uses for i := 0; i < b.N; i++ {}.
The fail fixture exits 1; all other recorded fixtures exit 0.

Source contract: Go 1.26.4 src/testing/benchmark.go (B.Run, run1, processBench),
src/cmd/internal/test2json/test2json.go, and src/cmd/go/internal/load/test.go:isTest.
Unlike ordinary tests, successful benchmarks have no unconditional individual
pass/bench terminal event. A filtered parent can also have no timing result.
The converter only completes explicitly started benchmark nodes at a package
pass, retaining ordinary missing-terminal and failed/interrupted-package guards.

Full capture metadata, source snapshots and baseline/fixed replay results remain
under .artifacts/benchmark-events-20261027. No user project was executed.
