import requests
import responses

from scraper.enrichment import enrich_from_website

URL = "https://example.church"

JSON_LD_HTML = """
<html><head>
<script type="application/ld+json">
{
  "@type": "Church",
  "name": "Grace Chapel",
  "description": "A welcoming church",
  "telephone": "555-1234",
  "url": "https://example.church",
  "image": {"url": "https://example.church/photo.jpg"},
  "address": {
    "streetAddress": "1 Main St",
    "addressLocality": "Springfield",
    "addressRegion": "IL",
    "postalCode": "62701"
  }
}
</script>
</head><body></body></html>
"""

JSON_LD_GRAPH_HTML = """
<html><head>
<script type="application/ld+json">
{"@graph": [
  {"@type": "WebSite", "name": "irrelevant"},
  {"@type": "LocalBusiness", "name": "Grace Chapel", "telephone": "555-1234"}
]}
</script>
</head><body></body></html>
"""

MALFORMED_JSON_LD_HTML = """
<html><head>
<script type="application/ld+json">not json</script>
</head><body>
<meta property="og:site_name" content="Grace Chapel">
<meta name="description" content="A welcoming church">
<meta property="og:image" content="https://example.church/photo.jpg">
</body></html>
"""

EMPTY_JSON_LD_TYPE_HTML = """
<html><head>
<script type="application/ld+json">{"@type": "Church"}</script>
</head><body><title>Grace Chapel</title></body></html>
"""

IRRELEVANT_TYPE_HTML = """
<html><head>
<script type="application/ld+json">{"@type": "WebSite", "name": "irrelevant"}</script>
</head><body><title>  Grace Chapel  </title>
<meta property="og:description" content="A welcoming church">
</body></html>
"""

NOTHING_USEFUL_HTML = "<html><head></head><body></body></html>"

GRAPH_WITH_NON_DICT_ENTRY_HTML = """
<html><head>
<script type="application/ld+json">
{"@graph": ["not a node", {"@type": "Church", "name": "Grace Chapel"}]}
</script>
</head><body></body></html>
"""

IMAGE_LIST_HTML = """
<html><head>
<script type="application/ld+json">
{"@type": "Church", "name": "Grace Chapel", "image": ["https://example.church/a.jpg"]}
</script>
</head><body></body></html>
"""

IMAGE_EMPTY_LIST_HTML = """
<html><head>
<script type="application/ld+json">
{"@type": "Church", "name": "Grace Chapel", "image": []}
</script>
</head><body></body></html>
"""

IMAGE_STRING_HTML = """
<html><head>
<script type="application/ld+json">
{"@type": "Church", "name": "Grace Chapel", "image": "https://example.church/a.jpg"}
</script>
</head><body></body></html>
"""


@responses.activate
def test_enrich_from_website_parses_json_ld():
    responses.add(responses.GET, URL, body=JSON_LD_HTML)

    result = enrich_from_website(URL)

    assert result == {
        "name": "Grace Chapel",
        "description": "A welcoming church",
        "phone": "555-1234",
        "website": "https://example.church",
        "image_url": "https://example.church/photo.jpg",
        "address": "1 Main St",
        "city": "Springfield",
        "state": "IL",
        "zip_code": "62701",
    }


@responses.activate
def test_enrich_from_website_unwraps_graph_array():
    responses.add(responses.GET, URL, body=JSON_LD_GRAPH_HTML)

    result = enrich_from_website(URL)

    assert result["name"] == "Grace Chapel"
    assert result["phone"] == "555-1234"


@responses.activate
def test_enrich_from_website_falls_back_to_meta_on_malformed_json_ld():
    responses.add(responses.GET, URL, body=MALFORMED_JSON_LD_HTML)

    result = enrich_from_website(URL)

    assert result == {
        "name": "Grace Chapel",
        "description": "A welcoming church",
        "image_url": "https://example.church/photo.jpg",
    }


@responses.activate
def test_enrich_from_website_ignores_json_ld_node_with_no_usable_fields():
    responses.add(responses.GET, URL, body=EMPTY_JSON_LD_TYPE_HTML)

    result = enrich_from_website(URL)

    assert result == {"name": "Grace Chapel", "description": None, "image_url": None}


@responses.activate
def test_enrich_from_website_skips_irrelevant_ld_type_and_uses_title_and_meta():
    responses.add(responses.GET, URL, body=IRRELEVANT_TYPE_HTML)

    result = enrich_from_website(URL)

    assert result["name"] == "Grace Chapel"
    assert result["description"] == "A welcoming church"


@responses.activate
def test_enrich_from_website_returns_empty_dict_when_nothing_usable():
    responses.add(responses.GET, URL, body=NOTHING_USEFUL_HTML)

    assert enrich_from_website(URL) == {}


@responses.activate
def test_enrich_from_website_returns_empty_dict_on_request_failure():
    responses.add(responses.GET, URL, body=requests.ConnectionError("boom"))

    assert enrich_from_website(URL) == {}


@responses.activate
def test_enrich_from_website_returns_empty_dict_on_http_error():
    responses.add(responses.GET, URL, status=500)

    assert enrich_from_website(URL) == {}


@responses.activate
def test_enrich_from_website_skips_non_dict_graph_entries():
    responses.add(responses.GET, URL, body=GRAPH_WITH_NON_DICT_ENTRY_HTML)

    result = enrich_from_website(URL)

    assert result["name"] == "Grace Chapel"


@responses.activate
def test_enrich_from_website_extracts_image_from_list():
    responses.add(responses.GET, URL, body=IMAGE_LIST_HTML)

    result = enrich_from_website(URL)

    assert result["image_url"] == "https://example.church/a.jpg"


@responses.activate
def test_enrich_from_website_extracts_no_image_from_empty_list():
    responses.add(responses.GET, URL, body=IMAGE_EMPTY_LIST_HTML)

    result = enrich_from_website(URL)

    assert result["image_url"] is None


@responses.activate
def test_enrich_from_website_extracts_image_from_plain_string():
    responses.add(responses.GET, URL, body=IMAGE_STRING_HTML)

    result = enrich_from_website(URL)

    assert result["image_url"] == "https://example.church/a.jpg"
