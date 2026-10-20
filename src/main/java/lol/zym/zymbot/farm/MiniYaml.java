package lol.zym.zymbot.farm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough YAML for Realistic Plant Growth's config files: nested maps, lists of scalars
 * ("- X", also at the same indent as their key), "[]", and # comments. Anything fancier
 * (anchors, multi-line strings, flow maps) isn't used there and isn't supported.
 */
final class MiniYaml {
    private MiniYaml() {}

    private record Line(int indent, String text) {}

    static Map<String, Object> parse(String src) {
        List<Line> lines = new ArrayList<>();
        for (String raw : src.split("\\R")) {
            String s = stripComment(raw);
            if (s.isBlank()) continue;
            int indent = 0;
            while (indent < s.length() && s.charAt(indent) == ' ') indent++;
            lines.add(new Line(indent, s.strip()));
        }
        int[] at = {0};
        Map<String, Object> out = new LinkedHashMap<>();
        while (at[0] < lines.size()) {
            if (block(lines, at) instanceof Map<?, ?> m) m.forEach((k, v) -> out.put((String) k, v));
            else at[0]++;
        }
        return out;
    }

    private static String stripComment(String s) {
        if (s.stripLeading().startsWith("#")) return "";
        int i = s.indexOf(" #");
        return i >= 0 ? s.substring(0, i) : s;
    }

    private static Object block(List<Line> ls, int[] at) {
        Line first = ls.get(at[0]);
        if (first.text.startsWith("- ") || first.text.equals("-")) {
            List<Object> list = new ArrayList<>();
            while (at[0] < ls.size() && ls.get(at[0]).indent == first.indent && ls.get(at[0]).text.startsWith("-")) {
                list.add(scalar(ls.get(at[0]).text.substring(1).strip()));
                at[0]++;
            }
            return list;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        while (at[0] < ls.size()) {
            Line l = ls.get(at[0]);
            if (l.indent != first.indent || l.text.startsWith("-")) break;
            // the key ends at ": " or a trailing ":" - ids like farmersdelight:cabbages contain colons
            int colon = l.text.indexOf(": ");
            if (colon < 0 && l.text.endsWith(":")) colon = l.text.length() - 1;
            if (colon < 0) {
                at[0]++;
                continue;
            }
            String key = unquote(l.text.substring(0, colon).strip());
            String rest = l.text.substring(colon + 1).strip();
            at[0]++;
            Object value = null;
            if (!rest.isEmpty()) {
                value = rest.equals("[]") ? new ArrayList<>() : scalar(rest);
            } else if (at[0] < ls.size()) {
                Line next = ls.get(at[0]);
                if (next.indent > l.indent || next.indent == l.indent && next.text.startsWith("-")) value = block(ls, at);
            }
            map.put(key, value);
        }
        return map;
    }

    private static Object scalar(String s) {
        if (s.equals("[]")) return new ArrayList<>();
        return unquote(s);
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'")))
            return s.substring(1, s.length() - 1);
        return s;
    }
}
