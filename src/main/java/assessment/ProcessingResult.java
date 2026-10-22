package assessment;
public record ProcessingResult(String transactionId, String requestId, String accountId,
                               TransactionStatus status, int attempts, String message) { }
