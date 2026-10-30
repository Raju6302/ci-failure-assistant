package shop;

import java.math.BigDecimal;

public class InvoiceService {

    public BigDecimal invoiceTotal(Order order) {
        return order.items().stream()
                .map(item -> item.price().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
