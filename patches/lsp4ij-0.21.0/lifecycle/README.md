# Explorer lifecycle EDT repair

This is a narrow source-level repair of the public LSP4IJ 0.21.0
`LanguageServerExplorerLifecycleListener`, whose original header offers
EPL-2.0 or Apache-2.0. The upstream copyright and license header are retained;
the parent directory contains the upstream EPL license and GoForge's root
`LICENSE` contains Apache-2.0. No platform tree-cache class is modified.

Upstream source:
<https://github.com/redhat-developer/lsp4ij/blob/0.21.0/src/main/java/com/redhat/devtools/lsp4ij/console/explorer/LanguageServerExplorerLifecycleListener.java>

The exact upstream JAR SHA-256 is
`c874e03849aeab974483913ea72161bca0c315c7800c77cbf530d82da0001900`.
Original source bytes SHA-256:
`ee8ad06cc2db45531a46474e6c1fe01b73f9756f13f9f3e931897f5c1facb4b6`.
The original listener class, metadata-patched input, reviewed replacement source,
compiled class and final JAR hashes are pinned in
`scripts/patch-lsp4ij-lifecycle.py`. Unknown versions or outputs fail before writing.

The upstream listener looks up and adds process nodes on the calling background
thread, then posts only status changes to the EDT. It uses `serverNode.add`
without a model insertion event. Concurrent first events can therefore create
duplicate process nodes, and the actual IntelliJ tree cache can receive a changed
index for a child it was never told about. A native session reported index 4 in a
four-child cache. The standalone probe reproduces that precise cache failure and
separately forces the actual listener's concurrent first-event defect.

The replacement sends status, message and error handling through the EDT before
any tree lookup or mutation. It checks disposal after dispatch, finds/creates one
process node, and inserts through `DefaultTreeModel.insertNodeInto` before
`setServerStatus` sends its change event. Existing status/selection/error handling
and the 500 ms background trace batching remain. Trace-message conversion stays
in the original background alarm. This does not catch or suppress tree errors,
disable the Language Servers window, or change language-server protocol behavior.

Apply after the existing metadata patch and after TextMate resource preparation:

```sh
python3 scripts/patch-lsp4ij-lifecycle.py \
  --plugin-dir <owned-lsp4ij-copy> --platform-home <pinned-platform-home> \
  --javac <JDK-25-javac>
```

Only GoForge-owned copies are writable; original caches and user plugin directories
are rejected. Exactly one class changes and one provenance entry is added. The
receipt includes the public original class so the validator can reconstruct and
hash every byte of the pinned metadata-patched base. The final archive hash is
also pinned, including rejection of unrecognized trailers. Atomic replacement
happens only after compilation and complete validation. Repeating metadata and
lifecycle preparation is supported without rewriting the JAR.

`scripts/test-patch-lsp4ij-lifecycle.py` compiles one probe and runs it first against
the original dependency, then the patched copy. It executes the real listener,
process/server tree nodes, Swing model and IntelliJ `DefaultTreeLayoutCache`.
Test-only explorer/wrapper adapters bypass heavyweight view/process constructors;
they do not replace those algorithms. The deterministic two-worker barrier exposes
the original off-EDT duplicate creation. The repaired run checks insertion before
change, one node, preserved status/message/error behavior, and disposal before a
queued update, and already-queued trace/error publication after listener disposal. It runs headlessly without Gradle, an IDE or a server, so it is not
a native project-switch acceptance claim. The first compiler attempt's failure
is retained: dependency JARs contain source files, hence compilation now explicitly
uses `-sourcepath '' -implicit:none` and accepts exactly one output class.

A standalone GoForge plugin ZIP does not replace an independently installed
LSP4IJ dependency. This repair ships in GoForge's prepared sandbox/distribution
dependency copies and must be repinned explicitly for any dependency/compiler change.

The compiler invocation targets Java 21, disables debug metadata and implicit source compilation, and uses JDK 25. The script verifies the compiled output hash rather than assuming that all JDK 25 vendor/update builds emit identical bytes. A different compiler output is rejected before replacing the dependency; Windows must pass the same compiler/output check and still requires separate native acceptance.
