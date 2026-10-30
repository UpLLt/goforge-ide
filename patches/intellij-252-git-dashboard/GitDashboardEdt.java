// Copyright 2026 GoForge contributors. Licensed under the Apache License, Version 2.0.
package dev.goforge.platform;

import com.intellij.openapi.application.CoroutinesKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Dispatchers;

/** Preserves launchOnShow's job while permitting TreeUtil's read actions. */
public final class GitDashboardEdt {
  private GitDashboardEdt() {}

  public static Function2<CoroutineScope, Continuation<? super Unit>, Object> wrap(
      Function2<? super CoroutineScope, ? super Continuation<? super Unit>, ? extends Object> block) {
    // launchOnShow deliberately installs Dispatchers.UI, overriding a context
    // argument. Switch inside its existing block, retaining cancellation,
    // modality, listener registration/finally cleanup, and show/hide lifetime.
    return (scope, continuation) ->
        BuildersKt.withContext(CoroutinesKt.getEDT(Dispatchers.INSTANCE), block, continuation);
  }
}
