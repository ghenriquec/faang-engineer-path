package dev.ghenriquec.ledger.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * JDBC/PostgreSQL transaction laboratory. Integration tests use a dedicated
 * disposable database. Not a payment processor or a production ledger.
 *
 * <p>Each transfer reserves its idempotency key, locks both accounts in a
 * canonical order, checks balances, and commits all changes as one database
 * transaction. See ARCHITECTURE.md for the guarantees and limitations.</p>
 */
public final class PostgresLedger {
    private final String url;
    private final String user;
    private final String password;

    public PostgresLedger(String url, String user, String password) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("JDBC URL required");
        }
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public void openAccount(String id, long initialMinor) throws SQLException {
        requireText(id, "account");
        if (initialMinor < 0) {
            throw new IllegalArgumentException("Opening balance must be nonnegative");
        }
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO accounts (id, balance_minor) VALUES (?, ?)")) {
            statement.setString(1, id);
            statement.setLong(2, initialMinor);
            statement.executeUpdate();
        }
    }

    public long balance(String id) throws SQLException {
        requireText(id, "account");
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT balance_minor FROM accounts WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Unknown account: " + id);
                }
                return result.getLong(1);
            }
        }
    }

    public TransferReceipt transfer(String key, String source, String destination, long amount)
            throws SQLException {
        requireText(key, "idempotency key");
        requireText(source, "source");
        requireText(destination, "destination");
        if (source.equals(destination)) {
            throw new IllegalArgumentException("Accounts must be different");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                Long sequence = reserve(connection, key, source, destination, amount);
                if (sequence == null) {
                    TransferReceipt replay = lookup(connection, key);
                    if (replay == null) {
                        throw new SQLException("Committed idempotency reservation not visible");
                    }
                    if (!replay.from().equals(source) || !replay.to().equals(destination)
                            || replay.amount() != amount) {
                        throw new IllegalArgumentException(
                                "Idempotency key reused with a different request");
                    }
                    connection.commit();
                    return replay;
                }

                // Consistent lock order: every transaction locks the
                // lexicographically smaller account before the larger one.
                String firstId = source.compareTo(destination) < 0 ? source : destination;
                String secondId = source.compareTo(destination) < 0 ? destination : source;
                long firstBalance = lockedBalance(connection, firstId);
                long secondBalance = lockedBalance(connection, secondId);
                long sourceBalance = source.equals(firstId) ? firstBalance : secondBalance;
                long destinationBalance = destination.equals(firstId) ? firstBalance : secondBalance;

                if (sourceBalance < amount) {
                    throw new InsufficientFundsException(source);
                }
                long newDestination = Math.addExact(destinationBalance, amount);
                long newSource = Math.subtractExact(sourceBalance, amount);

                updateBalance(connection, source, newSource);
                updateBalance(connection, destination, newDestination);
                connection.commit();
                return new TransferReceipt(key, source, destination, amount, sequence);
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    private Long reserve(Connection connection, String key, String from, String to, long amount)
            throws SQLException {
        String sql = """
                INSERT INTO transfer_requests (idempotency_key, from_id, to_id, amount_minor)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING sequence
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, from);
            statement.setString(3, to);
            statement.setLong(4, amount);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : null;
            }
        }
    }

    private TransferReceipt lookup(Connection connection, String key) throws SQLException {
        String sql = """
                SELECT from_id, to_id, amount_minor, sequence
                FROM transfer_requests WHERE idempotency_key = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new TransferReceipt(key, result.getString(1), result.getString(2),
                        result.getLong(3), result.getLong(4));
            }
        }
    }

    private long lockedBalance(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_minor FROM accounts WHERE id = ? FOR UPDATE")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Unknown account: " + id);
                }
                return result.getLong(1);
            }
        }
    }

    private void updateBalance(Connection connection, String id, long balance)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance_minor = ? WHERE id = ?")) {
            statement.setLong(1, balance);
            statement.setString(2, id);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Account was not updated: " + id);
            }
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private static void requireText(String text, String name) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
    }

    public record TransferReceipt(String key, String from, String to, long amount, long sequence) {}

    public static final class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException(String id) {
            super("Insufficient funds in: " + id);
        }
    }
}
