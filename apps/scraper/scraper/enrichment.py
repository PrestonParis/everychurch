import json
from typing import Optional

import requests
from bs4 import BeautifulSoup

# Mirrors server/src/main/java/.../UrlParserService.java: try JSON-LD first
# (structured, reliable), fall back to OpenGraph/meta tags.
USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)
REQUEST_TIMEOUT_S = 12
LD_JSON_TYPES = ("Church", "LocalBusiness", "Organization")


def enrich_from_website(url: str, session: Optional[requests.Session] = None) -> dict:
    """Fetches ``url`` and extracts church profile fields. Returns {} if the
    site can't be fetched or has nothing usable — enrichment is best-effort,
    it never blocks a submission from going out.
    """
    session = session or requests.Session()
    try:
        response = session.get(url, headers={"User-Agent": USER_AGENT}, timeout=REQUEST_TIMEOUT_S)
        response.raise_for_status()
    except requests.RequestException:
        return {}

    soup = BeautifulSoup(response.text, "html.parser")
    return _from_json_ld(soup) or _from_meta(soup)


def _from_json_ld(soup: BeautifulSoup) -> dict:
    for script in soup.find_all("script", attrs={"type": "application/ld+json"}):
        try:
            root = json.loads(script.string or "")
        except (json.JSONDecodeError, TypeError):
            continue

        nodes = root.get("@graph", root) if isinstance(root, dict) else root
        nodes = nodes if isinstance(nodes, list) else [nodes]

        for node in nodes:
            parsed = _parse_ld_node(node)
            if parsed:
                return parsed
    return {}


def _parse_ld_node(node: object) -> Optional[dict]:
    if not isinstance(node, dict):
        return None

    node_type = node.get("@type") or ""
    node_type = " ".join(node_type) if isinstance(node_type, list) else str(node_type)
    if not any(t in node_type for t in LD_JSON_TYPES):
        return None

    name = node.get("name")
    description = node.get("description")
    phone = node.get("telephone")

    if not any([name, description, phone]):
        return None

    address = node.get("address")
    address = address if isinstance(address, dict) else {}

    return {
        "name": name,
        "description": description,
        "phone": phone,
        "website": node.get("url"),
        "image_url": _extract_image(node.get("image")),
        "address": address.get("streetAddress"),
        "city": address.get("addressLocality"),
        "state": address.get("addressRegion"),
        "zip_code": address.get("postalCode"),
    }


def _extract_image(image: object) -> Optional[str]:
    if isinstance(image, dict):
        return image.get("url")
    if isinstance(image, list):
        return image[0] if image else None
    if isinstance(image, str):
        return image
    return None


def _from_meta(soup: BeautifulSoup) -> dict:
    name = _meta_content(soup, "og:site_name") or _meta_content(soup, "og:title")
    if not name and soup.title and soup.title.string:
        name = soup.title.string.strip()

    description = _meta_content(soup, "description") or _meta_content(soup, "og:description")
    image = _meta_content(soup, "og:image")

    if not any([name, description, image]):
        return {}

    return {"name": name, "description": description, "image_url": image}


def _meta_content(soup: BeautifulSoup, key: str) -> Optional[str]:
    tag = soup.find("meta", attrs={"property": key}) or soup.find("meta", attrs={"name": key})
    content = tag.get("content") if tag else None
    return content.strip() if content else None
