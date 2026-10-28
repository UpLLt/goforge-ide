# Community plugin search and essential-plugin toggle repairs

This is a narrow Apache-2.0 adaptation for the **public IntelliJ Community
252.28539.97** platform. It contains no proprietary GoLand implementation.

## Cause and public provenance

In the pinned platform, `PluginManagerConfigurable.enableSearch` can immediately
submit a search after lazily creating the Installed tab. Its installed-panel
model is loaded asynchronously. The query reaches
`MyPluginModel.getInstalledDescriptors()` before `setDownloadedGroup` has set
the installed panel, triggering its assertion. Settings search/Find Action can
trigger this order without an installed-plugin configuration error.

Public source at tag `idea/252.28539.97`:

- [PluginManagerConfigurable.java](https://github.com/JetBrains/intellij-community/blob/idea/252.28539.97/platform/platform-impl/src/com/intellij/ide/plugins/PluginManagerConfigurable.java): installed model callback at 1057–1158; query at 1305; search entry at 2097–2128.
- [PluginManagerPanelFactory.kt](https://github.com/JetBrains/intellij-community/blob/idea/252.28539.97/platform/platform-impl/src/com/intellij/ide/plugins/PluginManagerPanelFactory.kt): asynchronous IO then EDT callback at 63–81.
- [MyPluginModel.java](https://github.com/JetBrains/intellij-community/blob/idea/252.28539.97/platform/platform-impl/src/com/intellij/ide/plugins/newui/MyPluginModel.java): assignment at 612; assertion at 728.
- [SearchResultPanel.java](https://github.com/JetBrains/intellij-community/blob/idea/252.28539.97/platform/platform-impl/src/com/intellij/ide/plugins/newui/SearchResultPanel.java): the same-query fast path at 107 requires deferring **before** admitting the query.

JetBrains later addressed this ordering in
[IJPL-254032, commit bf1d84fac9258410c0e3312e9ea905f14b76a825](https://github.com/JetBrains/intellij-community/commit/bf1d84fac9258410c0e3312e9ea905f14b76a825).
That newer Kotlin implementation suspends search until its installed model job
completes. This patch preserves that readiness dependency in the older Java UI;
it is not a literal backport or a full platform upgrade.

## Exact changes

`scripts/patch-installed-plugin-search.py` checks the complete original
`app-client.jar` SHA-256 (`f1791ff550fc4f42e495c1f5ab65779661d002b3c55dc4183209c207345c7bc5`),
the original SHA-256 of six classes, and two unchanged collaborators before using
the platform's bundled ASM:

1. Installed search panel `PluginManagerConfigurable$13$6`: a per-instance
   `InstalledSearchGate` stores only the latest query until model readiness.
   Nonempty pending searches display “Loading installed plugins…”. Empty input
   clears/cancels the pending search. Ready queries use the original superclass
   `setQuery`, filtering, result groups, selection and callbacks. `getQuery`,
   `isEmpty` and `setEmpty` reflect pending input, so the original tab's clear,
   reset and tab-switch logic still sees the correct query state.
2. Installed tab `$13`: after the successful model callback has installed all
   groups and stopped loading, mark the search ready and replay the latest query
   once on the EDT. A failed callback is not marked ready.
   The tab captures its own search-panel identity at construction. A late callback
   for a disposed tab is rejected before entering the old model callback; it
   cannot initialize a newly opened settings instance through the outer field.
3. Its vendor/tag suggestion popup `$13$3`: while not ready, return the existing
   nullable “no suggestions yet” result, avoiding the same uninitialized model.
4. `PluginOptionsTopHitProvider` filters application-essential plugins and their
   transitive nonoptional dependencies before creating Boolean toggle options.
5. `PluginBooleanOptionDescriptor.togglePluginState` rechecks that protected
   closure on each disable request, including an option cached before filtering.
   A mixed ordinary/protected request is rejected atomically with normal user
   feedback; no plugin in it is disabled. The enable branch is unchanged.
6. Its notification undo action `$1` performs the same fresh check before its
   captured disable closure. A refused action expires its old notification.

The additional public sources are
[PluginOptionsTopHitProvider.java](https://github.com/JetBrains/intellij-community/blob/idea/252.28539.97/platform/platform-impl/src/com/intellij/ide/ui/PluginOptionsTopHitProvider.java),
[PluginBooleanOptionDescriptor.java](https://github.com/JetBrains/intellij-community/blob/idea/252.28539.97/platform/platform-impl/src/com/intellij/ide/ui/PluginBooleanOptionDescriptor.java),
and [DefaultUiPluginManagerController.kt](https://github.com/JetBrains/intellij-community/blob/idea/252.28539.97/platform/platform-impl/src/com/intellij/ide/plugins/newui/DefaultUiPluginManagerController.kt).
The original top-hit provider does not exclude essential plugins; its disable
and notification undo branches directly call `PluginEnabler.disable`. Its
dependent lookup skips essential plugins, so merely checking that returned list
would miss required dependencies. The adaptation uses the current
`ApplicationInfoEx` essential IDs and the original platform's
`processAllNonOptionalDependencies` traversal, including content-module mapping.
It has no hard-coded GoForge plugin allowlist, does not remove the Plugins page,
and does not restrict unrelated/optional plugins. The descriptor's separate
`require-restart` setting is a complementary lifecycle policy, not implemented by
this patch.

Disposal permanently invalidates that panel's gate. A late callback cannot
replay it. A later settings instance has a separate gate. There is no timer,
polling, blocking wait, global queue, removed assertion, or empty-result fallback
for a search. Existing platform initialization failure reporting is unchanged.

The generated source equivalent is:

```java
// Installed search panel; runs on the EDT.
public void setQuery(String query) {
    if (gate.defer(query)) {
        if (gate.isDisposed()) return;
        if (query.isEmpty()) super.setQuery(query);
        else getPanel().getEmptyText().setText("Loading installed plugins…");
        return;
    }
    super.setQuery(query);
}
public void goforgeModelInitialized() {
    String query = gate.initialized();
    if (query != null) setQuery(query);
}
public void dispose() { gate.dispose(); super.dispose(); }
// Successful installed-model callback only, after every group is installed:
installedSearchPanel.goforgeModelInitialized();
```

The platform's `app-client.jar` also has a generated `__index__` and a five-byte
ZIP comment containing optimized offsets. A rewritten ZIP cannot retain those
offsets. The patch validates and removes only this generated index and clears
that comment, allowing the unchanged IntelliJ `PathClassLoader` to use the normal
ZIP central directory. All other unmodified entries retain their original bytes.
The receipt records both removed metadata identities. Keeping the stale index
failed the real platform-loader probe; standard JVM class loading alone did not
detect it. No loader implementation or global class-loader option is changed.

## Reproduction, verification and boundaries

Run `python3 scripts/test-patch-installed-plugin-search.py --platform-home PATH`
against a pristine supported platform copy. It creates a separate owned test
copy, checks original/changed/unchanged class identities, validates generated
bytecode and same-instance callback hook structure, executes EDT gate lifecycle
checks, and instantiates the real transformed search panel on a headless EDT to
check query replacement, conditional clear, reset and disposal. It also tests
idempotence and rejects an altered original class.
It does not alter the input platform or download caches.
The probe explicitly uses the real IntelliJ `PathClassLoader`, including its ZIP
index handling, rather than relying on the standard JVM application loader.

For Windows CI, `--platform-zip PATH` can replace `--platform-home PATH`; it reads
only the official ZIP's Java libraries and product metadata into a disposable
copy. Pass `--javac PATH` for the host's JDK 21 or later. The probe uses the
platform's declared module-opening arguments. Python 3.9+ and a complete pristine
platform library set are required; the test does not download anything.

The macOS and Windows packagers apply this patch only to their staging copy and
include this directory, the patch script and the embedded/external receipt.
Gradle's original SDK remains pristine: ordinary plugin JUnit results alone do
**not** validate this application-platform patch. Native acceptance must cover
opening Plugins through Settings search, typing/clearing queries during initial
loading, closing/reopening Settings, and obtaining real installed-plugin results.
The standalone probe does not construct a complete PluginsTab/Application or
execute its installed-model coroutine: that requires full platform/native tests.
Windows runtime acceptance remains separate from packaging checks.

`EssentialPluginToggleProbe` additionally uses real public descriptor builders,
the actual platform dependency graph and the transformed provider, cached option
and notification-action classes. Its isolated Application service fixture records
the terminal `PluginEnabler` calls rather than actually unloading an application.
It checks transitive required versus optional dependencies, mixed-request
atomicity, ordinary enable/disable delegation, and captured notification actions.
The same assertions are also run against the three original toggle classes: the
known-defect baseline reaches the core disable sink and fails. That failure is
saved separately and is not counted as successful product behavior. Native
acceptance must still confirm that application-essential toggles are absent and
ordinary plugins remain usable in the real IDE.

The patch is rejected for unknown class bytes, a signed JAR, an unexpected build,
linked files, or a non-owned output location. Reapplying identical inputs checks
the receipt and performs no rewrite. Changed patch source requires a fresh
pristine staging copy. No public release is authorized until the project's full
functional alignment requirement is met.

## License

The original Community source is Copyright JetBrains s.r.o. and contributors,
Apache-2.0. The adaptation and helper are Copyright 2026 GoForge contributors,
Apache-2.0. [LICENSE](LICENSE) is the unchanged license text. Original platform
notices and licenses remain in the distribution.
