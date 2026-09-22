"""Why a start failed, in the client's own words: its crash report, the mods
that refused to construct, its last complaints."""
import pathlib
import re

from .files import tail_lines


def crash_report(bot):
    """The crash report the game said it saved, if this run crashed."""
    for l in tail_lines(bot.client_log, 400, r"Crash report saved to:"):
        m = re.search(r"Crash report saved to: #@!@# (.+\.txt)", l)
        if m and pathlib.Path(m.group(1).strip()).is_file():
            return pathlib.Path(m.group(1).strip())
    return None


def refusals(bot, width=220):
    """The mods that failed to construct, each with the line that says why."""
    try:
        lines = bot.client_log.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return []
    out = []
    for i, l in enumerate(lines):
        if "Failed to create mod instance" not in l:
            continue
        out.append(l.strip()[:width])
        for follow in lines[i + 1:i + 4]:
            if "Exception" in follow or "Error" in follow:
                out.append("  " + follow.strip()[:width])
                break
    return out


def crash_summary(report, width=220):
    """What a person reads first in a crash report: the description, the
    exception, and the mod frames nearest the top, which are the ones that
    name the culprit. The mod list at the end says nothing."""
    try:
        lines = report.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return []
    out = []
    plumbing = re.compile(r"minecraft@|neoforged|java\.base|modlauncher|bootstraplauncher|securejarhandler")
    for l in lines[:200]:
        s = l.strip()
        if l.startswith("Description:") or s.startswith("Caused by"):
            out.append(s)
        elif re.search(r"Exception|Error:|Missing|requires", s) and len(out) < 6:
            out.append(s)
        elif s.startswith("at ") and not plumbing.search(s) and len(out) < 12:
            out.append(s)
        if len(out) >= 14:
            break
    return [l[:width] for l in out]


def explain_crash(bot):
    """If this run crashed: (report path, the lines that say why); else None.
    A mod that refused to construct is the FIRST thing to look at: the game
    goes on to draw the loading-error screen, and what finally crashes is
    whatever draws it, so the report names the wrong thing. The refusal and
    its reason are in the log, a few lines apart."""
    report = crash_report(bot)
    if not report:
        return None
    return report, refusals(bot) + [f"crash report: {report}"] + crash_summary(report)


def complaints(bot):
    """The client's last words about joining. Since the pack comes from the
    registry, suspect number one is that it does not match the server's: that
    shows up as a mod rejection, not as "could not connect"."""
    return tail_lines(bot.client_log, 8,
                      r"disconnect|kick|refused|timed out|Unknown host|failed|"
                      r"mod rejections|incompatible|missing mods|negotiation")
