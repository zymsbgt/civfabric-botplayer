# Running more bots from a second machine

Bots on any machine on the LAN join the same team through the hub running on the main Mac.

- **Hub:** `http://192.168.0.3:25599` (the main Mac's address on the LAN; check it with `ipconfig getifaddr en0`).
- **Token:** in `hub/hub-token.txt` on the main Mac. It isn't committed. Connections from the Mac
  itself need no token; every other machine does.
- **Server:** the test machine, `192.168.0.19:25565` (it was `192.168.8.167:25565`; see "If the server moves").

## 1. Copy the bot instance over

On the main Mac, in Prism Launcher, right-click **ZymBot Modded**, choose **Export Instance**,
and save the zip. Leave out `saves`, `logs`, `zymbot/memory`, `zymbot/old-worlds` and `baritone`
(they belong to Bot1/Bot2).

On the second machine, in Prism, use **Add Instance**, then **Import**, and pick the zip. For each
extra bot, make one copy of the instance, named e.g. "ZymBot Bot3" and "ZymBot Bot4". Two
clients can't share an instance.

## 2. Point each copy at the hub

Edit `minecraft/config/zymbot.json` in each copy:

```json
"hubUrl": "http://192.168.0.3:25599, http://192.168.8.163:25599",
"hubToken": "<the token from hub/hub-token.txt>",
"autoStart": false,
"autoUpdate": true,
"serverAliases": { "192.168.0.19:25565": "192.168.8.167:25565" }
```

`hubUrl` may list several addresses, comma-separated (`"http://192.168.8.163:25599, http://192.168.0.3:25599"`): the bot
tries them in turn and stays on the first that answers. Edit the file with the game closed; it's read at
startup. Leave everything else as it is: `gatherMode` is `auto`, `sprint` is `false`, and the `teammates`
list should include `Bot1`, `Bot2`, `Bot3` and `Bot4`.

## 3. Accounts

In Prism, under **Accounts**, add offline accounts named `Bot3` and `Bot4`. Bots must have distinct
names: two clients with the same name kick each other off.

## 4. Launch

Launch each instance with its own account, and join `192.168.0.19:25565` (the server is offline
mode). From a terminal you can do it in one go (Windows path shown):

```
"C:\Program Files\PrismLauncher\prismlauncher.exe" -l "ZymBot Bot3" -s 192.168.0.19:25565 -a Bot3
```

A second launch while Prism is running is forwarded to that same Prism process, which ignores `-a`.
So start the second bot from the Prism window, or from a second Prism using its own data directory
(`-d <dir>`).

## 5. Start them

They join paused. Claude starts them through the hub, gives them the team's base
(`teamhome set main 76 63 376`), and they walk there and take the next free roles (Delver, then
Scholar).

## 6. Updates from the hub

With `"autoUpdate": true` in a copy's `zymbot.json`, that bot takes new versions of the mod from the
hub, so the jar only has to be copied over once (the first version with the updater in it).

- The hub serves the newest `build/libs/zymbot-<version>.jar` on the main Mac, or the file named
  by `ZYMBOT_HUB_MOD`. Like everything else on the hub, it needs the token.
- The bot checks at startup and every half hour. A new jar is downloaded next to the old one as
  `zymbot-0.1.0.jar.update`, checked against the hub's SHA-256, and copied in once the game has
  closed. The old one is kept as `zymbot-0.1.0.jar.bak`. The new version runs from the next launch.
- `update` (sent through the hub or in chat) does it straight away: the bot downloads the new jar
  and closes the game. Launch it again to run the new version. `update check` downloads without
  closing; `update status` (or `version`) says what it's running.
- The swap's log is `logs/zymbot-update.log`. To go back a version, close the game and rename
  `.bak` to `.jar`.

A bot with `autoUpdate` on runs whatever jar the hub serves, so the token is what keeps anyone
else on the network from running code on that machine. Don't share it.

## Without the hub

Bots also talk over server chat. ZymLabs Rules carries a chat line 150 blocks, the team's own
range, so a bot that can join the server can work with its team with no hub at all:

- About once a minute each bot says a status line (`~zb st ...`: role, classes, position, fields,
  bases), and each forecast reading of its own once (`~zb fc ...`). Teammates who hear it count it
  as in range. Commands between bots are ordinary chat lines (`zb Bot2 come`).
- Players nearby see these lines. `"chatRadio": false` turns it off and leaves the team on the hub.
- `"hubUrl": "off"` runs a bot with no hub. It still works with its team; it has no job boards, no
  updates from the hub, and Claude can't see or drive it except through a leader within 150 blocks
  (`tell Bot3 start`).
- Only names in `teammates` are listened to, and only when the name starts the chat line, so a
  player can't type a line that passes for a bot's.

The hub is still what Claude drives the leaders through, and where job boards and updates come from.

## If the server moves

A bot keeps its memory (home, bed, chests, fields) per server address, and the hub keeps the team
and its boards the same way. Joined at a new address, a bot would start over on a team of its own.
`serverAliases` says the new address is the old server: `"<new host:port>": "<old host:port>"`.
Every bot on that server needs the same line (the first address it ever used is the one on the
right). It's read at startup, so edit it with the game closed.

## If they can't reach the hub

- From the second machine, open `http://192.168.0.3:25599/leaders?server=x` in a browser. Without
  the token it should say `unauthorized`; if it doesn't load at all, the Mac's firewall is blocking
  port 25599. Allow `node` under System Settings → Network → Firewall → Options.
- The client log shows `Team hub http://192.168.0.3:25599 (server ...)` once it's connected.
