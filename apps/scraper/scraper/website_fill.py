"""Backfills service times, pastor and description on existing churches by crawling each
church's own website. Free: no API key and no per-call cost (Google Places enrichment
covers some of the same ground but bills every request).

Needs websites first. Run osm_fill.py and wikidata_fill.py before this. It only visits
churches that already have a website and are missing at least one of the three fields.

**What it reads, per site** (the homepage plus up to 4 linked pages on the same site,
picked by link text/URL: "Mass Times", "Worship", "Plan a Visit", "Our Pastor", "Staff"):
  - service times: lines like "Sundays at 9:00 & 10:30 AM", "Saturday Vigil 5:00 PM",
    "Worship Service ....... 11:00 a.m.", or a "Sunday" heading over a list of times.
    Precision over recall: office hours, Sunday School, dated events, time ranges,
    holiday/summer schedules and times without am/pm are all rejected (see
    `extract_service_times`), and a schedule with no Saturday/Sunday service is dropped
    as probably partial.
  - pastor: "Pastor Jane Doe", "Rev. John Smith, Senior Pastor", or a staff card with
    the name on one line and "Senior Pastor" on the next. Associate, youth, music etc.
    pastors never count, and two different lead-pastor names means no pastor, not a guess.
  - description: the site's own JSON-LD/meta/OpenGraph description, if it reads like one
    (not a template's "Just another WordPress site", not cut off with "...").

**Guards against filling the wrong church:**
  - the homepage must mention the church's name (a distinctive word of it), so a lapsed
    domain now serving a parking page or someone else's site is skipped;
  - if the site's addresses are all in another state, it's a same-named church elsewhere
    (a mistagged website) and is skipped;
  - a website listed on more than one church (a diocese or denomination site) is skipped,
    since its schedule belongs to one of them at most;
  - a description found on more than one church's site is boilerplate and dropped.

**Polite crawling:** an honest User-Agent, robots.txt honoured (Crawl-delay too), one
request at a time per site with a pause between, pages capped at 2MB, HTML only. Many
sites are crawled at once (--workers), since each is a different host.

Same server endpoint and guarantees as the other fills: POST /api/scraper/bulk-import/fill
(ChurchService.fillBlanks) sets blank fields only, adds service times only when the church
has none, and never touches a claimed church.

Run manually. A nationwide crawl takes hours, so pass --cache (one JSON line per church,
appended as it goes): re-running resumes where it stopped, and the real run reuses a dry
run's crawl instead of fetching every site again.

    python -m scraper.website_fill --dry-run --cache website_crawl.jsonl --states RI
    python -m scraper.website_fill --cache website_crawl.jsonl
"""

import argparse
import json
import logging
import os
import re
import threading
import time
import unicodedata
from collections import Counter, deque
from concurrent.futures import ThreadPoolExecutor
from typing import Callable, Dict, Iterable, List, Optional, Set, Tuple
from urllib.parse import urldefrag, urljoin, urlparse
from urllib.robotparser import RobotFileParser

import requests
from bs4 import BeautifulSoup

from .bulk_import import DEFAULT_BATCH_DELAY_S, DEFAULT_BATCH_SIZE
from .config import BulkImportConfig
from .enrichment import _from_json_ld
from .existing_churches import fetch_all_existing_churches
from .osm_fill import _blank, _missing, _service_type, log_samples, submit_fills

logger = logging.getLogger(__name__)

SOURCE_NAME = "website"
FILL_KEYS = ("serviceTimes", "pastor", "description")

# Identifies the crawler honestly. Robots.txt rules are matched against its first token.
USER_AGENT = "everychurch-website-fill/1.0 (+https://everychurch.co)"
REQUEST_TIMEOUT_S = 12
MAX_PAGE_BYTES = 2_000_000
MAX_SUBPAGES = 4
PAGE_DELAY_S = 1.0
# A site asking for more than this between requests gets its homepage only.
MAX_CRAWL_DELAY_S = 10.0
DEFAULT_WORKERS = 16
PROGRESS_EVERY = 100

# A church "website" on one of these is a profile page, not the church's own site.
SKIP_HOSTS = (
    "facebook.com", "fb.com", "instagram.com", "youtube.com", "twitter.com", "x.com",
    "tiktok.com", "linktr.ee", "google.com", "goo.gl", "yelp.com",
    # National sites some local congregations list as their own.
    "jw.org", "lds.org",
)


# --- fetching -----------------------------------------------------------------------

class Fetcher:
    """One site's requests: robots.txt checked per origin, a pause between requests."""

    def __init__(self, session: requests.Session, sleep: Callable[[float], None] = time.sleep):
        self.session = session
        self.sleep = sleep
        self.delay = PAGE_DELAY_S
        self.blocked = False
        self._robots: Dict[str, Optional[RobotFileParser]] = {}
        self._requests = 0

    def _load_robots(self, origin: str) -> Optional[RobotFileParser]:
        """None means no rules. An unreachable or 5xx robots.txt disallows everything,
        as Google treats it: the error may be hiding a Disallow."""
        rules = RobotFileParser()
        try:
            response = self.session.get(
                f"{origin}/robots.txt", headers={"User-Agent": USER_AGENT}, timeout=REQUEST_TIMEOUT_S
            )
        except requests.RequestException:
            rules.disallow_all = True
            return rules
        if response.status_code >= 500:
            rules.disallow_all = True
            return rules
        if response.status_code != 200:
            return None
        rules.parse(response.text.splitlines())
        crawl_delay = rules.crawl_delay(USER_AGENT)
        if crawl_delay:
            self.delay = max(self.delay, float(crawl_delay))
        return rules

    def allowed(self, url: str) -> bool:
        parts = urlparse(url)
        origin = f"{parts.scheme}://{parts.netloc}"
        if origin not in self._robots:
            self._robots[origin] = self._load_robots(origin)
        rules = self._robots[origin]
        return rules is None or rules.can_fetch(USER_AGENT, url)

    def get(self, url: str) -> Optional[Tuple[str, BeautifulSoup]]:
        """(final url after redirects, parsed page), or None for anything but a 200 HTML
        page under MAX_PAGE_BYTES that robots.txt allows."""
        if not self.allowed(url):
            self.blocked = True
            return None
        if self._requests:
            self.sleep(self.delay)
        self._requests += 1
        try:
            response = self.session.get(
                url, headers={"User-Agent": USER_AGENT}, timeout=REQUEST_TIMEOUT_S, stream=True
            )
        except requests.RequestException:
            return None
        try:
            content_type = response.headers.get("Content-Type", "")
            if response.status_code != 200 or "html" not in content_type.lower():
                return None
            body = b""
            for chunk in response.iter_content(65536):
                body += chunk
                if len(body) > MAX_PAGE_BYTES:
                    return None
        except requests.RequestException:
            return None
        finally:
            response.close()
        # Only trust a charset the server actually sent. requests falls back to
        # ISO-8859-1 for any text/* without one, which garbles UTF-8 pages; with no
        # header, BeautifulSoup reads the page's own <meta charset>.
        charset = re.search(r"charset=([\w-]+)", content_type, re.IGNORECASE)
        soup = BeautifulSoup(body, "html.parser", from_encoding=charset.group(1) if charset else None)
        return response.url, soup


# --- page parsing -------------------------------------------------------------------

_DROP_TAGS = ["script", "style", "noscript", "template", "svg", "head", "iframe", "select"]
# td/th are left out on purpose: "Sunday | 10:00 AM" across two cells should stay one line.
_BLOCK_TAGS = [
    "p", "div", "li", "ul", "ol", "tr", "table", "h1", "h2", "h3", "h4", "h5", "h6",
    "section", "article", "header", "footer", "nav", "main", "aside", "dd", "dt", "dl",
    "blockquote", "address", "form", "figure", "figcaption",
]


def page_title(soup: BeautifulSoup) -> str:
    return soup.title.get_text(" ") if soup.title else ""


def _glue(before: str, after: str) -> str:
    """What goes between two adjacent inline text pieces with no whitespace between them
    in the HTML. A browser would show them run together, but CSS often displays them
    apart: "<span>Pastor</span><span>Stephen</span>" reads "Pastor Stephen". A drop cap
    or highlight inside a word ("<span>S</span>unday") must not be split, though."""
    a, b = before[-1], after[0]
    if b in ".,:;!?)’'":
        return ""
    if not (a.isalpha() and b.isalpha()):
        return " "
    if a.islower() and b.isupper():
        return " "
    if a.isupper() and b.isupper() and len(before.split()[-1]) > 1:
        return " "
    return ""


def page_lines(soup: BeautifulSoup) -> List[str]:
    """Visible text, one line per block element, inline elements joined into the line
    (see _glue), so "<b>Sunday</b> 10am" stays one line: the unit service times are
    parsed on. Destructive: call it after anything else that reads the soup."""
    for tag in soup(_DROP_TAGS):
        tag.decompose()
    for br in soup.find_all("br"):
        br.replace_with("\n")
    for tag in soup.find_all(_BLOCK_TAGS):
        tag.insert_before("\n")
        tag.insert_after("\n")
    text = ""
    for piece in soup.get_text("\x00").split("\x00"):
        piece = piece.replace("\u200b", "").replace("\ufeff", "")
        if text and piece and not text[-1].isspace() and not piece[0].isspace():
            text += _glue(text, piece)
        text += piece
    return [line for line in (" ".join(raw.split()) for raw in text.split("\n")) if line]


_SERVICE_LINK = re.compile(
    r"mass|worship|service|times|schedule|visit|new[-_\s]?here|i'?m[-_\s]?new|horario|misa|liturg", re.I
)
_SERVICE_LINK_BEST = re.compile(r"times|schedule|mass|horario|misa", re.I)
_SERVICE_LINK_GOOD = re.compile(r"visit|new", re.I)
_PASTOR_LINK = re.compile(r"pastor|staff|clergy|leader|our[-_\s]?team|about|priest|rector|minister", re.I)
_PASTOR_LINK_BEST = re.compile(r"pastor|clergy|priest|rector", re.I)
_PASTOR_LINK_GOOD = re.compile(r"staff|leader|team|minister", re.I)
_SKIP_LINK = re.compile(
    r"\b(?:terms|privacy|past|archives?|sermons?|videos?|live|stream|watch|giving|give|donate|"
    r"events?|calendar|blog|news)\b|\.(?:pdf|jpe?g|png|gif|docx?|mp3|mp4|zip)$",
    re.I,
)


def _host(url: str) -> str:
    host = (urlparse(url).hostname or "").lower()
    return host[4:] if host.startswith("www.") else host


def page_links(soup: BeautifulSoup, base_url: str) -> List[Tuple[str, str]]:
    """Up to MAX_SUBPAGES (url, "service" | "pastor") same-site pages worth reading, best
    first: two about services, two about the pastor."""
    def key(url: str) -> str:
        return _host(url) + urlparse(url).path.rstrip("/").lower()

    seen = {key(base_url)}
    service: List[Tuple[int, int, str]] = []
    pastor: List[Tuple[int, int, str]] = []
    for position, anchor in enumerate(soup.find_all("a", href=True)):
        url = urldefrag(urljoin(base_url, anchor["href"].strip()))[0]
        if not url.startswith(("http://", "https://")) or _host(url) != _host(base_url):
            continue
        url = url.rstrip("/")
        text = " ".join(anchor.get_text(" ").split())
        path = urlparse(url).path
        label = f"{text} {path}"
        if key(url) in seen or _SKIP_LINK.search(text) or _SKIP_LINK.search(path.replace("-", " ").replace("_", " ")):
            continue
        if _SERVICE_LINK.search(label):
            rank = 0 if _SERVICE_LINK_BEST.search(label) else 1 if _SERVICE_LINK_GOOD.search(label) else 2
            service.append((rank, position, url))
            seen.add(key(url))
        elif _PASTOR_LINK.search(label):
            rank = 0 if _PASTOR_LINK_BEST.search(label) else 1 if _PASTOR_LINK_GOOD.search(label) else 2
            pastor.append((rank, position, url))
            seen.add(key(url))
    half = MAX_SUBPAGES // 2
    picked = [(url, "service") for _, _, url in sorted(service)[:half]]
    picked += [(url, "pastor") for _, _, url in sorted(pastor)[:MAX_SUBPAGES - len(picked)]]
    return picked


# --- name check ---------------------------------------------------------------------

_GENERIC_NAME_WORDS = {
    "the", "of", "and", "church", "churches", "chapel", "parish", "saint", "saints", "sts", "catholic",
    "roman", "baptist", "methodist", "united", "presbyterian", "lutheran", "episcopal", "orthodox",
    "christian", "community", "congregational", "evangelical", "pentecostal", "assembly", "assemblies",
    "god", "holy", "first", "second", "third", "new", "christ", "jesus", "ministries", "ministry",
    "mission", "fellowship", "center", "missionary", "reformed", "free", "will", "full", "gospel",
    "apostolic", "nazarene", "adventist", "seventh", "day", "greek", "american", "african",
    "international", "inc", "corps", "salvation", "army", "lady", "our", "san", "santa", "iglesia",
}


def _plain(text: str) -> str:
    """Lowercase ASCII words only, accents dropped: "Sábado" → "sabado"."""
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode().lower()
    return " ".join(re.sub(r"[^a-z0-9]+", " ", text).split())


def mentions_name(name: Optional[str], text: str) -> bool:
    """Whether a page plausibly belongs to this church: one of the name's distinctive
    words ("pius" in "Saint Pius X Catholic Church") appears on it, or, for an all-generic
    name ("First Baptist Church"), every word does."""
    words = [w for w in _plain(name or "").split() if len(w) >= 3]
    if not words:
        return False
    # Possessives drop out: "Saint Matthews" matches "St. Matthew's" (→ "matthew s").
    page = {w[:-1] if w.endswith("s") else w for w in _plain(text).split()}
    words = [w[:-1] if w.endswith("s") else w for w in words]
    distinctive = [w for w in words if w not in _GENERIC_NAME_WORDS]
    if distinctive:
        return any(w in page for w in distinctive)
    return all(w in page for w in words if w not in ("the", "and"))


# --- location check -----------------------------------------------------------------

_STATE_NAMES = {
    "AL": "Alabama", "AK": "Alaska", "AZ": "Arizona", "AR": "Arkansas", "CA": "California",
    "CO": "Colorado", "CT": "Connecticut", "DE": "Delaware", "DC": "District of Columbia",
    "FL": "Florida", "GA": "Georgia", "HI": "Hawaii", "ID": "Idaho", "IL": "Illinois", "IN": "Indiana",
    "IA": "Iowa", "KS": "Kansas", "KY": "Kentucky", "LA": "Louisiana", "ME": "Maine", "MD": "Maryland",
    "MA": "Massachusetts", "MI": "Michigan", "MN": "Minnesota", "MS": "Mississippi", "MO": "Missouri",
    "MT": "Montana", "NE": "Nebraska", "NV": "Nevada", "NH": "New Hampshire", "NJ": "New Jersey",
    "NM": "New Mexico", "NY": "New York", "NC": "North Carolina", "ND": "North Dakota", "OH": "Ohio",
    "OK": "Oklahoma", "OR": "Oregon", "PA": "Pennsylvania", "RI": "Rhode Island", "SC": "South Carolina",
    "SD": "South Dakota", "TN": "Tennessee", "TX": "Texas", "UT": "Utah", "VT": "Vermont",
    "VA": "Virginia", "WA": "Washington", "WV": "West Virginia", "WI": "Wisconsin", "WY": "Wyoming",
}
_STATE_CODE_BY_NAME = {name.lower(): code for code, name in _STATE_NAMES.items()}
# The state in an address: "Copperton, UT 84006" or "Copperton, Utah".
_ADDRESS_STATE = re.compile(
    r",\s+(?:(?P<code>[A-Z]{2})\.?\s+\d{5}\b|(?P<name>"
    + "|".join(sorted(_STATE_NAMES.values(), key=len, reverse=True)) + r")\b)"
)


def other_state_only(state: Optional[str], text: str) -> bool:
    """Whether every address on the site is in some other state than the church's. A
    mistagged website can belong to a same-named church elsewhere ("Immaculate Conception"
    in Cranston, RI tagged with the one in Copperton, Utah), which the name check passes.
    A site with no address at all is given the benefit of the doubt."""
    found = set()
    for match in _ADDRESS_STATE.finditer(text):
        code = match.group("code") or _STATE_CODE_BY_NAME[match.group("name").lower()]
        if code in _STATE_NAMES:
            found.add(code)
    return bool(state and found and state.upper() not in found)


# --- service times ------------------------------------------------------------------

_DAY_NAMES = ("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
_DAY_WORDS = {
    "monday": 0, "mon": 0, "lunes": 0,
    "tuesday": 1, "tues": 1, "tue": 1, "martes": 1,
    "wednesday": 2, "wed": 2, "miercoles": 2, "miércoles": 2,
    "thursday": 3, "thurs": 3, "thur": 3, "thu": 3, "jueves": 3,
    "friday": 4, "fri": 4, "viernes": 4,
    "saturday": 5, "sat": 5, "sabado": 5, "sábado": 5, "sabbath": 5,
    "sunday": 6, "sun": 6, "domingo": 6,
}
_DAY = r"(?:" + "|".join(sorted(_DAY_WORDS, key=len, reverse=True)) + r")(?:['’]?s)?\.?"
_DAY_GROUP = re.compile(
    rf"\b(?:(?P<weekdays>weekdays?)|(?P<a>{_DAY})(?:\s*(?:-|–|—|through|thru|to)\s*(?P<b>{_DAY}))?)(?!\w)"
)
_TIME = re.compile(
    r"(?<![\w:/])(?<!\d\.)(?P<h>\d{1,2})(?::(?P<m>\d{2}))?(?!\d)\s*(?:(?P<mer>[ap])\.?\s?m\b\.?|(?P<noon>noon)\b)?"
    r"|\b(?P<noon_word>noon)\b"
)
# Labels for a different schedule altogether: these reject even "Sunday Mass 10am" under them.
_SEASONAL = re.compile(
    r"holy day|holiday|christmas|easter|thanksgiving|\blent|advent|holy week|palm sunday|good friday|"
    r"holy (?:thursday|saturday)|triduum|new year|summer|winter"
)
_MIXED_HEADING = re.compile(r"\bmass(?:es)?\b|\bmisas?\b|worship|\bservices?\b|liturg")
_CLOCK = re.compile(r"\d\s*[ap]\.?\s?m\b|\bnoon\b|\b\d{1,2}:\d\d\b")
_MASS = re.compile(r"\bmass(?:es)?\b|\bmisas?\b")
_LIST_GAP = re.compile(r"^\s*(?:,|&|\+|/|and|or)?\s*(?:,|&|and|or)?\s*$")
_RANGE_GAP = re.compile(r"^\s*(?:-|–|—|to|until|till)\s*$")
# Parenthesized asides with a number or a day in them are alternate times or notes
# ("10:00 AM (9:00 AM in summer)", "Saturday (Sunday Vigil)"), not services. "(Spanish)" stays.
_PARENS = re.compile(r"\([^)]*(?:\d|day\b)[^)]*\)")
# A range split over three lines: "3:00 PM" / "to" / "3:45 PM".
_RANGE_LINE = re.compile(r"^(?:-|–|—|to|until|till)$", re.I)

_WORSHIP = re.compile(
    r"worship|\bservices?\b|\bmass(?:es)?\b|\bmisas?\b|liturg|eucharist|\bvigil|vespers|matins|orthros|"
    r"divine|sacrament meeting|gathering|celebration|celebraci|\bculto|servicio|join us|bible study|"
    r"estudio b[ií]blico|holy communion|\brite\b|traditional|contemporary"
)
# Anything these mention is not a weekly service, or not one we can place on a day:
# office hours, classes, meals, sacraments by appointment, broadcasts, seasonal or
# monthly schedules, and anything timed relative to something else ("after the service").
_EXCLUDE = re.compile(
    r"office|oficina|\bhours\b|school|escuela|class|coffee|fellowship hour|fellowship time|breakfast|"
    r"lunch|dinner|supper|potluck|rehearsal|practice|(?<!sacrament )meeting|youth|\bkids?\b|"
    r"children|nursery|student|\bteens?\b|confession|reconciliation|penance|adoration|rosary|novena|"
    r"stations of|pantry|bingo|check-in|\bdrill|wedding|funeral|baptism|registration|deadline|closed|"
    r"\buntil\b|\bafter\b|following|\bbefore\b|\bprior\b|doors open|broadcast|radio|channel|\btv\b|"
    r"television|replay|recorded|holy day|holiday|christmas|easter|thanksgiving|\blent|advent|"
    r"ash wednesday|summer|winter|first (?:mon|tue|wed|thu|fri|sat|sun)|\b(?:1st|2nd|3rd|4th|5th|last)\b|"
    r"once a month|monthly|every other|\bmonth\b|seniors|\bmen'?s\b|women'?s|ladies|small group|"
    r"\bgroups?\b|zoom|\btours?\b|bulletin|\bvbs\b|\bcamp\b|concert|festival|\bfair\b|\bsale\b|"
    r"community service|service project|parking|shuttle|tickets?|\bwatch\b|cable|"
    r"[uú]ltimo|primer|\bmes\b|mensual|\b1er\b|confesi|bautism|bautizo|quincea|catecismo|clases?\b|appointment|cita\b"
)
# Not a service on their own ("Prayer Gathering 9am"), but part of one in "Evening Prayer &
# Low Mass" or "Solemn Mass with Choir".
_SOFT_EXCLUDE = re.compile(r"prayer|oraci[oó]n|choir|coro\b")
_NAMES_SERVICE = re.compile(r"\bmass(?:es)?\b|\bmisas?\b|eucharist|worship|liturg|divine service")
# Checked before parenthesized asides are dropped: "10 AM Low Mass (3rd Saturday of the month)".
_MONTHLY = re.compile(r"\b(?:1st|2nd|3rd|4th|5th|first|second|third|fourth|last)\b.*\bmonth\b|monthly|mensual")
# A subheading naming a church: on a site shared by several churches ("St. Mary of the
# Assumption", "St. Henry"), the times under it belong to that church. "Holy ..." and the
# like only count with "Church" etc. at the end; "Holy Orders" is a menu item.
_PLACE_START = re.compile(r"^(?:st|sts|ss|saint|saints|san|santa|nuestra|our lady)\b")
_PLACE_END = re.compile(r"\b(?:church|chapel|parish|cathedral|campus|basilica|shrine)$")
_NOT_PLACE = re.compile(r"welcome|about|visit|join|\bour\b(?! lady)|\byour\b|\bthis\b")
# A place attached to one time in a list: "8 am, 9:15 am (St. Alexander Church), 10:30 am".
_TIME_AT_PLACE = re.compile(
    r"\d{1,2}(?::\d{2})?\s*[ap]\.?\s?m\b\.?(?P<place>\s*(?:,|\(|@|\bat\b|[-–—])\s*"
    r"(?P<name>(?:st|sts|saint|our lady|holy)\b[^\d,;)]*)\)?)"
)
_AT_PLACE = re.compile(
    r"(?:\bat|@|[-–—,(])\s*((?:st|sts|saint|our lady|holy)\b[^\d)]*)\)?"  # "10:30 am at St. Mary", "9:15 am, St. Alexander"
    r"|^((?:st|sts|saint|our lady|holy)\b[^–—:-]{0,40}?)\s*[–—:-]"  # "St. Joan of Arc – Monday 9 am"
)
# A specific date ("Oct 4", "10/4", "2026") makes it a one-off event, not the weekly schedule.
_DATED = re.compile(
    r"\b(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|"
    r"sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)(?![a-z])\.?\s*\d|"
    r"\b\d{1,2}[/-]\d{1,2}\b(?!:)|\b20\d\d\b"
)
_FILLER_WORDS = {
    "at", "and", "or", "on", "in", "the", "every", "each", "morning", "mornings", "evening",
    "evenings", "night", "nights", "afternoon", "afternoons", "a", "p", "m", "am", "pm", "est", "cst",
    "ct", "et", "pt", "mt", "edt", "cdt", "pdt", "mdt", "english", "spanish", "espanol", "español",
    "en", "es", "bilingual",
}
_LANGUAGES = (
    ("Spanish", re.compile(r"spanish|espa[ñn]ol|\bmisas?\b|domingo|s[áa]bado|\bculto|servicio")),
    ("Portuguese", re.compile(r"portugu")),
    ("Vietnamese", re.compile(r"vietnam")),
    ("Korean", re.compile(r"korean")),
    ("Chinese", re.compile(r"chinese|mandarin|cantonese")),
    ("Haitian Creole", re.compile(r"haitian|creole|krey[oò]l")),
    ("French", re.compile(r"french|fran[cç]ais")),
    ("Polish", re.compile(r"polish|polski")),
    ("Italian", re.compile(r"italian")),
    ("Latin", re.compile(r"\blatin\b(?! rite)")),
    ("Tagalog", re.compile(r"tagalog|filipino")),
)
# Earliest and latest start times believed, in minutes past midnight (5:00am-9:30pm).
_EARLIEST, _LATEST = 5 * 60, 21 * 60 + 30
_MAX_SERVICE_TIMES = 20
_MAX_LINE_CHARS = 200


def _expand(start: int, end: Optional[int] = None) -> List[int]:
    if end is None:
        return [start]
    return [(start + i) % 7 for i in range((end - start) % 7 + 1)]


def _day_index(word: str) -> int:
    """"Sundays", "Sunday's", "Tues." → the day's index."""
    word = word.rstrip(".")
    return _DAY_WORDS[word if word in _DAY_WORDS else re.sub(r"['’]?s$", "", word)]


def _minutes(hour: int, minute: int, meridiem: str) -> Optional[int]:
    if not 1 <= hour <= 12:
        return None
    if meridiem == "a":
        hour = 0 if hour == 12 else hour
    else:
        hour = hour if hour == 12 else hour + 12
    return hour * 60 + minute


Token = Tuple[int, int, str, object]


def _tokens(line: str) -> List[Token]:
    """(start, end, "day" | "time", value) in line order: a day token's value is a list of
    day indexes (Monday=0), a time token's is minutes past midnight. Ranges ("9-11:30am")
    and bare clock times that can't inherit an am/pm from the next time are dropped."""
    days = []
    weekday_spans = set()
    for match in _DAY_GROUP.finditer(line):
        if match.group("weekdays"):
            value = _expand(0, 4)
            weekday_spans.add(match.start())
        else:
            end = match.group("b")
            value = _expand(_day_index(match.group("a")), _day_index(end) if end else None)
        days.append((match.start(), match.end(), "day", value))

    raw = []  # [start, end, hour, minute, meridiem or None, has_colon]
    for match in _TIME.finditer(line):
        if match.group("noon_word"):
            raw.append([match.start(), match.end(), 12, 0, "p", True])
            continue
        meridiem = "p" if match.group("noon") else match.group("mer")
        minute = match.group("m")
        raw.append([match.start(), match.end(), int(match.group("h")), int(minute or 0), meridiem, minute is not None])

    in_range = set()
    for i in range(len(raw) - 1):
        if _RANGE_GAP.match(line[raw[i][1]:raw[i + 1][0]]):
            in_range.update((i, i + 1))

    times: List[Optional[int]] = [None] * len(raw)
    for i in range(len(raw) - 1, -1, -1):
        _, end, hour, minute, meridiem, has_colon = raw[i]
        if i in in_range or minute > 59:
            continue
        if meridiem:
            times[i] = _minutes(hour, minute, meridiem)
        elif has_colon and 13 <= hour <= 23:
            times[i] = hour * 60 + minute
        elif has_colon and i + 1 < len(raw) and times[i + 1] is not None and _LIST_GAP.match(line[end:raw[i + 1][0]]):
            # "8:30 & 11:00 AM": take the next time's half of the day, unless that would
            # put this one after it ("11:00 & 1:00 PM" is 11am, not 11pm).
            following = times[i + 1]
            for guess in ("p", "a") if following >= 12 * 60 else ("a",):
                value = _minutes(hour, minute, guess)
                if value is not None and value < following:
                    times[i] = value
                    break

    # "Weekdays: Monday, Tuesday, Thursday & Friday" means the days listed, not Mon-Fri too.
    if len(days) > 1:
        days = [day for day in days if not weekday_spans or day[0] not in weekday_spans]
    tokens = days + [(r[0], r[1], "time", t) for r, t in zip(raw, times) if t is not None]
    return sorted(tokens, key=lambda token: token[0])


def _assign(tokens: List[Token], carried: Optional[List[int]]) -> List[Tuple[int, Token]]:
    """(day, time token) pairs. Days usually come first ("Sunday 9 & 11am, Wednesday 7pm");
    a line that starts with a time and ends with a day is read the other way round
    ("11:30 a.m. Domingo"). Times before the first day of a day-first line are dropped:
    that's two table cells run together ("Vigil 4:00 PM Monday - Saturday 8:00 AM").
    A line with no day at all uses the day from the heading above it, if any."""
    kinds = [token[2] for token in tokens]
    if "day" not in kinds:
        return [(day, token) for day in carried or [] for token in tokens]

    pairs: List[Tuple[int, Token]] = []
    if kinds[0] == "time" and kinds[-1] == "day":
        pending: List[Token] = []
        last_days: List[int] = []
        for token in tokens:
            if token[2] == "time":
                pending.append(token)
            elif pending:
                pairs += [(day, t) for day in token[3] for t in pending]
                last_days, pending = list(token[3]), []
            else:  # "10am Saturday & Sunday": the second day joins the first
                pairs += [(day, t) for day in token[3] for (d, t) in list(pairs) if d in last_days]
        return pairs

    days: List[int] = []
    timed = False
    for token in tokens:
        if token[2] == "day":
            days = list(token[3]) if timed else days + list(token[3])
            timed = False
        else:
            pairs += [(day, token) for day in days]
            timed = True
    return pairs


def _is_bare(line: str, tokens: List[Token]) -> bool:
    """Nothing but days, times and filler ("Sunday Evening 7:00 pm"), so whether it's a
    service depends on the heading above it."""
    rest = list(line)
    for start, end, _, _ in tokens:
        rest[start:end] = " " * (end - start)
    return all(word in _FILLER_WORDS for word in re.findall(r"[^\W\d_]+", "".join(rest)))


def _language(text: str) -> Optional[str]:
    for language, pattern in _LANGUAGES:
        if pattern.search(text):
            return language
    return None


def _languages(line: str, tokens: List[Token]) -> Dict[int, Optional[str]]:
    """{time token start: language}. A language named after a time belongs to that time
    alone ("9am English, 11am Spanish"); one named before the first time, to every time
    that doesn't name its own ("Spanish Mass: Sunday 9 & 11am")."""
    times = [token for token in tokens if token[2] == "time"]
    lead = _language(line[:times[0][0]])
    ends = [token[0] for token in times[1:]] + [len(line)]
    return {token[0]: _language(line[token[1]:end]) or lead for token, end in zip(times, ends)}


def _types(line: str, tokens: List[Token], default: str) -> Dict[int, str]:
    """{time token start: service type}, read from the text just before each time:
    "Sunday Worship 11 AM, Wednesday Bible Study 7 PM" is one of each. A time joined to
    the one before by "&"/"and" shares its type ("Bible Study 6:30 & 7:30 pm")."""
    types: Dict[int, str] = {}
    previous, start = default, 0
    times = [token for token in tokens if token[2] == "time"]
    for i, token in enumerate(times):
        text = line[start:token[0]]
        if i == 0 and not text.strip():  # "7pm Bible Study": the label follows
            text = line[token[1]:times[1][0] if len(times) > 1 else len(line)]
        if "bible study" in text or "estudio b" in text:
            kind = "Bible Study"
        elif set(re.findall(r"[^\W\d_]+", text)) <= {"and", "or", "at"}:
            kind = previous
        else:
            kind = default
        types[token[0]] = previous = kind
        start = token[1]
    return types


def _join_ranges(lines: Iterable[str]) -> List[str]:
    joined: List[str] = []
    pending_join = False
    for line in lines:
        if pending_join and joined:
            joined[-1] = f"{joined[-1]} to {line}"
            pending_join = False
        elif _RANGE_LINE.match(line.strip()) and joined:
            pending_join = True
        else:
            joined.append(line)
    return joined


def _drop_other_places(line: str, name: str) -> str:
    """Each time followed by a place: drop the time if the place is another church,
    otherwise just the place. "8 am, 9:15 am (St. Alexander Church), 10:30 am" → "8 am, 10:30 am"
    for St. Mary of the Bay."""
    def keep_or_drop(match: "re.Match[str]") -> str:
        if mentions_name(name, match.group("name")):
            return match.group(0)[:match.start("place") - match.start()]
        return " "
    return _TIME_AT_PLACE.sub(keep_or_drop, line)


def _place(line: str, tokens: List[Token]) -> bool:
    text = line.strip(" :-–—")
    return bool(
        not tokens and 2 <= len(text.split()) <= 8 and (_PLACE_START.search(text) or _PLACE_END.search(text))
        and not (_NOT_PLACE.search(text) or _WORSHIP.search(text) or _EXCLUDE.search(text))
    )


def extract_service_times(lines: Iterable[str], catholic: bool = False, name: Optional[str] = None) -> List[dict]:
    """ServiceTimeDto dicts for one page's weekly schedule, or [] if there's nothing
    believable. A time line counts if it names a service itself ("Sunday Worship 10am")
    or is bare ("Sunday 10:00 am") under a heading that does ("Worship Times"). With the
    church's name, times under a subheading naming a different church are skipped."""
    headings: deque = deque(maxlen=3)
    # The few label lines since the last time line. Normally only the nearest one decides
    # ("Confessions" over "Saturday 3:00 PM"). But when a page lays labels and times out in
    # two columns ("Confessions:", "Rosary:", "Saturday Vigil:" then "3:00", "3:30",
    # "4:00") they can't be paired up, so any excluded label rejects all the times.
    labels: deque = deque(maxlen=3)
    after_time = False
    carried: Optional[List[int]] = None
    ours = True
    found: Dict[Tuple[int, int], dict] = {}

    lines = _join_ranges(lines)
    clocks = [len(line) <= _MAX_LINE_CHARS and bool(_CLOCK.search(line.lower())) for line in lines]
    for index, original in enumerate(lines):
        line = _PARENS.sub(" ", original).lower()
        if name:
            line = _drop_other_places(line, name)
        tokens = _tokens(line) if len(line) <= _MAX_LINE_CHARS else []
        # A line whose only times were a dropped range ("Saturday 4:30 - 5:45 pm") is
        # still a time line, not a heading for the lines below it.
        has_time = clocks[index]
        dangling = index > 0 and re.search(r"(?:@|\bat)$", lines[index - 1].lower())
        if not has_time:
            # A subheading is directly over a schedule; a place right after "8:00 am @"
            # finishes that line instead.
            if name and not dangling and any(clocks[index + 1:index + 3]) and _place(line, tokens):
                # Not a heading or a day change: "Sunday Masses" still applies below it.
                ours = mentions_name(name, line)
                continue
            # A long line with a "date" is more likely a Bible verse ("Romans 8:32-36").
            excluded = bool(_EXCLUDE.search(line) or (len(line) <= 40 and _DATED.search(line)))
            starts_run = after_time
            if after_time:
                labels.clear()
                after_time = False
            under_excluded = bool(labels) and labels[-1][1] and not _WORSHIP.search(line)
            # "Mass, Confession and Liturgical Times" heads the Masses too.
            labels.append((line.endswith(":"), excluded and not _MIXED_HEADING.search(line), bool(_SEASONAL.search(line))))
            bare = _is_bare(line, tokens)
            # Bare lines ("Sunday:", "English") would only push real headings out.
            if len(line) <= 80 and not bare:
                headings.append(line)
            # A day heading carries down to the times under it, unless it's under an
            # excluded label ("Office Hours:" / "Monday to Thursday"). It stops at a long
            # line, or at a new non-service label after some times ("Kids Programming:").
            if tokens and len(line) <= 40 and not excluded and not under_excluded:
                carried = [day for token in tokens for day in token[3]]
            elif len(line) > 20 or (starts_run and not bare and not _WORSHIP.search(line)):
                carried = None
            continue

        after_time = True
        if not any(token[2] == "time" for token in tokens):
            continue
        # "10:30 am @ St. Mary": the place named on the line decides, not the subheading.
        # "8:00 am @" may leave the place to the next line.
        where = line
        if re.search(r"(?:@|\bat)$", line) and index + 1 < len(lines):
            where = f"{line} {lines[index + 1].lower()}"
        at_place = _AT_PLACE.search(where)
        if name and at_place:
            if not mentions_name(name, at_place.group(1) or at_place.group(2)):
                continue
            # The place is fine; judge the rest of the line without it.
            line = line[:at_place.start()] + line[at_place.end():]
            tokens = _tokens(line)
            if not any(token[2] == "time" for token in tokens):
                continue
        elif not ours:
            continue
        if _DATED.search(line) or _EXCLUDE.search(line) or _MONTHLY.search(original.lower()):
            continue
        if _SOFT_EXCLUDE.search(line) and not _NAMES_SERVICE.search(line):
            continue
        # An excluded label only matters to a line that doesn't name its own service:
        # "Sunday Mass 9 am" right under "Confessions: Saturday 3 pm" is still a Mass.
        # A seasonal one ("Christmas Schedule") matters to every line under it.
        named = bool(_WORSHIP.search(line))
        nearest = list(labels)[-1:] if sum(colon for colon, _, _ in labels) < 2 else list(labels)
        if any(seasonal or (bad and not named) for _, bad, seasonal in nearest):
            continue
        if not (named or (_is_bare(line, tokens) and any(_WORSHIP.search(h) for h in headings))):
            continue

        mass = catholic or bool(_MASS.search(line)) or any(_MASS.search(h) for h in headings)
        types = _types(line, tokens, "Mass" if mass else "Worship")
        languages = _languages(line, tokens)
        for day, token in _assign(tokens, carried):
            minutes, language, service_type = token[3], languages[token[0]], types[token[0]]
            if not _EARLIEST <= minutes <= _LATEST or (day, minutes) in found:
                continue
            entry = {"dayOfWeek": _DAY_NAMES[day], "time": f"{minutes // 60:02d}:{minutes % 60:02d}", "type": service_type}
            if language:
                entry["language"] = language
            found[(day, minutes)] = entry

    times = [found[key] for key in sorted(found)]
    weekend = any(t["dayOfWeek"] in ("Saturday", "Sunday") and t["type"] != "Bible Study" for t in times)
    if not weekend or len(times) > _MAX_SERVICE_TIMES:
        return []
    return times


# --- pastor -------------------------------------------------------------------------

_LEAD_TITLE = (
    r"(?:senior|lead|head)\s+(?:pastor|minister)|\bpastor\b|\brector\b|priest[-\s]in[-\s]charge|"
    r"parochial administrator|parish priest"
)
_TITLE = re.compile(rf"(?:{_LEAD_TITLE})", re.I)
_TITLE_LINE = re.compile(rf"^(?:our\s+|the\s+)?(?:{_LEAD_TITLE})\s*[:\-–—]?$", re.I)
_NOT_LEAD = re.compile(
    r"\b(?:associate|assistant|asst|youth|children'?s?|kids?|worship|music|executive|student|students|"
    r"famil(?:y|ies)|women'?s|men'?s|care|outreach|missions?|emerit\w*|former|retired|late|visiting|guest|"
    r"deacon|teaching|discipleship|connections?|groups?|college|young|adults?|spanish|hispanic|campus|"
    r"pastoral|interns?|contact|search|new|education|formation|life|administration|engagement|"
    r"evangelism|spiritual|congregational|ministries|ministry|operations|equipping|community)\b",
    re.I,
)
# Any staff title, to tell which way round a staff list is laid out.
_STAFF_TITLE = re.compile(
    r"\b(?:pastor|minister|director|deacon|priest|rector|administrator|secretary|coordinator|elder|"
    r"bishop|vicar|assistant|associate|manager|organist|treasurer)\b",
    re.I,
)
_HONORIFICS = {
    "rev": "Rev.", "reverend": "Rev.", "dr": "Dr.", "fr": "Fr.", "father": "Fr.",
    "msgr": "Msgr.", "monsignor": "Msgr.", "bishop": "Bishop",
    "the": None, "very": None, "right": None, "most": None, "canon": None, "archimandrite": None,
    "hieromonk": None, "protopresbyter": None, "deacon": None, "pastor": None, "elder": None, "mr": None,
    "mrs": None, "ms": None,
}
_SUFFIXES = {"jr", "sr", "ii", "iii", "iv"}
_NOT_NAME_WORDS = {
    "church", "office", "corner", "message", "bio", "biography", "team", "staff", "search", "sermon",
    "sermons", "series", "blog", "notes", "page", "letter", "desk", "welcome", "meet", "our", "of", "and",
    "contact", "email", "phone", "click", "read", "more", "home", "about", "pastor", "pastors", "rev",
    "ministries", "ministry", "support", "appreciation", "anniversary", "installation", "update",
    "updates", "announcement", "us", "here", "new", "chat", "podcast", "family", "wife", "his", "her",
    "bishop", "parish", "catholic", "baptist", "methodist", "st", "saint", "holy", "christ", "god",
    "jesus", "lord", "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday",
    # Navigation-menu words, so "Pastor" next to "Plan Your Visit" in a menu isn't a staff card.
    "plan", "your", "visit", "give", "online", "watch", "live", "events", "connect", "prayer",
    "request", "requests", "beliefs", "believe", "what", "we", "who", "are", "mission", "vision",
    "history", "location", "locations", "directions", "times", "service", "services", "worship",
    "mass", "news", "calendar", "media", "resources", "groups", "get", "involved", "next", "steps",
    "gallery", "photos", "store", "shop", "donate", "giving", "members", "member", "login", "portal",
    "app", "bulletins", "bulletin", "newsletter", "school", "preschool", "youth", "kids", "children",
    "women", "men", "music", "choir", "outreach", "missions", "care", "leadership", "elders", "deacons",
    "council", "vestry", "sacraments", "faith", "formation", "religious", "education", "ministries",
    "deacon", "deacons", "sister", "brother", "bible", "study", "studies", "training", "class",
    "classes", "small", "group", "prayer", "worship", "fellowship", "volunteer", "volunteers", "close", "open", "menu", "toggle", "skip", "navigation", "main", "content", "search", "back", "top",
}
_INITIAL = re.compile(r"(?:[^\W\d_]\.){1,3}|[A-Z]{1,2}")


def _word_kind(token: str) -> Optional[str]:
    """"word", "initial", "suffix" or None for a token that can't be part of a name."""
    bare = token.rstrip(".,").lower()
    if bare in _SUFFIXES:
        return "suffix"
    if _INITIAL.fullmatch(token.rstrip(",")):
        return "initial"
    word = token.rstrip(",.")
    if (
        len(word) >= 2 and word[0].isupper() and any(c.islower() for c in word)
        and all(c.isalpha() or c in "'’-" for c in word) and bare not in _NOT_NAME_WORDS
    ):
        return "word"
    return None


def _name(tokens: List[str]) -> Optional[Tuple[Tuple[str, str], str]]:
    """((first, last) key, display name) if these tokens, in full, are a person's name
    with optional clerical honorifics in front ("Rev. Dr. Jane Q. Doe Jr.")."""
    titles: List[str] = []
    index = 0
    while index < len(tokens) and tokens[index].rstrip(".,").lower() in _HONORIFICS:
        honorific = _HONORIFICS[tokens[index].rstrip(".,").lower()]
        if honorific and honorific not in titles:
            titles.append(honorific)
        index += 1
    words = tokens[index:]
    if _STAFF_TITLE.search(" ".join(words)):
        return None
    kinds = [_word_kind(w) for w in words]
    if not 2 <= len(words) <= 5 or None in kinds or "word" not in kinds:
        return None
    if kinds[0] == "suffix" or [k for k in kinds if k != "suffix"][-1] != "word":
        return None
    display = [w.rstrip(",.") if k == "word" else w.rstrip(",") for w, k in zip(words, kinds)]
    core = [w for w, k in zip(display, kinds) if k != "suffix"]
    key = (core[0].rstrip(".").lower(), core[-1].lower())
    return key, " ".join(titles + display)


def _name_after(text: str) -> Optional[Tuple[Tuple[str, str], str]]:
    """The longest name at the start of text: "Luis Morales" from "Luis Morales preaches"."""
    tokens = text.split()[:7]
    for length in range(len(tokens), 1, -1):
        name = _name(tokens[:length])
        if name:
            # "Pastor Synodically Authorized Minister" is a title, not "Synodically Authorized".
            if length < len(tokens) and _STAFF_TITLE.fullmatch(tokens[length].strip(",.:;")):
                return None
            return name
    return None


def _name_before(text: str) -> Optional[Tuple[Tuple[str, str], str]]:
    """The longest name at the end of text: "Rev. Jane Doe" from "Welcome Rev. Jane Doe"."""
    tokens = text.split()[-7:]
    for start in range(len(tokens) - 1):
        name = _name(tokens[start:])
        if name:
            return name
    return None


def _not_lead(line: str, start: int, end: int) -> bool:
    """A qualifier just before the title ("Youth Pastor") or a "Pastor of/for <ministry>"
    just after makes it someone other than the lead pastor."""
    before = " ".join(line[:start].split()[-2:])
    after = re.match(r"\s*(?:of|for|to)\s+(\S+)", line[end:])
    return bool(_NOT_LEAD.search(f"{before} {line[start:end]}") or (after and _NOT_LEAD.search(after.group(1))))


def _inline_pastors(line: str) -> List[Tuple[Tuple[str, str], str, bool]]:
    line = re.sub(r"(?<=[a-z])['’]s\b", "", line)  # "Pastor John Coleman’s sermon"
    found = []
    for match in _TITLE.finditer(line):
        if _not_lead(line, match.start(), match.end()):
            continue
        senior = not match.group(0).lower().startswith(("pastor", "rector", "priest", "parochial", "parish"))
        # "Pastor Luis Morales", "Senior Pastor: Rev. Dr. John Smith"
        after = re.match(r"\s*[:\-–—|,]?\s*(?:is\s+|was\s+)?(.*)", line[match.end():]).group(1)
        name = _name_after(after)
        if name:
            found.append((*name, senior))
            continue
        # "Rev. Jane Doe, Senior Pastor", "John Smith - Lead Pastor"
        before = line[:match.start()].rstrip()
        if before.endswith((",", "-", "–", "—", "|")):
            name = _name_before(before[:-1])
            if name:
                found.append((*name, senior))
    return found


def extract_pastor(pages: Iterable[List[str]]) -> Optional[str]:
    """The lead pastor's name across all of a site's pages, or None if there isn't
    exactly one (preferring "Senior/Lead Pastor" when several people are "Pastor")."""
    candidates: Dict[Tuple[str, str], str] = {}
    senior: Set[Tuple[str, str]] = set()

    def add(key: Tuple[str, str], display: str, is_senior: bool) -> None:
        if len(display) > len(candidates.get(key, "")):
            candidates[key] = display
        if is_senior:
            senior.add(key)

    for lines in pages:
        for line in lines:
            if len(line) <= _MAX_LINE_CHARS:
                for key, display, is_senior in _inline_pastors(line):
                    add(key, display, is_senior)

        # Staff cards: a title alone on its line, the name on the line above or below.
        # Which one depends on the layout, so count the page's staff titles both ways.
        above = below = 0
        for i, line in enumerate(lines):
            if len(line) <= 40 and _STAFF_TITLE.search(line):
                above += i > 0 and _name(lines[i - 1].split()) is not None
                below += i + 1 < len(lines) and _name(lines[i + 1].split()) is not None
        if above == below:
            continue
        offset = -1 if above > below else 1
        for i, line in enumerate(lines):
            match = _TITLE_LINE.match(line)
            if not match or _NOT_LEAD.search(line) or not 0 <= i + offset < len(lines):
                continue
            name = _name(lines[i + offset].split())
            if name:
                add(*name, bool(re.search(r"senior|lead|head", line, re.I)))

    keys = list(candidates)
    if len(keys) > 1:
        keys = [key for key in keys if key in senior]
    return candidates[keys[0]] if len(keys) == 1 else None


# --- description --------------------------------------------------------------------

_BAD_DESCRIPTION = re.compile(
    r"wordpress|squarespace|\bwix\b|weebly|godaddy|just another|lorem ipsum|\btheme\b|website builder|"
    r"powered by|coming soon|under construction|not found|\b404\b|default|followers|talking about this|"
    r"cookies?|javascript|log ?in|sign ?in|\bdomain\b|home ?page|web ?site of|@| \| |follow us|facebook|instagram|"
    r"\(?\b\d{3}\)?[-.\s]\d{3}[-.]\d{4}\b",
    re.I,
)


def page_description(soup: BeautifulSoup) -> Optional[str]:
    """The site's own one-paragraph description, if it has one that reads like a real
    description: JSON-LD first, then <meta name=description>, then og:description."""
    candidates = [_from_json_ld(soup).get("description")]
    for attrs in ({"name": "description"}, {"property": "og:description"}):
        tag = soup.find("meta", attrs=attrs)
        candidates.append(tag.get("content") if tag else None)
    for candidate in candidates:
        if not isinstance(candidate, str):
            continue
        text = " ".join(candidate.split())
        if (
            60 <= len(text) <= 1000 and len(text.split()) >= 8 and not text.isupper()
            and not text.endswith(("...", "…")) and not _BAD_DESCRIPTION.search(text)
        ):
            return text
    return None


# --- crawling -----------------------------------------------------------------------

def site_key(website: Optional[str]) -> Optional[str]:
    """host + path, ignoring scheme, "www." and a trailing slash, so two churches listing
    the same site compare equal. None for no website or a social/profile host."""
    if _blank(website):
        return None
    url = website.strip()
    if not re.match(r"^https?://", url, re.I):
        url = f"http://{url}"
    host = _host(url)
    if not host or any(host == skip or host.endswith(f".{skip}") for skip in SKIP_HOSTS):
        return None
    return f"{host}{urlparse(url).path.rstrip('/').lower()}"


def _crawl(church: dict, fetcher: Fetcher) -> dict:
    url = church["website"].strip()
    if not re.match(r"^https?://", url, re.I):
        url = f"http://{url}"
    home = fetcher.get(url)
    if home is None:
        return {"status": "robots" if fetcher.blocked else "unreachable"}

    home_url, soup = home
    links = page_links(soup, home_url)
    description = page_description(soup)
    title = page_title(soup)
    home_lines = page_lines(soup)
    if not mentions_name(church.get("name"), " ".join([title] + home_lines)):
        return {"status": "name-mismatch"}

    service_pages: List[List[str]] = []
    other_pages: List[List[str]] = []
    if fetcher.delay > MAX_CRAWL_DELAY_S:
        links = []
    for link, kind in links:
        page = fetcher.get(link)
        if page:
            (service_pages if kind == "service" else other_pages).append(page_lines(page[1]))

    every_line = [title] + home_lines + [line for lines in service_pages + other_pages for line in lines]
    if other_state_only(church.get("state"), "\n".join(every_line)):
        return {"status": "other-state"}

    # A dedicated service-times page beats the homepage's summary; between several, the
    # fullest. Schedules from different pages are never merged (one may be out of date).
    catholic = _service_type(church.get("denomination")) == "Mass"
    name = church.get("name")
    schedules = [extract_service_times(lines, catholic, name) for lines in service_pages]
    service_times = max(schedules, key=len, default=[]) or extract_service_times(home_lines, catholic, name)

    fields = {
        "serviceTimes": service_times,
        "pastor": extract_pastor([home_lines] + service_pages + other_pages),
        "description": description,
    }
    return {
        "status": "ok",
        "pages": 1 + len(service_pages) + len(other_pages),
        "fields": {key: value for key, value in fields.items() if value},
    }


def crawl_site(church: dict, session: requests.Session, sleep: Callable[[float], None] = time.sleep) -> dict:
    """{"id", "website", "status", ["pages", "fields"]}: one cache line."""
    result = {"id": church["id"], "website": church["website"]}
    try:
        result.update(_crawl(church, Fetcher(session, sleep)))
    except Exception as error:  # one broken site must never end a multi-hour crawl
        logger.debug("crawl of %s failed", church["website"], exc_info=True)
        result["status"] = f"error: {type(error).__name__}"
    return result


def read_cache(cache_path: Optional[str]) -> Dict[int, dict]:
    """{church id: crawl result}. A line cut off by a crash mid-write is skipped."""
    results: Dict[int, dict] = {}
    if not cache_path or not os.path.exists(cache_path):
        return results
    with open(cache_path) as f:
        for line in f:
            try:
                result = json.loads(line)
            except ValueError:
                continue
            results[result["id"]] = result
    return results


def crawl_all(
    churches: List[dict],
    cache_path: Optional[str] = None,
    workers: int = DEFAULT_WORKERS,
    session_factory: Callable[[], requests.Session] = requests.Session,
    sleep: Callable[[float], None] = time.sleep,
) -> Dict[int, dict]:
    """Crawl results for every church, reusing cached ones whose website hasn't changed.
    Each result is appended to the cache as soon as it's in, so an interrupted crawl
    resumes from where it stopped."""
    results = read_cache(cache_path)
    todo = [c for c in churches if results.get(c["id"], {}).get("website") != c["website"]]
    logger.info("website crawl: %d cached, %d to crawl", len(churches) - len(todo), len(todo))
    lock = threading.Lock()
    done = [0]

    def work(church: dict) -> None:
        session = session_factory()
        try:
            result = crawl_site(church, session, sleep)
        finally:
            session.close()
        with lock:
            results[church["id"]] = result
            if cache_path:
                with open(cache_path, "a") as f:
                    f.write(json.dumps(result) + "\n")
            done[0] += 1
            if done[0] % PROGRESS_EVERY == 0 or done[0] == len(todo):
                logger.info("website crawl progress: %d/%d", done[0], len(todo))

    with ThreadPoolExecutor(max_workers=workers) as pool:
        list(pool.map(work, todo))
    return results


# --- planning -----------------------------------------------------------------------

def select_targets(existing: Iterable[dict], states: Optional[Iterable[str]] = None) -> Tuple[List[dict], dict]:
    """Unclaimed churches with a website of their own that are missing a field this can
    fill. A site listed on more than one church in `existing` is left out."""
    existing = list(existing)
    states = set(states) if states else None
    owners = Counter(site_key(c.get("website")) for c in existing)
    stats = {"candidates": 0, "sharedWebsite": 0, "profileWebsite": 0, "targets": 0}
    targets = []
    for church in existing:
        if church.get("id") is None or _blank(church.get("website")):
            continue
        if states is not None and church.get("state") not in states:
            continue
        if church.get("claimed") or not any(
            _blank(church.get(key)) for key in ("serviceSummary", "pastor", "description")
        ):
            continue
        stats["candidates"] += 1
        key = site_key(church["website"])
        if key is None:
            stats["profileWebsite"] += 1
        elif owners[key] > 1:
            stats["sharedWebsite"] += 1
        else:
            targets.append(church)
    stats["targets"] = len(targets)
    return targets, stats


def build_fills(targets: Iterable[dict], results: Dict[int, dict]) -> Tuple[List[dict], dict]:
    """(fills, stats). Each fill is {"id": ..., <only the fields that church is missing>}."""
    crawled = []
    statuses: Counter = Counter()
    for church in targets:
        result = results.get(church["id"])
        if result is None or result.get("website") != church["website"]:
            continue
        statuses[result.get("status", "").split(":")[0]] += 1
        crawled.append((church, dict(result.get("fields") or {})))

    shared = Counter(fields.get("description") for _, fields in crawled if fields.get("description"))
    dropped = 0
    fills = []
    for church, fields in crawled:
        if shared[fields.get("description")] > 1:
            del fields["description"]
            dropped += 1
        wanted = _missing(church, fields)
        if wanted:
            fills.append({"id": church["id"], **wanted})

    stats = {f"crawl.{status}": count for status, count in sorted(statuses.items())}
    stats["sharedDescriptionsDropped"] = dropped
    stats["churchesToFill"] = len(fills)
    for key in FILL_KEYS:
        stats[f"fill.{key}"] = sum(1 for fill in fills if key in fill)
    return fills, stats


def run(
    config: Optional[BulkImportConfig] = None,
    session: Optional[requests.Session] = None,
    states: Optional[Iterable[str]] = None,
    cache_path: Optional[str] = None,
    dry_run: bool = False,
    workers: int = DEFAULT_WORKERS,
    limit: Optional[int] = None,
    batch_size: int = DEFAULT_BATCH_SIZE,
    batch_delay_s: float = DEFAULT_BATCH_DELAY_S,
    session_factory: Callable[[], requests.Session] = requests.Session,
    sleep: Callable[[float], None] = time.sleep,
) -> dict:
    config = config or BulkImportConfig.from_env()
    session = session or requests.Session()

    # With --states, only those states' churches are fetched (a few requests, not the
    # ~1,000 the whole directory takes), so the shared-website check is within them.
    if states:
        existing = [c for state in states for c in fetch_all_existing_churches(config.server_base_url, session, state=state)]
    else:
        existing = fetch_all_existing_churches(config.server_base_url, session)
    targets, stats = select_targets(existing, states)
    if limit is not None:
        targets = targets[:limit]
    logger.info("website fill targets: %s", stats)

    results = crawl_all(targets, cache_path, workers, session_factory, sleep)
    fills, fill_stats = build_fills(targets, results)
    stats = {**stats, **fill_stats}
    logger.info("website fill plan: %s", stats)

    if dry_run:
        log_samples(fills, {c.get("id"): c for c in existing})
        return {**stats, "updated": 0}

    updated = 0
    for start in range(0, len(fills), batch_size):
        batch = fills[start:start + batch_size]
        updated += submit_fills(config, session, batch, source=SOURCE_NAME)
        logger.info("website fill progress: %d/%d sent, %d updated", start + len(batch), len(fills), updated)
        if start + batch_size < len(fills):
            sleep(batch_delay_s)

    result = {**stats, "updated": updated}
    logger.info("website fill run: %s", result)
    return result


def main(argv: Optional[List[str]] = None) -> dict:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--dry-run", action="store_true", help="crawl and report, send nothing")
    parser.add_argument("--cache", help="JSON-lines file to save/reuse crawl results (resumable)")
    parser.add_argument("--states", help="comma-separated state codes (default: every state)")
    parser.add_argument("--workers", type=int, default=DEFAULT_WORKERS, help="sites crawled at once")
    parser.add_argument("--limit", type=int, help="crawl at most this many churches (for trial runs)")
    args = parser.parse_args(argv)
    states = [s.strip().upper() for s in args.states.split(",")] if args.states else None
    return run(states=states, cache_path=args.cache, dry_run=args.dry_run, workers=args.workers, limit=args.limit)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    main()
