"""Parses durations like "1h30m" or "45s" into seconds."""
import re

_UNITS = {"h": 3600, "m": 60, "s": 1}


def parse_duration(text: str) -> int:
    total = 0
    for amount, unit in re.findall(r"(\d+)([hms])", text):
        total += int(amount) if unit != "s" else int(amount) * _UNITS[unit]
    return total
