package assessment;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class TransactionProcessorTest {
    private TransactionRequest tx(String id, String request, String account, TransactionRequest.TransactionType type,
                                 String amount, long sequence, int failures) {
        return new TransactionRequest(id, request, account, type, new BigDecimal(amount), "USD", sequence, failures);
    }
    @Test 
    void repeatedBusinessTransactionHasOneEffect() {
        var p = new TransactionProcessor(3);
        assertEquals(TransactionStatus.PROCESSED, p.accept(tx("T1", "R1", "A", TransactionRequest.TransactionType.CREDIT, "10", 1, 0)).status());
        assertEquals(TransactionStatus.DUPLICATE, p.accept(tx("T1", "R2", "A", TransactionRequest.TransactionType.CREDIT, "10", 2, 0)).status());
        assertEquals(new BigDecimal("10"), p.balance("A"));
    }
    @Test 
    void holdsFutureSequenceAndDrainsWhenGapArrives() {
        var p = new TransactionProcessor(3);
        assertEquals(TransactionStatus.PENDING_ORDER, p.accept(tx("T2", "R2", "A", TransactionRequest.TransactionType.DEBIT, "4", 2, 0)).status());
        p.accept(tx("T1", "R1", "A", TransactionRequest.TransactionType.CREDIT, "10", 1, 0));
        assertEquals(new BigDecimal("6"), p.balance("A"));
        assertEquals(TransactionStatus.PROCESSED, p.statusOf("T2"));
    }
    @Test 
    void rejectsInvalidAmountAndContinuesProcessing() {
        var p = new TransactionProcessor(3);
        assertEquals(TransactionStatus.FAILED, p.accept(tx("BAD", "RB", "A", TransactionRequest.TransactionType.DEBIT, "-1", 1, 0)).status());
        assertEquals(TransactionStatus.PROCESSED, p.accept(tx("GOOD", "RG", "B", TransactionRequest.TransactionType.CREDIT, "3", 1, 0)).status());
    }
    @Test 
    void retriesTransientFailureWithoutDuplicateEffect() {
        var p = new TransactionProcessor(3);
        var result = p.accept(tx("T1", "R1", "A", TransactionRequest.TransactionType.CREDIT, "10", 1, 1));
        assertEquals(TransactionStatus.PROCESSED, result.status());
        assertEquals(2, result.attempts());
        assertEquals(new BigDecimal("10"), p.balance("A"));
    }
    @Test 
    void marksExhaustedRetryFailed() {
        var p = new TransactionProcessor(2);
        var result = p.accept(tx("T1", "R1", "A", TransactionRequest.TransactionType.CREDIT, "10", 1, 5));
        assertEquals(TransactionStatus.FAILED, result.status());
        assertEquals(2, result.attempts());
        assertEquals(BigDecimal.ZERO, p.balance("A"));
    }
}
