#!/usr/bin/env python3
"""MCP server of the bot — FAKE tools, on purpose.

No Minecraft yet. What is tested here is not that the bot does something, but
that the thinking layer works: that Claude sees the tools, respects the ids,
that a long task does not blow it up and that it can be cut halfway.

It speaks JSON-RPC 2.0 over stdin/stdout, one line per message. No
dependencies: if this needed a library, it would be one more thing to break.
"""
import json
import os
import sys
import time

STOP_FLAG = "/tmp/marionette_stop"

# The real registry has thousands. These are enough to test that an id that
# does not exist is REJECTED instead of accepted, leaving the bot standing in
# a forest.
BLOCKS = {
    "oak_log", "birch_log", "spruce_log", "cherry_log", "pale_oak_log",
    "stone", "cobblestone", "coal_ore", "iron_ore", "diamond_ore",
    "dirt", "grass_block", "sand", "gravel",
}

# The model's typical mistakes: it says the name in another language, or it
# says the item when what is mined is the block.
EQUIVALENCES = {
    "log": "oak_log", "wood": "oak_log", "oak": "oak_log",
    "tronco": "oak_log", "madera": "oak_log", "roble": "oak_log",
    "piedra": "stone", "carbon": "coal_ore", "coal": "coal_ore",
    "hierro": "iron_ore", "iron": "iron_ore", "tierra": "dirt",
    "diamante": "diamond_ore", "diamond": "diamond_ore",
}

INVENTORY = {"oak_log": 3, "stick": 2, "wooden_pickaxe": 1}


def record(msg):
    """To the log, never to stdout: stdout is the protocol channel."""
    print(f"[mcp] {msg}", file=sys.stderr, flush=True)


def wait_for(seconds, step=0.25):
    """Waits watching the stop flag. Returns True if it was cancelled.

    Every blocking tool has to be cuttable from outside; otherwise `stop`
    waits for exactly what you want to stop to finish.
    """
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if os.path.exists(STOP_FLAG):
            return True
        time.sleep(step)
    return False


def normalize(block):
    """Returns (valid_id, None) or (None, explanation)."""
    b = (block or "").strip().lower().removeprefix("minecraft:")
    if b in BLOCKS:
        return b, None
    if b in EQUIVALENCES:
        return None, (
            f"'{block}' is not a Minecraft id. I think you mean "
            f"'{EQUIVALENCES[b]}'. Call me again with that one."
        )
    return None, (
        f"I do not know the block '{block}'. Ids go in English, as in the "
        f"game registry. Examples: oak_log, stone, coal_ore."
    )


# --- the tools --------------------------------------------------------------

def t_state(_):
    return ("Hp 20/20, hunger 17/20, position -330 64 24, on grass_block, "
            "biome plains. Nearby: oak_log x44 at 2m, stone x120 at 4m, pig at 12m.")


def t_show_inventory(_):
    if not INVENTORY:
        return "I carry nothing."
    return "I carry: " + ", ".join(f"{n}x {k}" for k, n in INVENTORY.items())


def t_mine(args):
    block, error = normalize(args.get("block"))
    if error:
        return error
    target = int(args.get("amount", 1))
    already = INVENTORY.get(block, 0)
    missing = max(0, target - already)
    if missing == 0:
        return f"I already carried {already}x {block}, I did nothing."

    # Simulates the work: ~1.5 s per block.
    record(f"mining {missing}x {block}")
    cancelled = wait_for(1.5 * missing)
    achieved = missing // 2 if cancelled else missing
    INVENTORY[block] = already + achieved
    if cancelled:
        return (f"Cancelled by order of Player1. I reached {achieved} of "
                f"{missing}; I now carry {INVENTORY[block]}x {block}.")
    return f"Done: {achieved}x {block}. I now carry {INVENTORY[block]}."


def t_go_to(args):
    x, y, z = args.get("x"), args.get("y"), args.get("z")
    if os.path.exists(STOP_FLAG):
        return "I did not move: there is an active stop order."
    cancelled = wait_for(float(args.get("_seconds", 4)))
    if cancelled:
        return f"Cancelled halfway to {x} {y} {z}."
    return f"Arrived at {x} {y} {z}."


def t_long_task(args):
    """Exists only to check that a slow tool breaks nothing."""
    sec = float(args.get("seconds", 60))
    record(f"long task of {sec}s")
    t0 = time.monotonic()
    cancelled = wait_for(sec)
    real = round(time.monotonic() - t0, 1)
    if cancelled:
        return f"Cancelled after {real}s of {sec}s."
    return f"Finished after {real}s real."


def t_stop(_):
    open(STOP_FLAG, "w").close()
    return "Everything stopped."


TOOLS = {
    "state": (t_state, "Hp, hunger, position, biome and what is around.", {}),
    "show_inventory": (t_show_inventory, "What the bot carries.", {}),
    "mine": (t_mine,
             "Mines blocks until HAVING the requested amount. The id goes in "
             "English, as in the game registry (oak_log, stone, coal_ore). "
             "It waits to finish before answering.",
             {"block": ("string", "Id of the block, in English."),
              "amount": ("integer", "How many you want to have in total.")}),
    "go_to": (t_go_to, "Walks to some coordinates. Waits to arrive.",
              {"x": ("number", ""), "y": ("number", ""), "z": ("number", "")}),
    "long_task": (t_long_task, "Test: keeps the bot busy for N seconds.",
                  {"seconds": ("number", "How many seconds.")}),
    "stop": (t_stop, "Stops everything immediately.", {}),
}


def schema(fields):
    return {
        "type": "object",
        "properties": {k: {"type": t, **({"description": d} if d else {})}
                       for k, (t, d) in fields.items()},
        "required": [k for k in fields],
    }


def respond(id_, result):
    sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": id_,
                                 "result": result}) + "\n")
    sys.stdout.flush()


def main():
    record("started")
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            continue

        method = msg.get("method")
        id_ = msg.get("id")

        # Notifications carry no id and are not answered.
        if id_ is None:
            continue

        if method == "initialize":
            # The version the client asks for is returned: that way there is
            # no chasing the number every time the protocol changes.
            requested = msg.get("params", {}).get("protocolVersion", "2025-06-18")
            respond(id_, {
                "protocolVersion": requested,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "marionette-bot", "version": "0.1.0"},
            })
        elif method == "tools/list":
            respond(id_, {"tools": [
                {"name": n, "description": d, "inputSchema": schema(c)}
                for n, (_, d, c) in TOOLS.items()
            ]})
        elif method == "tools/call":
            p = msg.get("params", {})
            name = p.get("name")
            args = p.get("arguments") or {}
            entry = TOOLS.get(name)
            if not entry:
                text, failed = f"There is no tool called '{name}'.", True
            else:
                try:
                    text, failed = entry[0](args), False
                except Exception as e:            # let it show, do not swallow it
                    text, failed = f"{name} failed: {e}", True
            record(f"{name}({args}) -> {text[:70]}")
            respond(id_, {"content": [{"type": "text", "text": text}],
                          "isError": failed})
        else:
            respond(id_, {})


if __name__ == "__main__":
    main()
