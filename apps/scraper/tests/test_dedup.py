from scraper.dedup import existing_keys, filter_new


def test_existing_keys_normalizes_name_and_zip():
    existing = [{"name": "First Baptist Church", "zipCode": "62701"}]

    assert existing_keys(existing) == {"firstbaptistchurch|62701"}


def test_existing_keys_handles_missing_fields():
    existing = [{}]

    assert existing_keys(existing) == {"|"}


def test_filter_new_drops_matches_and_keeps_the_rest():
    discovered = [
        {"name": "First Baptist Church", "zip_code": "62701"},
        {"name": "Grace Chapel", "zip_code": "62702"},
    ]
    existing = [{"name": "first baptist church", "zipCode": "62701"}]

    result = filter_new(discovered, existing)

    assert result == [{"name": "Grace Chapel", "zip_code": "62702"}]


def test_filter_new_is_case_and_punctuation_insensitive():
    discovered = [{"name": "St. Mary's Church!", "zip_code": "10001"}]
    existing = [{"name": "st marys church", "zipCode": "10001"}]

    assert filter_new(discovered, existing) == []
