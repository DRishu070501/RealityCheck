package com.realitycheck.common.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.realitycheck.common.config.RealityCheckProperties;
import com.realitycheck.common.config.RealityCheckProperties.Ai;
import com.realitycheck.common.config.RealityCheckProperties.Cors;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

class SystemInfoServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-02T04:30:00Z");

    private final DataSource dataSource = mock(DataSource.class);
    private final Flyway flyway = mock(Flyway.class);

    @Test
    void reportsDatabaseAndMigrationStateWhenConnected() throws SQLException {
        databaseIsUp("PostgreSQL", "17.2");
        migrationsApplied(3, "3");

        SystemInfoResponse info = service(Ai.of("none", null), "0.1.0").current();

        assertThat(info.application()).isEqualTo("RealityCheck");
        assertThat(info.version()).isEqualTo("0.1.0");
        assertThat(info.serverTime()).isEqualTo(NOW);
        assertThat(info.database()).isEqualTo(new SystemInfoResponse.Database("UP", "PostgreSQL", "17.2"));
        assertThat(info.migrations()).isEqualTo(new SystemInfoResponse.Migrations(3, "3"));
        assertThat(info.ai()).isEqualTo(new SystemInfoResponse.Ai(false, "template", null, true));
        assertThat(info.simulation().algorithmVersion()).isEqualTo("9.9.9");
    }

    @Test
    void reportsDownInsteadOfFailingWhenTheDatabaseIsUnreachable() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("Connection refused"));

        SystemInfoResponse info = service(Ai.of("none", null), "0.1.0").current();

        assertThat(info.database()).isEqualTo(new SystemInfoResponse.Database("DOWN", null, null));
        assertThat(info.migrations()).isEqualTo(new SystemInfoResponse.Migrations(0, null));
        verifyNoInteractions(flyway);
    }

    @Test
    void aiIsEnabledOnlyWithBothAProviderAndAKey() throws SQLException {
        databaseIsUp("PostgreSQL", "17.2");
        migrationsApplied(1, "1");

        assertThat(service(Ai.of("anthropic", "key"), "0.1.0").current().ai())
                .isEqualTo(new SystemInfoResponse.Ai(true, "anthropic", "claude-haiku-5-5", true));
        // Without a key the template analyst answers, and says so.
        assertThat(service(Ai.of("anthropic", " "), "0.1.0").current().ai())
                .isEqualTo(new SystemInfoResponse.Ai(false, "template", null, true));
        assertThat(service(Ai.of("none", "key"), "0.1.0").current().ai().enabled()).isFalse();
        assertThat(service(Ai.of("template", "key"), "0.1.0").current().ai().enabled()).isFalse();
    }

    @Test
    void fallsBackToADevVersionWithoutBuildMetadata() throws SQLException {
        databaseIsUp("PostgreSQL", "17.2");
        migrationsApplied(1, "1");

        assertThat(service(Ai.of("none", null), null).current().version()).isEqualTo("dev");
    }

    @Test
    void theApiKeyNeverAppearsInTheConfigurationsTextForm() {
        assertThat(Ai.of("anthropic", "sk-very-secret").toString())
                .doesNotContain("sk-very-secret")
                .contains("<set>");
    }

    private void databaseIsUp(String product, String version) throws SQLException {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn(product);
        when(metaData.getDatabaseProductVersion()).thenReturn(version);
    }

    private void migrationsApplied(int count, String currentVersion) {
        MigrationInfoService infoService = mock(MigrationInfoService.class);
        MigrationInfo current = mock(MigrationInfo.class);
        when(flyway.info()).thenReturn(infoService);
        when(infoService.applied()).thenReturn(new MigrationInfo[count]);
        when(infoService.current()).thenReturn(current);
        when(current.getVersion()).thenReturn(MigrationVersion.fromVersion(currentVersion));
    }

    @SuppressWarnings("unchecked")
    private SystemInfoService service(Ai ai, String buildVersion) {
        ObjectProvider<BuildProperties> buildProperties = mock(ObjectProvider.class);
        if (buildVersion != null) {
            Properties entries = new Properties();
            entries.setProperty("version", buildVersion);
            when(buildProperties.getIfAvailable()).thenReturn(new BuildProperties(entries));
        }
        RealityCheckProperties properties =
                new RealityCheckProperties(new Cors(List.of("http://localhost:5173")), ai,
                        new RealityCheckProperties.Demo(false, false, null));
        ObjectProvider<AlgorithmVersionSource> algorithm = mock(ObjectProvider.class);
        when(algorithm.getIfAvailable()).thenReturn(() -> "9.9.9");
        return new SystemInfoService(dataSource, flyway, Clock.fixed(NOW, ZoneOffset.UTC), properties, buildProperties,
                algorithm);
    }
}
