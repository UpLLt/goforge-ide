// Copyright 2026 GoForge contributors. Licensed under the Apache License, Version 2.0.
package dev.goforge.platform;

import javax.swing.SwingUtilities;
import java.util.Objects;

/** Per-panel EDT state. No task, timer, global map, or reference to a disposed UI. */
public final class InstalledSearchGate {
  private boolean ready;
  private boolean disposed;
  private String pending;

  /** True means the caller must not yet call SearchResultPanel.setQuery. */
  public boolean defer(String query) {
    requireEdt();
    Objects.requireNonNull(query, "query");
    if (disposed) return true;
    if (ready) return false;
    pending = query; // Includes an empty query, which cancels an earlier search.
    return true;
  }

  /** Returns the latest query exactly once, after the complete model is installed. */
  public String initialized() {
    requireEdt();
    if (disposed || ready) return null;
    ready = true;
    String result = pending;
    pending = null;
    return result;
  }

  public boolean isReady() {
    requireEdt();
    return ready && !disposed;
  }

  /** Preserve PluginsTab's getQuery/isEmpty contract before admitting a query. */
  public String pendingQuery() {
    requireEdt();
    return ready || disposed ? null : pending;
  }

  public boolean isDisposed() {
    requireEdt();
    return disposed;
  }

  public void dispose() {
    requireEdt();
    disposed = true;
    pending = null;
  }

  private static void requireEdt() {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("Installed plugin search must run on the EDT");
    }
  }
}
