package dev.goforge.refactor;

/** Original Go standard-library-only parser. It never loads or executes a user package. */
final class GoSignatureSafetySource {
    private GoSignatureSafetySource() { }
    static final String TEXT = """
        package main
        import (
          "bytes"
          "encoding/json"
          "fmt"
          "go/ast"
          "go/parser"
          "go/printer"
          "go/token"
          "os"
          "strings"
          "unicode/utf16"
        )
        type Position struct { Line int `json:"line"`; Character int `json:"character"` }
        type Range struct { Start Position `json:"start"`; End Position `json:"end"` }
        type Location struct { URI string `json:"uri"`; Range Range `json:"range"` }
        type Source struct { URI string `json:"uri"`; Text string `json:"text"`; After *string `json:"after"` }
        type Input struct { Mode string `json:"mode"`; Sources []Source `json:"sources"`; Target Location `json:"target"`; References []Location `json:"references"`; OriginalSignature string `json:"originalSignature"`; RequestedSignature string `json:"requestedSignature"` }
        type Output struct { Passed bool `json:"passed"`; Reason string `json:"reason,omitempty"`; Target *Location `json:"target,omitempty"`; Checked int `json:"checked"` }
        type Parsed struct { Source; File *ast.File; Tokens *token.File; Set *token.FileSet }
        func offset(text string, p Position) (int,error) {
          if p.Line<0 || p.Character<0 { return 0,fmt.Errorf("invalid position") }
          line, column := 0,0
          for i,r := range text {
            if line==p.Line && column==p.Character { return i,nil }
            if r=='\\n' { line++;column=0 } else { column+=utf16.RuneLen(r) }
            if line>p.Line || line==p.Line && column>p.Character { return 0,fmt.Errorf("invalid UTF-16 position") }
          }
          if line==p.Line && column==p.Character { return len(text),nil };return 0,fmt.Errorf("position outside source")
        }
        func position(text string, end int) Position {
          p:=Position{};for i,r:=range text { if i>=end {break};if r=='\\n' {p.Line++;p.Character=0}else{p.Character+=utf16.RuneLen(r)} };return p
        }
        func parse(s Source) (*Parsed,error) {
          set:=token.NewFileSet();f,e:=parser.ParseFile(set,"source.go",s.Text,parser.SkipObjectResolution)
          if e!=nil{return nil,fmt.Errorf("a captured Go source cannot be parsed")};return &Parsed{s,f,set.File(f.Pos()),set},nil
        }
        func nodeText(p *Parsed,n ast.Node) string {var b bytes.Buffer;_ = printer.Fprint(&b,p.Set,n);return b.String()}
        func formatted(n ast.Node) string {if n==nil{return ""};var b bytes.Buffer;_ = printer.Fprint(&b,token.NewFileSet(),n);return b.String()}
        func fieldsKey(fields *ast.FieldList) string {
          if fields==nil{return ""};var parts []string
          for _,field:=range fields.List {var names []string;for _,id:=range field.Names {names=append(names,id.Name)};parts=append(parts,strings.Join(names,",")+":"+formatted(field.Type))};return strings.Join(parts,";")
        }
        func signatureAllowed(original,requested string) bool {
          old,e:=parser.ParseExpr(original);if e!=nil{return false};oldType,ok:=old.(*ast.FuncType);if !ok{return false}
          next,e:=parser.ParseExpr(requested);if e!=nil{return false};newType,ok:=next.(*ast.FuncType);if !ok{return false}
          if fieldsKey(oldType.Results)!=fieldsKey(newType.Results) || fieldsKey(oldType.TypeParams)!=fieldsKey(newType.TypeParams){return false}
          oldParams:=map[string]string{}
          for _,field:=range oldType.Params.List {if len(field.Names)==0{return false};for _,id:=range field.Names {if id.Name=="_" || oldParams[id.Name]!=""{return false};oldParams[id.Name]=formatted(field.Type)}}
          seen:=map[string]bool{}
          for _,field:=range newType.Params.List {if len(field.Names)==0{return false};for _,id:=range field.Names {if seen[id.Name] || oldParams[id.Name]!=formatted(field.Type){return false};seen[id.Name]=true}}
          return true
        }
        func plain(e ast.Expr) bool {
          switch v:=e.(type) {case *ast.Ident:return v.Name!="_";case *ast.BasicLit:return true;case *ast.ParenExpr:return plain(v.X)}
          return false
        }
        func callee(e ast.Expr) (*ast.Ident,bool) {
          switch v:=e.(type) {
          case *ast.Ident:return v,true
          case *ast.SelectorExpr:_,ok:=v.X.(*ast.Ident);return v.Sel,ok
          case *ast.IndexExpr:return callee(v.X)
          case *ast.IndexListExpr:return callee(v.X)
          case *ast.ParenExpr:return callee(v.X)
          }
          return nil,false
        }
        func run(in Input) Output {
          fail:=func(reason string)Output{return Output{Reason:reason}}
          if in.OriginalSignature!="" || in.RequestedSignature!="" {if !signatureAllowed(in.OriginalSignature,in.RequestedSignature){return fail("only a unique subset/reordering of existing named parameters with identical types and unchanged results is supported")}}
          files:=map[string]*Parsed{}
          for _,s:=range in.Sources {p,e:=parse(s);if e!=nil{return fail(e.Error())};if files[s.URI]!=nil{return fail("duplicate captured source")};files[s.URI]=p}
          target:=files[in.Target.URI];if target==nil{return fail("signature source is outside the snapshot")}
          at,e:=offset(target.Text,in.Target.Range.Start);if e!=nil{return fail(e.Error())}
          var decl *ast.FuncDecl
          for _,d:=range target.File.Decls {if f,ok:=d.(*ast.FuncDecl);ok {
            end:=target.Tokens.Offset(f.End());if f.Body!=nil{end=target.Tokens.Offset(f.Body.Lbrace)}
            if at>=target.Tokens.Offset(f.Pos()) && at<=end {decl=f;break}
          }}
          if decl==nil{return fail("place the caret within a function or method declaration signature")}
          for _,field:=range decl.Type.Params.List {if _,ok:=field.Type.(*ast.Ellipsis);ok{return fail("variadic signatures require separate argument evaluation analysis")}}
          nameStart:=target.Tokens.Offset(decl.Name.Pos());nameEnd:=target.Tokens.Offset(decl.Name.End())
          loc:=Location{target.URI,Range{position(target.Text,nameStart),position(target.Text,nameEnd)}}
          if in.Mode=="target" {return Output{Passed:true,Target:&loc}}
          if in.Mode!="verify" || in.References==nil{return fail("a complete reference response is required")}
          checked:=map[string]map[int]bool{}
          for _,ref:=range in.References {
            p:=files[ref.URI];if p==nil{return fail("a reference is outside the captured project")}
            start,e:=offset(p.Text,ref.Range.Start);if e!=nil{return fail(e.Error())};end,e:=offset(p.Text,ref.Range.End);if e!=nil{return fail(e.Error())}
            if ref.URI==loc.URI && start==nameStart && end==nameEnd {return fail("unexpected declaration in caller references")}
            var call *ast.CallExpr;var id *ast.Ident;safeReceiver:=false
            ast.Inspect(p.File,func(n ast.Node)bool{if c,ok:=n.(*ast.CallExpr);ok {candidate,safe:=callee(c.Fun);if candidate!=nil && p.Tokens.Offset(candidate.Pos())==start && p.Tokens.Offset(candidate.End())==end {call=c;id=candidate;safeReceiver=safe}};return true})
            if call==nil || id==nil || id.Name!=decl.Name.Name{return fail("a reference is not a direct call; function values and indirect dispatch require separate analysis")}
            if !safeReceiver{return fail("a method receiver or callee has a nontrivial evaluation")}
            if call.Ellipsis.IsValid(){return fail("variadic expansion is not supported by signature safety checking")}
            for _,arg:=range call.Args {if !plain(arg){return fail("signature change refused: call arguments must be identifiers or literals; calls, indexing, dereferences, channel receives and other expressions may change evaluation order")}}
            if checked[ref.URI]==nil{checked[ref.URI]=map[int]bool{}};checked[ref.URI][start]=true
          }
          // Fail closed if a same-name call is absent from references, even if
          // the edit also omitted that caller. Disambiguating shadowed methods
          // and inactive build variants requires a broader semantic proof.
          for _,p:=range files {
            missing:=false
            ast.Inspect(p.File,func(n ast.Node)bool{if c,ok:=n.(*ast.CallExpr);ok {id,_:=callee(c.Fun);if id!=nil && id.Name==decl.Name.Name && !checked[p.URI][p.Tokens.Offset(id.Pos())] {missing=true}};return true})
            if missing{return fail("a same-name call is absent from references; completeness cannot be established for shadowed methods or inactive build variants")}
            if p.After==nil {continue}
            after,e:=parse(Source{URI:p.URI,Text:*p.After});if e!=nil{return fail("the proposed Go source cannot be parsed")}
            for _,d:=range after.File.Decls {if f,ok:=d.(*ast.FuncDecl);ok {names:=map[string]bool{};for _,field:=range f.Type.Params.List {for _,id:=range field.Names {if id.Name!="_" && names[id.Name]{return fail("the proposed signature repeats a parameter")};names[id.Name]=true}}}}
          }
          count:=0;for _,refs:=range checked {count+=len(refs)};return Output{Passed:true,Target:&loc,Checked:count}
        }
        func main() {
          result:=Output{Reason:"invalid checker request"}
          if len(os.Args)==2 {data,e:=os.ReadFile(os.Args[1]);if e==nil && len(data)<=96*1024*1024 {var in Input;d:=json.NewDecoder(strings.NewReader(string(data)));d.DisallowUnknownFields();if d.Decode(&in)==nil{result=run(in)}}}
          _ = json.NewEncoder(os.Stdout).Encode(result)
        }
        """;
}
