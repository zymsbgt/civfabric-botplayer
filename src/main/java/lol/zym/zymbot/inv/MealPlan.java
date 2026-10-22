package lol.zym.zymbot.inv;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The best order to eat a pack of food in under Spice of Fabric: each food restores
 * {@code round(hunger * base^n)}, n being how often it is among the last {@code length} meals, so
 * the way to get the most out of a pack is to go round the foods (bread, beef, carrot, bread...)
 * rather than finish one pile before the next. Each meal takes whichever food is worth most right
 * then, which settles into that round. A raw food may also be eaten as it is or cooked first,
 * whichever pays more by then: a raw beef nobody has eaten lately (3) beats a cooked one eaten four
 * times out of the last eleven (2).
 *
 * <p>Plain Java, no game classes, so teammates' packs (heard as ids and counts) plan the same way.
 */
public final class MealPlan {
    /** One way to eat a pile: as the item it is, or as what it cooks into. */
    public record Form(String id, int hunger) {}

    /** Items of one kind and the ways to eat them; {@code now} is the form edible as it stands (or null). */
    public record Pile(String id, int count, Form now, List<Form> forms) {}

    /** The total a plan restores, and the pile it eats first. */
    public record Result(double total, String first) {}

    private final List<String> history;
    private final int length;
    private final double base;

    /** {@code history}: the food ids of the last meals, oldest first. */
    public MealPlan(List<String> history, int length, double base) {
        this.history = history.size() > length ? history.subList(history.size() - length, history.size()) : history;
        this.length = length;
        this.base = base;
    }

    /** What a food restores eaten after {@code times} more of the same in the last meals. */
    public double worth(int hunger, int times) {
        return Math.max(0, Math.round(hunger * Math.pow(base, Math.min(times, length))));
    }

    /** Most hunger the whole pack can restore, eaten in the best order. */
    public double total(List<Pile> piles) {
        // the biggest bite every time isn't always best (a mix came to 112 that way, 113 starting on
        // the apple): try each first meal as well
        Result r = next(piles);
        return Math.max(rollout(piles, null), r == null ? 0 : r.total());
    }

    /** The biggest bite every time: within a point or so of {@link #total}, at a fraction of the work. */
    public double quick(List<Pile> piles) {
        return rollout(piles, null);
    }

    /**
     * What the pack restores with each pile eaten first (as it stands) and the rest in the best
     * order after: the meal to eat now is the one with the biggest total, not the biggest bite.
     */
    public Map<String, Double> ifFirst(List<Pile> piles) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Pile p : piles) if (p.now() != null && p.count() > 0) out.put(p.id(), rollout(piles, p.id()));
        return out;
    }

    /** The pile to eat now, and what the pack is then worth; level, the bigger bite now. */
    public Result next(List<Pile> piles) {
        Result best = null;
        double bestBite = 0;
        for (var e : ifFirst(piles).entrySet()) {
            double bite = bite(piles, e.getKey());
            if (best == null || e.getValue() > best.total() || e.getValue() == best.total() && bite > bestBite) {
                best = new Result(e.getValue(), e.getKey());
                bestBite = bite;
            }
        }
        return best;
    }

    /** What the pile restores eaten now, as it stands. */
    public double bite(List<Pile> piles, String id) {
        for (Pile p : piles) {
            if (p.id().equals(id) && p.now() != null)
                return worth(p.now().hunger(), (int) history.stream().filter(p.now().id()::equals).count());
        }
        return 0;
    }

    /** Eat the pack one meal at a time, each the food worth most then; {@code first} forced first. */
    private double rollout(List<Pile> piles, String first) {
        ArrayDeque<String> window = new ArrayDeque<>(history);
        Map<String, Integer> times = new HashMap<>();
        for (String id : window) times.merge(id, 1, Integer::sum);
        int[] left = new int[piles.size()];
        for (int i = 0; i < left.length; i++) left[i] = piles.get(i).count();
        double total = 0;
        boolean forced = first != null;
        while (true) {
            int pick = -1;
            Form form = null;
            double best = 0;
            for (int i = 0; i < left.length; i++) {
                if (left[i] <= 0) continue;
                Pile p = piles.get(i);
                if (forced) {
                    if (!p.id().equals(first) || p.now() == null) continue;
                    pick = i;
                    form = p.now();
                    best = worth(form.hunger(), times.getOrDefault(form.id(), 0));
                    break;
                }
                for (Form f : p.forms()) {
                    double w = worth(f.hunger(), times.getOrDefault(f.id(), 0));
                    // ties: from the bigger pile, which has the more meals still to spread out
                    if (w > best || w == best && w > 0 && pick >= 0 && left[i] > left[pick]) {
                        best = w;
                        pick = i;
                        form = f;
                    }
                }
            }
            forced = false;
            // nothing left worth a point: the rest of the pack restores nothing more this way
            if (pick < 0 || best <= 0) break;
            total += best;
            left[pick]--;
            window.addLast(form.id());
            times.merge(form.id(), 1, Integer::sum);
            if (window.size() > length) {
                String gone = window.removeFirst();
                times.merge(gone, -1, Integer::sum);
            }
        }
        return total;
    }

    /** Piles from ids and counts, given each item's forms (none: not a safe food, left out). */
    public static List<Pile> piles(Map<String, Integer> counts, java.util.function.Function<String, Pile> describe) {
        List<Pile> out = new ArrayList<>();
        counts.forEach((id, n) -> {
            if (n == null || n <= 0) return;
            Pile p = describe.apply(id);
            if (p != null && !p.forms().isEmpty()) out.add(new Pile(p.id(), n, p.now(), p.forms()));
        });
        return out;
    }
}
