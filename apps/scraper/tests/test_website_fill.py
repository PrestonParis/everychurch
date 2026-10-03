import json
import logging
from unittest.mock import MagicMock, patch

import pytest
import requests
from bs4 import BeautifulSoup

from scraper import website_fill
from scraper.config import BulkImportConfig
from scraper.website_fill import (
    USER_AGENT,
    Fetcher,
    _glue,
    build_fills,
    crawl_all,
    crawl_site,
    extract_pastor,
    extract_service_times,
    main,
    mentions_name,
    page_description,
    page_lines,
    page_links,
    page_title,
    read_cache,
    run,
    select_targets,
    site_key,
)


def make_config():
    return BulkImportConfig(bulk_import_api_key="bulk-secret", server_base_url="https://api.example.com")


def soup(html):
    return BeautifulSoup(html, "html.parser")


def lines_of(html):
    return page_lines(soup(html))


def schedule(lines, catholic=False, name=None):
    """extract_service_times as compact "Sun 10:00 Worship [Spanish]" strings."""
    return [
        " ".join(filter(None, [t["dayOfWeek"][:3], t["time"], t["type"], t.get("language")]))
        for t in extract_service_times(lines, catholic, name)
    ]


# --- fake http ----------------------------------------------------------------------

class FakeResponse:
    def __init__(self, url, status=200, body=b"", content_type="text/html", chunks=None, error=None):
        self.url = url
        self.status_code = status
        self.headers = {"Content-Type": content_type} if content_type is not None else {}
        self._body = body.encode() if isinstance(body, str) else body
        self._chunks = chunks
        self._error = error
        self.closed = False

    @property
    def text(self):
        return self._body.decode()

    def iter_content(self, size):
        if self._error:
            raise self._error
        return iter(self._chunks if self._chunks is not None else [self._body])

    def close(self):
        self.closed = True


class FakeSession:
    """Routes url → FakeResponse, an exception to raise, or an html string. Anything not
    routed is a 404 (so robots.txt is absent unless a test adds one)."""

    def __init__(self, routes=None):
        self.routes = routes or {}
        self.calls = []
        self.closed = False

    def get(self, url, headers=None, timeout=None, stream=False):
        self.calls.append(url)
        assert headers == {"User-Agent": USER_AGENT}
        route = self.routes.get(url)
        if isinstance(route, Exception):
            raise route
        if isinstance(route, FakeResponse):
            return route
        if isinstance(route, str):
            return FakeResponse(url, body=route)
        return FakeResponse(url, status=404, content_type="text/plain")

    def close(self):
        self.closed = True


# --- Fetcher ------------------------------------------------------------------------

def test_fetcher_returns_the_final_url_and_parsed_page():
    session = FakeSession({"https://a.org/": FakeResponse("https://www.a.org/", body="<p>Hi</p>")})

    url, page = Fetcher(session).get("https://a.org/")

    assert url == "https://www.a.org/"
    assert page.get_text() == "Hi"
    assert session.calls == ["https://a.org/robots.txt", "https://a.org/"]


def test_fetcher_uses_the_header_charset_and_otherwise_the_page_meta():
    body = "<p>Sábado</p>".encode("utf-8")
    with_header = FakeSession({"https://a.org/": FakeResponse("https://a.org/", body=body, content_type="text/html; charset=utf-8")})
    with_meta = FakeSession({"https://a.org/": FakeResponse("https://a.org/", body=b'<meta charset="utf-8">' + body)})

    assert Fetcher(with_header).get("https://a.org/")[1].get_text() == "Sábado"
    assert Fetcher(with_meta).get("https://a.org/")[1].get_text() == "Sábado"


def test_fetcher_pauses_between_requests_but_not_before_the_first():
    sleep = MagicMock()
    session = FakeSession({"https://a.org/": "<p>a</p>", "https://a.org/b": "<p>b</p>"})
    fetcher = Fetcher(session, sleep)

    fetcher.get("https://a.org/")
    sleep.assert_not_called()
    fetcher.get("https://a.org/b")
    sleep.assert_called_once_with(website_fill.PAGE_DELAY_S)


@pytest.mark.parametrize("response", [
    FakeResponse("u", status=404),
    FakeResponse("u", content_type="application/pdf"),
    FakeResponse("u", content_type=None),
    FakeResponse("u", chunks=[b"x" * 1_500_000, b"x" * 1_500_000]),
    FakeResponse("u", error=requests.exceptions.ChunkedEncodingError("cut off")),
])
def test_fetcher_rejects_non_html_errors_and_oversized_pages(response):
    session = FakeSession({"https://a.org/": response})

    assert Fetcher(session).get("https://a.org/") is None
    assert response.closed


def test_fetcher_returns_none_when_the_request_fails():
    session = FakeSession({"https://a.org/": requests.exceptions.ConnectionError("down")})

    assert Fetcher(session).get("https://a.org/") is None


def test_robots_disallow_blocks_the_page_and_crawl_delay_slows_the_site():
    robots = "User-agent: everychurch-website-fill\nDisallow: /private\nCrawl-delay: 5\n"
    session = FakeSession({"https://a.org/robots.txt": FakeResponse("u", body=robots, content_type="text/plain")})
    fetcher = Fetcher(session)

    assert fetcher.get("https://a.org/private/page") is None
    assert fetcher.blocked
    assert fetcher.delay == 5.0
    assert fetcher.allowed("https://a.org/public")
    assert session.calls == ["https://a.org/robots.txt"]  # fetched once per origin


def test_robots_without_a_crawl_delay_keeps_the_default_pause():
    session = FakeSession({"https://a.org/robots.txt": FakeResponse("u", body="User-agent: *\nAllow: /\n")})
    fetcher = Fetcher(session)

    assert fetcher.allowed("https://a.org/")
    assert fetcher.delay == website_fill.PAGE_DELAY_S


@pytest.mark.parametrize("robots", [
    FakeResponse("u", status=503),
    requests.exceptions.ConnectTimeout("slow"),
])
def test_an_erroring_robots_txt_disallows_everything(robots):
    fetcher = Fetcher(FakeSession({"https://a.org/robots.txt": robots}))

    assert not fetcher.allowed("https://a.org/")


# --- page text ----------------------------------------------------------------------

def test_page_lines_splits_blocks_and_joins_inline_elements():
    html = """
        <head><title>T</title></head>
        <script>var x = "Sunday 10am";</script><style>p {}</style>
        <h2>Worship Times</h2>
        <p><b>Sunday</b> 10:00 AM<br>Wednesday 7 PM</p>
        <ul><li>One</li><li>Two​</li></ul>
        <table><tr><td>Sunday</td><td>9 AM</td></tr></table>
    """
    assert lines_of(html) == ["Worship Times", "Sunday 10:00 AM", "Wednesday 7 PM", "One", "Two", "Sunday 9 AM"]


@pytest.mark.parametrize("html, expected", [
    ("<a><span>Pastor</span><span>Stephen Schmidt</span></a>", "Pastor Stephen Schmidt"),
    ("<span>S</span>unday", "Sunday"),
    ("<span>SUNDAY</span><span>WORSHIP</span>", "SUNDAY WORSHIP"),
    ("<span>Rev</span>. John", "Rev. John"),
    ("<span>Sunday</span><span>10am</span>", "Sunday 10am"),
    ("<span>serv</span><span>ice</span>", "service"),
])
def test_page_lines_glues_inline_pieces_like_a_reader_would(html, expected):
    assert lines_of(f"<p>{html}</p>") == [expected]


def test_glue_between_a_letter_and_punctuation():
    assert _glue("Sunday", "(") == " "


def test_page_title():
    assert page_title(soup("<title>Grace Church</title>")) == "Grace Church"
    assert page_title(soup("<p>no title</p>")) == ""


# --- links --------------------------------------------------------------------------

def test_page_links_picks_two_service_and_two_pastor_pages_best_first():
    html = """
        <a href="/worship">Worship</a>
        <a href="/mass-times">Mass Times</a>
        <a href="/plan-a-visit">Plan a Visit</a>
        <a href="/about">About</a>
        <a href="/staff">Staff</a>
        <a href="/our-pastor">Our Pastor</a>
        <a href="https://other.org/pastor">Elsewhere</a>
        <a href="mailto:office@a.org">Email the pastor</a>
        <a href="/sermons">Pastor's sermons</a>
        <a href="/bulletin.pdf">Mass times bulletin</a>
        <a href="/past-services">Past services</a>
        <a href="/MASS-TIMES/#top">Mass Times again</a>
        <a href="https://a.org/">Home</a>
        <a href="/contact">Contact</a>
    """
    links = page_links(soup(html), "https://www.a.org/")

    assert links == [
        ("https://www.a.org/mass-times", "service"),
        ("https://www.a.org/plan-a-visit", "service"),
        ("https://www.a.org/our-pastor", "pastor"),
        ("https://www.a.org/staff", "pastor"),
    ]


def test_page_links_gives_unused_service_slots_to_pastor_pages():
    html = '<a href="/about">About</a><a href="/leadership">Leadership</a><a href="/pastor">Pastor</a>'

    assert [kind for _, kind in page_links(soup(html), "https://a.org")] == ["pastor", "pastor", "pastor"]


# --- name check ---------------------------------------------------------------------

@pytest.mark.parametrize("name, text, expected", [
    ("Saint Pius X Catholic Church", "Welcome to St. Pius parish", True),
    ("Saint Pius X Catholic Church", "This domain is for sale", False),
    ("Saint Matthews Episcopal Church", "St. Matthew's, Jamestown", True),
    ("First Baptist Church", "First Baptist Church of Waltham", True),
    ("First Baptist Church", "Baptist resources", False),
    ("Iglesia Sábado", "SABADO", True),
    ("St", "anything", False),
    (None, "anything", False),
])
def test_mentions_name(name, text, expected):
    assert mentions_name(name, text) is expected


# --- service times ------------------------------------------------------------------

@pytest.mark.parametrize("lines, expected", [
    # one line: day first, shared am/pm, several days, range of days
    (["Sunday Worship 9:00 & 10:30 AM"], ["Sun 09:00 Worship", "Sun 10:30 Worship"]),
    (["Join us Sundays at 8:30, 9:45 and 11:00 am"], ["Sun 08:30 Worship", "Sun 09:45 Worship", "Sun 11:00 Worship"]),
    (["Sunday Worship 11 AM, Wednesday Bible Study 7 PM"], ["Wed 19:00 Bible Study", "Sun 11:00 Worship"]),
    (["Worship: Saturday & Sunday 10am"], ["Sat 10:00 Worship", "Sun 10:00 Worship"]),
    (["Worship Mon - Wed 7 am", "Sunday service 10 am"], ["Mon 07:00 Worship", "Tue 07:00 Worship", "Wed 07:00 Worship", "Sun 10:00 Worship"]),
    (["Worship Sat-Mon 7 am"], ["Mon 07:00 Worship", "Sat 07:00 Worship", "Sun 07:00 Worship"]),
    (["Worship Service.......11:00 a.m. Sunday's"], ["Sun 11:00 Worship"]),
    (["Sunday service 11:00 & 1:00 pm"], ["Sun 11:00 Worship", "Sun 13:00 Worship"]),
    (["Sunday service 10:00 & 9:00 am"], ["Sun 09:00 Worship"]),  # 10:00 can't come before 9am
    (["Bible Study Wednesday 6:30 & 7:30 pm", "7pm Bible Study Thursday", "Sunday service 10am"],
     ["Wed 18:30 Bible Study", "Wed 19:30 Bible Study", "Thu 19:00 Bible Study", "Sun 10:00 Worship"]),
    (["Sunday service at noon"], ["Sun 12:00 Worship"]),
    (["Sunday service 12 noon"], ["Sun 12:00 Worship"]),
    (["Sunday service 17:30"], ["Sun 17:30 Worship"]),
    # time first, day last
    (["11:30 a.m. Domingo culto"], ["Sun 11:30 Worship Spanish"]),
    (["Worship 10am Saturday & Sunday"], ["Sat 10:00 Worship", "Sun 10:00 Worship"]),
    # two table cells run together: the leading time has no day
    (["Vigil 4:00 PM Monday - Saturday 8:00 AM", "Sunday Mass 9 am"],
     ["Mon 08:00 Worship", "Tue 08:00 Worship", "Wed 08:00 Worship", "Thu 08:00 Worship", "Fri 08:00 Worship",
      "Sat 08:00 Worship", "Sun 09:00 Mass"]),
    # "Weekdays" plus the days listed means just those days
    (["Weekday Masses: Monday, Thursday 8 am", "Sunday Mass 10 am"], ["Mon 08:00 Mass", "Thu 08:00 Mass", "Sun 10:00 Mass"]),
])
def test_schedule_from_single_lines(lines, expected):
    assert schedule(lines) == expected


def test_weekdays_alone_means_monday_to_friday():
    assert schedule(["Weekdays Mass 8 am", "Sunday Mass 10 am"]) == [
        "Mon 08:00 Mass", "Tue 08:00 Mass", "Wed 08:00 Mass", "Thu 08:00 Mass", "Fri 08:00 Mass", "Sun 10:00 Mass",
    ]


def test_bare_lines_count_under_a_worship_heading_with_the_day_carried_from_above():
    lines = [
        "Mass Times", "Saturday Vigil", "4:00 PM", "Sunday", "8:00 AM", "English", "10:30 AM",
        "Weekdays", "7:30 AM",
    ]
    assert schedule(lines) == [
        "Mon 07:30 Mass", "Tue 07:30 Mass", "Wed 07:30 Mass", "Thu 07:30 Mass", "Fri 07:30 Mass",
        "Sat 16:00 Mass", "Sun 08:00 Mass", "Sun 10:30 Mass",
    ]


def test_a_day_under_an_excluded_label_does_not_carry():
    lines = ["Office Hours:", "Monday to Thursday", "9 AM to 3 pm", "Kids Programming:", "at 9 AM Services",
             "Service Times", "Sundays 10:45 AM"]
    assert schedule(lines) == ["Sun 10:45 Worship"]


def test_a_new_non_service_label_after_some_times_ends_the_carried_day():
    lines = ["Worship", "Sunday", "9 AM", "Music lessons", "at 4 PM", "Traditional service", "at 11 AM"]
    assert schedule(lines) == ["Sun 09:00 Worship"]
    lines = ["Sunday", "Traditional Service", "8:30 am", "Contemporary Service", "11:00 am"]
    assert schedule(lines) == ["Sun 08:30 Worship", "Sun 11:00 Worship"]


def test_bare_lines_without_a_worship_heading_are_ignored():
    assert schedule(["Upcoming", "Sunday 10:00 am"]) == []


def test_a_long_line_drops_the_carried_day():
    lines = ["Worship", "Sunday", "x" * 201, "10:00 am"]
    assert schedule(lines) == []


def test_a_medium_line_without_a_day_drops_the_carried_day():
    assert schedule(["Worship", "Sunday", "Our friendly congregation", "10:00 am"]) == []


@pytest.mark.parametrize("line", [
    "Office Hours: Sunday 9am",
    "Sunday School 9:30 am",
    "Sunday Worship 10:00-11:30 am",
    "Sunday Mass, Oct 4, 10am",
    "Sunday Mass 10/4 10am",
    "Christmas Eve Mass Sunday 5pm",
    "Coffee after the 10am Sunday service",
    "Sunday service 4am",
    "Sunday service 13:00 pm",
    "Sunday service 10:75 am",
    "Sunday service, Luke 6:36",
    "Sunday Worship 10:30",
])
def test_rejected_service_lines(line):
    assert schedule([line]) == []


def test_a_range_split_over_three_lines_is_still_a_range():
    lines = ["Worship", "Sunday", "3:00 PM", "to", "3:45 PM", "Sunday service 10am"]
    assert schedule(lines) == ["Sun 10:00 Worship"]


def test_an_excluded_heading_rejects_the_times_under_it_until_the_next_heading():
    lines = [
        "Confessions", "Saturday 3:00 PM",
        "Mass Schedule", "Saturday 4:00 PM",
        "Office Hours", "Monday 9:00 AM",
    ]
    assert schedule(lines) == ["Sat 16:00 Mass"]


def test_a_mixed_heading_still_heads_its_masses():
    assert schedule(["Mass, Confession and Liturgical Times", "Saturday :", "4:00 PM"]) == ["Sat 16:00 Mass"]


def test_a_dated_short_label_rejects_the_time_under_it_but_a_long_verse_does_not():
    assert schedule(["Worship", "October 31 :", "Sunday 4:00 PM"]) == []
    verse = '"Nothing will separate us from the love of God," Romans 8:32-36, read at every service'
    assert schedule(["Worship", verse, "Sunday 10:00 AM"]) == ["Sun 10:00 Worship"]


def test_two_column_labels_with_one_excluded_reject_every_time():
    lines = ["Saturday", "Confessions:", "Holy Rosary:", "Saturday Vigil:", "3:00 PM", "3:30 PM", "4:00 PM", "Sunday Mass 9 am"]
    assert schedule(lines) == ["Sun 09:00 Mass"]


def test_a_time_line_with_only_a_range_still_ends_the_label_run():
    lines = ["Confession", "Saturday 4:30 - 5:45 pm", "Monday 8:00 pm", "Sunday Mass 9 am"]
    assert schedule(lines) == ["Sun 09:00 Mass"]


def test_service_types_and_languages():
    lines = [
        "Sunday Mass 8 am",
        "Sunday Worship 9 am English, 11 am Spanish",
        "Spanish Mass: Sunday 12:30 & 2 pm",
        "Wednesday Bible Study 7 pm",
        "Saturday Latin Rite Mass 5 pm",
        "Sunday Latin Mass 7 am",
    ]
    assert schedule(lines) == [
        "Wed 19:00 Bible Study",
        "Sat 17:00 Mass",
        "Sun 07:00 Mass Latin",
        "Sun 08:00 Mass",
        "Sun 09:00 Worship",
        "Sun 11:00 Worship Spanish",
        "Sun 12:30 Mass Spanish",
        "Sun 14:00 Mass Spanish",
    ]


def test_catholic_churches_and_mass_headings_mean_mass():
    assert schedule(["Sunday service 10am"], catholic=True) == ["Sun 10:00 Mass"]
    assert schedule(["Mass Schedule", "Sunday 10am"]) == ["Sun 10:00 Mass"]


def test_parenthesized_alternate_times_are_dropped_but_languages_kept():
    assert schedule(["Sunday Worship 10:00 AM (9:00 AM in July)"]) == ["Sun 10:00 Worship"]
    assert schedule(["Sunday Mass 10:00 AM (Spanish)"]) == ["Sun 10:00 Mass Spanish"]


def test_a_schedule_needs_a_weekend_service_and_a_sane_size():
    assert schedule(["Wednesday worship 7pm"]) == []
    assert schedule(["Sunday Bible Study 9am"]) == []
    many = [f"Sunday service {h}:{m} am" for h in range(6, 12) for m in ("00", "15", "30", "45")]
    assert schedule(many) == []


def test_duplicate_times_are_listed_once():
    assert schedule(["Sunday Worship 10am", "Join us Sunday 10:00 AM"]) == ["Sun 10:00 Worship"]


def test_menu_items_that_look_like_places_are_not_subheadings():
    lines = ["Holy Orders", "Sacred Music Ministry", "St Vincent de Paul Society", "Contact", "Mass Times", "Sunday 10:00 am"]
    assert schedule(lines, name="Our Lady of Mercy Church") == ["Sun 10:00 Mass"]


def test_subheadings_keep_the_heading_and_day_above_them():
    lines = [
        "Sunday Masses",
        "Our Lady of Good Help", "10:30am",
        "St. Theresa Church and Shrine (35 Dion Drive)", "8:00am & 5:00pm",
        "10:30am- Our Lady of Good Help",
        "7:00pm- St. Theresa Church",
    ]
    assert schedule(lines, name="Saint Theresa Catholic Church") == ["Sun 08:00 Mass", "Sun 17:00 Mass", "Sun 19:00 Mass"]


def test_subheadings_naming_another_church_hide_its_times():
    lines = [
        "Mass Schedule",
        "Our Lady of Good Hope", "Saturday: 5:00 pm", "Sunday: 10:00 am",
        "St. Mary of the Assumption", "Saturday: 4:00 pm", "Sunday: 9:00 am",
        "Welcome to our church", "Sunday: 7:00 am",
    ]
    assert schedule(lines, name="Our Lady of Good Hope Catholic Church") == ["Sat 17:00 Mass", "Sun 10:00 Mass"]
    # Without the name there's no telling which block is whose, so all of them count.
    assert schedule(lines) == ["Sat 16:00 Mass", "Sat 17:00 Mass", "Sun 09:00 Mass", "Sun 10:00 Mass"]


def test_a_place_named_on_the_line_decides():
    name = "Saint Mary Catholic Church"
    lines = [
        "Mass Times",
        "Saturday Vigil: 4:00 p.m. @ St. Mary.",
        "Sunday: 8:00 am @",
        "St. George in New Baden.",
        "Sunday Mass 10:30 am at St. Mary",
        "Tuesday Mass 8:00 am at St. George",
        "St. Joan of Arc – Sunday 9:00 a.m.",
        "St. Mary – Sunday 11:00 a.m.",
        "St. Mary 7:00 am – Sunday",
        "Sunday Mass at St. Mary",
    ]
    assert schedule(lines, name=name) == ["Sat 16:00 Mass", "Sun 10:30 Mass", "Sun 11:00 Mass"]


# --- pastor -------------------------------------------------------------------------

@pytest.mark.parametrize("lines, expected", [
    (["Welcome! Pastor Luis Morales preaches this week."], "Luis Morales"),
    (["Senior Pastor: Rev. Dr. John Q. Smith Jr."], "Rev. Dr. John Q. Smith Jr."),
    (["The Reverend Jane Doe, Rector"], "Rev. Jane Doe"),
    (["Father Kevin O'Brien - Pastor of St. Mary's"], "Fr. Kevin O'Brien"),
    (["Our pastor is Rev. John Smith."], "Rev. John Smith"),
    (["Pastor DD Jackson"], "DD Jackson"),
    (["Pastor D.D. Jackson"], "D.D. Jackson"),
    (["Pastor John sends out a note"], None),
    (["Youth Pastor Jane Doe"], None),
    (["Jane Doe, Associate Pastor"], None),
    (["Pastor of Christian Education, Pastor Carol Reale"], None),
    (["Jane Doe, Pastor for Children"], None),
    (["Contact the Kid Pastor"], None),
    (["Pastor's office: 555-0100"], None),
    (["Pastor Plan Your Visit"], None),
    (["Pastor Jr. Smith"], None),
    (["Pastor John D."], None),
    (["Pastor Synodically Authorized Minister"], None),
    (["Read Pastor John Coleman’s weekly letter"], "John Coleman"),
    (["Pastor Jane Doe", "Pastor John Smith"], None),
    (["Pastor Jane Doe", "John Smith, Senior Pastor"], "John Smith"),
    (["Pastor Jane Doe", "Rev. Jane Doe, Pastor"], "Rev. Jane Doe"),
    (["Lead Pastor Ann Lee", "Senior Pastor Bob Ray"], None),
    (["x" * 150 + " Pastor Jane Doe " + "x" * 50], None),
    (["Some text, Pastor"], None),
])
def test_inline_pastor(lines, expected):
    assert extract_pastor([lines]) == expected


def test_staff_cards_with_the_name_above_the_title():
    page = ["Our Staff", "Cheryl Lavornia", "Senior Pastor", "BJ Kadeg", "Director of Worship", "Jeff Allain", "Associate Pastor"]
    assert extract_pastor([page]) == "Cheryl Lavornia"


def test_staff_cards_with_the_title_above_the_name():
    page = ["Our Staff", "Pastor", "Joshua Murray", "Worship Director", "Amy Lee", "Secretary", "Bo Diaz"]
    assert extract_pastor([page]) == "Joshua Murray"


def test_staff_cards_with_no_clear_layout_are_ignored():
    page = ["Jane Doe", "Pastor", "John Smith"]
    assert extract_pastor([page]) is None


def test_staff_cards_skip_non_lead_and_edge_titles():
    above = ["Ann Lee", "Associate Pastor", "Bo Diaz", "Secretary", "Pastor"]
    assert extract_pastor([above]) is None
    below = ["Pastor", "Plan Your Visit", "Secretary", "Bo Diaz"]
    assert extract_pastor([below]) is None


def test_pastors_are_combined_across_pages():
    assert extract_pastor([["Pastor Luis Morales"], ["Rev. Luis Morales, Pastor"]]) == "Rev. Luis Morales"
    assert extract_pastor([["Rev. Luis Morales, Pastor"], ["Pastor Luis Morales"]]) == "Rev. Luis Morales"
    assert extract_pastor([[], []]) is None


# --- description --------------------------------------------------------------------

DESCRIPTION = "Grace Church is a welcoming community in Cranston, Rhode Island, worshipping together since 1902."


def test_description_prefers_json_ld_then_meta_then_open_graph():
    ld = json.dumps({"@type": "Church", "name": "Grace", "description": DESCRIPTION})
    assert page_description(soup(f'<script type="application/ld+json">{ld}</script>')) == DESCRIPTION
    assert page_description(soup(f'<meta name="description" content="  {DESCRIPTION}  ">')) == DESCRIPTION
    html = f'<meta name="description" content="Home"><meta property="og:description" content="{DESCRIPTION}">'
    assert page_description(soup(html)) == DESCRIPTION


@pytest.mark.parametrize("text", [
    "Too short to be a description.",
    "Just another WordPress site with a long enough description to pass the length check ok",
    "A welcoming church in Cranston, Rhode Island, where everyone is family and always will be...",
    "A WELCOMING CHURCH IN CRANSTON, RHODE ISLAND, WHERE EVERYONE IS FAMILY AND ALWAYS WILL BE",
    "Grace Church | Cranston | Rhode Island | Sunday Worship | Bible Study | Youth Group | Give",
    "2800 Rockwell Drive - Brownsville, Texas - 78521 - (956) 541-1480 Email us any time you like",
    "Saint Augustin Catholic Church, Newport, Rhode Island - Home page of the parish and school",
    "Supercalifragilisticexpialidocious-and-also-very-long-wordwithoutanyspaces-at-all-whatsoever",
])
def test_template_or_junk_descriptions_are_rejected(text):
    assert page_description(soup(f'<meta name="description" content="{text}">')) is None


def test_no_description_at_all():
    assert page_description(soup("<p>nothing</p>")) is None


# --- crawl --------------------------------------------------------------------------

HOME = """
<html><head><title>Grace Community Church</title>
<meta name="description" content="{description}"></head>
<body>
  <a href="/worship-times">Worship Times</a>
  <a href="/staff">Staff</a>
  <h2>Join us</h2><p>Sunday Worship 9:00 AM</p>
</body></html>
""".format(description=DESCRIPTION)
TIMES = "<h2>Worship Times</h2><p>Sunday 9:00 &amp; 11:00 AM</p><p>Wednesday Bible Study 7 PM</p>"
STAFF = "<p>John Smith</p><p>Senior Pastor</p><p>Jane Doe</p><p>Secretary</p>"


def church(**fields):
    return {"id": 7, "name": "Grace Community Church", "website": "grace.example.org", "state": "RI", **fields}


def test_crawl_site_reads_homepage_service_and_staff_pages():
    session = FakeSession({
        "http://grace.example.org": FakeResponse("https://grace.example.org/", body=HOME),
        "https://grace.example.org/worship-times": TIMES,
        "https://grace.example.org/staff": STAFF,
    })
    sleep = MagicMock()

    result = crawl_site(church(), session, sleep)

    assert result == {
        "id": 7,
        "website": "grace.example.org",
        "status": "ok",
        "pages": 3,
        "fields": {
            "serviceTimes": [
                {"dayOfWeek": "Wednesday", "time": "19:00", "type": "Bible Study"},
                {"dayOfWeek": "Sunday", "time": "09:00", "type": "Worship"},
                {"dayOfWeek": "Sunday", "time": "11:00", "type": "Worship"},
            ],
            "pastor": "John Smith",
            "description": DESCRIPTION,
        },
    }
    assert sleep.call_count == 2


def test_crawl_site_falls_back_to_the_homepage_schedule():
    session = FakeSession({
        "https://grace.example.org": FakeResponse("https://grace.example.org/", body=HOME),
        "https://grace.example.org/worship-times": "<p>Nothing here</p>",
    })

    result = crawl_site(church(website="https://grace.example.org", denomination="Catholic"), session, lambda s: None)

    assert result["pages"] == 2
    assert result["fields"]["serviceTimes"] == [{"dayOfWeek": "Sunday", "time": "09:00", "type": "Mass"}]


def test_crawl_site_only_reads_the_homepage_when_crawl_delay_is_long():
    robots = FakeResponse("u", body="User-agent: *\nCrawl-delay: 30\n", content_type="text/plain")
    session = FakeSession({"http://grace.example.org/robots.txt": robots, "http://grace.example.org": HOME})

    result = crawl_site(church(), session, lambda s: None)

    assert result["pages"] == 1
    assert result["fields"]["serviceTimes"] == [{"dayOfWeek": "Sunday", "time": "09:00", "type": "Worship"}]


def test_crawl_site_statuses():
    robots = FakeResponse("u", body="User-agent: *\nDisallow: /\n", content_type="text/plain")
    blocked = FakeSession({"http://grace.example.org/robots.txt": robots})
    parked = FakeSession({"http://grace.example.org": "<title>Domain for sale</title>"})

    assert crawl_site(church(), FakeSession(), lambda s: None)["status"] == "unreachable"
    assert crawl_site(church(), blocked, lambda s: None)["status"] == "robots"
    assert crawl_site(church(), parked, lambda s: None)["status"] == "name-mismatch"


def test_crawl_site_records_an_unexpected_error_instead_of_raising():
    session = MagicMock()
    session.get.side_effect = ValueError("bad url")

    assert crawl_site(church(), session)["status"] == "error: ValueError"


# --- cache --------------------------------------------------------------------------

def test_read_cache_skips_a_truncated_line(tmp_path):
    path = tmp_path / "crawl.jsonl"
    path.write_text('{"id": 1, "website": "a"}\n{"id": 2, "web')

    assert read_cache(str(path)) == {1: {"id": 1, "website": "a"}}
    assert read_cache(str(tmp_path / "missing.jsonl")) == {}
    assert read_cache(None) == {}


def test_crawl_all_reuses_cached_results_and_appends_new_ones(tmp_path, caplog):
    path = tmp_path / "crawl.jsonl"
    path.write_text(
        json.dumps({"id": 1, "website": "a.org", "status": "ok"}) + "\n"
        + json.dumps({"id": 2, "website": "old.org", "status": "ok"}) + "\n"
    )
    churches = [
        {"id": 1, "name": "A", "website": "a.org"},
        {"id": 2, "name": "B", "website": "b.org"},
        {"id": 3, "name": "C", "website": "c.org"},
    ]
    sessions = []

    def factory():
        sessions.append(FakeSession())
        return sessions[-1]

    with patch.object(website_fill, "PROGRESS_EVERY", 5), caplog.at_level(logging.INFO):
        results = crawl_all(churches, str(path), workers=2, session_factory=factory, sleep=lambda s: None)

    assert results[1]["status"] == "ok"
    assert results[2] == {"id": 2, "website": "b.org", "status": "unreachable"}
    assert results[3]["status"] == "unreachable"
    assert len(sessions) == 2 and all(s.closed for s in sessions)
    assert read_cache(str(path)) == results
    assert "website crawl progress: 2/2" in caplog.text


def test_crawl_all_without_a_cache():
    results = crawl_all([{"id": 1, "name": "A", "website": "a.org"}], None, 1, FakeSession, lambda s: None)

    assert results[1]["status"] == "unreachable"


# --- planning -----------------------------------------------------------------------

@pytest.mark.parametrize("website, expected", [
    ("https://www.Grace.org/", "grace.org"),
    ("grace.org/Home/", "grace.org/home"),
    ("https://www.facebook.com/grace", None),
    ("https://m.facebook.com/grace", None),
    ("https://www.jw.org/", None),
    ("  ", None),
    (None, None),
    ("http://", None),
])
def test_site_key(website, expected):
    assert site_key(website) == expected


def test_select_targets():
    existing = [
        {"id": 1, "website": "grace.org", "state": "RI"},
        {"id": 2, "website": "https://www.diocese.org/", "state": "RI"},
        {"id": 3, "website": "diocese.org", "state": "MA"},  # shares 2's site, other state
        {"id": 4, "website": "https://facebook.com/x", "state": "RI"},
        {"id": 5, "website": "full.org", "state": "RI", "serviceSummary": "Sundays 10:00 AM", "pastor": "P", "description": "D"},
        {"id": 6, "website": "claimed.org", "state": "RI", "claimed": True},
        {"id": 7, "website": "texas.org", "state": "TX"},
        {"id": 8, "website": "", "state": "RI"},
        {"id": None, "website": "noid.org", "state": "RI"},
        {"id": 9, "website": "partial.org", "state": "RI", "serviceSummary": "Sundays 10:00 AM", "pastor": "P"},
    ]

    targets, stats = select_targets(existing, ["RI"])

    assert [c["id"] for c in targets] == [1, 9]
    assert stats == {"candidates": 4, "sharedWebsite": 1, "profileWebsite": 1, "targets": 2}
    assert [c["id"] for c in select_targets(existing)[0]] == [1, 7, 9]


def test_build_fills_sends_only_missing_fields_and_drops_shared_descriptions():
    times = [{"dayOfWeek": "Sunday", "time": "10:00", "type": "Worship"}]
    targets = [
        {"id": 1, "website": "a.org"},
        {"id": 2, "website": "b.org", "serviceSummary": "Sundays 9:00 AM"},
        {"id": 3, "website": "c.org"},
        {"id": 4, "website": "d.org"},  # not crawled yet
        {"id": 5, "website": "new.org"},  # crawled under its old website
        {"id": 6, "website": "f.org"},
    ]
    results = {
        1: {"id": 1, "website": "a.org", "status": "ok", "fields": {"serviceTimes": times, "pastor": "Jo Ray", "description": "Same"}},
        2: {"id": 2, "website": "b.org", "status": "ok", "fields": {"serviceTimes": times, "description": "Same"}},
        3: {"id": 3, "website": "c.org", "status": "ok", "fields": {"description": "Own words"}},
        5: {"id": 5, "website": "old.org", "status": "ok", "fields": {"pastor": "Al Ray"}},
        6: {"id": 6, "website": "f.org", "status": "error: Boom"},
    }

    fills, stats = build_fills(targets, results)

    assert fills == [
        {"id": 1, "serviceTimes": times, "pastor": "Jo Ray"},
        {"id": 3, "description": "Own words"},
    ]
    assert stats == {
        "crawl.error": 1,
        "crawl.ok": 3,
        "sharedDescriptionsDropped": 2,
        "churchesToFill": 2,
        "fill.serviceTimes": 1,
        "fill.pastor": 1,
        "fill.description": 1,
    }


# --- run / main ---------------------------------------------------------------------

EXISTING = [
    {"id": 1, "name": "Grace Community Church", "website": "grace.example.org", "state": "RI"},
    {"id": 2, "name": "Hope Church", "website": "hope.example.org", "state": "RI"},
]


def crawled(churches, cache_path, workers, session_factory, sleep):
    return {
        c["id"]: {"id": c["id"], "website": c["website"], "status": "ok", "fields": {"pastor": f"Pastor {c['id']} Ray"}}
        for c in churches
    }


@patch.object(website_fill, "crawl_all", side_effect=crawled)
@patch.object(website_fill, "fetch_all_existing_churches", return_value=EXISTING)
def test_run_dry_run_sends_nothing(fetch, crawl, caplog):
    with caplog.at_level(logging.INFO):
        result = run(make_config(), MagicMock(), states=["RI"], cache_path="c.jsonl", dry_run=True, workers=3, limit=1)

    assert result["updated"] == 0
    assert result["churchesToFill"] == 1
    assert crawl.call_args.args[:3] == ([EXISTING[0]], "c.jsonl", 3)
    assert "#1 Grace Community Church" in caplog.text


@patch.object(website_fill, "submit_fills", side_effect=[1, 1])
@patch.object(website_fill, "crawl_all", side_effect=crawled)
@patch.object(website_fill, "fetch_all_existing_churches", return_value=EXISTING)
def test_run_submits_in_batches(fetch, crawl, submit):
    sleep = MagicMock()
    config = make_config()

    result = run(config, MagicMock(), batch_size=1, batch_delay_s=0.5, sleep=sleep)

    assert result["updated"] == 2
    assert [call.args[2] for call in submit.call_args_list] == [
        [{"id": 1, "pastor": "Pastor 1 Ray"}], [{"id": 2, "pastor": "Pastor 2 Ray"}],
    ]
    assert all(call.kwargs["source"] == "website" for call in submit.call_args_list)
    sleep.assert_called_once_with(0.5)


@patch.object(website_fill, "fetch_all_existing_churches", return_value=[])
@patch.object(website_fill.BulkImportConfig, "from_env", return_value=make_config())
def test_run_defaults_config_and_session(from_env, fetch):
    assert run()["updated"] == 0
    from_env.assert_called_once()


@patch.object(website_fill, "run", return_value={"updated": 0})
def test_main_parses_arguments(run_mock):
    main(["--dry-run", "--cache", "c.jsonl", "--states", "ri, ma", "--workers", "4", "--limit", "10"])
    run_mock.assert_called_once_with(states=["RI", "MA"], cache_path="c.jsonl", dry_run=True, workers=4, limit=10)

    run_mock.reset_mock()
    main([])
    run_mock.assert_called_once_with(states=None, cache_path=None, dry_run=False, workers=16, limit=None)
