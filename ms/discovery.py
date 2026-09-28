import socket
from contextlib import contextmanager
from typing import Iterator

from zeroconf import ServiceInfo, Zeroconf, ServiceBrowser, ServiceListener

SERVICE_TYPE = "_music-syncer._tcp.local."
SERVICE_NAME = "music-syncer"


@contextmanager
def advertise(device_id: str, port: int) -> Iterator[None]:
    zc = Zeroconf()
    info = ServiceInfo(
        SERVICE_TYPE, f"{SERVICE_NAME}.{SERVICE_TYPE}",
        addresses=[socket.inet_aton("0.0.0.0")], port=port,
        properties={"device_id": device_id})
    zc.register_service(info)
    try:
        yield
    finally:
        zc.unregister_service(info)
        zc.close()


class _Listener(ServiceListener):
    def __init__(self):
        self.found: list[dict] = []

    def add_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        info = zc.get_service_info(type_, name)
        if info:
            self.found.append({
                "device_id": (info.properties.get(b"device_id") or b"").decode(),
                "address": socket.inet_ntoa(info.addresses[0]) if info.addresses else "",
                "port": info.port,
            })

    def update_service(self, zc, type_, name):  # noqa: D102
        self.add_service(zc, type_, name)

    def remove_service(self, zc, type_, name):  # noqa: D102
        pass


def discover(timeout: float = 3.0) -> list[dict]:
    zc = Zeroconf()
    listener = _Listener()
    ServiceBrowser(zc, SERVICE_TYPE, listener)
    try:
        import time
        time.sleep(timeout)
    finally:
        zc.close()
    return listener.found