package main

import (
 "go/ast"
 "go/parser"
 "go/token"
 "go/types"
 "strings"
 "testing"
)

// The reference fixture includes every exact target identifier except its
// declaration. Refusal fixtures intentionally include function-value references;
// they must not disappear merely because they are not a supported call shape.
func genericRequest(t *testing.T, source string, spec Spec) Input {
 t.Helper()
 p, err := parse(Source{URI:"file:///generic/fixture.go", Text:source})
 if err != nil { t.Fatal(err) }
 var declaration *ast.FuncDecl
 for _, node := range p.File.Decls {
  if f, ok := node.(*ast.FuncDecl); ok && f.Name.Name == "F" { declaration=f;break }
 }
 if declaration == nil { t.Fatal("fixture has no F declaration") }
 start:=p.Tokens.Offset(declaration.Name.Pos())
 refs:=[]Location{}
 ast.Inspect(p.File,func(node ast.Node)bool{
  if id,ok:=node.(*ast.Ident);ok && id.Name=="F" && id!=declaration.Name {
   refs=append(refs,Location{p.URI,Range{position(source,p.Tokens.Offset(id.Pos())),position(source,p.Tokens.Offset(id.End()))}})
  }
  return true
 })
 return Input{Mode:"plan",Sources:[]Source{p.Source},Target:Location{p.URI,Range{position(source,start),position(source,start+1)}},References:refs,Spec:spec}
}
func genericApply(t *testing.T, in Input, result Output) string {
 t.Helper()
 original:=in.Sources[0].Text;after:=original
 edits:=result.Changes[in.Sources[0].URI]
 for i:=len(edits)-1;i>=0;i-- {
  edit:=edits[i]
  a,err:=offset(original,edit.Range.Start);if err!=nil{t.Fatal(err)}
  b,err:=offset(original,edit.Range.End);if err!=nil{t.Fatal(err)}
  if a!=b {t.Fatal("generic support must retain existing expression/type bytes")}
  after=after[:a]+edit.NewText+after[b:]
 }
 return after
}
func genericTypeCheck(t *testing.T, source string) {
 t.Helper()
 fs:=token.NewFileSet();file,err:=parser.ParseFile(fs,"fixture.go",source,parser.ParseComments)
 if err!=nil{t.Fatal(err)}
 conf:=types.Config{GoVersion:"go1.26",Sizes:types.SizesFor("gc","amd64")}
 if _,err=conf.Check("generic",fs,[]*ast.File{file},nil);err!=nil{t.Fatalf("source is not type-correct: %v\n%s",err,source)}
}
func genericTypeParameters(t *testing.T, source string) string {
 t.Helper();p,err:=parse(Source{URI:"file:///generic/fixture.go",Text:source});if err!=nil{t.Fatal(err)}
 for _,node:=range p.File.Decls{if f,ok:=node.(*ast.FuncDecl);ok&&f.Name.Name=="F"{
  if f.Type.TypeParams==nil{return ""}
  return source[p.Tokens.Offset(f.Type.TypeParams.Opening):p.Tokens.Offset(f.Type.TypeParams.Closing)+1]
 }}
 t.Fatal("missing declaration");return ""
}
func TestGenericAddParameterPreservesInferenceAndTypeArguments(t *testing.T) {
 single:="func F[T ~int](left T, right T) T { return left+right }\n"
 multi:="func F[A ~int, B ~string](left A, right B) A { return left }\n"
 for _,c:=range []struct{name,decl,call,want string;index int}{
  {"inferred-single",single,"var _=F(1,2)\n","F(1,7, 2)",1},
  {"explicit-single",single,"var _=F[int](1,2)\n","F[int](1,7, 2)",1},
  {"inferred-multiple",multi,"var _=F(1,\"x\")\n","F(1,7, \"x\")",1},
  {"explicit-multiple",multi,"var _=F[int,string](1,\"x\")\n","F[int,string](1,7, \"x\")",1},
  {"partial-explicit",multi,"var _=F[int](1,\"x\")\n","F[int](1,7, \"x\")",1},
  {"parenthesized-explicit",single,"var _=((F[int]))(1,2)\n","((F[int]))(1,7, 2)",1},
  {"parenthesized-complete-multiple",multi,"var _=(F[int,string])(1,\"x\")\n","(F[int,string])(1,7, \"x\")",1},
  {"first",single,"var _=F[int](1,2)\n","F[int](7, 1,2)",0},
  {"last",single,"var _=F(1,2)\n","F(1,2, 7)",2},
  {"zero-value-arguments","func F[T any]() {}\n","func Call(){F[int]()}\n","F[int](7)",0},
  {"dependent-constraints","func F[S ~[]E, E ~int](values S, item E) E {return values[0]+item}\n","var _=F([]int{1},2)\n","F([]int{1},7, 2)",1},
  {"named-type-inference","type Number int\n"+single,"var _=F(Number(1),Number(2))\n","F(Number(1),7, Number(2))",1},
  {"function-argument-inference","func F[T any](fn func(T) T, value T) T {return fn(value)}\n","var _=F(func(n int) int{return n+1},2)\n","F(func(n int) int{return n+1},7, 2)",1},
  {"recursive","func F[T ~int](a T, b T) T {if a==0{return b};return F(a-1,b)}\n","var _=F[int](2,3)\n","F(a-1,7, b)",1},
  {"nested-calls",single,"var _=F(F(1,2),3)\n","F(F(1,7, 2),7, 3)",1},
  {"unicode-and-type-comments","// 世界🙂\nfunc F[T /* keep constraint */ ~int](left T, right T) T {return left+right}\n","var _=F[/* keep argument */int](1,2)\n","F[/* keep argument */int](1,7, 2)",1},
  {"unchanged-effectful-arguments",single,"func one()int{return 1}\nfunc two()int{return 2}\nvar _=F[int](one(),two())\n","F[int](one(),7, two())",1},
  {"non-generic-regression","func F(left int,right int) int{return left+right}\n","var _=(F)(1,2)\n","(F)(1,7, 2)",1},
 } {
  t.Run(c.name,func(t *testing.T){
   source:="package generic\n"+c.decl+c.call
   genericTypeCheck(t,source)
   in:=genericRequest(t,source,Spec{Name:"extra",Type:"int",Index:c.index,DefaultValue:"7"})
   originalTypeParameters:=genericTypeParameters(t,source)
   result:=run(in);if !result.Passed{t.Fatal(result.Reason)}
   if result.Checked!=len(in.References){t.Fatalf("did not check every caller: %d/%d",result.Checked,len(in.References))}
   after:=genericApply(t,in,result)
   if !strings.Contains(after,c.want){t.Fatalf("type arguments or old call expressions changed; want %s\n%s",c.want,after)}
   if got:=genericTypeParameters(t,after);got!=originalTypeParameters{t.Fatalf("type parameters/constraints changed: %q vs %q",got,originalTypeParameters)}
   genericTypeCheck(t,after)
  })
 }
}
func TestGenericAddParameterRefusesUnprovenCallsAndBindings(t *testing.T) {
 base:="package generic\nfunc F[T ~int](left T,right T)T{return left+right}\n"
 spec:=Spec{Name:"extra",Type:"int",Index:1,DefaultValue:"7"}
 for _,c:=range []struct{name,source,reason string;spec Spec;modify func(*Input)}{
  {name:"instantiated-function-value",source:base+"var saved=F[int]\nvar _=saved(1,2)\n",reason:"not a verified direct call"},
  {name:"generic-value-inferred-by-assignment",source:base+"var saved func(int,int)int=F\n",reason:"not a verified direct call"},
  {name:"nested-instantiation",source:base+"var _=F[int][int](1,2)\n",reason:"not a verified direct call"},
  {name:"parenthesized-nested-instantiation",source:base+"var _=(F[int])[int](1,2)\n",reason:"not a verified direct call"},
  {name:"parenthesized-uninstantiated-generic",source:base+"var _=(F)(1,2)\n",reason:"not a verified direct call"},
  {name:"parentheses-around-generic-base",source:base+"var _=(F)[int](1,2)\n",reason:"not a verified direct call"},
  {name:"parenthesized-partial-generic",source:"package generic\nfunc F[A ~int,B ~string](left A,right B)A{return left}\nvar _=(F[int])(1,\"x\")\n",reason:"not a verified direct call"},
  {name:"too-many-type-arguments",source:base+"var _=F[int,int](1,2)\n",reason:"not a verified direct call"},
  {name:"index-argument-is-not-callee",source:base+"var _=F[F](1,2)\n",reason:"not a verified direct call"},
  {name:"non-generic-indexed",source:"package generic\nfunc F(left int,right int)int{return left+right}\nvar _=F[int](1,2)\n",reason:"not a verified direct call"},
  {name:"unbounded-callee-wrapping",source:base+"var _="+strings.Repeat("(",70)+"F[int]"+strings.Repeat(")",70)+"(1,2)\n",reason:"not a verified direct call"},
  {name:"type-parameter-shadows-int",source:"package generic\nfunc F[int any](left int,right int)int{return left}\nvar _=F(1,2)\n",reason:"shadowed by a function type parameter"},
  {name:"type-parameter-shadows-bool",source:"package generic\nfunc F[bool any](left bool,right bool)bool{return left}\nvar _=F(true,false)\n",reason:"shadowed by a function type parameter",spec:Spec{Name:"extra",Type:"bool",Index:1,DefaultValue:"true"}},
  {name:"new-name-is-type-parameter",source:base+"var _=F(1,2)\n",reason:"new parameter name already appears",spec:Spec{Name:"T",Type:"int",Index:1,DefaultValue:"7"}},
  {name:"new-name-is-constraint",source:"package generic\ntype extra interface{~int}\nfunc F[T extra](left T,right T)T{return left}\nvar _=F(1,2)\n",reason:"new parameter name already appears"},
  {name:"new-name-is-captured-in-closure",source:"package generic\nvar extra=3\nfunc F[T ~int](left T,right T)T{use:=func()int{return extra};_ =use;return left}\nvar _=F(1,2)\n",reason:"new parameter name already appears"},
  {name:"package-type-shadows-int",source:"package generic\ntype int string\nfunc F[T any](left T,right T)T{return left}\nvar _=F(\"a\",\"b\")\n",reason:"shadowed in the declaration package"},
  {name:"generic-variadic",source:"package generic\nfunc F[T any](values ...T){}\nfunc Call(){F(1,2)}\n",reason:"variadic signatures"},
  {name:"generic-tuple-argument",source:base+"func pair()(int,int){return 1,2}\nvar _=F(pair())\n",reason:"tuple-valued calls"},
  {name:"method-of-generic-receiver",source:"package generic\ntype Box[T any]struct{}\nfunc (box Box[T]) F(left T,right T)T{return left}\n",reason:"methods and external declarations"},
  {name:"linkage-directive",source:"package generic\n//go:linkname F other.F\nfunc F[T ~int](left T,right T)T{return left}\n",reason:"compiler/linkage directives"},
  {name:"missing-reference",source:base+"var _=F[int](1,2)\n",reason:"absent from the direct-call reference set",modify:func(in *Input){in.References=[]Location{}}},
  {name:"duplicate-reference",source:base+"var _=F[int](1,2)\n",reason:"duplicate caller reference",modify:func(in *Input){in.References=append(in.References,in.References[0])}},
  {name:"outside-reference",source:base+"var _=F[int](1,2)\n",reason:"outside the captured project",modify:func(in *Input){in.References[0].URI="file:///outside.go"}},
 } {
  t.Run(c.name,func(t *testing.T){
   use:=c.spec;if use.Name==""{use=spec}
   in:=genericRequest(t,c.source,use);if c.modify!=nil{c.modify(&in)}
   result:=run(in)
   if result.Passed||len(result.Changes)!=0||!strings.Contains(result.Reason,c.reason){t.Fatalf("wanted refusal %q, got %+v",c.reason,result)}
  })
 }
}
