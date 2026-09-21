import re
from typing import Iterable, List, Optional, Set

_NON_ALNUM = re.compile(r"[^a-z0-9]")


def _normalize_key(name: Optional[str], zip_code: Optional[str]) -> str:
    normalized_name = _NON_ALNUM.sub("", (name or "").lower())
    normalized_zip = (zip_code or "").strip()
    return f"{normalized_name}|{normalized_zip}"


def existing_keys(existing_churches: Iterable[dict]) -> Set[str]:
    """Builds a lookup of already-known churches from the server's
    ``GET /api/churches`` response (camelCase ChurchDto fields).
    """
    return {_normalize_key(c.get("name"), c.get("zipCode")) for c in existing_churches}


def filter_new(discovered: Iterable[dict], existing: Iterable[dict]) -> List[dict]:
    """Drops discovered churches that already exist, matched on normalized
    name + zip. Exact address/lat-lon matching is left to the server's review
    queue, which has richer data (and a human) to break ties.
    """
    known = existing_keys(existing)
    return [
        church
        for church in discovered
        if _normalize_key(church.get("name"), church.get("zip_code")) not in known
    ]
