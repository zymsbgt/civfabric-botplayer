package lol.zym.zymbot.brain;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.inv.Inv;
import lol.zym.zymbot.team.TeamBus;
import net.minecraft.world.item.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Who makes the team's tools (the owner's rules, 2026-10-23). Once the team is fed, tools come
 * from the Blacksmith: everyone else asks for them on the job board instead of crafting their own,
 * unless no Blacksmith is in radio range or the ask has gone unanswered for 20 minutes. And a
 * Blacksmith short of iron or diamond puts it into pickaxes before swords, axes, shovels and hoes.
 */
public final class ToolShare {
    private ToolShare() {}

    /** How long an ask may go unanswered before we make the tool ourselves after all. */
    private static final long WAIT_MS = 20 * 60_000L;
    private static final Map<Item, Long> askedSince = new HashMap<>();

    /** Down to its last 15% (5 uses at least): time to have the next one made. */
    public static boolean worn(ItemStack s) {
        return s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= Math.max(5, s.getMaxDamage() * 0.15);
    }

    /** We carry this item and it isn't worn out. */
    public static boolean hasFresh(Item item) {
        return Inv.count(s -> s.is(item) && !worn(s)) > 0;
    }

    /** Sword, pickaxe, axe, shovel or hoe. */
    public static boolean isTool(Item it) {
        return it instanceof SwordItem || it instanceof DiggerItem;
    }

    /** A Blacksmith teammate within radio range. */
    public static Optional<TeamBus.Member> smith(Bot bot) {
        return bot.team().inRange().stream().filter(m -> {
            var c = m.state().get("classes");
            if (c == null || !c.isJsonArray()) return false;
            for (var e : c.getAsJsonArray()) if ("blacksmith".equals(e.getAsString())) return true;
            return false;
        }).findFirst();
    }

    /**
     * Why we leave this tool to the Blacksmith ("Bot2, the Blacksmith,"), or null when we make it
     * ourselves: we're a Blacksmith, the team isn't fed yet, there's no Blacksmith in range, or
     * we've waited long enough.
     */
    public static String smithMakes(Bot bot, Item item) {
        if (!isTool(item) || hasFresh(item)) {
            askedSince.remove(item);
            return null;
        }
        if (ClassPlan.inPlan(bot, "blacksmith") || !Progression.teamFoodSecure(bot)) return null;
        var smith = smith(bot);
        if (smith.isEmpty()) return null;
        long now = System.currentTimeMillis();
        if (now - askedSince.computeIfAbsent(item, k -> now) > WAIT_MS) return null;
        return smith.get().name() + ", the Blacksmith,";
    }

    /** What to ask the Blacksmith for: a wooden tool's stone version (it lasts twice as long). */
    public static Item askFor(Item item) {
        if (item == Items.WOODEN_SWORD) return Items.STONE_SWORD;
        if (item == Items.WOODEN_PICKAXE) return Items.STONE_PICKAXE;
        if (item == Items.WOODEN_AXE) return Items.STONE_AXE;
        if (item == Items.WOODEN_SHOVEL) return Items.STONE_SHOVEL;
        if (item == Items.WOODEN_HOE) return Items.STONE_HOE;
        return item;
    }

    /** The ore a tool is made of, when it's one worth rationing (iron, diamond), else null. */
    static Item material(Item tool) {
        if (!(tool instanceof TieredItem t)) return null;
        if (t.getTier() == Tiers.IRON) return Items.IRON_INGOT;
        if (t.getTier() == Tiers.DIAMOND) return Items.DIAMOND;
        return null;
    }

    /** Units of its material a tool takes. */
    private static int cost(Item tool) {
        if (tool instanceof PickaxeItem || tool instanceof AxeItem) return 3;
        if (tool instanceof ShovelItem) return 1;
        return 2; // sword, hoe
    }

    /**
     * Why a Blacksmith holds this tool back for now, or null: it's a sword, axe, shovel or hoe of
     * iron or diamond, a pickaxe of that material is wanted (ours, or asked for on a board in
     * range), and what we hold won't cover the pickaxes and this too.
     */
    public static String pickaxesFirst(Bot bot, Item tool) {
        Item ore = material(tool);
        if (ore == null || tool instanceof PickaxeItem || !ClassPlan.inPlan(bot, "blacksmith")) return null;
        int tier = Inv.tier(new ItemStack(tool));
        int wanted = 0;
        boolean ours = false;
        for (int i = 0; i < 36; i++) {
            ItemStack s = Inv.inv().getItem(i);
            if (s.getItem() instanceof PickaxeItem && Inv.tier(s) >= tier) ours = true;
        }
        if (!ours) wanted++;
        for (var board : bot.team().boards()) {
            for (var e : board.getAsJsonArray("posts")) {
                var post = e.getAsJsonObject();
                if (!"open".equals(post.get("status").getAsString())) continue;
                var it = lol.zym.zymbot.craft.ItemTarget.parse(post.get("item").getAsString(), 1);
                if (it == null || it.candidates().isEmpty()) continue;
                Item want = it.candidates().get(0);
                if (want instanceof PickaxeItem && material(want) == ore) {
                    wanted += post.has("open") ? post.get("open").getAsInt() : post.get("count").getAsInt() - post.get("delivered").getAsInt();
                }
            }
        }
        if (wanted <= 0 || Inv.count(ore) >= 3 * wanted + cost(tool)) return null;
        return Inv.id(ore).replace("minecraft:", "") + " goes to pickaxes first (" + wanted + " wanted)";
    }
}
