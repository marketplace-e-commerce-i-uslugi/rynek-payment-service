package pl.rynek.payment.payouts;

import java.math.BigInteger;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Numer IBAN: bez spacji i myślników, wielkimi literami, z sumą kontrolną mod 97. Sam numer polskiego rachunku
 * (26 cyfr) dostaje prefiks {@code PL}. Polski IBAN ma 28 znaków.
 */
final class Iban {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s-]");
    private static final Pattern DOMESTIC = Pattern.compile("\\d{26}");
    private static final Pattern SHAPE = Pattern.compile("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}");
    private static final Pattern GROUPS = Pattern.compile("(.{4})(?!$)");
    private static final BigInteger MOD = BigInteger.valueOf(97);

    private Iban() {
    }

    static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String iban = SEPARATORS.matcher(raw).replaceAll("").toUpperCase();
        if (DOMESTIC.matcher(iban).matches()) {
            iban = "PL" + iban;
        }
        if (!SHAPE.matcher(iban).matches() || (iban.startsWith("PL") && iban.length() != 28)) {
            return Optional.empty();
        }
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return new BigInteger(digits.toString()).mod(MOD).intValue() == 1 ? Optional.of(iban) : Optional.empty();
    }

    /** {@code PL61109010140000071219812874} → {@code PL61 1090 1014 0000 0712 1981 2874}. */
    static String format(String iban) {
        return GROUPS.matcher(iban).replaceAll("$1 ");
    }
}
