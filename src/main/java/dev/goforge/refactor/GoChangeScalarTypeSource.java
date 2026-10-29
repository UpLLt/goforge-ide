package dev.goforge.refactor;

/** Original AST planner; compiler validation is performed on an owned snapshot by the Java worker. */
final class GoChangeScalarTypeSource {
    private GoChangeScalarTypeSource() {}
    static final String TEXT = """
        package main
        
        import (
         "encoding/json"
         "fmt"
         "go/ast"
         "go/parser"
         "go/token"
         "net/url"
         "os"
         "path"
         "strings"
         "unicode/utf16"
        )
        
        type Position struct { Line int `json:"line"`; Character int `json:"character"` }
        type Range struct { Start Position `json:"start"`; End Position `json:"end"` }
        type Location struct { URI string `json:"uri"`; Range Range `json:"range"` }
        type Source struct { URI string `json:"uri"`; Text string `json:"text"` }
        type Spec struct { Result bool `json:"result"`; Index int `json:"index"`; Type string `json:"type"` }
        type Input struct { Mode string `json:"mode"`; Sources []Source `json:"sources"`; Target Location `json:"target"`; References []Location `json:"references"`; Spec Spec `json:"spec"` }
        type Edit struct { Range Range `json:"range"`; NewText string `json:"newText"` }
        type Output struct { Passed bool `json:"passed"`; Reason string `json:"reason,omitempty"`; Target *Location `json:"target,omitempty"`; Changes map[string][]Edit `json:"changes,omitempty"`; Checked int `json:"checked"`; Method bool `json:"method"` }
        type Parsed struct { Source; File *ast.File; Tokens *token.File }
        
        func offset(text string,p Position)(int,error) {
         if p.Line<0||p.Character<0{return 0,fmt.Errorf("invalid position")};line,column:=0,0
         for i,r:=range text {if line==p.Line&&column==p.Character{return i,nil};if r=='\\n'{line++;column=0}else{column+=utf16.RuneLen(r)};if line>p.Line||line==p.Line&&column>p.Character{return 0,fmt.Errorf("invalid UTF-16 position")}}
         if line==p.Line&&column==p.Character{return len(text),nil};return 0,fmt.Errorf("position outside source")
        }
        func position(text string,end int)Position {p:=Position{};for i,r:=range text{if i>=end{break};if r=='\\n'{p.Line++;p.Character=0}else{p.Character+=utf16.RuneLen(r)}};return p}
        func parse(s Source)(*Parsed,error){fs:=token.NewFileSet();f,e:=parser.ParseFile(fs,"source.go",s.Text,parser.ParseComments|parser.SkipObjectResolution);if e!=nil{return nil,fmt.Errorf("a captured Go source cannot be parsed")};return &Parsed{s,f,fs.File(f.Pos())},nil}
        func parent(uri string)string{u,e:=url.Parse(uri);if e!=nil{return ""};return path.Dir(u.Path)}
        func scalar(name string)bool{for _,n:=range strings.Fields("bool string int int8 int16 int32 int64 uint uint8 uint16 uint32 uint64 uintptr byte rune float32 float64 complex64 complex128"){if n==name{return true}};return false}
        func callee(e ast.Expr)(*ast.Ident,bool){indexed:=false;for depth:=0;depth<64;depth++{switch n:=e.(type){case *ast.Ident:return n,true;case *ast.SelectorExpr:_,ok:=n.X.(*ast.Ident);return n.Sel,ok;case *ast.ParenExpr:e=n.X;case *ast.IndexExpr:if indexed{return nil,false};indexed=true;e=n.X;case *ast.IndexListExpr:if indexed{return nil,false};indexed=true;e=n.X;default:return nil,false}};return nil,false}
        func run(in Input)Output {
         fail:=func(s string)Output{return Output{Reason:s}}
         if !scalar(in.Spec.Type){return fail("new type must be a predeclared scalar type")}
         if len(in.Sources)==0||len(in.Sources)>2048{return fail("invalid source snapshot size")}
         files:=map[string]*Parsed{};size:=0
         for _,src:=range in.Sources{size+=len(src.Text);if size>96*1024*1024{return fail("source snapshot exceeds limit")};if files[src.URI]!=nil{return fail("duplicate captured source")};p,e:=parse(src);if e!=nil{return fail(e.Error())};files[src.URI]=p
          for _,group:=range p.File.Comments{for _,c:=range group.List{if strings.Contains(c.Text,"go:linkname")||strings.Contains(c.Text,"go:wasmexport")||strings.Contains(c.Text,"go:wasmimport")||strings.HasPrefix(c.Text,"//export "){return fail("compiler/linkage directives are outside the captured caller graph")};if strings.HasPrefix(c.Text,"//go:embed")||strings.HasPrefix(c.Text,"//go:build")||strings.HasPrefix(c.Text,"// +build"){return fail("embedded resources and conditional build sources are not supported by this type-change snapshot")}}}
         }
         target:=files[in.Target.URI];if target==nil{return fail("declaration is outside the captured project")}
         at,e:=offset(target.Text,in.Target.Range.Start);if e!=nil{return fail(e.Error())}
         var decl *ast.FuncDecl
         for _,d:=range target.File.Decls{if f,ok:=d.(*ast.FuncDecl);ok&&at>=target.Tokens.Offset(f.Pos())&&at<=target.Tokens.Offset(f.Type.End()){decl=f;break}}
         if decl==nil||decl.Body==nil{return fail("select a free-function or supported private-method declaration with a body")}
         method:=decl.Recv!=nil
         if method{
          if len(decl.Recv.List)!=1||len(decl.Recv.List[0].Names)!=1||decl.Recv.List[0].Names[0].Name=="_"{return fail("method type changes require a private method and one named concrete receiver")}
          recv:=decl.Recv.List[0].Type;if star,ok:=recv.(*ast.StarExpr);ok{recv=star.X};name,ok:=recv.(*ast.Ident)
          if !ok||name.IsExported(){return fail("only private non-generic struct receivers support scalar type changes")}
          if decl.Name.IsExported(){return fail("scalar type changes require a private method")}
          var receiver *ast.TypeSpec
          for _,p:=range files{if parent(p.URI)!=parent(target.URI)||p.File.Name.Name!=target.File.Name.Name{continue};for _,d:=range p.File.Decls{if gen,ok:=d.(*ast.GenDecl);ok{for _,sp:=range gen.Specs{if typ,ok:=sp.(*ast.TypeSpec);ok&&typ.Name.Name==name.Name{if receiver!=nil{return fail("ambiguous receiver declaration")};receiver=typ}}}}}
          if receiver==nil||receiver.Assign.IsValid()||receiver.TypeParams!=nil{return fail("method receiver must be a private non-alias, non-generic local struct")};if _,ok:=receiver.Type.(*ast.StructType);!ok{return fail("method receiver must be a concrete local struct")}
         }
         if decl.Name.Name=="init"||decl.Name.Name=="main"{return fail("runtime entry-point signatures cannot be changed")}
         if strings.HasSuffix(in.Target.URI,"_test.go"){for _,prefix:=range []string{"Test","Benchmark","Fuzz","Example"}{if strings.HasPrefix(decl.Name.Name,prefix){return fail("test entry-point signatures cannot be changed")}}}
         if decl.Doc!=nil{for _,c:=range decl.Doc.List{if strings.Contains(c.Text,"go:")||strings.HasPrefix(c.Text,"//export "){return fail("functions with compiler/linkage directives cannot be changed")}}}
         for _,field:=range decl.Type.Params.List{if _,ok:=field.Type.(*ast.Ellipsis);ok{return fail("variadic signatures are not supported")}}
         var field *ast.Field
         if in.Spec.Result{
          if in.Spec.Index!=0||decl.Type.Results==nil||len(decl.Type.Results.List)!=1||len(decl.Type.Results.List[0].Names)>1{return fail("select exactly one existing result type; multiple results are not supported")};field=decl.Type.Results.List[0]
         }else{
          index:=0
          for _,f:=range decl.Type.Params.List{n:=len(f.Names);if n==0{n=1};if in.Spec.Index>=index&&in.Spec.Index<index+n{if n!=1{return fail("split grouped parameters before changing one type")};field=f;break};index+=n}
          if field==nil||in.Spec.Index<0{return fail("parameter index is outside the existing signature")}
         }
         old,ok:=field.Type.(*ast.Ident);if !ok||!scalar(old.Name){return fail("existing type must be a predeclared scalar identifier")}
         if old.Name==in.Spec.Type{return fail("choose a different scalar type")}
         // Resolve scalar spellings conservatively: neither may be a package/type-parameter/import binding.
         shadow:=func(name string)bool{return name==old.Name||name==in.Spec.Type}
         if decl.Type.TypeParams!=nil{for _,f:=range decl.Type.TypeParams.List{for _,n:=range f.Names{if shadow(n.Name){return fail("a scalar type is shadowed by a function type parameter")}}}}
         for _,imp:=range target.File.Imports{if imp.Name!=nil&&(imp.Name.Name=="."||shadow(imp.Name.Name)){return fail("dot imports and import aliases that shadow either scalar type are refused")}}
         for _,p:=range files{if parent(p.URI)!=parent(target.URI)||p.File.Name.Name!=target.File.Name.Name{continue};for _,d:=range p.File.Decls{switch d:=d.(type){case *ast.FuncDecl:if d.Recv==nil&&shadow(d.Name.Name){return fail("a scalar type is shadowed in the declaration package")};case *ast.GenDecl:for _,sp:=range d.Specs{switch s:=sp.(type){case *ast.TypeSpec:if shadow(s.Name.Name){return fail("a scalar type is shadowed in the declaration package")};case *ast.ValueSpec:for _,n:=range s.Names{if shadow(n.Name){return fail("a scalar type is shadowed in the declaration package")}}}}}}}
         start,end:=target.Tokens.Offset(decl.Name.Pos()),target.Tokens.Offset(decl.Name.End());location:=Location{target.URI,Range{position(target.Text,start),position(target.Text,end)}}
         if in.Mode=="target"{return Output{Passed:true,Target:&location,Method:method}}
         if in.Mode!="plan"||in.References==nil||len(in.References)>10000{return fail("a complete bounded reference response is required")}
         seen:=map[string]map[int]bool{}
         for _,ref:=range in.References{
          p:=files[ref.URI];if p==nil{return fail("a reference is outside the captured project")};a,e:=offset(p.Text,ref.Range.Start);if e!=nil{return fail(e.Error())};b,e:=offset(p.Text,ref.Range.End);if e!=nil{return fail(e.Error())}
          matched:=false;ast.Inspect(p.File,func(n ast.Node)bool{if c,ok:=n.(*ast.CallExpr);ok{id,direct:=callee(c.Fun);if direct&&id.Name==decl.Name.Name&&p.Tokens.Offset(id.Pos())==a&&p.Tokens.Offset(id.End())==b{matched=true}};return true})
          if !matched{return fail("a reference is not a verified direct call; function values and indirect uses are refused")}
          if seen[p.URI]==nil{seen[p.URI]=map[int]bool{}};if seen[p.URI][a]{return fail("duplicate caller reference")};seen[p.URI][a]=true
         }
         for _,p:=range files{missing:=false;ast.Inspect(p.File,func(n ast.Node)bool{if id,ok:=n.(*ast.Ident);ok&&id.Name==decl.Name.Name{at:=p.Tokens.Offset(id.Pos());if !(p.URI==target.URI&&at==start)&&!seen[p.URI][at]{missing=true}};return true});if missing{return fail("a same-name identifier is absent from the direct-call reference set; completeness cannot be established")}}
         a,b:=target.Tokens.Offset(old.Pos()),target.Tokens.Offset(old.End())
         change:=Edit{Range{position(target.Text,a),position(target.Text,b)},in.Spec.Type}
         return Output{Passed:true,Target:&location,Changes:map[string][]Edit{target.URI:{change}},Checked:len(in.References),Method:method}
        }
        func main(){result:=Output{Reason:"invalid scalar type-change request"};if len(os.Args)==2{data,e:=os.ReadFile(os.Args[1]);if e==nil&&len(data)<=128*1024*1024{var in Input;d:=json.NewDecoder(strings.NewReader(string(data)));d.DisallowUnknownFields();if d.Decode(&in)==nil{result=run(in)}}};_ = json.NewEncoder(os.Stdout).Encode(result)}
        """;
}
