package pl.rynek.payment.common;

/** Kwota w groszach do komunikatów: 38900 → „389,00 zł”. */
public final class Zloty {

    private Zloty() {
    }

    public static String format(long amount) {
        String sign = amount < 0 ? "-" : "";
        long abs = Math.abs(amount);
        return String.format("%s%d,%02d zł", sign, abs / 100, abs % 100);
    }
}
