package dev.goforge.platform;

import com.intellij.ide.plugins.DynamicPlugins;
import com.intellij.ide.plugins.IdeaPluginDescriptorImpl;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public final class GoForgePluginLifecycleTest extends BasePlatformTestCase {
    public void testLoadedPluginRemainsEnabledAndRequiresRestartForUnloading() {
        var plugin = PluginManagerCore.getPlugin(PluginId.getId("dev.goforge.ide"));
        assertNotNull(plugin);
        assertTrue("The lifecycle declaration must not prevent normal plugin loading", plugin.isEnabled());
        assertInstanceOf(plugin, IdeaPluginDescriptorImpl.class);
        var descriptor = (IdeaPluginDescriptorImpl) plugin;

        // Exercise the platform's actual unload decision without disabling the test host's plugin.
        assertEquals("Plugin dev.goforge.ide is explicitly marked as requiring restart",
                DynamicPlugins.INSTANCE.checkCanUnloadWithoutRestart(descriptor));
        assertFalse(DynamicPlugins.allowLoadUnloadWithoutRestart(descriptor));
        assertTrue(plugin.isEnabled());
    }
}
