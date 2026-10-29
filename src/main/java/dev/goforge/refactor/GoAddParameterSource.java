package dev.goforge.refactor;

/** Original parser and constant checker; never loads or executes the captured project. */
final class GoAddParameterSource {
    private GoAddParameterSource() {}
    static final String TEXT = """
        package main
        
        import (
         "encoding/json"
         "fmt"
         "go/ast"
         "go/parser"
         "go/token"
         "go/types"
         "net/url"
         "os"
         "path"
         "sort"
         "strings"
         "unicode/utf16"
        )
        
        type Position struct { Line int `json:"line"`; Character int `json:"character"` }
        type Range struct { Start Position `json:"start"`; End Position `json:"end"` }
        type Location struct { URI string `json:"uri"`; Range Range `json:"range"` }
        type Source struct { URI string `json:"uri"`; Text string `json:"text"` }
        type Spec struct { Name string `json:"name"`; Type string `json:"type"`; Index int `json:"index"`; DefaultValue string `json:"defaultValue"` }
        type Input struct { Mode string `json:"mode"`; Sources []Source `json:"sources"`; Target Location `json:"target"`; References []Location `json:"references"`; Spec Spec `json:"spec"`; CheckedImports bool `json:"checkedImports"` }
        type Edit struct { Range Range `json:"range"`; NewText string `json:"newText"` }
        type Output struct { Passed bool `json:"passed"`; Reason string `json:"reason,omitempty"`; Target *Location `json:"target,omitempty"`; Changes map[string][]Edit `json:"changes,omitempty"`; Checked int `json:"checked"`; NeedsCompilation bool `json:"needsCompilation,omitempty"`; Method bool `json:"method,omitempty"` }
        type Parsed struct { Source; File *ast.File; Tokens *token.File }
        
        func offset(text string,p Position)(int,error) {
         if p.Line<0||p.Character<0{return 0,fmt.Errorf("invalid position")};line,column:=0,0
         for i,r:=range text {if line==p.Line&&column==p.Character{return i,nil};if r=='\\n'{line++;column=0}else{column+=utf16.RuneLen(r)};if line>p.Line||line==p.Line&&column>p.Character{return 0,fmt.Errorf("invalid UTF-16 position")}}
         if line==p.Line&&column==p.Character{return len(text),nil};return 0,fmt.Errorf("position outside source")
        }
        func position(text string,end int)Position {p:=Position{};for i,r:=range text{if i>=end{break};if r=='\\n'{p.Line++;p.Character=0}else{p.Character+=utf16.RuneLen(r)}};return p}
        func parse(s Source)(*Parsed,error){fs:=token.NewFileSet();f,e:=parser.ParseFile(fs,"source.go",s.Text,parser.ParseComments|parser.SkipObjectResolution);if e!=nil{return nil,fmt.Errorf("a captured Go source cannot be parsed")};return &Parsed{s,f,fs.File(f.Pos())},nil}
        func parent(uri string)string{u,e:=url.Parse(uri);if e!=nil{return ""};return path.Dir(u.Path)}
        func direct(e ast.Expr,typeParameters int)(*ast.Ident,bool){
         instantiated,parenthesized:=false,false
         // Only parentheses and one function instantiation may surround the
         // referenced callee. Never treat an index/type argument as that callee.
         for depth:=0;depth<64;depth++{switch n:=e.(type){
          case *ast.Ident:if typeParameters>0&&parenthesized&&!instantiated{return nil,false};return n,true
          case *ast.SelectorExpr:if typeParameters>0&&parenthesized&&!instantiated{return nil,false};_,ok:=n.X.(*ast.Ident);return n.Sel,ok
          // A generic value inside parentheses must already be completely
          // instantiated; parentheses around its uninstantiated base are invalid.
          case *ast.ParenExpr:if instantiated{return nil,false};parenthesized=true;e=n.X
          case *ast.IndexExpr:if typeParameters==0||instantiated||parenthesized&&typeParameters!=1{return nil,false};instantiated=true;e=n.X
          case *ast.IndexListExpr:if instantiated||len(n.Indices)==0||len(n.Indices)>typeParameters||parenthesized&&len(n.Indices)!=typeParameters{return nil,false};instantiated=true;e=n.X
          default:return nil,false
         }};return nil,false
        }
        func literal(e ast.Expr)bool{switch n:=e.(type){case *ast.BasicLit:return true;case *ast.ParenExpr:return literal(n.X);case *ast.UnaryExpr:return (n.Op==token.ADD||n.Op==token.SUB)&&literal(n.X)};return false}
        func defaultValue(s Spec)(string,error){
         allowed:=map[string]bool{};for _,name:=range strings.Fields("bool string int int8 int16 int32 int64 uint uint8 uint16 uint32 uint64 uintptr byte rune float32 float64 complex64 complex128"){allowed[name]=true}
         if !allowed[s.Type]{return "",fmt.Errorf("new parameter type must be a predeclared scalar type")}
         text:=strings.TrimSpace(s.DefaultValue);if text==""||len(text)>4096{return "",fmt.Errorf("enter a bounded literal default value")}
         // These expressions remain boolean constants even in caller scopes which shadow true/false.
         canonical:=text
         if text=="true"{canonical="(0 == 0)"}else if text=="false"{canonical="(0 != 0)"}else{e,err:=parser.ParseExpr(text);if err!=nil||!literal(e){return "",fmt.Errorf("default value must be a literal, a signed numeric literal, true or false; identifiers and evaluated expressions are refused")}}
         fs:=token.NewFileSet();f,e:=parser.ParseFile(fs,"constant.go","package p\\nvar _ "+s.Type+" = "+canonical+"\\n",0)
         if e!=nil{return "",fmt.Errorf("invalid default value")}
         cfg:=types.Config{Sizes:types.SizesFor("gc","386")};if _,e=cfg.Check("constant",fs,[]*ast.File{f},nil);e!=nil{return "",fmt.Errorf("default value is not representable by the parameter type (int/uint use the portable 32-bit range)")}
         return canonical,nil
        }
        func run(in Input)Output {
         fail:=func(s string)Output{return Output{Reason:s}}
         s:=in.Spec
         if !token.IsIdentifier(s.Name)||s.Name=="_"||len(s.Name)>256{return fail("enter one new non-blank Go parameter identifier")}
         value,e:=defaultValue(s);if e!=nil{return fail(e.Error())}
         if len(in.Sources)==0||len(in.Sources)>2048{return fail("invalid source snapshot size")}
         files:=map[string]*Parsed{};size:=0
         for _,src:=range in.Sources{size+=len(src.Text);if size>96*1024*1024{return fail("source snapshot exceeds limit")};if files[src.URI]!=nil{return fail("duplicate captured source")};p,e:=parse(src);if e!=nil{return fail(e.Error())};files[src.URI]=p}
         target:=files[in.Target.URI];if target==nil{return fail("declaration is outside the captured project")}
         at,e:=offset(target.Text,in.Target.Range.Start);if e!=nil{return fail(e.Error())}
         var decl *ast.FuncDecl
         for _,d:=range target.File.Decls{if f,ok:=d.(*ast.FuncDecl);ok{end:=target.Tokens.Offset(f.Type.End());if at>=target.Tokens.Offset(f.Pos())&&at<=end{decl=f;break}}}
         if decl==nil{return fail("select the func keyword of a function declaration")}
         if decl.Body==nil{return fail("adding parameters requires a function or method body; external declarations are refused")}
         method:=decl.Recv!=nil
         if method{
          if len(decl.Recv.List)!=1||len(decl.Recv.List[0].Names)!=1||decl.Recv.List[0].Names[0].Name=="_"{return fail("method parameters require a private method and one named concrete receiver")}
          recv:=decl.Recv.List[0].Type;if star,ok:=recv.(*ast.StarExpr);ok{recv=star.X};name,ok:=recv.(*ast.Ident)
          if !ok||name.IsExported(){return fail("methods and external declarations require separate proof; only private non-generic struct receivers are supported")}
          if decl.Name.IsExported(){return fail("method parameters require a private method")}
          var receiver *ast.TypeSpec
          for _,p:=range files{if parent(p.URI)!=parent(target.URI)||p.File.Name.Name!=target.File.Name.Name{continue};for _,d:=range p.File.Decls{if gen,ok:=d.(*ast.GenDecl);ok{for _,sp:=range gen.Specs{if typ,ok:=sp.(*ast.TypeSpec);ok&&typ.Name.Name==name.Name{if receiver!=nil{return fail("ambiguous receiver declaration")};receiver=typ}}}}}
          if receiver==nil||receiver.Assign.IsValid()||receiver.TypeParams!=nil{return fail("method receiver must be a private non-alias, non-generic local struct")};if _,ok:=receiver.Type.(*ast.StructType);!ok{return fail("method receiver must be a concrete local struct")}
          if !in.CheckedImports{return Output{Reason:"Methods require a frozen typed-interface and before/after compile check.",NeedsCompilation:true}}
         }
         typeParameters:=0
         if decl.Type.TypeParams!=nil{for _,field:=range decl.Type.TypeParams.List{typeParameters+=len(field.Names);for _,name:=range field.Names{if name.Name==s.Type{return fail("new scalar type is shadowed by a function type parameter")}}}}
         if decl.Name.Name=="init"||decl.Name.Name=="main"{return fail("runtime entry-point signatures cannot be changed")}
         if strings.HasSuffix(in.Target.URI,"_test.go"){for _,prefix:=range []string{"Test","Benchmark","Fuzz","Example"}{if strings.HasPrefix(decl.Name.Name,prefix){return fail("test entry-point signatures cannot be changed")}}}
         if decl.Doc!=nil{for _,c:=range decl.Doc.List{if strings.Contains(c.Text,"go:")||strings.HasPrefix(c.Text,"//export "){return fail("functions with compiler/linkage directives cannot be changed")}}}
         names:=map[string]bool{};for _,f:=range decl.Type.Params.List{if len(f.Names)!=1||f.Names[0].Name=="_"{return fail("existing parameters must each have one unique non-blank name; grouped or unnamed parameters are refused")};if _,ok:=f.Type.(*ast.Ellipsis);ok{return fail("variadic signatures are not supported")};if names[f.Names[0].Name]{return fail("existing parameter names must be unique")};names[f.Names[0].Name]=true}
         count:=len(decl.Type.Params.List);if s.Index<0||s.Index>count{return fail("insert position must be between zero and the existing parameter count")}
         collision:=s.Name==decl.Name.Name
         ast.Inspect(decl,func(n ast.Node)bool{if id,ok:=n.(*ast.Ident);ok&&id.Name==s.Name{collision=true};return true})
         if collision{return fail("new parameter name already appears in the function; choose a name that cannot shadow existing code")}
         // Only the Java before/after compile-proof path may accept unknown default import names.
         needsCompilation:=false
         for _,imp:=range target.File.Imports{if imp.Name==nil{needsCompilation=true;continue};if imp.Name.Name=="."||imp.Name.Name==s.Type{return fail("dot imports and import aliases shadowing the new scalar type are refused")}}
         for _,p:=range files{if parent(p.URI)!=parent(target.URI)||p.File.Name.Name!=target.File.Name.Name{continue};for _,d:=range p.File.Decls{switch d:=d.(type){case *ast.FuncDecl:if d.Recv==nil&&d.Name.Name==s.Type{return fail("new scalar type is shadowed in the declaration package")};case *ast.GenDecl:for _,sp:=range d.Specs{switch v:=sp.(type){case *ast.TypeSpec:if v.Name.Name==s.Type{return fail("new scalar type is shadowed in the declaration package")};case *ast.ValueSpec:for _,n:=range v.Names{if n.Name==s.Type{return fail("new scalar type is shadowed in the declaration package")}}}}}}}
         for _,f:=range decl.Type.Params.List{if f.Names[0].Name==s.Type{return fail("new scalar type is shadowed by an existing parameter")}}
         if decl.Type.Results!=nil{for _,f:=range decl.Type.Results.List{for _,n:=range f.Names{if n.Name==s.Type{return fail("new scalar type is shadowed by a named result")}}}}
         if needsCompilation&&!in.CheckedImports{return Output{Reason:"Ordinary declaration imports require a frozen before/after compile check.",NeedsCompilation:true}}
         nameStart,nameEnd:=target.Tokens.Offset(decl.Name.Pos()),target.Tokens.Offset(decl.Name.End())
         loc:=Location{target.URI,Range{position(target.Text,nameStart),position(target.Text,nameEnd)}}
         if in.Mode=="target"{return Output{Passed:true,Target:&loc,Method:method}}
         if in.Mode!="plan"||in.References==nil{return fail("a complete reference response is required")}
         if len(in.References)>10000{return fail("reference limit exceeded")}
         changes:=map[string][]Edit{};seen:=map[string]map[int]bool{}
         insert:=func(p *Parsed,at int,text string){pos:=position(p.Text,at);changes[p.URI]=append(changes[p.URI],Edit{Range{pos,pos},text})}
         for _,ref:=range in.References{
          p:=files[ref.URI];if p==nil{return fail("a reference is outside the captured project")};start,e:=offset(p.Text,ref.Range.Start);if e!=nil{return fail(e.Error())};end,e:=offset(p.Text,ref.Range.End);if e!=nil{return fail(e.Error())}
          var call *ast.CallExpr
          ast.Inspect(p.File,func(n ast.Node)bool{if c,ok:=n.(*ast.CallExpr);ok{id,safe:=direct(c.Fun,typeParameters);if safe&&id.Name==decl.Name.Name&&p.Tokens.Offset(id.Pos())==start&&p.Tokens.Offset(id.End())==end{call=c}};return true})
          if call==nil{return fail("a reference is not a verified direct call; function values, nested or invalid instantiation and indirect uses are refused")}
          if call.Ellipsis.IsValid()||len(call.Args)!=count{return fail("variadic expansion or tuple-valued calls cannot be transformed safely")}
          if seen[p.URI]==nil{seen[p.URI]=map[int]bool{}};if seen[p.URI][start]{return fail("duplicate caller reference")};seen[p.URI][start]=true
          switch{case count==0:insert(p,p.Tokens.Offset(call.Rparen),value);case s.Index<count:insert(p,p.Tokens.Offset(call.Args[s.Index].Pos()),value+", ");default:insert(p,p.Tokens.Offset(call.Args[count-1].End()),", "+value)}
         }
         // Every same-name identifier must be the selected declaration or a verified call.
         // This is intentionally conservative for unrelated same-name symbols/build variants.
         for _,p:=range files{missing:=false;ast.Inspect(p.File,func(n ast.Node)bool{if id,ok:=n.(*ast.Ident);ok&&id.Name==decl.Name.Name{start:=p.Tokens.Offset(id.Pos());if !(p.URI==target.URI&&start==nameStart)&&!seen[p.URI][start]{missing=true}};return true});if missing{return fail("a same-name identifier is absent from the direct-call reference set; completeness cannot be established")}}
         addition:=s.Name+" "+s.Type
         switch{case count==0:insert(target,target.Tokens.Offset(decl.Type.Params.Closing),addition);case s.Index<count:insert(target,target.Tokens.Offset(decl.Type.Params.List[s.Index].Pos()),addition+", ");default:insert(target,target.Tokens.Offset(decl.Type.Params.List[count-1].End()),", "+addition)}
         // Ensure insertion grammar remains valid without reformatting or reordering old expressions.
         for uri,edits:=range changes{p:=files[uri];sort.Slice(edits,func(i,j int)bool{a,_:=offset(p.Text,edits[i].Range.Start);b,_:=offset(p.Text,edits[j].Range.Start);return a<b});text:=p.Text;previous:=-1
          for i:=len(edits)-1;i>=0;i--{at,_:=offset(p.Text,edits[i].Range.Start);if at==previous{return fail("overlapping insertion points")};previous=at;text=text[:at]+edits[i].NewText+text[at:]}
          if _,e:=parse(Source{URI:uri,Text:text});e!=nil{return fail("the proposed source could not be parsed")};changes[uri]=edits
         }
         return Output{Passed:true,Target:&loc,Changes:changes,Checked:len(in.References)}
        }
        func main(){result:=Output{Reason:"invalid add-parameter request"};if len(os.Args)==2{data,e:=os.ReadFile(os.Args[1]);if e==nil&&len(data)<=128*1024*1024{var in Input;d:=json.NewDecoder(strings.NewReader(string(data)));d.DisallowUnknownFields();if d.Decode(&in)==nil{result=run(in)}}};_ = json.NewEncoder(os.Stdout).Encode(result)}
        """;
}
