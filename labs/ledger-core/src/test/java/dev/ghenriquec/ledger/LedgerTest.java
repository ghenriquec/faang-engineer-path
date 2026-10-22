package dev.ghenriquec.ledger;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LedgerTest {

    @Test
    void transfersMinorUnitsWithoutLosingMoney() {
        Ledger ledger = new Ledger();
        ledger.openAccount("alice", 10_000);
        ledger.openAccount("bob", 500);

        Ledger.TransferReceipt receipt = ledger.transfer("payment-1", "alice", "bob", 125);

        assertEquals(9_875, ledger.balance("alice"));
        assertEquals(625, ledger.balance("bob"));
        assertEquals("payment-1", receipt.key());
        assertEquals(1, receipt.sequence());
    }

    @Test
    void duplicateCommandReturnsSameReceiptAndDoesNotDebitTwice() {
        Ledger ledger = new Ledger();
        ledger.openAccount("a", 100);
        ledger.openAccount("b", 0);

        var first = ledger.transfer("same", "a", "b", 40);
        var again = ledger.transfer("same", "a", "b", 40);

        assertEquals(first, again);
        assertEquals(60, ledger.balance("a"));
        assertEquals(40, ledger.balance("b"));
    }

    @Test
    void rejectsKeyReuseForDifferentIntent() {
        Ledger ledger = new Ledger();
        ledger.openAccount("a", 100);
        ledger.openAccount("b", 0);
        ledger.transfer("key", "a", "b", 10);

        assertThrows(IllegalArgumentException.class,
                () -> ledger.transfer("key", "a", "b", 11));
        assertEquals(90, ledger.balance("a"));
    }

    @Test
    void insufficientFundsNeverPartiallyAppliesAndFailedKeyMayRetry() {
        Ledger ledger = new Ledger();
        ledger.openAccount("a", 0);
        ledger.openAccount("b", 0);
        ledger.openAccount("funding", 25);

        assertThrows(Ledger.InsufficientFundsException.class,
                () -> ledger.transfer("retry", "a", "b", 10));
        ledger.transfer("fund", "funding", "a", 20);

        var receipt = ledger.transfer("retry", "a", "b", 10);
        assertEquals("retry", receipt.key());
        assertEquals(10, ledger.balance("a"));
        assertEquals(10, ledger.balance("b"));
    }

    @Test
    void destinationOverflowDoesNotMutateEitherAccount() {
        Ledger ledger = new Ledger();
        ledger.openAccount("a", 10);
        ledger.openAccount("b", Long.MAX_VALUE);
        assertThrows(ArithmeticException.class,
                () -> ledger.transfer("overflow", "a", "b", 1));
        assertEquals(10, ledger.balance("a"));
        assertEquals(Long.MAX_VALUE, ledger.balance("b"));
    }

    @Test
    void refusesInvalidAccountsAndAmounts() {
        Ledger ledger = new Ledger();
        assertThrows(IllegalArgumentException.class, () -> ledger.openAccount("a", -1));
        ledger.openAccount("a", 10);
        ledger.openAccount("b", 0);
        assertThrows(IllegalArgumentException.class, () -> ledger.openAccount("a", 1));
        assertThrows(IllegalArgumentException.class, () -> ledger.transfer("x", "a", "a", 1));
        assertThrows(IllegalArgumentException.class, () -> ledger.transfer("x", "a", "b", 0));
        assertThrows(IllegalArgumentException.class, () -> ledger.transfer("x", "a", "missing", 1));
    }

    @Test
    void competingRequestsWithOneKeyApplyOnlyOnce() throws Exception {
        Ledger ledger = new Ledger();
        ledger.openAccount("a", 100_000);
        ledger.openAccount("b", 0);
        int requests = 20;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Future<Ledger.TransferReceipt>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return ledger.transfer("race-key", "a", "b", 77);
                }));
            }
            start.countDown();
            Ledger.TransferReceipt expected = results.getFirst().get(15, TimeUnit.SECONDS);
            for (var future : results) {
                assertEquals(expected, future.get(15, TimeUnit.SECONDS));
            }
            assertEquals(100_000 - 77, ledger.balance("a"));
            assertEquals(77, ledger.balance("b"));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDifferentKeysCannotOverdrawAnAccount() throws Exception {
        Ledger ledger = new Ledger();
        ledger.openAccount("debit", 50);
        ledger.openAccount("credit", 0);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Boolean>> outcomes = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                int requestId = i;
                outcomes.add(pool.submit(() -> {
                    start.await();
                    try {
                        ledger.transfer("overdraft-" + requestId, "debit", "credit", 1);
                        return true;
                    } catch (Ledger.InsufficientFundsException expected) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> outcome : outcomes) {
                if (outcome.get(20, TimeUnit.SECONDS)) {
                    succeeded++;
                }
            }
            assertEquals(50, succeeded);
            assertEquals(0, ledger.balance("debit"));
            assertEquals(50, ledger.balance("credit"));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void oppositeDirectionTransfersCompleteWithoutDeadlockOrMoneyCreation() throws Exception {
        Ledger ledger = new Ledger();
        ledger.openAccount("a", 1_000_000);
        ledger.openAccount("b", 1_000_000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                int workerId = worker;
                jobs.add(pool.submit(() -> {
                    start.await();
                    for (int transfer = 0; transfer < 100; transfer++) {
                        String from = workerId % 2 == 0 ? "a" : "b";
                        String to = workerId % 2 == 0 ? "b" : "a";
                        ledger.transfer(workerId + "-" + transfer, from, to, 1);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var job : jobs) {
                job.get(20, TimeUnit.SECONDS);
            }
            assertEquals(2_000_000, ledger.balance("a") + ledger.balance("b"));
            assertEquals(1_000_000, ledger.balance("a"));
            assertEquals(1_000_000, ledger.balance("b"));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }
}
