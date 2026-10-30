package shop;

import java.math.BigDecimal;
import java.util.List;

public record Order(String id, List<LineItem> items) {

    public record LineItem(String sku, int quantity, BigDecimal unitPrice) {

        public BigDecimal total() {
            return unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }
}
