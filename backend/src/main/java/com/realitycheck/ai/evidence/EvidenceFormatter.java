package com.realitycheck.ai.evidence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a stored value into the strings a provider may quote, so that no provider ever needs to
 * convert or round: seconds also as hours and days, fractions as percentages, money grouped with
 * its currency code. Every number in these strings is accepted by the validator.
 *
 * <p>Deterministic: the same value and unit always give the same strings.
 */
public final class EvidenceFormatter {

    public static final String SECONDS = "seconds";
    public static final String FRACTION = "fraction";
    public static final String PERCENT = "percent";
    public static final String CURRENCY = "currency";
    public static final String CURRENCY_PER_STONE = "currency/stone";
    public static final String STONES = "stones";
    public static final String CARATS = "carats";
    public static final String STONES_PER_HOUR = "stones/hour";
    public static final String ORDERS = "orders";
    public static final String JOBS = "jobs";
    public static final String UNITS = "units";
    public static final String SCORE = "score";

    private static final BigDecimal MINUTE = BigDecimal.valueOf(60);
    private static final BigDecimal HOUR = BigDecimal.valueOf(3600);
    private static final BigDecimal DAY = BigDecimal.valueOf(86_400);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final DateTimeFormatter INSTANT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private EvidenceFormatter() {
    }

    /**
     * @param delta the value is a difference (alternative minus baseline): it is signed, and a
     *              difference of fractions is shown in percentage points
     */
    public static List<String> display(BigDecimal value, String unit, String currencyCode, boolean delta) {
        if (value == null) {
            return List.of();
        }
        String sign = delta && value.signum() > 0 ? "+" : "";
        List<String> out = new ArrayList<>();
        switch (unit) {
            case SECONDS -> {
                BigDecimal hours = value.divide(HOUR, 1, RoundingMode.HALF_UP);
                out.add(sign + decimal(hours, 1) + " hours");
                if (value.abs().compareTo(BigDecimal.valueOf(7200)) < 0) {
                    out.add(sign + decimal(value.divide(MINUTE, 0, RoundingMode.HALF_UP), 0) + " minutes");
                }
                if (value.abs().compareTo(DAY) >= 0) {
                    out.add(sign + decimal(value.divide(DAY, 1, RoundingMode.HALF_UP), 1) + " days");
                }
                out.add(sign + decimal(hours.setScale(0, RoundingMode.HALF_UP), 0) + " hours");
            }
            case FRACTION -> {
                BigDecimal percent = value.multiply(HUNDRED);
                String suffix = delta ? " percentage points" : "%";
                out.add(sign + decimal(percent.setScale(1, RoundingMode.HALF_UP), 1) + suffix);
                out.add(sign + decimal(percent.setScale(0, RoundingMode.HALF_UP), 0) + suffix);
            }
            case PERCENT -> {
                out.add(sign + decimal(value.setScale(1, RoundingMode.HALF_UP), 1) + "%");
                out.add(sign + decimal(value.setScale(0, RoundingMode.HALF_UP), 0) + "%");
            }
            case CURRENCY -> {
                out.add(sign + money(value.setScale(2, RoundingMode.HALF_UP), 2, currencyCode));
                out.add(sign + money(value.setScale(0, RoundingMode.HALF_UP), 0, currencyCode));
            }
            case CURRENCY_PER_STONE -> out.add(sign + money(value.setScale(2, RoundingMode.HALF_UP), 2, currencyCode)
                    + " per stone");
            case STONES -> out.add(sign + decimal(value.setScale(0, RoundingMode.HALF_UP), 0) + " stones");
            case CARATS -> out.add(sign + decimal(value.setScale(2, RoundingMode.HALF_UP), 2) + " carats");
            case STONES_PER_HOUR -> out.add(sign + decimal(value.setScale(1, RoundingMode.HALF_UP), 1)
                    + " stones per hour");
            case JOBS -> out.add(sign + decimal(value.setScale(1, RoundingMode.HALF_UP), 1) + " jobs");
            case SCORE -> out.add(sign + decimal(value.setScale(2, RoundingMode.HALF_UP), 2));
            case ORDERS -> out.add(sign + decimal(value.setScale(0, RoundingMode.HALF_UP), 0) + " orders");
            case UNITS -> out.add(sign + decimal(value.setScale(0, RoundingMode.HALF_UP), 0) + " units");
            default -> out.add(sign + value.stripTrailingZeros().toPlainString());
        }
        return out.stream().distinct().toList();
    }

    public static String instant(Instant instant) {
        return instant == null ? null : INSTANT.format(instant);
    }

    private static String money(BigDecimal value, int scale, String currencyCode) {
        String number = decimal(value, scale);
        return currencyCode == null || currencyCode.isBlank() ? number : currencyCode + " " + number;
    }

    /** Grouped with commas, fixed scale. */
    static String decimal(BigDecimal value, int scale) {
        DecimalFormat format = new DecimalFormat(scale == 0 ? "#,##0" : "#,##0." + "0".repeat(scale),
                DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value.setScale(scale, RoundingMode.HALF_UP));
    }
}
