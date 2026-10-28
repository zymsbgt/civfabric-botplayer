#!/bin/zsh
# Restart a bot's Minecraft client on the newest jar, then start it and give it its first command.
#   hub/restart-bot.sh <bot name> <server address> <hub server key> [command after start]
#   hub/restart-bot.sh Wandersong 195.201.199.249:25570 62f2ca33 "follow ZymSB"
# The Prism instance is "ZymBot <bot name>" and the Prism account is the bot name.
# Expects the hub on localhost:25599 (no token needed from localhost).
set -u
BOT=$1 SERVER=$2 KEY=$3 AFTER=${4:-}
ROOT=${0:A:h:h}
INST="$HOME/Library/Application Support/PrismLauncher/instances/ZymBot $BOT/minecraft"
LOG="$INST/logs/latest.log"
HUB="http://localhost:25599"

send() {
  curl -s -m 5 -X POST "$HUB/send?server=$KEY" -H 'Content-Type: application/json' \
    -d "$(python3 -c 'import json,sys; print(json.dumps({"from":sys.argv[1],"to":sys.argv[1],"text":sys.argv[2]}))' "$BOT" "$1")" >/dev/null
}
enabled() {
  curl -s -m 5 "$HUB/view?server=$KEY&as=$BOT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["leader"].get("enabled"))' 2>/dev/null
}

[ -d "$INST" ] || { echo "no instance: $INST"; exit 1; }
send "say Restarting, back in a minute."
sleep 2

# only this bot's client: never a broad kill
pid=$(ps -axo pid=,command= | grep "[j]ava-runtime-delta" | grep "Prism Launcher: ZymBot $BOT" | awk '{print $1}')
if [ -n "$pid" ]; then
  kill $pid
  for i in {1..30}; do kill -0 $pid 2>/dev/null || break; sleep 1; done
  kill -0 $pid 2>/dev/null && { echo "client $pid didn't exit"; exit 1; }
  sleep 6
fi

# the jar goes in only while the client is closed
rm -f "$INST/mods/zymbot-0.1.0.jar.update"
cp "$ROOT/build/libs/zymbot-0.1.0.jar" "$INST/mods/zymbot-0.1.0.jar"
shasum "$ROOT/build/libs/zymbot-0.1.0.jar" "$INST/mods/zymbot-0.1.0.jar" | awk '{print $1}' | uniq | wc -l | grep -q '^ *1$' \
  || { echo "jar copy mismatch"; exit 1; }

before=$(date +%s)
"/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher" -l "ZymBot $BOT" -s "$SERVER" -a "$BOT" >/dev/null 2>&1 &
sleep 10
for i in {1..150}; do
  [ "$(stat -f %m "$LOG" 2>/dev/null || echo 0)" -ge "$before" ] && grep -q "\[$BOT\] joined" "$LOG" && break
  sleep 1
done
grep -q "\[$BOT\] joined" "$LOG" || { echo "didn't join:"; grep -iE "disconnect|kicked" "$LOG" | tail -3; exit 1; }

for i in {1..8}; do
  send start; sleep 3
  [ "$(enabled)" = True ] && break
done
[ "$(enabled)" = True ] || { echo "joined but not started"; exit 1; }
[ -n "$AFTER" ] && { sleep 2; send "$AFTER"; }
echo "$BOT restarted and running${AFTER:+, then: $AFTER}"
