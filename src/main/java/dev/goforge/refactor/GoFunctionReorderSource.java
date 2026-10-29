package dev.goforge.refactor;

/** Original bounded static-function reorder helper; never executes project code. */
final class GoFunctionReorderSource {
    private GoFunctionReorderSource() {}
    static final String TEXT = """
        package main

        import (
         "bytes"
         "encoding/json"
         "fmt"
         "go/ast"
         "go/format"
         "go/parser"
         "go/token"
         "os"
         "sort"
         "strings"
         "unicode/utf16"
        )

        type Position struct { Line int `json:"line"`; Character int `json:"character"` }
        type Range struct { Start Position `json:"start"`; End Position `json:"end"` }
        type Location struct { URI string `json:"uri"`; Range Range `json:"range"` }
        type Source struct { URI string `json:"uri"`; Text string `json:"text"` }
        type Input struct { Mode string `json:"mode"`; Sources []Source `json:"sources"`; Target Location `json:"target"`; References []Location `json:"references"`; Signature string `json:"signature"` }
        type Edit struct { Range Range `json:"range"`; NewText string `json:"newText"` }
        type Output struct { Passed bool `json:"passed"`; Reason string `json:"reason,omitempty"`; Target *Location `json:"target,omitempty"`; Changes map[string][]Edit `json:"changes,omitempty"`; Checked int `json:"checked"` }
        type Parsed struct { Source; File *ast.File; Tokens *token.File; Parents map[ast.Node]ast.Node }
        func offset(text string,p Position)(int,error){if p.Line<0||p.Character<0{return 0,fmt.Errorf("invalid position")};line,column:=0,0;for i,r:=range text{if line==p.Line&&column==p.Character{return i,nil};if r=='\\n'{line++;column=0}else{column+=utf16.RuneLen(r)};if line>p.Line||line==p.Line&&column>p.Character{return 0,fmt.Errorf("invalid UTF-16 position")}};if line==p.Line&&column==p.Character{return len(text),nil};return 0,fmt.Errorf("position outside source")}
        func position(text string,end int)Position{p:=Position{};for i,r:=range text{if i>=end{break};if r=='\\n'{p.Line++;p.Character=0}else{p.Character+=utf16.RuneLen(r)}};return p}
        func scalar(name string)bool{for _,n:=range strings.Fields("bool string int int8 int16 int32 int64 uint uint8 uint16 uint32 uint64 uintptr byte rune float32 float64 complex64 complex128"){if name==n{return true}};return false}
        func formatted(n ast.Node)string{if n==nil{return ""};var b bytes.Buffer;if format.Node(&b,token.NewFileSet(),n)!=nil{return "<invalid>"};return b.String()}
        func params(f *ast.FuncType)([]string,[]string,error){if f.TypeParams!=nil||f.Params==nil||len(f.Params.List)<2||len(f.Params.List)>64{return nil,nil,fmt.Errorf("choose 2–64 individually named scalar parameters of a non-generic function")};names:=[]string{};types:=[]string{};seen:=map[string]bool{};for _,p:=range f.Params.List{if len(p.Names)!=1||p.Names[0].Name=="_"||seen[p.Names[0].Name]{return nil,nil,fmt.Errorf("split grouped parameters and keep unique parameter names")};id,ok:=p.Type.(*ast.Ident);if !ok||!scalar(id.Name){return nil,nil,fmt.Errorf("only predeclared scalar parameters are supported; variadic parameters are refused")};names=append(names,p.Names[0].Name);types=append(types,id.Name);seen[p.Names[0].Name]=true};return names,types,nil}
        func comments(p *Parsed,a,b token.Pos)bool{for _,g:=range p.File.Comments{if g.Pos()<b&&g.End()>a{return true}};return false}
        func run(in Input)Output{
         fail:=func(s string)Output{return Output{Reason:s}}
         if len(in.Sources)==0||len(in.Sources)>2048||len(in.Signature)>16384{return fail("invalid bounded function reorder input")}
         files:=map[string]*Parsed{};idents:=map[string]bool{};total:=0
         for _,src:=range in.Sources{total+=len(src.Text);if total>32*1024*1024||files[src.URI]!=nil{return fail("invalid source snapshot size or duplicate URI")};fs:=token.NewFileSet();f,e:=parser.ParseFile(fs,"source.go",src.Text,parser.ParseComments|parser.SkipObjectResolution);if e!=nil{return fail("captured source cannot be parsed")};p:=&Parsed{src,f,fs.File(f.Pos()),map[ast.Node]ast.Node{}};stack:=[]ast.Node{};ast.Inspect(f,func(n ast.Node)bool{if n==nil{stack=stack[:len(stack)-1];return true};if len(stack)>0{p.Parents[n]=stack[len(stack)-1]};stack=append(stack,n);if id,ok:=n.(*ast.Ident);ok{idents[id.Name]=true};return true});files[src.URI]=p}
         p:=files[in.Target.URI];if p==nil{return fail("function is outside the captured source")};at,e:=offset(p.Text,in.Target.Range.Start);if e!=nil{return fail(e.Error())};var target *ast.FuncDecl
         for _,d:=range p.File.Decls{if f,ok:=d.(*ast.FuncDecl);ok&&at>=p.Tokens.Offset(f.Pos())&&at<=p.Tokens.Offset(f.Type.End()){target=f;break}}
         if target==nil||target.Recv!=nil||target.Body==nil||target.Name.IsExported()||target.Name.Name=="init"||target.Name.Name=="main"{return fail("select a private top-level function with a body; methods and entry points are not supported")}
         oldNames,oldTypes,e:=params(target.Type);if e!=nil{return fail(e.Error())};expr,e:=parser.ParseExpr(in.Signature);if e!=nil{return fail("requested signature is not a function type")};requested,ok:=expr.(*ast.FuncType);if !ok{return fail("requested signature is not a function type")};newNames,newTypes,e:=params(requested);if e!=nil{return fail(e.Error())}
         if len(oldNames)!=len(newNames)||formatted(&ast.FuncType{Params:&ast.FieldList{},Results:target.Type.Results})!=formatted(&ast.FuncType{Params:&ast.FieldList{},Results:requested.Results}){return fail("keep every parameter and the complete result signature")}
         order:=make([]int,len(oldNames));different:=false
         for i,name:=range newNames{order[i]=-1;for j,old:=range oldNames{if name==old&&newTypes[i]==oldTypes[j]{order[i]=j}};if order[i]<0{return fail("only a permutation of existing parameter names and scalar types is supported")};different=different||order[i]!=i}
         if !different{return fail("choose a different parameter order")};if comments(p,target.Type.Params.Opening,target.Type.Params.Closing){return fail("move parameter-list comments before this bounded reorder")}
         a,b:=p.Tokens.Offset(target.Name.Pos()),p.Tokens.Offset(target.Name.End());loc:=Location{p.URI,Range{position(p.Text,a),position(p.Text,b)}}
         if in.Mode=="target"{return Output{Passed:true,Target:&loc}};if in.Mode!="plan"||in.References==nil||len(in.References)>10000{return fail("a complete bounded reference set is required")}
         refs:=append([]Location{},in.References...);sort.Slice(refs,func(i,j int)bool{if refs[i].URI!=refs[j].URI{return refs[i].URI<refs[j].URI};if refs[i].Range.Start.Line!=refs[j].Range.Start.Line{return refs[i].Range.Start.Line<refs[j].Range.Start.Line};return refs[i].Range.Start.Character<refs[j].Range.Start.Character})
         changes:=map[string][]Edit{};parts:=[]string{};for i,name:=range newNames{parts=append(parts,name+" "+newTypes[i])};changes[p.URI]=[]Edit{{Range{position(p.Text,p.Tokens.Offset(target.Type.Params.Opening)+1),position(p.Text,p.Tokens.Offset(target.Type.Params.Closing))},strings.Join(parts,", ")}}
         seen:=map[string]bool{};serial:=0
         for _,ref:=range refs{p:=files[ref.URI];if p==nil{return fail("reference is outside the captured source")};a,e:=offset(p.Text,ref.Range.Start);if e!=nil{return fail(e.Error())};b,e:=offset(p.Text,ref.Range.End);if e!=nil{return fail(e.Error())};key:=fmt.Sprintf("%s:%d:%d",p.URI,a,b);if seen[key]{return fail("duplicate function reference")};seen[key]=true
          var id *ast.Ident;ast.Inspect(p.File,func(n ast.Node)bool{if x,ok:=n.(*ast.Ident);ok&&p.Tokens.Offset(x.Pos())==a&&p.Tokens.Offset(x.End())==b{id=x};return true});if id==nil||id.Name!=target.Name.Name{return fail("reference does not name the selected function")}
          call,ok:=p.Parents[id].(*ast.CallExpr);if !ok||call.Fun!=id||call.Ellipsis.IsValid()||len(call.Args)!=len(oldNames){return fail("every reference must be a direct non-variadic call with one expression per parameter")};statement,ok:=p.Parents[call].(*ast.ExprStmt);if !ok{return fail("only standalone expression-statement calls preserve the supported evaluation order")};if _,ok:=p.Parents[statement].(*ast.BlockStmt);!ok{return fail("call must be directly inside a block; labels and clause lists are unsupported")}
          var enclosing ast.Node;for n:=ast.Node(statement);n!=nil;n=p.Parents[n]{switch n.(type){case *ast.FuncDecl,*ast.FuncLit:enclosing=n};if enclosing!=nil{break}};if enclosing==nil{return fail("call has no enclosing function")};label:=false;ast.Inspect(enclosing,func(n ast.Node)bool{if _,ok:=n.(*ast.LabeledStmt);ok{label=true};return true});if label{return fail("functions containing labels are outside this reorder")}
          if comments(p,call.Pos(),call.End()){return fail("move call comments before this bounded reorder")}
          start,end:=p.Tokens.Offset(call.Pos()),p.Tokens.Offset(call.End());line:=strings.LastIndex(p.Text[:start],"\\n")+1;indent:="";for _,r:=range p.Text[line:start]{if r!=' '&&r!='\\t'{break};indent+=string(r)};newline:="\\n";if strings.Contains(p.Text,"\\r\\n"){newline="\\r\\n"}
          temps:=make([]string,len(oldNames));var out strings.Builder;out.WriteString("{"+newline)
          for i,arg:=range call.Args{for{temps[i]=fmt.Sprintf("_goforgeArg%d",serial);serial++;if !idents[temps[i]]{idents[temps[i]]=true;break}};out.WriteString(indent+"\\tvar "+temps[i]+" "+oldTypes[i]+" = "+p.Text[p.Tokens.Offset(arg.Pos()):p.Tokens.Offset(arg.End())]+newline)}
          reordered:=make([]string,len(order));for i,j:=range order{reordered[i]=temps[j]};out.WriteString(indent+"\\t"+id.Name+"("+strings.Join(reordered,", ")+")"+newline+indent+"}")
          changes[p.URI]=append(changes[p.URI],Edit{Range{position(p.Text,start),position(p.Text,end)},out.String()})
         }
         return Output{Passed:true,Target:&loc,Changes:changes,Checked:len(refs)}
        }
        func main(){out:=Output{Reason:"invalid function reorder request"};if len(os.Args)==2{b,e:=os.ReadFile(os.Args[1]);if e==nil&&len(b)<=96*1024*1024{var in Input;d:=json.NewDecoder(strings.NewReader(string(b)));d.DisallowUnknownFields();if d.Decode(&in)==nil{out=run(in)}}};_ = json.NewEncoder(os.Stdout).Encode(out)}
        """;
}
