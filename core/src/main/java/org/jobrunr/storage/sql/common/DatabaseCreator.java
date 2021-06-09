package org.jobrunr.storage.sql.common;

import org.jobrunr.JobRunrException;
import org.jobrunr.storage.sql.SqlStorageProvider;
import org.jobrunr.storage.sql.common.db.Transaction;
import org.jobrunr.storage.sql.common.migrations.SqlMigration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Stream;

import static java.util.Comparator.comparing;
import static org.jobrunr.utils.StringUtils.isNullOrEmpty;

public class DatabaseCreator {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseCreator.class);
    private static final String[] jobrunr_tables = new String[]{"jobrunr_jobs", "jobrunr_recurring_jobs", "jobrunr_backgroundjobservers", "jobrunr_metadata"};
    ;


    private final ConnectionProvider connectionProvider;
    private final String schemaName;
    private final DatabaseMigrationsProvider databaseMigrationsProvider;

    public static void main(String[] args) {
        String url = args[0];
        String userName = args[1];
        String password = args[2];
        String schemaName = args.length >= 4 ? args[3] : null;

        try {
            System.out.println("==========================================================");
            System.out.println("================== JobRunr Table Creator =================");
            System.out.println("==========================================================");
            new DatabaseCreator(() -> DriverManager.getConnection(url, userName, password), schemaName, new SqlStorageProviderFactory().getStorageProviderClassByJdbcUrl(url)).runMigrations();
            System.out.println("Successfully created all tables!");
        } catch (Exception e) {
            System.out.println("An error occurred: ");
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String exceptionAsString = sw.toString();
            System.out.println(exceptionAsString);
        }
    }

    protected DatabaseCreator(DataSource dataSource) {
        this(dataSource, null, null);
    }

    protected DatabaseCreator(DataSource dataSource, String schemaName) {
        this(dataSource, schemaName, null);
    }

    public DatabaseCreator(DataSource dataSource, Class<? extends SqlStorageProvider> sqlStorageProviderClass) {
        this(dataSource::getConnection, null, sqlStorageProviderClass);
    }

    public DatabaseCreator(DataSource dataSource, String schemaName, Class<? extends SqlStorageProvider> sqlStorageProviderClass) {
        this(dataSource::getConnection, schemaName, sqlStorageProviderClass);
    }

    public DatabaseCreator(ConnectionProvider connectionProvider, String schemaName, Class<? extends SqlStorageProvider> sqlStorageProviderClass) {
        this.connectionProvider = connectionProvider;
        this.schemaName = schemaName;
        this.databaseMigrationsProvider = new DatabaseMigrationsProvider(sqlStorageProviderClass);
    }

    public void runMigrations() {
        getMigrations()
                .filter(migration -> migration.getFileName().endsWith(".sql"))
                .sorted(comparing(SqlMigration::getFileName))
                .filter(this::isNewMigration)
                .forEach(this::runMigration);
    }

    public void validateTables() {
        try (final Connection conn = getConnection();
             final Transaction tran = new Transaction(conn);
             final Statement pSt = conn.createStatement()) {
            for (String table : jobrunr_tables) {
                try (ResultSet rs = pSt.executeQuery("select count(*) from " + getFQTableName(table))) {
                    if (rs.next()) {
                        int count = rs.getInt(1);
                    }
                }
            }
            tran.commit();
        } catch (Exception becauseTableDoesNotExist) {
            throw new JobRunrException("Not all required tables are available by JobRunr!");
        }
    }

    protected Stream<SqlMigration> getMigrations() {
        return databaseMigrationsProvider.getMigrations();
    }

    protected void runMigration(SqlMigration migration) {
        LOGGER.info("Running migration {}", migration);
        try (final Connection conn = getConnection(); final Transaction tran = new Transaction(conn)) {
            if (!isEmptyMigration(migration)) {
                runMigrationStatement(conn, migration);
            }
            updateMigrationsTable(conn, migration);
            tran.commit();
        } catch (Exception e) {
            throw JobRunrException.shouldNotHappenException(new IllegalStateException("Error running database migration " + migration.getFileName(), e));
        }
    }

    private boolean isEmptyMigration(SqlMigration migration) throws IOException {
        return migration.getMigrationSql().startsWith("-- Empty migration");
    }


    protected void runMigrationStatement(Connection connection, SqlMigration migration) throws IOException, SQLException {
        final String sql = migration.getMigrationSql();
        for (String statement : sql.split(";")) {
            try (final Statement stmt = connection.createStatement()) {
                stmt.execute(updateStatementWithSchemaName(statement));
            }
        }
    }

    protected void updateMigrationsTable(Connection connection, SqlMigration migration) throws SQLException {
        try (PreparedStatement pSt = connection.prepareStatement("insert into " + getFQTableName("jobrunr_migrations") + " values (?, ?, ?)")) {
            pSt.setString(1, UUID.randomUUID().toString());
            pSt.setString(2, migration.getFileName());
            pSt.setString(3, LocalDateTime.now().toString());
            pSt.execute();
        }
    }

    private boolean isNewMigration(SqlMigration migration) {
        return !isMigrationApplied(migration);
    }

    protected boolean isMigrationApplied(SqlMigration migration) {
        try (final Connection conn = getConnection();
             final Transaction tran = new Transaction(conn);
             final PreparedStatement pSt = conn.prepareStatement("select count(*) from " + getFQTableName("jobrunr_migrations") + " where script = ?")) {
            boolean result = false;
            pSt.setString(1, migration.getFileName());
            try (ResultSet rs = pSt.executeQuery()) {
                if (rs.next()) {
                    result = rs.getInt(1) == 1;
                }
            }
            tran.commit();
            return result;
        } catch (Exception becauseTableDoesNotExist) {
            return false;
        }
    }

    private String updateStatementWithSchemaName(String statement) {
        if (isNullOrEmpty(schemaName)) {
            return statement;
        }
        final String updatedStatement = statement.replace("jobrunr_", schemaName + ".jobrunr_");
        return updatedStatement;
    }

    private String getFQTableName(String tableName) {
        if (isNullOrEmpty(schemaName)) {
            return tableName;
        }
        return schemaName + "." + tableName;
    }

    private Connection getConnection() {
        try {
            return connectionProvider.getConnection();
        } catch (SQLException exception) {
            throw JobRunrException.shouldNotHappenException(exception);
        }
    }

    @FunctionalInterface
    private interface ConnectionProvider {

        Connection getConnection() throws SQLException;

    }

}
