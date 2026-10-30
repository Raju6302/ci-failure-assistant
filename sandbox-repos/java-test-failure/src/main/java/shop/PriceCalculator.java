package shop;

public class PriceCalculator {

    /** Applies a percentage discount, e.g. applyDiscount(100, 10) == 90. */
    public double applyDiscount(double price, double percent) {
        return price * (1 - percent);
    }
}
