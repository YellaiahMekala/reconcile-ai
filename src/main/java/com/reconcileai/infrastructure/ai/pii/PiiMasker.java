package com.reconcileai.infrastructure.ai.pii;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pattern-based masking of structured identifiers before text leaves the process for a model provider.
 *
 * <h2>What it masks</h2>
 * <pre>
 *  EMAIL           name@domain.tld                         -> [EMAIL]
 *  IBAN            GB29NWBK60161331926819                   -> [IBAN]
 *  PHONE           +country code followed by 7-14 digits     -> [PHONE]
 *  CARD_NUMBER     13-19 digits, optional space/dash, Luhn  -> [CARD_NUMBER]
 *  ACCOUNT_NUMBER  a standalone run of 8-18 digits           -> [ACCOUNT_NUMBER]
 * </pre>
 *
 * <h2>What it deliberately does NOT touch</h2>
 * Amounts (separators, fewer digits), dates, business references such as PO-88410, and chunk ids (UUIDs): every
 * digit pattern requires that the match is not glued to a letter, digit or dash, so a UUID segment never matches
 * and citations still line up with the retrieval snapshot.
 *
 * <h2>Honest scope</h2>
 * Masking of structured identifiers, not DLP. Names and free-form disclosures pass through. Masking is one-way:
 * placeholders are never re-identified in the model output.
 */
public final class PiiMasker {

    public enum PiiType { EMAIL, IBAN, PHONE, CARD_NUMBER, ACCOUNT_NUMBER }

    /** The safe text and how many of each type were replaced. */
    public record MaskResult(String text, Map<PiiType, Integer> counts) {
        public int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern IBAN =
        Pattern.compile("(?<![A-Za-z0-9])[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?(?![A-Za-z0-9])");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w+])\\+\\d{1,3}[ -]?\\(?\\d{1,4}\\)?(?:[ -]?\\d){6,12}(?!\\w)");
    private static final Pattern CARD = Pattern.compile("(?<![\\w-])(?:\\d[ -]?){12,18}\\d(?![\\w-])");
    private static final Pattern ACCOUNT = Pattern.compile("(?<![\\w.,-])\\d{8,18}(?![\\w-]|[.,]\\d)");

    private PiiMasker() {}

    public static MaskResult mask(String input) {
        Map<PiiType, Integer> counts = new EnumMap<>(PiiType.class);
        if (input == null || input.isEmpty()) {
            return new MaskResult(input, counts);
        }
        String s = replace(input, EMAIL, PiiType.EMAIL, counts, false);
        s = replace(s, IBAN, PiiType.IBAN, counts, false);
        s = replace(s, PHONE, PiiType.PHONE, counts, false);
        s = replace(s, CARD, PiiType.CARD_NUMBER, counts, true);
        s = replace(s, ACCOUNT, PiiType.ACCOUNT_NUMBER, counts, false);
        return new MaskResult(s, counts);
    }

    private static String replace(String in, Pattern p, PiiType type, Map<PiiType, Integer> counts, boolean luhn) {
        Matcher m = p.matcher(in);
        StringBuilder out = new StringBuilder(in.length());
        boolean any = false;
        while (m.find()) {
            if (luhn && !luhnValid(m.group().replaceAll("[^0-9]", ""))) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }
            m.appendReplacement(out, "[" + type.name() + "]");
            counts.merge(type, 1, Integer::sum);
            any = true;
        }
        if (!any) {
            return in;
        }
        m.appendTail(out);
        return out.toString();
    }

    static boolean luhnValid(String digits) {
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }
}
