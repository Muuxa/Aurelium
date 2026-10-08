package com.muuxa.aurelium.storage;

import java.math.BigInteger;
import java.util.Locale;

/**
 * Display-only AE2 equivalent bytes; the vault itself has no byte capacity limit.
 *
 * <p>Formatting is <b>always bounded</b>. Beyond QiB the IEC scale has no larger prefix,
 * and an uncapped integer part would grow without limit (and could overflow a
 * {@code writeUtf} budget on the wire). We therefore keep the integer QiB part while it
 * stays short, and switch to scientific notation once it would exceed
 * {@link #MAX_DIGITS} digits. The underlying BigInteger is never modified, and the
 * result length is guaranteed to stay well under the network string limit.</p>
 */
public final class VaultBytes {
    private static final BigInteger KIB = BigInteger.valueOf(1024);
    // IEC 2022 also defines robi/que bi; above QiB we keep the integer QiB part
    // rather than inventing units. Smaller units are hidden in this compact display;
    // the underlying BigInteger remains intact.
    private static final String[] UNITS = {
            "B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB", "ZiB", "YiB", "RiB", "QiB"
    };
    /** Largest plain digit count before switching to scientific notation. */
    private static final int MAX_DIGITS = 24;
    /** 10^MAX_DIGITS: threshold for the QiB integer part to switch to scientific form. */
    private static final BigInteger DIGIT_LIMIT = BigInteger.TEN.pow(MAX_DIGITS);

    private VaultBytes() {}

    /** Round each key up separately, matching AE2's per-type partial byte allocation. */
    public static BigInteger forKey(BigInteger amount, int amountPerByte) {
        if (amount == null || amount.signum() <= 0) return BigInteger.ZERO;
        BigInteger perByte = BigInteger.valueOf(Math.max(1, amountPerByte));
        return amount.subtract(BigInteger.ONE).divide(perByte).add(BigInteger.ONE);
    }

    /** Never use a cast to long/double; never let the string grow unbounded. */
    public static String format(BigInteger bytes) {
        if (bytes == null || bytes.signum() <= 0) return "0 B";
        BigInteger divisor = BigInteger.ONE;
        int unit = 0;
        while (unit < UNITS.length - 1 && bytes.compareTo(divisor.multiply(KIB)) >= 0) {
            divisor = divisor.multiply(KIB);
            unit++;
        }
        BigInteger integral = bytes.divide(divisor);
        // Once past the largest unit the integer keeps growing. Keep it human-readable
        // for a while, then fall back to scientific notation so the string stays bounded.
        // Compare against 10^MAX_DIGITS instead of stringifying a possibly gigantic number.
        if (unit == UNITS.length - 1 && integral.compareTo(DIGIT_LIMIT) >= 0) {
            return scientific(bytes);
        }
        if (unit == 0 || integral.compareTo(BigInteger.valueOf(100)) >= 0) {
            return group(integral) + " " + UNITS[unit];
        }
        BigInteger oneDecimal = bytes.remainder(divisor).multiply(BigInteger.TEN).divide(divisor);
        return group(integral) + (oneDecimal.signum() == 0 ? "" : "." + oneDecimal) + " " + UNITS[unit];
    }

    /**
     * Bounded scientific notation for arbitrarily large values, e.g. {@code 1.234e+300}.
     * The mantissa is read from the leading decimal digits only; the full value is never
     * laid out in the result.
     */
    private static String scientific(BigInteger bytes) {
        String digits = bytes.toString();
        int exponent = digits.length() - 1;
        String lead = digits.substring(0, 1);
        String frac = digits.substring(1, Math.min(digits.length(), 4));
        // Trim trailing zeros without under-running an empty string.
        while (!frac.isEmpty() && frac.endsWith("0")) frac = frac.substring(0, frac.length() - 1);
        return lead + (frac.isEmpty() ? "" : "." + frac) + "e+" + exponent + " B";
    }

    private static String group(BigInteger number) {
        // Only called when the digit count is already bounded.
        return String.format(Locale.ROOT, "%,d", number);
    }
}