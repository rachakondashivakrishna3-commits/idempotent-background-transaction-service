package assessment;

import java.math.BigDecimal;
import java.util.List;

public final class Main {
    private Main() { }
    private static TransactionRequest tx(String tid, String rid, String account, TransactionRequest.TransactionType type,
                                         String amount, long seq, int failures) {
        return new TransactionRequest(tid, rid, account, type, new BigDecimal(amount), "USD", seq, failures);
    }
    public static void main(String[] args) {
        TransactionProcessor processor = new TransactionProcessor(3);
        List<TransactionRequest> input = List.of(
            tx("TXN-2002", "REQ-2", "ACC-201", TransactionRequest.TransactionType.DEBIT, "40", 2, 0),
            tx("TXN-2001", "REQ-1", "ACC-201", TransactionRequest.TransactionType.CREDIT, "100", 1, 0),
            tx("TXN-2002", "REQ-2-DUP", "ACC-201", TransactionRequest.TransactionType.DEBIT, "40", 2, 0),
            tx("TXN-2003", "REQ-3", "ACC-201", TransactionRequest.TransactionType.CREDIT, "25", 3, 0),
            tx("TXN-2004", "REQ-4", "ACC-202", TransactionRequest.TransactionType.DEBIT, "-10", 1, 0),
            tx("TXN-2005", "REQ-5", "ACC-203", TransactionRequest.TransactionType.CREDIT, "500", 1, 1));
        for (TransactionRequest request : input) {
            ProcessingResult result = processor.accept(request);
            System.out.printf("%s,%s,%s,%d,%s%n", result.transactionId(), result.requestId(), result.status(), result.attempts(), result.message());
        }
        System.out.println("Summary: " + processor.summary());
        System.out.println("Balances: ACC-201=" + processor.balance("ACC-201") + ", ACC-203=" + processor.balance("ACC-203"));
    }
}
