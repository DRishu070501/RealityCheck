package com.realitycheck.common.system;

import com.realitycheck.common.config.RealityCheckProperties;
import com.realitycheck.common.system.SystemInfoResponse.Ai;
import com.realitycheck.common.system.SystemInfoResponse.Database;
import com.realitycheck.common.system.SystemInfoResponse.Migrations;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Clock;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

/**
 * Reports what the running backend is connected to. Everything is read live; a database outage
 * is reported as {@code DOWN} rather than failing the request, so the UI can still explain it.
 */
@Service
public class SystemInfoService {

    private static final Logger log = LoggerFactory.getLogger(SystemInfoService.class);
    private static final String APPLICATION_NAME = "RealityCheck";
    private static final String UNKNOWN_VERSION = "dev";

    private final DataSource dataSource;
    private final Flyway flyway;
    private final Clock clock;
    private final RealityCheckProperties properties;
    private final String version;

    public SystemInfoService(DataSource dataSource, Flyway flyway, Clock clock,
            RealityCheckProperties properties, ObjectProvider<BuildProperties> buildProperties) {
        this.dataSource = dataSource;
        this.flyway = flyway;
        this.clock = clock;
        this.properties = properties;
        BuildProperties build = buildProperties.getIfAvailable();
        this.version = build == null ? UNKNOWN_VERSION : build.getVersion();
    }

    public SystemInfoResponse current() {
        Database database = readDatabase();
        Migrations migrations = Database.UP.equals(database.status()) ? readMigrations() : new Migrations(0, null);
        Ai ai = new Ai(properties.ai().enabled(), properties.ai().provider());
        return new SystemInfoResponse(APPLICATION_NAME, version, clock.instant(), database, migrations, ai,
                new SystemInfoResponse.Demo(properties.demo().resetEnabled()));
    }

    private Database readDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            return new Database(Database.UP, metaData.getDatabaseProductName(), metaData.getDatabaseProductVersion());
        } catch (SQLException ex) {
            log.warn("Database is not reachable: {}", ex.getMessage());
            return new Database(Database.DOWN, null, null);
        }
    }

    private Migrations readMigrations() {
        try {
            MigrationInfoService info = flyway.info();
            MigrationInfo current = info.current();
            String currentVersion = current == null || current.getVersion() == null
                    ? null
                    : current.getVersion().getVersion();
            return new Migrations(info.applied().length, currentVersion);
        } catch (FlywayException ex) {
            log.warn("Migration state could not be read: {}", ex.getMessage());
            return new Migrations(0, null);
        }
    }
}
