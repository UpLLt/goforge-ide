package main

import (
	"go/ast"
	"reflect"
	"strings"
	"testing"
)

// These tests exercise the captured-source AST planner only. Typed method-set
// proof and before/after compilation remain separate mandatory production gates.
func methodResultRequest(t *testing.T, text, name string) Input {
	t.Helper()
	p, err := parse(Source{URI: "file:///method-result/method.go", Text: text})
	if err != nil {
		t.Fatal(err)
	}
	var decl *ast.FuncDecl
	for _, node := range p.File.Decls {
		if f, ok := node.(*ast.FuncDecl); ok && f.Name.Name == name {
			decl = f
			break
		}
	}
	if decl == nil {
		t.Fatal("missing method declaration")
	}
	refs := []Location{}
	ast.Inspect(p.File, func(node ast.Node) bool {
		if id, ok := node.(*ast.Ident); ok && id.Name == name && id != decl.Name {
			a, b := p.Tokens.Offset(id.Pos()), p.Tokens.Offset(id.End())
			refs = append(refs, Location{p.URI, Range{position(text, a), position(text, b)}})
		}
		return true
	})
	at := p.Tokens.Offset(decl.Type.Func)
	return Input{Mode: "plan", Sources: []Source{p.Source}, Target: Location{p.URI, Range{position(text, at), position(text, at)}}, References: refs, Spec: Spec{Result: true, Type: "int64"}}
}

func TestMethodScalarResultSingleTypeToken(t *testing.T) {
	for _, receiver := range []string{"worker", "*worker"} {
		for _, named := range []bool{false, true} {
			label := receiver + "/unnamed"
			result, body := "int", "return 1"
			if named {
				label, result, body = receiver+"/named", "(value /* keep-result */ int)", "value=1;return"
			}
			t.Run(label, func(t *testing.T) {
				text := "package p\ntype worker struct{}\n// 世界🙂 — preserve receiver, parameter and body.\nfunc(w " + receiver + ") render(n int) " + result + " {" + body + "}\nfunc caller(){w:=worker{};_=w.render(1);_=w.render(2)}\n"
				in := methodResultRequest(t, text, "render")
				before := append([]Source(nil), in.Sources...)
				out := run(in)
				if !out.Passed || !out.Method || out.Checked != 2 {
					t.Fatalf("unsupported successful method result plan: %#v", out)
				}
				if len(out.Changes) != 1 || len(out.Changes[in.Target.URI]) != 1 {
					t.Fatalf("expected exactly one edited type token in the declaration: %#v", out.Changes)
				}
				edit := out.Changes[in.Target.URI][0]
				a, err := offset(text, edit.Range.Start)
				if err != nil {
					t.Fatal(err)
				}
				b, err := offset(text, edit.Range.End)
				if err != nil {
					t.Fatal(err)
				}
				if text[a:b] != "int" || edit.NewText != "int64" {
					t.Fatalf("edit differs from the existing result type token: %#v", edit)
				}
				wantResult := strings.Replace(result, "int", "int64", 1)
				want := strings.Replace(text, ") "+result+" {", ") "+wantResult+" {", 1)
				after := text[:a] + edit.NewText + text[b:]
				if after != want || !reflect.DeepEqual(in.Sources, before) {
					t.Fatalf("planner changed more than the result token or modified its source input:\n%s", after)
				}
				in.Mode, in.References = "target", nil
				target := run(in)
				if !target.Passed || !target.Method || target.Target == nil || len(target.Changes) != 0 || target.Checked != 0 {
					t.Fatalf("target phase must classify without edits/caller claims: %#v", target)
				}
			})
		}
	}
}

func TestMethodScalarResultScopeAndReferenceRefusals(t *testing.T) {
	base := "type worker struct{}\nfunc(w worker) render() int {return 1}\nfunc caller(){w:=worker{};_=w.render()}\n"
	for _, tc := range []struct {
		name, declarations, method, reason string
		change                             func(*Input)
	}{
		{name: "multiple-results", declarations: "type worker struct{}\nfunc(w worker) render()(int,int){return 1,2}\n", reason: "multiple results"},
		{name: "grouped-results", declarations: "type worker struct{}\nfunc(w worker) render()(left,right int){return 1,2}\n", reason: "multiple results"},
		{name: "no-result", declarations: "type worker struct{}\nfunc(w worker) render(){}\n", reason: "existing result"},
		{name: "alias-receiver", declarations: "type worker struct{}\ntype alias=worker\nfunc(w alias) render()int{return 1}\n", reason: "non-alias"},
		{name: "exported-receiver", declarations: "type Worker struct{}\nfunc(w Worker) render()int{return 1}\n", reason: "private non-generic"},
		{name: "exported-method", declarations: "type worker struct{}\nfunc(w worker) Render()int{return 1}\n", method: "Render", reason: "private method"},
		{name: "generic-receiver", declarations: "type worker[T any] struct{}\nfunc(w worker[T]) render()int{return 1}\n", reason: "non-generic"},
		{name: "unnamed-receiver", declarations: "type worker struct{}\nfunc(worker) render()int{return 1}\n", reason: "named concrete"},
		{name: "old-type-shadow", declarations: "type int=int32\n" + base, reason: "shadowed"},
		{name: "new-type-shadow", declarations: "type int64=int\n" + base, reason: "shadowed"},
		{name: "cross-file-type-shadow", declarations: base, reason: "shadowed", change: func(in *Input) {
			in.Sources = append(in.Sources, Source{URI: "file:///method-result/shadow.go", Text: "package p\ntype int64=int\n"})
		}},
		{name: "missing-reference", declarations: base, reason: "absent", change: func(in *Input) { in.References = []Location{} }},
		{name: "nil-references", declarations: base, reason: "complete bounded", change: func(in *Input) { in.References = nil }},
		{name: "duplicate-reference", declarations: base, reason: "duplicate", change: func(in *Input) { in.References = append(in.References, in.References[0]) }},
		{name: "outside-reference", declarations: base, reason: "outside", change: func(in *Input) { in.References[0].URI = "file:///outside/method.go" }},
		{name: "nonzero-result-index", declarations: base, reason: "existing result", change: func(in *Input) { in.Spec.Index = 1 }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			name := tc.method
			if name == "" {
				name = "render"
			}
			in := methodResultRequest(t, "package p\n"+tc.declarations, name)
			if tc.change != nil {
				tc.change(&in)
			}
			before := append([]Source(nil), in.Sources...)
			out := run(in)
			if out.Passed || !strings.Contains(out.Reason, tc.reason) || len(out.Changes) != 0 || out.Checked != 0 {
				t.Fatalf("refusal must retain its reason and emit no edits or checked-call claim: %#v", out)
			}
			if !reflect.DeepEqual(in.Sources, before) {
				t.Fatal("refusal mutated captured source input")
			}
		})
	}
}
