package dev.goforge.packaging;

import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.application.PathManager;
import com.intellij.ide.gdpr.ConsentOptions;
import com.intellij.internal.statistic.utils.StatisticsUploadAssistant;
import java.nio.file.Files;
import java.nio.file.Path;

/** Headless native-launcher verification; no IDE project, UI or network is started. */
public final class PackageProbe {
    public static void main(String[] args) throws Exception {
        String name = ApplicationNamesInfo.getInstance().getFullProductName();
        Path home = Path.of(PathManager.getHomePath()).toRealPath();
        if (!"GoForge".equals(name)) throw new IllegalStateException("Unexpected product: " + name);
        if (!StatisticsUploadAssistant.isDisableCollectStatistics()
                || !StatisticsUploadAssistant.isSuppressStatisticsReport()
                || Boolean.getBoolean("idea.headless.enable.statistics")) {
            throw new IllegalStateException("GoForge usage telemetry must be disabled by product configuration");
        }
        if (!"false".equals(System.getProperty("idea.ultimate.features.hints.enabled"))) {
            throw new IllegalStateException("GoForge must disable upstream commercial feature promotions by default");
        }
        var consents = ConsentOptions.getInstance();
        if (consents.isSendingUsageStatsAllowed() != ConsentOptions.Permission.UNDEFINED
                || consents.getAiDataCollectionPermission() != ConsentOptions.Permission.UNDEFINED
                || consents.isEAPFeedbackAllowed() != ConsentOptions.Permission.UNDEFINED
                || !consents.getConsents().getFirst().isEmpty()) {
            throw new IllegalStateException("GoForge must not enroll users in upstream data collection");
        }
        String descriptor = "META-INF/" + System.getProperty("idea.platform.prefix", "") + "Plugin.xml";
        try (var input = PackageProbe.class.getClassLoader().getResourceAsStream(descriptor)) {
            if (input == null || !new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .contains("<id>com.intellij</id>")) {
                throw new IllegalStateException("Missing Community main plugin descriptor: " + descriptor);
            }
        }
        for (String tool : new String[]{"go", "gopls", "dlv"}) {
            Path binary = home.resolve("goforge-tools/bin").resolve(tool);
            if (!Files.isExecutable(binary)) throw new IllegalStateException("Missing bundled tool: " + binary);
            if (!binary.toRealPath().startsWith(home)) throw new IllegalStateException("External tool dependency: " + binary);
        }
        System.out.println("product=" + name);
        System.out.println("home=" + home);
        System.out.println("config=" + PathManager.getConfigPath());
        System.out.println("system=" + PathManager.getSystemPath());
        System.out.println("plugins=" + PathManager.getPluginsPath());
        System.out.println("log=" + PathManager.getLogPath());
        System.out.println("bundledTools=go,gopls,dlv");
        System.out.println("mainPluginDescriptor=" + descriptor);
        System.out.println("usageTelemetry=collection-disabled,reporting-disabled; no user consent written");
        System.out.println("commercialFeaturePromotions=disabled-by-product-default");
    }
}
