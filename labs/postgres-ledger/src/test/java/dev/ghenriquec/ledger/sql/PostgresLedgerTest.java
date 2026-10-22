package dev.ghenriquec.ledger.sql;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PostgresLedgerTest {
    private String url;
    private String user;
    private String password;
    private PostgresLedger ledger;

    @BeforeEach
    void resetDisposableDatabase() throws Exception {
        url = System.getenv("LEDGER_JDBC_URL");
        user = System.getenv("LEDGER_DB_USER");
        password = System.getenv("LEDGER_DB_PASSWORD");
        assertNotNull(url, "Set LEDGER_JDBC_URL to a disposable local PostgreSQL database");
        assertNotNull(user, "Set LEDGER_DB_USER");
        assertNotNull(password, "Set LEDGER_DB_PASSWORD");
        assertEquals("I_ACCEPT_TABLE_RESET", System.getenv("LEDGER_TEST_RESET_CONFIRMATION"),
                "Explicitly confirm that these destructive tests use a disposable database");
        boolean loopback = url.startsWith("jdbc:postgresql://localhost:")
                || url.startsWith("jdbc:postgresql://127.0.0.1:");
        assertTrue(loopback && url.endsWith("/ledger_test"),
                "Destructive tests require a local database named ledger_test");

        // This test drops these tables. Never supply production credentials.
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS transfer_requests");
            statement.execute("DROP TABLE IF EXISTS accounts");
            statement.execute("""
                    CREATE TABLE accounts (
                        id VARCHAR(128) PRIMARY KEY,
                        balance_minor BIGINT NOT NULL CHECK (balance_minor >= 0)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE transfer_requests (
                        idempotency_key VARCHAR(128) PRIMARY KEY,
                        from_id VARCHAR(128) NOT NULL REFERENCES accounts(id),
                        to_id VARCHAR(128) NOT NULL REFERENCES accounts(id),
                        amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
                        sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE NOT NULL,
                        CHECK (from_id <> to_id)
                    )
                    """);
        }
        ledger = new PostgresLedger(url, user, password);
    }

    @Test
    void persistsAtomicTransferAcrossSeparateClients() throws Exception {
        ledger.openAccount("alice", 2_000);
        ledger.openAccount("bob", 100);

        var receipt = ledger.transfer("request-1", "alice", "bob", 75);
        var differentProcessClient = new PostgresLedger(url, user, password);

        assertEquals(1_925, differentProcessClient.balance("alice"));
        assertEquals(175, differentProcessClient.balance("bob"));
        assertEquals(receipt, differentProcessClient.transfer("request-1", "alice", "bob", 75));
        assertEquals(175, ledger.balance("bob"));
    }

    @Test
    void rejectsConflictingKeyWithoutChangingAccounts() throws Exception {
        ledger.openAccount("alice", 100);
        ledger.openAccount("bob", 0);
        ledger.transfer("same", "alice", "bob", 10);

        assertThrows(IllegalArgumentException.class,
                () -> ledger.transfer("same", "alice", "bob", 11));
        assertEquals(90, ledger.balance("alice"));
        assertEquals(10, ledger.balance("bob"));
    }

    @Test
    void failedTransferRollsBackBalanceAndIdempotencyRecord() throws Exception {
        ledger.openAccount("alice", 0);
        ledger.openAccount("bob", 0);
        ledger.openAccount("funding", 30);

        assertThrows(PostgresLedger.InsufficientFundsException.class,
                () -> ledger.transfer("retry", "alice", "bob", 10));
        ledger.transfer("top-up", "funding", "alice", 20);
        ledger.transfer("retry", "alice", "bob", 10);

        assertEquals(10, ledger.balance("alice"));
        assertEquals(10, ledger.balance("bob"));
    }

    @Test
    void overflowRollsBackAllWrites() throws Exception {
        ledger.openAccount("alice", 20);
        ledger.openAccount("bob", Long.MAX_VALUE);
        assertThrows(ArithmeticException.class,
                () -> ledger.transfer("overflow", "alice", "bob", 1));
        assertEquals(20, ledger.balance("alice"));
        assertEquals(Long.MAX_VALUE, ledger.balance("bob"));
    }

    @Test
    void concurrentSameKeyCommitsOnlyOneDebit() throws Exception {
        ledger.openAccount("alice", 1_000);
        ledger.openAccount("bob", 0);
        ExecutorService workers = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<PostgresLedger.TransferReceipt>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                results.add(workers.submit(() -> {
                    start.await();
                    return ledger.transfer("shared-key", "alice", "bob", 37);
                }));
            }
            start.countDown();
            var expected = results.getFirst().get(30, TimeUnit.SECONDS);
            for (var future : results) {
                assertEquals(expected, future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(963, ledger.balance("alice"));
            assertEquals(37, ledger.balance("bob"));
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void opposingRequestsDoNotDeadlockAndPreserveTotal() throws Exception {
        ledger.openAccount("a", 5_000);
        ledger.openAccount("b", 5_000);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int worker = i;
                results.add(workers.submit(() -> {
                    start.await();
                    for (int j = 0; j < 20; j++) {
                        String from = worker % 2 == 0 ? "a" : "b";
                        String to = worker % 2 == 0 ? "b" : "a";
                        ledger.transfer(worker + ":" + j, from, to, 1);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : results) {
                f.get(30, TimeUnit.SECONDS);
            }
            assertEquals(5_000, ledger.balance("a"));
            assertEquals(5_000, ledger.balance("b"));
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void concurrentDifferentKeysCannotOverdrawOneAccount() throws Exception {
        ledger.openAccount("a", 25);
        ledger.openAccount("b", 0);
        ExecutorService workers = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                int id = i;
                results.add(workers.submit(() -> {
                    start.await();
                    try {
                        ledger.transfer("overdraft-" + id, "a", "b", 1);
                        return true;
                    } catch (PostgresLedger.InsufficientFundsException expected) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int successful = 0;
            for (Future<Boolean> f : results) {
                if (f.get(30, TimeUnit.SECONDS)) {
                    successful++;
                }
            }
            assertEquals(25, successful);
            assertEquals(0, ledger.balance("a"));
            assertEquals(25, ledger.balance("b"));
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }
}
