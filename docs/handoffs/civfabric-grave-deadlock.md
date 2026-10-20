# CivFabric: a grave's skin lookup can deadlock the server while its chunk loads

**For:** whoever maintains `lol.zym.civfabric.grave.GraveBlockEntity` (CivFabric, 1.21.1 Fabric).
**Seen:** 2026-10-20 19:53:58 on the local test server. The crash report is
`crash-2026-10-20_19.53.58-server.txt`, from the user.
**Effect:** the server thread blocks forever and the watchdog kills the server after 60 s
(`java.lang.Error: Watchdog`, "Watching Server").

## What the crash shows

The server thread is loading a chunk (promoting it to FULL). While it places the chunk's block
entities, a grave's `setWorld` runs. That ends up asking the world for the same chunk, and the
thread parks waiting for itself.

Relevant frames, innermost first (yarn intermediary names in brackets):

```
LockSupport.parkNanos
ThreadExecutor.runTasks / waitFor                    [class_1255.method_20813 / method_18857]
ServerChunkManager...getChunk (blocking)             [class_3215.method_12121]
World.getChunk / getWorldChunk                       [class_1937.method_8402, class_4538.method_22342, class_1937.method_8497/8500]
World.markDirty(BlockPos)                            [class_1937.method_8524]
BlockEntity.markDirty()                              [class_2586.method_5431 -> method_31663]
GraveBlockEntity.lambda$resolve$0  (GraveBlockEntity.java:110)
CompletableFuture$UniAccept.tryFire
ThreadExecutor.execute  (runs the task inline)       [class_1255.execute:108]
CompletableFuture.uniAcceptNow / thenAcceptAsync
GraveBlockEntity.resolve  (GraveBlockEntity.java:103)
GraveBlockEntity.setWorld (GraveBlockEntity.java:214) [method_31662]
WorldChunk.setBlockEntity / addBlockEntity           [class_2818.method_12007]
...chunk load / promotion to FULL                    [class_2852.method_39797, class_9310.method_60553]
MinecraftServer.runTasks / tick
```

## Root cause

`GraveBlockEntity.resolve()` (lines ~93–114):

```java
pending.getFuture().thenAcceptAsync(resolved -> {
    ...
    profile = resolved;
    markDirty();                                   // line 110
    world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_ALL);
}, world.getServer());
```

`setWorld()` (lines ~205–214) calls `resolve()` whenever the stored profile isn't complete:

```java
@Override
public void setWorld(World world) {
    super.setWorld(world);
    if (profile != null && !profile.isCompleted()) {
        resolve();
    }
}
```

Three things combine:

1. **`setWorld` runs during chunk loading.** It's called while the chunk is being turned into a
   `WorldChunk` and its block entities are attached, before that chunk is FULL.
2. **The server's executor doesn't queue when you're already on the server thread.**
   `MinecraftServer` (a `ThreadExecutor`/`BlockableEventLoop`) runs a submitted task
   *immediately* if the caller is the server thread. So if the profile future has **already
   completed**, `thenAcceptAsync(..., server)` runs the callback inline, right there inside
   `setWorld`.
3. **The callback calls `markDirty()`.** That goes to `World.markDirty(pos)`, which looks up the
   chunk at `pos` and blocks until it's FULL. It's the chunk currently being loaded by this same
   thread, so the wait never ends.

### Why it shows up now: offline-mode players' graves

For a normal online player the profile lookup is still pending when the chunk loads, so the
callback runs later, on a normal tick, and nothing goes wrong. The test server runs offline-mode
bot accounts ("Bot1", "Bot2"). Their `ProfileComponent` resolves immediately: there's no Mojang
profile to fetch, so the future is complete or completes at once. That makes step 2 run inline every
time a chunk containing one of their graves loads. The bots died several times on that world, so
there were several such graves. The crash happened when a player walked into range of one.

Anything else that makes the future complete before `setWorld` would trigger it too, such as a
cached profile or a server that resolves locally.

## Reproduce

1. Run a server with `online-mode=false`.
2. Join with an offline account, die so a grave is placed, then walk far enough away that the
   grave's chunk unloads (or restart the server).
3. Walk back towards the grave. The server hangs while the chunk loads, and the watchdog kills it
   about 60 s later.

## Suggested fix

Never apply the resolved profile synchronously inside `setWorld`, and never touch the world while
the chunk may still be loading. Defer it to a later tick on the server queue. `server.send(...)`
always enqueues, unlike `execute(...)`:

```java
private void resolve() {
    if (resolving || profile == null || world == null || world.isClient() || world.getServer() == null) {
        return;
    }
    resolving = true;
    ProfileComponent pending = profile;
    MinecraftServer server = world.getServer();

    pending.getFuture().thenAccept(resolved ->
            // always a later tick: setWorld runs mid chunk-load, and markDirty would wait on that chunk
            server.send(new ServerTask(server.getTicks() + 1, () -> apply(pending, resolved))));
}

private void apply(ProfileComponent pending, ProfileComponent resolved) {
    if (world == null || world.isClient() || profile != pending || isRemoved()) {
        return;
    }
    // the chunk can still be unloading or not yet ready: just keep the result, don't touch the world
    if (!world.isChunkLoaded(ChunkSectionPos.getSectionCoord(pos.getX()), ChunkSectionPos.getSectionCoord(pos.getZ()))) {
        profile = resolved;
        return;
    }
    profile = resolved;
    markDirty();
    world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_ALL);
}
```

Notes:

- `thenAccept` (not `thenAcceptAsync`) is fine here: the callback only *enqueues* work.
- Equivalent alternative: in `setWorld`, don't call `resolve()` at all. Set a flag and resolve on
  the block entity's first server tick, or from a `ServerChunkEvents.CHUNK_LOAD` callback, which
  fires once the chunk is FULL.
- If the stored profile is already complete, `setWorld` doesn't call `resolve()`. An offline
  profile that comes back from NBT incomplete always goes down the immediate path, so test with one.
- Worth a quick search for the same shape elsewhere: any block entity that calls `markDirty()`,
  `world.getBlockState`, `world.setBlockState` or `updateListeners` from `setWorld`/`readNbt`, or
  from a future that may already be done.

## How to test the fix

1. Repeat the reproduction above with an offline account: dying, unloading and reloading the
   chunk must not hang the server.
2. Check the grave's head still updates to the right skin for an **online** player (the deferred
   path still applies the profile and re-sends the block).
3. Restart the server with several offline-player graves in loaded spawn chunks. Startup must not
   hang either: those chunks load before any player joins.
