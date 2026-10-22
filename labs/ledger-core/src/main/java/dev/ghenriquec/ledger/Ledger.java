package dev.ghenriquec.ledger;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory transfer laboratory. Not a bank, a payment gateway, or durable storage.
 *
 * <p>Key guarantees within one JVM: no partial transfers; an idempotency key
 * applies at most once to the same payload; opposite-direction transfers cannot
 * deadlock. See ARCHITECTURE.md for limits and explicit trade-offs.</p>
 */
public final class Ledger {
    private final ConcurrentMap<String, Account> accounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final AtomicLong sequences = new AtomicLong();

    /**
     * Creates an immutable account identity with a nonnegative starting balance.
     */
    public void openAccount(String id, long openingBalance) {
        requireText(id, "account id");
        if (openingBalance < 0) {
            throw new IllegalArgumentException("Opening balance cannot be negative");
        }
        if (accounts.putIfAbsent(id, new Account(openingBalance)) != null) {
            throw new IllegalArgumentException("Account already exists: " + id);
        }
    }

    /**
     * Returns a balance with visibility guaranteed by the account lock.
     * It is not a consistent cross-account snapshot.
     */
    public long balance(String id) {
        Account account = account(id);
        account.lock.lock();
        try {
            return account.balance;
        } finally {
            account.lock.unlock();
        }
    }

    /**
     * Transfers positive minor currency units between two known accounts.
     * Successful idempotency keys are retained for the lifetime of this Ledger.
     * A failed attempt releases its key so a caller can retry.
     */
    public TransferReceipt transfer(String key, String from, String to, long amount) {
        requireText(key, "idempotency key");
        requireText(from, "source account");
        requireText(to, "destination account");
        if (from.equals(to)) {
            throw new IllegalArgumentException("Source and destination must differ");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        Intent intent = new Intent(from, to, amount);
        Attempt mine = new Attempt(intent);
        Attempt existing = attempts.putIfAbsent(key, mine);
        if (existing != null) {
            if (!existing.intent.equals(intent)) {
                throw new IllegalArgumentException("Idempotency key reused with different payload");
            }
            // Wait for the command's first execution, whether it succeeds or fails.
            // CompletionException preserves the underlying failure as its cause.
            return existing.result.join();
        }

        try {
            TransferReceipt receipt = moveFunds(key, intent);
            mine.result.complete(receipt);
            return receipt;
        } catch (RuntimeException | Error failure) {
            mine.result.completeExceptionally(failure);
            attempts.remove(key, mine);
            throw failure;
        }
    }

    private TransferReceipt moveFunds(String key, Intent intent) {
        Account source = account(intent.from);
        Account destination = account(intent.to);

        // Global ordering of lock acquisition prevents cyclic waits.
        boolean sourceFirst = intent.from.compareTo(intent.to) < 0;
        Account first = sourceFirst ? source : destination;
        Account second = sourceFirst ? destination : source;

        first.lock.lock();
        try {
            second.lock.lock();
            try {
                if (source.balance < intent.amount) {
                    throw new InsufficientFundsException(intent.from);
                }
                long updatedDestination = Math.addExact(destination.balance, intent.amount);
                // Compute all values before mutating either balance.
                long updatedSource = Math.subtractExact(source.balance, intent.amount);
                long sequence = sequences.incrementAndGet();
                source.balance = updatedSource;
                destination.balance = updatedDestination;
                return new TransferReceipt(key, intent.from, intent.to, intent.amount, sequence);
            } finally {
                second.lock.unlock();
            }
        } finally {
            first.lock.unlock();
        }
    }

    private Account account(String id) {
        requireText(id, "account id");
        Account account = accounts.get(id);
        if (account == null) {
            throw new IllegalArgumentException("Unknown account: " + id);
        }
        return account;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public record TransferReceipt(String key, String from, String to, long amount, long sequence) {
        public TransferReceipt {
            Objects.requireNonNull(key);
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
        }
    }

    public static final class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException(String account) {
            super("Insufficient funds in account: " + account);
        }
    }

    private record Intent(String from, String to, long amount) {
    }

    private static final class Attempt {
        private final Intent intent;
        private final CompletableFuture<TransferReceipt> result = new CompletableFuture<>();

        private Attempt(Intent intent) {
            this.intent = intent;
        }
    }

    private static final class Account {
        private final ReentrantLock lock = new ReentrantLock();
        private long balance;

        private Account(long openingBalance) {
            this.balance = openingBalance;
        }
    }
}
