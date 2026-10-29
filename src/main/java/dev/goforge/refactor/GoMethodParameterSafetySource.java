package dev.goforge.refactor;

/** Original standard-library-only typed method proof; only reads the private snapshot and checked exports. */
final class GoMethodParameterSafetySource {
    private GoMethodParameterSafetySource() { }
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
        type Package struct { Dir,ImportPath,Name,Export,ForTest string; GoFiles,TestGoFiles,IgnoredGoFiles,CgoFiles []string; ImportMap map[string]string; Module *Module; Error json.RawMessage; DepsErrors []json.RawMessage }
        type Output struct { Passed bool `json:"passed"`; Reason string `json:"reason,omitempty"`; Checked int `json:"checked"` }
        type Parsed struct { Source; File *ast.File; Tokens *token.File }
        type limitedFile struct{ io.Reader; file *os.File };func(f *limitedFile)Close()error{return f.file.Close()}
        func offset(text string,p Position)(int,error){if p.Line<0||p.Character<0{return 0,fmt.Errorf("invalid position")};line,column:=0,0;for i,r:=range text{if line==p.Line&&column==p.Character{return i,nil};if r=='\\n'{line++;column=0}else{column+=utf16.RuneLen(r)};if line>p.Line||line==p.Line&&column>p.Character{return 0,fmt.Errorf("invalid UTF-16 position")}};if line==p.Line&&column==p.Character{return len(text),nil};return 0,fmt.Errorf("position outside source")}
        func readBounded(path string,limit int64)([]byte,error){st,e:=os.Lstat(path);if e!=nil||!st.Mode().IsRegular()||st.Size()>limit{return nil,fmt.Errorf("proof input is not a bounded regular file")};f,e:=os.Open(path);if e!=nil{return nil,e};defer f.Close();now,e:=f.Stat();if e!=nil||!os.SameFile(st,now){return nil,fmt.Errorf("proof file identity changed")};b,e:=io.ReadAll(io.LimitReader(f,limit+1));if e!=nil||int64(len(b))>limit{return nil,fmt.Errorf("proof input exceeded its bound")};return b,nil}
        func run(in Input)Output{
         fail:=func(reason string)Output{return Output{Reason:reason}}
         if len(in.Sources)==0||len(in.Sources)>2048||in.Request.References==nil||len(in.Request.References)>10000{return fail("incomplete bounded method source/reference inventory")}
         fs:=token.NewFileSet();byURI:=map[string]*Parsed{};byPath:=map[string]*Parsed{};var targetFile *Parsed;total:=0
         for _,src:=range in.Sources{total+=len(src.Text);if total>32*1024*1024{return fail("method source inventory exceeds its size limit")};path:=filepath.Clean(src.Path);if !filepath.IsAbs(path)||byPath[path]!=nil||byURI[src.URI]!=nil{return fail("ambiguous method source path")};data,e:=readBounded(path,32*1024*1024);if e!=nil||string(data)!=src.Text{return fail("private method snapshot changed before type checking")};f,e:=parser.ParseFile(fs,path,src.Text,parser.ParseComments);if e!=nil{return fail("method source cannot be parsed")};for _,imp:=range f.Imports{if imp.Path.Value==`"unsafe"`{return fail("unsafe imports in the captured module are outside private method proof")}};for _,group:=range f.Comments{for _,comment:=range group.List{if strings.Contains(comment.Text,"go:linkname")||strings.Contains(comment.Text,"go:wasm")||strings.HasPrefix(comment.Text,"//export "){return fail("foreign linkage in the captured module is outside private method proof")}}};p:=&Parsed{src,f,fs.File(f.Pos())};byPath[path]=p;byURI[src.URI]=p;if src.URI==in.Request.Target.URI{targetFile=p}}
         if targetFile==nil{return fail("method target is outside the type snapshot")};targetDir:=filepath.Dir(filepath.Clean(targetFile.Path))
         data,e:=readBounded(in.Packages,8*1024*1024);if e!=nil{return fail(e.Error())};dec:=json.NewDecoder(strings.NewReader(string(data)));exports:=map[string]string{};var selected *Package;count:=0
         for{var pkg Package;e:=dec.Decode(&pkg);if e==io.EOF{break};if e!=nil{return fail("invalid compiler export graph")};count++;if count>4096||len(pkg.Error)>0&&string(pkg.Error)!="null"||len(pkg.DepsErrors)>0{return fail("incomplete or oversized compiler export graph")};if pkg.ForTest==""&&!strings.Contains(pkg.ImportPath," ["){if old,ok:=exports[pkg.ImportPath];ok&&old!=pkg.Export{return fail("ambiguous compiler export identity")};exports[pkg.ImportPath]=pkg.Export;if filepath.Clean(pkg.Dir)==targetDir&&pkg.Name==targetFile.File.Name.Name{if selected!=nil{return fail("ambiguous target compiler package")};copy:=pkg;selected=&copy}}}
         if selected==nil||selected.Module==nil||selected.Module.GoVersion==""||len(selected.IgnoredGoFiles)>0||len(selected.CgoFiles)>0{return fail("method proof requires one complete ordinary compiler package")}
         filenames:=append(append([]string{},selected.GoFiles...),selected.TestGoFiles...);included:=map[string]bool{};files:=[]*ast.File{};parents:=map[ast.Node]ast.Node{}
         for _,name:=range filenames{if name!=filepath.Base(name)||!strings.HasSuffix(name,".go"){return fail("unsafe compiler source name")};path:=filepath.Join(targetDir,name);p:=byPath[path];if p==nil||p.File.Name.Name!=selected.Name||included[path]{return fail("compiler sources differ from the reviewed method package")};included[path]=true;files=append(files,p.File)
          for _,imp:=range p.File.Imports{if imp.Path.Value==`"unsafe"`||imp.Name!=nil&&imp.Name.Name=="."{return fail("unsafe and dot imports are outside private method proof")}}
          for _,group:=range p.File.Comments{for _,c:=range group.List{if strings.Contains(c.Text,"go:linkname")||strings.Contains(c.Text,"go:wasm")||strings.HasPrefix(c.Text,"//export ")||strings.HasPrefix(c.Text,"//go:embed")||strings.HasPrefix(c.Text,"//go:build")||strings.HasPrefix(c.Text,"// +build"){return fail("compiler/linkage/build directives are outside private method proof")}}}
          stack:=[]ast.Node{};ast.Inspect(p.File,func(n ast.Node)bool{if n==nil{stack=stack[:len(stack)-1];return true};if len(stack)>0{parents[n]=stack[len(stack)-1]};stack=append(stack,n);return true})
         }
         for path,p:=range byPath{if filepath.Dir(path)==targetDir&&p.File.Name.Name==selected.Name&&!included[path]{return fail("a reviewed method package source is absent from the compiler graph")}}
         totalExports:=int64(0);opened:=map[string]bool{}
         lookup:=func(path string)(io.ReadCloser,error){if mapped:=selected.ImportMap[path];mapped!=""{path=mapped};export:=exports[path];if export==""||!filepath.IsAbs(export){return nil,fmt.Errorf("required export is absent from the checked compiler graph")};st,e:=os.Lstat(export);if e!=nil||!st.Mode().IsRegular()||st.Size()>64*1024*1024{return nil,fmt.Errorf("compiler export is not a bounded regular file")};if !opened[export]{totalExports+=st.Size();opened[export]=true};if totalExports>256*1024*1024{return nil,fmt.Errorf("compiler export budget exceeded")};f,e:=os.Open(export);if e!=nil{return nil,e};now,e:=f.Stat();if e!=nil||!os.SameFile(st,now){f.Close();return nil,fmt.Errorf("compiler export identity changed")};return &limitedFile{io.LimitReader(f,64*1024*1024+1),f},nil}
         sizes:=types.SizesFor("gc",in.Arch);if sizes==nil{return fail("unsupported method compiler architecture")}
         info:=&types.Info{Types:map[ast.Expr]types.TypeAndValue{},Defs:map[*ast.Ident]types.Object{},Uses:map[*ast.Ident]types.Object{},Selections:map[*ast.SelectorExpr]*types.Selection{}}
         cfg:=types.Config{Importer:importer.ForCompiler(fs,"gc",lookup),Sizes:sizes,GoVersion:"go"+selected.Module.GoVersion}
         pkg,e:=cfg.Check(selected.ImportPath,fs,files,info);if e!=nil{return fail("complete private method package type checking failed: "+e.Error())}
         start,e:=offset(targetFile.Text,in.Request.Target.Range.Start);if e!=nil{return fail(e.Error())};end,e:=offset(targetFile.Text,in.Request.Target.Range.End);if e!=nil{return fail(e.Error())}
         var decl *ast.FuncDecl
         for _,d:=range targetFile.File.Decls{if f,ok:=d.(*ast.FuncDecl);ok&&targetFile.Tokens.Offset(f.Name.Pos())==start&&targetFile.Tokens.Offset(f.Name.End())==end{decl=f}}
         if decl==nil||decl.Recv==nil||decl.Name.IsExported(){return fail("typed target must be a private method declaration")};obj,ok:=info.Defs[decl.Name].(*types.Func);if !ok||obj.Pkg()!=pkg{return fail("method object identity could not be established")};sig:=obj.Type().(*types.Signature)
         recv:=sig.Recv().Type();if ptr,ok:=recv.(*types.Pointer);ok{recv=ptr.Elem()};named,ok:=recv.(*types.Named);if !ok||named.Obj().IsAlias()||named.Obj().Exported()||named.Obj().Pkg()!=pkg||named.TypeParams().Len()!=0{return fail("method receiver is not one private non-generic local named type")};if _,ok:=named.Underlying().(*types.Struct);!ok{return fail("method receiver is not a concrete struct")}
         // A signature change can REMOVE or INTRODUCE interface conformance even when both
         // versions compile. Refuse every related method ID in the complete observed type graph.
         seenTypes:=map[types.Type]bool{};related:=false;typeBudget:=0;var walk func(types.Type)
         walk=func(t types.Type){if t==nil||seenTypes[t]||related{return};seenTypes[t]=true;typeBudget++;if typeBudget>50000{related=true;return};switch t:=t.(type){case *types.Alias:walk(types.Unalias(t));case *types.Named:walk(t.Underlying());for i:=0;i<t.TypeParams().Len();i++{walk(t.TypeParams().At(i))};for i:=0;i<t.TypeArgs().Len();i++{walk(t.TypeArgs().At(i))};case *types.Interface:t.Complete();for i:=0;i<t.NumMethods();i++{m:=t.Method(i);if m.Name()==obj.Name()&&m.Pkg()!=nil&&m.Pkg().Path()==pkg.Path(){related=true;return};walk(m.Type())};for i:=0;i<t.NumEmbeddeds();i++{walk(t.EmbeddedType(i))};case *types.Pointer:walk(t.Elem());case *types.Array:walk(t.Elem());case *types.Slice:walk(t.Elem());case *types.Map:walk(t.Key());walk(t.Elem());case *types.Chan:walk(t.Elem());case *types.Struct:for i:=0;i<t.NumFields();i++{walk(t.Field(i).Type())};case *types.Signature:if t.Recv()!=nil{walk(t.Recv().Type())};walk(t.Params());walk(t.Results());for i:=0;i<t.TypeParams().Len();i++{walk(t.TypeParams().At(i))};for i:=0;i<t.RecvTypeParams().Len();i++{walk(t.RecvTypeParams().At(i))};case *types.Tuple:for i:=0;i<t.Len();i++{walk(t.At(i).Type())};case *types.TypeParam:walk(t.Constraint());case *types.Union:for i:=0;i<t.Len();i++{walk(t.Term(i).Type())}}}
         for _,o:=range info.Defs{if o!=nil{walk(o.Type())}};for _,tv:=range info.Types{walk(tv.Type)};for _,o:=range info.Uses{if o!=nil{walk(o.Type())}}
         if related{return fail("a related interface/constraint or oversized type graph prevents private method signature changes")}
         expected:=map[string]bool{}
         for _,ref:=range in.Request.References{p:=byURI[ref.URI];if p==nil||!included[filepath.Clean(p.Path)]{return fail("a method reference is outside its reviewed typed package")};a,e:=offset(p.Text,ref.Range.Start);if e!=nil{return fail(e.Error())};b,e:=offset(p.Text,ref.Range.End);if e!=nil{return fail(e.Error())};key:=fmt.Sprintf("%s:%d:%d",p.Path,a,b);if expected[key]{return fail("duplicate method reference")};expected[key]=true}
         checked:=map[string]bool{}
         for selector,sel:=range info.Selections{if sel.Obj()!=obj{continue};call,ok:=parents[selector].(*ast.CallExpr);if !ok||call.Fun!=selector||sel.Kind()!=types.MethodVal||len(sel.Index())!=1{return fail("method values, expressions and promoted calls are not supported")};if _,ok:=selector.X.(*ast.Ident);!ok{return fail("method receiver calls must use a concrete identifier")};receiver:=sel.Recv();if ptr,ok:=receiver.(*types.Pointer);ok{receiver=ptr.Elem()};if !types.Identical(receiver,named){return fail("method call receiver differs from its declaration")};p:=byPath[fs.Position(selector.Pos()).Filename];if p==nil{return fail("typed method call is outside the captured source")};key:=fmt.Sprintf("%s:%d:%d",p.Path,p.Tokens.Offset(selector.Sel.Pos()),p.Tokens.Offset(selector.Sel.End()));if !expected[key]||checked[key]{return fail("typed method calls differ from the language-server reference set")};checked[key]=true}
         if len(checked)!=len(expected){return fail("a reference is not the same concrete method's direct call")}
         return Output{Passed:true,Checked:len(checked)}
        }
        func main(){out:=Output{Reason:"invalid bounded method proof input"};if len(os.Args)==2{b,e:=readBounded(os.Args[1],96*1024*1024);if e==nil{var in Input;d:=json.NewDecoder(strings.NewReader(string(b)));d.DisallowUnknownFields();if d.Decode(&in)==nil{out=run(in)}}};_ = json.NewEncoder(os.Stdout).Encode(out)}
        """;
}
