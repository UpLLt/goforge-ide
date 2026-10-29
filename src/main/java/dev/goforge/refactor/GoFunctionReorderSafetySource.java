package dev.goforge.refactor;

/** Original bounded static-function reorder helper; never executes project code. */
final class GoFunctionReorderSafetySource {
    private GoFunctionReorderSafetySource() {}
    static final String TEXT = """
        package main

        import (
         "encoding/json"
         "fmt"
         "go/ast"
         "go/importer"
         "go/parser"
         "go/token"
         "go/types"
         "io"
         "os"
         "path/filepath"
         "strings"
         "unicode/utf16"
        )
        type Position struct { Line int `json:"line"`; Character int `json:"character"` }
        type Range struct { Start Position `json:"start"`; End Position `json:"end"` }
        type Location struct { URI string `json:"uri"`; Range Range `json:"range"` }
        type Request struct { Target Location `json:"target"`; References []Location `json:"references"` }
        type Source struct { URI string `json:"uri"`; Path string `json:"path"`; Text string `json:"text"` }
        type Input struct { Sources []Source `json:"sources"`; Request Request `json:"request"`; Packages string `json:"packages"`; Arch string `json:"arch"` }
        type Module struct { GoVersion string }
        type Package struct { Dir,ImportPath,Name,Export,ForTest string; GoFiles,TestGoFiles,IgnoredGoFiles,CgoFiles,Match []string; ImportMap map[string]string; Module *Module; Error json.RawMessage; DepsErrors []json.RawMessage }
        type Output struct { Passed bool `json:"passed"`; Reason string `json:"reason,omitempty"`; Checked int `json:"checked"` }
        type Parsed struct { Source; File *ast.File; Tokens *token.File }
        type limitedFile struct{ io.Reader; file *os.File };func(f *limitedFile)Close()error{return f.file.Close()}
        func offset(text string,p Position)(int,error){if p.Line<0||p.Character<0{return 0,fmt.Errorf("invalid position")};line,column:=0,0;for i,r:=range text{if line==p.Line&&column==p.Character{return i,nil};if r=='\\n'{line++;column=0}else{column+=utf16.RuneLen(r)};if line>p.Line||line==p.Line&&column>p.Character{return 0,fmt.Errorf("invalid UTF-16 position")}};if line==p.Line&&column==p.Character{return len(text),nil};return 0,fmt.Errorf("position outside source")}
        func readBounded(path string,limit int64)([]byte,error){st,e:=os.Lstat(path);if e!=nil||!st.Mode().IsRegular()||st.Size()>limit{return nil,fmt.Errorf("proof input is not a bounded regular file")};f,e:=os.Open(path);if e!=nil{return nil,e};defer f.Close();now,e:=f.Stat();if e!=nil||!os.SameFile(st,now){return nil,fmt.Errorf("proof file identity changed")};b,e:=io.ReadAll(io.LimitReader(f,limit+1));if e!=nil||int64(len(b))>limit{return nil,fmt.Errorf("proof input exceeded its bound")};return b,nil}
        func matched(values []string)bool{for _,v:=range values{if v=="./..."{return true}};return false}
        func scalar(name string)bool{for _,n:=range strings.Fields("bool string int int8 int16 int32 int64 uint uint8 uint16 uint32 uint64 uintptr byte rune float32 float64 complex64 complex128"){if name==n{return true}};return false}
        func run(in Input)Output{
         fail:=func(reason string)Output{return Output{Reason:reason}}
         if len(in.Sources)==0||len(in.Sources)>2048||in.Request.References==nil||len(in.Request.References)>10000{return fail("incomplete bounded function reorder source/reference inventory")}
         fs:=token.NewFileSet();byURI:=map[string]*Parsed{};byPath:=map[string]*Parsed{};var targetFile *Parsed;total:=0
         for _,src:=range in.Sources{total+=len(src.Text);if total>32*1024*1024{return fail("function reorder source inventory exceeds its size limit")};path:=filepath.Clean(src.Path);if !filepath.IsAbs(path)||byPath[path]!=nil||byURI[src.URI]!=nil{return fail("ambiguous function reorder source path")};data,e:=readBounded(path,32*1024*1024);if e!=nil||string(data)!=src.Text{return fail("private function reorder snapshot changed before type checking")};f,e:=parser.ParseFile(fs,path,src.Text,parser.ParseComments);if e!=nil{return fail("function reorder source cannot be parsed")};for _,imp:=range f.Imports{if imp.Path.Value==`"unsafe"`{return fail("unsafe imports in the captured module are outside private function reorder proof")}};for _,group:=range f.Comments{for _,comment:=range group.List{if strings.Contains(comment.Text,"go:linkname")||strings.Contains(comment.Text,"go:wasm")||strings.HasPrefix(comment.Text,"//export "){return fail("foreign linkage in the captured module is outside private function reorder proof")}}};p:=&Parsed{src,f,fs.File(f.Pos())};byPath[path]=p;byURI[src.URI]=p;if src.URI==in.Request.Target.URI{targetFile=p}}
         if targetFile==nil{return fail("function reorder target is outside the type snapshot")};targetDir:=filepath.Dir(filepath.Clean(targetFile.Path))
         data,e:=readBounded(in.Packages,8*1024*1024);if e!=nil{return fail(e.Error())};dec:=json.NewDecoder(strings.NewReader(string(data)));exports:=map[string]string{};var selected *Package;count:=0
         for{var pkg Package;e:=dec.Decode(&pkg);if e==io.EOF{break};if e!=nil{return fail("invalid compiler export graph")};count++;if count>4096||len(pkg.Error)>0&&string(pkg.Error)!="null"||len(pkg.DepsErrors)>0{return fail("incomplete or oversized compiler export graph")};if pkg.ForTest==""&&!strings.Contains(pkg.ImportPath," ["){if old,ok:=exports[pkg.ImportPath];ok&&old!=pkg.Export{return fail("ambiguous compiler export identity")};exports[pkg.ImportPath]=pkg.Export;if matched(pkg.Match)&&filepath.Clean(pkg.Dir)==targetDir&&pkg.Name==targetFile.File.Name.Name{if selected!=nil{return fail("ambiguous target compiler package")};copy:=pkg;selected=&copy}}}
         if selected==nil||selected.Module==nil||selected.Module.GoVersion==""||len(selected.IgnoredGoFiles)>0||len(selected.CgoFiles)>0{return fail("function reorder proof requires one complete ordinary compiler package")}
         filenames:=append(append([]string{},selected.GoFiles...),selected.TestGoFiles...);included:=map[string]bool{};files:=[]*ast.File{};parents:=map[ast.Node]ast.Node{}
         for _,name:=range filenames{if name!=filepath.Base(name)||!strings.HasSuffix(name,".go"){return fail("unsafe compiler source name")};path:=filepath.Join(targetDir,name);p:=byPath[path];if p==nil||p.File.Name.Name!=selected.Name||included[path]{return fail("compiler sources differ from the reviewed function reorder package")};included[path]=true;files=append(files,p.File)
          for _,imp:=range p.File.Imports{if imp.Path.Value==`"unsafe"`||imp.Name!=nil&&imp.Name.Name=="."{return fail("unsafe and dot imports are outside private function reorder proof")}}
          for _,group:=range p.File.Comments{for _,c:=range group.List{if strings.Contains(c.Text,"go:linkname")||strings.Contains(c.Text,"go:wasm")||strings.HasPrefix(c.Text,"//export ")||strings.HasPrefix(c.Text,"//go:embed")||strings.HasPrefix(c.Text,"//go:build")||strings.HasPrefix(c.Text,"// +build"){return fail("compiler/linkage/build directives are outside private function reorder proof")}}}
          stack:=[]ast.Node{};ast.Inspect(p.File,func(n ast.Node)bool{if n==nil{stack=stack[:len(stack)-1];return true};if len(stack)>0{parents[n]=stack[len(stack)-1]};stack=append(stack,n);return true})
         }
         for path,p:=range byPath{if filepath.Dir(path)==targetDir&&p.File.Name.Name==selected.Name&&!included[path]{return fail("a reviewed function reorder package source is absent from the compiler graph")}}
         totalExports:=int64(0);opened:=map[string]bool{}
         lookup:=func(path string)(io.ReadCloser,error){if mapped:=selected.ImportMap[path];mapped!=""{path=mapped};export:=exports[path];if export==""||!filepath.IsAbs(export){return nil,fmt.Errorf("required export is absent from the checked compiler graph")};st,e:=os.Lstat(export);if e!=nil||!st.Mode().IsRegular()||st.Size()>64*1024*1024{return nil,fmt.Errorf("compiler export is not a bounded regular file")};if !opened[export]{totalExports+=st.Size();opened[export]=true};if totalExports>256*1024*1024{return nil,fmt.Errorf("compiler export budget exceeded")};f,e:=os.Open(export);if e!=nil{return nil,e};now,e:=f.Stat();if e!=nil||!os.SameFile(st,now){f.Close();return nil,fmt.Errorf("compiler export identity changed")};return &limitedFile{io.LimitReader(f,64*1024*1024+1),f},nil}
         sizes:=types.SizesFor("gc",in.Arch);if sizes==nil{return fail("unsupported function reorder compiler architecture")}
         info:=&types.Info{Types:map[ast.Expr]types.TypeAndValue{},Defs:map[*ast.Ident]types.Object{},Uses:map[*ast.Ident]types.Object{},Selections:map[*ast.SelectorExpr]*types.Selection{}}
         cfg:=types.Config{Importer:importer.ForCompiler(fs,"gc",lookup),Sizes:sizes,GoVersion:"go"+selected.Module.GoVersion}
         pkg,e:=cfg.Check(selected.ImportPath,fs,files,info);if e!=nil{return fail("complete private function reorder package type checking failed: "+e.Error())}
         start,e:=offset(targetFile.Text,in.Request.Target.Range.Start);if e!=nil{return fail(e.Error())};end,e:=offset(targetFile.Text,in.Request.Target.Range.End);if e!=nil{return fail(e.Error())}
         var decl *ast.FuncDecl
         for _,d:=range targetFile.File.Decls{if f,ok:=d.(*ast.FuncDecl);ok&&targetFile.Tokens.Offset(f.Name.Pos())==start&&targetFile.Tokens.Offset(f.Name.End())==end{decl=f}}

         if decl==nil||decl.Recv!=nil||decl.Name.IsExported()||decl.Body==nil||decl.Type.TypeParams!=nil{return fail("typed target must be a private non-generic top-level function")}
         obj,ok:=info.Defs[decl.Name].(*types.Func);if !ok||obj.Pkg()!=pkg||obj.Parent()!=pkg.Scope(){return fail("static function object identity could not be established")};sig:=obj.Type().(*types.Signature)
         if sig.Recv()!=nil||sig.TypeParams().Len()!=0||sig.Variadic()||sig.Params().Len()<2||sig.Params().Len()>64{return fail("unsupported static scalar function signature")}
         spellings:=[]string{}
         for _,field:=range decl.Type.Params.List{if len(field.Names)!=1||field.Names[0].Name=="_"{return fail("one named scalar is required per parameter")};id,ok:=field.Type.(*ast.Ident);if !ok||!scalar(id.Name)||info.Uses[id]!=types.Universe.Lookup(id.Name){return fail("parameter type must resolve to the predeclared scalar, without shadowing or aliases")};spellings=append(spellings,id.Name)}
         if len(spellings)!=sig.Params().Len(){return fail("parameter shape differs from its typed signature")}
         expected:=map[string]bool{}
         for _,ref:=range in.Request.References{p:=byURI[ref.URI];if p==nil||!included[filepath.Clean(p.Path)]{return fail("a function reference is outside its reviewed typed package")};a,e:=offset(p.Text,ref.Range.Start);if e!=nil{return fail(e.Error())};b,e:=offset(p.Text,ref.Range.End);if e!=nil{return fail(e.Error())};key:=fmt.Sprintf("%s:%d:%d",p.Path,a,b);if expected[key]{return fail("duplicate function reference")};expected[key]=true}
         checked:=map[string]bool{}
         for id,used:=range info.Uses{if used!=obj{continue};call,ok:=parents[id].(*ast.CallExpr);if !ok||call.Fun!=id||call.Ellipsis.IsValid()||len(call.Args)!=sig.Params().Len(){return fail("function values, indirect calls and expanded arguments are not supported")}
          statement,ok:=parents[call].(*ast.ExprStmt);if !ok{return fail("only independent expression-statement calls are supported")};if _,ok:=parents[statement].(*ast.BlockStmt);!ok{return fail("call is not directly inside a block")}
          var enclosing ast.Node;for n:=ast.Node(statement);n!=nil;n=parents[n]{switch n.(type){case *ast.FuncDecl,*ast.FuncLit:enclosing=n};if enclosing!=nil{break}};if enclosing==nil{return fail("call has no enclosing function")};label:=false;ast.Inspect(enclosing,func(n ast.Node)bool{if _,ok:=n.(*ast.LabeledStmt);ok{label=true};return true});if label{return fail("functions containing labels are unsupported")}
          scope:=pkg.Scope().Innermost(call.Pos());if scope==nil{return fail("the caller lexical scope cannot be established")}
          for i,arg:=range call.Args{_,binding:=scope.LookupParent(spellings[i],call.Pos());if binding!=types.Universe.Lookup(spellings[i]){return fail("a caller scope shadows the scalar type required by a temporary")}
           tv,known:=info.Types[arg];if !known||tv.Type==nil{return fail("argument type is unavailable")};if tv.Value!=nil{continue}
           inner,ok:=arg.(*ast.CallExpr);if !ok||inner.Ellipsis.IsValid(){return fail("each argument must be a constant or one direct static function call; mutable variable reads are refused")}
           var called types.Object
           switch fun:=inner.Fun.(type){case *ast.Ident:called=info.Uses[fun];case *ast.SelectorExpr:if info.Selections[fun]!=nil{return fail("method calls are outside the static argument subset")};qualifier,ok:=fun.X.(*ast.Ident);if !ok{return fail("argument callee is not a static package function")};if _,ok:=info.Uses[qualifier].(*types.PkgName);!ok{return fail("argument callee qualifier is not an imported package")};called=info.Uses[fun.Sel];default:return fail("argument callee must be one non-generic static function")}
           fn,ok:=called.(*types.Func);if !ok||fn.Pkg()==nil||fn.Parent()!=fn.Pkg().Scope(){return fail("builtins, conversions and function variables are not supported as argument calls")};signature,ok:=fn.Type().(*types.Signature);if !ok||signature.Recv()!=nil||signature.TypeParams().Len()!=0||signature.Variadic()||signature.Results().Len()!=1{return fail("argument function must be non-variadic and return exactly one scalar")}
           basic,ok:=signature.Results().At(0).Type().Underlying().(*types.Basic);if !ok||basic.Info()&(types.IsBoolean|types.IsNumeric|types.IsString)==0||!types.AssignableTo(signature.Results().At(0).Type(),sig.Params().At(i).Type()){return fail("argument function must return one assignable scalar")}
          }
          p:=byPath[fs.Position(id.Pos()).Filename];if p==nil{return fail("typed function call is outside the captured source")};key:=fmt.Sprintf("%s:%d:%d",p.Path,p.Tokens.Offset(id.Pos()),p.Tokens.Offset(id.End()));if !expected[key]||checked[key]{return fail("typed function calls differ from the language-server reference set")};checked[key]=true
         }
         if len(checked)!=len(expected){return fail("a reference is not the selected static function's direct call")}

         return Output{Passed:true,Checked:len(checked)}
        }
        func main(){out:=Output{Reason:"invalid bounded function reorder proof input"};if len(os.Args)==2{b,e:=readBounded(os.Args[1],96*1024*1024);if e==nil{var in Input;d:=json.NewDecoder(strings.NewReader(string(b)));d.DisallowUnknownFields();if d.Decode(&in)==nil{out=run(in)}}};_ = json.NewEncoder(os.Stdout).Encode(out)}
        """;
}
