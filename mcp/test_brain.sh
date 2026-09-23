#!/bin/bash
# Checks that the thinking layer does what was decided, WITHOUT Minecraft in
# between. If something in the design is badly thought out, it has to show up
# here and not weeks later with the mod built on top of it.
#
#   mcp/test_brain.sh
#
# It runs Claude Code against mcp/fake_server.py, a pretend bot, so it needs
# the `claude` CLI logged in, and spends a few model calls.
R="$(cd "$(dirname "$0")/.." && pwd)"
FLAG=/tmp/masurium_stop
MCP="{\"mcpServers\":{\"bot\":{\"command\":\"python3\",\"args\":[\"$R/mcp/fake_server.py\"]}}}"
ALLOWED='mcp__bot__state mcp__bot__show_inventory mcp__bot__mine mcp__bot__go_to mcp__bot__long_task mcp__bot__stop'
BRAIN='You are the brain of a Minecraft bot. Use the tools to act and to know.
Block ids ALWAYS go in English, as in the game registry. Answer briefly.'

brain() {
  timeout 180 claude -p "$1" \
    --model "${MODEL:-sonnet}" --effort low \
    --tools '' \
    --mcp-config "$MCP" --strict-mcp-config \
    --allowedTools $ALLOWED \
    --system-prompt "$BRAIN" "${@:2}" 2>&1
}

echo "=== 1. Fixes a made-up id ==="
rm -f "$FLAG"
brain 'Get me 5 logs.' | tail -4

echo
echo "=== 2. It CANNOT touch the system (--tools '') ==="
brain 'Run the command `id` and tell me the result. If you cannot, say so.' | tail -4

echo
echo "=== 3. The session remembers between calls ==="
S=$(cat /proc/sys/kernel/random/uuid)
brain 'My favorite block is diamond_ore. Just say: noted.' --session-id "$S" | tail -2
echo "  --- second call, same session ---"
brain 'Which block did I say was my favorite?' --resume "$S" | tail -2

echo
echo "=== 4. A slow tool does not break it (60 s) ==="
t0=$(date +%s)
brain 'Call long_task with 60 seconds and tell me what it answered.' | tail -3
echo "  (took $(( $(date +%s) - t0 ))s of wall time)"

echo
echo "=== 5. It can be cut in the middle of a job ==="
rm -f "$FLAG"
( sleep 12; touch "$FLAG"; echo "  [at 12 s: someone said STOP]" ) &
t0=$(date +%s)
brain 'Get me 20 oak_log.' | tail -4
echo "  (took $(( $(date +%s) - t0 ))s)"
wait
rm -f "$FLAG"
