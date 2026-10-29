package main

import("encoding/json";"go/ast";"go/parser";"go/token";"os";"path/filepath";"runtime";"strings";"testing")
func point(text string,at int)Position{p:=Position{};for _,r:=range text[:at]{if r=='\n'{p.Line++;p.Character=0}else if r>0xffff{p.Character+=2}else{p.Character++}};return p}
func proofFixture(t *testing.T,source,other string)Input{t.Helper();root:=t.TempDir();texts:=map[string]string{"worker.go":source};if other!=""{texts["worker_test.go"]=other};in:=Input{Arch:runtime.GOARCH};pkg:=Package{Dir:root,ImportPath:"example.test/proof",Name:"p",GoFiles:[]string{"worker.go"},Module:&Module{GoVersion:"1.26"}};if other!=""{pkg.TestGoFiles=[]string{"worker_test.go"}};in.Request.References=[]Location{}
 for name,text:=range texts{path:=filepath.Join(root,name);if e:=os.WriteFile(path,[]byte(text),0600);e!=nil{t.Fatal(e)};uri:="file://"+filepath.ToSlash(path);in.Sources=append(in.Sources,Source{URI:uri,Path:path,Text:text});fs:=token.NewFileSet();file,e:=parser.ParseFile(fs,path,text,0);if e!=nil{t.Fatal(e)};tokens:=fs.File(file.Pos());for _,d:=range file.Decls{if f,ok:=d.(*ast.FuncDecl);ok&&f.Recv!=nil&&f.Name.Name=="add"{in.Request.Target=Location{uri,Range{point(text,tokens.Offset(f.Name.Pos())),point(text,tokens.Offset(f.Name.End()))}}}};ast.Inspect(file,func(n ast.Node)bool{if s,ok:=n.(*ast.SelectorExpr);ok&&s.Sel.Name=="add"{in.Request.References=append(in.Request.References,Location{uri,Range{point(text,tokens.Offset(s.Sel.Pos())),point(text,tokens.Offset(s.Sel.End()))}})};return true})}
 b,_:=json.Marshal(pkg);in.Packages=filepath.Join(root,"graph.json");if e:=os.WriteFile(in.Packages,b,0600);e!=nil{t.Fatal(e)};return in}
const header="package p\ntype worker struct{ n int }\nfunc (w worker) add(x int) int{return w.n+x}\n"
func TestTypedMethodUsageAndContractBoundaries(t *testing.T){cases:=[]struct{name,text,other,reason string}{
 {"value-calls-cross-file",header+"func call(){w:=worker{};_=w.add(1)}\n","package p\nfunc testCall(){w:=worker{};_=w.add(2)}\n",""},
 {"pointer-receiver",strings.Replace(header,"(w worker)","(w *worker)",1)+"func call(){w:=worker{};_=w.add(1);p:=&w;_=p.add(2)}\n","",""},
 {"method-expression",header+"func call(){w:=worker{};_=worker.add(w,1)}\n","","method values, expressions"},
 {"method-value",header+"func call(){w:=worker{};f:=w.add;_=f(1)}\n","","method values, expressions"},
 {"parenthesized-method-value",header+"func call(){w:=worker{};_=(w.add)(1)}\n","","method values, expressions"},
 {"promoted-method",header+"type wrapper struct{worker}\nfunc call(){w:=wrapper{};_=w.add(1)}\n","","promoted"},
 {"nontrivial-receiver",header+"func value()worker{return worker{}}\nfunc call(){_=value().add(1)}\n","","identifier"},
 {"old-interface-contract",header+"type contract interface{add(int)int}\nvar _=any(worker{}).(contract)\n","","related interface"},
 {"new-interface-contract",header+"type contract interface{add(int,int)int}\nfunc check(){_,_=any(worker{}).(contract)}\n","","related interface"},
 {"anonymous-assertion",header+"func check(){_,_=any(worker{}).(interface{add(int)int})}\n","","related interface"},
 {"anonymous-switch",header+"func check(v any){switch v.(type){case interface{add(int)int}:}}\n","","related interface"},
 {"aliased-embedded-interface",header+"type base interface{add(int)int};type alias=base;type contract interface{alias}\n","","related interface"},
 {"constraint",header+"func constrained[T interface{add(int)int}](v T){}\n","","related interface"},
 {"test-only-contract",header,"package p\nfunc check(){_,_=any(worker{}).(interface{add(int,int)int})}\n","related interface"},
 {"unrelated-interface",header+"type contract interface{other(int)int}\nfunc call(){w:=worker{};_=w.add(1)}\n","",""},
 {"generic-receiver","package p\ntype worker[T any] struct{}\nfunc(w worker[T])add(x int)int{return x}\n","","non-generic"},
 {"public-receiver",strings.ReplaceAll(header,"worker","Worker"),"","private non-generic"},
 }
 for _,c:=range cases{t.Run(c.name,func(t *testing.T){result:=run(proofFixture(t,c.text,c.other));if c.reason==""{if !result.Passed{t.Fatal(result.Reason)}}else if result.Passed||!strings.Contains(result.Reason,c.reason){t.Fatalf("expected %s refusal, got %+v",c.reason,result)}})}
}
func TestTypedMethodReferenceCompleteness(t *testing.T){in:=proofFixture(t,header+"func call(){w:=worker{};_=w.add(1)}\n","");in.Request.References=[]Location{};if got:=run(in);got.Passed||!strings.Contains(got.Reason,"reference set"){t.Fatalf("missing ref accepted: %+v",got)};in=proofFixture(t,header+"func call(){w:=worker{};_=w.add(1)}\n","");in.Request.References=append(in.Request.References,in.Request.References[0]);if got:=run(in);got.Passed||!strings.Contains(got.Reason,"duplicate"){t.Fatalf("duplicate accepted: %+v",got)}}
func TestPrivateSnapshotAndCompilerInventoryMustMatch(t *testing.T){in:=proofFixture(t,header,"");if e:=os.WriteFile(in.Sources[0].Path,[]byte(header+"//changed"),0600);e!=nil{t.Fatal(e)};if got:=run(in);got.Passed||!strings.Contains(got.Reason,"snapshot changed"){t.Fatalf("changed source accepted: %+v",got)};in=proofFixture(t,header,"");var pkg Package;b,_:=os.ReadFile(in.Packages);json.Unmarshal(b,&pkg);pkg.GoFiles=append(pkg.GoFiles,"missing.go");b,_=json.Marshal(pkg);os.WriteFile(in.Packages,b,0600);if got:=run(in);got.Passed||!strings.Contains(got.Reason,"compiler sources differ"){t.Fatalf("missing source accepted: %+v",got)}}

func TestForeignCapturedPackageCannotHideLinkageCaller(t *testing.T){for _,text:=range []string{"package other\nimport _ \"unsafe\"\n","package other\n//go:linkname hidden example.test/proof.worker.add\nfunc hidden(){}\n"}{in:=proofFixture(t,header,"");dir:=filepath.Join(filepath.Dir(in.Sources[0].Path),"other");os.Mkdir(dir,0700);path:=filepath.Join(dir,"other.go");os.WriteFile(path,[]byte(text),0600);in.Sources=append(in.Sources,Source{URI:"file://"+filepath.ToSlash(path),Path:path,Text:text});if got:=run(in);got.Passed||!strings.Contains(got.Reason,"captured module"){t.Fatalf("hidden linkage accepted: %+v",got)}}}
