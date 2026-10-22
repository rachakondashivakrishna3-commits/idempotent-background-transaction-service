package assessment;

import java.math.BigDecimal;
import java.util.*;
import java.util.logging.Logger;

/** In-memory reference implementation. Each state/effect update is synchronized and atomic in this process. */
public final class TransactionProcessor {
    private static final Logger LOG = Logger.getLogger(TransactionProcessor.class.getName());
    private final int maxAttempts;
    private final Map<String, TransactionStatus> transactionStates = new LinkedHashMap<>();
    private final Map<String, TransactionStatus> requestStates = new HashMap<>();
    private final Map<String, BigDecimal> balances = new HashMap<>();
    private final Map<String, Long> nextSequence = new HashMap<>();
    private final Map<String, NavigableMap<Long, TransactionRequest>> pendingByAccount = new HashMap<>();
    private final List<ProcessingResult> results = new ArrayList<>();
    private final Set<String> completedIds = new HashSet<>();
    private final Set<String> seenTransactionIds = new HashSet<>();

    public TransactionProcessor(int maxAttempts) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
        this.maxAttempts = maxAttempts;
    }

    public synchronized ProcessingResult accept(TransactionRequest request) {
        String invalid = validate(request);
        if (invalid != null) return record(request, TransactionStatus.FAILED, 0, "Validation: " + invalid);
        if (seenTransactionIds.contains(request.transactionId()) || requestStates.containsKey(request.requestId()))
            return record(request, TransactionStatus.DUPLICATE, 0, "Request or business transaction already seen");
        seenTransactionIds.add(request.transactionId());
        transactionStates.put(request.transactionId(), TransactionStatus.RECEIVED);
        requestStates.put(request.requestId(), TransactionStatus.RECEIVED);
        long expected = nextSequence.computeIfAbsent(request.accountId(), ignored -> 1L);
        if (request.sequenceNumber() < expected)
            return record(request, TransactionStatus.DUPLICATE, 0, "Sequence is older than the next expected value");
        if (request.sequenceNumber() > expected) {
            NavigableMap<Long, TransactionRequest> pending = pendingByAccount.computeIfAbsent(request.accountId(), ignored -> new TreeMap<>());
            TransactionRequest conflict = pending.putIfAbsent(request.sequenceNumber(), request);
            if (conflict != null) return record(request, TransactionStatus.FAILED, 0, "Another transaction already occupies this account sequence");
            setStatus(request, TransactionStatus.PENDING_ORDER);
            return record(request, TransactionStatus.PENDING_ORDER, 0, "Waiting for sequence " + expected);
        }
        ProcessingResult first = processInOrder(request);
        drain(request.accountId());
        return first;
    }

    private void drain(String account) {
        NavigableMap<Long, TransactionRequest> pending = pendingByAccount.get(account);
        if (pending == null) return;
        while (true) {
            TransactionRequest next = pending.remove(nextSequence.getOrDefault(account, 1L));
            if (next == null) break;
            processInOrder(next);
        }
        if (pending.isEmpty()) pendingByAccount.remove(account);
    }

    private ProcessingResult processInOrder(TransactionRequest request) {
        setStatus(request, TransactionStatus.PROCESSING);
        int attempts = 0;
        while (attempts < maxAttempts) {
            attempts++;
            try {
                // Deterministic fault injection for the demo; a production adapter would throw real transient errors.
                if (attempts <= request.transientFailuresBeforeSuccess()) throw new TransientFailure();
                BigDecimal oldBalance = balances.getOrDefault(request.accountId(), BigDecimal.ZERO);
                BigDecimal newBalance = switch (request.type()) {
                    case CREDIT, REVERSAL -> oldBalance.add(request.amount());
                    case DEBIT -> oldBalance.subtract(request.amount());
                };
                balances.put(request.accountId(), newBalance);
                completedIds.add(request.transactionId());
                nextSequence.put(request.accountId(), request.sequenceNumber() + 1);
                return record(request, TransactionStatus.PROCESSED, attempts, "Applied once; balance=" + newBalance);
            } catch (TransientFailure e) {
                if (attempts < maxAttempts) {
                    setStatus(request, TransactionStatus.RETRY_PENDING);
                    LOG.info("retry_pending transaction=" + request.transactionId() + " attempt=" + attempts);
                }
            } catch (RuntimeException e) {
                nextSequence.put(request.accountId(), request.sequenceNumber() + 1);
                return record(request, TransactionStatus.FAILED, attempts, "Processing failed safely");
            }
        }
        nextSequence.put(request.accountId(), request.sequenceNumber() + 1);
        return record(request, TransactionStatus.FAILED, attempts, "Retry limit exhausted");
    }

    private String validate(TransactionRequest r) {
        if (r == null) return "record is null";
        if (blank(r.transactionId()) || blank(r.requestId()) || blank(r.accountId())) return "transactionId, requestId and accountId are required";
        if (r.type() == null) return "transactionType is required";
        if (r.amount() == null || r.amount().signum() <= 0) return "amount must be positive";
        if (blank(r.currency()) || !r.currency().matches("[A-Z]{3}")) return "currency must be a three-letter uppercase code";
        if (r.sequenceNumber() < 1) return "sequenceNumber must be positive";
        if (r.transientFailuresBeforeSuccess() < 0) return "transient failure count cannot be negative";
        return null;
    }
    private boolean blank(String s) { return s == null || s.isBlank(); }
    private void setStatus(TransactionRequest r, TransactionStatus s) {
        transactionStates.put(r.transactionId(), s); requestStates.put(r.requestId(), s);
    }
    private ProcessingResult record(TransactionRequest r, TransactionStatus s, int attempts, String message) {
        if (r != null && r.transactionId() != null && (s != TransactionStatus.DUPLICATE || !seenTransactionIds.contains(r.transactionId()))) transactionStates.put(r.transactionId(), s);
        if (r != null && r.requestId() != null) requestStates.put(r.requestId(), s);
        // Log identifiers and status only; avoid account and amount in logs.
        LOG.info("transaction=" + tid(r) + " status=" + s + " attempts=" + attempts);
        ProcessingResult result = new ProcessingResult(tid(r), rid(r), aid(r), s, attempts, message);
        results.add(result); return result;
    }
    private String tid(TransactionRequest r) { return r == null ? null : r.transactionId(); }
    private String rid(TransactionRequest r) { return r == null ? null : r.requestId(); }
    private String aid(TransactionRequest r) { return r == null ? null : r.accountId(); }
    private static final class TransientFailure extends RuntimeException { }

    public synchronized BigDecimal balance(String account) { return balances.getOrDefault(account, BigDecimal.ZERO); }
    public synchronized Map<String, Long> summary() {
        Map<String, Long> counts = new EnumMap<>(TransactionStatus.class);
        for (TransactionStatus s : TransactionStatus.values()) counts.put(s, 0L);
        for (TransactionStatus status : requestStates.values()) counts.compute(status, (k, v) -> v + 1);
        return Collections.unmodifiableMap(counts);
    }
    public synchronized List<ProcessingResult> results() { return List.copyOf(results); }
    public synchronized TransactionStatus statusOf(String id) { return transactionStates.get(id); }
}





