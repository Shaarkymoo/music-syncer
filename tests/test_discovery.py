import ms.discovery as disc


def test_advertise_and_discover_localhost():
    with disc.advertise("laptop", 8756):
        found = disc.discover(timeout=2.0)
    assert any(f["device_id"] == "laptop" for f in found)
    laptop = next(f for f in found if f["device_id"] == "laptop")
    assert laptop["address"]
    assert laptop["address"] != "0.0.0.0"