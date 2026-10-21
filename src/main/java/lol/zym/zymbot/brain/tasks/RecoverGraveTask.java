package lol.zym.zymbot.brain.tasks;

import lol.zym.zymbot.Bot;
import lol.zym.zymbot.brain.Task;
import lol.zym.zymbot.control.Breaker;
import lol.zym.zymbot.control.Placer;
import lol.zym.zymbot.nav.Nav;
import lol.zym.zymbot.world.BlockSearch;
import lol.zym.zymbot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/** Walk back to where we died and open/break the CivFabric grave to get our things back. */
public class RecoverGraveTask extends Task {
    private static final ResourceLocation GRAVE = ResourceLocation.fromNamespaceAndPath("civfabric", "grave");
    private final BlockPos death;
    private BlockSearch search;
    private BlockPos grave;
    private Breaker breaker;

    public RecoverGraveTask(BlockPos death) {
        this.death = death;
    }

    @Override
    public String name() {
        return "Recover grave " + death.toShortString();
    }

    /** Lava or fire around where we died (a floor of it below counts): whatever killed us there is still there. */
    private static boolean burning(BlockPos at) {
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-4, -3, -4), at.offset(4, 2, 4))) {
            var st = WorldView.state(p);
            if (st.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) || st.getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock)
                return true;
        }
        return false;
    }

    private static boolean isGrave(net.minecraft.world.level.block.state.BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).equals(GRAVE);
    }

    @Override
    protected Status onTick(Bot bot) {
        if (child != null) {
            Status s = runChild(bot);
            return s == Status.SUCCESS && grave == null && search == null ? Status.SUCCESS : Status.RUNNING;
        }
        if (ticks > 20 * 60 * 5) return fail("gave up on grave");
        // a grave in lava or fire: we die in it again (a bot burned twice going back for one)
        if ((bot.player().isOnFire() || bot.player().isInLava()) && bot.player().blockPosition().closerThan(death, 24)) {
            bot.markHazard(death);
            return fail("our grave at " + death.toShortString() + " is in fire or lava; leaving it");
        }
        // and look before going: Bot4 walked into the lava by two of its graves before it found out
        if (WorldView.loaded(death) && burning(death)) {
            bot.markHazard(death);
            return fail("our grave at " + death.toShortString() + " is by lava or fire; leaving it");
        }
        if (grave == null) {
            if (!bot.player().blockPosition().closerThan(death, 24)) {
                Nav.goNear(death, 3);
                return Status.RUNNING;
            }
            if (search == null) search = new BlockSearch(death, 10, 8, RecoverGraveTask::isGrave, null);
            if (!search.step(16)) return Status.RUNNING;
            grave = search.results().stream().findFirst().orElse(null);
            search = null;
            if (grave == null) return Status.SUCCESS; // nothing there (already looted or no items)
        }
        if (!isGrave(WorldView.state(grave))) {
            setChild(bot, new PickupTask(grave, 6).intoWater());
            grave = null;
            return Status.RUNNING;
        }
        if (!Breaker.inReach(grave)) {
            Nav.goToBlock(grave);
            return Status.RUNNING;
        }
        Nav.stop();
        if (breaker == null) {
            Placer.use(grave); // some graves hand everything back on right-click
            breaker = new Breaker(grave);
            return Status.RUNNING;
        }
        if (breaker.tick() == Breaker.Result.STUCK) return fail("grave won't open");
        return Status.RUNNING;
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
        if (breaker != null) breaker.cancel();
        Nav.stop();
    }
}
