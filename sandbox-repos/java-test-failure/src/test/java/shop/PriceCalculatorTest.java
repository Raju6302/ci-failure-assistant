package shop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PriceCalculatorTest {

    PriceCalculator calculator = new PriceCalculator();

    @Test
    void appliesPercentageDiscount() {
        assertEquals(90.0, calculator.applyDiscount(100.0, 10.0), 0.001);
    }

    @Test
    void zeroDiscountKeepsPrice() {
        assertEquals(100.0, calculator.applyDiscount(100.0, 0.0), 0.001);
    }
}
