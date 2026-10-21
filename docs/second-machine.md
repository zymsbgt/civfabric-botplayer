# Running more bots from a second machine

Bots on any machine on the LAN join the same team through the hub running on the main Mac.

- **Hub:** `http://192.168.8.163:25599` (the main Mac's address on the LAN; check it with `ipconfig getifaddr en0`).
- **Token:** in `hub/hub-token.txt` on the main Mac. It isn't committed. Connections from the Mac
  itself need no token; every other machine does.
- **Server:** the test machine, `192.168.8.167:25565`.

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
"hubUrl": "http://192.168.8.163:25599",
"hubToken": "<the token from hub/hub-token.txt>",
"autoStart": false
```

`hubUrl` may list several addresses, comma-separated (`"http://192.168.8.163:25599, http://192.168.0.3:25599"`): the bot
tries them in turn and stays on the first that answers. Edit the file with the game closed; it's read at
startup. Leave everything else as it is: `gatherMode` is `auto`, `sprint` is `false`, and the `teammates`
list should include `Bot1`, `Bot2`, `Bot3` and `Bot4`.

## 3. Accounts

In Prism, under **Accounts**, add offline accounts named `Bot3` and `Bot4`. Bots must have distinct
names: two clients with the same name kick each other off.

## 4. Launch

Launch each instance with its own account, and join `192.168.8.167:25565` (the server is offline
mode). From a terminal you can do it in one go (Windows path shown):

```
"C:\Program Files\PrismLauncher\prismlauncher.exe" -l "ZymBot Bot3" -s 192.168.8.167:25565 -a Bot3
```

A second launch while Prism is running is forwarded to that same Prism process, which ignores `-a`.
So start the second bot from the Prism window, or from a second Prism using its own data directory
(`-d <dir>`).

## 5. Start them

They join paused. Claude starts them through the hub, gives them the team's base
(`teamhome set main 76 63 376`), and they walk there and take the next free roles (Delver, then
Scholar).

## If they can't reach the hub

- From the second machine, open `http://192.168.0.3:25599/leaders?server=x` in a browser. Without
  the token it should say `unauthorized`; if it doesn't load at all, the Mac's firewall is blocking
  port 25599. Allow `node` under System Settings → Network → Firewall → Options.
- The client log shows `Team hub http://192.168.0.3:25599 (server ...)` once it's connected.
