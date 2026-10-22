package assessment;

import java.math.BigDecimal;

public record TransactionRequest(String transactionId, String requestId, String accountId,
        TransactionType type, BigDecimal amount, String currency, long sequenceNumber,
        int transientFailuresBeforeSuccess) {
    public enum TransactionType { CREDIT, DEBIT, REVERSAL }
}
