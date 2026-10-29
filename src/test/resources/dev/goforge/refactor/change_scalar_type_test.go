package main

import (
 "go/ast"
 "strings"
 "testing"
)
func request(t *testing.T, text string, spec Spec) Input {
 t.Helper();p,err:=parse(Source{URI:"file:///scalar/a.go",Text:text});if err!=nil{t.Fatal(err)}
 var f *ast.FuncDecl;for _,n:=range p.File.Decls{if d,ok:=n.(*ast.FuncDecl);ok&&d.Name.Name=="F"{f=d;break}};if f==nil{t.Fatal("missing F")}
 refs:=[]Location{};ast.Inspect(p.File,func(n ast.Node)bool{if id,ok:=n.(*ast.Ident);ok&&id.Name=="F"&&id!=f.Name{a,b:=p.Tokens.Offset(id.Pos()),p.Tokens.Offset(id.End());refs=append(refs,Location{p.URI,Range{position(text,a),position(text,b)}})};return true})
 at:=p.Tokens.Offset(f.Type.Func);return Input{Mode:"plan",Sources:[]Source{p.Source},Target:Location{p.URI,Range{position(text,at),position(text,at)}},References:refs,Spec:spec}
}
func TestScalarTypeTokenPlan(t *testing.T){
 for _,c:=range []struct{name,text,want string;spec Spec}{
  {"parameter","package p\nfunc F(x int)string{return \"ok\"}\nvar _=F(1)\n","func F(x int64)",Spec{Type:"int64"}},
  {"ordinary-import","package p\nimport \"fmt\"\nfunc F(x int)string{return fmt.Sprint(x)}\nvar _=F(1)\n","func F(x int64)",Spec{Type:"int64"}},
  {"second-parameter","package p\nfunc F(x string,y int){}\nfunc G(){F(\"a\",2)}\n","x string,y uint8",Spec{Index:1,Type:"uint8"}},
  {"unnamed-parameter","package p\nfunc F(int){}\nfunc G(){F(2)}\n","func F(int32)",Spec{Type:"int32"}},
  {"result","package p\nfunc F()int{return 1}\nvar _=F()\n","F()int64",Spec{Result:true,Type:"int64"}},
  {"named-result","package p\nfunc F()(value int){return 1}\nvar _=F()\n","value int64",Spec{Result:true,Type:"int64"}},
  {"generic-scalar-parameter","package p\nfunc F[T ~string](s T,n int)T{return s}\nvar _=F(\"a\",2)\n","s T,n int64",Spec{Index:1,Type:"int64"}},
  {"generic-result","package p\nfunc F[T ~string](s T)int{return len(s)}\nvar _=F[string](\"a\")\n","(s T)int64",Spec{Result:true,Type:"int64"}},
  {"unicode-and-comments","package p\n/* 世界🙂 */ func F(x /* keep */ int){}\nfunc G(){F(2)}\n","x /* keep */ int64",Spec{Type:"int64"}},
 }{t.Run(c.name,func(t *testing.T){in:=request(t,c.text,c.spec);out:=run(in);if !out.Passed{t.Fatal(out.Reason)};edits:=out.Changes[in.Target.URI];if len(edits)!=1{t.Fatal("expected exactly one type edit")};e:=edits[0];a,_:=offset(c.text,e.Range.Start);b,_:=offset(c.text,e.Range.End);after:=c.text[:a]+e.NewText+c.text[b:];if !strings.Contains(after,c.want){t.Fatal(after)};if out.Checked!=len(in.References){t.Fatal("unchecked refs")}})}
}
func TestScalarTypeRefusals(t *testing.T){
 base:="package p\nfunc F(x int)int{return x}\nvar _=F(1)\n"
 for _,c:=range []struct{name,text,reason string;spec Spec;modify func(*Input)}{
  {name:"unchanged",text:base,reason:"different scalar",spec:Spec{Type:"int"}},
  {name:"new-composite",text:base,reason:"predeclared scalar",spec:Spec{Type:"[]int"}},
  {name:"existing-named-type",text:"package p\ntype N int\nfunc F(x N){}\nfunc G(){F(1)}\n",reason:"existing type"},
  {name:"grouped-parameters",text:"package p\nfunc F(x,y int){}\nfunc G(){F(1,2)}\n",reason:"grouped"},
  {name:"variadic",text:"package p\nfunc F(xs ...int){}\nfunc G(){F(1)}\n",reason:"variadic"},
  {name:"method",text:"package p\ntype S struct{}\nfunc(S) F(x int){}\n",reason:"free-function"},
  {name:"multiple-results",text:"package p\nfunc F()(int,int){return 1,2}\n",reason:"multiple results",spec:Spec{Result:true,Type:"int64"}},
  {name:"grouped-results",text:"package p\nfunc F()(x,y int){return 1,2}\n",reason:"multiple results",spec:Spec{Result:true,Type:"int64"}},
  {name:"no-result",text:"package p\nfunc F(){}\n",reason:"existing result",spec:Spec{Result:true,Type:"int64"}},
  {name:"invalid-index",text:base,reason:"outside",spec:Spec{Index:2,Type:"int64"}},
  {name:"shadow-old",text:"package p\ntype int int64\nfunc F(x int){}\n",reason:"shadowed"},
  {name:"shadow-new",text:"package p\ntype int64 int\nfunc F(x int){}\n",reason:"shadowed"},
  {name:"shadow-type-parameter",text:"package p\nfunc F[int64 any](x int){}\nfunc G(){F[int](1)}\n",reason:"type parameter"},
  {name:"function-value",text:base+"var value=F\n",reason:"not a verified direct call"},
  {name:"missing-reference",text:base,reason:"absent",modify:func(in *Input){in.References=[]Location{}}},
  {name:"outside-reference",text:base,reason:"outside",modify:func(in *Input){in.References[0].URI="file:///outside.go"}},
  {name:"duplicate-reference",text:base,reason:"duplicate",modify:func(in *Input){in.References=append(in.References,in.References[0])}},
  {name:"compiler-directive",text:"package p\n//go:noinline\nfunc F(x int){}\n",reason:"directives"},
  {name:"detached-linkage-directive",text:"package p\n//go:linkname Other F\nvar other int\nfunc F(x int){}\n",reason:"linkage"},
  {name:"conditional-build",text:"//go:build linux\n\n"+base,reason:"conditional"},
  {name:"embed",text:"package p\n//go:embed data.txt\nvar text string\nfunc F(x int){}\n",reason:"embedded"},
  {name:"shadow-import",text:"package p\nimport int64 \"fmt\"\nfunc F(x int){int64.Println(x)}\n",reason:"shadow"},
 }{t.Run(c.name,func(t *testing.T){s:=c.spec;if s.Type==""{s.Type="int64"};in:=request(t,c.text,s);if c.modify!=nil{c.modify(&in)};out:=run(in);if out.Passed||!strings.Contains(out.Reason,c.reason){t.Fatalf("unexpected result: %#v",out)}})}
}
