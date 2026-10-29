// Original GoForge source-only indexer. No package loading or user-code execution.
package main

import (
	"encoding/json"
	"go/ast"
	"go/doc"
	"go/parser"
	"go/token"
	"os"
	"strings"
	"unicode"
	"unicode/utf8"
)

type request struct { File string `json:"file"`; Text string `json:"text"` }
type declaration struct {
	Name string `json:"name"`
	Kind string `json:"kind"`
	Start int `json:"start"`
	End int `json:"end"`
	NameStart int `json:"nameStart"`
}
type result struct { Passed bool `json:"passed"`; Reason string `json:"reason,omitempty"`; Declarations []declaration `json:"declarations"` }

func testName(name string) bool {
	if !strings.HasPrefix(name, "Test") { return false }
	if name == "Test" { return true }
	r, _ := utf8.DecodeRuneInString(name[len("Test"):])
	return !unicode.IsLower(r)
}
func testShape(f *ast.FuncDecl) bool {
	if f.Recv != nil || f.Type.TypeParams != nil && len(f.Type.TypeParams.List) != 0 || f.Body == nil { return false }
	if f.Type.Results != nil && len(f.Type.Results.List) != 0 || f.Type.Params == nil || len(f.Type.Params.List) != 1 { return false }
	p := f.Type.Params.List[0]
	if len(p.Names) > 1 { return false }
	star, ok := p.Type.(*ast.StarExpr); if !ok { return false }
	switch t := star.X.(type) {
	case *ast.Ident: return t.Name == "T"
	case *ast.SelectorExpr: return t.Sel.Name == "T"
	}
	return false
}
func inspect(in request) result {
	fail := func(reason string) result { return result{Reason:reason} }
	if !strings.HasSuffix(in.File, "_test.go") || len(in.Text) > 8*1024*1024 || !utf8.ValidString(in.Text) { return fail("Expected a bounded UTF-8 Go test source.") }
	set := token.NewFileSet()
	file, err := parser.ParseFile(set, in.File, in.Text, parser.ParseComments|parser.SkipObjectResolution)
	if err != nil { return fail("The captured Go source contains a syntax error; save and correct it before selecting a test.") }
	tokens := set.File(file.Pos())
	examples := map[string]bool{}
	for _, ex := range doc.Examples(file) {
		if ex.Output != "" || ex.EmptyOutput { examples["Example"+ex.Name] = true }
	}
	out := result{Passed:true, Declarations:[]declaration{}}
	seen := map[string]bool{}
	for _, node := range file.Decls {
		f, ok := node.(*ast.FuncDecl)
		if !ok || f.Recv != nil || f.Body == nil { continue }
		kind := ""
		if testName(f.Name.Name) && testShape(f) { kind = "test" }
		if examples[f.Name.Name] { kind = "example" }
		if kind == "" { continue }
		if seen[f.Name.Name] { return fail("The captured source contains duplicate test names.") }
		seen[f.Name.Name] = true
		out.Declarations = append(out.Declarations, declaration{f.Name.Name,kind,tokens.Offset(f.Pos()),tokens.Offset(f.End()),tokens.Offset(f.Name.Pos())})
		if len(out.Declarations)>16384 { return fail("The captured source contains too many test declarations.") }
	}
	return out
}
func main() {
	out := result{Reason:"Invalid source-index request."}
	if len(os.Args)==2 {
		data, err := os.ReadFile(os.Args[1])
		if err==nil && len(data)<=64*1024*1024 {
			var in request
			decoder := json.NewDecoder(strings.NewReader(string(data)))
			decoder.DisallowUnknownFields()
			if decoder.Decode(&in)==nil { out=inspect(in) }
		}
	}
	_ = json.NewEncoder(os.Stdout).Encode(out)
}
