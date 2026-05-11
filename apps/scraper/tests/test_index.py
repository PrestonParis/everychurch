from scraper import index


def test_index():
    assert index.hello() == "Hello scraper"
